package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.policy.ActionPolicyGate
import com.tom.rv2ide.artificial.policy.ActionType
import com.tom.rv2ide.artificial.policy.UserApprovalResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * State of a managed background terminal process.
 */
enum class ManagedProcessState {
    STARTING,
    RUNNING,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    TERMINATED
}

/**
 * Public immutable descriptor of a managed process.
 */
data class ManagedProcessDescriptor(
    val processId: String,
    val command: String,
    val workingDir: String,
    val startTimeMs: Long,
    val endTimeMs: Long? = null,
    val state: ManagedProcessState,
    val exitCode: Int? = null,
    val totalLinesCaptured: Int = 0,
    val isMutating: Boolean = false
) {
    val isAlive: Boolean
        get() = state == ManagedProcessState.STARTING || state == ManagedProcessState.RUNNING

    fun toJson(): JSONObject = JSONObject().apply {
        put("processId", processId)
        put("command", command)
        put("workingDir", workingDir)
        put("startTimeMs", startTimeMs)
        put("endTimeMs", endTimeMs ?: JSONObject.NULL)
        put("state", state.name)
        put("exitCode", exitCode ?: JSONObject.NULL)
        put("totalLinesCaptured", totalLinesCaptured)
        put("isAlive", isAlive)
        put("isMutating", isMutating)
    }

    fun formatSummary(): String = buildString {
        appendLine("Process ID: $processId")
        appendLine("Command: $command")
        appendLine("Working Dir: $workingDir")
        appendLine("Status: ${state.name} (Alive: $isAlive)")
        if (exitCode != null) {
            appendLine("Exit Code: $exitCode")
        }
        val durationMs = (endTimeMs ?: System.currentTimeMillis()) - startTimeMs
        appendLine("Duration: ${durationMs}ms")
        appendLine("Captured Lines: $totalLinesCaptured")
    }.trim()
}

/**
 * Result chunk returned when incrementally reading process logs.
 */
data class ProcessOutputChunk(
    val processId: String,
    val state: ManagedProcessState,
    val isAlive: Boolean,
    val exitCode: Int?,
    val lines: List<String>,
    val nextCursor: Int,
    val hasMore: Boolean,
    val droppedLinesCount: Int = 0,
    val droppedBytesCount: Long = 0L,
    val truncatedLinesCount: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("processId", processId)
        put("state", state.name)
        put("isAlive", isAlive)
        put("exitCode", exitCode ?: JSONObject.NULL)
        put("lines", JSONArray(lines))
        put("nextCursor", nextCursor)
        put("hasMore", hasMore)
        put("droppedLinesCount", droppedLinesCount)
        put("droppedBytesCount", droppedBytesCount)
        put("truncatedLinesCount", truncatedLinesCount)
    }

    fun formatSummary(): String = buildString {
        appendLine("Process ID: $processId [${state.name}, Alive: $isAlive${if (exitCode != null) ", Exit: $exitCode" else ""}]")
        if (droppedLinesCount > 0 || droppedBytesCount > 0L) {
            appendLine("[Note: Buffer ring dropped $droppedLinesCount older lines ($droppedBytesCount bytes)]")
        }
        if (truncatedLinesCount > 0) {
            appendLine("[Note: $truncatedLinesCount line(s) exceeded maximum length and were truncated]")
        }
        appendLine("Lines (${lines.size}, Next Cursor: $nextCursor, Has More: $hasMore):")
        lines.forEach { appendLine(it) }
    }.trim()
}

/**
 * Abstraction for launching external OS processes.
 * Allows deterministic mocking and verification in unit tests across platforms.
 */
fun interface ProcessLauncher {
    fun launch(command: String, workingDir: File?, environment: Map<String, String>?): Process
}

/**
 * Default system process launcher:
 * Uses /system/bin/sh on Android, or OS-native shell on host platforms.
 */
class DefaultProcessLauncher : ProcessLauncher {
    override fun launch(command: String, workingDir: File?, environment: Map<String, String>?): Process {
        val isAndroid = File("/system/bin/sh").exists()
        val isWindows = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true

        val pb = if (isAndroid) {
            ProcessBuilder("/system/bin/sh", "-c", command)
        } else if (isWindows) {
            ProcessBuilder("cmd.exe", "/c", command)
        } else {
            ProcessBuilder("/bin/sh", "-c", command)
        }

        if (workingDir != null && workingDir.exists()) {
            pb.directory(workingDir)
        }
        if (!environment.isNullOrEmpty()) {
            pb.environment().putAll(environment)
        }
        pb.redirectErrorStream(true)
        return pb.start()
    }
}

