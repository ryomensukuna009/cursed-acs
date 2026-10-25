package com.tom.rv2ide.artificial.coding

import com.tom.rv2ide.artificial.parser.SnippetParser
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Represents a validated file operation in a coordinated multi-file change-set.
 */
data class CoordinatedFileEdit(
    val relativePath: String,
    val targetFile: File,
    val isNewFile: Boolean,
    val finalContent: String,
    val originalContent: String?
)

/**
 * Result of pre-apply validation on a multi-file change-set.
 */
data class ChangeSetValidationResult(
    val isValid: Boolean,
    val edits: List<CoordinatedFileEdit>,
    val errors: List<String>,
    val changeSetFingerprint: String
)

/**
 * Result of executing an atomic multi-file transaction.
 */
data class TransactionCommitResult(
    val success: Boolean,
    val appliedEdits: List<CoordinatedFileEdit>,
    val rolledBack: Boolean,
    val errorMessages: List<String>
)

/**
 * Identifies the tier that matched a SEARCH/REPLACE patch block.
 */
enum class PatchMatchTier {
    EXACT,
    LINE_ENDING_NORMALIZED,
    INDENTATION_TOLERANT,
    CONSERVATIVE_FUZZY,
    NONE
}

/**
 * Result of matching and applying a SEARCH/REPLACE block with actionable diagnostic metadata.
 */
data class PatchMatchResult(
    val updatedContent: String?,
    val tier: PatchMatchTier,
    val failureReason: String? = null,
    val bestMatchLines: String? = null,
    val bestMatchStartLine: Int? = null,
    val bestMatchEndLine: Int? = null,
    val bestSimilarity: Double? = null
)

/**
 * Coordinates multi-file parsing, pre-apply structural/syntax validation, and
 * atomic multi-file writes with automatic rollback on partial failure.
 */
object TransactionalFileCoordinator {

    private val LAZY_TRUNCATION_PATTERNS = listOf(
        Regex("""//\s*\.\.\.\s*(?:existing|rest of|remaining|other)\s+code\s*\.\.\.""", RegexOption.IGNORE_CASE),
        Regex("""/\*\s*\.\.\.\s*(?:existing|rest of|remaining)\s+code\s*\.\.\.\s*\*/""", RegexOption.IGNORE_CASE),
        Regex("""//\s*(?:rest of file|existing implementation)\s+unchanged""", RegexOption.IGNORE_CASE),
        Regex("""<!--\s*\.\.\.\s*(?:existing|rest of)\s+xml\s*\.\.\.\s*-->""", RegexOption.IGNORE_CASE)
    )

    private val SEARCH_REPLACE_BLOCK_REGEX = Regex(
        """FILE_TO_PATCH:\s*([^\r\n]+)\s*\r?\n<<<<<<< SEARCH\r?\n([\s\S]*?)\r?\n=======\r?\n([\s\S]*?)\r?\n>>>>>>> REPLACE"""
    )

    data class RawParsedFileBlock(
        val rawPath: String,
        val codeContent: String
    )

