package com.tom.rv2ide.artificial.coding

import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Type of file operation performed during an autonomous run.
 */
enum class RollbackOperation {
    CREATED,
    MODIFIED,
    DELETED
}

/**
 * Reasons why a file cannot be safely rolled back without data loss.
 */
enum class RollbackConflictReason(val description: String) {
    MODIFIED_EXTERNALLY("File was modified externally after the AI agent wrote it."),
    DELETED_EXTERNALLY("File was deleted externally after the AI agent modified it."),
    RECREATED_EXTERNALLY("File was recreated externally after the AI agent deleted it."),
    OUTSIDE_PROJECT_ROOT("File path escapes the project root directory boundary.")
}

/**
 * Specific conflict preventing automatic file rollback.
 */
data class RollbackConflict(
    val relativePath: String,
    val file: File,
    val reason: RollbackConflictReason,
    val detail: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("relativePath", relativePath)
        put("filePath", file.absolutePath)
        put("reason", reason.name)
        put("detail", detail)
    }
}

/**
 * Outcome of rolling back an individual file in a session.
 */
sealed class RollbackFileResult {
    data class Success(
        val relativePath: String,
        val operation: RollbackOperation,
        val detail: String
    ) : RollbackFileResult()

    data class Conflict(
        val conflict: RollbackConflict
    ) : RollbackFileResult()

    data class Skipped(
        val relativePath: String,
        val reason: String
    ) : RollbackFileResult()
}

/**
 * Summary result of attempting to rollback an autonomous engineering run.
 */
data class SessionRollbackResult(
    val sessionId: String,
    val success: Boolean,
    val totalEntries: Int,
    val revertedCount: Int,
    val conflictCount: Int,
    val skippedCount: Int,
    val fileResults: List<RollbackFileResult>,
    val conflicts: List<RollbackConflict> = fileResults.filterIsInstance<RollbackFileResult.Conflict>().map { it.conflict },
    val message: String
) {
    fun formatSummary(): String = buildString {
        appendLine("Rollback Result for session '$sessionId': ${if (success) "SUCCESS" else "PARTIAL/BLOCKED"}")
        appendLine("Total files recorded: $totalEntries")
        appendLine("Successfully reverted: $revertedCount")
        appendLine("Conflicts preserved: $conflictCount")
        appendLine("Skipped: $skippedCount")
        if (conflicts.isNotEmpty()) {
            appendLine("Conflicts:")
            conflicts.forEach { c ->
                appendLine("  • ${c.relativePath}: ${c.reason.description} (${c.detail})")
            }
        }
    }.trim()
}

/**
 * Tracked pre-state and latest agent-written state for a single file in a session.
 */