/**
 * Thread-safe ring buffer for line-based terminal output bounded by both line count and memory byte size.
 * Retains up to [maxCapacity] lines and [maxTotalBytes] bytes, discarding older lines when limits are reached.
 * Automatically truncates individual lines exceeding [maxLineLength] characters.
 */
class BoundedLineRingBuffer(
    val maxCapacity: Int = 2000,
    val maxTotalBytes: Long = 2 * 1024 * 1024L, // 2MB memory cap
    val maxLineLength: Int = 4096 // 4KB per single line limit
) {
    private val lock = Any()
    private val buffer = ArrayList<String>(maxCapacity)
    private var totalPushed = 0
    private var droppedCount = 0
    private var droppedBytes = 0L
    private var retainedBytes = 0L
    private var truncatedCount = 0

    fun appendLine(rawLine: String) {
        synchronized(lock) {
            val line = if (rawLine.length > maxLineLength) {
                truncatedCount++
                rawLine.substring(0, maxLineLength) + " [line truncated: ${rawLine.length - maxLineLength} chars omitted]"
            } else {
                rawLine
            }

            val lineBytes = line.length * 2L
            while ((buffer.size >= maxCapacity || (buffer.isNotEmpty() && retainedBytes + lineBytes > maxTotalBytes))) {
                val removed = buffer.removeAt(0)
                val removedBytes = removed.length * 2L
                retainedBytes -= removedBytes
                droppedCount++
                droppedBytes += removedBytes
            }

            buffer.add(line)
            retainedBytes += lineBytes
            totalPushed++
        }
    }

    fun read(cursor: Int, maxLines: Int = 100, maxBytes: Int = 64 * 1024): ProcessOutputChunkReadResult {
        synchronized(lock) {
            val safeCursor = if (cursor < 0) 0 else cursor
            val bufferStartAbsolute = droppedCount
            val bufferEndAbsolute = droppedCount + buffer.size

            if (safeCursor >= bufferEndAbsolute) {
                return ProcessOutputChunkReadResult(
                    lines = emptyList(),
                    nextCursor = safeCursor,
                    hasMore = false,
                    droppedLinesCount = droppedCount,
                    droppedBytesCount = droppedBytes,
                    truncatedLinesCount = truncatedCount
                )
            }

            val effectiveStart = if (safeCursor < bufferStartAbsolute) bufferStartAbsolute else safeCursor
            val bufferIndex = effectiveStart - bufferStartAbsolute

            var accumulatedBytes = 0
            var countToTake = 0
            while (countToTake < maxLines && (bufferIndex + countToTake) < buffer.size) {
                val lineLenBytes = buffer[bufferIndex + countToTake].length * 2
                if (accumulatedBytes + lineLenBytes > maxBytes && countToTake > 0) {
                    break
                }
                accumulatedBytes += lineLenBytes
                countToTake++
            }

            val sub = buffer.subList(bufferIndex, bufferIndex + countToTake).toList()
            val nextCursor = effectiveStart + countToTake
            val hasMore = nextCursor < bufferEndAbsolute

            return ProcessOutputChunkReadResult(
                lines = sub,
                nextCursor = nextCursor,
                hasMore = hasMore,
                droppedLinesCount = droppedCount,
                droppedBytesCount = droppedBytes,
                truncatedLinesCount = truncatedCount
            )
        }
    }

    fun size(): Int = synchronized(lock) { buffer.size }
    fun totalLinesPushed(): Int = synchronized(lock) { totalPushed }
    fun droppedLinesCount(): Int = synchronized(lock) { droppedCount }
    fun droppedBytesCount(): Long = synchronized(lock) { droppedBytes }
    fun retainedBytes(): Long = synchronized(lock) { retainedBytes }
    fun truncatedLinesCount(): Int = synchronized(lock) { truncatedCount }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            totalPushed = 0
            droppedCount = 0
            droppedBytes = 0L
            retainedBytes = 0L
            truncatedCount = 0
        }
    }
}

data class ProcessOutputChunkReadResult(
    val lines: List<String>,
    val nextCursor: Int,
    val hasMore: Boolean,
    val droppedLinesCount: Int,
    val droppedBytesCount: Long = 0L,
    val truncatedLinesCount: Int = 0
)

