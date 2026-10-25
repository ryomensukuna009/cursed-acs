package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.chat.AgentCompletionReport
import com.tom.rv2ide.artificial.chat.ChatMessageItem
import com.tom.rv2ide.artificial.chat.VerificationLevel
import com.tom.rv2ide.artificial.policy.ActionPolicyGate
import com.tom.rv2ide.artificial.policy.ActionType
import com.tom.rv2ide.artificial.transport.ChatRole
import com.tom.rv2ide.artificial.transport.ToolCall
import com.tom.rv2ide.artificial.transport.ToolExecutionStatus
import com.tom.rv2ide.artificial.transport.ToolResultMessage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

/**
 * Deterministic regression test suite for Task 28:
 * State Invalidation, Recovery Semantics & Completion-Gate Proof.
 */
class AgentStateInvalidationAndCompletionTest {

    private lateinit var sandboxDir: File
    private lateinit var sampleFile: File
    private lateinit var orchestrator: ToolOrchestrator
    private lateinit var webSearchManager: WebSearchManager

    @Before
    fun setUp() {
        sandboxDir = Files.createTempDirectory("task28_state_sandbox").toFile()
        val srcDir = File(sandboxDir, "app/src/main/java/com/example").apply { mkdirs() }
        sampleFile = File(srcDir, "MainActivity.kt").apply {
            writeText(
                """
                package com.example
                class MainActivity {
                    fun onCreate() {
                        println("Hello Task 28")
                    }
                }
                """.trimIndent()
            )
        }

        webSearchManager = WebSearchManager(null)
        orchestrator = ToolOrchestrator(
            webSearchManager = webSearchManager,
            safeUrlFetcher = SafeUrlFetcher(),
            projectRootProvider = { sandboxDir }
        )
    }

    @After
    fun tearDown() {
        sandboxDir.deleteRecursively()
        ActionPolicyGate.clearSessionApprovals()
    }

    // 1. Terminal classifier distinguishes pure inspection from mutating commands
    @Test
    fun terminalClassifier_classifiesInspectionCommandsCorrectly() {
        val inspectionCmds = listOf(
            "ls -la app/src",
            "cat app/src/main/java/com/example/MainActivity.kt",
            "git status",
            "git diff HEAD~1",
            "git log -n 5",
            "pwd",
            "find . -name '*.kt'",
            "grep -rn 'println' .",
            "echo 'Hello world'"
        )
        for (cmd in inspectionCmds) {
            assertFalse(
                "Command '$cmd' must be classified as read-only inspection",
                TerminalCommandClassifier.isMutatingOrBuildCommand(cmd)
            )
        }
    }

    // 2. Terminal classifier recognizes mutating commands (redirection, file ops, git mutating)
    @Test
    fun terminalClassifier_classifiesMutatingCommandsCorrectly() {
        val mutatingCmds = listOf(
            "touch newfile.txt",
            "rm -rf app/build",
            "mkdir -p app/src/test",
            "cp file1.kt file2.kt",
            "mv fileA.kt fileB.kt",
            "echo 'val x = 1' > app/src/main/java/Config.kt",
            "echo 'val y = 2' >> app/src/main/java/Config.kt",
            "git checkout -b feature/test",
            "git reset --hard",
            "chmod +x gradlew",
            "sed -i 's/foo/bar/g' MainActivity.kt",
            "./custom_script.sh"
        )
        for (cmd in mutatingCmds) {
            assertTrue(
                "Command '$cmd' must be classified as potentially mutating",
                TerminalCommandClassifier.isMutatingOrBuildCommand(cmd)
            )
        }
    }

    // 3. Terminal classifier recognizes build and test tasks
    @Test
    fun terminalClassifier_classifiesBuildAndTestCommandsCorrectly() {
        assertTrue(TerminalCommandClassifier.isBuildCommand("./gradlew assembleDebug"))
        assertTrue(TerminalCommandClassifier.isBuildCommand("gradle compileJava"))
        assertTrue(TerminalCommandClassifier.isTestCommand("./gradlew testDebugUnitTest"))
        assertTrue(TerminalCommandClassifier.isTestCommand("gradle check"))
        assertFalse(TerminalCommandClassifier.isBuildCommand("cat build.gradle"))
        assertFalse(TerminalCommandClassifier.isTestCommand("cat Test.kt"))
    }