data class FileRollbackRecord(
    val relativePath: String,
    val canonicalPath: String,
    val existedBeforeRun: Boolean,
    val originalContent: String?,
    val originalChecksum: String?,
    var latestWrittenContent: String?,
    var latestWrittenChecksum: String?,
    var operation: RollbackOperation = if (existedBeforeRun) RollbackOperation.MODIFIED else RollbackOperation.CREATED,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("relativePath", relativePath)
        put("canonicalPath", canonicalPath)
        put("existedBeforeRun", existedBeforeRun)
        put("originalContent", originalContent ?: JSONObject.NULL)
        put("originalChecksum", originalChecksum ?: JSONObject.NULL)
        put("latestWrittenContent", latestWrittenContent ?: JSONObject.NULL)
        put("latestWrittenChecksum", latestWrittenChecksum ?: JSONObject.NULL)
        put("operation", operation.name)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(obj: JSONObject): FileRollbackRecord {
            val opName = obj.optString("operation", RollbackOperation.MODIFIED.name)
            val op = try { RollbackOperation.valueOf(opName) } catch (_: Exception) { RollbackOperation.MODIFIED }
            return FileRollbackRecord(
                relativePath = obj.getString("relativePath"),
                canonicalPath = obj.getString("canonicalPath"),
                existedBeforeRun = obj.getBoolean("existedBeforeRun"),
                originalContent = if (obj.isNull("originalContent")) null else obj.getString("originalContent"),
                originalChecksum = if (obj.isNull("originalChecksum")) null else obj.getString("originalChecksum"),
                latestWrittenContent = if (obj.isNull("latestWrittenContent")) null else obj.getString("latestWrittenContent"),
                latestWrittenChecksum = if (obj.isNull("latestWrittenChecksum")) null else obj.getString("latestWrittenChecksum"),
                operation = op,
                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}

/**
 * Persisted journal for an entire autonomous engineering session.
 */
data class SessionRollbackJournal(
    val sessionId: String,
    val projectRootPath: String,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var isRolledBack: Boolean = false,
    var rolledBackAt: Long? = null,
    val entries: LinkedHashMap<String, FileRollbackRecord> = LinkedHashMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("projectRootPath", projectRootPath)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("isRolledBack", isRolledBack)
        put("rolledBackAt", rolledBackAt ?: JSONObject.NULL)
        val entriesArr = JSONArray()
        entries.values.forEach { entriesArr.put(it.toJson()) }
        put("entries", entriesArr)
    }

    companion object {
        fun fromJson(obj: JSONObject): SessionRollbackJournal {
            val journal = SessionRollbackJournal(
                sessionId = obj.getString("sessionId"),
                projectRootPath = obj.getString("projectRootPath"),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                isRolledBack = obj.optBoolean("isRolledBack", false),
                rolledBackAt = if (obj.isNull("rolledBackAt")) null else obj.optLong("rolledBackAt")
            )
            val entriesArr = obj.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until entriesArr.length()) {
                val item = entriesArr.getJSONObject(i)
                val record = FileRollbackRecord.fromJson(item)
                journal.entries[record.relativePath] = record
            }
            return journal
        }
    }
}

/**
 * Coordinates session-scoped file rollback journals, external conflict detection,
 * and safe idempotent restoration of pre-run project states.
 */
object AutonomousRollbackManager {

    private val log = LoggerFactory.getLogger(AutonomousRollbackManager::class.java)

    private val journalsBySession = ConcurrentHashMap<String, SessionRollbackJournal>()
    private val sessionLocks = ConcurrentHashMap<String, ReentrantLock>()

    /** Custom storage directory override for testing or sandboxing */
    @Volatile
    var customJournalStorageDir: File? = null

    private fun getLock(sessionId: String): ReentrantLock {
        return sessionLocks.computeIfAbsent(sessionId) { ReentrantLock() }
    }

    /**
     * Initializes a rollback journal for a newly started autonomous session.
     */
    fun startSession(sessionId: String, projectRoot: File): SessionRollbackJournal {
        val rootPath = projectRoot.canonicalPath
        val journal = SessionRollbackJournal(
            sessionId = sessionId,
            projectRootPath = rootPath
        )
        journalsBySession[sessionId] = journal
        persistJournalToDisk(journal, projectRoot)
        log.info("[RollbackManager] Started rollback session: {} (root: {})", sessionId, rootPath)
        return journal
    }

    /**
     * Returns an existing journal from memory or loads it from disk.
     */
    fun getJournal(sessionId: String, projectRoot: File? = null): SessionRollbackJournal? {
        journalsBySession[sessionId]?.let { return it }
        if (projectRoot != null) {
            val loaded = loadJournalFromDisk(sessionId, projectRoot)
            if (loaded != null) {
                journalsBySession[sessionId] = loaded
                return loaded
            }
        }
        return null
    }

