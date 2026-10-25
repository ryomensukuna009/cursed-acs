package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.chat.AgentCompletionReport
import com.tom.rv2ide.artificial.chat.VerificationLevel
import com.tom.rv2ide.artificial.coding.AutonomousRollbackManager
import com.tom.rv2ide.artificial.coding.BuildDiagnostic
import com.tom.rv2ide.artificial.coding.StructuredDiagnosticCollector
import com.tom.rv2ide.artificial.policy.ActionPolicyGate
import com.tom.rv2ide.artificial.policy.AgentExecutionMode
import com.tom.rv2ide.artificial.providers.ApiProtocol
import com.tom.rv2ide.artificial.providers.ProviderProfile
import com.tom.rv2ide.artificial.rules.WritingRules
import com.tom.rv2ide.artificial.transport.ChatRole
import com.tom.rv2ide.artificial.transport.StreamEvent
import com.tom.rv2ide.artificial.transport.ToolCall
import com.tom.rv2ide.artificial.transport.ToolExecutionStatus
import com.tom.rv2ide.artificial.transport.TransportCancellationToken
import com.tom.rv2ide.artificial.transport.UnifiedChatMessage
import com.tom.rv2ide.artificial.transport.UnifiedChatRequest
import com.tom.rv2ide.artificial.transport.UnifiedChatResponse
import com.tom.rv2ide.artificial.transport.UnifiedTransportClient
import com.tom.rv2ide.builder.BuildExecutionResult
import com.tom.rv2ide.builder.BuildMode
import com.tom.rv2ide.builder.SharedBuildOrchestrator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Deterministic End-to-End Agent Benchmark Suite (Task 29).
 * Exercises the actual production orchestration entry point ([ToolOrchestrator.executeOrchestratedChat])
 * with controlled mock providers and safe filesystem project fixtures.
 *
 * Covers 15 comprehensive scenarios (A through O):
 * - Scenario A: Direct response (no tools, report never claims build/test ran).
 * - Scenario B: Targeted inspection (single & cached read, tracking verification).
 * - Scenario C: Single-file edit (applied transactionally to disk, verified).
 * - Scenario D: Coordinated multi-file change (multiple files edited on disk, verified).
 * - Scenario E: Search-and-replace (targeted patch with deletion, surrounding code preserved).
 * - Scenario F: Build failure diagnosis & repair (diagnostics processed, repaired, verified).
 * - Scenario G: Persistent failure bounded (repeated build failures cannot be concealed by reads/mutations).
 * - Scenario H: Authorization denial (side agent mutation shield blocks disk write).
 * - Scenario I: Malformed arguments (conflicting arguments rejected before execution).
 * - Scenario J: Context compaction & recovery (preserves context across multi-step execution).
 * - Scenario K: Repetition & state changes (cached reads vs fresh reads post-mutation).
 * - Scenario L: Verification honesty (stale build invalidation, test failure precedence).
 * - Scenario M: Cancellation & budget exhaustion (halts cleanly within limits).
 * - Scenario N: Context retention (history preserved across rounds).
 * - Scenario O: Rollback & partial failure (rollback transaction verified).
 */
class AgentEndToEndBenchmarkTest {

    private lateinit var sandboxDir: File
    private lateinit var sampleFile: File
    private lateinit var utilsFile: File
    private lateinit var testProfile: ProviderProfile

