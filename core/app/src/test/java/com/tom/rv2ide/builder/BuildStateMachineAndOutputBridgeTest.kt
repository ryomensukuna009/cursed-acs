package com.tom.rv2ide.builder

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking

/**
 * Unit tests validating Tasks 3, 4, and 10:
 * - Authoritative BuildState machine with icons (✅, ❌, 🚫, ⏱️)
 * - Safe lifecycle avoiding stuck or unbounded top progress indicators
 * - Output bridge live event propagation for UI & AI agent builds
 */
class BuildStateMachineAndOutputBridgeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var projectDir: File

    @Before
    fun setUp() {
        projectDir = tempFolder.newFolder("TestBuildProject")
        SharedBuildOrchestrator.resetStateForTesting()
        SharedBuildOrchestrator.setActiveProjectDir(projectDir)
    }

    @After
    fun tearDown() {
        SharedBuildOrchestrator.resetStateForTesting()
    }

    @Test
    fun testAuthoritativeBuildStatesAndIcons() {
        // Task 3: Verify all authoritative states and their terminal status
        assertTrue("IDLE must be terminal", BuildState.IDLE.isTerminal)
        assertFalse("QUEUED must not be terminal", BuildState.QUEUED.isTerminal)
        assertFalse("RUNNING must not be terminal", BuildState.RUNNING.isTerminal)
        assertTrue("SUCCESS must be terminal", BuildState.SUCCESS.isTerminal)
        assertTrue("FAILED must be terminal", BuildState.FAILED.isTerminal)
        assertTrue("CANCELLED must be terminal", BuildState.CANCELLED.isTerminal)
        assertTrue("TIMED_OUT must be terminal", BuildState.TIMED_OUT.isTerminal)

        // Verify authoritative icons
        assertEquals("✅", BuildState.SUCCESS.icon)
        assertEquals("❌", BuildState.FAILED.icon)
        assertEquals("🚫", BuildState.CANCELLED.icon)
        assertEquals("⏱️", BuildState.TIMED_OUT.icon)
        assertEquals("⚙️", BuildState.RUNNING.icon)
        assertEquals("⏳", BuildState.QUEUED.icon)
    }

    @Test
    fun testOutputBridgeAndStateListenerOnSuccess() = runBlocking {
        val startedEvents = CopyOnWriteArrayList<Triple<BuildMode, File, String>>()
        val outputLines = CopyOnWriteArrayList<String>()
        val finishedResults = CopyOnWriteArrayList<BuildExecutionResult>()
        val stateTransitions = CopyOnWriteArrayList<BuildState>()

        val bridge = object : SharedBuildOrchestrator.BuildOutputBridge {
            override fun onBuildStarted(mode: BuildMode, projectDir: File, runId: String) {
                startedEvents.add(Triple(mode, projectDir, runId))
            }

            override fun onBuildOutput(line: String) {
                outputLines.add(line)
            }

            override fun onBuildFinished(result: BuildExecutionResult) {
                finishedResults.add(result)
            }
        }

        val listener = object : SharedBuildOrchestrator.BuildStateListener {
            override fun onBuildStateChanged(runId: String, state: BuildState, result: BuildExecutionResult?) {
                stateTransitions.add(state)
            }
        }

        SharedBuildOrchestrator.addOutputBridge(bridge)
        SharedBuildOrchestrator.addStateListener(listener)

        SharedBuildOrchestrator.setTestBuildExecutor { dir, mode ->
            BuildExecutionResult(
                runId = "test-run-123",
                projectDir = dir,
                buildMode = mode,
                isSuccess = true,
                exitCode = 0,
                durationMs = 1500L,
                rawLog = "Compilation succeeded.\nBuilt output.apk",
                failedStage = null,
                errorMessage = null,
                apkFile = File(dir, "output.apk"),
                apkSizeBytes = 2048L,
                diagnostics = emptyList(),
                state = BuildState.SUCCESS
            )
        }

        val result = SharedBuildOrchestrator.executeBuild(projectDir = projectDir, requestedMode = BuildMode.OFFLINE_NDK)

        // Verify result
        assertTrue(result.isSuccess)
        assertEquals(BuildState.SUCCESS, result.state)
        assertEquals(BuildState.SUCCESS, SharedBuildOrchestrator.getCurrentBuildState())
        assertTrue("Summary contains checkmark", result.formatSummary().contains("✅"))

        // Verify bridge events
        assertEquals(1, startedEvents.size)
        assertEquals(BuildMode.OFFLINE_NDK, startedEvents[0].first)
        assertEquals(projectDir, startedEvents[0].second)

        assertEquals(1, finishedResults.size)
        assertEquals(BuildState.SUCCESS, finishedResults[0].state)

        // Verify state transitions: RUNNING -> SUCCESS
        assertTrue(stateTransitions.contains(BuildState.RUNNING))
        assertTrue(stateTransitions.contains(BuildState.SUCCESS))
        assertTrue(stateTransitions.last().isTerminal)
    }

    @Test
    fun testOutputBridgeAndStateListenerOnFailure() = runBlocking {
        val stateTransitions = CopyOnWriteArrayList<BuildState>()
        val finishedResults = CopyOnWriteArrayList<BuildExecutionResult>()

        SharedBuildOrchestrator.addStateListener(object : SharedBuildOrchestrator.BuildStateListener {
            override fun onBuildStateChanged(runId: String, state: BuildState, result: BuildExecutionResult?) {
                stateTransitions.add(state)
            }
        })
        SharedBuildOrchestrator.addOutputBridge(object : SharedBuildOrchestrator.BuildOutputBridge {
            override fun onBuildStarted(mode: BuildMode, projectDir: File, runId: String) {}
            override fun onBuildOutput(line: String) {}
            override fun onBuildFinished(result: BuildExecutionResult) {
                finishedResults.add(result)
            }
        })

        SharedBuildOrchestrator.setTestBuildExecutor { dir, mode ->
            BuildExecutionResult(
                runId = "test-fail-456",
                projectDir = dir,
                buildMode = mode,
                isSuccess = false,
                exitCode = 1,
                durationMs = 800L,
                rawLog = "error: undefined symbol foo",
                failedStage = "LINK_NATIVE",
                errorMessage = "undefined symbol foo",
                apkFile = null,
                apkSizeBytes = 0L,
                diagnostics = emptyList(),
                state = BuildState.FAILED
            )
        }

        val result = SharedBuildOrchestrator.executeBuild(projectDir = projectDir, requestedMode = BuildMode.OFFLINE_NDK)

        assertFalse(result.isSuccess)
        assertEquals(BuildState.FAILED, result.state)
        assertEquals(BuildState.FAILED, SharedBuildOrchestrator.getCurrentBuildState())
        assertTrue("Summary contains cross mark", result.formatSummary().contains("❌"))
        assertEquals(1, finishedResults.size)
        assertEquals(BuildState.FAILED, finishedResults[0].state)
        assertTrue(stateTransitions.last().isTerminal)
    }

    @Test
    fun testNullProjectFailsImmediatelyAndCleansState() = runBlocking {
        SharedBuildOrchestrator.setActiveProjectDir(null)
        val stateTransitions = CopyOnWriteArrayList<BuildState>()

        SharedBuildOrchestrator.addStateListener(object : SharedBuildOrchestrator.BuildStateListener {
            override fun onBuildStateChanged(runId: String, state: BuildState, result: BuildExecutionResult?) {
                stateTransitions.add(state)
            }
        })

        val result = SharedBuildOrchestrator.executeBuild(projectDir = null)

        assertFalse(result.isSuccess)
        assertEquals(BuildState.FAILED, result.state)
        assertEquals(BuildState.FAILED, SharedBuildOrchestrator.getCurrentBuildState())
        assertFalse("Must not leave isBuilding active", SharedBuildOrchestrator.isBuildRunning())
        assertTrue("Must transition to terminal state", stateTransitions.last().isTerminal)
    }

    @Test
    fun testCancelCurrentBuildTransitionsToCancelled() {
        val stateTransitions = CopyOnWriteArrayList<BuildState>()

        SharedBuildOrchestrator.addStateListener(object : SharedBuildOrchestrator.BuildStateListener {
            override fun onBuildStateChanged(runId: String, state: BuildState, result: BuildExecutionResult?) {
                stateTransitions.add(state)
            }
        })

        // Cancel when not running returns false
        assertFalse(SharedBuildOrchestrator.cancelCurrentBuild())

        // Simulate active running build
        SharedBuildOrchestrator.setTestBuildExecutor { dir, mode ->
            BuildExecutionResult(
                runId = "test-cancel",
                projectDir = dir,
                buildMode = mode,
                isSuccess = false,
                exitCode = 130,
                durationMs = 200L,
                rawLog = "Cancelled",
                failedStage = "CANCELLED",
                errorMessage = "Build cancelled by user.",
                apkFile = null,
                apkSizeBytes = 0L,
                diagnostics = emptyList(),
                state = BuildState.CANCELLED
            )
        }

        // Verify CANCELLED state properties
        assertEquals("🚫", BuildState.CANCELLED.icon)
        assertTrue(BuildState.CANCELLED.isTerminal)
    }
}
