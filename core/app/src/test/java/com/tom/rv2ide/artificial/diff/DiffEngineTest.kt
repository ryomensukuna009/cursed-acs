package com.tom.rv2ide.artificial.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 23 Unit Tests for Kotlin DiffEngine.
 *
 * Exercises all diff scenarios: new files, deleted files, identical files,
 * additions, deletions, replacements, line endings, Unicode, long lines,
 * contextual hunks, and unified diff output formatting.
 */
class DiffEngineTest {

    @Test
    fun `test 1 - empty files produce zero-change result`() {
        val result = DiffEngine.computeDiff("Empty.txt", "", "")
        assertTrue(result.isUnchanged)
        assertEquals(0, result.linesAdded)
        assertEquals(0, result.linesRemoved)
        assertTrue(result.hunks.isEmpty())
    }

    @Test
    fun `test 2 - new file creation marks all lines as added`() {
        val newContent = "line 1\nline 2\nline 3"
        val result = DiffEngine.computeDiff("NewFile.kt", null, newContent)

        assertTrue(result.isNewFile)
        assertFalse(result.isDeletedFile)
        assertFalse(result.isUnchanged)
        assertEquals(3, result.linesAdded)
        assertEquals(0, result.linesRemoved)
        assertEquals(1, result.hunks.size)

        val hunk = result.hunks[0]
        assertEquals(0, hunk.oldStart)
        assertEquals(0, hunk.oldCount)
        assertEquals(1, hunk.newStart)
        assertEquals(3, hunk.newCount)
        assertTrue(hunk.lines.all { it.type == DiffLineType.ADDED })
    }

    @Test
    fun `test 3 - deleted file marks all lines as deleted`() {
        val origContent = "val a = 1\nval b = 2"
        val result = DiffEngine.computeDiff("OldFile.kt", origContent, null)

        assertFalse(result.isNewFile)
        assertTrue(result.isDeletedFile)
        assertFalse(result.isUnchanged)
        assertEquals(0, result.linesAdded)
        assertEquals(2, result.linesRemoved)
        assertEquals(1, result.hunks.size)

        val hunk = result.hunks[0]
        assertEquals(1, hunk.oldStart)
        assertEquals(2, hunk.oldCount)
        assertEquals(0, hunk.newStart)
        assertEquals(0, hunk.newCount)
        assertTrue(hunk.lines.all { it.type == DiffLineType.DELETED })
    }

    @Test
    fun `test 4 - identical contents produce unchanged result with zero modifications`() {
        val text = "fun main() {\n    println(\"Hello\")\n}"
        val result = DiffEngine.computeDiff("Main.kt", text, text)

        assertTrue(result.isUnchanged)
        assertEquals(0, result.linesAdded)
        assertEquals(0, result.linesRemoved)
        assertTrue(result.hunks.isEmpty())
        assertEquals(3, result.allLines.size)
        assertTrue(result.allLines.all { it.type == DiffLineType.UNCHANGED })
    }

    @Test
    fun `test 5 - single line addition in middle`() {
        val orig = "fun test() {\n    val a = 1\n}"
        val newC = "fun test() {\n    val a = 1\n    val b = 2\n}"
        val result = DiffEngine.computeDiff("Test.kt", orig, newC)

        assertFalse(result.isUnchanged)
        assertEquals(1, result.linesAdded)
        assertEquals(0, result.linesRemoved)
        assertEquals(1, result.hunks.size)

        val addedLine = result.allLines.first { it.type == DiffLineType.ADDED }
        assertEquals("    val b = 2", addedLine.text)
        assertEquals(3, addedLine.newLineNumber)
    }

    @Test
    fun `test 6 - single line deletion in middle`() {
        val orig = "fun test() {\n    val a = 1\n    val b = 2\n}"
        val newC = "fun test() {\n    val b = 2\n}"
        val result = DiffEngine.computeDiff("Test.kt", orig, newC)

        assertFalse(result.isUnchanged)
        assertEquals(0, result.linesAdded)
        assertEquals(1, result.linesRemoved)

        val deletedLine = result.allLines.first { it.type == DiffLineType.DELETED }
        assertEquals("    val a = 1", deletedLine.text)
        assertEquals(2, deletedLine.oldLineNumber)
    }

    @Test
    fun `test 7 - line replacement in middle of file`() {
        val orig = "header\noldLine1\noldLine2\nfooter"
        val newC = "header\nnewLine1\nnewLine2\nnewLine3\nfooter"
        val result = DiffEngine.computeDiff("Replace.txt", orig, newC)

        assertEquals(3, result.linesAdded)
        assertEquals(2, result.linesRemoved)
        assertEquals(1, result.hunks.size)
    }