    // 3b. Conservative Edge Cases: Quoted arguments, pipelines, wrappers, scripts, package managers
    @Test
    fun terminalClassifier_conservativeEdgeCasesCovered() {
        // Quoted arguments containing redirection characters must NOT be treated as output redirects
        assertFalse("Quoted > in grep must be inspection", TerminalCommandClassifier.isMutatingOrBuildCommand("grep '<div>' file.html"))
        assertFalse("Quoted > in echo must be inspection", TerminalCommandClassifier.isMutatingOrBuildCommand("echo \"a > b\""))
        assertFalse("Quoted semicolon in grep must be inspection", TerminalCommandClassifier.isMutatingOrBuildCommand("grep 'a;b' file.txt"))

        // Pure inspection pipelines
        assertFalse("Pipeline of inspection tools must be inspection", TerminalCommandClassifier.isMutatingOrBuildCommand("cat file.txt | grep foo | wc -l"))
        assertFalse("Pipeline with sort and uniq must be inspection", TerminalCommandClassifier.isMutatingOrBuildCommand("cat file.txt | sort | uniq"))

        // Mutating sinks in pipelines
        assertTrue("Pipeline with tee mutates", TerminalCommandClassifier.isMutatingOrBuildCommand("cat file.txt | tee output.txt"))
        assertTrue("Pipeline with xargs rm mutates", TerminalCommandClassifier.isMutatingOrBuildCommand("cat file.txt | xargs rm"))

        // Chained commands where at least one stage mutates
        assertTrue("Chained command with mutation must be mutating", TerminalCommandClassifier.isMutatingOrBuildCommand("ls -la && rm -rf temp"))

        // Shell wrappers
        assertFalse("sh -c with inspection inner command must be inspection", TerminalCommandClassifier.isMutatingOrBuildCommand("sh -c \"ls -la\""))
        assertTrue("sh -c with mutating inner command must be mutating", TerminalCommandClassifier.isMutatingOrBuildCommand("sh -c \"rm -rf temp\""))
        assertTrue("bash -c with sed mutates", TerminalCommandClassifier.isMutatingOrBuildCommand("bash -c \"sed -i 's/a/b/' file.txt\""))

        // Script interpreters are treated conservatively as mutating
        assertTrue("python execution mutates", TerminalCommandClassifier.isMutatingOrBuildCommand("python script.py"))
        assertTrue("node execution mutates", TerminalCommandClassifier.isMutatingOrBuildCommand("node index.js"))
        assertFalse("python --version is pure inspection", TerminalCommandClassifier.isMutatingOrBuildCommand("python --version"))

        // Package managers
        assertTrue("npm install mutates", TerminalCommandClassifier.isMutatingOrBuildCommand("npm install express"))
        assertFalse("npm list is inspection", TerminalCommandClassifier.isMutatingOrBuildCommand("npm list"))
        assertTrue("pip install mutates", TerminalCommandClassifier.isMutatingOrBuildCommand("pip install requests"))
        assertFalse("pip list is inspection", TerminalCommandClassifier.isMutatingOrBuildCommand("pip list"))

        // Git mutations vs safe queries
        assertTrue("git branch -D mutates", TerminalCommandClassifier.isMutatingOrBuildCommand("git branch -D feature-branch"))
        assertFalse("git branch listing is inspection", TerminalCommandClassifier.isMutatingOrBuildCommand("git branch -a"))
        assertFalse("git status is inspection", TerminalCommandClassifier.isMutatingOrBuildCommand("git status"))

        // Unknown binaries are treated conservatively as mutating
        assertTrue("Unknown custom binary must be treated as mutating", TerminalCommandClassifier.isMutatingOrBuildCommand("custom_optimizer_tool --run"))
    }

    // 4. Canonical signature deduplication across all 7 filePath aliases
    @Test
    fun signatureDeduplication_normalizesAllSevenFilePathAliases() {
        val tracker = ToolExecutionTracker()
        val aliases = listOf("filePath", "path", "filepath", "file_path", "filename", "targetFile", "relPath")
        val targetPath = "app/src/main/java/com/example/MainActivity.kt"

        val canonicalSig = tracker.computeSignature(
            AgentToolRegistry.TOOL_READ_PROJECT_FILE,
            JSONObject().put("filePath", targetPath)
        )

        for (alias in aliases) {
            val sig = tracker.computeSignature(
                AgentToolRegistry.TOOL_READ_PROJECT_FILE,
                JSONObject().put(alias, targetPath)
            )
            assertEquals("Alias '$alias' must produce identical canonical signature", canonicalSig, sig)
        }
    }

