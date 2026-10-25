package com.tom.rv2ide.artificial.tools

import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.tom.rv2ide.artificial.secrets.SecretRedactor

/**
 * Metadata and content record for a historical tool execution result.
 */
data class HistoricalToolOutputEntry(
    val callId: String,
    val sessionId: String,
    val toolName: String,
    val argumentsJson: String,
    val fullContent: String,
    val timestamp: Long = System.currentTimeMillis(),
    val totalLines: Int = if (fullContent.isEmpty()) 0 else fullContent.lines().size,
    val characterCount: Int = fullContent.length,
    val isPrunedInContext: Boolean = false,
    val filePath: String? = null,
    val fileLastModified: Long? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("callId", callId)
        put("sessionId", sessionId)
        put("toolName", toolName)
        put("argumentsJson", argumentsJson)
        put("fullContent", fullContent)
        put("timestamp", timestamp)
        put("totalLines", totalLines)
        put("characterCount", characterCount)
        put("isPrunedInContext", isPrunedInContext)
        if (filePath != null) put("filePath", filePath)
        if (fileLastModified != null) put("fileLastModified", fileLastModified)
    }

    companion object {
        fun fromJson(json: JSONObject): HistoricalToolOutputEntry = HistoricalToolOutputEntry(
            callId = json.optString("callId", ""),
            sessionId = json.optString("sessionId", "session_0"),
            toolName = json.optString("toolName", ""),
            argumentsJson = json.optString("argumentsJson", "{}"),
            fullContent = json.optString("fullContent", ""),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            totalLines = json.optInt("totalLines", 0),
            characterCount = json.optInt("characterCount", 0),
            isPrunedInContext = json.optBoolean("isPrunedInContext", false),
            filePath = json.optString("filePath", "").takeIf { it.isNotBlank() },
            fileLastModified = if (json.has("fileLastModified")) json.optLong("fileLastModified") else null
        )
    }
}

/**
 * Context-aware historical tool output storage and retrieval engine.
 *
 * Capabilities:
 * 1. Bounded LRU in-memory cache of tool outputs per session.
 * 2. Optional disk persistence in session-bounded directory (zero external dependencies).
 * 3. File read deduplication cache: detects if a requested file read has identical mtime/size.
 * 4. Sub-slice retrieval: allows inspection of historical outputs by line range or search query
 *    without reloading or re-transmitting entire bloated outputs into LLM context.
 */
object HistoricalToolOutputStore {

    private const val MAX_IN_MEMORY_ENTRIES = 120

    private val entriesByCallId = ConcurrentHashMap<String, HistoricalToolOutputEntry>()
    private val callIdsBySession = ConcurrentHashMap<String, MutableSet<String>>()
    private val cachedFileReads = ConcurrentHashMap<String, String>() // normalizedPath -> callId

    @Volatile
    private var baseStorageDir: File? = null

    fun setStorageDirectory(dir: File) {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        baseStorageDir = dir
    }

    fun getStorageDirectory(): File? = baseStorageDir

    /**
     * Records a tool output in memory and optional persistent storage.
     */
    fun recordOutput(
        sessionId: String,
        callId: String,
        toolName: String,
        argumentsJson: String,
        content: String,
        filePath: String? = null,
        fileLastModified: Long? = null
    ): HistoricalToolOutputEntry {
        val sanitizedContent = SecretRedactor.redact(content)
        val sanitizedArgs = SecretRedactor.redact(argumentsJson)
        val entry = HistoricalToolOutputEntry(
            callId = callId,
            sessionId = sessionId,
            toolName = toolName,
            argumentsJson = sanitizedArgs,
            fullContent = sanitizedContent,
            filePath = filePath,
            fileLastModified = fileLastModified
        )

        entriesByCallId[callId] = entry
        callIdsBySession.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }.add(callId)

        if (filePath != null) {
            cachedFileReads[normalizePath(filePath)] = callId
        }

        pruneMemoryLruIfNeeded()
        persistEntryToDisk(entry)

