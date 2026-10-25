package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.coding.AutonomousRollbackManager
import com.tom.rv2ide.artificial.coding.RollbackConflictReason
import com.tom.rv2ide.artificial.coding.RollbackOperation
import com.tom.rv2ide.artificial.coding.TaskCheckpoint
import com.tom.rv2ide.artificial.coding.TaskCheckpointManager
import com.tom.rv2ide.artificial.diff.DiffEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Task 23 Unit Tests for AI Trust Engine:
 * Pre-flight conflict inspection, turn-based checkpoints, and rollback validation.
 */
class TrustEngineCheckpointTest {

    private lateinit var testProjectRoot: File

    @Before
    fun setUp() {
        testProjectRoot = Files.createTempDirectory("acs_trust_engine_test").toFile()
        AutonomousRollbackManager.clearMemory()
        AutonomousRollbackManager.customJournalStorageDir = null
        TaskCheckpointManager.setStorageDirectory(File(testProjectRoot, ".roxum/checkpoints"))
    }

    @After
    fun tearDown() {
        AutonomousRollbackManager.clearMemory()
        AutonomousRollbackManager.customJournalStorageDir = null
        testProjectRoot.deleteRecursively()
    }

    @Test
    fun `test 1 - checkConflicts detects modified file before rollback without mutating disk`() {
        val sessionId = UUID.randomUUID().toString()
        val file = File(testProjectRoot, "Service.kt").apply { writeText("original code") }

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, file, "Service.kt")