    /**
     * Parses both full-file modifications (`FILE_TO_MODIFY:` / `FILE_TO_CREATE:`) and targeted
     * `FILE_TO_PATCH:` `SEARCH/REPLACE` blocks into a unified list of proposed edits against [projectRoot].
     */
    fun parseProposedChangeSet(
        aiResponse: String,
        projectRoot: File
    ): ChangeSetValidationResult {
        val canonicalRoot = try {
            projectRoot.canonicalFile
        } catch (_: Exception) {
            projectRoot.absoluteFile
        }

        val validationErrors = mutableListOf<String>()
        val editByRelPath = LinkedHashMap<String, CoordinatedFileEdit>()

        // 1. Parse targeted SEARCH/REPLACE blocks first if present
        for (match in SEARCH_REPLACE_BLOCK_REGEX.findAll(aiResponse)) {
            val rawPath = match.groupValues[1].trim()
            val searchBlock = match.groupValues[2]
            val replaceBlock = match.groupValues[3]

            val resolved = resolveSafeTargetFile(canonicalRoot, rawPath)
            if (resolved == null) {
                validationErrors.add("Rejected unsafe or out-of-project path: '$rawPath'")
                continue
            }
            val (relPath, targetFile) = resolved
            val baseContent = editByRelPath[relPath]?.finalContent
                ?: if (targetFile.exists() && targetFile.isFile) targetFile.readText() else null

            if (baseContent == null) {
                validationErrors.add("Cannot apply SEARCH/REPLACE patch to non-existent file '$relPath'")
                continue
            }

            val matchResult = applySearchReplaceWithDiagnostics(baseContent, searchBlock, replaceBlock, relPath)
            if (matchResult.updatedContent == null) {
                val errorMsg = buildString {
                    append("SEARCH block failed to match in '$relPath'")
                    matchResult.failureReason?.let { append(": $it") }
                    if (!matchResult.bestMatchLines.isNullOrBlank() && matchResult.bestMatchStartLine != null) {
                        append("\nDid you mean to match lines ${matchResult.bestMatchStartLine}-${matchResult.bestMatchEndLine}?")
                        append("\n${matchResult.bestMatchLines}")
                    }
                }
                validationErrors.add(errorMsg)
                continue
            }

            val origText = if (targetFile.exists()) targetFile.readText() else null
            editByRelPath[relPath] = CoordinatedFileEdit(
                relativePath = relPath,
                targetFile = targetFile,
                isNewFile = origText == null,
                finalContent = matchResult.updatedContent!!,
                originalContent = origText
            )
        }

        // 2. Parse FILE_TO_MODIFY / FILE_TO_CREATE blocks
        val parsedBlocks = extractFileBlocks(aiResponse)
        for (block in parsedBlocks) {
            val resolved = resolveSafeTargetFile(canonicalRoot, block.rawPath)
            if (resolved == null) {
                validationErrors.add("Rejected unsafe or out-of-project path: '${block.rawPath}'")
                continue
            }
            val (relPath, targetFile) = resolved
            val existingEdit = editByRelPath[relPath]
            if (existingEdit != null && existingEdit.finalContent != block.codeContent) {
                validationErrors.add("Conflicting duplicate modifications proposed for '$relPath' in the same turn")
                continue
            }

            val origText = if (targetFile.exists() && targetFile.isFile) {
                try {
                    targetFile.readText()
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            editByRelPath[relPath] = CoordinatedFileEdit(
                relativePath = relPath,
                targetFile = targetFile,
                isNewFile = origText == null,
                finalContent = block.codeContent,
                originalContent = origText
            )
        }

        // 3. Validate and topologically sort each proposed edit by dependency
        val unsortedEdits = editByRelPath.values.toList()
        val validatedEdits = DependencyAwarePatchCoordinator.sortEditsByDependency(canonicalRoot, unsortedEdits)
        for (edit in validatedEdits) {
            validationErrors.addAll(validateSingleEdit(edit))
        }

        val fingerprint = computeFingerprint(validatedEdits)
        return ChangeSetValidationResult(
            isValid = validationErrors.isEmpty(),
            edits = validatedEdits,
            errors = validationErrors,
            changeSetFingerprint = fingerprint
        )
    }

    /**
     * Extracts `FILE_TO_MODIFY:` and `FILE_TO_CREATE:` blocks from an AI response,
     * supporting both `CODE_START`...`CODE_END` and raw/fenced code blocks.
     */
    fun extractFileBlocks(aiResponse: String): List<RawParsedFileBlock> {
        val results = mutableListOf<RawParsedFileBlock>()
        val snippetParser = SnippetParser()
        val lines = aiResponse.replace("\r\n", "\n").lines()

        var currentPath: String? = null
        val buffer = StringBuilder()

        fun flushCurrent() {
            val path = currentPath ?: return
            val raw = buffer.toString().trim()
            if (raw.isNotEmpty()) {
                val cleaned = cleanExtractedBlock(raw, snippetParser)
                results.add(RawParsedFileBlock(rawPath = path, codeContent = cleaned))
            }
            currentPath = null
            buffer.clear()
        }

        val fileBracketRegex = Regex("""^\[FILE[:\s]+([^\]\r\n]+)\]""", RegexOption.IGNORE_CASE)

        for (line in lines) {
            val trimmed = line.trim()
            val bracketMatch = fileBracketRegex.find(trimmed)

            when {
                bracketMatch != null -> {
                    flushCurrent()
                    currentPath = bracketMatch.groupValues[1].trim()
                }
                trimmed.equals("[/FILE]", ignoreCase = true) -> {
                    flushCurrent()
                }
                trimmed.startsWith("FILE_TO_MODIFY:", ignoreCase = true) -> {
                    flushCurrent()
                    currentPath = trimmed.substringAfter("FILE_TO_MODIFY:").trim()
                }
                trimmed.startsWith("FILE_TO_CREATE:", ignoreCase = true) -> {
                    flushCurrent()
                    currentPath = trimmed.substringAfter("FILE_TO_CREATE:").trim()
                }
                trimmed.startsWith("FILE_TO_PATCH:", ignoreCase = true) -> {
                    flushCurrent()
                }
                currentPath != null -> {
                    buffer.append(line).append('\n')
                }
            }
        }
        flushCurrent()
        return results
    }

    private fun cleanExtractedBlock(raw: String, snippetParser: SnippetParser): String {
        var text = raw.trim()
        if (text.contains("CODE_START")) {
            text = text.substringAfter("CODE_START").substringBefore("CODE_END").trim()
            return snippetParser.removeCodeBlockMarkers(text)
        }
        return try {
            snippetParser.cleanFileContent(text)
        } catch (_: Throwable) {
            snippetParser.removeCodeBlockMarkers(text)
        }
    }

    /**
     * Validates a single file edit for empty content, lazy truncation placeholders,
     * unbalanced delimiters in source files, or malformed XML.
     */
    fun validateSingleEdit(edit: CoordinatedFileEdit): List<String> {
        val errors = mutableListOf<String>()
        val content = edit.finalContent.trim()
        if (content.isEmpty()) {
            errors.add("File '${edit.relativePath}' has empty content.")
            return errors
        }

        // Check for lazy truncation placeholders when modifying an existing file
        val orig = edit.originalContent
        if (orig != null && orig.lines().size >= 8) {
            for (pattern in LAZY_TRUNCATION_PATTERNS) {
                if (pattern.containsMatchIn(content)) {
                    errors.add(
                        "File '${edit.relativePath}' contains a truncation placeholder ('${pattern.find(content)?.value}'). " +
                            "Full, compilable file content is required."
                    )
                    break
                }
            }
        }

        val ext = edit.targetFile.extension.lowercase()
        when (ext) {
            "kt", "java", "c", "cpp", "cc", "h", "hpp", "json", "gradle", "kts" -> {
                val balanceError = checkDelimiterBalance(content, edit.relativePath)
                if (balanceError != null) {
                    errors.add(balanceError)
                }
            }
            "xml" -> {
                val xmlError = checkXmlWellFormed(content, edit.relativePath)
                if (xmlError != null) {
                    errors.add(xmlError)
                }
            }
        }

        return errors
    }

    /**
     * Applies a validated multi-file change-set atomically.
     * If any file write or post-write hook fails, all files in the batch are rolled back
     * to their exact pre-transaction state.
     */
    fun applyChangeSetTransactionally(
        edits: List<CoordinatedFileEdit>,
        sessionId: String? = null,
        projectRoot: File? = null,
        onAfterEachWrite: ((CoordinatedFileEdit) -> Unit)? = null
    ): TransactionCommitResult {
        if (edits.isEmpty()) {
            return TransactionCommitResult(
                success = true,
                appliedEdits = emptyList(),
                rolledBack = false,
                errorMessages = emptyList()
            )
        }

        // Record pre-state in session rollback journal before touching disk
        if (sessionId != null && projectRoot != null) {
            for (edit in edits) {
                try {
                    AutonomousRollbackManager.recordFilePreState(
                        sessionId = sessionId,
                        projectRoot = projectRoot,
                        targetFile = edit.targetFile,
                        relativePath = edit.relativePath
                    )
                } catch (e: Exception) {
                    // Boundary error or security exception
                    return TransactionCommitResult(
                        success = false,
                        appliedEdits = emptyList(),
                        rolledBack = false,
                        errorMessages = listOf("Security rejection for '${edit.relativePath}': ${e.message}")
                    )
                }
            }
        }

        data class FileSnapshot(
            val file: File,
            val existedBefore: Boolean,
            val previousBytes: ByteArray?
        )

        val snapshots = edits.map { edit ->
            val exists = edit.targetFile.exists() && edit.targetFile.isFile
            val bytes = if (exists) {
                try {
                    edit.targetFile.readBytes()
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            FileSnapshot(edit.targetFile, exists, bytes)
        }

        val applied = mutableListOf<CoordinatedFileEdit>()
        try {
            for (edit in edits) {
                val parent = edit.targetFile.parentFile
                if (parent != null && !parent.exists()) {
                    if (!parent.mkdirs() && !parent.exists()) {
                        throw IllegalStateException("Failed to create parent directory for '${edit.relativePath}'")
                    }
                }

                val tempFile = File(
                    edit.targetFile.parentFile ?: File("."),
                    ".${edit.targetFile.name}.roxum_tx_tmp"
                )
                tempFile.writeText(edit.finalContent, Charsets.UTF_8)
                tempFile.copyTo(edit.targetFile, overwrite = true)
                tempFile.delete()

                applied.add(edit)
                AcsRepoMap.invalidateFile(edit.targetFile)

                if (sessionId != null && projectRoot != null) {
                    AutonomousRollbackManager.recordFilePostWrite(
                        sessionId = sessionId,
                        projectRoot = projectRoot,
                        targetFile = edit.targetFile,
                        relativePath = edit.relativePath,
                        newContent = edit.finalContent
                    )
                }

                onAfterEachWrite?.invoke(edit)
            }

            lastAppliedEdits = applied
            return TransactionCommitResult(
                success = true,
                appliedEdits = applied,
                rolledBack = false,
                errorMessages = emptyList()
            )
        } catch (t: Throwable) {
            for (snap in snapshots) {
                try {
                    if (snap.existedBefore && snap.previousBytes != null) {
                        snap.file.writeBytes(snap.previousBytes)
                    } else if (!snap.existedBefore && snap.file.exists()) {
                        snap.file.delete()
                    }
                } catch (_: Exception) {
                }
            }
            return TransactionCommitResult(
                success = false,
                appliedEdits = emptyList(),
                rolledBack = true,
                errorMessages = listOf("Transaction failed and was safely rolled back: ${t.message ?: t.javaClass.simpleName}")
            )
        }
    }

    private var lastAppliedEdits: List<CoordinatedFileEdit> = emptyList()

    fun getLastAppliedEdits(): List<CoordinatedFileEdit> = lastAppliedEdits

    fun clearLastAppliedEdits() {
        lastAppliedEdits = emptyList()
    }

    fun rollbackLastTransaction(): Boolean {
        if (lastAppliedEdits.isEmpty()) return false
        val editsToRollback = lastAppliedEdits
        lastAppliedEdits = emptyList()
        rollbackAppliedEdits(editsToRollback)
        return true
    }

    /**
     * Rolls back an already-applied list of [CoordinatedFileEdit]s if post-commit build/diagnostic
     * verification determines the change-set is unrecoverable.
     */
    fun rollbackAppliedEdits(edits: List<CoordinatedFileEdit>) {
        for (edit in edits) {
            try {
                if (!edit.isNewFile && edit.originalContent != null) {
                    edit.targetFile.writeText(edit.originalContent, Charsets.UTF_8)
                } else if (edit.isNewFile && edit.targetFile.exists()) {
                    edit.targetFile.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Safely rolls back all edits in an autonomous engineering session, preserving
     * any files with subsequent manual user edits as conflicts.
     */
    fun revertSession(sessionId: String, projectRoot: File? = null): SessionRollbackResult {
        return AutonomousRollbackManager.rollbackSession(sessionId, projectRoot)
    }

    /**
     * Retrieves the session rollback journal if available.
     */
    fun getSessionJournal(sessionId: String, projectRoot: File? = null): SessionRollbackJournal? {
        return AutonomousRollbackManager.getJournal(sessionId, projectRoot)
    }


    private fun resolveSafeTargetFile(canonicalRoot: File, rawPath: String): Pair<String, File>? {
        val trimmed = rawPath.trim().removeSurrounding("\"").removeSurrounding("'")
        if (trimmed.isEmpty() || trimmed.contains("..")) return null

        val rawFile = File(trimmed)
        val candidate = if (rawFile.isAbsolute) {
            rawFile
        } else {
            val direct = File(canonicalRoot, trimmed.replace('\\', '/').trimStart('/'))
            if (direct.exists()) {
                direct
            } else {
                val cleanRel = trimmed.replace('\\', '/').trimStart('/')
                val matched = canonicalRoot.walkTopDown().maxDepth(6).firstOrNull {
                    it.isFile && (it.name == rawFile.name || it.relativeToOrNull(canonicalRoot)?.path?.replace('\\', '/') == cleanRel)
                }
                matched ?: direct
            }
        }
        val canonicalCandidate = try {
            candidate.canonicalFile
        } catch (_: Exception) {
            return null
        }

        val rootPath = canonicalRoot.path
        val candidatePath = canonicalCandidate.path
        if (!candidatePath.startsWith(rootPath + File.separator) && candidatePath != rootPath) {
            return null
        }

        val relPath = canonicalCandidate.relativeTo(canonicalRoot).path.replace('\\', '/')
        if (relPath.isEmpty() || relPath.startsWith(".git/") || relPath == ".git") return null
        return relPath to canonicalCandidate
    }

    data class LineRecord(
        val content: String,
        val lineEnding: String,
        val startIndex: Int,
        val endIndex: Int
    )

    data class CandidateMatch(
        val startLine: Int,
        val endLine: Int,
        val similarity: Double,
        val chunkText: String
    )

    fun applySearchReplace(
        baseContent: String,
        searchBlock: String,
        replaceBlock: String
    ): String? {
        return applySearchReplaceWithDiagnostics(baseContent, searchBlock, replaceBlock).updatedContent
    }

    fun applySearchReplaceWithDiagnostics(
        baseContent: String,
        searchBlock: String,
        replaceBlock: String,
        filePath: String = ""
    ): PatchMatchResult {
        // Tier 1: Exact match
        if (baseContent.contains(searchBlock)) {
            return PatchMatchResult(
                updatedContent = baseContent.replaceFirst(searchBlock, replaceBlock),
                tier = PatchMatchTier.EXACT
            )
        }

        val lineRecords = parseLineRecords(baseContent)
        val searchLines = searchBlock.trimEnd('\r', '\n').split(Regex("""\r?\n"""))
        val replaceLines = if (replaceBlock.isEmpty()) emptyList() else replaceBlock.trimEnd('\r', '\n').split(Regex("""\r?\n"""))

        // Tier 2: Line-ending normalized match
        val tier2 = tryLineEndingNormalizedMatch(baseContent, lineRecords, searchLines, replaceLines)
        if (tier2 != null) return tier2

        // Tier 3: Indentation-tolerant match
        val tier3 = tryIndentationTolerantMatch(baseContent, lineRecords, searchLines, replaceLines)
        if (tier3 != null) return tier3

        // Tier 4 & 5: Conservative fuzzy match & Actionable diagnostics
        return tryConservativeFuzzyMatch(baseContent, lineRecords, searchBlock, replaceBlock, filePath)
    }

    internal fun parseLineRecords(text: String): List<LineRecord> {
        val records = mutableListOf<LineRecord>()
        var i = 0
        val n = text.length
        var lineStart = 0

        while (i < n) {
            val c = text[i]
            if (c == '\r') {
                if (i + 1 < n && text[i + 1] == '\n') {
                    records.add(LineRecord(text.substring(lineStart, i), "\r\n", lineStart, i + 2))
                    i += 2
                    lineStart = i
                } else {
                    records.add(LineRecord(text.substring(lineStart, i), "\r", lineStart, i + 1))
                    i++
                    lineStart = i
                }
            } else if (c == '\n') {
                records.add(LineRecord(text.substring(lineStart, i), "\n", lineStart, i + 1))
                i++
                lineStart = i
            } else {
                i++
            }
        }

        if (lineStart < n) {
            records.add(LineRecord(text.substring(lineStart, n), "", lineStart, n))
        }

        return records
    }

    private fun tryLineEndingNormalizedMatch(
        baseContent: String,
        lineRecords: List<LineRecord>,
        searchLines: List<String>,
        replaceLines: List<String>
    ): PatchMatchResult? {
        val m = searchLines.size
        if (m == 0 || lineRecords.size < m) return null

        for (i in 0..(lineRecords.size - m)) {
            val candidateRecords = lineRecords.subList(i, i + m)
            val matches = (0 until m).all { candidateRecords[it].content == searchLines[it] }
            if (matches) {
                val startChar = candidateRecords.first().startIndex
                val endChar = candidateRecords.last().endIndex
                val targetEnding = candidateRecords.first().lineEnding.ifEmpty { "\n" }
                val hasTrailingEnding = candidateRecords.last().lineEnding.isNotEmpty()

                val replacementBody = if (replaceLines.isEmpty()) {
                    ""
                } else if (hasTrailingEnding) {
                    replaceLines.joinToString(targetEnding, postfix = targetEnding)
                } else {
                    replaceLines.joinToString(targetEnding)
                }

                val updated = baseContent.substring(0, startChar) + replacementBody + baseContent.substring(endChar)
                return PatchMatchResult(
                    updatedContent = updated,
                    tier = PatchMatchTier.LINE_ENDING_NORMALIZED
                )
            }
        }
        return null
    }

    private fun tryIndentationTolerantMatch(
        baseContent: String,
        lineRecords: List<LineRecord>,
        searchLines: List<String>,
        replaceLines: List<String>
    ): PatchMatchResult? {
        val m = searchLines.size
        if (m == 0 || lineRecords.size < m) return null

        val searchTrimmed = searchLines.map { it.trim() }

        for (i in 0..(lineRecords.size - m)) {
            val candidateRecords = lineRecords.subList(i, i + m)
            val candidateTrimmed = candidateRecords.map { it.content.trim() }

            if (candidateTrimmed != searchTrimmed) continue

            val reindentedReplacement = tryComputeReindentedReplacement(candidateRecords, searchLines, replaceLines)
                ?: continue

            val startChar = candidateRecords.first().startIndex
            val endChar = candidateRecords.last().endIndex
            val targetEnding = candidateRecords.first().lineEnding.ifEmpty { "\n" }
            val hasTrailingEnding = candidateRecords.last().lineEnding.isNotEmpty()

            val replacementBody = if (reindentedReplacement.isEmpty()) {
                ""
            } else if (hasTrailingEnding) {
                reindentedReplacement.joinToString(targetEnding, postfix = targetEnding)
            } else {
                reindentedReplacement.joinToString(targetEnding)
            }

            val updated = baseContent.substring(0, startChar) + replacementBody + baseContent.substring(endChar)
            return PatchMatchResult(
                updatedContent = updated,
                tier = PatchMatchTier.INDENTATION_TOLERANT
            )
        }
        return null
    }

    private fun tryComputeReindentedReplacement(
        fileRecords: List<LineRecord>,
        searchLines: List<String>,
        replaceLines: List<String>
    ): List<String>? {
        val m = fileRecords.size
        val nonEmptyIndices = (0 until m).filter { searchLines[it].trim().isNotEmpty() && fileRecords[it].content.trim().isNotEmpty() }
        if (nonEmptyIndices.isEmpty()) return replaceLines

        val fileIndents = nonEmptyIndices.map { fileRecords[it].content.takeWhile { c -> c == ' ' || c == '\t' } }
        val searchIndents = nonEmptyIndices.map { searchLines[it].takeWhile { c -> c == ' ' || c == '\t' } }

        // Case A: File has additional leading whitespace
        val allFileStartWithSearch = nonEmptyIndices.indices.all { idx ->
            fileIndents[idx].startsWith(searchIndents[idx])
        }
        if (allFileStartWithSearch) {
            val deltas = nonEmptyIndices.indices.map { idx ->
                fileIndents[idx].substring(searchIndents[idx].length)
            }
            val firstDelta = deltas.first()
            if (deltas.all { it == firstDelta }) {
                return replaceLines.map { line ->
                    if (line.isBlank()) line else firstDelta + line
                }
            }
        }

        // Case B: Search has additional leading whitespace
        val allSearchStartWithFile = nonEmptyIndices.indices.all { idx ->
            searchIndents[idx].startsWith(fileIndents[idx])
        }
        if (allSearchStartWithFile) {
            val deltas = nonEmptyIndices.indices.map { idx ->
                searchIndents[idx].substring(fileIndents[idx].length)
            }
            val firstDelta = deltas.first()
            if (deltas.all { it == firstDelta }) {
                val dropLen = firstDelta.length
                return replaceLines.map { line ->
                    if (line.isBlank()) line else line.drop(minOf(dropLen, line.takeWhile { c -> c == ' ' || c == '\t' }.length))
                }
            }
        }

        // Case C: Tab-vs-space conversion
        val fileHasTabs = fileIndents.any { it.contains('\t') }
        val searchHasTabs = searchIndents.any { it.contains('\t') }

        if (fileHasTabs != searchHasTabs) {
            val fileDepths = fileIndents.map { indent ->
                if (fileHasTabs) indent.count { it == '\t' } else (indent.length / 4)
            }
            val searchDepths = searchIndents.map { indent ->
                if (searchHasTabs) indent.count { it == '\t' } else {
                    val step = if (searchIndents.any { it.length % 4 != 0 && it.length % 2 == 0 }) 2 else 4
                    indent.length / maxOf(1, step)
                }
            }

            val levelDeltas = fileDepths.indices.map { fileDepths[it] - searchDepths[it] }
            val uniformLevelDelta = levelDeltas.first()
            if (levelDeltas.all { it == uniformLevelDelta }) {
                val searchStep = if (searchHasTabs) 1 else (if (searchIndents.any { it.length % 4 != 0 && it.length % 2 == 0 }) 2 else 4)
                return replaceLines.map { line ->
                    if (line.isBlank()) {
                        line
                    } else {
                        val currentIndent = line.takeWhile { c -> c == ' ' || c == '\t' }
                        val currentLevel = if (searchHasTabs) currentIndent.count { it == '\t' } else (currentIndent.length / maxOf(1, searchStep))
                        val targetLevel = maxOf(0, currentLevel + uniformLevelDelta)
                        val targetIndent = if (fileHasTabs) "\t".repeat(targetLevel) else "    ".repeat(targetLevel)
                        targetIndent + line.trimStart()
                    }
                }
            }
        }

        return null
    }

    private fun tryConservativeFuzzyMatch(
        baseContent: String,
        lineRecords: List<LineRecord>,
        searchBlock: String,
        replaceBlock: String,
        filePath: String = ""
    ): PatchMatchResult {
        val searchLines = searchBlock.trimEnd('\r', '\n').split(Regex("""\r?\n"""))
        val m = searchLines.size
        val trimmedSearch = searchBlock.trim()

        val normSearch = searchLines.joinToString("\n") { it.trimEnd() }
        val normSearchTrimmed = searchLines.joinToString("\n") { it.trim() }
        val candidates = mutableListOf<CandidateMatch>()

        val minLen = maxOf(1, (m * 0.9).toInt())
        val maxLen = minOf(lineRecords.size, (m * 1.1).toInt() + 1)

        for (len in minLen..maxLen) {
            if (len <= 0 || len > lineRecords.size) continue
            for (i in 0..(lineRecords.size - len)) {
                val chunkRecords = lineRecords.subList(i, i + len)
                val chunkText = chunkRecords.joinToString("\n") { it.content.trimEnd() }
                val chunkTextTrimmed = chunkRecords.joinToString("\n") { it.content.trim() }

                val lengthDiff = Math.abs(chunkTextTrimmed.length - normSearchTrimmed.length)
                if (lengthDiff > (normSearchTrimmed.length * 0.5)) continue

                val rawSim = calculateSimilarity(normSearch, chunkText)
                val trimmedSim = calculateSimilarity(normSearchTrimmed, chunkTextTrimmed)
                val sim = maxOf(rawSim, trimmedSim)
                if (sim >= 0.50) {
                    candidates.add(
                        CandidateMatch(
                            startLine = i,
                            endLine = i + len,
                            similarity = sim,
                            chunkText = chunkRecords.joinToString("\n") { it.content }
                        )
                    )
                }
            }
        }

        candidates.sortByDescending { it.similarity }
        val best = candidates.firstOrNull()

        // Minimum block length check: Do NOT fuzzy match blocks shorter than 3 lines or 20 characters
        if (m < 3 || trimmedSearch.length < 20) {
            val reason = "SEARCH block did not match exactly, and is too short for fuzzy matching ($m line(s), ${trimmedSearch.length} char(s); minimum 3 lines and 20 chars required)"
            return PatchMatchResult(
                updatedContent = null,
                tier = PatchMatchTier.NONE,
                failureReason = reason,
                bestMatchLines = best?.chunkText,
                bestMatchStartLine = best?.let { it.startLine + 1 },
                bestMatchEndLine = best?.endLine,
                bestSimilarity = best?.similarity
            )
        }

        if (best == null) {
            return PatchMatchResult(
                updatedContent = null,
                tier = PatchMatchTier.NONE,
                failureReason = "No matching or similar content found in file (no candidate reached 50% similarity)"
            )
        }

        // Find distinct second-best candidate (overlap < 50%)
        val secondBest = candidates.firstOrNull { other ->
            val overlap = minOf(best.endLine, other.endLine) - maxOf(best.startLine, other.startLine)
            val minSpan = minOf(best.endLine - best.startLine, other.endLine - other.startLine)
            overlap < (minSpan / 2)
        }

        // Ambiguity check: If two or more candidate windows have similarity >= 0.85 and diff < 0.05
        if (secondBest != null && best.similarity >= 0.85 && secondBest.similarity >= 0.85 && (best.similarity - secondBest.similarity) < 0.05) {
            return PatchMatchResult(
                updatedContent = null,
                tier = PatchMatchTier.NONE,
                failureReason = "Ambiguous fuzzy match: multiple candidate locations matched with high similarity " +
                    "(lines ${best.startLine + 1}-${best.endLine} at ${(best.similarity * 100).toInt()}% and " +
                    "lines ${secondBest.startLine + 1}-${secondBest.endLine} at ${(secondBest.similarity * 100).toInt()}%)",
                bestMatchLines = best.chunkText,
                bestMatchStartLine = best.startLine + 1,
                bestMatchEndLine = best.endLine,
                bestSimilarity = best.similarity
            )
        }

        // Threshold check: similarity >= 0.85
        if (best.similarity < 0.85) {
            val replaceInBase = replaceBlock.trim().isNotEmpty() && baseContent.contains(replaceBlock.trim())
            val note = if (replaceInBase) " (Note: The REPLACE block appears to already be present in file)" else ""
            return PatchMatchResult(
                updatedContent = null,
                tier = PatchMatchTier.NONE,
                failureReason = "Closest match had similarity ${(best.similarity * 100).toInt()}% at lines ${best.startLine + 1}-${best.endLine}, below the required 85% threshold$note",
                bestMatchLines = best.chunkText,
                bestMatchStartLine = best.startLine + 1,
                bestMatchEndLine = best.endLine,
                bestSimilarity = best.similarity
            )
        }

        // Unambiguous, safe fuzzy match >= 0.85!
        val startChar = lineRecords[best.startLine].startIndex
        val endChar = lineRecords[best.endLine - 1].endIndex
        val targetEnding = lineRecords[best.startLine].lineEnding.ifEmpty { "\n" }
        val hasTrailingEnding = lineRecords[best.endLine - 1].lineEnding.isNotEmpty()

        val replaceLines = if (replaceBlock.isEmpty()) emptyList() else replaceBlock.trimEnd('\r', '\n').split(Regex("""\r?\n"""))
        val fileLeadingIndent = lineRecords[best.startLine].content.takeWhile { it == ' ' || it == '\t' }
        val searchLeadingIndent = searchLines.firstOrNull { it.isNotBlank() }?.takeWhile { it == ' ' || it == '\t' } ?: ""
        val adaptedReplaceLines = if (fileLeadingIndent.isNotEmpty() && searchLeadingIndent.isEmpty()) {
            replaceLines.map { if (it.isBlank()) it else fileLeadingIndent + it }
        } else {
            replaceLines
        }

        val replacementBody = if (adaptedReplaceLines.isEmpty()) {
            ""
        } else if (hasTrailingEnding) {
            adaptedReplaceLines.joinToString(targetEnding, postfix = targetEnding)
        } else {
            adaptedReplaceLines.joinToString(targetEnding)
        }

        val updated = baseContent.substring(0, startChar) + replacementBody + baseContent.substring(endChar)
        return PatchMatchResult(
            updatedContent = updated,
            tier = PatchMatchTier.CONSERVATIVE_FUZZY,
            bestMatchLines = best.chunkText,
            bestMatchStartLine = best.startLine + 1,
            bestMatchEndLine = best.endLine,
            bestSimilarity = best.similarity
        )
    }

    fun calculateSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        val dist = levenshteinDistance(s1, s2)
        return maxOf(0.0, 1.0 - (dist.toDouble() / maxLen.toDouble()))
    }

    fun levenshteinDistance(s1: String, s2: String, maxLimit: Int = Int.MAX_VALUE): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length
        if (Math.abs(s1.length - s2.length) > maxLimit) return maxLimit + 1

        var prev = IntArray(s2.length + 1) { it }
        var curr = IntArray(s2.length + 1)

        for (i in 1..s1.length) {
            curr[0] = i
            var minVal = curr[0]
            val c1 = s1[i - 1]
            for (j in 1..s2.length) {
                val cost = if (c1 == s2[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
                if (curr[j] < minVal) minVal = curr[j]
            }
            if (minVal > maxLimit) {
                return maxLimit + 1
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[s2.length]
    }

    /**
     * Lightweight lexer that verifies balanced `{}` and `()` while skipping comments and string literals.
     */
    internal fun checkDelimiterBalance(code: String, filePath: String): String? {
        var braceDepth = 0
        var parenDepth = 0
        var inSingleLineComment = false
        var inMultiLineComment = false
        var inDoubleQuote = false
        var inSingleQuote = false
        var inRawString = false
        var i = 0
        val n = code.length

        while (i < n) {
            val c = code[i]
            val next = if (i + 1 < n) code[i + 1] else '\u0000'
            val next2 = if (i + 2 < n) code[i + 2] else '\u0000'

            if (inSingleLineComment) {
                if (c == '\n') inSingleLineComment = false
                i++
                continue
            }
            if (inMultiLineComment) {
                if (c == '*' && next == '/') {
                    inMultiLineComment = false
                    i += 2
                    continue
                }
                i++
                continue
            }
            if (inRawString) {
                if (c == '"' && next == '"' && next2 == '"') {
                    inRawString = false
                    i += 3
                    continue
                }
                i++
                continue
            }
            if (inDoubleQuote) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inDoubleQuote = false
                i++
                continue
            }
            if (inSingleQuote) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '\'') inSingleQuote = false
                i++
                continue
            }

            if (c == '/' && next == '/') {
                inSingleLineComment = true
                i += 2
                continue
            }
            if (c == '/' && next == '*') {
                inMultiLineComment = true
                i += 2
                continue
            }
            if (c == '"' && next == '"' && next2 == '"') {
                inRawString = true
                i += 3
                continue
            }
            if (c == '"') {
                inDoubleQuote = true
                i++
                continue
            }
            if (c == '\'') {
                inSingleQuote = true
                i++
                continue
            }

            when (c) {
                '{' -> braceDepth++
                '}' -> {
                    braceDepth--
                    if (braceDepth < 0) {
                        return "Syntax validation failed in '$filePath': unexpected closing brace '}'"
                    }
                }
                '(' -> parenDepth++
                ')' -> {
                    parenDepth--
                    if (parenDepth < 0) {
                        return "Syntax validation failed in '$filePath': unexpected closing parenthesis ')'"
                    }
                }
            }
            i++
        }

        if (braceDepth != 0) {
            return "Syntax validation failed in '$filePath': unbalanced curly braces (net depth $braceDepth)"
        }
        if (parenDepth != 0) {
            return "Syntax validation failed in '$filePath': unbalanced parentheses (net depth $parenDepth)"
        }
        return null
    }

    internal fun checkXmlWellFormed(xmlContent: String, filePath: String): String? {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
            }
            val builder = factory.newDocumentBuilder()
            builder.setErrorHandler(null)
            builder.parse(InputSource(StringReader(xmlContent)))
            null
        } catch (e: Exception) {
            "Malformed XML in '$filePath': ${e.message}"
        }
    }

    fun computeFingerprint(edits: List<CoordinatedFileEdit>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (e in edits.sortedBy { it.relativePath }) {
            digest.update(e.relativePath.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(e.finalContent.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(24)
    }
}
