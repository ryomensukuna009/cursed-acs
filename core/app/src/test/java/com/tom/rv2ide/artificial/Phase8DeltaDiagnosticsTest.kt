package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.coding.BuildDiagnostic
import com.tom.rv2ide.artificial.coding.RepairWaveRecord
import com.tom.rv2ide.artificial.coding.StructuredDiagnosticCollector
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase8DeltaDiagnosticsTest {

    private fun createDiag(
        filePath: String? = "app/src/main/java/com/example/MainActivity.kt",
        line: Int? = 42,
        column: Int? = 10,
        severity: BuildDiagnostic.Severity = BuildDiagnostic.Severity.ERROR,
        stage: String = "KOTLINC",
        message: String = "Unresolved reference: missingFunction"
    ): BuildDiagnostic {
        return BuildDiagnostic(
            filePath = filePath,
            line = line,
            column = column,
            severity = severity,
            stage = stage,
            message = message,
            rawLine = "$filePath:$line:$column: ${severity.name.lowercase()}: $message"
        )
    }

    @Test
    fun `test 1 - identical diagnostics in baseline and current build are classified as persistent`() {
        val diag = createDiag()
        val delta = StructuredDiagnosticCollector.computeDiagnosticDelta(
            baselineDiagnostics = listOf(diag),
            currentDiagnostics = listOf(diag)
        )

        assertEquals(1, delta.persistentDiagnostics.size)
        assertEquals(0, delta.newDiagnostics.size)
        assertEquals(0, delta.resolvedDiagnostics.size)
        assertEquals(0, delta.shiftedDiagnostics.size)
        assertEquals(1, delta.totalActiveErrors)
        assertEquals(1, delta.baselineCount)
        assertEquals(1, delta.currentCount)
    }

    @Test
    fun `test 2 - genuinely new compiler error is classified as new`() {
        val baseDiag = createDiag(message = "Unresolved reference: funcA")
        val newDiag = createDiag(line = 50, message = "cannot find symbol variable funcB")

        val delta = StructuredDiagnosticCollector.computeDiagnosticDelta(
            baselineDiagnostics = listOf(baseDiag),
            currentDiagnostics = listOf(baseDiag, newDiag)
        )

        assertEquals(1, delta.persistentDiagnostics.size)
        assertEquals(1, delta.newDiagnostics.size)
        assertEquals("cannot find symbol variable funcB", delta.newDiagnostics[0].message)
        assertEquals(0, delta.resolvedDiagnostics.size)
        assertEquals(0, delta.shiftedDiagnostics.size)
        assertEquals(2, delta.totalActiveErrors)
    }

    @Test
    fun `test 3 - resolved error is classified as resolved`() {
        val diagFixed = createDiag(line = 20, message = "Unresolved reference: oldBug")
        val diagRemaining = createDiag(line = 42, message = "Unresolved reference: persistentBug")

        val delta = StructuredDiagnosticCollector.computeDiagnosticDelta(
            baselineDiagnostics = listOf(diagFixed, diagRemaining),
            currentDiagnostics = listOf(diagRemaining)
        )

        assertEquals(1, delta.resolvedDiagnostics.size)
        assertEquals("Unresolved reference: oldBug", delta.resolvedDiagnostics[0].message)
        assertEquals(1, delta.persistentDiagnostics.size)
        assertEquals(0, delta.newDiagnostics.size)
        assertEquals(0, delta.shiftedDiagnostics.size)
        assertEquals(1, delta.totalActiveErrors)
    }

    @Test
    fun `test 4 - persistent unresolved error tracked across multiple repair waves`() {
        val errorA = createDiag(line = 30, message = "syntax error: unexpected token")
        val errorB = createDiag(line = 40, message = "unresolved reference: Helper")
        val errorC = createDiag(line = 50, message = "package does not exist: com.util")

        // Wave 0 -> Wave 1: Fixed errorB, but introduced errorC. errorA remains.
        val wave1Delta = StructuredDiagnosticCollector.computeDiagnosticDelta(
            baselineDiagnostics = listOf(errorA, errorB),
            currentDiagnostics = listOf(errorA, errorC)
        )
        assertEquals(1, wave1Delta.persistentDiagnostics.size)
        assertEquals(errorA, wave1Delta.persistentDiagnostics[0])
        assertEquals(1, wave1Delta.resolvedDiagnostics.size)
        assertEquals(errorB, wave1Delta.resolvedDiagnostics[0])
        assertEquals(1, wave1Delta.newDiagnostics.size)
        assertEquals(errorC, wave1Delta.newDiagnostics[0])

        // Wave 1 -> Wave 2: Fixed errorC, errorA still remains.
        val wave2Delta = StructuredDiagnosticCollector.computeDiagnosticDelta(
            baselineDiagnostics = listOf(errorA, errorC),
            currentDiagnostics = listOf(errorA)
        )
        assertEquals(1, wave2Delta.persistentDiagnostics.size)
        assertEquals(errorA, wave2Delta.persistentDiagnostics[0])
        assertEquals(1, wave2Delta.resolvedDiagnostics.size)
        assertEquals(errorC, wave2Delta.resolvedDiagnostics[0])
        assertEquals(0, wave2Delta.newDiagnostics.size)
        assertEquals(1, wave2Delta.totalActiveErrors)
    }

    @Test
    fun `test 5 - same error with shifted line number is classified as shifted with previous and current lines`() {
        val baseDiag = createDiag(line = 42, message = "cannot find symbol: class UnknownWidget")
        val shiftedDiag = createDiag(line = 55, message = "cannot find symbol: class UnknownWidget")

        val delta = StructuredDiagnosticCollector.computeDiagnosticDelta(
            baselineDiagnostics = listOf(baseDiag),
            currentDiagnostics = listOf(shiftedDiag)
        )

        assertEquals(0, delta.persistentDiagnostics.size)
        assertEquals(0, delta.newDiagnostics.size)
        assertEquals(0, delta.resolvedDiagnostics.size)
        assertEquals(1, delta.shiftedDiagnostics.size)

        val shifted = delta.shiftedDiagnostics[0]
        assertEquals(42, shifted.previousLine)
        assertEquals(55, shifted.currentLine)
        assertEquals("cannot find symbol: class UnknownWidget", shifted.diagnostic.message)
        assertEquals(1, delta.totalActiveErrors)
    }

    @Test
    fun `test 6 - different errors with similar messages are not falsely merged`() {
        val diagFoo = createDiag(line = 42, message = "cannot find symbol variable foo")
        val diagBar = createDiag(line = 42, message = "cannot find symbol variable bar")

        val delta = StructuredDiagnosticCollector.computeDiagnosticDelta(
            baselineDiagnostics = listOf(diagFoo),
            currentDiagnostics = listOf(diagBar)
        )

        // foo should be resolved, bar should be newly introduced - not merged!
        assertEquals(0, delta.persistentDiagnostics.size)
        assertEquals(0, delta.shiftedDiagnostics.size)
        assertEquals(1, delta.newDiagnostics.size)
        assertEquals("cannot find symbol variable bar", delta.newDiagnostics[0].message)
        assertEquals(1, delta.resolvedDiagnostics.size)
        assertEquals("cannot find symbol variable foo", delta.resolvedDiagnostics[0].message)
    }

    @Test
    fun `test 7 - normalized path separators windows vs unix match cleanly`() {
        val baseDiag = createDiag(
            filePath = """app\src\main\jni\NativeHelper.cpp""",
            line = 100,
            stage = "NDK_BUILD",
            message = "use of undeclared identifier 'nullptr_t'"
        )
        val currentDiag = createDiag(
            filePath = "app/src/main/jni/NativeHelper.cpp",
            line = 100,
            stage = "NDK_BUILD",
            message = "use of undeclared identifier 'nullptr_t'"
        )

        val delta = StructuredDiagnosticCollector.computeDiagnosticDelta(
            baselineDiagnostics = listOf(baseDiag),
            currentDiagnostics = listOf(currentDiag)
        )

        assertEquals(1, delta.persistentDiagnostics.size)
        assertEquals(0, delta.newDiagnostics.size)
        assertEquals(0, delta.resolvedDiagnostics.size)
    }

    @Test
    fun `test 8 - empty baseline on first build marks all diagnostics as active and new`() {
        val d1 = createDiag(line = 10, message = "error 1")
        val d2 = createDiag(line = 20, message = "error 2")
        val d3 = createDiag(line = 30, message = "error 3")

        val delta = StructuredDiagnosticCollector.computeDiagnosticDelta(
            baselineDiagnostics = emptyList(),
            currentDiagnostics = listOf(d1, d2, d3)
        )

        assertEquals(0, delta.baselineCount)
        assertEquals(3, delta.currentCount)
        assertEquals(3, delta.newDiagnostics.size)
        assertEquals(0, delta.persistentDiagnostics.size)
        assertEquals(0, delta.resolvedDiagnostics.size)
        assertEquals(3, delta.totalActiveErrors)
    }

    @Test
    fun `test 9 - fallback behavior when comparison data is empty or ambiguous preserves all active errors`() {
        val d1 = createDiag(line = 10, message = "error 1")
        val d2 = createDiag(line = 20, message = "error 2")

        val delta = StructuredDiagnosticCollector.computeDiagnosticDelta(
            baselineDiagnostics = emptyList(),
            currentDiagnostics = listOf(d1, d2)
        )

        assertEquals(2, delta.totalActiveErrors)
        assertFalse(delta.summaryPromptBlock.isBlank())
        assertTrue(delta.summaryPromptBlock.contains("Active Diagnostics: 2 (Baseline had 0)"))
    }

    @Test
    fun `test 10 - no false build success when unresolved fatal errors remain`() {
        val errorA = createDiag(line = 10, message = "fatal error: PCH file invalid")
        val errorB = createDiag(line = 20, message = "cannot find symbol: Foo")

        // In this wave, errorB was resolved, but fatal errorA remains
        val delta = StructuredDiagnosticCollector.computeDiagnosticDelta(
            baselineDiagnostics = listOf(errorA, errorB),
            currentDiagnostics = listOf(errorA)
        )

        assertEquals(1, delta.resolvedDiagnostics.size)
        assertEquals(1, delta.persistentDiagnostics.size)
        // totalActiveErrors MUST be greater than 0, preventing any false success
        assertTrue(delta.totalActiveErrors > 0)
        assertEquals(1, delta.totalActiveErrors)
    }

    @Test
    fun `test 11 - summary prompt block renders distinct sections for new, persistent, shifted, and resolved errors`() {
        val persistentDiag = createDiag(line = 15, message = "cannot find symbol class Engine")
        val shiftedBaseDiag = createDiag(line = 30, message = "expecting member declaration")
        val shiftedCurrDiag = createDiag(line = 45, message = "expecting member declaration")
        val resolvedDiag = createDiag(line = 60, message = "unresolved reference: OldFix")
        val newDiag = createDiag(line = 80, message = "unresolved reference: NewlyIntroducedBug")

        val delta = StructuredDiagnosticCollector.computeDiagnosticDelta(
            baselineDiagnostics = listOf(persistentDiag, shiftedBaseDiag, resolvedDiag),
            currentDiagnostics = listOf(persistentDiag, shiftedCurrDiag, newDiag)
        )

        val block = delta.summaryPromptBlock
        assertTrue(block.contains("=== DELTA COMPILER DIAGNOSTICS ==="))
        assertTrue(block.contains("[!] NEW ERRORS INTRODUCED IN THIS WAVE (1):"))
        assertTrue(block.contains("NewlyIntroducedBug"))
        assertTrue(block.contains("[*] PERSISTENT ROOT-CAUSE ERRORS (1):"))
        assertTrue(block.contains("Engine"))
        assertTrue(block.contains("[~] PERSISTENT ERRORS (Shifted Line Numbers) (1):"))
        assertTrue(block.contains("previously line 30"))
        assertTrue(block.contains("[✓] RESOLVED COMPILER ERRORS (1 fixed):"))
        assertTrue(block.contains("OldFix"))
    }

    @Test
    fun `test 12 - RepairWaveRecord records newErrorsCount and resolvedErrorsCount`() {
        val record = RepairWaveRecord(
            waveIndex = 2,
            modifiedFiles = listOf("app/src/main/cpp/Main.cpp"),
            errorFingerprint = "FINGERPRINT_123",
            buildSucceeded = false,
            remainingErrorsCount = 2,
            primaryRootCauses = listOf("cannot find symbol: foo"),
            newErrorsCount = 1,
            resolvedErrorsCount = 3
        )

        assertEquals(2, record.waveIndex)
        assertEquals("FINGERPRINT_123", record.errorFingerprint)
        assertFalse(record.buildSucceeded)
        assertEquals(2, record.remainingErrorsCount)
        assertEquals(1, record.newErrorsCount)
        assertEquals(3, record.resolvedErrorsCount)

        // Test default parameters for backwards compatibility
        val legacyRecord = RepairWaveRecord(
            waveIndex = 1,
            modifiedFiles = listOf("file.kt"),
            errorFingerprint = "FP",
            buildSucceeded = true,
            remainingErrorsCount = 0,
            primaryRootCauses = emptyList()
        )
        assertEquals(0, legacyRecord.newErrorsCount)
        assertEquals(0, legacyRecord.resolvedErrorsCount)
    }
}