    /**
     * Records the original pre-run file state BEFORE the agent performs its first write to [targetFile].
     *
     * Multi-wave rule: If [relativePath] already has an entry in this session, the original pre-run
     * content is PRESERVED and not overwritten by subsequent repair waves!
     */
    fun recordFilePreState(
        sessionId: String,
        projectRoot: File,
        targetFile: File,
        relativePath: String
    ) {
        val lock = getLock(sessionId)
        lock.withLock {
            val journal = getOrCreateJournal(sessionId, projectRoot)
            val canonicalRoot = projectRoot.canonicalFile
            val canonicalTarget = targetFile.canonicalFile

            if (!isPathWithinRoot(canonicalRoot, canonicalTarget)) {
                log.warn("[RollbackManager] Rejected recordFilePreState outside project root: {}", relativePath)
                throw SecurityException("Path '$relativePath' resolves outside project root.")
            }

            val normalizedRel = relativePath.replace('\\', '/').trimStart('/')
            if (journal.entries.containsKey(normalizedRel)) {
                // Baseline pre-run state already recorded in an earlier wave; keep baseline intact!
                return
            }

            val exists = canonicalTarget.exists() && canonicalTarget.isFile
            val originalContent = if (exists) {
                try {
                    canonicalTarget.readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    log.error("[RollbackManager] Failed to read pre-state for {}: {}", normalizedRel, e.message)
                    null
                }
            } else {
                null
            }

            val checksum = originalContent?.let { computeSha256(it) }
            val record = FileRollbackRecord(
                relativePath = normalizedRel,
                canonicalPath = canonicalTarget.canonicalPath,
                existedBeforeRun = exists,
                originalContent = originalContent,
                originalChecksum = checksum,
                latestWrittenContent = originalContent,
                latestWrittenChecksum = checksum,
                operation = if (exists) RollbackOperation.MODIFIED else RollbackOperation.CREATED
            )

            journal.entries[normalizedRel] = record
            journal.updatedAt = System.currentTimeMillis()
            persistJournalToDisk(journal, projectRoot)
            log.debug("[RollbackManager] Recorded pre-state for '{}' (exists={}, len={})", normalizedRel, exists, originalContent?.length ?: 0)
        }
    }

    /**
     * Updates the latest written state for [targetFile] after the agent successfully writes to it.
     */
    fun recordFilePostWrite(
        sessionId: String,
        projectRoot: File,
        targetFile: File,
        relativePath: String,
        newContent: String
    ) {
        val lock = getLock(sessionId)
        lock.withLock {
            val journal = getOrCreateJournal(sessionId, projectRoot)
            val canonicalRoot = projectRoot.canonicalFile
            val canonicalTarget = targetFile.canonicalFile

            if (!isPathWithinRoot(canonicalRoot, canonicalTarget)) {
                throw SecurityException("Path '$relativePath' resolves outside project root.")
            }

            val normalizedRel = relativePath.replace('\\', '/').trimStart('/')
            var record = journal.entries[normalizedRel]
            val newChecksum = computeSha256(newContent)

            if (record == null) {
                // If pre-state wasn't captured explicitly, capture it now
                val existed = false
                record = FileRollbackRecord(
                    relativePath = normalizedRel,
                    canonicalPath = canonicalTarget.canonicalPath,
                    existedBeforeRun = existed,
                    originalContent = null,
                    originalChecksum = null,
                    latestWrittenContent = newContent,
                    latestWrittenChecksum = newChecksum,
                    operation = RollbackOperation.CREATED
                )
                journal.entries[normalizedRel] = record
            } else {
                record.latestWrittenContent = newContent
                record.latestWrittenChecksum = newChecksum
            }

            journal.updatedAt = System.currentTimeMillis()
            persistJournalToDisk(journal, projectRoot)
            log.debug("[RollbackManager] Recorded post-write for '{}' (checksum={})", normalizedRel, newChecksum.take(8))
        }
    }

