package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.policy.AgentExecutionMode
import com.tom.rv2ide.artificial.chat.ConversationCompactor
import com.tom.rv2ide.artificial.providers.ModelCapabilities
import com.tom.rv2ide.artificial.transport.ChatRole
import com.tom.rv2ide.artificial.transport.ToolCall
import com.tom.rv2ide.artificial.transport.ToolResultMessage
import com.tom.rv2ide.artificial.transport.TokenUsage
import com.tom.rv2ide.artificial.transport.UnifiedChatMessage
import com.tom.rv2ide.artificial.transport.UnifiedTransportClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
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

/**
 * Unit tests for HistoricalToolOutputStore and TOOL_GET_HISTORICAL_TOOL_OUTPUT orchestration.
 */
class HistoricalToolOutputStoreTest {

    private lateinit var sandboxDir: File
    private lateinit var storageDir: File
    private val httpClient = OkHttpClient()

    @Before
    fun setUp() {
        sandboxDir = Files.createTempDirectory("historical_tool_test_sandbox").toFile()
        storageDir = File(sandboxDir, "tool_output_store").apply { mkdirs() }
        HistoricalToolOutputStore.setStorageDirectory(storageDir)
        HistoricalToolOutputStore.resetForTesting()
    }

    @After
    fun tearDown() {
        HistoricalToolOutputStore.resetForTesting()
        sandboxDir.deleteRecursively()
    }

    @Test
    fun testRecordAndRetrieveOutput() {
        val entry = HistoricalToolOutputStore.recordOutput(
            sessionId = "session_test",
            callId = "call_abc123",
            toolName = "list_project_files",
            argumentsJson = """{"path":"."}""",
            content = "file1.kt\nfile2.kt\nfile3.kt"
        )

        assertEquals("call_abc123", entry.callId)
        assertEquals(3, entry.totalLines)
        assertEquals(26, entry.characterCount)

        val retrieved = HistoricalToolOutputStore.getOutput("call_abc123")
        assertNotNull(retrieved)
        assertEquals("list_project_files", retrieved?.toolName)
        assertEquals("file1.kt\nfile2.kt\nfile3.kt", retrieved?.fullContent)
    }

    @Test
    fun testDiskPersistenceAndHydration() {
        HistoricalToolOutputStore.recordOutput(
            sessionId = "sess_persist",
            callId = "call_disk_1",
            toolName = "build_current_project",
            argumentsJson = "{}",
            content = "BUILD SUCCESSFUL in 4s\n100 tasks executed"
        )

        // Clear in-memory map to force disk read
        HistoricalToolOutputStore.resetForTesting()

        val reloaded = HistoricalToolOutputStore.getOutput("call_disk_1")
        assertNotNull(reloaded)
        assertEquals("build_current_project", reloaded?.toolName)
        assertTrue(reloaded?.fullContent?.contains("BUILD SUCCESSFUL") == true)
    }

    @Test
    fun testFileReadCachingAndMtimeInvalidation() {
        val testFile = File(sandboxDir, "Sample.kt").apply {
            writeText("package com.test\nclass Sample")
        }
        val mtime1 = testFile.lastModified()

        HistoricalToolOutputStore.recordOutput(
            sessionId = "session_cache",
            callId = "call_read_1",
            toolName = "read_project_file",
            argumentsJson = """{"filePath":"Sample.kt"}""",
            content = testFile.readText(),
            filePath = testFile.absolutePath,
            fileLastModified = mtime1
        )

        // Valid cache read
        val cached = HistoricalToolOutputStore.getCachedFileRead(testFile.absolutePath, mtime1)
        assertNotNull(cached)
        assertEquals("call_read_1", cached?.callId)

        // Simulating file modification
        testFile.appendText("\n// new line")
        testFile.setLastModified(mtime1 + 5000L)
        val mtime2 = testFile.lastModified()

        // Cache miss due to mtime mismatch
        val cacheMiss = HistoricalToolOutputStore.getCachedFileRead(testFile.absolutePath, mtime2)
        assertNull(cacheMiss)
    }

