package com.tom.rv2ide.artificial.coding

import com.tom.rv2ide.artificial.chat.VerificationLevel
import com.tom.rv2ide.builder.BuildExecutionResult
import com.tom.rv2ide.builder.BuildMode
import com.tom.rv2ide.builder.SharedBuildOrchestrator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Phase 8.6: Autonomous Tool-to-Build Verification & Diagnostic Error Window Augmentation Tests.
 */
class Phase86AutonomousVerificationTest {

    private lateinit var testProjectRoot: File

    @Before
    fun setUp() {
        testProjectRoot = Files.createTempDirectory("acs_phase86_test").toFile()
        AutonomousRollbackManager.clearMemory()
        SharedBuildOrchestrator.resetStateForTesting()
    }

    @After
    fun tearDown() {
        AutonomousRollbackManager.clearMemory()
        SharedBuildOrchestrator.resetStateForTesting()
        testProjectRoot.deleteRecursively()
    }

    @Test
    fun `test 1 - extractSourceContext extracts surrounding lines and points to target line`() {
        val srcFile = File(testProjectRoot, "app/src/main/cpp/Main.cpp").apply {
            parentFile?.mkdirs()
            writeText(
                """
                #include <iostream>
                void first() {}
                void second() {}
                void third() {}
                void errorTarget() {
                    syntax_error_here();
                }
                void fifth() {}
                void sixth() {}
                void seventh() {}
                """.trimIndent()
            )
        }

        val diag = BuildDiagnostic(
            filePath = "app/src/main/cpp/Main.cpp",
            line = 6,
            column = 5,
            severity = BuildDiagnostic.Severity.ERROR,
            stage = "NDK_BUILD",
            message = "use of undeclared identifier 'syntax_error_here'",
            rawLine = "app/src/main/cpp/Main.cpp:6:5: error: use of undeclared identifier 'syntax_error_here'"
        )

        val snippet = StructuredDiagnosticCollector.extractSourceContext(
            diagnostic = diag,
            projectRoot = testProjectRoot,
            windowRadius = 2
        )

        assertNotNull("Snippet should not be null", snippet)
        assertTrue("Snippet must mention file and line", snippet!!.contains("Main.cpp around line 6"))
        assertTrue("Snippet must mark target line with >", snippet.contains(">    6 |     syntax_error_here();"))
        assertTrue("Snippet must include surrounding line 4", snippet.contains("4 | void third() {}"))
        assertTrue("Snippet must include surrounding line 8", snippet.contains("8 | void fifth() {}"))
    }

    @Test
    fun `test 2 - extractSourceContext handles boundary conditions safely`() {
        val srcFile = File(testProjectRoot, "SingleLine.kt").apply {
            writeText("val x = 42")
        }

        val diagLine1 = BuildDiagnostic(
            filePath = "SingleLine.kt",
            line = 1,
            column = 1,
            severity = BuildDiagnostic.Severity.ERROR,
            stage = "KOTLINC",
            message = "Syntax error",
            rawLine = "SingleLine.kt:1:1: error: Syntax error"
        )

        val snippet1 = StructuredDiagnosticCollector.extractSourceContext(
            diagnostic = diagLine1,
            projectRoot = testProjectRoot,
            windowRadius = 5
        )
        assertNotNull(snippet1)
        assertTrue(snippet1!!.contains(">    1 | val x = 42"))

        // Non-existent file
        val diagMissing = BuildDiagnostic(
            filePath = "DoesNotExist.kt",
            line = 10,
            column = null,
            severity = BuildDiagnostic.Severity.ERROR,
            stage = "KOTLINC",
            message = "Unresolved",
            rawLine = "DoesNotExist.kt:10: error"
        )
        assertNull(StructuredDiagnosticCollector.extractSourceContext(diagMissing, testProjectRoot))

        // Null line
        val diagNoLine = BuildDiagnostic(
            filePath = "SingleLine.kt",
            line = null,
            column = null,
            severity = BuildDiagnostic.Severity.ERROR,
            stage = "KOTLINC",
            message = "General error",
            rawLine = "SingleLine.kt: error"
        )
        assertNull(StructuredDiagnosticCollector.extractSourceContext(diagNoLine, testProjectRoot))
    }

