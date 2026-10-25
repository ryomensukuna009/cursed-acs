package com.tom.rv2ide.artificial.diff

/**
 * Type of line in a computed diff.
 */
enum class DiffLineType {
    ADDED,
    DELETED,
    UNCHANGED,
    HEADER
}

/**
 * A single line in a diff with line numbering and modification metadata.
 */
data class DiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null
)

/**
 * A contiguous diff hunk with standard unified diff header coordinates.
 */
data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>
) {
    fun header(): String = "@@ -$oldStart,$oldCount +$newStart,$newCount @@"
}

/**
 * Result of computing a visual line-by-line diff between original and new file content.
 */
data class DiffFileResult(
    val relativePath: String,
    val linesAdded: Int,
    val linesRemoved: Int,
    val isNewFile: Boolean,
    val isDeletedFile: Boolean,
    val hunks: List<DiffHunk>,
    val allLines: List<DiffLine>
) {
    val isUnchanged: Boolean = (linesAdded == 0 && linesRemoved == 0 && !isNewFile && !isDeletedFile)

    fun toUnifiedDiff(): String = buildString {
        appendLine("--- a/$relativePath")
        appendLine("+++ b/$relativePath")
        if (hunks.isEmpty()) {
            appendLine("@@ -0,0 +0,0 @@ (No changes)")
        } else {
            for (hunk in hunks) {
                appendLine(hunk.header())
                for (line in hunk.lines) {
                    val prefixChar = when (line.type) {
                        DiffLineType.ADDED -> "+"
                        DiffLineType.DELETED -> "-"
                        DiffLineType.UNCHANGED -> " "
                        DiffLineType.HEADER -> "@"
                    }
                    appendLine("$prefixChar${line.text}")
                }
            }
        }
    }.trimEnd()

    fun toFormattedText(): String = buildString {
        if (isUnchanged) {
            appendLine("  [No changes in $relativePath]")
            return@buildString
        }
        for (hunk in hunks) {
            appendLine(hunk.header())
            for (line in hunk.lines) {
                val oldStr = line.oldLineNumber?.toString()?.padStart(4) ?: "    "
                val newStr = line.newLineNumber?.toString()?.padStart(4) ?: "    "
                val marker = when (line.type) {
                    DiffLineType.ADDED -> "+"
                    DiffLineType.DELETED -> "-"
                    DiffLineType.UNCHANGED -> " "
                    DiffLineType.HEADER -> "@"
                }
                appendLine("$oldStr $newStr $marker ${line.text}")
            }
        }
    }.trimEnd()
}

/**
 * Robust, high-performance line-based Myers/LCS diff engine.
 *
 * Handles:
 * - Empty original (new file -> all lines added)
 * - Empty new content (deleted file -> all lines removed)
 * - Identical content (0 added, 0 removed)
 * - Multi-line insertions, deletions, replacements
 * - Windows CRLF / Unix LF normalization
 * - Missing final newlines
 * - Fast prefix/suffix trimming for sub-millisecond execution
 * - Unicode and multi-byte content
 */
object DiffEngine {

    private const val MAX_LCS_CELLS = 2_000_000