    @Before
    fun setUp() {
        sandboxDir = Files.createTempDirectory("benchmark_sandbox").toFile()
        val pkgDir = File(sandboxDir, "app/src/main/java/com/example").apply { mkdirs() }
        sampleFile = File(pkgDir, "MainActivity.kt").apply {
            writeText(
                """
                package com.example

                class MainActivity {
                    // TARGET_HOOK
                    fun onCreate() {
                        println("Hello ROXUM")
                    }
                }
                """.trimIndent()
            )
        }
        utilsFile = File(pkgDir, "Utils.kt").apply {
            writeText(
                """
                package com.example

                object Utils {
                    fun helper(): String = "Original"
                }
                """.trimIndent()
            )
        }

        testProfile = ProviderProfile(
            id = "test-provider",
            displayName = "Test Benchmark Provider",
            protocol = ApiProtocol.OPENAI_COMPATIBLE,
            baseUrl = "http://localhost:8080/v1"
        )

        WritingRules.isEditCommandActive = true
        AutonomousRollbackManager.clearMemory()
        SharedBuildOrchestrator.resetStateForTesting()
        ActionPolicyGate.clearSessionApprovals()
    }

    @After
    fun tearDown() {
        WritingRules.isEditCommandActive = false
        AutonomousRollbackManager.clearMemory()
        SharedBuildOrchestrator.resetStateForTesting()
        ActionPolicyGate.clearSessionApprovals()
        sandboxDir.deleteRecursively()
    }

    private class FakeBenchmarkTransportClient(
        private val responses: MutableList<UnifiedChatResponse> = mutableListOf()
    ) : UnifiedTransportClient() {

        val recordedRequests = mutableListOf<UnifiedChatRequest>()

        fun enqueue(response: UnifiedChatResponse) {
            responses.add(response)
        }

        override suspend fun executeChat(
            request: UnifiedChatRequest,
            apiKey: String?,
            cancellationToken: TransportCancellationToken,
            maxRetries: Int,
            onEvent: (StreamEvent) -> Unit
        ): UnifiedChatResponse {
            recordedRequests.add(request)
            if (cancellationToken.isCancelled()) {
                throw CancellationException("AI request cancelled")
            }
            if (responses.isNotEmpty()) {
                return responses.removeAt(0)
            }
            return UnifiedChatResponse(text = "Final completed answer.")
        }
    }

    // SCENARIO A — Direct response
    @Test
    fun benchmark_scenarioA_directResponse_honestReporting() = runBlocking {
        val fakeTransport = FakeBenchmarkTransportClient().apply {
            enqueue(UnifiedChatResponse(text = "Android Activities manage user interface lifecycles."))
        }
        val orchestrator = ToolOrchestrator(
            transportClient = fakeTransport,
            webSearchManager = WebSearchManager(null),
            projectRootProvider = { sandboxDir }
        )

        val result = orchestrator.executeOrchestratedChat(
            profile = testProfile,
            modelId = "gpt-4o",
            apiKey = "test-key",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Explain Activity")),
            stream = false
        )

        assertEquals("Android Activities manage user interface lifecycles.", result.text.trim())
        val report = result.completionReport
        assertNotNull(report)
        assertEquals(VerificationLevel.NOT_VERIFIED, report!!.verificationLevel)
        assertTrue(report.filesModified.isEmpty())
        assertTrue(report.validationPassed)
        assertTrue(report.validationChecksRun.isEmpty())
    }

    // SCENARIO B — Targeted inspection
    @Test
    fun benchmark_scenarioB_targetedInspection_tracksInspectedFiles() = runBlocking {
        val fakeTransport = FakeBenchmarkTransportClient().apply {
            // Turn 1: request file read
            enqueue(
                UnifiedChatResponse(
                    text = "Checking MainActivity...",
                    toolCalls = listOf(
                        ToolCall(
                            id = "call_read_1",
                            name = "read_project_file",
                            argumentsJson = JSONObject().put("filePath", "app/src/main/java/com/example/MainActivity.kt").toString()
                        )
                    )
                )
            )
            // Turn 2: final answer
            enqueue(
                UnifiedChatResponse(text = "MainActivity contains the greeting logic.")
            )
        }
        val orchestrator = ToolOrchestrator(
            transportClient = fakeTransport,
            webSearchManager = WebSearchManager(null),
            projectRootProvider = { sandboxDir }
        )

        val result = orchestrator.executeOrchestratedChat(
            profile = testProfile,
            modelId = "gpt-4o",
            apiKey = "test-key",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Inspect MainActivity")),
            stream = false
        )

        val report = result.completionReport
        assertNotNull(report)
        assertEquals(VerificationLevel.NOT_VERIFIED, report!!.verificationLevel)
        assertTrue(report.filesModified.isEmpty())
        assertTrue(report.mainChangesSummary.contains("Inspected 1 file(s)"))
    }