    @Test
    fun `test 3 - buildAugmentedErrorContext formats multiple diagnostics with deduplication and cap`() {
        val file1 = File(testProjectRoot, "File1.kt").apply {
            writeText("fun a() {}\nfun b() { broken() }\nfun c() {}\n")
        }
        val file2 = File(testProjectRoot, "File2.kt").apply {
            writeText("class Foo {\n    val y = unknownVar\n}\n")
        }

        val diags = listOf(
            BuildDiagnostic("File1.kt", 2, 11, BuildDiagnostic.Severity.ERROR, "KOTLINC", "unresolved reference: broken", ""),
            BuildDiagnostic("File1.kt", 2, 11, BuildDiagnostic.Severity.ERROR, "KOTLINC", "unresolved reference: broken duplicate", ""),
            BuildDiagnostic("File2.kt", 2, 13, BuildDiagnostic.Severity.ERROR, "KOTLINC", "unresolved reference: unknownVar", "")
        )

        val augmented = StructuredDiagnosticCollector.buildAugmentedErrorContext(
            diagnostics = diags,
            projectRoot = testProjectRoot,
            maxSnippets = 5,
            windowRadius = 2
        )

        assertTrue(augmented.isNotBlank())
        assertTrue("Must include File1.kt context", augmented.contains("File1.kt around line 2"))
        assertTrue("Must include File2.kt context", augmented.contains("File2.kt around line 2"))
        // Check deduplication of same file:line
        val countFile1 = augmented.split("File1.kt around line 2").size - 1
        assertEquals("Should only include File1.kt:2 once due to deduplication", 1, countFile1)
    }

