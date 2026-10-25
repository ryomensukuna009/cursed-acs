package com.tom.rv2ide.builder

import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.tooling.api.IProject
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import com.tom.rv2ide.tooling.api.messages.result.BuildCancellationRequestResult
import com.tom.rv2ide.tooling.api.messages.result.InitializeResult
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult
import com.tom.rv2ide.tooling.api.models.ToolingServerMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class GradleToolingWarmUpTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var projectDir: File

    private class TestBuildService(
        @Volatile var isStarted: Boolean = false,
        @Volatile var executeTasksResult: TaskExecutionResult = TaskExecutionResult.SUCCESS,
        @Volatile var executeDelayMs: Long = 0L,
        @Volatile var isBuildInProgressVal: Boolean = false
    ) : BuildService {

        val executedTasksList = mutableListOf<List<String>>()
        val cancellationRequested = AtomicBoolean(false)

        override val isBuildInProgress: Boolean
            get() = isBuildInProgressVal

        override fun isToolingServerStarted(): Boolean = isStarted

        override fun metadata(): CompletableFuture<ToolingServerMetadata> {
            return CompletableFuture.completedFuture(null)
        }

        override fun initializeProject(params: InitializeProjectParams): CompletableFuture<InitializeResult> {
            return CompletableFuture.completedFuture(null)
        }

        override fun executeTasks(vararg tasks: String): CompletableFuture<TaskExecutionResult> {
            synchronized(executedTasksList) {
                executedTasksList.add(tasks.toList())
            }
            val future = CompletableFuture<TaskExecutionResult>()
            if (executeDelayMs > 0) {
                Thread {
                    try {
                        Thread.sleep(executeDelayMs)
                    } catch (_: InterruptedException) {}
                    future.complete(executeTasksResult)
                }.start()
            } else {
                future.complete(executeTasksResult)
            }
            return future
        }

        override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> {
            cancellationRequested.set(true)
            return CompletableFuture.completedFuture(BuildCancellationRequestResult(true, null))
        }
    }

    @Before
    fun setUp() {
        projectDir = tempFolder.newFolder("test_gradle_project")
        SharedBuildOrchestrator.resetStateForTesting()
        Lookup.getDefault().unregister(BuildService.KEY_BUILD_SERVICE)
    }

    @After
    fun tearDown() {
        SharedBuildOrchestrator.cancelCurrentBuild()
        SharedBuildOrchestrator.resetStateForTesting()
        Lookup.getDefault().unregister(BuildService.KEY_BUILD_SERVICE)
    }

    @Test
    fun test01_toolingServerAlreadyRunning_immediateDispatch() = runBlocking {
        val testService = TestBuildService(isStarted = true)
        Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, testService)

        val result = SharedBuildOrchestrator.executeBuild(
            projectDir = projectDir,
            requestedMode = BuildMode.GRADLE,
            gradleTasks = listOf("assembleDebug"),
            toolingWarmupTimeoutMs = 5000L
        )

        assertTrue("Expected build to succeed", result.isSuccess)
        assertEquals(0, result.exitCode)
        assertEquals(null, result.failedStage)
        assertEquals(1, testService.executedTasksList.size)
        assertEquals(listOf("assembleDebug"), testService.executedTasksList[0])
        assertFalse("Log should not indicate waiting when already ready", result.rawLog.contains("Waiting for warm-up"))
    }

    @Test
    fun test02_toolingServerWarmsUpWithinTimeout_succeedsAfterDelay() = runBlocking {
        val testService = TestBuildService(isStarted = false)
        Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, testService)

        // Launch background coroutine to mark service as started after 1000ms
        launch(Dispatchers.IO) {
            delay(1000L)
            testService.isStarted = true
        }

        val result = SharedBuildOrchestrator.executeBuild(
            projectDir = projectDir,
            requestedMode = BuildMode.GRADLE,
            gradleTasks = listOf("compileDebugSources"),
            toolingWarmupTimeoutMs = 4000L
        )

        assertTrue("Expected build to succeed after warm-up", result.isSuccess)
        assertEquals(0, result.exitCode)
        assertEquals(null, result.failedStage)
        assertEquals(1, testService.executedTasksList.size)
        assertEquals(listOf("compileDebugSources"), testService.executedTasksList[0])
        assertTrue("Log must record warm-up initiation", result.rawLog.contains("Waiting for warm-up"))
        assertTrue("Log must record server readiness", result.rawLog.contains("Gradle Tooling Server is ready after"))
    }

    @Test
    fun test03_toolingServerTimesOut_failsGracefullyWithStructuredDiagnostic() = runBlocking {
        val testService = TestBuildService(isStarted = false)
        Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, testService)

        val result = SharedBuildOrchestrator.executeBuild(
            projectDir = projectDir,
            requestedMode = BuildMode.GRADLE,
            gradleTasks = listOf("assembleDebug"),
            toolingWarmupTimeoutMs = 1200L
        )

        assertFalse("Expected build to fail due to timeout", result.isSuccess)
        assertEquals(2, result.exitCode)
        assertEquals("TOOLING_SERVER", result.failedStage)
        assertTrue(testService.executedTasksList.isEmpty())
        assertTrue("Error message must mention timeout", result.errorMessage?.contains("warm-up timeout") == true)
        assertTrue("Diagnostics must not be empty", result.diagnostics.isNotEmpty())
        assertEquals("GRADLE_TOOLING", result.diagnostics[0].stage)
    }

    @Test
    fun test04_buildCancellationDuringWarmup_cleansUpWithExitCode130() = runBlocking {
        val testService = TestBuildService(isStarted = false)
        Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, testService)

        val buildDeferred = async(Dispatchers.IO) {
            SharedBuildOrchestrator.executeBuild(
                projectDir = projectDir,
                requestedMode = BuildMode.GRADLE,
                toolingWarmupTimeoutMs = 10_000L
            )
        }

        // Allow warm-up loop to start
        delay(400L)
        assertTrue("Build should be in progress", SharedBuildOrchestrator.isBuildRunning())

        // Cancel build
        val cancelled = SharedBuildOrchestrator.cancelCurrentBuild()
        assertTrue("Cancel call should succeed", cancelled)

        val result = buildDeferred.await()
        assertFalse("Cancelled build must not succeed", result.isSuccess)
        assertEquals(130, result.exitCode)
        assertEquals("CANCELLED", result.failedStage)
        assertEquals("Build cancelled by user.", result.errorMessage)
        assertFalse("Orchestrator should not remain busy after cancellation", SharedBuildOrchestrator.isBuildRunning())
    }

    @Test
    fun test05_concurrencyGuard_blocksConcurrentBuildAttempts() = runBlocking {
        val testService = TestBuildService(isStarted = false)
        Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, testService)

        val slowBuildDeferred = async(Dispatchers.IO) {
            SharedBuildOrchestrator.executeBuild(
                projectDir = projectDir,
                requestedMode = BuildMode.GRADLE,
                toolingWarmupTimeoutMs = 5000L
            )
        }

        // Wait until first build acquires lock
        delay(300L)
        assertTrue("First build should hold lock", SharedBuildOrchestrator.isBuildRunning())

        // Attempt concurrent build
        val concurrentResult = SharedBuildOrchestrator.executeBuild(
            projectDir = projectDir,
            requestedMode = BuildMode.GRADLE
        )

        assertFalse("Concurrent build must be blocked", concurrentResult.isSuccess)
        assertEquals(-2, concurrentResult.exitCode)
        assertEquals("CONCURRENT_LOCK", concurrentResult.failedStage)
        assertTrue(concurrentResult.errorMessage?.contains("Another build is already running") == true)

        // Cancel first build to unblock
        SharedBuildOrchestrator.cancelCurrentBuild()
        slowBuildDeferred.await()
        assertFalse("Orchestrator should be idle now", SharedBuildOrchestrator.isBuildRunning())
    }

    @Test
    fun test06_serviceNullInitiallyThenRegistered_warmupSucceeds() = runBlocking {
        // Service is not registered in Lookup at all initially
        val testService = TestBuildService(isStarted = true)

        launch(Dispatchers.IO) {
            delay(800L)
            Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, testService)
        }

        val result = SharedBuildOrchestrator.executeBuild(
            projectDir = projectDir,
            requestedMode = BuildMode.GRADLE,
            gradleTasks = listOf("assembleRelease"),
            toolingWarmupTimeoutMs = 4000L
        )

        assertTrue("Expected build to succeed once service is registered", result.isSuccess)
        assertEquals(0, result.exitCode)
        assertEquals(1, testService.executedTasksList.size)
        assertEquals(listOf("assembleRelease"), testService.executedTasksList[0])
    }

    @Test
    fun test07_progressListenerReceivesWarmupStageCallbacks() = runBlocking {
        val testService = TestBuildService(isStarted = false)
        Lookup.getDefault().update(BuildService.KEY_BUILD_SERVICE, testService)

        val stagesStarted = mutableListOf<String>()
        val stagesCompleted = mutableListOf<String>()

        val listener = object : BuildProgressListener {
            override fun onStageStarted(stage: String, description: String) {
                synchronized(stagesStarted) { stagesStarted.add(stage) }
            }

            override fun onStageLog(stage: String, line: String) {}

            override fun onStageCompleted(stage: String, success: Boolean, durationMs: Long, message: String?) {
                synchronized(stagesCompleted) { stagesCompleted.add(stage) }
            }
        }

        launch(Dispatchers.IO) {
            delay(600L)
            testService.isStarted = true
        }

        val result = SharedBuildOrchestrator.executeBuild(
            projectDir = projectDir,
            requestedMode = BuildMode.GRADLE,
            toolingWarmupTimeoutMs = 3000L,
            listener = listener
        )

        assertTrue(result.isSuccess)
        assertTrue("Should receive TOOLING_WARMUP start callback", stagesStarted.contains("TOOLING_WARMUP"))
        assertTrue("Should receive TOOLING_WARMUP completed callback", stagesCompleted.contains("TOOLING_WARMUP"))
        assertTrue("Should receive GRADLE_EXECUTION start callback", stagesStarted.contains("GRADLE_EXECUTION"))
        assertTrue("Should receive GRADLE_EXECUTION completed callback", stagesCompleted.contains("GRADLE_EXECUTION"))
    }
}