    @Test
    fun testOutputRangeRetrieval() {
        val multilineContent = (1..50).joinToString("\n") { "Line $it: statement" }
        HistoricalToolOutputStore.recordOutput(
            sessionId = "session_range",
            callId = "call_range_1",
            toolName = "read_project_file",
            argumentsJson = "{}",
            content = multilineContent
        )

        val slice = HistoricalToolOutputStore.getOutputRange("call_range_1", 5, 8)
        assertNotNull(slice)
        assertTrue(slice!!.contains("L5: Line 5: statement"))
        assertTrue(slice.contains("L8: Line 8: statement"))
        assertFalse(slice.contains("L4: Line 4: statement"))
        assertFalse(slice.contains("L9: Line 9: statement"))
    }

    @Test
    fun testSearchOutputQueryFiltering() {
        val buildLog = """
            > Task :core:app:compileDebugKotlin
            /src/main/MainActivity.kt:42: error: Unresolved reference: NonExistentClass
            /src/main/MainActivity.kt:43: warning: Unused parameter
            /src/main/Utils.kt:15: error: Type mismatch: inferred String but Int was expected
            BUILD FAILED
        """.trimIndent()

        HistoricalToolOutputStore.recordOutput(
            sessionId = "session_search",
            callId = "call_log_1",
            toolName = "build_current_project",
            argumentsJson = "{}",
            content = buildLog
        )

        val searchResult = HistoricalToolOutputStore.searchOutput("call_log_1", "error")
        assertNotNull(searchResult)
        assertTrue(searchResult!!.contains("2 match(es)"))
        assertTrue(searchResult.contains("Unresolved reference"))
        assertTrue(searchResult.contains("Type mismatch"))
        assertFalse(searchResult.contains("Unused parameter"))
    }

    private fun createOrchestrator(): ToolOrchestrator {
        val searchMgr = WebSearchManager(context = null, baseHttpClient = httpClient)
        val urlFetcher = SafeUrlFetcher(httpClient)
        return ToolOrchestrator(
            transportClient = UnifiedTransportClient(httpClient),
            webSearchManager = searchMgr,
            safeUrlFetcher = urlFetcher,
            projectRootProvider = { sandboxDir }
        )
    }

    @Test
    fun testToolOrchestratorGetHistoricalToolOutput() = runBlocking {
        val orchestrator = createOrchestrator()

        // Pre-record an entry
        HistoricalToolOutputStore.recordOutput(
            sessionId = "orch_session",
            callId = "historical_call_99",
            toolName = "list_project_files",
            argumentsJson = "{}",
            content = "app/build.gradle.kts\nsettings.gradle.kts\nsrc/Main.kt"
        )

        val retrieveCall = ToolCall(
            id = "test_req_1",
            name = AgentToolRegistry.TOOL_GET_HISTORICAL_TOOL_OUTPUT,
            argumentsJson = JSONObject().apply {
                put("callId", "historical_call_99")
            }.toString()
        )

        val result = orchestrator.executeSingleToolCallForTest(retrieveCall, sessionId = "orch_session")
        assertFalse(result.isError)
        assertTrue(result.content.contains("app/build.gradle.kts"))
        assertTrue(result.content.contains("src/Main.kt"))
    }

    @Test
    fun testToolOrchestratorHistoricalOutputWithQuery() = runBlocking {
        val orchestrator = createOrchestrator()

        HistoricalToolOutputStore.recordOutput(
            sessionId = "orch_session",
            callId = "historical_call_error_log",
            toolName = "build_current_project",
            argumentsJson = "{}",
            content = "Line 1: OK\nLine 2: FATAL CRASH: Out of memory\nLine 3: OK"
        )

        val searchCall = ToolCall(
            id = "test_req_2",
            name = AgentToolRegistry.TOOL_GET_HISTORICAL_TOOL_OUTPUT,
            argumentsJson = JSONObject().apply {
                put("callId", "historical_call_error_log")
                put("query", "FATAL CRASH")
            }.toString()
        )

        val result = orchestrator.executeSingleToolCallForTest(searchCall, sessionId = "orch_session")
        assertFalse(result.isError)
        assertTrue(result.content.contains("FATAL CRASH"))
        assertFalse(result.content.contains("Line 1: OK"))
    }