    fun computeDiff(
        relativePath: String,
        originalContent: String?,
        newContent: String?,
        contextLines: Int = 3
    ): DiffFileResult {
        // Case 1: Both null or both identical
        if (originalContent == null && newContent == null) {
            return DiffFileResult(
                relativePath = relativePath,
                linesAdded = 0,
                linesRemoved = 0,
                isNewFile = false,
                isDeletedFile = false,
                hunks = emptyList(),
                allLines = emptyList()
            )
        }

        // Case 2: New file (originalContent is null)
        if (originalContent == null && newContent != null) {
            val newLines = splitLines(newContent)
            val diffLines = newLines.mapIndexed { idx, lineText ->
                DiffLine(
                    type = DiffLineType.ADDED,
                    text = lineText,
                    oldLineNumber = null,
                    newLineNumber = idx + 1
                )
            }
            val hunk = DiffHunk(
                oldStart = 0,
                oldCount = 0,
                newStart = 1,
                newCount = newLines.size,
                lines = diffLines
            )
            return DiffFileResult(
                relativePath = relativePath,
                linesAdded = newLines.size,
                linesRemoved = 0,
                isNewFile = true,
                isDeletedFile = false,
                hunks = if (diffLines.isEmpty()) emptyList() else listOf(hunk),
                allLines = diffLines
            )
        }

        // Case 3: Deleted file (newContent is null)
        if (originalContent != null && newContent == null) {
            val origLines = splitLines(originalContent)
            val diffLines = origLines.mapIndexed { idx, lineText ->
                DiffLine(
                    type = DiffLineType.DELETED,
                    text = lineText,
                    oldLineNumber = idx + 1,
                    newLineNumber = null
                )
            }
            val hunk = DiffHunk(
                oldStart = 1,
                oldCount = origLines.size,
                newStart = 0,
                newCount = 0,
                lines = diffLines
            )
            return DiffFileResult(
                relativePath = relativePath,
                linesAdded = 0,
                linesRemoved = origLines.size,
                isNewFile = false,
                isDeletedFile = true,
                hunks = if (diffLines.isEmpty()) emptyList() else listOf(hunk),
                allLines = diffLines
            )
        }

        // Case 4: Both contents present
        val origLines = splitLines(originalContent!!)
        val newLines = splitLines(newContent!!)

        if (origLines == newLines) {
            val lines = origLines.mapIndexed { idx, text ->
                DiffLine(DiffLineType.UNCHANGED, text, idx + 1, idx + 1)
            }
            return DiffFileResult(
                relativePath = relativePath,
                linesAdded = 0,
                linesRemoved = 0,
                isNewFile = false,
                isDeletedFile = false,
                hunks = emptyList(),
                allLines = lines
            )
        }

        // Fast prefix matching
        var prefix = 0
        while (prefix < origLines.size && prefix < newLines.size && origLines[prefix] == newLines[prefix]) {
            prefix++
        }

        // Fast suffix matching
        var suffix = 0
        while (suffix < (origLines.size - prefix) && suffix < (newLines.size - prefix) &&
            origLines[origLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
        ) {
            suffix++
        }

        val origMid = origLines.subList(prefix, origLines.size - suffix)
        val newMid = newLines.subList(prefix, newLines.size - suffix)

        val midDiff = if (origMid.size.toLong() * newMid.size.toLong() <= MAX_LCS_CELLS) {
            computeLcsDiff(origMid, newMid)
        } else {
            // Guard against massive matrix explosion: treat middle as total replacement
            val fallback = mutableListOf<DiffLine>()
            origMid.forEach { fallback.add(DiffLine(DiffLineType.DELETED, it)) }
            newMid.forEach { fallback.add(DiffLine(DiffLineType.ADDED, it)) }
            fallback
        }

        // Assemble full raw line sequence
        val rawLines = mutableListOf<DiffLine>()
        for (p in 0 until prefix) {
            rawLines.add(DiffLine(DiffLineType.UNCHANGED, origLines[p]))
        }
        rawLines.addAll(midDiff)
        for (s in (origLines.size - suffix) until origLines.size) {
            rawLines.add(DiffLine(DiffLineType.UNCHANGED, origLines[s]))
        }

        // Assign accurate 1-based line numbers and count line alterations
        var curOld = 1
        var curNew = 1
        var addedCount = 0
        var removedCount = 0

        val numberedLines = rawLines.map { line ->
            when (line.type) {
                DiffLineType.UNCHANGED -> {
                    DiffLine(DiffLineType.UNCHANGED, line.text, curOld++, curNew++)
                }
                DiffLineType.DELETED -> {
                    removedCount++
                    DiffLine(DiffLineType.DELETED, line.text, curOld++, null)
                }
                DiffLineType.ADDED -> {
                    addedCount++
                    DiffLine(DiffLineType.ADDED, line.text, null, curNew++)
                }
                DiffLineType.HEADER -> line
            }
        }

        // Chunk into contextual hunks
        val hunks = chunkIntoHunks(numberedLines, contextLines)

        return DiffFileResult(
            relativePath = relativePath,
            linesAdded = addedCount,
            linesRemoved = removedCount,
            isNewFile = false,
            isDeletedFile = false,
            hunks = hunks,
            allLines = numberedLines
        )
    }

    private fun computeLcsDiff(origMid: List<String>, newMid: List<String>): List<DiffLine> {
        val m = origMid.size
        val n = newMid.size

        if (m == 0) {
            return newMid.map { DiffLine(DiffLineType.ADDED, it) }
        }
        if (n == 0) {
            return origMid.map { DiffLine(DiffLineType.DELETED, it) }
        }

        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            val o = origMid[i - 1]
            for (j in 1..n) {
                if (o == newMid[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                } else {
                    dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        var i = m
        var j = n
        val result = mutableListOf<DiffLine>()

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && origMid[i - 1] == newMid[j - 1]) {
                result.add(DiffLine(DiffLineType.UNCHANGED, origMid[i - 1]))
                i--
                j--
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                result.add(DiffLine(DiffLineType.ADDED, newMid[j - 1]))
                j--
            } else {
                result.add(DiffLine(DiffLineType.DELETED, origMid[i - 1]))
                i--
            }
        }

        result.reverse()
        return result
    }

    private fun chunkIntoHunks(lines: List<DiffLine>, contextLines: Int): List<DiffHunk> {
        val changeIndices = lines.indices.filter {
            lines[it].type == DiffLineType.ADDED || lines[it].type == DiffLineType.DELETED
        }
        if (changeIndices.isEmpty()) return emptyList()

        val ranges = mutableListOf<Pair<Int, Int>>()
        for (idx in changeIndices) {
            val start = maxOf(0, idx - contextLines)
            val end = minOf(lines.size - 1, idx + contextLines)

            if (ranges.isEmpty()) {
                ranges.add(start to end)
            } else {
                val last = ranges.last()
                if (start <= last.second + 1) {
                    ranges[ranges.size - 1] = last.first to maxOf(last.second, end)
                } else {
                    ranges.add(start to end)
                }
            }
        }

        return ranges.map { (startIdx, endIdx) ->
            val hunkLines = lines.subList(startIdx, endIdx + 1)
            val oldLines = hunkLines.filter { it.type == DiffLineType.UNCHANGED || it.type == DiffLineType.DELETED }
            val newLines = hunkLines.filter { it.type == DiffLineType.UNCHANGED || it.type == DiffLineType.ADDED }

            val oldStart = oldLines.firstOrNull()?.oldLineNumber ?: 1
            val oldCount = oldLines.size
            val newStart = newLines.firstOrNull()?.newLineNumber ?: 1
            val newCount = newLines.size

            DiffHunk(
                oldStart = oldStart,
                oldCount = oldCount,
                newStart = newStart,
                newCount = newCount,
                lines = hunkLines
            )
        }
    }

    fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val raw = normalized.split("\n")
        return if (normalized.endsWith("\n") && raw.isNotEmpty() && raw.last().isEmpty()) {
            raw.dropLast(1)
        } else {
            raw
        }
    }
}
