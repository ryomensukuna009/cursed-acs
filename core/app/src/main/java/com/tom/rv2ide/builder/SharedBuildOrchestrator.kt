package com.tom.rv2ide.builder

import com.tom.rv2ide.artificial.coding.BuildDiagnostic
import com.tom.rv2ide.artificial.coding.StructuredDiagnosticCollector
import com.tom.rv2ide.builder.offline.OfflineBuildEngine
import com.tom.rv2ide.builder.offline.OfflineBuildProgressListener
import com.tom.rv2ide.builder.offline.OfflineBuildStage
import com.tom.rv2ide.builder.offline.OfflineStageResult
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.preferences.internal.BuildPreferences
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.builder.BuildService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class BuildMode {
    OFFLINE_NDK,
    GRADLE
}

/**
 * Authoritative build states and associated terminal indicators (Task 3).
 */
enum class BuildState(val icon: String, val isTerminal: Boolean, val displayName: String) {
    IDLE("", true, "Idle"),
    QUEUED("⏳", false, "Queued"),
    RUNNING("⚙️", false, "Running"),
    SUCCESS("✅", true, "Success"),
    FAILED("❌", true, "Failed"),
    CANCELLED("🚫", true, "Cancelled"),
    TIMED_OUT("⏱️", true, "Timed Out")
}

data class BuildExecutionResult(
    val runId: String,
    val projectDir: File,
    val buildMode: BuildMode,
    val isSuccess: Boolean,
    val exitCode: Int,
    val durationMs: Long,
    val rawLog: String,
    val failedStage: String?,
    val errorMessage: String?,
    val apkFile: File?,
    val apkSizeBytes: Long,
    val diagnostics: List<BuildDiagnostic>,
    val state: BuildState = if (isSuccess) BuildState.SUCCESS else (if (failedStage == "CANCELLED" || exitCode == 130) BuildState.CANCELLED else BuildState.FAILED)
) {
    fun formatSummary(): String = buildString {
        appendLine("${state.icon} Build Run ID: $runId")
        appendLine("Project: ${projectDir.name}")
        appendLine("Mode: $buildMode")
        appendLine("State: ${state.name} (${state.displayName})")
        appendLine("Status: ${if (isSuccess) "SUCCESS" else "FAILED"}")
        appendLine("Duration: ${durationMs}ms")
        if (apkFile != null) {
            appendLine("APK Output: ${apkFile.absolutePath} ($apkSizeBytes bytes)")
        }
        if (!isSuccess) {
            appendLine("Failed Stage: ${failedStage ?: "Unknown"}")
            appendLine("Exit Code: $exitCode")
            appendLine("Error: ${errorMessage ?: "None"}")
            appendLine("Diagnostics Count: ${diagnostics.size}")
        }
    }.trim()
}

interface BuildProgressListener {
    fun onStageStarted(stage: String, description: String)
    fun onStageLog(stage: String, line: String)
    fun onStageCompleted(stage: String, success: Boolean, durationMs: Long, message: String?)
}

/**
 * Unified build orchestration interface shared by both:
 * 1. The normal UI Build / Quick Run button (QuickRunWithCancellationAction.kt)
 * 2. The AI Agent's autonomous engineering tools (build_current_project)
 *
 * Guarantees zero simulated builds: both paths execute the exact same underlying
 * backend dispatch (OfflineBuildEngine or GradleBuildService) and record identical
 * structured diagnostics and raw logs.
 */
object SharedBuildOrchestrator {

    private val log = LoggerFactory.getLogger(SharedBuildOrchestrator::class.java)

    const val DEFAULT_TOOLING_SERVER_WARMUP_TIMEOUT_MS = 10_000L
    const val TOOLING_SERVER_POLL_INTERVAL_MS = 500L

    @Volatile
    var toolingServerWarmupTimeoutMs: Long = DEFAULT_TOOLING_SERVER_WARMUP_TIMEOUT_MS