    /**
     * Records deletion of [targetFile] by the agent.
     */
    fun recordFileDeletion(
        sessionId: String,
        projectRoot: File,
        targetFile: File,
        relativePath: String
    ) {
        val lock = getLock(sessionId)
        lock.withLock {
            val journal = getOrCreateJournal(sessionId, projectRoot)
            val canonicalRoot = projectRoot.canonicalFile
            val canonicalTarget = targetFile.canonicalFile

            if (!isPathWithinRoot(canonicalRoot, canonicalTarget)) {
                throw SecurityException("Path '$relativePath' resolves outside project root.")
            }

            val normalizedRel = relativePath.replace('\\', '/').trimStart('/')
            val record = journal.entries[normalizedRel]
            if (record != null) {
                record.latestWrittenContent = null
                record.latestWrittenChecksum = null
                record.operation = RollbackOperation.DELETED
            }
            journal.updatedAt = System.currentTimeMillis()
            persistJournalToDisk(journal, projectRoot)
        }
    }

    /**
     * Safely rolls back changes recorded in [sessionId].
     *
     * Detects subsequent user edits or external file alterations. If an external edit is detected,
     * the file is preserved untouched and recorded as a conflict, allowing other non-conflicting
     * files to revert cleanly.
     */
    fun rollbackSession(
        sessionId: String,
        projectRootOverride: File? = null
    ): SessionRollbackResult {
        val lock = getLock(sessionId)
        lock.withLock {
            val journal = journalsBySession[sessionId]
                ?: (projectRootOverride?.let { loadJournalFromDisk(sessionId, it) })
                ?: return SessionRollbackResult(
                    sessionId = sessionId,
                    success = false,
                    totalEntries = 0,
                    revertedCount = 0,
                    conflictCount = 0,
                    skippedCount = 0,
                    fileResults = emptyList(),
                    message = "Rollback journal not found for session '$sessionId'."
                )

            val rootFile = projectRootOverride ?: File(journal.projectRootPath)
            val canonicalRoot = rootFile.canonicalFile

            if (journal.isRolledBack) {
                log.info("[RollbackManager] Session '{}' was already rolled back.", sessionId)
                return SessionRollbackResult(
                    sessionId = sessionId,
                    success = true,
                    totalEntries = journal.entries.size,
                    revertedCount = 0,
                    conflictCount = 0,
                    skippedCount = journal.entries.size,
                    fileResults = journal.entries.keys.map {
                        RollbackFileResult.Skipped(it, "Session already rolled back.")
                    },
                    message = "Session was already rolled back previously."
                )
            }

            if (journal.entries.isEmpty()) {
                journal.isRolledBack = true
                journal.rolledBackAt = System.currentTimeMillis()
                persistJournalToDisk(journal, rootFile)
                return SessionRollbackResult(
                    sessionId = sessionId,
                    success = true,
                    totalEntries = 0,
                    revertedCount = 0,
                    conflictCount = 0,
                    skippedCount = 0,
                    fileResults = emptyList(),
                    message = "No files were modified during this session."
                )
            }

            val fileResults = mutableListOf<RollbackFileResult>()
            var reverted = 0
            var conflicts = 0
            var skipped = 0

            for ((relPath, entry) in journal.entries) {
                val targetFile = File(entry.canonicalPath)
                val canonicalTarget = targetFile.canonicalFile

                // 1. Project containment check
                if (!isPathWithinRoot(canonicalRoot, canonicalTarget)) {
                    val conflict = RollbackConflict(
                        relativePath = relPath,
                        file = targetFile,
                        reason = RollbackConflictReason.OUTSIDE_PROJECT_ROOT,
                        detail = "Target file canonical path escapes project root."
                    )
                    fileResults.add(RollbackFileResult.Conflict(conflict))
                    conflicts++
                    continue
                }

                // 2. Case: Newly created file by agent
                if (!entry.existedBeforeRun) {
                    if (!canonicalTarget.exists()) {
                        fileResults.add(RollbackFileResult.Skipped(relPath, "File no longer exists on disk."))
                        skipped++
                        continue
                    }

                    val currentContent = canonicalTarget.readText(Charsets.UTF_8)
                    val currentChecksum = computeSha256(currentContent)

                    if (currentChecksum == entry.latestWrittenChecksum || currentContent == entry.latestWrittenContent) {
                        // Safe to delete newly created file
                        val deleted = canonicalTarget.delete()
                        if (deleted) {
                            AcsRepoMap.invalidateFile(canonicalTarget)
                            fileResults.add(RollbackFileResult.Success(relPath, RollbackOperation.CREATED, "Deleted agent-created file."))
                            reverted++
                        } else {
                            val conflict = RollbackConflict(
                                relativePath = relPath,
                                file = canonicalTarget,
                                reason = RollbackConflictReason.MODIFIED_EXTERNALLY,
                                detail = "Unable to delete newly created file from disk."
                            )
                            fileResults.add(RollbackFileResult.Conflict(conflict))
                            conflicts++
                        }
                    } else {
                        // Content was modified after agent created it
                        val conflict = RollbackConflict(
                            relativePath = relPath,
                            file = canonicalTarget,
                            reason = RollbackConflictReason.MODIFIED_EXTERNALLY,
                            detail = "File content was modified after the AI agent created it. Preserving user modifications."
                        )
                        fileResults.add(RollbackFileResult.Conflict(conflict))
                        conflicts++
                    }
                    continue
                }

                // 3. Case: Existing file modified by agent
                if (entry.existedBeforeRun && entry.operation == RollbackOperation.MODIFIED) {
                    if (!canonicalTarget.exists()) {
                        val conflict = RollbackConflict(
                            relativePath = relPath,
                            file = canonicalTarget,
                            reason = RollbackConflictReason.DELETED_EXTERNALLY,
                            detail = "File was deleted externally after the AI agent modified it."
                        )
                        fileResults.add(RollbackFileResult.Conflict(conflict))
                        conflicts++
                        continue
                    }

                    val currentContent = canonicalTarget.readText(Charsets.UTF_8)
                    val currentChecksum = computeSha256(currentContent)

                    if (currentChecksum == entry.latestWrittenChecksum || currentContent == entry.latestWrittenContent) {
                        // Current content still matches what the agent wrote: safe to restore baseline pre-state!
                        val origContent = entry.originalContent ?: ""
                        atomicWriteText(canonicalTarget, origContent)
                        AcsRepoMap.invalidateFile(canonicalTarget)
                        fileResults.add(RollbackFileResult.Success(relPath, RollbackOperation.MODIFIED, "Restored original pre-run content."))
                        reverted++
                    } else {
                        // File was edited after agent wrote it: conflict detected! Preserve user edits!
                        val conflict = RollbackConflict(
                            relativePath = relPath,
                            file = canonicalTarget,
                            reason = RollbackConflictReason.MODIFIED_EXTERNALLY,
                            detail = "File content differs from agent's latest written output. Preserving user modifications."
                        )
                        fileResults.add(RollbackFileResult.Conflict(conflict))
                        conflicts++
                    }
                    continue
                }

                // 4. Case: File was deleted by agent
                if (entry.operation == RollbackOperation.DELETED) {
                    if (canonicalTarget.exists()) {
                        val conflict = RollbackConflict(
                            relativePath = relPath,
                            file = canonicalTarget,
                            reason = RollbackConflictReason.RECREATED_EXTERNALLY,
                            detail = "File was recreated externally after the AI agent deleted it."
                        )
                        fileResults.add(RollbackFileResult.Conflict(conflict))
                        conflicts++
                        continue
                    }

                    // Restore deleted file from original content
                    val origContent = entry.originalContent ?: ""
                    atomicWriteText(canonicalTarget, origContent)
                    AcsRepoMap.invalidateFile(canonicalTarget)
                    fileResults.add(RollbackFileResult.Success(relPath, RollbackOperation.DELETED, "Restored previously deleted file."))
                    reverted++
                    continue
                }
            }

            val overallSuccess = conflicts == 0 && (reverted > 0 || skipped > 0)
            if (reverted > 0 || overallSuccess) {
                journal.isRolledBack = true
                journal.rolledBackAt = System.currentTimeMillis()
                persistJournalToDisk(journal, rootFile)
            }

            val summaryMsg = if (conflicts == 0) {
                "Successfully reverted $reverted file(s)."
            } else {
                "Reverted $reverted file(s); $conflicts file(s) had conflicting edits and were preserved."
            }

            return SessionRollbackResult(
                sessionId = sessionId,
                success = overallSuccess,
                totalEntries = journal.entries.size,
                revertedCount = reverted,
                conflictCount = conflicts,
                skippedCount = skipped,
                fileResults = fileResults,
                message = summaryMsg
            )
        }
    }