        // AI writes
        file.writeText("ai written code")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, file, "Service.kt", "ai written code")

        // No conflict yet
        val preConflicts = AutonomousRollbackManager.checkConflicts(sessionId, testProjectRoot)
        assertTrue("No conflict before external modification", preConflicts.isEmpty())

        // User manually modifies the file
        file.writeText("user manual modification")

        // checkConflicts detects it
        val postConflicts = AutonomousRollbackManager.checkConflicts(sessionId, testProjectRoot)
        assertEquals(1, postConflicts.size)
        assertEquals("Service.kt", postConflicts[0].relativePath)
        assertEquals(RollbackConflictReason.MODIFIED_EXTERNALLY, postConflicts[0].reason)

        // Verify disk was NOT mutated by checkConflicts
        assertEquals("user manual modification", file.readText())
    }

    @Test
    fun `test 2 - checkConflicts detects external deletion of modified file`() {
        val sessionId = UUID.randomUUID().toString()
        val file = File(testProjectRoot, "DeletedLater.kt").apply { writeText("original") }

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, file, "DeletedLater.kt")

        file.writeText("ai modified")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, file, "DeletedLater.kt", "ai modified")

        // User deletes the file externally
        file.delete()

        val conflicts = AutonomousRollbackManager.checkConflicts(sessionId, testProjectRoot)
        assertEquals(1, conflicts.size)
        assertEquals(RollbackConflictReason.DELETED_EXTERNALLY, conflicts[0].reason)
    }

    @Test
    fun `test 3 - checkConflicts detects external modification on newly created file`() {
        val sessionId = UUID.randomUUID().toString()
        val newFile = File(testProjectRoot, "NewHelper.kt")

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, newFile, "NewHelper.kt")

        newFile.writeText("ai created new class")
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, newFile, "NewHelper.kt", "ai created new class")

        // User modifies new file
        newFile.writeText("user customized ai class")

        val conflicts = AutonomousRollbackManager.checkConflicts(sessionId, testProjectRoot)
        assertEquals(1, conflicts.size)
        assertEquals(RollbackConflictReason.MODIFIED_EXTERNALLY, conflicts[0].reason)
    }

    @Test
    fun `test 4 - checkConflicts detects external recreation of agent-deleted file`() {
        val sessionId = UUID.randomUUID().toString()
        val file = File(testProjectRoot, "Obsolete.kt").apply { writeText("old code") }

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, file, "Obsolete.kt")

        // Agent deletes file
        file.delete()
        AutonomousRollbackManager.recordFileDeletion(sessionId, testProjectRoot, file, "Obsolete.kt")

        // User recreates file externally
        file.writeText("user recreated obsolete file")

        val conflicts = AutonomousRollbackManager.checkConflicts(sessionId, testProjectRoot)
        assertEquals(1, conflicts.size)
        assertEquals(RollbackConflictReason.RECREATED_EXTERNALLY, conflicts[0].reason)
    }

    @Test
    fun `test 5 - task checkpoint records modified files and reloads accurately`() {
        val sessionId = "session_chk_test_1"
        val checkpoint = TaskCheckpoint(
            taskId = sessionId,
            objective = "Implement trust engine diff viewer",
            acceptanceCriteria = listOf("Visual diff", "One-tap undo"),
            completedSteps = listOf("Create DiffEngine.kt"),
            modifiedFiles = listOf("DiffEngine.kt", "ActionPolicyGate.kt"),
            verificationEvidence = "14 diff unit tests passing",
            nextAction = "Verify on device",
            projectPath = testProjectRoot.absolutePath
        )

        TaskCheckpointManager.recordCheckpoint(sessionId, checkpoint)

        // Reload checkpoint
        val reloaded = TaskCheckpointManager.getCheckpoint(sessionId)
        assertNotNull(reloaded)
        assertEquals(sessionId, reloaded!!.taskId)
        assertEquals("Implement trust engine diff viewer", reloaded.objective)
        assertEquals(2, reloaded.modifiedFiles.size)
        assertTrue(reloaded.modifiedFiles.contains("DiffEngine.kt"))
        assertTrue(reloaded.modifiedFiles.contains("ActionPolicyGate.kt"))

        val md = reloaded.toMarkdownSummary()
        assertTrue(md.contains("DiffEngine.kt"))
        assertTrue(md.contains("Implement trust engine diff viewer"))
    }

    @Test
    fun `test 6 - multi-file diff computation integrates with recorded journal states`() {
        val sessionId = UUID.randomUUID().toString()
        val f1 = File(testProjectRoot, "FileA.kt").apply { writeText("val a = 1\nval b = 2") }
        val f2 = File(testProjectRoot, "FileB.kt").apply { writeText("fun oldB() {}") }

        AutonomousRollbackManager.startSession(sessionId, testProjectRoot)

        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, f1, "FileA.kt")
        AutonomousRollbackManager.recordFilePreState(sessionId, testProjectRoot, f2, "FileB.kt")

        val newF1 = "val a = 1\nval b = 2\nval c = 3"
        val newF2 = "fun newB() {}\nfun extra() {}"

        f1.writeText(newF1)
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, f1, "FileA.kt", newF1)

        f2.writeText(newF2)
        AutonomousRollbackManager.recordFilePostWrite(sessionId, testProjectRoot, f2, "FileB.kt", newF2)

        val journal = AutonomousRollbackManager.getJournal(sessionId, testProjectRoot)
        assertNotNull(journal)

        val diff1 = DiffEngine.computeDiff("FileA.kt", journal!!.entries["FileA.kt"]!!.originalContent, journal.entries["FileA.kt"]!!.latestWrittenContent)
        assertEquals(1, diff1.linesAdded)
        assertEquals(0, diff1.linesRemoved)

        val diff2 = DiffEngine.computeDiff("FileB.kt", journal.entries["FileB.kt"]!!.originalContent, journal.entries["FileB.kt"]!!.latestWrittenContent)
        assertEquals(2, diff2.linesAdded)
        assertEquals(1, diff2.linesRemoved)

        // Total changeset stats
        val totalAdded = listOf(diff1, diff2).sumOf { it.linesAdded }
        val totalRemoved = listOf(diff1, diff2).sumOf { it.linesRemoved }
        assertEquals(3, totalAdded)
        assertEquals(1, totalRemoved)
    }
}