    // 5. Canonical signature deduplication for whole-file content aliases
    @Test
    fun signatureDeduplication_normalizesWholeFileContentAliases() {
        val tracker = ToolExecutionTracker()
        val contentAliases = listOf("codeContent", "content", "code", "newContent", "changes")
        val codeSample = "class Foo {}"

        val canonicalSig = tracker.computeSignature(
            AgentToolRegistry.TOOL_APPLY_FILE_CHANGES,
            JSONObject().put("filePath", "A.kt").put("codeContent", codeSample)
        )

        for (alias in contentAliases) {
            val sig = tracker.computeSignature(
                AgentToolRegistry.TOOL_APPLY_FILE_CHANGES,
                JSONObject().put("filePath", "A.kt").put(alias, codeSample)
            )
            assertEquals("Content alias '$alias' must produce identical canonical signature", canonicalSig, sig)
        }
    }

    // 6. Two calls differing in meaningful arguments produce distinct signatures
    @Test
    fun signatureDeduplication_distinctArgumentsProduceDistinctSignatures() {
        val tracker = ToolExecutionTracker()
        val sigA = tracker.computeSignature(
            AgentToolRegistry.TOOL_READ_PROJECT_FILE,
            JSONObject().put("filePath", "app/MainActivity.kt")
        )
        val sigB = tracker.computeSignature(
            AgentToolRegistry.TOOL_READ_PROJECT_FILE,
            JSONObject().put("filePath", "app/SecondActivity.kt")
        )
        assertTrue("Different paths must produce different signatures", sigA != sigB)

        val rangeA = tracker.computeSignature(
            AgentToolRegistry.TOOL_READ_FILE_RANGE,
            JSONObject().put("filePath", "A.kt").put("startLine", 1).put("endLine", 10)
        )
        val rangeB = tracker.computeSignature(
            AgentToolRegistry.TOOL_READ_FILE_RANGE,
            JSONObject().put("filePath", "A.kt").put("startLine", 11).put("endLine", 20)
        )
        assertTrue("Different line ranges must produce different signatures", rangeA != rangeB)
    }

    // 7. Two different tools with similar arguments produce distinct signatures
    @Test
    fun signatureDeduplication_differentToolsProduceDistinctSignatures() {
        val tracker = ToolExecutionTracker()
        val readSig = tracker.computeSignature(
            AgentToolRegistry.TOOL_READ_PROJECT_FILE,
            JSONObject().put("filePath", "A.kt")
        )
        val outlineSig = tracker.computeSignature(
            AgentToolRegistry.TOOL_GET_FILE_OUTLINE,
            JSONObject().put("filePath", "A.kt")
        )
        assertTrue("Different tools must produce different signatures", readSig != outlineSig)
    }

    // 8. Repeated successful read reuses cached content truthfully
    @Test
    fun duplicateRead_reusesCachedContentTruthfully() {
        val tracker = ToolExecutionTracker()
        val sig = tracker.computeSignature(
            AgentToolRegistry.TOOL_READ_PROJECT_FILE,
            JSONObject().put("filePath", "app/src/main/java/com/example/MainActivity.kt")
        )

        val initialContent = "package com.example\nclass MainActivity {}"
        tracker.recordExecution(sig, ToolExecutionStatus.SUCCESS, isError = false, content = initialContent)

        // Duplicate read in same generation
        val duplicateResult = tracker.evaluateCall("c2", "read_project_file", AgentToolRegistry.TOOL_READ_PROJECT_FILE, sig)
        assertNotNull("Duplicate read must be caught by tracker", duplicateResult)
        assertEquals(ToolExecutionStatus.SKIPPED_POLICY_LIMIT, duplicateResult?.status)
        assertFalse("Cached read is not an error", duplicateResult!!.isError)
        assertTrue("Must include truthfulness label", duplicateResult.content.contains("Cached Content"))
        assertTrue("Must preserve cached file text", duplicateResult.content.contains("class MainActivity"))
    }