    /**
     * Inspects files recorded in [sessionId] to identify external user modifications or conflicts
     * without mutating any files on disk. Used for pre-flight inspection in change review UI.
     */
    fun checkConflicts(
        sessionId: String,
        projectRootOverride: File? = null
    ): List<RollbackConflict> {
        val lock = getLock(sessionId)
        lock.withLock {
            val journal = journalsBySession[sessionId]
                ?: (projectRootOverride?.let { loadJournalFromDisk(sessionId, it) })
                ?: return emptyList()

            val rootFile = projectRootOverride ?: File(journal.projectRootPath)
            val canonicalRoot = rootFile.canonicalFile
            val conflicts = mutableListOf<RollbackConflict>()

            for ((relPath, entry) in journal.entries) {
                val targetFile = File(entry.canonicalPath)
                val canonicalTarget = targetFile.canonicalFile

                if (!isPathWithinRoot(canonicalRoot, canonicalTarget)) {
                    conflicts.add(
                        RollbackConflict(
                            relativePath = relPath,
                            file = targetFile,
                            reason = RollbackConflictReason.OUTSIDE_PROJECT_ROOT,
                            detail = "Target file canonical path escapes project root."
                        )
                    )
                    continue
                }

                if (!entry.existedBeforeRun) {
                    if (canonicalTarget.exists()) {
                        val currentContent = canonicalTarget.readText(Charsets.UTF_8)
                        val currentChecksum = computeSha256(currentContent)
                        if (currentChecksum != entry.latestWrittenChecksum && currentContent != entry.latestWrittenContent) {
                            conflicts.add(
                                RollbackConflict(
                                    relativePath = relPath,
                                    file = canonicalTarget,
                                    reason = RollbackConflictReason.MODIFIED_EXTERNALLY,
                                    detail = "File content was modified externally after agent created it."
                                )
                            )
                        }
                    }
                } else if (entry.operation == RollbackOperation.MODIFIED) {
                    if (!canonicalTarget.exists()) {
                        conflicts.add(
                            RollbackConflict(
                                relativePath = relPath,
                                file = canonicalTarget,
                                reason = RollbackConflictReason.DELETED_EXTERNALLY,
                                detail = "File was deleted externally after AI agent modified it."
                            )
                        )
                    } else {
                        val currentContent = canonicalTarget.readText(Charsets.UTF_8)
                        val currentChecksum = computeSha256(currentContent)
                        if (currentChecksum != entry.latestWrittenChecksum && currentContent != entry.latestWrittenContent) {
                            conflicts.add(
                                RollbackConflict(
                                    relativePath = relPath,
                                    file = canonicalTarget,
                                    reason = RollbackConflictReason.MODIFIED_EXTERNALLY,
                                    detail = "File content differs from agent written output. Preserving user modifications."
                                )
                            )
                        }
                    }
                } else if (entry.operation == RollbackOperation.DELETED) {
                    if (canonicalTarget.exists()) {
                        conflicts.add(
                            RollbackConflict(
                                relativePath = relPath,
                                file = canonicalTarget,
                                reason = RollbackConflictReason.RECREATED_EXTERNALLY,
                                detail = "File was recreated externally after AI agent deleted it."
                            )
                        )
                    }
                }
            }

            return conflicts
        }
    }