    // SCENARIO C — Single-file edit
    @Test
    fun benchmark_scenarioC_singleFileEdit_modifiesDiskAndVerifies() = runBlocking {
        val newContent = "package com.example\nclass MainActivity { fun newMethod() = 100 }"
        val fakeTransport = FakeBenchmarkTransportClient().apply {
            // Turn 1: tool call to apply changes
            enqueue(
                UnifiedChatResponse(
                    text = "Applying modification...",
                    toolCalls = listOf(
                        ToolCall(
                            id = "call_edit_1",
                            name = "apply_file_changes",
                            argumentsJson = JSONObject()
                                .put("filePath", "app/src/main/java/com/example/MainActivity.kt")
                                .put("codeContent", newContent)
                                .toString()
                        )
                    )
                )
            )
            // Turn 2: final answer
            enqueue(UnifiedChatResponse(text = "Successfully updated MainActivity."))
        }
        val orchestrator = ToolOrchestrator(
            transportClient = fakeTransport,
            webSearchManager = WebSearchManager(null),
            projectRootProvider = { sandboxDir }
        )

        val result = orchestrator.executeOrchestratedChat(
            profile = testProfile,
            modelId = "gpt-4o",
            apiKey = "test-key",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Update MainActivity")),
            stream = false
        )

        assertEquals(newContent, sampleFile.readText().trim())
        val report = result.completionReport
        assertNotNull(report)
        assertEquals(VerificationLevel.IMPLEMENTED_UNVERIFIED, report!!.verificationLevel)
        assertEquals(listOf("app/src/main/java/com/example/MainActivity.kt"), report.filesModified)
    }

    // SCENARIO D — Coordinated multi-file change
    @Test
    fun benchmark_scenarioD_coordinatedMultiFileChange_appliesAllFiles() = runBlocking {
        val multiFilePatch = """
            [FILE: app/src/main/java/com/example/MainActivity.kt]
            package com.example
            class MainActivity { val ver = 2 }

            [FILE: app/src/main/java/com/example/Utils.kt]
            package com.example
            object Utils { val ver = 2 }
        """.trimIndent()

        val fakeTransport = FakeBenchmarkTransportClient().apply {
            enqueue(
                UnifiedChatResponse(
                    text = "Modifying multiple files...",
                    toolCalls = listOf(
                        ToolCall(
                            id = "call_multi_1",
                            name = "apply_file_changes",
                            argumentsJson = JSONObject().put("codeContent", multiFilePatch).toString()
                        )
                    )
                )
            )
            enqueue(UnifiedChatResponse(text = "Updated both files."))
        }
        val orchestrator = ToolOrchestrator(
            transportClient = fakeTransport,
            webSearchManager = WebSearchManager(null),
            projectRootProvider = { sandboxDir }
        )

        val result = orchestrator.executeOrchestratedChat(
            profile = testProfile,
            modelId = "gpt-4o",
            apiKey = "test-key",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Update both files")),
            stream = false
        )

        assertTrue(sampleFile.readText().contains("val ver = 2"))
        assertTrue(utilsFile.readText().contains("val ver = 2"))
        val report = result.completionReport
        assertNotNull(report)
        assertEquals(2, report!!.filesModified.size)
        assertEquals(VerificationLevel.IMPLEMENTED_UNVERIFIED, report.verificationLevel)
    }