    private val isBuilding = AtomicBoolean(false)
    private val isCancellationRequested = AtomicBoolean(false)
    private val activeJobRef = AtomicReference<Job?>(null)
    private val lastResultRef = AtomicReference<BuildExecutionResult?>(null)

    private val historyMap = ConcurrentHashMap<String, BuildExecutionResult>()
    private val rawLogsMap = ConcurrentHashMap<String, String>()

    @Volatile
    private var explicitProjectDir: File? = null

    interface BuildOutputBridge {
        fun onBuildStarted(mode: BuildMode, projectDir: File, runId: String)
        fun onBuildOutput(line: String)
        fun onBuildFinished(result: BuildExecutionResult)
    }

    interface BuildStateListener {
        fun onBuildStateChanged(runId: String, state: BuildState, result: BuildExecutionResult?)
    }

    private val outputBridges = java.util.concurrent.CopyOnWriteArrayList<BuildOutputBridge>()
    private val stateListeners = java.util.concurrent.CopyOnWriteArrayList<BuildStateListener>()

    fun addOutputBridge(bridge: BuildOutputBridge) {
        outputBridges.add(bridge)
    }

    fun removeOutputBridge(bridge: BuildOutputBridge) {
        outputBridges.remove(bridge)
    }

    fun addStateListener(listener: BuildStateListener) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: BuildStateListener) {
        stateListeners.remove(listener)
    }

    @Volatile
    private var currentState: BuildState = BuildState.IDLE

    @Volatile
    private var currentRunId: String? = null

    fun getCurrentBuildState(): BuildState = currentState

    fun getCurrentRunId(): String? = currentRunId

    private fun notifyStateChanged(runId: String, state: BuildState, result: BuildExecutionResult?) {
        stateListeners.forEach {
            try {
                it.onBuildStateChanged(runId, state, result)
            } catch (_: Throwable) {}
        }
    }

    fun resetStateForTesting() {
        isBuilding.set(false)
        isCancellationRequested.set(false)
        activeJobRef.set(null)
        lastResultRef.set(null)
        historyMap.clear()
        rawLogsMap.clear()
        explicitProjectDir = null
        outputBridges.clear()
        stateListeners.clear()
        currentState = BuildState.IDLE
        currentRunId = null
        testBuildExecutor = null
        toolingServerWarmupTimeoutMs = DEFAULT_TOOLING_SERVER_WARMUP_TIMEOUT_MS
    }

    @Volatile
    private var testBuildExecutor: ((File, BuildMode) -> BuildExecutionResult)? = null

    fun setTestBuildExecutor(executor: ((File, BuildMode) -> BuildExecutionResult)?) {
        testBuildExecutor = executor
    }

    fun setActiveProjectDir(dir: File?) {
        explicitProjectDir = dir
        log.info("SharedBuildOrchestrator active project set to: {}", dir?.absolutePath)
    }

    fun getActiveProjectDir(): File? {
        if (explicitProjectDir != null && explicitProjectDir!!.exists()) {
            return explicitProjectDir
        }
        val fromManager = try {
            IProjectManager.getInstance().projectDir
        } catch (_: Throwable) {
            null
        }
        if (fromManager != null && fromManager.exists()) {
            return fromManager
        }
        val fromPrefs = try {
            com.tom.rv2ide.preferences.internal.GeneralPreferences.lastOpenedProject?.let { File(it) }
        } catch (_: Throwable) {
            null
        }
        if (fromPrefs != null && fromPrefs.exists()) {
            return fromPrefs
        }
        return lastResultRef.get()?.projectDir?.takeIf { it.exists() }
    }

    fun isBuildRunning(): Boolean = isBuilding.get()

    fun getLastResult(): BuildExecutionResult? = lastResultRef.get()

    fun getResultByRunId(runId: String): BuildExecutionResult? = historyMap[runId]

    fun getRawLog(runId: String? = null, maxLines: Int = 1000): String {
        val targetId = runId ?: lastResultRef.get()?.runId ?: return ""
        val fullLog = rawLogsMap[targetId] ?: historyMap[targetId]?.rawLog ?: ""
        val lines = fullLog.lines()
        return if (lines.size <= maxLines) {
            fullLog
        } else {
            lines.takeLast(maxLines).joinToString("\n")
        }
    }

    fun getDiagnostics(runId: String? = null): List<BuildDiagnostic> {
        val targetId = runId ?: lastResultRef.get()?.runId ?: return emptyList()
        return historyMap[targetId]?.diagnostics ?: lastResultRef.get()?.diagnostics ?: emptyList()
    }

    fun getCurrentBuildMode(): BuildMode {
        return try {
            if (BuildPreferences.isOfflineNdkBuild) {
                BuildMode.OFFLINE_NDK
            } else {
                BuildMode.GRADLE
            }
        } catch (_: Throwable) {
            BuildMode.GRADLE
        }
    }

    fun registerActiveJob(job: Job?) {
        activeJobRef.set(job)
    }

    fun cancelCurrentBuild(): Boolean {
        if (!isBuilding.get()) {
            return false
        }
        log.info("Cancelling active build...")
        isCancellationRequested.set(true)
        activeJobRef.getAndSet(null)?.cancel()

        try {
            val isOffline = try { BuildPreferences.isOfflineNdkBuild } catch (_: Throwable) { false }
            if (!isOffline) {
                val builder = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
                builder?.cancelCurrentBuild()
            }
        } catch (e: Exception) {
            log.warn("Failed to signal Gradle build service cancellation: {}", e.message)
        }
        currentState = BuildState.CANCELLED
        notifyStateChanged(currentRunId ?: "cancelled", BuildState.CANCELLED, null)
        return true
    }

    /**
     * Executes the actual build against [projectDir] using the active or requested [BuildMode].
     */
    suspend fun executeBuild(
        projectDir: File? = null,
        customTmpDir: File? = null,
        requestedMode: BuildMode? = null,
        gradleTasks: List<String> = listOf("assembleDebug"),
        toolingWarmupTimeoutMs: Long? = null,
        listener: BuildProgressListener? = null
    ): BuildExecutionResult = withContext(Dispatchers.IO) {
        val resolvedProjectDir = projectDir ?: getActiveProjectDir()

        if (resolvedProjectDir == null || !resolvedProjectDir.exists()) {
            val errResult = BuildExecutionResult(
                runId = "err-${System.currentTimeMillis()}",
                projectDir = File("."),
                buildMode = requestedMode ?: getCurrentBuildMode(),
                isSuccess = false,
                exitCode = -1,
                durationMs = 0L,
                rawLog = "Error: Active project directory could not be resolved or does not exist.",
                failedStage = "PROJECT_INITIALIZATION",
                errorMessage = "Active project directory could not be resolved or does not exist.",
                apkFile = null,
                apkSizeBytes = 0L,
                diagnostics = emptyList(),
                state = BuildState.FAILED
            )
            lastResultRef.set(errResult)
            currentState = BuildState.FAILED
            notifyStateChanged(errResult.runId, BuildState.FAILED, errResult)
            return@withContext errResult
        }

        if (!isBuilding.compareAndSet(false, true)) {
            val busyResult = BuildExecutionResult(
                runId = "busy-${System.currentTimeMillis()}",
                projectDir = resolvedProjectDir,
                buildMode = requestedMode ?: getCurrentBuildMode(),
                isSuccess = false,
                exitCode = -2,
                durationMs = 0L,
                rawLog = "Error: Another build operation is already in progress.",
                failedStage = "CONCURRENT_LOCK",
                errorMessage = "Another build is already running. Please wait or cancel the active build.",
                apkFile = null,
                apkSizeBytes = 0L,
                diagnostics = emptyList()
            )
            return@withContext busyResult
        }

        isCancellationRequested.set(false)

        val runId = "build-${UUID.randomUUID().toString().take(8)}"
        val mode = requestedMode ?: getCurrentBuildMode()
        val rawLogBuilder = StringBuilder()
        val startTime = System.currentTimeMillis()

        currentState = BuildState.RUNNING
        currentRunId = runId
        notifyStateChanged(runId, BuildState.RUNNING, null)

        outputBridges.forEach {
            try {
                it.onBuildStarted(mode, resolvedProjectDir, runId)
            } catch (_: Throwable) {}
        }

        try {
            com.tom.rv2ide.problems.ProjectProblemRegistry.getInstance().setProjectRoot(resolvedProjectDir)
            com.tom.rv2ide.problems.ProjectProblemRegistry.getInstance().onBuildStarted()
        } catch (_: Throwable) {}

        fun logLine(stage: String, line: String) {
            val formatted = "[$stage] $line"
            synchronized(rawLogBuilder) {
                rawLogBuilder.appendLine(formatted)
            }
            listener?.onStageLog(stage, line)
            outputBridges.forEach {
                try {
                    it.onBuildOutput(formatted)
                } catch (_: Throwable) {}
            }
        }

        val testExec = testBuildExecutor
        if (testExec != null) {
            val res = testExec(resolvedProjectDir, mode)
            lastResultRef.set(res)
            historyMap[runId] = res
            rawLogsMap[runId] = res.rawLog
            isBuilding.set(false)
            currentState = res.state
            notifyStateChanged(runId, res.state, res)
            outputBridges.forEach {
                try {
                    it.onBuildFinished(res)
                } catch (_: Throwable) {}
            }
            return@withContext res
        }

        try {
            when (mode) {
                BuildMode.OFFLINE_NDK -> {
                    logLine("INIT", "Starting Standalone Offline NDK Build for '${resolvedProjectDir.name}' (Run ID: $runId)")
                    val engine = OfflineBuildEngine()
                    val offlineResult = engine.build(
                        projectDir = resolvedProjectDir,
                        listener = object : OfflineBuildProgressListener {
                            override fun onStageStarted(stage: OfflineBuildStage, description: String) {
                                logLine(stage.name, "=== [Stage: ${stage.title}] $description ===")
                                listener?.onStageStarted(stage.name, description)
                            }

                            override fun onStageLog(stage: OfflineBuildStage, message: String) {
                                logLine(stage.name, message)
                            }

                            override fun onStageCompleted(stage: OfflineBuildStage, result: OfflineStageResult) {
                                val status = if (result.success) "SUCCESS" else "FAILED"
                                logLine(stage.name, "=== [Stage Done: ${stage.title}] $status in ${result.durationMs}ms ===")
                                listener?.onStageCompleted(stage.name, result.success, result.durationMs, result.message)
                            }
                        },
                        customTmpDir = customTmpDir
                    )

                    val totalDuration = System.currentTimeMillis() - startTime
                    val finalRawLog = rawLogBuilder.toString()
                    val logToParse = if (!offlineResult.success && !offlineResult.errorMessage.isNullOrBlank()) {
                        buildString {
                            append(finalRawLog)
                            append("\n\n")
                            append(offlineResult.errorMessage)
                        }
                    } else {
                        finalRawLog
                    }
                    val parsedDiagnostics = StructuredDiagnosticCollector.parseBuildOutput(
                        rawOutput = logToParse,
                        projectRoot = resolvedProjectDir,
                        defaultStage = offlineResult.failedStage?.name ?: "BUILD"
                    )

                    val executionResult = BuildExecutionResult(
                        runId = runId,
                        projectDir = resolvedProjectDir,
                        buildMode = BuildMode.OFFLINE_NDK,
                        isSuccess = offlineResult.success && offlineResult.finalApk != null && offlineResult.finalApk.exists(),
                        exitCode = if (offlineResult.success) 0 else 1,
                        durationMs = totalDuration,
                        rawLog = finalRawLog,
                        failedStage = offlineResult.failedStage?.name,
                        errorMessage = offlineResult.errorMessage,
                        apkFile = offlineResult.finalApk,
                        apkSizeBytes = offlineResult.apkSizeBytes,
                        diagnostics = parsedDiagnostics
                    )

                    recordExecutionResult(executionResult)
                    return@withContext executionResult
                }

                BuildMode.GRADLE -> {
                    logLine("INIT", "Starting Gradle Build for '${resolvedProjectDir.name}' tasks: $gradleTasks (Run ID: $runId)")
                    var buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)

                    val warmupTimeout = toolingWarmupTimeoutMs ?: toolingServerWarmupTimeoutMs
                    if ((buildService == null || !buildService.isToolingServerStarted()) && warmupTimeout > 0L) {
                        val warmupMsg = "Gradle Tooling Server is initializing. Waiting for warm-up (timeout: ${warmupTimeout}ms)..."
                        logLine("TOOLING_WARMUP", warmupMsg)
                        listener?.onStageStarted("TOOLING_WARMUP", warmupMsg)
                        val warmupStartTime = System.currentTimeMillis()
                        var elapsed = 0L

                        while (elapsed < warmupTimeout) {
                            if (isCancellationRequested.get()) {
                                throw CancellationException("Build cancelled by user.")
                            }
                            coroutineContext.ensureActive()
                            val waitChunk = minOf(TOOLING_SERVER_POLL_INTERVAL_MS, warmupTimeout - elapsed)
                            delay(waitChunk)
                            if (isCancellationRequested.get()) {
                                throw CancellationException("Build cancelled by user.")
                            }
                            elapsed = System.currentTimeMillis() - warmupStartTime

                            if (buildService == null) {
                                buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
                            }

                            if (buildService != null && buildService.isToolingServerStarted()) {
                                val readyMsg = "Gradle Tooling Server is ready after ${elapsed}ms."
                                logLine("TOOLING_WARMUP", readyMsg)
                                listener?.onStageCompleted("TOOLING_WARMUP", true, elapsed, readyMsg)
                                break
                            }
                        }
                    }

                    if (buildService == null || !buildService.isToolingServerStarted()) {
                        val errMsg = if (buildService == null) {
                            "Gradle Tooling Server is unavailable (BuildService not registered in Lookup)."
                        } else {
                            "Gradle Tooling Server failed to start within warm-up timeout (${warmupTimeout}ms)."
                        }
                        logLine("GRADLE_ERROR", errMsg)
                        listener?.onStageCompleted("TOOLING_WARMUP", false, System.currentTimeMillis() - startTime, errMsg)
                        val totalDuration = System.currentTimeMillis() - startTime
                        val executionResult = BuildExecutionResult(
                            runId = runId,
                            projectDir = resolvedProjectDir,
                            buildMode = BuildMode.GRADLE,
                            isSuccess = false,
                            exitCode = 2,
                            durationMs = totalDuration,
                            rawLog = rawLogBuilder.toString(),
                            failedStage = "TOOLING_SERVER",
                            errorMessage = errMsg,
                            apkFile = null,
                            apkSizeBytes = 0L,
                            diagnostics = listOf(
                                BuildDiagnostic(
                                    filePath = null,
                                    line = null,
                                    column = null,
                                    severity = BuildDiagnostic.Severity.ERROR,
                                    stage = "GRADLE_TOOLING",
                                    message = errMsg,
                                    rawLine = errMsg
                                )
                            )
                        )
                        recordExecutionResult(executionResult)
                        return@withContext executionResult
                    }

                    listener?.onStageStarted("GRADLE_EXECUTION", "Executing Gradle tasks: ${gradleTasks.joinToString(" ")}")
                    val taskResult = try {
                        buildService.executeTasks(*gradleTasks.toTypedArray()).get()
                    } catch (e: Exception) {
                        logLine("GRADLE_EXCEPTION", "Exception during Gradle task execution: ${e.message}")
                        null
                    }

                    val totalDuration = System.currentTimeMillis() - startTime
                    val isSuccess = taskResult?.isSuccessful == true
                    val finalRawLog = rawLogBuilder.toString()
                    val parsedDiagnostics = StructuredDiagnosticCollector.parseBuildOutput(
                        rawOutput = finalRawLog,
                        projectRoot = resolvedProjectDir
                    )

                    listener?.onStageCompleted("GRADLE_EXECUTION", isSuccess, totalDuration, if (isSuccess) "Build completed" else "Build failed")

                    val executionResult = BuildExecutionResult(
                        runId = runId,
                        projectDir = resolvedProjectDir,
                        buildMode = BuildMode.GRADLE,
                        isSuccess = isSuccess,
                        exitCode = if (isSuccess) 0 else 1,
                        durationMs = totalDuration,
                        rawLog = finalRawLog,
                        failedStage = if (isSuccess) null else "GRADLE_TASKS",
                        errorMessage = if (isSuccess) null else "Gradle tasks execution failed.",
                        apkFile = null, // Gradle APK is discovered via task execution listing
                        apkSizeBytes = 0L,
                        diagnostics = parsedDiagnostics
                    )

                    recordExecutionResult(executionResult)
                    return@withContext executionResult
                }
            }
        } catch (e: CancellationException) {
            logLine("CANCEL", "Build was cancelled by user.")
            val executionResult = BuildExecutionResult(
                runId = runId,
                projectDir = resolvedProjectDir,
                buildMode = mode,
                isSuccess = false,
                exitCode = 130,
                durationMs = System.currentTimeMillis() - startTime,
                rawLog = rawLogBuilder.toString(),
                failedStage = "CANCELLED",
                errorMessage = "Build cancelled by user.",
                apkFile = null,
                apkSizeBytes = 0L,
                diagnostics = emptyList()
            )
            recordExecutionResult(executionResult)
            return@withContext executionResult
        } catch (e: Throwable) {
            log.error("Unexpected error during build orchestration", e)
            logLine("ERROR", "Unexpected error: ${e.message}")
            val executionResult = BuildExecutionResult(
                runId = runId,
                projectDir = resolvedProjectDir,
                buildMode = mode,
                isSuccess = false,
                exitCode = 1,
                durationMs = System.currentTimeMillis() - startTime,
                rawLog = rawLogBuilder.toString(),
                failedStage = "EXECUTION_EXCEPTION",
                errorMessage = e.message ?: "Unknown build exception",
                apkFile = null,
                apkSizeBytes = 0L,
                diagnostics = emptyList()
            )
            recordExecutionResult(executionResult)
            return@withContext executionResult
        } finally {
            isBuilding.set(false)
            isCancellationRequested.set(false)
            activeJobRef.set(null)
            if (!currentState.isTerminal) {
                currentState = BuildState.FAILED
                notifyStateChanged(runId, BuildState.FAILED, lastResultRef.get())
            }
        }
    }

    fun recordExecutionResult(result: BuildExecutionResult) {
        lastResultRef.set(result)
        currentState = result.state
        notifyStateChanged(result.runId, result.state, result)
        historyMap[result.runId] = result
        rawLogsMap[result.runId] = result.rawLog
        try {
            com.tom.rv2ide.problems.ProjectProblemRegistry.getInstance().recordBuildDiagnostics(
                result.diagnostics,
                result.projectDir
            )
        } catch (_: Throwable) {}
        if (historyMap.size > 20) {
            val oldestKey = historyMap.keys.firstOrNull()
            if (oldestKey != null) {
                historyMap.remove(oldestKey)
                rawLogsMap.remove(oldestKey)
            }
        }
        outputBridges.forEach {
            try {
                it.onBuildFinished(result)
            } catch (_: Throwable) {}
        }
    }
}