        return entry
    }

    /**
     * Marks an entry as having been pruned in the conversational context.
     */
    fun markPruned(callId: String) {
        val existing = getOutput(callId) ?: return
        val updated = existing.copy(isPrunedInContext = true)
        entriesByCallId[callId] = updated
        persistEntryToDisk(updated)
    }

    /**
     * Retrieves an entry by tool call ID.
     */
    fun getOutput(callId: String): HistoricalToolOutputEntry? {
        val inMemory = entriesByCallId[callId]
        if (inMemory != null) return inMemory
        return loadEntryFromDisk(callId)?.also {
            entriesByCallId[callId] = it
        }
    }

    /**
     * Checks if a cached file read is available and still valid based on file modification timestamp.
     */
    fun getCachedFileRead(filePath: String, currentLastModified: Long): HistoricalToolOutputEntry? {
        val callId = cachedFileReads[normalizePath(filePath)] ?: return null
        val entry = getOutput(callId) ?: return null
        if (entry.fileLastModified != null && entry.fileLastModified == currentLastModified) {
            return entry
        }
        return null
    }

    /**
     * Retrieves a specific line range (1-based, inclusive) from a historical tool output.
     */
    fun getOutputRange(callId: String, startLine: Int, endLine: Int): String? {
        val entry = getOutput(callId) ?: return null
        val lines = entry.fullContent.lines()
        if (lines.isEmpty()) return ""

        val safeStart = startLine.coerceIn(1, lines.size)
        val safeEnd = endLine.coerceIn(safeStart, lines.size)

        return buildString {
            appendLine("=== HISTORICAL OUTPUT [${entry.toolName} (Call ID: $callId)] (Lines $safeStart-$safeEnd of ${lines.size}) ===")
            for (i in safeStart..safeEnd) {
                appendLine("L$i: ${lines[i - 1]}")
            }
        }.trimEnd()
    }

    /**
     * Searches a historical tool output for lines matching a query string.
     */
    fun searchOutput(callId: String, query: String, maxMatches: Int = 20): String? {
        val entry = getOutput(callId) ?: return null
        val lines = entry.fullContent.lines()
        val matchedLines = mutableListOf<String>()

        for ((idx, line) in lines.withIndex()) {
            if (line.contains(query, ignoreCase = true)) {
                matchedLines.add("L${idx + 1}: $line")
                if (matchedLines.size >= maxMatches) break
            }
        }

        return buildString {
            appendLine("=== HISTORICAL OUTPUT SEARCH [${entry.toolName} (Call ID: $callId)] Query: '$query' (${matchedLines.size} match(es)) ===")
            if (matchedLines.isEmpty()) {
                appendLine("No matching lines found for query '$query'. Total lines in output: ${lines.size}.")
            } else {
                matchedLines.forEach { appendLine(it) }
                if (matchedLines.size >= maxMatches) {
                    appendLine("... [Capped at $maxMatches matches of ${lines.size} total lines] ...")
                }
            }
        }.trimEnd()
    }

    /**
     * Clears in-memory records and disk files for a specific session.
     */
    fun clearSession(sessionId: String) {
        val callIds = callIdsBySession.remove(sessionId) ?: return
        for (id in callIds) {
            entriesByCallId.remove(id)
        }
        val dir = baseStorageDir ?: return
        try {
            val sessionDir = File(dir, sanitizeFileName(sessionId))
            if (sessionDir.exists()) {
                sessionDir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }

    /**
     * Clears all in-memory entries (for testing).
     */
    fun resetForTesting() {
        entriesByCallId.clear()
        callIdsBySession.clear()
        cachedFileReads.clear()
    }

    private fun pruneMemoryLruIfNeeded() {
        if (entriesByCallId.size <= MAX_IN_MEMORY_ENTRIES) return
        val oldestKeys = entriesByCallId.entries
            .sortedBy { it.value.timestamp }
            .take(entriesByCallId.size - MAX_IN_MEMORY_ENTRIES)
            .map { it.key }
        for (k in oldestKeys) {
            entriesByCallId.remove(k)
        }
    }

    private fun persistEntryToDisk(entry: HistoricalToolOutputEntry) {
        val base = baseStorageDir ?: return
        try {
            val sessionDir = File(base, sanitizeFileName(entry.sessionId))
            if (!sessionDir.exists()) sessionDir.mkdirs()
            val file = File(sessionDir, sanitizeFileName(entry.callId) + ".json")
            val tmp = File(sessionDir, sanitizeFileName(entry.callId) + ".json.tmp")
            tmp.writeText(entry.toJson().toString())
            if (!tmp.renameTo(file)) {
                file.writeText(entry.toJson().toString())
                tmp.delete()
            }
        } catch (_: Exception) {}
    }

    private fun loadEntryFromDisk(callId: String): HistoricalToolOutputEntry? {
        val base = baseStorageDir ?: return null
        return try {
            val candidateFile = base.walkTopDown().maxDepth(3).firstOrNull {
                it.isFile && it.name == sanitizeFileName(callId) + ".json"
            } ?: return null
            val obj = JSONObject(candidateFile.readText())
            val entry = HistoricalToolOutputEntry.fromJson(obj)
            if (entry.callId.isBlank() || entry.toolName.isBlank()) null else entry
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizePath(path: String): String {
        return path.replace('\\', '/').trim().lowercase()
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
