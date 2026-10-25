package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.chat.VerificationLevel
import com.tom.rv2ide.artificial.policy.ActionPolicyGate
import com.tom.rv2ide.artificial.policy.ActionType
import com.tom.rv2ide.artificial.policy.AgentExecutionMode
import com.tom.rv2ide.artificial.transport.ToolCall
import com.tom.rv2ide.artificial.transport.ToolExecutionStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ManagedProcessLifecycleTest {

    private lateinit var manager: ManagedProcessManager

    /**
     * Controllable mock process for reliable multiplatform testing.
     */
    class FakeTestProcess(
        val exitCodeValue: Int = 0,
        val stdoutContent: String = "",
        val runningDelayMs: Long = 0L,
        val isIndefinite: Boolean = false
    ) : Process() {
        private val startedAt = System.currentTimeMillis()
        val destroyed = AtomicBoolean(false)
        val forciblyDestroyed = AtomicBoolean(false)

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream {
            return ByteArrayInputStream(stdoutContent.toByteArray(Charsets.UTF_8))
        }

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            while (isAlive) {
                Thread.sleep(10)
            }
            return exitValue()
        }

        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException("Process still running")
            return if (destroyed.get()) -1 else exitCodeValue
        }

        override fun destroy() {
            destroyed.set(true)
        }

        override fun destroyForcibly(): Process {
            destroyed.set(true)
            forciblyDestroyed.set(true)
            return this
        }

        override fun isAlive(): Boolean {
            if (destroyed.get() || forciblyDestroyed.get()) return false
            if (isIndefinite) return true
            return System.currentTimeMillis() - startedAt < runningDelayMs
        }
    }

    @Before
    fun setup() {
        manager = ManagedProcessManager(
            maxConcurrentProcesses = 4,
            maxProcessLifetimeMs = 5000L,
            maxLinesPerProcess = 2000,
            processLauncher = { cmd, _, _ ->
                FakeTestProcess(
                    exitCodeValue = 0,
                    stdoutContent = "Server starting...\nListening on port 8080\nReady for connections\n",
                    isIndefinite = true
                )
            }
        )
    }

    @After
    fun tearDown() {
        manager.shutdownAll()
    }

    @Test
    fun testStartBackgroundProcess_returnsRunningDescriptorWithProcessId() {
        val desc = manager.startProcess(
            command = "npm run dev",
            workingDir = File("/workspace"),
            isBackground = true
        )

        assertNotNull(desc.processId)
        assertTrue(desc.processId.startsWith("proc_"))
        assertEquals(ManagedProcessState.RUNNING, desc.state)
        assertTrue(desc.isAlive)
        assertEquals("npm run dev", desc.command)
    }

    @Test
    fun testPollProcess_reflectsLiveStateAndExitStatus() {
        var isProcessRunning = true
        manager.processLauncher = ProcessLauncher { _, _, _ ->
            object : Process() {
                val destroyed = AtomicBoolean(false)
                override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
                override fun getInputStream(): InputStream = ByteArrayInputStream("Running task\n".toByteArray())
                override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun waitFor(): Int = exitValue()
                override fun exitValue(): Int = if (isProcessRunning) throw IllegalThreadStateException() else 0
                override fun destroy() { destroyed.set(true) }
                override fun destroyForcibly(): Process { destroyed.set(true); return this }
                override fun isAlive(): Boolean = isProcessRunning && !destroyed.get()
            }
        }

        val desc = manager.startProcess("gradle --daemon", null, isBackground = true)
        val poll1 = manager.pollProcess(desc.processId)
        assertEquals(ManagedProcessState.RUNNING, poll1.state)
        assertTrue(poll1.isAlive)
        assertNull(poll1.exitCode)

        isProcessRunning = false
        val poll2 = manager.pollProcess(desc.processId)
        assertEquals(ManagedProcessState.COMPLETED, poll2.state)
        assertFalse(poll2.isAlive)
        assertEquals(0, poll2.exitCode)
    }

    @Test
    fun testReadOutput_withIncrementalCursor_advancesAccurately() {
        val lines = (1..10).map { "Log line $it" }.joinToString("\n") + "\n"
        manager.processLauncher = ProcessLauncher { _, _, _ ->
            FakeTestProcess(stdoutContent = lines, isIndefinite = true)
        }

        val desc = manager.startProcess("python -m http.server", null, isBackground = true)
        Thread.sleep(100) // Allow reader thread to populate buffer

        val chunk1 = manager.readProcessOutput(desc.processId, cursor = 0, maxLines = 4)
        assertEquals(4, chunk1.lines.size)
        assertEquals("Log line 1", chunk1.lines[0])
        assertEquals("Log line 4", chunk1.lines[3])
        assertEquals(4, chunk1.nextCursor)
        assertTrue(chunk1.hasMore)

        val chunk2 = manager.readProcessOutput(desc.processId, cursor = chunk1.nextCursor, maxLines = 4)
        assertEquals(4, chunk2.lines.size)
        assertEquals("Log line 5", chunk2.lines[0])
        assertEquals("Log line 8", chunk2.lines[3])
        assertEquals(8, chunk2.nextCursor)
        assertTrue(chunk2.hasMore)

        val chunk3 = manager.readProcessOutput(desc.processId, cursor = chunk2.nextCursor, maxLines = 4)
        assertEquals(2, chunk3.lines.size)
        assertEquals("Log line 9", chunk3.lines[0])
        assertEquals("Log line 10", chunk3.lines[1])
        assertEquals(10, chunk3.nextCursor)
        assertFalse(chunk3.hasMore)
    }

    @Test
    fun testBoundedOutputRingBuffer_dropsOldestLines_preservesDropCount() {
        val ringBuffer = BoundedLineRingBuffer(maxCapacity = 10)
        for (i in 1..25) {
            ringBuffer.appendLine("Message line $i")
        }

        assertEquals(10, ringBuffer.size())
        assertEquals(25, ringBuffer.totalLinesPushed())
        assertEquals(15, ringBuffer.droppedLinesCount())

        val readFromZero = ringBuffer.read(cursor = 0, maxLines = 5)
        assertEquals(5, readFromZero.lines.size)
        assertEquals("Message line 16", readFromZero.lines[0])
        assertEquals(20, readFromZero.nextCursor)
        assertEquals(15, readFromZero.droppedLinesCount)
    }

    @Test
    fun testConcurrencyLimit_rejectsFifthConcurrentProcess() {
        for (i in 1..4) {
            manager.startProcess("process_$i", null, isBackground = true)
        }

        try {
            manager.startProcess("process_5", null, isBackground = true)
            fail("Expected IllegalStateException due to concurrency limit")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Maximum concurrent processes limit reached") == true)
        }
    }

    @Test
    fun testWaitProcess_blocksUntilCompletionOrTimeout() {
        manager.processLauncher = ProcessLauncher { _, _, _ ->
            FakeTestProcess(exitCodeValue = 0, stdoutContent = "Done\n", runningDelayMs = 150L)
        }

        val desc = manager.startProcess("quick_task.sh", null, isBackground = true)
        val result = manager.waitProcess(desc.processId, timeoutMs = 1000L)

        assertEquals(ManagedProcessState.COMPLETED, result.state)
        assertEquals(0, result.exitCode)
        assertFalse(result.isAlive)
    }

    @Test
    fun testWaitProcess_returnsRunningIfTimeoutElapsesBeforeExit() {
        manager.processLauncher = ProcessLauncher { _, _, _ ->
            FakeTestProcess(exitCodeValue = 0, stdoutContent = "Running forever\n", isIndefinite = true)
        }

        val desc = manager.startProcess("daemon.sh", null, isBackground = true)
        val result = manager.waitProcess(desc.processId, timeoutMs = 100L)

        assertEquals(ManagedProcessState.RUNNING, result.state)
        assertTrue(result.isAlive)
    }

    @Test
    fun testTerminateProcess_gracefulThenForceKill() {
        val fakeProcess = FakeTestProcess(exitCodeValue = 0, stdoutContent = "Watching...\n", isIndefinite = true)
        manager.processLauncher = ProcessLauncher { _, _, _ -> fakeProcess }

        val desc = manager.startProcess("watch.sh", null, isBackground = true)
        assertTrue(fakeProcess.isAlive)

        val termDesc = manager.terminateProcess(desc.processId, force = false)
        assertEquals(ManagedProcessState.TERMINATED, termDesc.state)
        assertFalse(termDesc.isAlive)
        assertTrue(fakeProcess.destroyed.get())
    }

    @Test
    fun testRejectArbitraryPidsOrForeignProcessIds() {
        try {
            manager.pollProcess("proc_non_existent_12345")
            fail("Expected IllegalArgumentException for unknown processId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("not found") == true)
        }

        try {
            manager.readProcessOutput("1234") // arbitrary OS pid rejection
            fail("Expected IllegalArgumentException for unknown processId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("not found") == true)
        }

        try {
            manager.terminateProcess("9999")
            fail("Expected IllegalArgumentException for unknown processId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("not found") == true)
        }
    }

    @Test
    fun testReadProcessOutput_handlesNoNewOutputGracefully() {
        manager.processLauncher = ProcessLauncher { _, _, _ ->
            FakeTestProcess(stdoutContent = "Line 1\nLine 2\n", isIndefinite = true)
        }

        val desc = manager.startProcess("server.sh", null, isBackground = true)
        Thread.sleep(100)

        val chunk1 = manager.readProcessOutput(desc.processId, cursor = 0, maxLines = 10)
        assertEquals(2, chunk1.lines.size)
        assertEquals(2, chunk1.nextCursor)

        val chunk2 = manager.readProcessOutput(desc.processId, cursor = 2, maxLines = 10)
        assertTrue(chunk2.lines.isEmpty())
        assertEquals(2, chunk2.nextCursor)
        assertFalse(chunk2.hasMore)
    }

    @Test
    fun testProcessTimeout_enforcesMaxLifetime() {
        val shortLifetimeManager = ManagedProcessManager(
            maxConcurrentProcesses = 4,
            maxProcessLifetimeMs = 150L,
            processLauncher = { _, _, _ ->
                FakeTestProcess(isIndefinite = true)
            }
        )

        val desc = shortLifetimeManager.startProcess("long_task.sh", null, isBackground = true)
        assertEquals(ManagedProcessState.RUNNING, desc.state)

        Thread.sleep(250) // Let lifetime expire

        val polled = shortLifetimeManager.pollProcess(desc.processId)
        assertEquals(ManagedProcessState.TIMED_OUT, polled.state)
        assertFalse(polled.isAlive)
        shortLifetimeManager.shutdownAll()
    }

    @Test
    fun testListProcesses_returnsActiveAndRecentProcesses() {
        manager.startProcess("task1", null, isBackground = true)
        manager.startProcess("task2", null, isBackground = true)

        val list = manager.listProcesses()
        assertEquals(2, list.size)
        assertTrue(list.any { it.command == "task1" })
        assertTrue(list.any { it.command == "task2" })
    }

    @Test
    fun testShutdownAll_cleansUpAllLiveProcesses() {
        val p1 = FakeTestProcess(isIndefinite = true)
        val p2 = FakeTestProcess(isIndefinite = true)
        var count = 0
        manager.processLauncher = ProcessLauncher { _, _, _ ->
            if (count++ == 0) p1 else p2
        }

        manager.startProcess("srv1", null, isBackground = true)
        manager.startProcess("srv2", null, isBackground = true)

        assertTrue(p1.isAlive)
        assertTrue(p2.isAlive)

        manager.shutdownAll()

        assertFalse(p1.isAlive)
        assertFalse(p2.isAlive)
        assertTrue(manager.listProcesses().isEmpty())
    }

    @Test
    fun testActionPolicyGateEnforcedOnBackgroundStart() = runBlocking {
        val orchestrator = ToolOrchestrator(
            webSearchManager = WebSearchManager(),
            managedProcessManager = manager
        )

        val toolCall = ToolCall(
            id = "call_bg_destructive",
            name = "run_terminal_command",
            argumentsJson = JSONObject().apply {
                put("command", "rm -rf /data/local/tmp")
                put("isBackground", true)
            }.toString()
        )

        val result = orchestrator.executeSingleToolCallForTest(
            toolCall = toolCall,
            executionMode = AgentExecutionMode.AUTONOMOUS
        )

        assertEquals(ToolExecutionStatus.AUTHORIZATION_DENIED, result.status)
        assertTrue(result.isError)
        assertTrue(result.content.contains("denied", ignoreCase = true) || result.content.contains("refused", ignoreCase = true))
    }

    @Test
    fun testBackgroundProcessStart_doesNotSatisfyVerificationGate() = runBlocking {
        val orchestrator = ToolOrchestrator(
            webSearchManager = WebSearchManager(),
            managedProcessManager = manager
        )

        val toolCall = ToolCall(
            id = "call_bg_dev",
            name = "run_terminal_command",
            argumentsJson = JSONObject().apply {
                put("command", "npm run watch")
                put("isBackground", true)
            }.toString()
        )

        val result = orchestrator.executeSingleToolCallForTest(
            toolCall = toolCall,
            executionMode = AgentExecutionMode.AUTONOMOUS
        )

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertFalse(result.isError)
        assertTrue(result.content.contains("Background process launched successfully"))

        // Build report when only background process was started:
        val report = orchestrator.buildCompletionReport(
            userGoal = "Start the dev server and test",
            turnModifiedFiles = emptySet(),
            currentGeneration = 0L,
            ledger = orchestrator.verificationLedger
        )

        // Starting a background watcher does NOT satisfy build or test verification!
        assertNotEquals(VerificationLevel.BUILT_AND_VERIFIED, report.verificationLevel)
        assertEquals(VerificationLevel.NOT_VERIFIED, report.verificationLevel)
    }

    @Test
    fun testExtremelyLargeOutputLine_isTruncatedAndPreservesDropCounters() {
        val ringBuffer = BoundedLineRingBuffer(maxCapacity = 10, maxLineLength = 200)
        val massiveLine = "X".repeat(5000)
        ringBuffer.appendLine(massiveLine)

        assertEquals(1, ringBuffer.size())
        assertEquals(1, ringBuffer.truncatedLinesCount())
        val chunk = ringBuffer.read(cursor = 0, maxLines = 1)
        assertEquals(1, chunk.lines.size)
        assertTrue(chunk.lines[0].contains("[line truncated:"))
        assertTrue(chunk.lines[0].length < 300)
    }

    @Test
    fun testTotalBytesCapacity_dropsOldestLinesWhenByteLimitExceeded() {
        // 500 bytes max byte limit
        val ringBuffer = BoundedLineRingBuffer(maxCapacity = 100, maxTotalBytes = 500L, maxLineLength = 1000)
        for (i in 1..20) {
            // each line is ~50 chars -> ~100 bytes in memory
            ringBuffer.appendLine("This is a moderately long line for testing byte capacity #$i")
        }

        assertTrue("Oldest lines should be dropped when byte cap is reached", ringBuffer.droppedLinesCount() > 0)
        assertTrue("Dropped bytes counter must be positive", ringBuffer.droppedBytesCount() > 0L)
        assertTrue("Retained bytes must be within max limit", ringBuffer.retainedBytes() <= 500L)
    }

    @Test
    fun testHighVolumeStdoutAndStderr_streamBoundedLinesDoesNotDeadlock() {
        val ringBuffer = BoundedLineRingBuffer(maxCapacity = 100, maxTotalBytes = 10_000L, maxLineLength = 200)
        val unbrokenStream = "ABCDEFGH\n".repeat(500) + "Z".repeat(3000)
        val inputStream = ByteArrayInputStream(unbrokenStream.toByteArray(Charsets.UTF_8))

        ManagedProcessManager.streamBoundedLines(inputStream, ringBuffer, maxLineLength = ringBuffer.maxLineLength)

        assertTrue(ringBuffer.totalLinesPushed() > 0)
        assertTrue(ringBuffer.truncatedLinesCount() > 0)
        assertTrue(ringBuffer.size() <= 100)
    }

    @Test
    fun testConcurrentTerminationAndProcessExit_producesStableTerminalState() {
        val fakeProcess = FakeTestProcess(exitCodeValue = 0, stdoutContent = "output\n", runningDelayMs = 50L)
        manager.processLauncher = ProcessLauncher { _, _, _ -> fakeProcess }

        val desc = manager.startProcess("task_race", null, isBackground = true)
        val threads = (1..10).map {
            Thread {
                try {
                    manager.pollProcess(desc.processId)
                    manager.readProcessOutput(desc.processId)
                    manager.terminateProcess(desc.processId)
                } catch (_: Exception) {}
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val finalDesc = manager.pollProcess(desc.processId)
        assertFalse(finalDesc.isAlive)
        assertTrue(finalDesc.state == ManagedProcessState.COMPLETED || finalDesc.state == ManagedProcessState.TERMINATED)
        assertNotNull(finalDesc.exitCode)
    }

    @Test
    fun testConcurrencyCap_underSimultaneousStarts() {
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)
        val rejectedCount = java.util.concurrent.atomic.AtomicInteger(0)

        val threads = (1..10).map { i ->
            Thread {
                try {
                    manager.startProcess("simultaneous_$i", null, isBackground = true)
                    successCount.incrementAndGet()
                } catch (e: IllegalStateException) {
                    rejectedCount.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("Exactly 4 processes should be started under max limit of 4", 4, successCount.get())
        assertEquals("Remaining 6 starts must be rejected", 6, rejectedCount.get())
    }

    @Test
    fun testProcessStartupFailure_doesNotLeakSlotOrProcess() {
        manager.processLauncher = ProcessLauncher { _, _, _ ->
            throw java.io.IOException("Cannot run program 'missing_binary': No such file or directory")
        }

        try {
            manager.startProcess("missing_binary", null, isBackground = true)
            fail("Expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message?.contains("missing_binary") == true)
        }

        // Active processes count must remain 0
        assertEquals(0, manager.listProcesses().size)

        // Slot must not be leaked, subsequent valid process can start
        manager.processLauncher = ProcessLauncher { _, _, _ ->
            FakeTestProcess(exitCodeValue = 0, stdoutContent = "OK\n", isIndefinite = true)
        }
        val validDesc = manager.startProcess("valid_proc", null, isBackground = true)
        assertEquals(ManagedProcessState.RUNNING, validDesc.state)
    }

    @Test
    fun testSideAgentMode_prohibitsMutatingTerminalCommands() = runBlocking {
        val orchestrator = ToolOrchestrator(
            webSearchManager = WebSearchManager(),
            managedProcessManager = manager
        )

        val mutateCall = ToolCall(
            id = "call_side_mutate",
            name = "run_terminal_command",
            argumentsJson = JSONObject().apply {
                put("command", "git clean -fd")
                put("isBackground", false)
            }.toString()
        )

        val result = orchestrator.executeSingleToolCallForTest(
            toolCall = mutateCall,
            executionMode = AgentExecutionMode.SIDE_AGENT
        )

        assertEquals(ToolExecutionStatus.AUTHORIZATION_DENIED, result.status)
        assertTrue(result.isError)
        assertTrue(result.content.contains("COMMAND MUTATION PROHIBITED"))
    }

    @Test
    fun testRegistryToDispatchAgreement_forManageTerminalProcessTool() = runBlocking {
        val orchestrator = ToolOrchestrator(
            webSearchManager = WebSearchManager(),
            managedProcessManager = manager
        )

        val listCall = ToolCall(
            id = "call_manage_list",
            name = "manage_terminal_process",
            argumentsJson = JSONObject().apply {
                put("action", "list")
            }.toString()
        )

        val result = orchestrator.executeSingleToolCallForTest(
            toolCall = listCall,
            executionMode = AgentExecutionMode.AUTONOMOUS
        )

        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertFalse(result.isError)
        assertTrue(result.content.contains("No active or recent managed terminal processes") || result.content.contains("Active/Recent"))
    }
}