    @Test
    fun `test 4 - runAutonomousEngineeringLoop with preModifiedFiles immediately verifies build on disk`() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        val modifiedFile = File(testProjectRoot, "app/src/main/java/com/example/MainActivity.kt").apply {
            parentFile?.mkdirs()
            writeText("// Verified tool modification")
        }

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, modifiedFile, "app/src/main/java/com/example/MainActivity.kt")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, modifiedFile, "app/src/main/java/com/example/MainActivity.kt", modifiedFile.readText())

        // Set simulated successful build in SharedBuildOrchestrator
        val fakeApk = File(testProjectRoot, "app-debug.apk").apply { writeText("fake-apk") }
        SharedBuildOrchestrator.setTestBuildExecutor { dir, mode ->
            BuildExecutionResult(
                runId = "test-run",
                projectDir = dir,
                buildMode = BuildMode.OFFLINE_NDK,
                isSuccess = true,
                exitCode = 0,
                durationMs = 120,
                rawLog = "BUILD SUCCESSFUL",
                failedStage = null,
                errorMessage = null,
                apkFile = fakeApk,
                apkSizeBytes = 100L,
                diagnostics = emptyList()
            )
        }

        val summary = AutonomousExecutionCoordinator.runAutonomousEngineeringLoop(
            projectRoot = testProjectRoot,
            userGoal = "Add switch in MainActivity",
            initialCodeCandidate = null,
            codeGenerator = { null },
            preModifiedFiles = listOf("app/src/main/java/com/example/MainActivity.kt"),
            existingSessionId = sessionId
        )

        assertTrue("Summary must be success", summary.isSuccess)
        assertEquals(AutonomousExecutionState.BUILD_SUCCEEDED, summary.finalState)
        assertEquals(VerificationLevel.BUILT_AND_VERIFIED, summary.report.verificationLevel)
        assertEquals(listOf("app/src/main/java/com/example/MainActivity.kt"), summary.report.filesModified)
        assertEquals(sessionId, summary.report.rollbackSessionId)
        assertTrue(summary.report.validationChecksRun.contains("Actual build execution (OFFLINE_NDK)"))
        assertTrue(summary.report.validationChecksRun.contains("File change-set transaction applied"))
    }

    @Test
    fun `test 5 - runAutonomousEngineeringLoop with preModifiedFiles repairs failing build`() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        val modifiedFile = File(testProjectRoot, "Broken.kt").apply {
            writeText("class Broken { error_here }")
        }

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, modifiedFile, "Broken.kt")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, modifiedFile, "Broken.kt", modifiedFile.readText())

        var buildCount = 0
        val fakeApk = File(testProjectRoot, "fixed-debug.apk").apply { writeText("apk") }

        // Configure mock build result dynamically: first build fails, second build succeeds after repair
        SharedBuildOrchestrator.setTestBuildExecutor { dir, mode ->
            buildCount++
            if (buildCount == 1) {
                BuildExecutionResult(
                    runId = "test-fail",
                    projectDir = dir,
                    buildMode = BuildMode.OFFLINE_NDK,
                    isSuccess = false,
                    exitCode = 1,
                    durationMs = 150,
                    rawLog = "Broken.kt:1:16: error: expecting member declaration",
                    failedStage = "COMPILER",
                    errorMessage = "Syntax error in Broken.kt",
                    apkFile = null,
                    apkSizeBytes = 0L,
                    diagnostics = listOf(
                        BuildDiagnostic("Broken.kt", 1, 16, BuildDiagnostic.Severity.ERROR, "KOTLINC", "expecting member declaration", "Broken.kt:1:16: error: expecting member declaration")
                    )
                )
            } else {
                BuildExecutionResult(
                    runId = "test-pass",
                    projectDir = dir,
                    buildMode = BuildMode.OFFLINE_NDK,
                    isSuccess = true,
                    exitCode = 0,
                    durationMs = 120,
                    rawLog = "BUILD SUCCESSFUL",
                    failedStage = null,
                    errorMessage = null,
                    apkFile = fakeApk,
                    apkSizeBytes = 100L,
                    diagnostics = emptyList()
                )
            }
        }

        var generatorPromptReceived = ""
        val summary = AutonomousExecutionCoordinator.runAutonomousEngineeringLoop(
            projectRoot = testProjectRoot,
            userGoal = "Fix syntax error",
            initialCodeCandidate = null,
            codeGenerator = { prompt ->
                generatorPromptReceived = prompt
                // Return fixed content
                """
                FILE_TO_MODIFY: Broken.kt
                CODE_START
                class Broken { /* fixed */ }
                CODE_END
                """.trimIndent()
            },
            preModifiedFiles = listOf("Broken.kt"),
            existingSessionId = sessionId
        )

        assertTrue("Loop must succeed after repair wave", summary.isSuccess)
        assertEquals(AutonomousExecutionState.BUILD_SUCCEEDED, summary.finalState)
        assertEquals(1, summary.wavesExecuted)
        assertTrue("Generator prompt must have received augmented source window", generatorPromptReceived.contains("Broken.kt around line 1"))
        assertTrue("Generator prompt must have pointed to error line", generatorPromptReceived.contains(">    1 | class Broken { error_here }"))
        assertEquals("class Broken { /* fixed */ }", modifiedFile.readText().trim())
    }

    @Test
    fun `test 6 - rollback preserves preModifiedFiles and repairs under shared session`() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        val originalText = "original content before any agent changes"
        val testFile = File(testProjectRoot, "Target.kt").apply {
            writeText(originalText)
        }

        // Tool edit
        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, testFile, "Target.kt")
        testFile.writeText("tool edit content")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, testFile, "Target.kt", "tool edit content")

        // Repair wave edit using same sessionId
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, testFile, "Target.kt")
        testFile.writeText("wave 1 repair content")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, testFile, "Target.kt", "wave 1 repair content")

        // Now rollback session
        val rollbackResult = AutonomousRollbackManager.rollbackSession(
            sessionId = sessionId,
            projectRootOverride = testProjectRoot
        )

        assertTrue("Rollback should succeed", rollbackResult.success)
        assertEquals(1, rollbackResult.revertedCount)
        val restoredPaths = rollbackResult.fileResults.filterIsInstance<RollbackFileResult.Success>().map { it.relativePath }
        assertEquals(listOf("Target.kt"), restoredPaths)
        assertEquals("File must be restored to original pre-agent state", originalText, testFile.readText())
    }
}
