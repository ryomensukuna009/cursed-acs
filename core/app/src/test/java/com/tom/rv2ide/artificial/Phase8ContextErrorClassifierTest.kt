package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.chat.ConversationCompactor
import com.tom.rv2ide.artificial.exceptions.ProviderRequestException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import com.tom.rv2ide.artificial.providers.ModelCapabilities
import com.tom.rv2ide.artificial.providers.ProviderTemplates
import com.tom.rv2ide.artificial.providers.TriStateCapability
import com.tom.rv2ide.artificial.tools.SafeUrlFetcher
import com.tom.rv2ide.artificial.tools.ToolOrchestrator
import com.tom.rv2ide.artificial.tools.WebSearchManager
import com.tom.rv2ide.artificial.transport.ChatRole
import com.tom.rv2ide.artificial.transport.StreamEvent
import com.tom.rv2ide.artificial.transport.ToolCall
import com.tom.rv2ide.artificial.transport.ToolResultMessage
import com.tom.rv2ide.artificial.transport.UnifiedChatMessage
import com.tom.rv2ide.artificial.transport.UnifiedTransportClient
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 8.3.1: Context Error Classifier Hardening Tests.
 *
 * Validates that context-window error detection accurately classifies genuine
 * provider context-overflow errors while strictly rejecting:
 * - Invalid credentials / authorization tokens
 * - Rate limits, RPM/TPM exhaustion, quotas, and HTTP 429
 * - Generic limits (daily, usage, credit)
 * - Invalid request parameters and malformed JSON
 * - Execution/system contexts
 */
class Phase8ContextErrorClassifierTest {

    @Test
    fun `test 1 - genuine context-window overflow with structured codes and provider signatures`() {
        // OpenAI structured code and message
        assertTrue(
            "OpenAI structured code",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "This model's maximum context length is 128000 tokens. However, your messages resulted in 135000 tokens.",
                errorCode = "context_length_exceeded",
                errorType = "invalid_request_error"
            )
        )

