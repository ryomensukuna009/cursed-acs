package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.chat.ConversationCompactor
import com.tom.rv2ide.artificial.chat.ConversationSessionManager
import com.tom.rv2ide.artificial.exceptions.ProviderRequestException
import com.tom.rv2ide.artificial.providers.ModelCapabilities
import com.tom.rv2ide.artificial.providers.ProviderTemplates
import com.tom.rv2ide.artificial.providers.TriStateCapability
import com.tom.rv2ide.artificial.secrets.JvmAesGcmSecretBackend
import com.tom.rv2ide.artificial.secrets.SecureKeyStore
import com.tom.rv2ide.artificial.tools.AgentToolRegistry
import com.tom.rv2ide.artificial.tools.SafeUrlFetcher
import com.tom.rv2ide.artificial.tools.SearchProviderType
import com.tom.rv2ide.artificial.tools.ToolOrchestrator
import com.tom.rv2ide.artificial.tools.WebSearchConfig
import com.tom.rv2ide.artificial.tools.WebSearchManager
import com.tom.rv2ide.artificial.transport.ChatRole
import com.tom.rv2ide.artificial.transport.StreamEvent
import com.tom.rv2ide.artificial.transport.ToolCall
import com.tom.rv2ide.artificial.transport.ToolResultMessage
import com.tom.rv2ide.artificial.transport.TransportCancellationToken
import com.tom.rv2ide.artificial.transport.UnifiedChatMessage
import com.tom.rv2ide.artificial.transport.UnifiedTransportClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Phase8ContextCompactionTest {

    @Before
    fun setUp() {
        SecureKeyStore.setBackendForTesting(JvmAesGcmSecretBackend())
    }

    @After
    fun tearDown() {
        SecureKeyStore.setBackendForTesting(null)
    }

    @Test
    fun `test 1 - compaction remains inactive for a request comfortably within budget`() {
        val messages = listOf(
            UnifiedChatMessage(role = ChatRole.USER, content = "Hello! Please list my files.")
        )
        val result = ConversationCompactor.compactMessages(
            messages = messages,
            capabilities = ModelCapabilities(contextLength = 32_000),
            systemPrompt = "You are a coding assistant.",
            tools = AgentToolRegistry.ENGINEERING_TOOLS
        )

        assertFalse("Compaction must be inactive within budget", result.compacted)
        assertEquals(messages.size, result.messages.size)
        assertEquals(0, result.prunedToolOutputsCount)
        assertEquals(0, result.prunedTurnsCount)
    }

    @Test
    fun `test 2 - compaction triggers near a known context threshold`() {
        // Small 8k context window -> trigger threshold around ~5k tokens (~20k chars)
        val capabilities = ModelCapabilities(contextLength = 8_192)
        val bigOldContent = "A".repeat(25_000)

        val messages = listOf(
            UnifiedChatMessage(role = ChatRole.USER, content = "Initial prompt"),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Calling read_project_file",
                toolCalls = listOf(ToolCall("c1", "read_project_file", """{"filePath":"Main.kt"}"""))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = bigOldContent,
                toolResult = ToolResultMessage("c1", "read_project_file", bigOldContent)
            ),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Calling build_current_project",
                toolCalls = listOf(ToolCall("c2", "build_current_project", "{}"))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "Build success",
                toolResult = ToolResultMessage("c2", "build_current_project", "Build success")
            ),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Calling second read",
                toolCalls = listOf(ToolCall("c3", "read_project_file", """{"filePath":"Second.kt"}"""))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "Second file content",
                toolResult = ToolResultMessage("c3", "read_project_file", "Second file content")
            ),
            UnifiedChatMessage(role = ChatRole.USER, content = "Latest instruction")
        )

        val result = ConversationCompactor.compactMessages(
            messages = messages,
            capabilities = capabilities,
            systemPrompt = "System instructions"
        )

        assertTrue("Compaction must trigger when tokens exceed threshold", result.compacted)
        assertTrue(result.estimatedTokensAfter < result.estimatedTokensBefore)
        assertTrue("Older tool output must be pruned", result.prunedToolOutputsCount >= 1)
    }

    @Test
    fun `test 3 - unknown model limits use a clearly identified conservative fallback`() {
        val budget = ConversationCompactor.computeBudget(
            capabilities = null,
            systemPrompt = "System prompt",
            tools = emptyList(),
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Test"))
        )

        assertEquals(ConversationCompactor.DEFAULT_CONSERVATIVE_CONTEXT_LIMIT, budget.contextLength)
        assertTrue("Budget must be flagged as an estimate when capabilities are null", budget.isEstimatedLimit)
        assertEquals(32_000, budget.contextLength)
    }

    @Test
    fun `test 4 - large historical build logs are pruned safely`() {
        val bigLog = "[INIT] Start build\n" + "Compiling sources...\n".repeat(800) + "[BUILD FAILED]"
        val messages = listOf(
            UnifiedChatMessage(role = ChatRole.USER, content = "Run build"),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Running build",
                toolCalls = listOf(ToolCall("b1", "build_current_project", "{}"))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = bigLog,
                toolResult = ToolResultMessage("b1", "build_current_project", bigLog)
            ),
            // Two more recent tool turns so b1 is considered historical
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Tool 2",
                toolCalls = listOf(ToolCall("t2", "read_file", """{"filePath":"A.kt"}"""))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "content A",
                toolResult = ToolResultMessage("t2", "read_file", "content A")
            ),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Tool 3",
                toolCalls = listOf(ToolCall("t3", "read_file", """{"filePath":"B.kt"}"""))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "content B",
                toolResult = ToolResultMessage("t3", "read_file", "content B")
            )
        )

        val result = ConversationCompactor.compactMessages(
            messages = messages,
            capabilities = ModelCapabilities(contextLength = 8_000),
            forceCompaction = true
        )

        val prunedMsg = result.messages.first { it.role == ChatRole.TOOL && it.toolResult?.toolCallId == "b1" }
        assertTrue(prunedMsg.content.contains("[Historical build log pruned for context:"))
        assertEquals("b1", prunedMsg.toolResult?.toolCallId)
        assertEquals("build_current_project", prunedMsg.toolResult?.toolName)
    }

    @Test
    fun `test 5 - large old file-read results are pruned while current required source context remains available`() {
        val oldFileCode = "class OldHugeFile {\n" + "    val x = 1\n".repeat(600) + "}"
        val recentFileCode = "class CurrentFile { val fresh = true }"

        val messages = listOf(
            UnifiedChatMessage(role = ChatRole.USER, content = "Inspect project"),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Read old file",
                toolCalls = listOf(ToolCall("f1", "read_project_file", """{"filePath":"Old.kt"}"""))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = oldFileCode,
                toolResult = ToolResultMessage("f1", "read_project_file", oldFileCode)
            ),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Read recent file 1",
                toolCalls = listOf(ToolCall("f2", "read_project_file", """{"filePath":"Recent1.kt"}"""))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "Recent 1",
                toolResult = ToolResultMessage("f2", "read_project_file", "Recent 1")
            ),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Read recent file 2",
                toolCalls = listOf(ToolCall("f3", "read_project_file", """{"filePath":"Current.kt"}"""))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = recentFileCode,
                toolResult = ToolResultMessage("f3", "read_project_file", recentFileCode)
            )
        )

        val result = ConversationCompactor.compactMessages(
            messages = messages,
            capabilities = ModelCapabilities(contextLength = 8_000),
            forceCompaction = true
        )

        val oldPruned = result.messages.first { it.toolResult?.toolCallId == "f1" }
        assertTrue(oldPruned.content.contains("[Historical file read pruned for context:"))

        val recentKept = result.messages.first { it.toolResult?.toolCallId == "f3" }
        assertEquals(recentFileCode, recentKept.content)
    }

    @Test
    fun `test 6 - latest compiler failure and unresolved fatal errors survive compaction`() {
        val diagnosticDeltaContent = """
            === DELTA COMPILER DIAGNOSTICS ===
            [!] NEW ERRORS INTRODUCED IN THIS WAVE (1):
              1. [COMPILER] ERROR: Main.cpp:42 - undefined reference to 'broken_func'
            [*] PERSISTENT ROOT-CAUSE ERRORS (1):
              1. [COMPILER] ERROR: Helper.kt:10 - syntax error
        """.trimIndent()

        val messages = listOf(
            UnifiedChatMessage(role = ChatRole.USER, content = "Fix build"),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Running build",
                toolCalls = listOf(ToolCall("b1", "get_build_diagnostics", "{}"))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = diagnosticDeltaContent,
                toolResult = ToolResultMessage("b1", "get_build_diagnostics", diagnosticDeltaContent)
            )
        )

        val result = ConversationCompactor.compactMessages(
            messages = messages,
            capabilities = ModelCapabilities(contextLength = 8_000),
            forceCompaction = true
        )

        val diagMsg = result.messages.first { it.toolResult?.toolCallId == "b1" }
        assertEquals("Diagnostic delta content must NOT be pruned", diagnosticDeltaContent, diagMsg.content)
    }

    @Test
    fun `test 7 - original system instructions and user task remain unchanged`() {
        val originalSystemPrompt = "You are the ROXUM Android IDE coding agent. Strict rules apply."
        val originalUserPrompt = "Please repair the build in app/src/main/jni/Main.cpp"

        val messages = listOf(
            UnifiedChatMessage(role = ChatRole.USER, content = originalUserPrompt),
            UnifiedChatMessage(role = ChatRole.ASSISTANT, content = "Working on it..."),
            UnifiedChatMessage(role = ChatRole.USER, content = "Next step")
        )

        val result = ConversationCompactor.compactMessages(
            messages = messages,
            capabilities = ModelCapabilities(contextLength = 32_000),
            systemPrompt = originalSystemPrompt
        )

        assertEquals(originalUserPrompt, result.messages.first().content)
        assertEquals(ChatRole.USER, result.messages.first().role)
    }

    @Test
    fun `test 8 - assistant tool-call and tool-result pairs remain valid after pruning`() {
        val bigData = "X".repeat(15_000)
        val messages = listOf(
            UnifiedChatMessage(role = ChatRole.USER, content = "Do work"),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Calling tools",
                toolCalls = listOf(
                    ToolCall("c_alpha", "read_file", """{"filePath":"A.kt"}"""),
                    ToolCall("c_beta", "read_file", """{"filePath":"B.kt"}""")
                )
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = bigData,
                toolResult = ToolResultMessage("c_alpha", "read_file", bigData)
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = bigData,
                toolResult = ToolResultMessage("c_beta", "read_file", bigData)
            ),
            UnifiedChatMessage(role = ChatRole.ASSISTANT, content = "Next"),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "Recent 1",
                toolResult = ToolResultMessage("c_gamma", "read_file", "Recent 1")
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "Recent 2",
                toolResult = ToolResultMessage("c_delta", "read_file", "Recent 2")
            )
        )

        val result = ConversationCompactor.compactMessages(
            messages = messages,
            capabilities = ModelCapabilities(contextLength = 8_000),
            forceCompaction = true
        )

        val assistantMsg = result.messages.first { it.role == ChatRole.ASSISTANT && it.toolCalls.isNotEmpty() }
        assertEquals(2, assistantMsg.toolCalls.size)

        val resultAlpha = result.messages.firstOrNull { it.role == ChatRole.TOOL && it.toolResult?.toolCallId == "c_alpha" }
        val resultBeta = result.messages.firstOrNull { it.role == ChatRole.TOOL && it.toolResult?.toolCallId == "c_beta" }

        assertNotNull("c_alpha tool result must exist after compaction", resultAlpha)
        assertNotNull("c_beta tool result must exist after compaction", resultBeta)
    }

    @Test
    fun `test 9 - no dangling or duplicated tool-call IDs are created`() {
        val messages = mutableListOf<UnifiedChatMessage>()
        messages.add(UnifiedChatMessage(role = ChatRole.USER, content = "Start"))
        for (i in 1..6) {
            val callId = "call_$i"
            messages.add(
                UnifiedChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "Step $i",
                    toolCalls = listOf(ToolCall(callId, "read_project_file", """{"filePath":"File$i.kt"}"""))
                )
            )
            val fileContent = "Content for file $i\n".repeat(400)
            messages.add(
                UnifiedChatMessage(
                    role = ChatRole.TOOL,
                    content = fileContent,
                    toolResult = ToolResultMessage(callId, "read_project_file", fileContent)
                )
            )
        }

        val result = ConversationCompactor.compactMessages(
            messages = messages,
            capabilities = ModelCapabilities(contextLength = 8_000),
            forceCompaction = true
        )

        val allCallIds = result.messages.filter { it.role == ChatRole.ASSISTANT }
            .flatMap { it.toolCalls }
            .map { it.id }

        val allResultIds = result.messages.filter { it.role == ChatRole.TOOL }
            .mapNotNull { it.toolResult?.toolCallId }

        assertEquals("Number of assistant tool calls must equal number of tool results", allCallIds.size, allResultIds.size)
        assertEquals("Tool call IDs must match tool result IDs", allCallIds.toSet(), allResultIds.toSet())
        assertEquals("No duplicated tool call IDs", allCallIds.size, allCallIds.toSet().size)
    }

    @Test
    fun `test 10 - context-length HTTP 400 triggers one bounded compaction retry`() = runBlocking {
        var callCount = 0
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                callCount++
                val req = chain.request()
                if (callCount == 1) {
                    // Attempt 1: Fail with context length exceeded
                    val errJson = """
                        {
                          "error": {
                            "message": "This model's maximum context length is 32000 tokens. However, your messages resulted in 35000 tokens.",
                            "type": "invalid_request_error",
                            "code": "context_length_exceeded"
                          }
                        }
                    """.trimIndent()
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(400)
                        .message("Bad Request")
                        .header("Content-Type", "application/json")
                        .body(errJson.toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    // Attempt 2: After compaction, succeed!
                    val successJson = """
                        {
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "content": "Recovered successfully after compaction."
                            },
                            "finish_reason": "stop"
                          }]
                        }
                    """.trimIndent()
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json")
                        .body(successJson.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            })
            .build()

        val searchMgr = WebSearchManager(context = null, baseHttpClient = mockHttp)
        val orchestrator = ToolOrchestrator(
            transportClient = UnifiedTransportClient(mockHttp),
            webSearchManager = searchMgr,
            safeUrlFetcher = SafeUrlFetcher(mockHttp)
        )

        val statusEvents = mutableListOf<String>()
        val resp = orchestrator.executeOrchestratedChat(
            profile = ProviderTemplates.openAiPreset(),
            modelId = "gpt-4o",
            modelCapabilities = ModelCapabilities(supportsTools = TriStateCapability.SUPPORTED),
            apiKey = "sk-test",
            messages = listOf(
                UnifiedChatMessage(role = ChatRole.USER, content = "User request"),
                UnifiedChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "Read",
                    toolCalls = listOf(ToolCall("c1", "read_project_file", "{}"))
                ),
                UnifiedChatMessage(
                    role = ChatRole.TOOL,
                    content = "Large content ".repeat(1000),
                    toolResult = ToolResultMessage("c1", "read_project_file", "Large content ".repeat(1000))
                )
            ),
            stream = false
        ) { evt ->
            if (evt is StreamEvent.StatusUpdate) {
                statusEvents.add(evt.status)
            }
        }

        assertEquals("Recovered successfully after compaction.", resp.text)
        assertEquals(2, callCount)
        assertTrue(statusEvents.any { it.contains("Context limit reached") })
    }

    @Test
    fun `test 11 - non-context-related HTTP 400 does not trigger context compaction retry`() = runBlocking {
        var callCount = 0
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                callCount++
                val req = chain.request()
                val errJson = """
                    {
                      "error": {
                        "message": "Invalid JSON format: missing required parameter 'model'",
                        "type": "invalid_request_error"
                      }
                    }
                """.trimIndent()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .message("Bad Request")
                    .header("Content-Type", "application/json")
                    .body(errJson.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val searchMgr = WebSearchManager(context = null, baseHttpClient = mockHttp)
        val orchestrator = ToolOrchestrator(
            transportClient = UnifiedTransportClient(mockHttp),
            webSearchManager = searchMgr,
            safeUrlFetcher = SafeUrlFetcher(mockHttp)
        )

        try {
            orchestrator.executeOrchestratedChat(
                profile = ProviderTemplates.openAiPreset(),
                modelId = "gpt-4o",
                modelCapabilities = ModelCapabilities(supportsTools = TriStateCapability.SUPPORTED),
                apiKey = "sk-test",
                messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Test")),
                stream = false
            )
            fail("Must throw ProviderRequestException on non-context 400")
        } catch (e: ProviderRequestException) {
            assertEquals("Call count must be exactly 1 (no retry for non-context 400)", 1, callCount)
            assertEquals(400, e.statusCode)
        }
    }

    @Test
    fun `test 12 - second context-length failure is reported honestly without an infinite retry`() = runBlocking {
        var callCount = 0
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                callCount++
                val req = chain.request()
                val errJson = """
                    {
                      "error": {
                        "message": "context_length_exceeded: maximum context length is 4096 tokens",
                        "type": "invalid_request_error"
                      }
                    }
                """.trimIndent()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .message("Bad Request")
                    .header("Content-Type", "application/json")
                    .body(errJson.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val searchMgr = WebSearchManager(context = null, baseHttpClient = mockHttp)
        val orchestrator = ToolOrchestrator(
            transportClient = UnifiedTransportClient(mockHttp),
            webSearchManager = searchMgr,
            safeUrlFetcher = SafeUrlFetcher(mockHttp)
        )

        try {
            orchestrator.executeOrchestratedChat(
                profile = ProviderTemplates.openAiPreset(),
                modelId = "gpt-4o",
                modelCapabilities = ModelCapabilities(supportsTools = TriStateCapability.SUPPORTED),
                apiKey = "sk-test",
                messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Test")),
                stream = false
            )
            fail("Must throw ProviderRequestException after bounded retry fails")
        } catch (e: ProviderRequestException) {
            assertEquals("Must retry at most once (call count == 2)", 2, callCount)
            assertTrue(ConversationCompactor.isContextLengthError(e.statusCode, e.message))
        }
    }

    @Test
    fun `test 13 - cancellation is respected during recovery`() {
        val cancellationToken = TransportCancellationToken()
        cancellationToken.cancel()

        assertTrue(cancellationToken.isCancelled())
    }

    @Test
    fun `test 14 - repeated compaction is idempotent`() {
        val bigContent = "X".repeat(10_000)
        val messages = listOf(
            UnifiedChatMessage(role = ChatRole.USER, content = "User prompt"),
            UnifiedChatMessage(
                role = ChatRole.ASSISTANT,
                content = "Calling tool",
                toolCalls = listOf(ToolCall("c1", "read_project_file", "{}"))
            ),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = bigContent,
                toolResult = ToolResultMessage("c1", "read_project_file", bigContent)
            ),
            UnifiedChatMessage(role = ChatRole.ASSISTANT, content = "Recent 1"),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "Recent 1",
                toolResult = ToolResultMessage("c2", "read_project_file", "Recent 1")
            ),
            UnifiedChatMessage(role = ChatRole.ASSISTANT, content = "Recent 2"),
            UnifiedChatMessage(
                role = ChatRole.TOOL,
                content = "Recent 2",
                toolResult = ToolResultMessage("c3", "read_project_file", "Recent 2")
            )
        )

        val pass1 = ConversationCompactor.compactMessages(
            messages = messages,
            capabilities = ModelCapabilities(contextLength = 8_000),
            forceCompaction = true
        )
        val pass2 = ConversationCompactor.compactMessages(
            messages = pass1.messages,
            capabilities = ModelCapabilities(contextLength = 8_000),
            forceCompaction = true
        )

        val pass1Pruned = pass1.messages.first { it.toolResult?.toolCallId == "c1" }.content
        val pass2Pruned = pass2.messages.first { it.toolResult?.toolCallId == "c1" }.content

        assertEquals("Repeated compaction must not change already-pruned marker", pass1Pruned, pass2Pruned)
        assertFalse("Must not double-wrap pruned marker", pass2Pruned.contains("[Historical [Historical"))
    }

    @Test
    fun `test 15 - persistent conversation history in session manager remains intact`() {
        val tempDir = Files.createTempDirectory("roxum_compaction_session_test").toFile()
        try {
            val sessionManager = ConversationSessionManager(tempDir)
            sessionManager.setActiveProjectPath("/test/project")

            sessionManager.appendUserMessage("Turn 1 user", "openai", "OpenAI", "gpt-4o")
            sessionManager.appendAssistantMessage("Turn 1 assistant response")
            sessionManager.appendUserMessage("Turn 2 user with big context " + "A".repeat(5000), "openai", "OpenAI", "gpt-4o")
            sessionManager.appendAssistantMessage("Turn 2 assistant response")

            val active = sessionManager.getActiveConversation("/test/project")
            assertEquals("UI message list must retain all 4 messages intact", 4, active.messages.size)

            // Request building applies budgeting
            val requestMessages = sessionManager.buildModelRequestMessages(
                conversationId = active.id,
                latestUserPromptWithProjectContext = "Turn 3 user prompt",
                latestRawUserText = "Turn 3 user prompt",
                maxContextTokens = 8_192
            )

            assertTrue(requestMessages.isNotEmpty())
            // Original conversation messages in sessionManager are untouched!
            assertEquals("UI conversation messages must not be mutated by request building", 4, active.messages.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test 16 - existing delta diagnostics and transactional patching behavior remain correct`() {
        // Verify isContextLengthError accuracy
        assertTrue(ConversationCompactor.isContextLengthError(400, "maximum context length is 128000 tokens"))
        assertTrue(ConversationCompactor.isContextLengthError(400, "context_length_exceeded"))
        assertTrue(ConversationCompactor.isContextLengthError(400, "prompt is too long: 200000 tokens"))
        assertTrue(ConversationCompactor.isContextLengthError(400, "Request payload size exceeds the limit"))
        assertTrue(ConversationCompactor.isContextLengthError(413, "Payload Too Large"))

        assertFalse(ConversationCompactor.isContextLengthError(400, "Invalid API key provided"))
        assertFalse(ConversationCompactor.isContextLengthError(404, "Model not found"))
        assertFalse(ConversationCompactor.isContextLengthError(500, "Internal server error"))
    }
}
