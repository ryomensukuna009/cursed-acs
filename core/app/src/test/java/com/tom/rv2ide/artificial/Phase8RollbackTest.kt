package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.coding.AutonomousRollbackManager
import com.tom.rv2ide.artificial.coding.FileRollbackRecord
import com.tom.rv2ide.artificial.coding.RollbackConflictReason
import com.tom.rv2ide.artificial.coding.RollbackOperation
import com.tom.rv2ide.artificial.coding.SessionRollbackJournal
import com.tom.rv2ide.artificial.coding.TransactionalFileCoordinator
import com.tom.rv2ide.artificial.coding.CoordinatedFileEdit
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Phase 8.5A: Session-Safe Autonomous Edit Rollback Tests.
 *
 * Validates session-scoped journals, multi-wave pre-state retention,
 * conflict detection, partial safe reverts, path traversal containment,
 * disk persistence, thread-safety, and cleanup safety.
 */
class Phase8RollbackTest {

    private lateinit var testProjectRoot: File

    @Before
    fun setUp() {
        testProjectRoot = Files.createTempDirectory("acs_rollback_test_proj").toFile()
        AutonomousRollbackManager.clearMemory()
        AutonomousRollbackManager.customJournalStorageDir = null
    }

    @After
    fun tearDown() {
        AutonomousRollbackManager.clearMemory()
        AutonomousRollbackManager.customJournalStorageDir = null
        testProjectRoot.deleteRecursively()
    }

    @Test
    fun `test 1 - session-scoped journal records files across waves`() {
        val sessionId = UUID.randomUUID().toString()
        val file1 = File(testProjectRoot, "File1.kt").apply { writeText("original file 1") }
        val file2 = File(testProjectRoot, "File2.kt").apply { writeText("original file 2") }

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)