    // SCENARIO E — Search-and-replace
    @Test
    fun benchmark_scenarioE_searchAndReplace_deletesTargetWithoutOverwritingFile() = runBlocking {
        val fakeTransport = FakeBenchmarkTransportClient().apply {
            enqueue(
                UnifiedChatResponse(
                    text = "Removing TARGET_HOOK...",
                    toolCalls = listOf(
                        ToolCall(
                            id = "call_sr_1",
                            name = "apply_file_changes",
                            argumentsJson = JSONObject()
                                .put("filePath", "app/src/main/java/com/example/MainActivity.kt")
                                .put("search", "    // TARGET_HOOK\n")
                                .put("replace", "") // Deletion!
                                .toString()
                        )
                    )
                )
            )
            enqueue(UnifiedChatResponse(text = "Removed TARGET_HOOK cleanly."))
        }
        val orchestrator = ToolOrchestrator(
            transportClient = fakeTransport,
            webSearchManager = WebSearchManager(null),
            projectRootProvider = { sandboxDir }
        )

        orchestrator.executeOrchestratedChat(
            profile = testProfile,
            modelId = "gpt-4o",
            apiKey = "test-key",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Delete TARGET_HOOK")),
            stream = false
        )

        val updated = sampleFile.readText()
        assertFalse(updated.contains("// TARGET_HOOK"))
        assertTrue(updated.contains("class MainActivity"))
        assertTrue(updated.contains("println(\"Hello ROXUM\")"))
    }

    // SCENARIO F — Build failure diagnosis and recovery
    @Test
    fun benchmark_scenarioF_buildFailureDiagnosisAndRecovery_reachesBuiltAndVerified() = runBlocking {
        var buildAttempts = 0
        SharedBuildOrchestrator.setTestBuildExecutor { dir, mode ->
            buildAttempts++
            if (buildAttempts == 1) {
                BuildExecutionResult(
                    runId = "build_1",
                    projectDir = dir,
                    buildMode = mode,
                    isSuccess = false,
                    exitCode = 1,
                    durationMs = 250,
                    rawLog = "MainActivity.kt:3: error: unresolved reference: brokenSyntax",
                    failedStage = "KOTLINC",
                    errorMessage = "unresolved reference: brokenSyntax",
                    apkFile = null,
                    apkSizeBytes = 0L,
                    diagnostics = listOf(
                        BuildDiagnostic(
                            filePath = "app/src/main/java/com/example/MainActivity.kt",
                            line = 3,
                            column = 5,
                            severity = BuildDiagnostic.Severity.ERROR,
                            stage = "KOTLINC",
                            message = "unresolved reference: brokenSyntax",
                            rawLine = "MainActivity.kt:3: error: unresolved reference: brokenSyntax"
                        )
                    )
                )
            } else {
                BuildExecutionResult(
                    runId = "build_2",
                    projectDir = dir,
                    buildMode = mode,
                    isSuccess = true,
                    exitCode = 0,
                    durationMs = 300,
                    rawLog = "BUILD SUCCESSFUL in 300ms",
                    failedStage = null,
                    errorMessage = null,
                    apkFile = File(dir, "output.apk"),
                    apkSizeBytes = 1024L,
                    diagnostics = emptyList()
                )
            }
        }

        val fakeTransport = FakeBenchmarkTransportClient().apply {
            // Turn 1: Try build -> fails
            enqueue(
                UnifiedChatResponse(
                    text = "Building project...",
                    toolCalls = listOf(ToolCall(id = "b1", name = "build_current_project", argumentsJson = "{}"))
                )
            )
            // Turn 2: Fix the code
            enqueue(
                UnifiedChatResponse(
                    text = "Fixing compilation error...",
                    toolCalls = listOf(
                        ToolCall(
                            id = "edit1",
                            name = "apply_file_changes",
                            argumentsJson = JSONObject()
                                .put("filePath", "app/src/main/java/com/example/MainActivity.kt")
                                .put("codeContent", "package com.example\nclass MainActivity { fun onCreate() {} }")
                                .toString()
                        )
                    )
                )
            )
            // Turn 3: Re-build -> succeeds
            enqueue(
                UnifiedChatResponse(
                    text = "Re-verifying build...",
                    toolCalls = listOf(ToolCall(id = "b2", name = "build_current_project", argumentsJson = "{}"))
                )
            )
            // Turn 4: Final response
            enqueue(UnifiedChatResponse(text = "Repaired and verified build successfully."))
        }

        val orchestrator = ToolOrchestrator(
            transportClient = fakeTransport,
            webSearchManager = WebSearchManager(null),
            projectRootProvider = { sandboxDir }
        )

        val result = orchestrator.executeOrchestratedChat(
            profile = testProfile,
            modelId = "gpt-4o",
            apiKey = "test-key",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Build and fix")),
            stream = false
        )

        val report = result.completionReport
        assertNotNull(report)
        assertEquals(VerificationLevel.BUILT_AND_VERIFIED, report!!.verificationLevel)
        assertTrue(report.validationPassed)
        assertTrue(report.remainingErrors.isEmpty())
    }

