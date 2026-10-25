package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.chat.VerificationLevel
import com.tom.rv2ide.artificial.coding.BuildDiagnostic
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VerificationEvidenceLedgerTest {

    private lateinit var ledger: VerificationEvidenceLedger

    @Before
    fun setup() {
        ledger = VerificationEvidenceLedger()
    }

    @Test
    fun testRecordFailedBuild_capturesDiagnosticsAndRunId() {
        val diag1 = BuildDiagnostic(
            filePath = "app/src/main/cpp/native-lib.cpp",
            line = 42,
            column = 10,
            severity = BuildDiagnostic.Severity.ERROR,
            stage = "NDK_BUILD",
            message = "undefined reference to 'foo()'",
            rawLine = "native-lib.cpp:42:10: error: undefined reference to 'foo()'"
        )

        val entry = ledger.recordRun(
            type = VerificationType.BUILD,
            commandOrTool = "build_current_project",
            generation = 1L,
            isSuccess = false,
            exitCode = 1,
            diagnostics = listOf(diag1),
            summary = "NDK compilation failed with 1 error",
            runId = "build_run_fail_1"
        )

        assertEquals("build_run_fail_1", entry.runId)
        assertEquals(VerificationType.BUILD, entry.type)
        assertFalse(entry.isSuccess)
        assertEquals(1, entry.exitCode)
        assertEquals(1, entry.diagnostics.size)
        assertTrue(entry.isUnresolvedFailure)
        assertNull(entry.resolvedByRunId)
    }

    @Test
    fun testSubsequentPassingBuild_resolvesPreviousBuildFailure() {
        val diag1 = BuildDiagnostic(
            filePath = "MainActivity.kt",
            line = 15,
            column = 5,
            severity = BuildDiagnostic.Severity.ERROR,
            stage = "KOTLINC",
            message = "unresolved reference: Button",
            rawLine = "e: MainActivity.kt:15:5: unresolved reference: Button"
        )

        val failedRun = ledger.recordRun(
            type = VerificationType.BUILD,
            commandOrTool = "build_current_project",
            generation = 1L,
            isSuccess = false,
            exitCode = 1,
            diagnostics = listOf(diag1),
            runId = "build_fail_1"
        )
        assertTrue(failedRun.isUnresolvedFailure)

        // Agent applies fix, generation increments to 2
        ledger.onMutationOccurred(2L)
        assertTrue(failedRun.isStale)

        // Build passes at generation 2
        val successRun = ledger.recordRun(
            type = VerificationType.BUILD,
            commandOrTool = "build_current_project",
            generation = 2L,
            isSuccess = true,
            exitCode = 0,
            diagnostics = emptyList(),
            runId = "build_success_2"
        )

        // Previous failed build is resolved by runId
        assertEquals("build_success_2", failedRun.resolvedByRunId)
        assertFalse(failedRun.isUnresolvedFailure)
        assertTrue(failedRun.isResolvedFailure)
        assertTrue(ledger.getUnresolvedFailures().isEmpty())
    }

    @Test
    fun testPassingBuild_doesNotResolveTestFailures() {
        // Test fails at generation 1
        val testDiag = BuildDiagnostic(
            filePath = "AppTest.kt",
            line = 25,
            column = null,
            severity = BuildDiagnostic.Severity.ERROR,
            stage = "TEST",
            message = "AssertionError: expected:<10> but was:<20>",
            rawLine = "AppTest > testCalculation FAILED"
        )

        val failedTest = ledger.recordRun(
            type = VerificationType.TEST,
            commandOrTool = "./gradlew test",
            generation = 1L,
            isSuccess = false,
            exitCode = 1,
            diagnostics = listOf(testDiag),
            runId = "test_run_1"
        )

        // Subsequent BUILD succeeds at generation 2
        val successBuild = ledger.recordRun(
            type = VerificationType.BUILD,
            commandOrTool = "build_current_project",
            generation = 2L,
            isSuccess = true,
            exitCode = 0,
            runId = "build_run_2"
        )

        // A passing BUILD must NOT resolve a failing TEST run!
        assertNull(failedTest.resolvedByRunId)
        assertTrue(failedTest.isUnresolvedFailure)
        assertEquals(1, ledger.getUnresolvedFailures().size)
        assertEquals("test_run_1", ledger.getUnresolvedFailures().first().runId)
    }

    @Test
    fun testPassingTest_resolvesPreviousTestFailure() {
        val failedTest = ledger.recordRun(
            type = VerificationType.TEST,
            commandOrTool = "./gradlew test",
            generation = 1L,
            isSuccess = false,
            exitCode = 1,
            summary = "1 test failed",
            runId = "test_run_fail"
        )
        assertTrue(failedTest.isUnresolvedFailure)

        // Test re-run passes
        val passedTest = ledger.recordRun(
            type = VerificationType.TEST,
            commandOrTool = "./gradlew test",
            generation = 2L,
            isSuccess = true,
            exitCode = 0,
            summary = "All tests passed",
            runId = "test_run_pass"
        )

        assertEquals("test_run_pass", failedTest.resolvedByRunId)
        assertFalse(failedTest.isUnresolvedFailure)
        assertTrue(ledger.getUnresolvedFailures().isEmpty())
    }

    @Test
    fun testMutationInvalidatesPreviousVerification_marksStale() {
        val buildRun = ledger.recordRun(
            type = VerificationType.BUILD,
            commandOrTool = "build_current_project",
            generation = 1L,
            isSuccess = true,
            exitCode = 0,
            runId = "build_gen1"
        )
        assertFalse(buildRun.isStale)
        assertTrue(ledger.hasActiveSuccess(VerificationType.BUILD, currentGeneration = 1L))

        // Mutation occurs -> generation advances to 2
        ledger.onMutationOccurred(2L)

        assertTrue(buildRun.isStale)
        // At generation 2, the gen 1 build is stale and no longer an active success
        assertFalse(ledger.hasActiveSuccess(VerificationType.BUILD, currentGeneration = 2L))
    }

    @Test
    fun testCompletionReport_withUnresolvedErrors_returnsFailedWithErrors() {
        ledger.recordRun(
            type = VerificationType.BUILD,
            commandOrTool = "build_current_project",
            generation = 1L,
            isSuccess = false,
            exitCode = 1,
            diagnostics = listOf(
                BuildDiagnostic(
                    filePath = "Main.kt",
                    line = 10,
                    column = 1,
                    severity = BuildDiagnostic.Severity.ERROR,
                    stage = "KOTLINC",
                    message = "Syntax error",
                    rawLine = "Main.kt:10:1: error: Syntax error"
                )
            )
        )

        val status = ledger.computeCompletionStatus(
            currentGeneration = 1L,
            hadMutations = true,
            rolledBack = false
        )

        assertEquals(VerificationLevel.FAILED_WITH_ERRORS, status.verificationLevel)
        assertFalse(status.validationPassed)
        assertEquals(1, status.remainingErrors.size)
        assertTrue(status.hasUnresolvedFailures)
        assertEquals("Resolve compilation/build errors.", status.nextActionableStep)
    }

    @Test
    fun testCompletionReport_withStaleVerificationAfterMutation_returnsImplementedUnverified() {
        ledger.recordRun(
            type = VerificationType.BUILD,
            commandOrTool = "build_current_project",
            generation = 1L,
            isSuccess = true,
            exitCode = 0
        )

        // File modified without re-building
        ledger.onMutationOccurred(2L)

        val status = ledger.computeCompletionStatus(
            currentGeneration = 2L,
            hadMutations = true,
            rolledBack = false
        )

        assertEquals(VerificationLevel.IMPLEMENTED_UNVERIFIED, status.verificationLevel)
        assertTrue(status.validationPassed)
        assertTrue(status.remainingErrors.isEmpty())
        assertTrue(status.isStale)
        assertEquals("Build or run tests to verify recent modifications.", status.nextActionableStep)
    }

    @Test
    fun testCompletionReport_withCurrentBuildAndTest_returnsBuiltAndVerified() {
        ledger.recordRun(
            type = VerificationType.BUILD,
            commandOrTool = "build_current_project",
            generation = 3L,
            isSuccess = true,
            exitCode = 0,
            runId = "build_3"
        )
        ledger.recordRun(
            type = VerificationType.TEST,
            commandOrTool = "./gradlew test",
            generation = 3L,
            isSuccess = true,
            exitCode = 0,
            runId = "test_3"
        )

        val status = ledger.computeCompletionStatus(
            currentGeneration = 3L,
            hadMutations = true,
            rolledBack = false
        )

        assertEquals(VerificationLevel.BUILT_AND_VERIFIED, status.verificationLevel)
        assertTrue(status.validationPassed)
        assertTrue(status.remainingErrors.isEmpty())
        assertNull(status.nextActionableStep)
    }

    @Test
    fun testCompletionReport_rolledBack_returnsRolledBack() {
        val status = ledger.computeCompletionStatus(
            currentGeneration = 2L,
            hadMutations = true,
            rolledBack = true
        )

        assertEquals(VerificationLevel.ROLLED_BACK, status.verificationLevel)
        assertFalse(status.validationPassed)
    }

    @Test
    fun testLedger_preservesHistoricalRecordsWithoutIndiscriminateDeletion() {
        ledger.recordRun(type = VerificationType.BUILD, commandOrTool = "b1", generation = 1L, isSuccess = false, exitCode = 1, runId = "r1")
        ledger.recordRun(type = VerificationType.BUILD, commandOrTool = "b2", generation = 2L, isSuccess = true, exitCode = 0, runId = "r2")
        ledger.recordRun(type = VerificationType.TEST, commandOrTool = "t1", generation = 2L, isSuccess = false, exitCode = 1, runId = "r3")

        val entries = ledger.getAllEntries()
        assertEquals(3, entries.size)
        // r1 is preserved with resolvedByRunId
        val r1 = entries.first { it.runId == "r1" }
        assertEquals("r2", r1.resolvedByRunId)
        // r3 remains unresolved
        val r3 = entries.first { it.runId == "r3" }
        assertNull(r3.resolvedByRunId)
    }

    @Test
    fun testCorruptedOrEmptyOutput_handledGracefully() {
        val entry = ledger.recordRun(
            type = VerificationType.BUILD,
            commandOrTool = "corrupted_cmd",
            generation = 1L,
            isSuccess = false,
            exitCode = -1,
            diagnostics = emptyList(),
            summary = ""
        )

        val unres = ledger.getUnresolvedDiagnostics()
        assertEquals(1, unres.size)
        assertTrue(unres.first().contains("Project Build Error"))
    }

    @Test
    fun testMultipleIndependentFailures_allMustBeResolvedBeforePassing() {
        val b1 = ledger.recordRun(type = VerificationType.BUILD, commandOrTool = "build1", generation = 1L, isSuccess = false, exitCode = 1, runId = "b1")
        val t1 = ledger.recordRun(type = VerificationType.TEST, commandOrTool = "test1", generation = 1L, isSuccess = false, exitCode = 1, runId = "t1")

        // Build 2 succeeds, resolves b1
        ledger.recordRun(type = VerificationType.BUILD, commandOrTool = "build2", generation = 2L, isSuccess = true, exitCode = 0, runId = "b2")

        // Gate should still FAIL because t1 is not resolved!
        val status = ledger.computeCompletionStatus(currentGeneration = 2L, hadMutations = true, rolledBack = false)
        assertEquals(VerificationLevel.FAILED_WITH_ERRORS, status.verificationLevel)
        assertFalse(status.validationPassed)

        // Now test 2 succeeds, resolves t1
        ledger.recordRun(type = VerificationType.TEST, commandOrTool = "test2", generation = 2L, isSuccess = true, exitCode = 0, runId = "t2")

        val statusClean = ledger.computeCompletionStatus(currentGeneration = 2L, hadMutations = true, rolledBack = false)
        assertEquals(VerificationLevel.BUILT_AND_VERIFIED, statusClean.verificationLevel)
        assertTrue(statusClean.validationPassed)
    }

    @Test
    fun testHistoricalLedgerQueries_answerDistinctQuestionsIndependently() {
        val r1 = ledger.recordRun(
            type = VerificationType.BUILD,
            commandOrTool = "./gradlew assembleDebug",
            generation = 1L,
            isSuccess = false,
            exitCode = 1,
            runId = "build_fail_run"
        )

        // Question 1: Did run fail historically?
        assertTrue(ledger.didRunFailHistorically("build_fail_run"))
        // Question 2: Was failure resolved?
        assertFalse(ledger.wasFailureResolved("build_fail_run"))
        // Question 3: Is it applicable to current state?
        assertTrue(ledger.isEvidenceApplicableToCurrentState("build_fail_run", 1L))

        // Mutation occurs
        ledger.onMutationOccurred(2L)
        assertFalse(ledger.isEvidenceApplicableToCurrentState("build_fail_run", 2L))

        // Successful repair build
        val r2 = ledger.recordRun(
            type = VerificationType.BUILD,
            commandOrTool = "./gradlew assembleDebug",
            generation = 2L,
            isSuccess = true,
            exitCode = 0,
            runId = "build_pass_run"
        )

        // Historical failure answers:
        assertTrue("Historical failure remains true forever", ledger.didRunFailHistorically("build_fail_run"))
        assertTrue("Failure is now resolved", ledger.wasFailureResolved("build_fail_run"))
        assertEquals("build_pass_run", ledger.getResolvingRun("build_fail_run")?.runId)

        // Question 5: Which exact run produced supporting evidence for current state?
        val supporting = ledger.getSupportingRun(VerificationType.BUILD, 2L)
        assertNotNull(supporting)
        assertEquals("build_pass_run", supporting?.runId)
    }

    @Test
    fun testPassingBuild_doesNotEraseUnrelatedToolErrors() {
        val orchestrator = ToolOrchestrator(
            webSearchManager = WebSearchManager()
        )

        ledger.recordRun(
            type = VerificationType.BUILD,
            commandOrTool = "assembleDebug",
            generation = 1L,
            isSuccess = true,
            exitCode = 0
        )

        val report = orchestrator.buildCompletionReport(
            userGoal = "Fix crash bug",
            turnModifiedFiles = setOf("MainActivity.kt"),
            unrecoveredErrors = listOf("[File IO Error]: Disk full while writing backup"),
            currentGeneration = 1L,
            ledger = ledger
        )

        assertFalse("Unrelated non-verification errors must prevent validationPassed", report.validationPassed)
        assertEquals(VerificationLevel.FAILED_WITH_ERRORS, report.verificationLevel)
        assertTrue(report.remainingErrors.any { it.contains("Disk full") })
    }

    @Test
    fun testStaleVerification_afterLaterMutation_revertsToImplementedUnverified() {
        ledger.recordRun(
            type = VerificationType.BUILD,
            commandOrTool = "assembleDebug",
            generation = 1L,
            isSuccess = true,
            exitCode = 0
        )

        // First check: Build is current at generation 1
        val statusGen1 = ledger.computeCompletionStatus(currentGeneration = 1L, hadMutations = true, rolledBack = false)
        assertEquals(VerificationLevel.BUILT_AND_VERIFIED, statusGen1.verificationLevel)

        // Subsequent code mutation occurs at generation 2
        ledger.onMutationOccurred(2L)

        // Second check: Without a new build, verification is now stale
        val statusGen2 = ledger.computeCompletionStatus(currentGeneration = 2L, hadMutations = true, rolledBack = false)
        assertEquals(VerificationLevel.IMPLEMENTED_UNVERIFIED, statusGen2.verificationLevel)
        assertTrue(statusGen2.isStale)
        assertNotNull(statusGen2.nextActionableStep)
        assertTrue(statusGen2.nextActionableStep?.contains("verify recent modifications") == true)
    }

    @Test
    fun testSavedEvidenceRestoration_withLegacyOrMalformedJson() {
        val malformedJson = org.json.JSONObject().apply {
            put("runId", "legacy_run_999")
            put("type", "UNKNOWN_FUTURE_TYPE")
            put("generation", 1L)
            // missing isSuccess, exitCode, diagnostics
        }

        val restored = VerificationRunEntry.fromJson(malformedJson)
        assertEquals("legacy_run_999", restored.runId)
        // Defaults safely to BUILD, isSuccess = false
        assertEquals(VerificationType.BUILD, restored.type)
        assertFalse(restored.isSuccess)
        assertNull(restored.exitCode)
        assertTrue(restored.diagnostics.isEmpty())
    }
}
