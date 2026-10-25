package com.tom.rv2ide.lifecycle

import com.tom.rv2ide.artificial.coding.TaskCheckpoint
import com.tom.rv2ide.artificial.coding.TaskCheckpointManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectEditorLifecycleTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var checkpointDir: File

  @Before
  fun setUp() {
    checkpointDir = tempFolder.newFolder("checkpoints")
    TaskCheckpointManager.setStorageDirectory(checkpointDir)
    TaskCheckpointManager.clearAll()
  }

  @Test
  fun testTaskCheckpointProjectScopingAndIsolation() {
    val projA = "/storage/emulated/0/AndroidIDEProjects/ProjectAlpha"
    val projB = "/storage/emulated/0/AndroidIDEProjects/ProjectBeta"

    val cpA1 = TaskCheckpoint(
        taskId = "task_alpha_1",
        objective = "Implement Auth in Alpha",
        projectPath = projA,
        timestamp = 1000L
    )
    val cpA2 = TaskCheckpoint(
        taskId = "task_alpha_2",
        objective = "Fix DB Migration in Alpha",
        projectPath = projA,
        timestamp = 3000L
    )
    val cpB1 = TaskCheckpoint(
        taskId = "task_beta_1",
        objective = "Design UI for Beta",
        projectPath = projB,
        timestamp = 2000L
    )

    TaskCheckpointManager.recordCheckpoint("session_alpha_1", cpA1)
    TaskCheckpointManager.recordCheckpoint("session_alpha_2", cpA2)
    TaskCheckpointManager.recordCheckpoint("session_beta_1", cpB1)

    // Check project-scoped latest retrieval
    val latestAlpha = TaskCheckpointManager.getLatestCheckpoint(projA)
    assertNotNull(latestAlpha)
    assertEquals("task_alpha_2", latestAlpha!!.taskId)
    assertEquals("Fix DB Migration in Alpha", latestAlpha.objective)
    assertEquals(projA, latestAlpha.projectPath)

    val latestBeta = TaskCheckpointManager.getLatestCheckpoint(projB)
    assertNotNull(latestBeta)
    assertEquals("task_beta_1", latestBeta!!.taskId)
    assertEquals("Design UI for Beta", latestBeta.objective)
    assertEquals(projB, latestBeta.projectPath)

    // Check project-filtered list
    val allAlpha = TaskCheckpointManager.getAllCheckpoints(projA)
    assertEquals(2, allAlpha.size)
    assertTrue(allAlpha.all { it.projectPath == projA })

    val allBeta = TaskCheckpointManager.getAllCheckpoints(projB)
    assertEquals(1, allBeta.size)
    assertEquals("task_beta_1", allBeta.first().taskId)

    // Clear Project Alpha checkpoints, verify Project Beta remains intact
    TaskCheckpointManager.clearCheckpointsForProject(projA)
    val alphaAfterClear = TaskCheckpointManager.getAllCheckpoints(projA)
    assertEquals(0, alphaAfterClear.size)

    val betaAfterClear = TaskCheckpointManager.getAllCheckpoints(projB)
    assertEquals(1, betaAfterClear.size)
    assertEquals("task_beta_1", betaAfterClear.first().taskId)
  }

  @Test
  fun testTaskCheckpointJsonSerializationPreservesProject() {
    val proj = "/storage/emulated/0/AndroidIDEProjects/DemoApp"
    val cp = TaskCheckpoint(
        taskId = "task_test_99",
        objective = "Verify Serialization",
        projectPath = proj,
        acceptanceCriteria = listOf("Criteria 1", "Criteria 2"),
        modifiedFiles = listOf("MainActivity.kt")
    )

    val json = cp.toJson()
    assertEquals(proj, json.getString("projectPath"))

    val restored = TaskCheckpoint.fromJson(json)
    assertEquals("task_test_99", restored.taskId)
    assertEquals(proj, restored.projectPath)
    assertEquals(2, restored.acceptanceCriteria.size)
    assertEquals(listOf("MainActivity.kt"), restored.modifiedFiles)
    assertTrue(restored.toMarkdownSummary().contains(proj))
  }

  @Test
  fun testExternalModificationDetectionLogic() {
    val testFile = tempFolder.newFile("sample.kt").apply {
      writeText("println(\"Initial\")")
    }

    var lastSavedMtime = testFile.lastModified()
    assertFalse("Immediately after save, should not report external mod", testFile.lastModified() > lastSavedMtime)

    // Simulate external edit by updating lastModified forward
    Thread.sleep(50)
    testFile.writeText("println(\"Modified externally by Git/Agent\")")
    testFile.setLastModified(lastSavedMtime + 5000L)

    val hasExternalMod = lastSavedMtime > 0L && testFile.lastModified() > lastSavedMtime
    assertTrue("Should detect external modification", hasExternalMod)

    // Reconcile/save updates lastSavedMtime
    lastSavedMtime = testFile.lastModified()
    val afterReconcile = lastSavedMtime > 0L && testFile.lastModified() > lastSavedMtime
    assertFalse("After reconcile, external mod should be cleared", afterReconcile)
  }
}