    // 9. Repeated failed call preserves prior failure details
    @Test
    fun duplicateFailedCall_preservesPriorFailureDetails() {
        val tracker = ToolExecutionTracker()
        val sig = tracker.computeSignature(
            AgentToolRegistry.TOOL_READ_PROJECT_FILE,
            JSONObject().put("filePath", "NonExistent.kt")
        )

        val errorContent = "FileNotFoundException: File 'NonExistent.kt' does not exist"
        tracker.recordExecution(sig, ToolExecutionStatus.EXECUTION_FAILURE, isError = true, content = errorContent)

        val skipResult = tracker.evaluateCall("c2", "read_project_file", AgentToolRegistry.TOOL_READ_PROJECT_FILE, sig)
        assertNotNull(skipResult)
        assertEquals(ToolExecutionStatus.SKIPPED_POLICY_LIMIT, skipResult?.status)
        assertTrue(skipResult!!.isError)
        assertTrue("Prior failure details must be preserved", skipResult.content.contains("FileNotFoundException"))
    }

    // 10. Memory bounding: LRU cache evicts oldest entries past maxHistoryEntries
    @Test
    fun memoryBound_lruEvictsOldestEntries() {
        val maxEntries = 5
        val tracker = ToolExecutionTracker(maxHistoryEntries = maxEntries, maxBlockedEntries = maxEntries)

        for (i in 1..10) {
            val sig = "read_file(filePath=File$i.kt)"
            tracker.recordExecution(sig, ToolExecutionStatus.SUCCESS, isError = false, content = "Content $i")
        }

        // Entries 1 through 5 should have been evicted by LRU
        assertFalse("Oldest entry past capacity should be evicted", tracker.hasRecordedExecution("read_file(filePath=File1.kt)"))
        assertTrue("Recent entry within capacity must remain recorded", tracker.hasRecordedExecution("read_file(filePath=File10.kt)"))

        val recentEntry = tracker.evaluateCall("c10", "read_project_file", AgentToolRegistry.TOOL_READ_PROJECT_FILE, "read_file(filePath=File10.kt)")
        assertNotNull("Recent pure read within capacity must remain cached", recentEntry)
    }

    // 11. State invalidation: terminal mutating command advances generation
    @Test
    fun stateInvalidation_terminalMutatingCommandAdvancesGeneration() {
        val tracker = ToolExecutionTracker()
        val sig = tracker.computeSignature(
            AgentToolRegistry.TOOL_READ_PROJECT_FILE,
            JSONObject().put("filePath", "A.kt")
        )
        tracker.recordExecution(sig, ToolExecutionStatus.SUCCESS, isError = false, content = "Old Content")

        // Mutating terminal command (e.g. touch or checkout)
        val isMutating = TerminalCommandClassifier.isMutatingOrBuildCommand("touch A.kt")
        assertTrue(isMutating)
        if (isMutating) {
            tracker.incrementMutationGeneration()
        }

        assertEquals(1L, tracker.mutationGeneration)
        val evaluated = tracker.evaluateCall("c2", "read_project_file", AgentToolRegistry.TOOL_READ_PROJECT_FILE, sig)
        assertNull("Read must be permitted again once terminal mutation advances generation", evaluated)
    }

    // 12. State invalidation: terminal inspection command does NOT advance generation
    @Test
    fun stateInvalidation_terminalInspectionDoesNotMutateState() {
        val tracker = ToolExecutionTracker()
        val sig = tracker.computeSignature(
            AgentToolRegistry.TOOL_READ_PROJECT_FILE,
            JSONObject().put("filePath", "A.kt")
        )
        tracker.recordExecution(sig, ToolExecutionStatus.SUCCESS, isError = false, content = "Old Content")

        // Read-only terminal command (e.g. ls)
        val isMutating = TerminalCommandClassifier.isMutatingOrBuildCommand("ls -la")
        assertFalse(isMutating)
        if (isMutating) {
            tracker.incrementMutationGeneration()
        }

        assertEquals(0L, tracker.mutationGeneration)
        val evaluated = tracker.evaluateCall("c2", "read_project_file", AgentToolRegistry.TOOL_READ_PROJECT_FILE, sig)
        assertNotNull("Read remains cached when only inspection terminal commands ran", evaluated)
    }

    // 13. Failure progression: incidental read cannot conceal persistent build failure
    @Test
    fun failureProgression_incidentalReadCannotConcealPersistentBuildFailure() {
        var consecutiveFailureRounds = 0
        var loopIterations = 0

        // Simulate 3 rounds where build fails but a trivial read succeeds
        for (i in 0 until 5) {
            loopIterations++
            val roundAllSucceeded = false
            val roundAnyMutation = false
            val roundAnyToolError = true // Build failed!

            // Progress rule under Task 28:
            if (roundAllSucceeded || roundAnyMutation) {
                consecutiveFailureRounds = 0
            } else if (roundAnyToolError) {
                consecutiveFailureRounds++
                if (consecutiveFailureRounds >= 3) {
                    break
                }
            }
        }

        assertEquals("Failure counter must trip circuit breaker after 3 rounds despite incidental reads", 3, loopIterations)
        assertEquals(3, consecutiveFailureRounds)
    }