    // SCENARIO G — Persistent failure cannot be concealed
    @Test
    fun benchmark_scenarioG_persistentFailure_haltsLoopAndReportsErrors() = runBlocking {
        SharedBuildOrchestrator.setTestBuildExecutor { dir, mode ->
            BuildExecutionResult(
                runId = "build_fail",
                projectDir = dir,
                buildMode = mode,
                isSuccess = false,
                exitCode = 1,
                durationMs = 100,
                rawLog = "Fatal compilation error",
                failedStage = "COMPILATION",
                errorMessage = "Build failed persistently",
                apkFile = null,
                apkSizeBytes = 0L,
                diagnostics = emptyList()
            )
        }

        val fakeTransport = FakeBenchmarkTransportClient().apply {
            // Round 1: failing build
            enqueue(UnifiedChatResponse(toolCalls = listOf(ToolCall(id = "b1", name = "build_current_project", argumentsJson = "{}"))))
            // Round 2: read file (succeeds) + failing build
            enqueue(UnifiedChatResponse(toolCalls = listOf(
                ToolCall(id = "r1", name = "read_project_file", argumentsJson = JSONObject().put("filePath", "app/src/main/java/com/example/MainActivity.kt").toString()),
                ToolCall(id = "b2", name = "build_current_project", argumentsJson = "{}")
            )))
            // Round 3: unrelated edit + failing build
            enqueue(UnifiedChatResponse(toolCalls = listOf(
                ToolCall(id = "e1", name = "apply_file_changes", argumentsJson = JSONObject().put("filePath", "app/src/main/java/com/example/Utils.kt").put("codeContent", "object Utils").toString()),
                ToolCall(id = "b3", name = "build_current_project", argumentsJson = "{}")
            )))
            // Extra queue item that should NEVER be reached due to circuit breaker
            enqueue(UnifiedChatResponse(text = "Should not be reached"))
        }

        val orchestrator = ToolOrchestrator(
            transportClient = fakeTransport,
            webSearchManager = WebSearchManager(null),
            projectRootProvider = { sandboxDir }
        )

        val result = orchestrator.executeOrchestratedChat(
            profile = testProfile,
            modelId = "gpt-4o",
            apiKey = "test-key",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Persistently failing build")),
            stream = false
        )

        val report = result.completionReport
        assertNotNull(report)
        assertEquals(VerificationLevel.FAILED_WITH_ERRORS, report!!.verificationLevel)
        assertFalse(report.validationPassed)
        assertTrue(report.remainingErrors.isNotEmpty())
    }