/**
 * Manages the lifecycle of background and long-running terminal processes.
 * Enforces strict ownership, concurrency limits, lifetime timeouts, and bounded memory buffers.
 */
class ManagedProcessManager(
    val maxConcurrentProcesses: Int = 4,
    val maxProcessLifetimeMs: Long = 15 * 60 * 1000L, // 15 minutes default
    val maxLinesPerProcess: Int = 2000,
    val maxTotalBytesPerProcess: Long = 2 * 1024 * 1024L, // 2MB memory cap
    val maxLineLength: Int = 4096,
    var processLauncher: ProcessLauncher = DefaultProcessLauncher()
) {

    private val processCounter = AtomicInteger(0)
    private val processes = ConcurrentHashMap<String, ProcessHandleInternal>()

    private class ProcessHandleInternal(
        val descriptor: ManagedProcessDescriptor,
        val process: Process,
        val ringBuffer: BoundedLineRingBuffer,
        val readerThread: Thread,
        val stderrReaderThread: Thread? = null,
        val stateLock: Any = Any(),
        @Volatile var currentState: ManagedProcessState = ManagedProcessState.STARTING,
        @Volatile var exitCode: Int? = null,
        @Volatile var endTimeMs: Long? = null
    )

    /**
     * Spawns a new managed terminal process.
     * Enforces concurrency limits and registers stdout/stderr streaming into a bounded ring buffer.
     */
    @Synchronized
    fun startProcess(
        command: String,
        workingDir: File?,
        isBackground: Boolean = true,
        environment: Map<String, String>? = null
    ): ManagedProcessDescriptor {
        val cleanCmd = command.trim()
        if (cleanCmd.isEmpty()) {
            throw IllegalArgumentException("Command string cannot be empty.")
        }

        // Clean up completed/dead processes before checking capacity
        reapDeadProcesses()

        val activeCount = processes.values.count { it.currentState == ManagedProcessState.STARTING || it.currentState == ManagedProcessState.RUNNING }
        if (activeCount >= maxConcurrentProcesses) {
            throw IllegalStateException("Maximum concurrent processes limit reached ($maxConcurrentProcesses). Terminate an existing active process before launching a new one.")
        }

        val id = "proc_${System.currentTimeMillis()}_${processCounter.incrementAndGet()}"
        val isMutating = TerminalCommandClassifier.isMutatingOrBuildCommand(cleanCmd)
        val startTime = System.currentTimeMillis()

        var launchedProcess: Process? = null
        try {
            val process = processLauncher.launch(cleanCmd, workingDir, environment)
            launchedProcess = process

            val ringBuffer = BoundedLineRingBuffer(
                maxCapacity = maxLinesPerProcess,
                maxTotalBytes = maxTotalBytesPerProcess,
                maxLineLength = maxLineLength
            )

            val initialDescriptor = ManagedProcessDescriptor(
                processId = id,
                command = cleanCmd,
                workingDir = workingDir?.absolutePath ?: "/",
                startTimeMs = startTime,
                state = ManagedProcessState.RUNNING,
                isMutating = isMutating
            )

            val stdoutThread = Thread({
                try {
                    streamBoundedLines(process.inputStream, ringBuffer, maxLineLength)
                } catch (_: Exception) {}
            }, "ManagedProcessReader-stdout-$id")
            stdoutThread.isDaemon = true
            stdoutThread.start()

            // Drain stderr if distinct from stdout stream to prevent pipe deadlock
            var stderrThread: Thread? = null
            try {
                val errStream = process.errorStream
                if (errStream != null && errStream != process.inputStream) {
                    stderrThread = Thread({
                        try {
                            streamBoundedLines(errStream, ringBuffer, maxLineLength)
                        } catch (_: Exception) {}
                    }, "ManagedProcessReader-stderr-$id")
                    stderrThread.isDaemon = true
                    stderrThread.start()
                }
            } catch (_: Exception) {}

            val handle = ProcessHandleInternal(
                descriptor = initialDescriptor,
                process = process,
                ringBuffer = ringBuffer,
                readerThread = stdoutThread,
                stderrReaderThread = stderrThread,
                currentState = ManagedProcessState.RUNNING
            )
            processes[id] = handle

            return getDescriptor(handle)
        } catch (e: Throwable) {
            try {
                launchedProcess?.destroyForcibly()
            } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Polls the live status of an active managed process.
     * Checks process liveliness, updates exit codes, and terminates processes exceeding maximum lifetime.
     */
    fun pollProcess(processId: String): ManagedProcessDescriptor {
        val handle = processes[processId]
            ?: throw IllegalArgumentException("Process ID '$processId' not found. Only managed processes created in this session can be queried.")

        return updateAndGetDescriptor(handle)
    }

    /**
     * Reads output lines incrementally starting from [cursor] up to [maxLines].
     */
    fun readProcessOutput(processId: String, cursor: Int = 0, maxLines: Int = 100): ProcessOutputChunk {
        val handle = processes[processId]
            ?: throw IllegalArgumentException("Process ID '$processId' not found. Only managed processes created in this session can be read.")

        updateAndGetDescriptor(handle)
        val readResult = handle.ringBuffer.read(cursor, maxLines)

        return ProcessOutputChunk(
            processId = processId,
            state = handle.currentState,
            isAlive = handle.currentState == ManagedProcessState.STARTING || handle.currentState == ManagedProcessState.RUNNING,
            exitCode = handle.exitCode,
            lines = readResult.lines,
            nextCursor = readResult.nextCursor,
            hasMore = readResult.hasMore,
            droppedLinesCount = readResult.droppedLinesCount,
            droppedBytesCount = readResult.droppedBytesCount,
            truncatedLinesCount = readResult.truncatedLinesCount
        )
    }

    /**
     * Waits up to [timeoutMs] for the process to complete.
     * Returns updated descriptor. If process is still running after timeout, returns with state RUNNING.
     */
    fun waitProcess(processId: String, timeoutMs: Long = 5000L): ManagedProcessDescriptor {
        val handle = processes[processId]
            ?: throw IllegalArgumentException("Process ID '$processId' not found. Only managed processes created in this session can be waited on.")

        val startTime = System.currentTimeMillis()
        val checkIntervalMs = 50L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            updateAndGetDescriptor(handle)
            if (handle.currentState != ManagedProcessState.STARTING && handle.currentState != ManagedProcessState.RUNNING) {
                return getDescriptor(handle)
            }
            try {
                Thread.sleep(checkIntervalMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        return updateAndGetDescriptor(handle)
    }

    /**
     * Terminates an active process owned by this manager.
     * Attempts graceful termination (SIGTERM) followed by forced kill (SIGKILL) if still alive.
     */
    fun terminateProcess(processId: String, force: Boolean = false): ManagedProcessDescriptor {
        val handle = processes[processId]
            ?: throw IllegalArgumentException("Process ID '$processId' not found. Only managed processes created in this session can be terminated.")

        synchronized(handle.stateLock) {
            if (handle.currentState != ManagedProcessState.STARTING && handle.currentState != ManagedProcessState.RUNNING) {
                return getDescriptor(handle)
            }

            try {
                if (force) {
                    handle.process.destroyForcibly()
                } else {
                    handle.process.destroy()
                    // Grace period: up to 1000ms
                    val startWait = System.currentTimeMillis()
                    while (handle.process.isAlive && System.currentTimeMillis() - startWait < 1000L) {
                        Thread.sleep(25)
                    }
                    if (handle.process.isAlive) {
                        handle.process.destroyForcibly()
                    }
                }
            } catch (_: Exception) {
                try {
                    handle.process.destroyForcibly()
                } catch (_: Exception) {}
            }

            handle.currentState = ManagedProcessState.TERMINATED
            handle.endTimeMs = System.currentTimeMillis()
            try {
                handle.exitCode = handle.process.exitValue()
            } catch (_: Exception) {
                handle.exitCode = -1
            }
            handle.ringBuffer.appendLine("[Process terminated by agent request]")

            try { handle.readerThread.interrupt() } catch (_: Exception) {}
            try { handle.stderrReaderThread?.interrupt() } catch (_: Exception) {}

            return getDescriptor(handle)
        }
    }

    /**
     * Returns a list of descriptors for all registered processes (active and recent).
     */
    fun listProcesses(): List<ManagedProcessDescriptor> {
        reapDeadProcesses()
        return processes.values.map { updateAndGetDescriptor(it) }
    }

    /**
     * Cleanly shuts down and terminates all active managed processes.
     */
    fun shutdownAll() {
        for (handle in processes.values) {
            synchronized(handle.stateLock) {
                if (handle.currentState == ManagedProcessState.STARTING || handle.currentState == ManagedProcessState.RUNNING) {
                    try {
                        handle.process.destroyForcibly()
                        handle.currentState = ManagedProcessState.TERMINATED
                        handle.endTimeMs = System.currentTimeMillis()
                        handle.exitCode = try { handle.process.exitValue() } catch (_: Exception) { -1 }
                        handle.readerThread.interrupt()
                        handle.stderrReaderThread?.interrupt()
                    } catch (_: Exception) {}
                }
            }
        }
        processes.clear()
    }

    /**
     * Checks liveliness and enforces lifetime timeout on a process handle.
     */
    private fun updateAndGetDescriptor(handle: ProcessHandleInternal): ManagedProcessDescriptor {
        synchronized(handle.stateLock) {
            if (handle.currentState != ManagedProcessState.STARTING && handle.currentState != ManagedProcessState.RUNNING) {
                return getDescriptor(handle)
            }

            val isAlive = try {
                handle.process.isAlive
            } catch (_: Exception) {
                false
            }

            val now = System.currentTimeMillis()
            if (isAlive) {
                // Check lifetime timeout
                if (now - handle.descriptor.startTimeMs > maxProcessLifetimeMs) {
                    try {
                        handle.process.destroyForcibly()
                    } catch (_: Exception) {}
                    handle.currentState = ManagedProcessState.TIMED_OUT
                    handle.endTimeMs = now
                    handle.exitCode = -1
                    handle.ringBuffer.appendLine("[Process exceeded maximum lifetime limit (${maxProcessLifetimeMs}ms) and was terminated]")
                    try { handle.readerThread.interrupt() } catch (_: Exception) {}
                    try { handle.stderrReaderThread?.interrupt() } catch (_: Exception) {}
                }
            } else {
                val code = try {
                    handle.process.exitValue()
                } catch (_: Exception) {
                    -1
                }
                handle.exitCode = code
                handle.endTimeMs = now
                handle.currentState = if (code == 0) ManagedProcessState.COMPLETED else ManagedProcessState.FAILED
                try { handle.readerThread.interrupt() } catch (_: Exception) {}
                try { handle.stderrReaderThread?.interrupt() } catch (_: Exception) {}
            }

            return getDescriptor(handle)
        }
    }

    private fun getDescriptor(handle: ProcessHandleInternal): ManagedProcessDescriptor {
        return handle.descriptor.copy(
            state = handle.currentState,
            exitCode = handle.exitCode,
            endTimeMs = handle.endTimeMs,
            totalLinesCaptured = handle.ringBuffer.totalLinesPushed()
        )
    }

    private fun reapDeadProcesses() {
        if (processes.size > 20) {
            val toRemove = processes.filter { (_, handle) ->
                handle.currentState != ManagedProcessState.STARTING && handle.currentState != ManagedProcessState.RUNNING
            }.keys.take(processes.size - 20)
            for (k in toRemove) {
                processes.remove(k)
            }
        }
    }

    companion object {
        /**
         * Streams characters from [inputStream] into [ringBuffer], bounding line length to [maxLineLength].
         * Prevents heap exhaustion when processes output huge unbroken streams without newlines.
         */
        fun streamBoundedLines(
            inputStream: java.io.InputStream,
            ringBuffer: BoundedLineRingBuffer,
            maxLineLength: Int = 4096
        ) {
            val reader = InputStreamReader(inputStream, Charsets.UTF_8)
            val sb = StringBuilder()
            val cbuf = CharArray(1024)
            var charsRead = 0
            while (!Thread.currentThread().isInterrupted && reader.read(cbuf).also { charsRead = it } != -1) {
                for (i in 0 until charsRead) {
                    val c = cbuf[i]
                    if (c == '\n') {
                        ringBuffer.appendLine(sb.toString())
                        sb.setLength(0)
                    } else if (c == '\r') {
                        // Ignore CR in CRLF
                    } else {
                        sb.append(c)
                        if (sb.length >= maxLineLength) {
                            ringBuffer.appendLine(sb.toString() + " [exceeded maxLineLength]")
                            sb.setLength(0)
                        }
                    }
                }
            }
            if (sb.isNotEmpty()) {
                ringBuffer.appendLine(sb.toString())
            }
        }
    }
}
