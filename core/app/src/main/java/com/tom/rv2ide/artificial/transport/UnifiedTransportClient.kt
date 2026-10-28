package com.tom.rv2ide.artificial.transport

import com.tom.rv2ide.artificial.chat.ConversationCompactor
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.NetworkErrorException
import com.tom.rv2ide.artificial.exceptions.ProviderRequestException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import com.tom.rv2ide.artificial.exceptions.UnsupportedEndpointException
import com.tom.rv2ide.artificial.providers.ApiProtocol
import com.tom.rv2ide.artificial.secrets.EndpointSecurityValidator
import com.tom.rv2ide.artificial.secrets.SecretRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TransportCancellationToken {
    private val cancelled = AtomicBoolean(false)
    private val activeCall = AtomicReference<Call?>(null)

    fun isCancelled(): Boolean = cancelled.get()

    fun attachCall(call: Call) {
        activeCall.set(call)
        if (cancelled.get()) {
            call.cancel()
        }
    }

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            activeCall.getAndSet(null)?.cancel()
        }
    }
}

open class UnifiedTransportClient(
    private val baseHttpClient: OkHttpClient = defaultHttpClient()
) {

    private val adapters: List<ProtocolAdapter> = listOf(
        OpenAICompatibleAdapter(),
        AnthropicMessagesAdapter(),
        OllamaNativeAdapter(),
        GeminiNativeAdapter()
    )

    fun adapterFor(protocol: ApiProtocol): ProtocolAdapter {
        return adapters.firstOrNull { protocol in it.supportedProtocols }
            ?: adapters.first()
    }

    open suspend fun executeChat(
        request: UnifiedChatRequest,
        apiKey: String?,
        cancellationToken: TransportCancellationToken = TransportCancellationToken(),
        maxRetries: Int = 1,
        onEvent: (StreamEvent) -> Unit = {}
    ): UnifiedChatResponse = withContext(Dispatchers.IO) {
        val adapter = adapterFor(request.profile.protocol)
        val built = adapter.buildHttpRequest(request, apiKey)
        val secrets = listOfNotNull(apiKey?.takeIf { it.isNotBlank() })

        EndpointSecurityValidator.validateEndpointUrl(
            urlString = built.url,
            allowCleartextHttp = request.profile.allowCleartextHttp
        )

        val timeoutSec = request.profile.timeoutSec.coerceIn(5, 600).toLong()
        val client = baseHttpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var attempt = 0
        var lastException: Throwable? = null

        while (attempt <= maxRetries) {
            coroutineContext.ensureActive()
            if (cancellationToken.isCancelled()) {
                throw CancellationException("AI request cancelled")
            }

            try {
                return@withContext executeSingleAttempt(
                    client = client,
                    adapter = adapter,
                    built = built,
                    request = request,
                    secrets = secrets,
                    cancellationToken = cancellationToken,
                    onEvent = onEvent
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitException) {
                lastException = e
                val waitSec = e.retryAfterSeconds
                if (attempt < maxRetries && waitSec != null && waitSec in 1..4) {
                    delay(waitSec * 1000L)
                    attempt++
                } else {
                    throw e
                }
            } catch (e: ProviderRequestException) {
                lastException = e
                if (attempt < maxRetries && e.statusCode in listOf(502, 503, 504)) {
                    delay(500L * (attempt + 1))
                    attempt++
                } else {
                    throw e
                }
            } catch (e: NetworkErrorException) {
                lastException = e
                if (cancellationToken.isCancelled()) {
                    throw CancellationException("AI request cancelled")
                }
                if (attempt < maxRetries) {
                    delay(500L * (attempt + 1))
                    attempt++
                } else {
                    throw e
                }
            }
        }

        throw (lastException ?: NetworkErrorException("Request failed after retries"))
    }

    private fun executeSingleAttempt(
        client: OkHttpClient,
        adapter: ProtocolAdapter,
        built: BuiltHttpRequest,
        request: UnifiedChatRequest,
        secrets: List<String>,
        cancellationToken: TransportCancellationToken,
        onEvent: (StreamEvent) -> Unit
    ): UnifiedChatResponse {
        val startTime = System.currentTimeMillis()
        val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        val initialOkRequest = Request.Builder()
            .url(built.url)
            .headers(ProtocolAdapterHelper.toOkHttpHeaders(built.headers))
            .post(built.bodyJson.toRequestBody(jsonMediaType))
            .build()

        val response = executeFollowingSafeRedirects(
            client = client,
            initialRequest = initialOkRequest,
            allowCleartextHttp = request.profile.allowCleartextHttp,
            cancellationToken = cancellationToken
        )

        val rawResult: UnifiedChatResponse = response.use { resp ->
            if (!resp.isSuccessful) {
                val rawErr = try {
                    resp.body?.string().orEmpty()
                } catch (_: Exception) {
                    ""
                }
                throw mapHttpError(resp.code, resp.header("Retry-After"), rawErr, secrets)
            }

            val body = resp.body ?: throw NetworkErrorException("Empty response body from provider")
            val contentType = resp.header("Content-Type").orEmpty().lowercase()

            val isChatGptOAuth = built.url.contains("backend-api/codex/responses") ||
                ((request.profile.id.equals("openai", ignoreCase = true) ||
                    request.profile.id.equals("chatgpt", ignoreCase = true) ||
                    request.profile.baseUrl.contains("openai.com") ||
                    request.profile.baseUrl.contains("chatgpt.com")) &&
                    (request.modelId.contains("terra", ignoreCase = true) ||
                        request.modelId.contains("codex", ignoreCase = true) ||
                        request.modelId.startsWith("gpt-5", ignoreCase = true) ||
                        secrets.any { it.startsWith("ey") || it.length > 150 }))

            val shouldParseStream = (request.stream || isChatGptOAuth) && (
                contentType.contains("text/event-stream") ||
                    contentType.contains("application/x-ndjson") ||
                    request.profile.protocol == ApiProtocol.OLLAMA_NATIVE ||
                    isChatGptOAuth
                )

            if (shouldParseStream) {
                val reader = BufferedReader(InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))
                adapter.parseStreamingResponse(
                    reader = reader,
                    modelId = request.modelId,
                    isCancelled = { cancellationToken.isCancelled() },
                    onEvent = onEvent
                )
            } else {
                val rawBody = body.string()
                val trimmed = rawBody.trimStart()
                // Check if server returned SSE lines (data:, event:, or keep-alive comment) without text/event-stream header
                if (trimmed.startsWith("data:") || trimmed.startsWith("event:") || trimmed.startsWith(":")) {
                    val reader = BufferedReader( rawBody.reader() )
                    adapter.parseStreamingResponse(
                        reader = reader,
                        modelId = request.modelId,
                        isCancelled = { cancellationToken.isCancelled() },
                        onEvent = onEvent
                    )
                } else {
                    val parsed = adapter.parseNonStreamingResponse(rawBody, request.modelId)
                    if (request.stream || isChatGptOAuth) {
                        if (parsed.text.isNotEmpty()) {
                            onEvent(StreamEvent.TextDelta(parsed.text))
                        }
                        for (c in parsed.citations) {
                            onEvent(StreamEvent.CitationAdded(c))
                        }
                        for (tc in parsed.toolCalls) {
                            onEvent(StreamEvent.ToolCallCompleted(tc))
                        }
                        onEvent(StreamEvent.Completed(parsed))
                    }
                    parsed
                }
            }
        }

        val durationMs = System.currentTimeMillis() - startTime
        val finalUsage = if (rawResult.usage != null) {
            rawResult.usage.copy(latencyMs = durationMs)
        } else {
            val estPrompt = ConversationCompactor.estimateRequestTokens(request.systemPrompt, request.tools, request.messages)
            val estCompletion = ConversationCompactor.estimateTextTokens(rawResult.text)
            TokenUsage(
                promptTokens = estPrompt,
                completionTokens = estCompletion,
                totalTokens = estPrompt + estCompletion,
                latencyMs = durationMs,
                isEstimated = true
            )
        }
        return rawResult.copy(usage = finalUsage)
    }

    private fun executeFollowingSafeRedirects(
        client: OkHttpClient,
        initialRequest: Request,
        allowCleartextHttp: Boolean,
        cancellationToken: TransportCancellationToken
    ): Response {
        var currentRequest = initialRequest
        var redirectCount = 0
        val maxRedirects = 4

        while (true) {
            if (cancellationToken.isCancelled()) {
                throw CancellationException("AI request cancelled")
            }
            val call = client.newCall(currentRequest)
            cancellationToken.attachCall(call)

            val response = try {
                call.execute()
            } catch (e: IOException) {
                if (cancellationToken.isCancelled() || call.isCanceled()) {
                    throw CancellationException("AI request cancelled")
                }
                throw NetworkErrorException(
                    "Network error connecting to provider: ${SecretRedactor.redact(e.message)}",
                    e
                )
            }

            if (response.code !in listOf(301, 302, 303, 307, 308)) {
                return response
            }

            val location = response.header("Location")
            if (location.isNullOrBlank() || redirectCount >= maxRedirects) {
                return response
            }

            val resolvedUrl = currentRequest.url.resolve(location) ?: return response
            response.close()

            EndpointSecurityValidator.validateEndpointUrl(
                urlString = resolvedUrl.toString(),
                allowCleartextHttp = allowCleartextHttp
            )

            val stripCreds = EndpointSecurityValidator.shouldStripCredentialsOnRedirect(
                fromUrl = currentRequest.url.toString(),
                toUrl = resolvedUrl.toString()
            )

            val newBuilder = currentRequest.newBuilder().url(resolvedUrl)
            if (stripCreds) {
                newBuilder.removeHeader("Authorization")
                newBuilder.removeHeader("x-api-key")
                newBuilder.removeHeader("x-goog-api-key")
            }
            if (response.code == 303) {
                newBuilder.get()
            }
            currentRequest = newBuilder.build()
            redirectCount++
        }
    }

    private data class ParsedErrorInfo(
        val message: String,
        val code: String? = null,
        val type: String? = null
    )

    private fun mapHttpError(
        statusCode: Int,
        retryAfterHeader: String?,
        rawErrorBody: String,
        secrets: List<String>
    ): Exception {
        val errInfo = extractErrorDetail(rawErrorBody)
        val safeDetail = SecretRedactor.redact(errInfo.message, secrets)

        return when (statusCode) {
            401, 403 -> InvalidApiKeyException(
                "Authentication failed (HTTP $statusCode): ${safeDetail.ifBlank { "Invalid or unauthorized API key" }}"
            )
            404 -> UnsupportedEndpointException(
                "Endpoint or model not found (HTTP 404): ${safeDetail.ifBlank { "Verify Base URL, protocol, and Model ID" }}"
            )
            429 -> {
                val retrySec = retryAfterHeader?.trim()?.toLongOrNull()
                RateLimitException(
                    message = "Rate limit exceeded (HTTP 429): ${safeDetail.ifBlank { "Too many requests or quota exhausted" }}",
                    retryAfterSeconds = retrySec
                )
            }
            else -> ProviderRequestException(
                statusCode = statusCode,
                message = "Provider error (HTTP $statusCode): ${safeDetail.ifBlank { "Request failed" }}",
                errorCode = errInfo.code,
                errorType = errInfo.type
            )
        }
    }

    private fun extractErrorDetail(rawErrorBody: String): ParsedErrorInfo {
        val trimmed = rawErrorBody.trim()
        if (trimmed.isEmpty()) return ParsedErrorInfo("")
        return try {
            val obj = JSONObject(trimmed)
            val errAny = obj.opt("error")
            when (errAny) {
                is JSONObject -> {
                    val msg = errAny.optString("message", "")
                    val code = errAny.optString("code", "").takeIf { it.isNotBlank() }
                        ?: obj.optString("code", "").takeIf { it.isNotBlank() }
                        ?: obj.optString("status", "").takeIf { it.isNotBlank() }
                    val type = errAny.optString("type", "").takeIf { it.isNotBlank() }
                        ?: obj.optString("type", "").takeIf { it.isNotBlank() }
                    ParsedErrorInfo(
                        message = if (msg.isNotBlank()) msg else errAny.toString(),
                        code = code,
                        type = type
                    )
                }
                is String -> {
                    ParsedErrorInfo(
                        message = errAny,
                        code = obj.optString("code", "").takeIf { it.isNotBlank() }
                            ?: obj.optString("status", "").takeIf { it.isNotBlank() },
                        type = obj.optString("type", "").takeIf { it.isNotBlank() }
                    )
                }
                else -> {
                    val msg = obj.optString("message", trimmed.take(240))
                    val code = obj.optString("code", "").takeIf { it.isNotBlank() }
                        ?: obj.optString("status", "").takeIf { it.isNotBlank() }
                    val type = obj.optString("type", "").takeIf { it.isNotBlank() }
                    ParsedErrorInfo(message = msg, code = code, type = type)
                }
            }
        } catch (_: Exception) {
            ParsedErrorInfo(trimmed.take(240))
        }
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