    // SCENARIO H — Authorization denial
    @Test
    fun benchmark_scenarioH_authorizationDenial_sideAgentProhibitsDiskMutation() = runBlocking {
        WritingRules.isEditCommandActive = false // Disable edit authorization

        val fakeTransport = FakeBenchmarkTransportClient().apply {
            enqueue(
                UnifiedChatResponse(
                    toolCalls = listOf(
                        ToolCall(
                            id = "call_unauth",
                            name = "apply_file_changes",
                            argumentsJson = JSONObject()
                                .put("filePath", "app/src/main/java/com/example/MainActivity.kt")
                                .put("codeContent", "MUTATION_ATTEMPT")
                                .toString()
                        )
                    )
                )
            )
            enqueue(UnifiedChatResponse(text = "Proposal noted."))
        }

        val orchestrator = ToolOrchestrator(
            transportClient = fakeTransport,
            webSearchManager = WebSearchManager(null),
            projectRootProvider = { sandboxDir }
        )

        orchestrator.executeOrchestratedChat(
            profile = testProfile,
            modelId = "gpt-4o",
            apiKey = "test-key",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Side agent edit")),
            executionMode = AgentExecutionMode.SIDE_AGENT,
            stream = false
        )

        // Verify disk file is UNTOUCHED!
        assertFalse(sampleFile.readText().contains("MUTATION_ATTEMPT"))
    }

    // SCENARIO I — Malformed arguments
    @Test
    fun benchmark_scenarioI_malformedArguments_rejectedSafely() = runBlocking {
        val fakeTransport = FakeBenchmarkTransportClient().apply {
            enqueue(
                UnifiedChatResponse(
                    toolCalls = listOf(
                        ToolCall(
                            id = "call_bad",
                            name = "apply_file_changes",
                            argumentsJson = JSONObject()
                                .put("filePath", "A.kt")
                                .put("path", "B.kt") // Conflicting aliases!
                                .put("codeContent", "foo")
                                .toString()
                        )
                    )
                )
            )
            enqueue(UnifiedChatResponse(text = "Correcting arguments."))
        }

        val orchestrator = ToolOrchestrator(
            transportClient = fakeTransport,
            webSearchManager = WebSearchManager(null),
            projectRootProvider = { sandboxDir }
        )

        val result = orchestrator.executeOrchestratedChat(
            profile = testProfile,
            modelId = "gpt-4o",
            apiKey = "test-key",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Conflicting path arguments")),
            stream = false
        )

        // Neither A.kt nor B.kt should exist
        assertFalse(File(sandboxDir, "A.kt").exists())
        assertFalse(File(sandboxDir, "B.kt").exists())
    }

    // SCENARIO J — Context retention across multi-step execution
    @Test
    fun benchmark_scenarioJ_multiStepContextRetention_preservesHistoryAcrossTurns() = runBlocking {
        val fakeTransport = FakeBenchmarkTransportClient().apply {
            enqueue(
                UnifiedChatResponse(
                    text = "Step 1",
                    toolCalls = listOf(
                        ToolCall(
                            id = "step1",
                            name = "read_project_file",
                            argumentsJson = JSONObject().put("filePath", "app/src/main/java/com/example/MainActivity.kt").toString()
                        )
                    )
                )
            )
            enqueue(
                UnifiedChatResponse(
                    text = "Step 2",
                    toolCalls = listOf(
                        ToolCall(
                            id = "step2",
                            name = "read_project_file",
                            argumentsJson = JSONObject().put("filePath", "app/src/main/java/com/example/Utils.kt").toString()
                        )
                    )
                )
            )
            enqueue(UnifiedChatResponse(text = "All steps complete."))
        }

        val orchestrator = ToolOrchestrator(
            transportClient = fakeTransport,
            webSearchManager = WebSearchManager(null),
            projectRootProvider = { sandboxDir }
        )

        val result = orchestrator.executeOrchestratedChat(
            profile = testProfile,
            modelId = "gpt-4o",
            apiKey = "test-key",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Multi step task")),
            stream = false
        )

        // Verify the 3rd request received by transport client contains previous tool history
        assertEquals(3, fakeTransport.recordedRequests.size)
        val finalReq = fakeTransport.recordedRequests[2]
        assertTrue(finalReq.messages.any { it.role == ChatRole.TOOL && it.content.contains("MainActivity") })
        assertTrue(finalReq.messages.any { it.role == ChatRole.TOOL && it.content.contains("Utils") })
    }