    /**
     * Atomically writes [text] to [targetFile] via a temporary file replacement.
     */
    private fun atomicWriteText(targetFile: File, text: String) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val temp = File(parent ?: File("."), ".${targetFile.name}.roxum_rollback_tmp")
        temp.writeText(text, Charsets.UTF_8)
        temp.copyTo(targetFile, overwrite = true)
        temp.delete()
    }

    /**
     * Clears all in-memory journals (primarily used for unit testing).
     */
    fun clearMemory() {
        journalsBySession.clear()
        sessionLocks.clear()
    }

    /**
     * Completely discards a session journal from memory and disk.
     */
    fun discardJournal(sessionId: String, projectRoot: File) {
        val lock = getLock(sessionId)
        lock.withLock {
            journalsBySession.remove(sessionId)
            val file = getJournalFile(sessionId, projectRoot)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    /**
     * Prunes old journals. Never deletes an active or recoverable unrolled journal.
     */
    fun cleanupOldJournals(projectRoot: File, maxJournalsToKeep: Int = 20) {
        val dir = getJournalStorageDir(projectRoot)
        if (!dir.exists() || !dir.isDirectory) return

        val journalFiles = dir.listFiles { f -> f.isFile && f.extension == "json" } ?: return
        if (journalFiles.size <= maxJournalsToKeep) return

        val parsedJournals = journalFiles.mapNotNull { f ->
            try {
                val json = JSONObject(f.readText())
                f to SessionRollbackJournal.fromJson(json)
            } catch (_: Exception) {
                null
            }
        }

        // Only delete journals that have already been rolled back, sorted by oldest first
        val rolledBackFiles = parsedJournals.filter { it.second.isRolledBack }
            .sortedBy { it.second.updatedAt }

        var toRemove = journalFiles.size - maxJournalsToKeep
        for ((file, _) in rolledBackFiles) {
            if (toRemove <= 0) break
            file.delete()
            toRemove--
        }
    }

    private fun getOrCreateJournal(sessionId: String, projectRoot: File): SessionRollbackJournal {
        return journalsBySession.computeIfAbsent(sessionId) {
            loadJournalFromDisk(sessionId, projectRoot) ?: SessionRollbackJournal(
                sessionId = sessionId,
                projectRootPath = projectRoot.canonicalPath
            )
        }
    }

    fun getJournalStorageDir(projectRoot: File): File {
        customJournalStorageDir?.let { return it }
        val dir = File(projectRoot, ".roxum/rollback")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getJournalFile(sessionId: String, projectRoot: File): File {
        return File(getJournalStorageDir(projectRoot), "journal_$sessionId.json")
    }

    fun persistJournalToDisk(journal: SessionRollbackJournal, projectRoot: File) {
        try {
            val file = getJournalFile(journal.sessionId, projectRoot)
            file.parentFile?.mkdirs()
            val tempFile = File(file.parentFile, ".${file.name}.tmp")
            tempFile.writeText(journal.toJson().toString(2), Charsets.UTF_8)
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        } catch (e: Exception) {
            log.warn("[RollbackManager] Failed to persist journal {}: {}", journal.sessionId, e.message)
        }
    }

    fun loadJournalFromDisk(sessionId: String, projectRoot: File): SessionRollbackJournal? {
        val file = getJournalFile(sessionId, projectRoot)
        if (!file.exists() || !file.isFile) return null
        return try {
            val content = file.readText(Charsets.UTF_8)
            val json = JSONObject(content)
            SessionRollbackJournal.fromJson(json)
        } catch (e: Exception) {
            log.warn("[RollbackManager] Failed to load journal {}: {}", sessionId, e.message)
            null
        }
    }

    fun isPathWithinRoot(canonicalRoot: File, canonicalTarget: File): Boolean {
        val rootPath = canonicalRoot.toPath().normalize()
        val targetPath = canonicalTarget.toPath().normalize()
        return targetPath.startsWith(rootPath)
    }

    fun computeSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