        // Anthropic structured type
        assertTrue(
            "Anthropic structured type request_too_large",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "request is too large: 215000 tokens > 200000 maximum",
                errorCode = null,
                errorType = "request_too_large"
            )
        )

        // Google Gemini input token count exceeded
        assertTrue(
            "Gemini input token count overflow",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "The input token count (1048577) exceeds the maximum allowed token count (1048576).",
                errorCode = "INVALID_ARGUMENT",
                errorType = null
            )
        )

        // Google Gemini payload size exceeds limit
        assertTrue(
            "Gemini payload size limit",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "Request payload size exceeds the limit: 10485760 bytes",
                errorCode = "INVALID_ARGUMENT",
                errorType = null
            )
        )

        // Ollama / llama.cpp KV cache / context length exceeded
        assertTrue(
            "Ollama context length exceeded",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "context length exceeded: prompt is larger than the context window"
            )
        )

        // HTTP 413 Payload Too Large
        assertTrue(
            "HTTP 413 Payload Too Large",
            ConversationCompactor.isContextLengthError(
                statusCode = 413,
                errorMessage = "Payload Too Large"
            )
        )
    }

    @Test
    fun `test 2 - prompt-too-long and maximum-context-length errors`() {
        // Anthropic message pattern
        assertTrue(
            "Anthropic prompt is too long",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "prompt is too long: 215000 tokens > 200000 maximum"
            )
        )

        // Generic prompt exceeds maximum context length
        assertTrue(
            "Prompt exceeds maximum context length",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "Your prompt exceeds the maximum context length of 32768 tokens."
            )
        )

        // Prompt length exceeds
        assertTrue(
            "Prompt length exceeds",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "prompt length exceeds maximum allowable tokens for this model"
            )
        )

        // Exception wrapper overload
        val ex = ProviderRequestException(
            statusCode = 400,
            message = "Provider error (HTTP 400): prompt is too long",
            errorCode = "prompt_too_long",
            errorType = "invalid_request_error"
        )
        assertTrue("Exception overload accepts genuine context overflow", ConversationCompactor.isContextLengthError(ex))
    }

    @Test
    fun `test 3 - invalid API token and credential errors do NOT trigger compaction`() {
        // Invalid API token
        assertFalse(
            "Invalid API token message",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "Invalid API token provided in Authorization header"
            )
        )

        // Bearer token expired
        assertFalse(
            "Bearer token expired",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "Bearer token expired: please refresh credentials"
            )
        )

        // Authorization token invalid code
        assertFalse(
            "Structured invalid_token code",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "Token signature verification failed",
                errorCode = "invalid_token",
                errorType = "authentication_error"
            )
        )

        // HTTP 401 Unauthorized
        assertFalse(
            "HTTP 401 is never context length error",
            ConversationCompactor.isContextLengthError(
                statusCode = 401,
                errorMessage = "Unauthorized: token missing"
            )
        )

        // HTTP 403 Forbidden
        assertFalse(
            "HTTP 403 is never context length error",
            ConversationCompactor.isContextLengthError(
                statusCode = 403,
                errorMessage = "Access token revoked"
            )
        )
    }

    @Test
    fun `test 4 - rate_limit_exceeded and HTTP 429 do NOT trigger compaction`() {
        // HTTP 429 rate limit
        assertFalse(
            "HTTP 429 status code is never context length error",
            ConversationCompactor.isContextLengthError(
                statusCode = 429,
                errorMessage = "Rate limit exceeded: please slow down"
            )
        )

        // HTTP 400 with rate_limit_exceeded
        assertFalse(
            "rate_limit_exceeded in message",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "rate_limit_exceeded: Rate limit reached for requests per minute (RPM)"
            )
        )

        // Tokens per minute (TPM) contains "token" but is a rate limit!
        assertFalse(
            "Tokens per minute rate limit must not trigger compaction",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "Rate limit exceeded: 40000 tokens per minute (TPM) limit reached"
            )
        )

        // Structured code rate_limit_exceeded
        assertFalse(
            "Structured rate_limit_exceeded code",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "You have exceeded your request limit",
                errorCode = "rate_limit_exceeded"
            )
        )

        // Gemini resource_exhausted
        assertFalse(
            "resource_exhausted quota error",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "Quota exceeded: resource_exhausted",
                errorCode = "RESOURCE_EXHAUSTED"
            )
        )
    }

    @Test
    fun `test 5 - generic limit exceeded messages do NOT trigger compaction`() {
        // Daily limit exceeded
        assertFalse(
            "Daily limit exceeded",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "Daily request limit exceeded for free tier"
            )
        )

        // Usage limit exceeded
        assertFalse(
            "Usage limit exceeded",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "Organization usage limit exceeded"
            )
        )

        // Credit / billing limit
        assertFalse(
            "Credit limit exceeded",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "Account credit limit exceeded for current billing cycle"
            )
        )

        // Generic limit exceeded without context window specifier
        assertFalse(
            "Bare limit exceeded",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "Request limit exceeded: max 50 concurrent requests"
            )
        )
    }

    @Test
    fun `test 6 - invalid request parameters and malformed-request HTTP 400 do NOT trigger compaction`() {
        // Missing parameter
        assertFalse(
            "Missing parameter",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "Invalid JSON format: missing required parameter 'model'"
            )
        )

        // Parameter temperature out of range
        assertFalse(
            "Temperature parameter",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "Parameter 'temperature' must be between 0 and 2"
            )
        )

        // max_tokens completion parameter exceeds output limit
        assertFalse(
            "max_tokens completion parameter error",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "max_tokens is too large: 8192. This model supports at most 4096 completion tokens"
            )
        )

        // Tools not supported
        assertFalse(
            "Tools not supported",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "tools are not supported for this model"
            )
        )

        // Non-LLM execution context timeout
        assertFalse(
            "Execution context timeout",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "Execution context exceeded maximum allowed timeout"
            )
        )

        // Security context violation
        assertFalse(
            "Security context error",
            ConversationCompactor.isContextLengthError(
                statusCode = 400,
                errorMessage = "Security context authentication failed"
            )
        )
    }

    @Test
    fun `test 7 - orchestrator executes exactly one compaction retry for genuine context error`() = runBlocking {
        var callCount = 0
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                callCount++
                val req = chain.request()
                if (callCount == 1) {
                    val errJson = """
                        {
                          "error": {
                            "message": "This model's maximum context length is 128000 tokens. However, your messages resulted in 135000 tokens.",
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
                    val okJson = """
                        {
                          "id": "chatcmpl-test",
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "Recovered successfully after compacting context."
                              },
                              "finish_reason": "stop"
                            }
                          ]
                        }
                    """.trimIndent()
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "application/json")
                        .body(okJson.toResponseBody("application/json".toMediaType()))
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
                UnifiedChatMessage(role = ChatRole.USER, content = "User task"),
                UnifiedChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "Read",
                    toolCalls = listOf(ToolCall("c1", "read_project_file", "{}"))
                ),
                UnifiedChatMessage(
                    role = ChatRole.TOOL,
                    content = "Large file content ".repeat(800),
                    toolResult = ToolResultMessage("c1", "read_project_file", "Large file content ".repeat(800))
                )
            ),
            stream = false
        ) { evt ->
            if (evt is StreamEvent.StatusUpdate) {
                statusEvents.add(evt.status)
            }
        }

        assertEquals("Recovered successfully after compacting context.", resp.text)
        assertEquals("Must execute exactly one retry (callCount == 2)", 2, callCount)
        assertTrue(statusEvents.any { it.contains("Context limit reached") })
    }

    @Test
    fun `test 8 - orchestrator executes NO compaction retry for non-context errors`() = runBlocking {
        val nonContextErrorResponses = listOf(
            // 8a: Parameter error
            """{"error": {"message": "Parameter 'temperature' must be between 0 and 2", "type": "invalid_request_error"}}""",
            // 8b: Token credential error
            """{"error": {"message": "Invalid API token provided in Authorization header", "code": "invalid_token"}}""",
            // 8c: Generic limit error
            """{"error": {"message": "Daily usage limit exceeded for current billing cycle", "code": "usage_limit_exceeded"}}"""
        )

        for (errPayload in nonContextErrorResponses) {
            var callCount = 0
            val mockHttp = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    callCount++
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(400)
                        .message("Bad Request")
                        .header("Content-Type", "application/json")
                        .body(errPayload.toResponseBody("application/json".toMediaType()))
                        .build()
                })
                .build()

            val orchestrator = ToolOrchestrator(
                transportClient = UnifiedTransportClient(mockHttp),
                webSearchManager = WebSearchManager(context = null, baseHttpClient = mockHttp),
                safeUrlFetcher = SafeUrlFetcher(mockHttp)
            )

            try {
                orchestrator.executeOrchestratedChat(
                    profile = ProviderTemplates.openAiPreset(),
                    modelId = "gpt-4o",
                    modelCapabilities = ModelCapabilities(supportsTools = TriStateCapability.SUPPORTED),
                    apiKey = "sk-test",
                    messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "User task")),
                    stream = false
                )
                fail("Must fail fast without retrying for non-context error: $errPayload")
            } catch (e: ProviderRequestException) {
                assertEquals("Call count must be exactly 1 (no retry) for $errPayload", 1, callCount)
            }
        }

        // 8d: RateLimitException on HTTP 429
        var rateLimitCalls = 0
        val mock429Http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                rateLimitCalls++
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .header("Content-Type", "application/json")
                    .body("""{"error": {"message": "Rate limit reached", "code": "rate_limit_exceeded"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val rateLimitOrchestrator = ToolOrchestrator(
            transportClient = UnifiedTransportClient(mock429Http),
            webSearchManager = WebSearchManager(context = null, baseHttpClient = mock429Http),
            safeUrlFetcher = SafeUrlFetcher(mock429Http)
        )

        try {
            rateLimitOrchestrator.executeOrchestratedChat(
                profile = ProviderTemplates.openAiPreset(),
                modelId = "gpt-4o",
                modelCapabilities = ModelCapabilities(supportsTools = TriStateCapability.SUPPORTED),
                apiKey = "sk-test",
                messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "User task")),
                stream = false
            )
            fail("Must fail on HTTP 429")
        } catch (e: RateLimitException) {
            assertEquals("Call count must be exactly 1 for HTTP 429", 1, rateLimitCalls)
        }
    }
}