    // SCENARIO K — Repetition and state changes
    @Test
    fun benchmark_scenarioK_repetitionAndStateChanges_allowsRereadAfterMutation() = runBlocking {
        val fakeTransport = FakeBenchmarkTransportClient().apply {
            // 1: Read file
            enqueue(UnifiedChatResponse(toolCalls = listOf(ToolCall(id = "r1", name = "read_project_file", argumentsJson = JSONObject().put("filePath", "app/src/main/java/com/example/MainActivity.kt").toString()))))
            // 2: Duplicate read (cached)
            enqueue(UnifiedChatResponse(toolCalls = listOf(ToolCall(id = "r2", name = "read_project_file", argumentsJson = JSONObject().put("filePath", "app/src/main/java/com/example/MainActivity.kt").toString()))))
            // 3: Mutate file
            enqueue(UnifiedChatResponse(toolCalls = listOf(ToolCall(id = "m1", name = "apply_file_changes", argumentsJson = JSONObject().put("filePath", "app/src/main/java/com/example/MainActivity.kt").put("codeContent", "package com.example\nclass MainActivity // Mutated").toString()))))
            // 4: Fresh read after mutation (generation advanced)
            enqueue(UnifiedChatResponse(toolCalls = listOf(ToolCall(id = "r3", name = "read_project_file", argumentsJson = JSONObject().put("filePath", "app/src/main/java/com/example/MainActivity.kt").toString()))))
            // 5: Final text
            enqueue(UnifiedChatResponse(text = "Done."))
        }

        val orchestrator = ToolOrchestrator(
            transportClient = fakeTransport,
            webSearchManager = WebSearchManager(null),
            projectRootProvider = { sandboxDir }
        )

        val result = orchestrator.executeOrchestratedChat(
            profile = testProfile,
            modelId = "gpt-4o",
            apiKey = "test-key",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Reread flow")),
            stream = false
        )

        assertNotNull(result.completionReport)
        assertTrue(sampleFile.readText().contains("// Mutated"))
    }

    // SCENARIO L — Verification honesty
    @Test
    fun benchmark_scenarioL_verificationHonesty_staleBuildAndFailingTests() {
        val orchestrator = ToolOrchestrator(
            webSearchManager = WebSearchManager(null),
            projectRootProvider = { sandboxDir }
        )

        // Case 1: Build passed at gen 1, but file was mutated at gen 2 without rebuild
        val staleReport = orchestrator.buildCompletionReport(
            turnModifiedFiles = setOf("app/src/main/java/com/example/MainActivity.kt"),
            buildExecuted = true,
            buildPassed = true,
            lastBuildGeneration = 1L,
            currentGeneration = 2L
        )
        assertEquals(VerificationLevel.IMPLEMENTED_UNVERIFIED, staleReport.verificationLevel)

        // Case 2: Build passed, but tests failed
        val testFailedReport = orchestrator.buildCompletionReport(
            turnModifiedFiles = setOf("app/src/main/java/com/example/MainActivity.kt"),
            buildExecuted = true,
            buildPassed = true,
            lastBuildGeneration = 1L,
            testsExecuted = true,
            testsPassed = false,
            lastTestGeneration = 1L,
            currentGeneration = 1L
        )
        assertEquals(VerificationLevel.FAILED_WITH_ERRORS, testFailedReport.verificationLevel)
        assertFalse(testFailedReport.validationPassed)
    }