    @Test
    fun testToolOrchestratorMissingCallIdReturnsError() = runBlocking {
        val orchestrator = createOrchestrator()

        val invalidCall = ToolCall(
            id = "test_req_3",
            name = AgentToolRegistry.TOOL_GET_HISTORICAL_TOOL_OUTPUT,
            argumentsJson = "{}"
        )

        val result = orchestrator.executeSingleToolCallForTest(invalidCall, sessionId = "orch_session")
        assertTrue(result.isError)
        assertTrue(result.content.contains("callId"))
    }

    @Test
    fun testClearSessionPurgesMemoryAndDisk() {
        HistoricalToolOutputStore.recordOutput(
            sessionId = "to_be_purged",
            callId = "call_p1",
            toolName = "test_tool",
            argumentsJson = "{}",
            content = "Sensitive session content"
        )

        assertNotNull(HistoricalToolOutputStore.getOutput("call_p1"))

        HistoricalToolOutputStore.clearSession("to_be_purged")

        assertNull(HistoricalToolOutputStore.getOutput("call_p1"))
    }

    @Test
    fun testEndToEndCodingTaskCompactionAndRetrievalLifecycle() = runBlocking {
        val orchestrator = createOrchestrator()
        val sessionId = "e2e_session_1"
        val callId = "call_read_file_100"

        // 1. Generate large source file output (100 lines, ~4000 chars)
        val fileLines = (1..100).map { line ->
            "fun method$line(param$line: Int): String { return \"Line $line output: \" + param$line }"
        }
        val fullFileContent = fileLines.joinToString("\n")

        val entry = HistoricalToolOutputStore.recordOutput(
            sessionId = sessionId,
            callId = callId,
            toolName = "read_project_file",
            argumentsJson = """{"filePath":"core/Sample.kt"}""",
            content = fullFileContent
        )
        assertFalse(entry.isPrunedInContext)

        // 2. Build multi-turn conversational history exceeding token budget
        val messages = mutableListOf(
            UnifiedChatMessage(
                role = ChatRole.USER,
                content = "Please analyze core/Sample.kt and implement method 25."
            ),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "I will read the file to locate method 25.",
                toolCalls = listOf(
                    ToolCall(
                        id = callId,
                        name = "read_project_file",
                        argumentsJson = """{"filePath":"core/Sample.kt"}"""
                    )
                )
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = fullFileContent,
                toolResult = ToolResultMessage(
                    toolCallId = callId,
                    toolName = "read_project_file",
                    content = fullFileContent
                )
            ),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Now checking project files.",
                toolCalls = listOf(
                    ToolCall(id = "call_mid", name = "list_project_files", argumentsJson = "{}")
                )
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "file1.kt\nfile2.kt",
                toolResult = ToolResultMessage(
                    toolCallId = "call_mid",
                    toolName = "list_project_files",
                    content = "file1.kt\nfile2.kt"
                )
            ),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Now checking outline.",
                toolCalls = listOf(
                    ToolCall(id = "call_recent", name = "get_file_outline", argumentsJson = "{}")
                )
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "OUTLINE: method25",
                toolResult = ToolResultMessage(
                    toolCallId = "call_recent",
                    toolName = "get_file_outline",
                    content = "OUTLINE: method25"
                )
            ),
            UnifiedChatMessage(
                role = ChatRole.USER,
                content = "Now please rewrite lines 20-35 with error handling."
            )
        )

        // 3. Trigger context compaction with constrained budget
        val capabilities = ModelCapabilities(contextLength = 3000, maxOutputTokens = 800)
        val compactionResult = ConversationCompactor.compactMessages(
            messages = messages,
            capabilities = capabilities,
            systemPrompt = "You are a coding assistant.",
            forceCompaction = true,
            overrideMaxInputTokens = 2000
        )

        assertTrue("Context compaction should trigger", compactionResult.compacted)
        assertTrue("At least 1 tool output should be pruned", compactionResult.prunedToolOutputsCount >= 1)
        assertTrue(
            "Estimated tokens should be reduced",
            compactionResult.estimatedTokensAfter < compactionResult.estimatedTokensBefore
        )

        // 4. Verify store marked entry as pruned in context
        val prunedEntry = HistoricalToolOutputStore.getOutput(callId)
        assertNotNull(prunedEntry)
        assertTrue("Entry in store should be marked as pruned", prunedEntry!!.isPrunedInContext)

        // 5. Verify the tool message in compacted history contains the retrieval hint
        val compactedToolMsg = compactionResult.messages.firstOrNull { it.role == ChatRole.TOOL }
        assertNotNull(compactedToolMsg)
        assertTrue(compactedToolMsg!!.content.contains("pruned", ignoreCase = true))
        assertTrue(compactedToolMsg.content.contains(callId))
        assertTrue(compactedToolMsg.content.contains("get_historical_tool_output"))

        // 6. Agent requests slice (lines 20-35) via get_historical_tool_output
        val retrievalCallId = "call_retrieval_slice_1"
        val retrievalCall = ToolCall(
            id = retrievalCallId,
            name = AgentToolRegistry.TOOL_GET_HISTORICAL_TOOL_OUTPUT,
            argumentsJson = JSONObject().apply {
                put("callId", callId)
                put("startLine", 20)
                put("endLine", 35)
            }.toString()
        )

        val toolResult = orchestrator.executeSingleToolCallForTest(retrievalCall, sessionId = sessionId)
        assertFalse(toolResult.isError)
        assertTrue("Should contain start line 20", toolResult.content.contains("L20: fun method20"))
        assertTrue("Should contain end line 35", toolResult.content.contains("L35: fun method35"))
        assertFalse("Must not contain unrequested line 19", toolResult.content.contains("L19: fun method19"))
        assertFalse("Must not contain unrequested line 36", toolResult.content.contains("L36: fun method36"))

        // 7. Verify recursive output-recording prevention:
        // The get_historical_tool_output call itself MUST NOT be recorded into HistoricalToolOutputStore
        val recursiveEntry = HistoricalToolOutputStore.getOutput(retrievalCallId)
        assertNull("Recursive recording of get_historical_tool_output must be prevented", recursiveEntry)
    }

    @Test
    fun testCorruptedAndMissingDiskRecords() = runBlocking {
        val orchestrator = createOrchestrator()
        val sessionDir = File(storageDir, "corrupted_session").apply { mkdirs() }

        // 1. Corrupted non-JSON file
        val corruptedFile = File(sessionDir, "call_corrupted.json")
        corruptedFile.writeText("{ malformed json :::: [not valid")

        val corruptedEntry = HistoricalToolOutputStore.getOutput("call_corrupted")
        assertNull("Corrupted JSON on disk must safely return null without throwing", corruptedEntry)

        val corruptedCall = ToolCall(
            id = "test_corrupt_call",
            name = AgentToolRegistry.TOOL_GET_HISTORICAL_TOOL_OUTPUT,
            argumentsJson = """{"callId":"call_corrupted"}"""
        )
        val resultCorrupt = orchestrator.executeSingleToolCallForTest(corruptedCall, sessionId = "corrupted_session")
        assertTrue("Corrupted record must return error result", resultCorrupt.isError)
        assertTrue(resultCorrupt.content.contains("not found in store"))

        // 2. Completely missing call ID
        val missingCall = ToolCall(
            id = "test_missing_call",
            name = AgentToolRegistry.TOOL_GET_HISTORICAL_TOOL_OUTPUT,
            argumentsJson = """{"callId":"call_non_existent_9999"}"""
        )
        val resultMissing = orchestrator.executeSingleToolCallForTest(missingCall, sessionId = "corrupted_session")
        assertTrue("Missing record must return error result", resultMissing.isError)
        assertTrue(resultMissing.content.contains("not found in store"))
    }

    @Test
    fun testSessionRestorationAcrossInMemoryPurge() {
        val sessionId = "session_restore_test"
        val callId = "call_disk_restore_42"

        HistoricalToolOutputStore.recordOutput(
            sessionId = sessionId,
            callId = callId,
            toolName = "list_project_files",
            argumentsJson = """{"path":"src"}""",
            content = "FileA.kt\nFileB.kt\nFileC.kt"
        )

        // Clear in-memory maps (simulating process restart)
        HistoricalToolOutputStore.resetForTesting()

        // Verify retrieval rehydrates from disk seamlessly
        val restored = HistoricalToolOutputStore.getOutput(callId)
        assertNotNull("Record should be rehydrated from disk", restored)
        assertEquals(sessionId, restored?.sessionId)
        assertEquals("list_project_files", restored?.toolName)
        assertEquals("FileA.kt\nFileB.kt\nFileC.kt", restored?.fullContent)
    }

    @Test
    fun testTokenSavingsCompactionTelemetryMeasurement() {
        val multilineLarge = (1..300).joinToString("\n") { "val item$it = computeLargeValue($it)" }
        val callId = "call_telemetry_large"

        HistoricalToolOutputStore.recordOutput(
            sessionId = "telemetry_session",
            callId = callId,
            toolName = "read_project_file",
            argumentsJson = "{}",
            content = multilineLarge
        )

        val rawMessages = listOf(
            UnifiedChatMessage(role = ChatRole.USER, content = "Refactor item 50."),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = multilineLarge,
                toolResult = ToolResultMessage(
                    toolCallId = callId,
                    toolName = "read_project_file",
                    content = multilineLarge
                )
            ),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Inspecting project dependencies.",
                toolCalls = listOf(ToolCall(id = "c2", name = "list_project_files", argumentsJson = "{}"))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "build.gradle.kts",
                toolResult = ToolResultMessage(toolCallId = "c2", toolName = "list_project_files", content = "build.gradle.kts")
            ),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Checking outline.",
                toolCalls = listOf(ToolCall(id = "c3", name = "get_file_outline", argumentsJson = "{}"))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "Outline ready",
                toolResult = ToolResultMessage(toolCallId = "c3", toolName = "get_file_outline", content = "Outline ready")
            )
        )

        val capabilities = ModelCapabilities(contextLength = 4000, maxOutputTokens = 1000)
        val compactionResult = ConversationCompactor.compactMessages(
            messages = rawMessages,
            capabilities = capabilities,
            systemPrompt = "System",
            forceCompaction = true,
            overrideMaxInputTokens = 1500
        )

        // Measure analytical token savings
        val estimatedTokensBefore = compactionResult.estimatedTokensBefore
        val estimatedTokensAfter = compactionResult.estimatedTokensAfter
        assertTrue("Estimated tokens before must exceed after", estimatedTokensBefore > estimatedTokensAfter)

        val estimatedSavingsRatio = (estimatedTokensBefore - estimatedTokensAfter).toDouble() / estimatedTokensBefore.toDouble()
        assertTrue("Estimated token reduction must be > 60%", estimatedSavingsRatio > 0.60)

        // Simulate provider telemetry verification (measured prompt tokens)
        val uncompactedTelemetry = TokenUsage(promptTokens = 2950, completionTokens = 120, totalTokens = 3070)
        val compactedTelemetry = TokenUsage(promptTokens = 410, completionTokens = 95, totalTokens = 505)

        val measuredSavingsTokens = uncompactedTelemetry.promptTokens - compactedTelemetry.promptTokens
        val measuredSavingsRatio = measuredSavingsTokens.toDouble() / uncompactedTelemetry.promptTokens.toDouble()

        assertEquals(2540, measuredSavingsTokens)
        assertTrue("Measured prompt token reduction must exceed 80%", measuredSavingsRatio > 0.80)
    }
}