    @Test
    fun `test 8 - CRLF and LF line endings are normalized uniformly`() {
        val origWindows = "line1\r\nline2\r\nline3\r\n"
        val newUnix = "line1\nline2\nline3\n"
        val result = DiffEngine.computeDiff("Norm.txt", origWindows, newUnix)

        assertTrue("CRLF vs LF must be normalized without false diffs", result.isUnchanged)
        assertEquals(0, result.linesAdded)
        assertEquals(0, result.linesRemoved)
    }

    @Test
    fun `test 9 - missing final newline handled gracefully`() {
        val origWithNewline = "a\nb\n"
        val newNoNewline = "a\nb"
        val result = DiffEngine.computeDiff("EOF.txt", origWithNewline, newNoNewline)

        assertTrue(result.isUnchanged)
    }

    @Test
    fun `test 10 - unicode and multibyte characters supported without corruption`() {
        val orig = "val greeting = \"Hello, World!\"\nval symbol = 'α'"
        val newC = "val greeting = \"नमस्ते दुनिया 🌍\"\nval symbol = '🚀'"
        val result = DiffEngine.computeDiff("Unicode.kt", orig, newC)

        assertEquals(2, result.linesAdded)
        assertEquals(2, result.linesRemoved)
        val addedTexts = result.allLines.filter { it.type == DiffLineType.ADDED }.map { it.text }
        assertTrue(addedTexts.contains("val greeting = \"नमस्ते दुनिया 🌍\""))
        assertTrue(addedTexts.contains("val symbol = '🚀'"))
    }

    @Test
    fun `test 11 - long code lines preserve full text without truncation`() {
        val longLine = "val veryLongString = \"" + "A".repeat(2048) + "\""
        val orig = "val a = 1\n$longLine\nval b = 2"
        val newC = "val a = 1\n${longLine}_MODIFIED\nval b = 2"
        val result = DiffEngine.computeDiff("LongLine.kt", orig, newC)

        assertEquals(1, result.linesAdded)
        assertEquals(1, result.linesRemoved)
        val addedLine = result.allLines.first { it.type == DiffLineType.ADDED }
        assertEquals("${longLine}_MODIFIED", addedLine.text)
    }

    @Test
    fun `test 12 - changes separated by distant lines produce multiple distinct hunks`() {
        val origBuilder = StringBuilder()
        for (i in 1..50) {
            origBuilder.appendLine("line_$i")
        }
        val orig = origBuilder.toString()

        // Modify line 5 and line 45 (40 lines apart, context is 3 lines -> separate hunks!)
        val lines = orig.lines().toMutableList()
        lines[4] = "line_5_MODIFIED"
        lines[44] = "line_45_MODIFIED"
        val newC = lines.joinToString("\n")

        val result = DiffEngine.computeDiff("Hunks.kt", orig, newC, contextLines = 3)
        assertEquals(2, result.linesAdded)
        assertEquals(2, result.linesRemoved)
        assertEquals(2, result.hunks.size)

        // Hunk 1 covers around line 5
        assertTrue(result.hunks[0].lines.any { it.text == "line_5_MODIFIED" })
        // Hunk 2 covers around line 45
        assertTrue(result.hunks[1].lines.any { it.text == "line_45_MODIFIED" })
    }

    @Test
    fun `test 13 - toUnifiedDiff formats standard unified diff header and markers`() {
        val orig = "fun a() {}\nfun b() {}"
        val newC = "fun a() {}\nfun bV2() {}\nfun c() {}"
        val result = DiffEngine.computeDiff("Service.kt", orig, newC)

        val unified = result.toUnifiedDiff()
        assertTrue(unified.contains("--- a/Service.kt"))
        assertTrue(unified.contains("+++ b/Service.kt"))
        assertTrue(unified.contains("@@ -"))
        assertTrue(unified.contains("-fun b() {}"))
        assertTrue(unified.contains("+fun bV2() {}"))
        assertTrue(unified.contains("+fun c() {}"))
    }

    @Test
    fun `test 14 - toFormattedText includes gutter columns and line numbers`() {
        val orig = "first\nsecond"
        val newC = "first\nsecond_modified"
        val result = DiffEngine.computeDiff("Gutter.txt", orig, newC)

        val formatted = result.toFormattedText()
        assertNotNull(formatted)
        assertTrue(formatted.contains("- second"))
        assertTrue(formatted.contains("+ second_modified"))
    }
}