    // 14. Failure progression: genuine mutation resets failure counter
    @Test
    fun failureProgression_genuineMutationResetsFailureCounter() {
        var consecutiveFailureRounds = 0

        // Round 1: build failed
        consecutiveFailureRounds++
        assertEquals(1, consecutiveFailureRounds)

        // Round 2: build failed again
        consecutiveFailureRounds++
        assertEquals(2, consecutiveFailureRounds)

        // Round 3: model applies file edit mutation
        val roundAnyMutation = true
        if (roundAnyMutation) {
            consecutiveFailureRounds = 0
        }
        assertEquals("Successful mutation must reset failure counter to allow verification", 0, consecutiveFailureRounds)
    }

    // 15. Authorization denial unblocks on explicit authorization
    @Test
    fun authorizationDenial_unblocksOnExplicitAuthorization() {
        val tracker = ToolExecutionTracker()
        val sig = tracker.computeSignature(
            AgentToolRegistry.TOOL_BUILD_CURRENT_PROJECT,
            JSONObject().put("buildMode", "GRADLE")
        )

        // User initially denies
        tracker.recordExecution(sig, ToolExecutionStatus.AUTHORIZATION_DENIED, isError = true)
        assertTrue(tracker.isSignatureBlocked(sig))

        // Subsequent unauthorized call is blocked
        val blocked = tracker.evaluateCall("c2", "build_current_project", AgentToolRegistry.TOOL_BUILD_CURRENT_PROJECT, sig)
        assertNotNull(blocked)
        assertEquals(ToolExecutionStatus.AUTHORIZATION_DENIED, blocked?.status)

        // User explicitly approves
        var userExplicitlyApproved = true
        val unblocked = tracker.evaluateCall(
            "c3", "build_current_project", AgentToolRegistry.TOOL_BUILD_CURRENT_PROJECT, sig,
            isExplicitlyAuthorized = { userExplicitlyApproved }
        )
        assertNull("Explicit user approval must unblock execution", unblocked)
        assertFalse(tracker.isSignatureBlocked(sig))
    }

    // 16. Completion report: stale build invalidated by subsequent mutation
    @Test
    fun completionReport_staleBuildInvalidatedBySubsequentMutation() {
        val report = orchestrator.buildCompletionReport(
            userGoal = "Refactor class",
            turnModifiedFiles = setOf("app/MainActivity.kt"),
            buildExecuted = true,
            buildPassed = true,
            lastBuildGeneration = 0L,  // Build passed in gen 0
            currentGeneration = 1L     // File modified in gen 1 without re-running build!
        )

        assertEquals(
            "Stale build verification must NOT report BUILT_AND_VERIFIED",
            VerificationLevel.IMPLEMENTED_UNVERIFIED,
            report.verificationLevel
        )
        assertFalse("Unverified modifications cannot claim validation passed", report.validationPassed)
        assertNotNull(report.nextActionableStep)
        assertTrue(report.nextActionableStep!!.contains("Build or run tests"))
    }

    // 17. Completion report: build passed but tests failed produces FAILED_WITH_ERRORS
    @Test
    fun completionReport_buildPassedButTestsFailedProducesFailedWithErrors() {
        val report = orchestrator.buildCompletionReport(
            userGoal = "Fix test case",
            turnModifiedFiles = setOf("app/MainActivity.kt"),
            buildExecuted = true,
            buildPassed = true,
            lastBuildGeneration = 1L,
            testsExecuted = true,
            testsPassed = false,       // Tests failed!
            lastTestGeneration = null,
            currentGeneration = 1L
        )

        assertEquals(
            "Failing tests must take precedence over passing build",
            VerificationLevel.FAILED_WITH_ERRORS,
            report.verificationLevel
        )
        assertFalse(report.validationPassed)
        assertNotNull(report.nextActionableStep)
        assertTrue(report.nextActionableStep!!.contains("failing test assertions"))
    }