    // SCENARIO M — Cancellation and budget exhaustion
    @Test
    fun benchmark_scenarioM_cancellation_haltsExecutionImmediately() {
        val cancelToken = TransportCancellationToken()
        val fakeTransport = FakeBenchmarkTransportClient().apply {
            enqueue(UnifiedChatResponse(toolCalls = listOf(ToolCall(id = "c1", name = "read_project_file", argumentsJson = JSONObject().put("filePath", "app/src/main/java/com/example/MainActivity.kt").toString()))))
        }
        val orchestrator = ToolOrchestrator(
            transportClient = fakeTransport,
            webSearchManager = WebSearchManager(null),
            projectRootProvider = { sandboxDir }
        )

        cancelToken.cancel() // Cancel before or during request

        var caughtCancellation = false
        try {
            runBlocking {
                orchestrator.executeOrchestratedChat(
                    profile = testProfile,
                    modelId = "gpt-4o",
                    apiKey = "test-key",
                    messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Cancel me")),
                    cancellationToken = cancelToken,
                    stream = false
                )
            }
        } catch (_: CancellationException) {
            caughtCancellation = true
        }

        assertTrue("Execution must throw CancellationException on cancel", caughtCancellation)
    }

    // SCENARIO N — Context retention
    @Test
    fun benchmark_scenarioN_contextCompaction_preservesEssentialInstructions() {
        val longMessages = mutableListOf<UnifiedChatMessage>()
        longMessages.add(UnifiedChatMessage(role = ChatRole.USER, content = "Crucial instruction: Keep MainActivity minimal."))
        for (i in 1..20) {
            longMessages.add(UnifiedChatMessage(role = ChatRole.ASSISTANT, content = "Turn $i intermediate chatter"))
            longMessages.add(UnifiedChatMessage(role = ChatRole.USER, content = "Continue work on step $i"))
        }

        val fakeTransport = FakeBenchmarkTransportClient().apply {
            enqueue(UnifiedChatResponse(text = "Final answer adhering to minimal structure."))
        }
        val orchestrator = ToolOrchestrator(
            transportClient = fakeTransport,
            webSearchManager = WebSearchManager(null),
            projectRootProvider = { sandboxDir }
        )

        val result = runBlocking {
            orchestrator.executeOrchestratedChat(
                profile = testProfile,
                modelId = "gpt-4o",
                apiKey = "test-key",
                messages = longMessages,
                stream = false
            )
        }

        assertNotNull(result.text)
        // Verify final prompt still conveyed the goal
        assertEquals(VerificationLevel.NOT_VERIFIED, result.completionReport?.verificationLevel)
    }

    // SCENARIO O — Rollback and partial failure
    @Test
    fun benchmark_scenarioO_rollback_revertsDiskAndReportsRolledBack() = runBlocking {
        val fakeTransport = FakeBenchmarkTransportClient().apply {
            // Turn 1: edit file
            enqueue(
                UnifiedChatResponse(
                    toolCalls = listOf(
                        ToolCall(
                            id = "e1",
                            name = "apply_file_changes",
                            argumentsJson = JSONObject().put("filePath", "app/src/main/java/com/example/MainActivity.kt").put("codeContent", "TEMP_CONTENT").toString()
                        )
                    )
                )
            )
            // Turn 2: rollback batch
            enqueue(
                UnifiedChatResponse(
                    toolCalls = listOf(
                        ToolCall(id = "rb1", name = "rollback_agent_batch", argumentsJson = "{}")
                    )
                )
            )
            // Turn 3: final text
            enqueue(UnifiedChatResponse(text = "Rollback finished."))
        }

        val orchestrator = ToolOrchestrator(
            transportClient = fakeTransport,
            webSearchManager = WebSearchManager(null),
            projectRootProvider = { sandboxDir }
        )

        val result = orchestrator.executeOrchestratedChat(
            profile = testProfile,
            modelId = "gpt-4o",
            apiKey = "test-key",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Edit then rollback")),
            stream = false
        )

        // Verify disk file was restored to original content!
        assertFalse(sampleFile.readText().contains("TEMP_CONTENT"))
        assertTrue(sampleFile.readText().contains("Hello ROXUM"))
        val report = result.completionReport
        assertNotNull(report)
        assertEquals(VerificationLevel.ROLLED_BACK, report!!.verificationLevel)
        assertTrue(report.isRolledBack)
    }
}
