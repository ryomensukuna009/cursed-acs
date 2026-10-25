package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.providers.ProviderTemplates
import com.tom.rv2ide.artificial.tools.AgentToolRegistry
import com.tom.rv2ide.artificial.transport.AnthropicMessagesAdapter
import com.tom.rv2ide.artificial.transport.ChatRole
import com.tom.rv2ide.artificial.transport.GeminiNativeAdapter
import com.tom.rv2ide.artificial.transport.OllamaNativeAdapter
import com.tom.rv2ide.artificial.transport.OpenAICompatibleAdapter
import com.tom.rv2ide.artificial.transport.SseLineParser
import com.tom.rv2ide.artificial.transport.StreamEvent
import com.tom.rv2ide.artificial.transport.ToolCall
import com.tom.rv2ide.artificial.transport.ToolResultMessage
import com.tom.rv2ide.artificial.transport.UnifiedChatMessage
import com.tom.rv2ide.artificial.transport.UnifiedChatRequest
import com.tom.rv2ide.artificial.transport.UnifiedTransportClient
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class Phase6TransportAndStreamingTest {

    @Test
    fun testSseLineParserAndCancellation() {
        val rawSse = """
            : keepalive comment
            event: message
            data: {"chunk": 1}

            data: {"chunk": 2}

            data: {"chunk": 3}

            data: [DONE]
        """.trimIndent()

        var count = 0
        val payloads = mutableListOf<String>()
        SseLineParser.parseSseStream(
            reader = BufferedReader(StringReader(rawSse)),
            isCancelled = { count >= 2 }
        ) { frame ->
            count++
            payloads.add(frame.data)
            true
        }

        // Cancelled after 2 frames, so frame 3 is never processed
        assertEquals(2, payloads.size)
        assertEquals("""{"chunk": 1}""", payloads[0])
        assertEquals("""{"chunk": 2}""", payloads[1])
    }

    @Test
    fun testOpenAIStreamingTextAndIncrementalToolCalls() {
        val sseStream = """
            data: {"id":"chatcmpl-1","model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"Checking "}}]}

            data: {"id":"chatcmpl-1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"live docs..."}}]}

            data: {"id":"chatcmpl-1","model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_abc123","type":"function","function":{"name":"web_search","arguments":"{\"query\":"}}]}}]}

            data: {"id":"chatcmpl-1","model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"kotlin coroutines\"}"}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]
        """.trimIndent()

        val adapter = OpenAICompatibleAdapter()
        val deltas = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCall>()

        val response = adapter.parseStreamingResponse(
            reader = BufferedReader(StringReader(sseStream)),
            modelId = "gpt-4o",
            isCancelled = { false }
        ) { event ->
            when (event) {
                is StreamEvent.TextDelta -> deltas.add(event.text)
                is StreamEvent.ToolCallCompleted -> toolCalls.add(event.toolCall)
                else -> {}
            }
        }

        assertEquals("Checking live docs...", response.text)
        assertEquals(listOf("Checking ", "live docs..."), deltas)
        assertEquals(1, response.toolCalls.size)
        assertEquals("call_abc123", response.toolCalls[0].id)
        assertEquals("web_search", response.toolCalls[0].name)
        assertEquals("kotlin coroutines", response.toolCalls[0].parseArgumentsObject().getString("query"))
    }

    @Test
    fun testAnthropicMessagesRequestFormattingAndStreamingToolUse() {
        val adapter = AnthropicMessagesAdapter()
        val req = UnifiedChatRequest(
            profile = ProviderTemplates.anthropicPreset(),
            modelId = "claude-3-7-sonnet-20250219",
            systemPrompt = "You are an Android expert.",
            messages = listOf(
                UnifiedChatMessage(role = ChatRole.USER, content = "Search NDK docs"),
                UnifiedChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "Searching...",
                    toolCalls = listOf(ToolCall("toolu_1", "web_search", """{"query":"android ndk"}"""))
                ),
                UnifiedChatMessage(
                    role = ChatRole.TOOL,
                    toolResult = ToolResultMessage("toolu_1", "web_search", "Result 1")
                ),
                UnifiedChatMessage(
                    role = ChatRole.TOOL,
                    toolResult = ToolResultMessage("toolu_2", "open_url", "Page content")
                )
            ),
            tools = AgentToolRegistry.WEB_TOOLS,
            stream = true
        )

        val built = adapter.buildHttpRequest(req, "sk-ant-test-key")
        assertEquals("https://api.anthropic.com/v1/messages", built.url)
        assertEquals("sk-ant-test-key", built.headers["x-api-key"])
        assertEquals("2023-06-01", built.headers["anthropic-version"])

        val bodyJson = JSONObject(built.bodyJson)
        assertEquals("You are an Android expert.", bodyJson.getString("system"))
        val msgs = bodyJson.getJSONArray("messages")
        // Consecutive TOOL messages must be merged into a single 'user' message with 2 tool_result blocks
        assertEquals(3, msgs.length())
        val toolResultTurn = msgs.getJSONObject(2)
        assertEquals("user", toolResultTurn.getString("role"))
        assertEquals(2, toolResultTurn.getJSONArray("content").length())

        val anthropicSse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"claude-3-7-sonnet-20250219"}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Let me search."}}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_99","name":"web_search","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"agp"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":" 8.5\"}"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}
        """.trimIndent()

        val streamResp = adapter.parseStreamingResponse(
            reader = BufferedReader(StringReader(anthropicSse)),
            modelId = "claude-3-7-sonnet-20250219",
            isCancelled = { false },
            onEvent = {}
        )

        assertEquals("Let me search.", streamResp.text)
        assertEquals(1, streamResp.toolCalls.size)
        assertEquals("toolu_99", streamResp.toolCalls[0].id)
        assertEquals("web_search", streamResp.toolCalls[0].name)
        assertEquals("agp 8.5", streamResp.toolCalls[0].parseArgumentsObject().getString("query"))
    }

    @Test
    fun testOllamaNativeNdjsonStreaming() {
        val ndjson = """
            {"model":"llama3.2","message":{"role":"assistant","content":"Hello "},"done":false}
            {"model":"llama3.2","message":{"role":"assistant","content":"world!"},"done":false}
            {"model":"llama3.2","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"open_url","arguments":{"url":"https://developer.android.com"}}}]},"done":true,"done_reason":"stop"}
        """.trimIndent()

        val adapter = OllamaNativeAdapter()
        val resp = adapter.parseStreamingResponse(
            reader = BufferedReader(StringReader(ndjson)),
            modelId = "llama3.2",
            isCancelled = { false },
            onEvent = {}
        )

        assertEquals("Hello world!", resp.text)
        assertEquals(1, resp.toolCalls.size)
        assertEquals("open_url", resp.toolCalls[0].name)
        assertEquals("https://developer.android.com", resp.toolCalls[0].parseArgumentsObject().getString("url"))
    }

    @Test
    fun testGeminiNativeGroundingAndCitations() {
        val adapter = GeminiNativeAdapter()
        val req = UnifiedChatRequest(
            profile = ProviderTemplates.geminiNativePreset(),
            modelId = "gemini-2.5-pro",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "What is new in Android 15?")),
            enableGeminiNativeGrounding = true,
            stream = true
        )

        val built = adapter.buildHttpRequest(req, "AIzaTestKey")
        val bodyObj = JSONObject(built.bodyJson)
        val toolsArr = bodyObj.getJSONArray("tools")
        assertTrue(toolsArr.getJSONObject(0).has("google_search"))

        val geminiSse = """
            data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Android 15 introduces "}]}, "groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://developer.android.com/about/versions/15","title":"Android 15 Overview"}}]}}]}

            data: {"candidates":[{"content":{"role":"model","parts":[{"text":"private space and edge-to-edge enforcement."}]}, "finishReason":"STOP"}]}
        """.trimIndent()

        val resp = adapter.parseStreamingResponse(
            reader = BufferedReader(StringReader(geminiSse)),
            modelId = "gemini-2.5-pro",
            isCancelled = { false },
            onEvent = {}
        )

        assertEquals("Android 15 introduces private space and edge-to-edge enforcement.", resp.text)
        assertEquals(1, resp.citations.size)
        assertEquals("Android 15 Overview", resp.citations[0].title)
        assertEquals("https://developer.android.com/about/versions/15", resp.citations[0].url)
    }

    @Test
    fun testUnifiedTransportClientStripsCredentialsOnCrossHostRedirectAndRetries503() = runBlocking {
        val requests = mutableListOf<Request>()
        var callCount = 0

        val mockOkHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val r = chain.request()
                requests.add(r)
                callCount++
                when (callCount) {
                    1 -> {
                        // First attempt returns transient 503
                        Response.Builder()
                            .request(r)
                            .protocol(Protocol.HTTP_1_1)
                            .code(503)
                            .message("Service Unavailable")
                            .body("""{"error":"overloaded"}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    2 -> {
                        // Second attempt redirects to a different host
                        Response.Builder()
                            .request(r)
                            .protocol(Protocol.HTTP_1_1)
                            .code(307)
                            .message("Temporary Redirect")
                            .header("Location", "https://mirror.example.org/v1/chat/completions")
                            .body("".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                    else -> {
                        Response.Builder()
                            .request(r)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("Content-Type", "application/json")
                            .body(
                                """{"choices":[{"message":{"content":"Redirected OK"},"finish_reason":"stop"}]}"""
                                    .toResponseBody("application/json".toMediaType())
                            )
                            .build()
                    }
                }
            })
            .build()

        val client = UnifiedTransportClient(mockOkHttp)
        val chatReq = UnifiedChatRequest(
            profile = ProviderTemplates.openAiPreset().copy(timeoutSec = 10),
            modelId = "gpt-4o",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "Ping")),
            stream = false
        )

        val resp = client.executeChat(
            request = chatReq,
            apiKey = "sk-redirect-secret-key",
            maxRetries = 1
        )

        assertEquals("Redirected OK", resp.text)
        assertEquals(3, requests.size)
        // Request 1 & 2 to api.openai.com had Authorization header
        assertEquals("Bearer sk-redirect-secret-key", requests[0].header("Authorization"))
        assertEquals("Bearer sk-redirect-secret-key", requests[1].header("Authorization"))
        // Request 3 to mirror.example.org MUST have Authorization stripped!
        assertNull(requests[2].header("Authorization"))
    }
}