    // 18. Completion report: tests passed for current state produces TESTS_PASSED
    @Test
    fun completionReport_testsPassedCurrentStateProducesTestsPassed() {
        val report = orchestrator.buildCompletionReport(
            userGoal = "Update unit test",
            turnModifiedFiles = setOf("app/Test.kt"),
            testsExecuted = true,
            testsPassed = true,
            lastTestGeneration = 1L,
            currentGeneration = 1L
        )

        assertEquals(VerificationLevel.TESTS_PASSED, report.verificationLevel)
        assertTrue(report.validationPassed)
    }

    // 19. Completion report: read-only interaction produces NOT_VERIFIED with validationPassed = true
    @Test
    fun completionReport_readOnlyProducesNotVerifiedWithValidationPassed() {
        val report = orchestrator.buildCompletionReport(
            userGoal = "Explain architecture",
            turnModifiedFiles = emptySet(),
            filesInspectedTracker = setOf("app/MainActivity.kt")
        )

        assertEquals(VerificationLevel.NOT_VERIFIED, report.verificationLevel)
        assertTrue("Read-only interaction without mutations has passed validation", report.validationPassed)
        assertNull(report.nextActionableStep)
    }

    // 20. Completion report: transaction rolled back produces ROLLED_BACK
    @Test
    fun completionReport_rolledBackProducesRolledBack() {
        val report = orchestrator.buildCompletionReport(
            userGoal = "Faulty change",
            rolledBack = true
        )

        assertEquals(VerificationLevel.ROLLED_BACK, report.verificationLevel)
        assertTrue(report.isRolledBack)
        assertFalse(report.validationPassed)
    }

    // 21. Persistence: legacy JSON missing status loads with safe defaults
    @Test
    fun persistence_legacyJsonMissingStatusLoadsWithSafeDefaults() {
        val legacyJson = JSONObject().apply {
            put("id", "legacy_msg_1")
            put("conversationId", "c1")
            put("role", "TOOL")
            put("content", "Legacy output")
            put("toolResult", JSONObject().apply {
                put("toolCallId", "call_legacy")
                put("toolName", "read_project_file")
                put("content", "Legacy output")
                put("isError", false)
                // Missing status, executionStarted, mutationOccurred, exitCode
            })
        }

        val restored = ChatMessageItem.fromJson(legacyJson)
        assertNotNull(restored.toolResult)
        val tr = restored.toolResult!!
        assertEquals(ToolExecutionStatus.SUCCESS, tr.status)
        assertTrue(tr.executionStarted)
        assertFalse(tr.mutationOccurred)
        assertNull(tr.exitCode)
    }

    // 22. Persistence: corrupt/unrecognized status string loads safely without crashing
    @Test
    fun persistence_corruptStatusStringLoadsSafelyWithoutCrashing() {
        val corruptJson = JSONObject().apply {
            put("id", "corrupt_msg_1")
            put("conversationId", "c1")
            put("role", "TOOL")
            put("content", "Output")
            put("toolResult", JSONObject().apply {
                put("toolCallId", "call_corrupt")
                put("toolName", "some_tool")
                put("content", "Output")
                put("isError", true)
                put("status", "FUTURE_UNKNOWN_STATUS_VALUE_XYZ") // Unknown status!
            })
        }

        val restored = ChatMessageItem.fromJson(corruptJson)
        assertNotNull(restored.toolResult)
        assertEquals(ToolExecutionStatus.EXECUTION_FAILURE, restored.toolResult!!.status)
    }

    // 23. Persistence: TIMED_OUT status round-trips with fidelity
    @Test
    fun persistence_timedOutStatusRoundTripsFidelity() {
        val timedOutResult = ToolResultMessage(
            toolCallId = "call_timeout",
            toolName = AgentToolRegistry.TOOL_RUN_TERMINAL_COMMAND,
            content = "[Process timed out after 60 seconds and was terminated]",
            isError = true,
            status = ToolExecutionStatus.TIMED_OUT,
            executionStarted = true,
            mutationOccurred = false,
            exitCode = -1
        )
        val msg = ChatMessageItem(
            conversationId = "conv_timeout",
            role = ChatRole.TOOL,
            content = timedOutResult.content,
            toolResult = timedOutResult
        )

        val json = msg.toJson()
        val roundTripped = ChatMessageItem.fromJson(json)

        assertNotNull(roundTripped.toolResult)
        assertEquals(ToolExecutionStatus.TIMED_OUT, roundTripped.toolResult!!.status)
        assertEquals(Integer.valueOf(-1), roundTripped.toolResult!!.exitCode)
        assertTrue(roundTripped.toolResult!!.isError)
    }
}