        // Wave 1: edit file 1
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, file1, "File1.kt")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, file1, "File1.kt", "wave 1 content")

        // Wave 2: edit file 2
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, file2, "File2.kt")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, file2, "File2.kt", "wave 2 content")

        val journal = AutonomousRollbackManager.getJournal(sessionId, testProjectRoot)
        assertNotNull("Journal must exist", journal)
        assertEquals(2, journal!!.entries.size)

        val entry1 = journal.entries["File1.kt"]!!
        assertEquals("original file 1", entry1.originalContent)
        assertEquals("wave 1 content", entry1.latestWrittenContent)

        val entry2 = journal.entries["File2.kt"]!!
        assertEquals("original file 2", entry2.originalContent)
        assertEquals("wave 2 content", entry2.latestWrittenContent)
    }

    @Test
    fun `test 2 - first-wave pre-edit content is preserved when a file is edited across multiple waves`() {
        val sessionId = UUID.randomUUID().toString()
        val target = File(testProjectRoot, "src/Main.cpp")
        target.parentFile?.mkdirs()
        target.writeText("// v0 original baseline")

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)

        // Wave 1: v0 -> v1
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, target, "src/Main.cpp")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, target, "src/Main.cpp", "// v1 wave 1 edit")

        // Wave 2: v1 -> v2 (recordFilePreState must NOT overwrite original baseline v0!)
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, target, "src/Main.cpp")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, target, "src/Main.cpp", "// v2 wave 2 edit")

        // Wave 3: v2 -> v3
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, target, "src/Main.cpp")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, target, "src/Main.cpp", "// v3 wave 3 edit")

        val journal = AutonomousRollbackManager.getJournal(sessionId, testProjectRoot)
        assertNotNull(journal)
        val entry = journal!!.entries["src/Main.cpp"]!!

        assertEquals("// v0 original baseline", entry.originalContent)
        assertEquals("// v3 wave 3 edit", entry.latestWrittenContent)
    }

    @Test
    fun `test 3 - newly created files are tracked and marked for deletion on rollback`() {
        val sessionId = UUID.randomUUID().toString()
        val newFile = File(testProjectRoot, "GeneratedHelper.java")
        assertFalse("File must not exist before run", newFile.exists())

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, newFile, "GeneratedHelper.java")

        // Agent writes new file
        newFile.writeText("public class GeneratedHelper {}")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, newFile, "GeneratedHelper.java", "public class GeneratedHelper {}")

        assertTrue("New file should exist on disk", newFile.exists())

        val result = AutonomousRollbackManager.rollbackSession(sessionId, testProjectRoot)
        assertTrue("Rollback must succeed", result.success)
        assertEquals(1, result.revertedCount)
        assertFalse("Newly created file must be deleted upon rollback", newFile.exists())
    }

    @Test
    fun `test 4 - existing files modified are restored to pre-run content on rollback`() {
        val sessionId = UUID.randomUUID().toString()
        val existing = File(testProjectRoot, "Config.json").apply { writeText("{\"version\": 1}") }

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, existing, "Config.json")

        existing.writeText("{\"version\": 2, \"agent_injected\": true}")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, existing, "Config.json", "{\"version\": 2, \"agent_injected\": true}")

        val result = AutonomousRollbackManager.rollbackSession(sessionId, testProjectRoot)
        assertTrue(result.success)
        assertEquals(1, result.revertedCount)
        assertEquals("{\"version\": 1}", existing.readText())
    }

    @Test
    fun `test 5 - deletion or reversion of multiple files in a single session`() {
        val sessionId = UUID.randomUUID().toString()
        val f1 = File(testProjectRoot, "A.kt").apply { writeText("orig A") }
        val f2 = File(testProjectRoot, "B.kt").apply { writeText("orig B") }
        val f3 = File(testProjectRoot, "C.kt") // new file

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)

        // Pre-state
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, f1, "A.kt")
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, f2, "B.kt")
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, f3, "C.kt")

        // Agent writes
        f1.writeText("agent A")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, f1, "A.kt", "agent A")
        f2.writeText("agent B")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, f2, "B.kt", "agent B")
        f3.writeText("agent C")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, f3, "C.kt", "agent C")

        val result = AutonomousRollbackManager.rollbackSession(sessionId, testProjectRoot)
        assertTrue(result.success)
        assertEquals(3, result.revertedCount)
        assertEquals("orig A", f1.readText())
        assertEquals("orig B", f2.readText())
        assertFalse("New file C.kt must be deleted", f3.exists())
    }

    @Test
    fun `test 6 - conflict detection - external modification after agent edit is detected and NOT overwritten`() {
        val sessionId = UUID.randomUUID().toString()
        val file = File(testProjectRoot, "Main.kt").apply { writeText("orig baseline") }

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, file, "Main.kt")

        file.writeText("agent modified version")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, file, "Main.kt", "agent modified version")

        // User manually edits the file after the agent wrote it!
        file.writeText("user manual edit - DO NOT OVERWRITE")

        val result = AutonomousRollbackManager.rollbackSession(sessionId, testProjectRoot)
        assertFalse("Rollback must reflect conflict", result.success)
        assertEquals(0, result.revertedCount)
        assertEquals(1, result.conflictCount)
        assertEquals(RollbackConflictReason.MODIFIED_EXTERNALLY, result.conflicts[0].reason)
        assertEquals("user manual edit - DO NOT OVERWRITE", file.readText())
    }

    @Test
    fun `test 7 - conflict detection - external modification to newly created file is detected and NOT deleted`() {
        val sessionId = UUID.randomUUID().toString()
        val newFile = File(testProjectRoot, "NewUtil.kt")

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, newFile, "NewUtil.kt")

        newFile.writeText("agent new class")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, newFile, "NewUtil.kt", "agent new class")

        // User edits the newly created file
        newFile.writeText("user edited agent's new class with custom logic")

        val result = AutonomousRollbackManager.rollbackSession(sessionId, testProjectRoot)
        assertFalse("Rollback must reflect conflict", result.success)
        assertEquals(0, result.revertedCount)
        assertEquals(1, result.conflictCount)
        assertTrue("New file with user changes must NOT be deleted", newFile.exists())
        assertEquals("user edited agent's new class with custom logic", newFile.readText())
    }

    @Test
    fun `test 8 - partial rollback - non-conflicting files revert while conflicting files remain untouched`() {
        val sessionId = UUID.randomUUID().toString()
        val fileClean = File(testProjectRoot, "Clean.kt").apply { writeText("orig Clean") }
        val fileConflict = File(testProjectRoot, "Conflict.kt").apply { writeText("orig Conflict") }

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)

        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, fileClean, "Clean.kt")
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, fileConflict, "Conflict.kt")

        fileClean.writeText("agent Clean")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, fileClean, "Clean.kt", "agent Clean")

        fileConflict.writeText("agent Conflict")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, fileConflict, "Conflict.kt", "agent Conflict")

        // User edits only fileConflict
        fileConflict.writeText("user modified Conflict.kt")

        val result = AutonomousRollbackManager.rollbackSession(sessionId, testProjectRoot)
        assertEquals(1, result.revertedCount)
        assertEquals(1, result.conflictCount)

        // Clean.kt reverted to pre-run
        assertEquals("orig Clean", fileClean.readText())
        // Conflict.kt retained user manual edits
        assertEquals("user modified Conflict.kt", fileConflict.readText())
    }

    @Test
    fun `test 9 - project boundary containment - path traversal is rejected`() {
        val sessionId = UUID.randomUUID().toString()
        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)

        val outsideFile = File(testProjectRoot.parentFile, "Outside.txt").apply { writeText("outside secret") }

        var rejected = false
        try {
            AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, outsideFile, "../Outside.txt")
        } catch (_: SecurityException) {
            rejected = true
        }

        assertTrue("Path traversal outside project root must be rejected with SecurityException", rejected)
        assertEquals("outside secret", outsideFile.readText())
        outsideFile.delete()
    }

    @Test
    fun `test 10 - idempotency - rolling back an already-rolled-back session is handled gracefully`() {
        val sessionId = UUID.randomUUID().toString()
        val file = File(testProjectRoot, "File.kt").apply { writeText("original") }

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, file, "File.kt")

        file.writeText("agent")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, file, "File.kt", "agent")

        // First rollback
        val res1 = AutonomousRollbackManager.rollbackSession(sessionId, testProjectRoot)
        assertTrue(res1.success)
        assertEquals(1, res1.revertedCount)
        assertEquals("original", file.readText())

        // Second rollback (idempotent call)
        val res2 = AutonomousRollbackManager.rollbackSession(sessionId, testProjectRoot)
        assertTrue(res2.success)
        assertEquals(0, res2.revertedCount)
        assertEquals(1, res2.skippedCount)
        assertEquals("original", file.readText())
    }

    @Test
    fun `test 11 - journal persistence and reload from disk across simulated restart`() {
        val sessionId = UUID.randomUUID().toString()
        val file = File(testProjectRoot, "Service.kt").apply { writeText("fun oldService()") }

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, file, "Service.kt")

        file.writeText("fun newService() { agentImpl() }")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, file, "Service.kt", "fun newService() { agentImpl() }")

        // Simulate app kill / memory wipe
        AutonomousRollbackManager.clearMemory()

        // Load journal from disk
        val loaded = AutonomousRollbackManager.getJournal(sessionId, testProjectRoot)
        assertNotNull("Journal must be reloadable from disk", loaded)
        assertEquals(1, loaded!!.entries.size)
        assertEquals("fun oldService()", loaded.entries["Service.kt"]!!.originalContent)

        // Rollback using reloaded journal
        val result = AutonomousRollbackManager.rollbackSession(sessionId, testProjectRoot)
        assertTrue(result.success)
        assertEquals(1, result.revertedCount)
        assertEquals("fun oldService()", file.readText())
    }

    @Test
    fun `test 12 - rollback result accurately reports reverted, conflict, and skipped file counts`() {
        val sessionId = UUID.randomUUID().toString()
        val f1 = File(testProjectRoot, "Revert1.kt").apply { writeText("orig1") }
        val f2 = File(testProjectRoot, "Revert2.kt").apply { writeText("orig2") }
        val fConflict = File(testProjectRoot, "Conflict.kt").apply { writeText("origConflict") }
        val fDeletedNew = File(testProjectRoot, "DeletedNew.kt") // new file that gets deleted before rollback

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)

        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, f1, "Revert1.kt")
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, f2, "Revert2.kt")
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, fConflict, "Conflict.kt")
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, fDeletedNew, "DeletedNew.kt")

        f1.writeText("agent1")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, f1, "Revert1.kt", "agent1")
        f2.writeText("agent2")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, f2, "Revert2.kt", "agent2")
        fConflict.writeText("agentConflict")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, fConflict, "Conflict.kt", "agentConflict")
        fDeletedNew.writeText("agentNew")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, fDeletedNew, "DeletedNew.kt", "agentNew")

        // Trigger external conflict on fConflict
        fConflict.writeText("user manual override")

        // Trigger external deletion of fDeletedNew
        fDeletedNew.delete()

        val result = AutonomousRollbackManager.rollbackSession(sessionId, testProjectRoot)
        assertEquals(4, result.totalEntries)
        assertEquals(2, result.revertedCount)
        assertEquals(1, result.conflictCount)
        assertEquals(1, result.skippedCount)
        assertFalse(result.success) // partial because 1 conflict
    }

    @Test
    fun `test 13 - rolling back a session with no modified files returns clean zero-change result`() {
        val sessionId = UUID.randomUUID().toString()
        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)

        val result = AutonomousRollbackManager.rollbackSession(sessionId, testProjectRoot)
        assertTrue(result.success)
        assertEquals(0, result.totalEntries)
        assertEquals(0, result.revertedCount)
        assertEquals(0, result.conflictCount)
        assertEquals(0, result.skippedCount)
    }

    @Test
    fun `test 14 - concurrent edits and rollback cannot corrupt the same file`() {
        val sessionId = UUID.randomUUID().toString()
        val file = File(testProjectRoot, "Concurrent.txt").apply { writeText("baseline initial") }

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, file, "Concurrent.txt")

        file.writeText("agent write")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, file, "Concurrent.txt", "agent write")

        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)
        val errors = mutableListOf<Throwable>()

        for (i in 0 until 4) {
            executor.submit {
                try {
                    AutonomousRollbackManager.rollbackSession(sessionId, testProjectRoot)
                } catch (t: Throwable) {
                    synchronized(errors) { errors.add(t) }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Threads must complete within timeout", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue("No exceptions during concurrent rollback: $errors", errors.isEmpty())
        assertEquals("File must safely contain baseline initial", "baseline initial", file.readText())
    }

    @Test
    fun `test 15 - snapshot cleanup does not delete an active or recoverable rollback journal`() {
        val activeSession1 = "active_1"
        val activeSession2 = "active_2"
        val completedSession = "completed_3"

        val j1 = AutonomousRollbackManager.startSession(activeSession1, testProjectRoot)
        j1.isRolledBack = false
        AutonomousRollbackManager.persistJournalToDisk(j1, testProjectRoot)

        val j2 = AutonomousRollbackManager.startSession(activeSession2, testProjectRoot)
        j2.isRolledBack = false
        AutonomousRollbackManager.persistJournalToDisk(j2, testProjectRoot)

        val j3 = AutonomousRollbackManager.startSession(completedSession, testProjectRoot)
        j3.isRolledBack = true
        j3.rolledBackAt = System.currentTimeMillis() - 100000
        AutonomousRollbackManager.persistJournalToDisk(j3, testProjectRoot)

        // Clear memory to test disk state
        AutonomousRollbackManager.clearMemory()

        // Clean up with maxJournalsToKeep = 1
        AutonomousRollbackManager.cleanupOldJournals(testProjectRoot, maxJournalsToKeep = 1)

        val activeReload1 = AutonomousRollbackManager.loadJournalFromDisk(activeSession1, testProjectRoot)
        val activeReload2 = AutonomousRollbackManager.loadJournalFromDisk(activeSession2, testProjectRoot)

        assertNotNull("Active recoverable journal 1 must NOT be deleted", activeReload1)
        assertNotNull("Active recoverable journal 2 must NOT be deleted", activeReload2)
        assertFalse(activeReload1!!.isRolledBack)
        assertFalse(activeReload2!!.isRolledBack)
    }
}
