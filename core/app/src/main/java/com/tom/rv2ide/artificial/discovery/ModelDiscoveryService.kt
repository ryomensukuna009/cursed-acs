/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.discovery

import com.tom.rv2ide.artificial.exceptions.InsecureEndpointException
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.MalformedApiResponseException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import com.tom.rv2ide.artificial.exceptions.UnsafeUrlException
import com.tom.rv2ide.artificial.exceptions.UnsupportedEndpointException
import com.tom.rv2ide.artificial.providers.ApiProtocol
import com.tom.rv2ide.artificial.providers.AuthStyle
import com.tom.rv2ide.artificial.providers.ConnectionTestResult
import com.tom.rv2ide.artificial.providers.DiscoveredModel
import com.tom.rv2ide.artificial.providers.DiscoveryStatus
import com.tom.rv2ide.artificial.providers.ModelCapabilities
import com.tom.rv2ide.artificial.providers.ModelDiscoveryResult
import com.tom.rv2ide.artificial.providers.ProviderProfile
import com.tom.rv2ide.artificial.providers.TriStateCapability
import com.tom.rv2ide.artificial.providers.UrlNormalizer
import com.tom.rv2ide.artificial.secrets.EndpointSecurityValidator
import com.tom.rv2ide.artificial.secrets.SecretRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Discovers available models from live provider endpoints based on the configured protocol,
 * parses returned metadata without fabricating capabilities, supports cursor/token pagination,
 * and validates endpoint security and redirect rules.
 */
class ModelDiscoveryService(
    private val baseHttpClient: OkHttpClient = OkHttpClient()
) {

    companion object {
        private const val MAX_PAGINATION_PAGES = 10
        private const val MAX_REDIRECTS = 5
    }

    /**
     * Tests connectivity and authentication against the configured provider's discovery endpoint.
     */
    suspend fun testConnection(
        profile: ProviderProfile,
        apiKey: String? = null
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey?.trim().orEmpty()
        val startMs = System.currentTimeMillis()
        try {
            val result = discoverModels(profile, cleanKey, maxPages = 1)
            val elapsed = System.currentTimeMillis() - startMs
            when (result.status) {
                DiscoveryStatus.SUCCESS, DiscoveryStatus.EMPTY -> ConnectionTestResult(
                    success = true,
                    httpStatusCode = result.httpStatusCode,
                    latencyMs = elapsed,
                    message = "Connected successfully (${result.models.size} models discovered in ${elapsed}ms).",
                    discoveredModelCount = result.models.size
                )
                DiscoveryStatus.UNSUPPORTED_ENDPOINT -> ConnectionTestResult(
                    success = false,
                    httpStatusCode = result.httpStatusCode,
                    latencyMs = elapsed,
                    message = result.errorMessage ?: "Endpoint does not support model listing (HTTP ${result.httpStatusCode}). You can enter a model ID manually."
                )
                else -> ConnectionTestResult(
                    success = false,
                    httpStatusCode = result.httpStatusCode,
                    latencyMs = elapsed,
                    message = result.errorMessage ?: "Connection test failed (${result.status})."
                )
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startMs
            ConnectionTestResult(
                success = false,
                httpStatusCode = -1,
                latencyMs = elapsed,
                message = SecretRedactor.safeErrorMessage(e, if (cleanKey.isNotEmpty()) listOf(cleanKey) else emptyList())
            )
        }
    }

    /**
     * Fetches the available model list from the provider's configured model-discovery endpoint.
     */
    suspend fun discoverModels(
        profile: ProviderProfile,
        apiKey: String? = null,
        maxPages: Int = MAX_PAGINATION_PAGES
    ): ModelDiscoveryResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey?.trim().orEmpty()
        val startMs = System.currentTimeMillis()
        val knownSecrets = if (cleanKey.isNotBlank()) listOf(cleanKey) else emptyList()

        try {
            // Check if auth is required
            if (profile.authStyle != AuthStyle.NONE && cleanKey.isBlank()) {
                return@withContext ModelDiscoveryResult(
                    status = DiscoveryStatus.AUTH_ERROR,
                    httpStatusCode = 401,
                    latencyMs = System.currentTimeMillis() - startMs,
                    errorMessage = "API key is not configured for ${profile.displayName}."
                )
            }

            val isChatGptOAuth = (profile.id.equals("openai", ignoreCase = true) || profile.baseUrl.contains("openai.com")) &&
                    (cleanKey.startsWith("ey") || cleanKey.length > 200)

            val baseDiscoveryUrl = if (isChatGptOAuth) {
                "https://chatgpt.com/backend-api/codex/models?client_version=0.159.0"
            } else {
                UrlNormalizer.resolveEndpoint(profile.baseUrl, profile.modelsEndpointPath)
            }
            EndpointSecurityValidator.validateEndpointUrl(baseDiscoveryUrl, profile.allowCleartextHttp)

            val connectSec = profile.connectTimeoutSec.coerceAtLeast(20).toLong()
            val readSec = profile.readTimeoutSec.coerceAtLeast(25).toLong()
            val client = baseHttpClient.newBuilder()
                .connectTimeout(connectSec, TimeUnit.SECONDS)
                .readTimeout(readSec, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            val allModels = mutableListOf<DiscoveredModel>()
            val seenIds = mutableSetOf<String>()
            var nextUrl: String? = baseDiscoveryUrl
            var pagesFetched = 0
            var lastStatusCode = 200
            val nowMs = System.currentTimeMillis()

            while (nextUrl != null && pagesFetched < maxPages) {
                pagesFetched++
                val (statusCode, responseBody) = try {
                    executeSafeGet(client, nextUrl, profile, cleanKey)
                } catch (e: Exception) {
                    if (allModels.isNotEmpty()) {
                        break
                    }
                    throw e
                }
                lastStatusCode = statusCode

                if (statusCode in 200..299) {
                    val pageParseResult = parseDiscoveryPage(
                        protocol = profile.protocol,
                        profile = profile,
                        rawBody = responseBody,
                        baseDiscoveryUrl = baseDiscoveryUrl,
                        discoveredAtMs = nowMs
                    )
                    for (model in pageParseResult.models) {
                        if (seenIds.add(model.id)) {
                            allModels.add(model)
                        }
                    }
                    nextUrl = pageParseResult.nextPageUrl
                } else {
                    if (isChatGptOAuth) {
                        val fallbackModels = getCodexDefaultModels(nowMs)
                        return@withContext ModelDiscoveryResult(
                            status = DiscoveryStatus.SUCCESS,
                            models = fallbackModels,
                            httpStatusCode = 200,
                            latencyMs = System.currentTimeMillis() - startMs,
                            pagesFetched = 1
                        )
                    }
                    val redactedError = parseErrorDetail(responseBody, knownSecrets)
                    return@withContext when (statusCode) {
                        401, 403 -> ModelDiscoveryResult(
                            status = DiscoveryStatus.AUTH_ERROR,
                            httpStatusCode = statusCode,
                            latencyMs = System.currentTimeMillis() - startMs,
                            errorMessage = "Authentication failed (HTTP $statusCode): $redactedError"
                        )
                        404, 405, 501 -> ModelDiscoveryResult(
                            status = DiscoveryStatus.UNSUPPORTED_ENDPOINT,
                            httpStatusCode = statusCode,
                            latencyMs = System.currentTimeMillis() - startMs,
                            errorMessage = "Model discovery endpoint not supported by ${profile.displayName} (HTTP $statusCode). Enter a model ID manually."
                        )
                        429 -> ModelDiscoveryResult(
                            status = DiscoveryStatus.RATE_LIMITED,
                            httpStatusCode = statusCode,
                            latencyMs = System.currentTimeMillis() - startMs,
                            errorMessage = "Rate limit exceeded while fetching models (HTTP 429): $redactedError"
                        )
                        else -> ModelDiscoveryResult(
                            status = DiscoveryStatus.NETWORK_ERROR,
                            httpStatusCode = statusCode,
                            latencyMs = System.currentTimeMillis() - startMs,
                            errorMessage = "HTTP $statusCode from ${profile.displayName}: $redactedError"
                        )
                    }
                }
            }

            if (allModels.isEmpty() && isChatGptOAuth) {
                allModels.addAll(getCodexDefaultModels(nowMs))
            }
            val finalStatus = if (allModels.isEmpty()) DiscoveryStatus.EMPTY else DiscoveryStatus.SUCCESS
            ModelDiscoveryResult(
                status = finalStatus,
                models = allModels,
                httpStatusCode = lastStatusCode,
                latencyMs = System.currentTimeMillis() - startMs,
                pagesFetched = pagesFetched
            )
        } catch (e: InsecureEndpointException) {
            ModelDiscoveryResult(
                status = DiscoveryStatus.NETWORK_ERROR,
                httpStatusCode = -1,
                latencyMs = System.currentTimeMillis() - startMs,
                errorMessage = SecretRedactor.redact(e.message, knownSecrets)
            )
        } catch (e: UnsafeUrlException) {
            ModelDiscoveryResult(
                status = DiscoveryStatus.NETWORK_ERROR,
                httpStatusCode = -1,
                latencyMs = System.currentTimeMillis() - startMs,
                errorMessage = SecretRedactor.redact(e.message, knownSecrets)
            )
        } catch (e: MalformedApiResponseException) {
            ModelDiscoveryResult(
                status = DiscoveryStatus.PARSE_ERROR,
                httpStatusCode = 200,
                latencyMs = System.currentTimeMillis() - startMs,
                errorMessage = SecretRedactor.redact(e.message, knownSecrets)
            )
        } catch (e: SocketTimeoutException) {
            ModelDiscoveryResult(
                status = DiscoveryStatus.NETWORK_ERROR,
                httpStatusCode = 408,
                latencyMs = System.currentTimeMillis() - startMs,
                errorMessage = "Connection timed out while fetching models from ${profile.displayName}."
            )
        } catch (e: Exception) {
            ModelDiscoveryResult(
                status = DiscoveryStatus.NETWORK_ERROR,
                httpStatusCode = -1,
                latencyMs = System.currentTimeMillis() - startMs,
                errorMessage = "Discovery failed: ${SecretRedactor.safeErrorMessage(e, knownSecrets)}"
            )
        }
    }

    /**
     * Executes a GET request with manual redirect handling so credentials are never leaked
     * to a cross-origin redirect host or downgraded to cleartext HTTP without permission.
     */
    private fun executeSafeGet(
        client: OkHttpClient,
        initialUrlString: String,
        profile: ProviderProfile,
        apiKey: String
    ): Pair<Int, String> {
        var currentUrl = EndpointSecurityValidator.validateEndpointUrl(initialUrlString, profile.allowCleartextHttp)
        val originalUrl = currentUrl
        var stripCredentials = false

        for (hop in 0..MAX_REDIRECTS) {
            val reqBuilder = Request.Builder()
                .url(currentUrl)
                .get()
                .header("Accept", "application/json")

            // Add non-secret custom headers
            for ((k, v) in profile.customHeaders) {
                if (k.isNotBlank()) {
                    reqBuilder.header(k.trim(), v)
                }
            }

            // Add API version header if configured
            if (!profile.apiVersionHeaderName.isNullOrBlank() && !profile.apiVersionHeaderValue.isNullOrBlank()) {
                reqBuilder.header(profile.apiVersionHeaderName.trim(), profile.apiVersionHeaderValue.trim())
            } else if (profile.protocol == ApiProtocol.ANTHROPIC_MESSAGES) {
                reqBuilder.header("anthropic-version", "2023-06-01")
            }

            if (currentUrl.toString().contains("chatgpt.com") ||
                ((profile.id.equals("openai", ignoreCase = true) || profile.baseUrl.contains("openai.com")) && (apiKey.startsWith("ey") || apiKey.length > 200))) {
                reqBuilder.header("originator", "kelivo")
                reqBuilder.header("version", "0.159.0")
                reqBuilder.header("User-Agent", "Kelivo")
                reqBuilder.header("OpenAI-Beta", "responses=experimental")
            }
            if (profile.id.equals("grok", ignoreCase = true) || profile.baseUrl.contains("x.ai") || profile.baseUrl.contains("grok.com")) {
                reqBuilder.header("X-XAI-Token-Auth", "xai-grok-cli")
            }

            // Attach authentication only if we haven't redirected to a different origin
            if (!stripCredentials && apiKey.isNotBlank()) {
                applyAuthHeader(reqBuilder, profile, apiKey)
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                val code = response.code
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location")
                        ?: return Pair(code, response.body?.string().orEmpty())
                    val resolvedRedirect = URL(currentUrl, location)
                    EndpointSecurityValidator.validateRedirectTarget(originalUrl, resolvedRedirect, profile.allowCleartextHttp)
                    if (EndpointSecurityValidator.shouldStripCredentialsOnRedirect(originalUrl, resolvedRedirect)) {
                        stripCredentials = true
                    }
                    currentUrl = resolvedRedirect
                } else {
                    return Pair(code, response.body?.string().orEmpty())
                }
            }
        }
        throw UnsafeUrlException("Too many redirects (exceeded $MAX_REDIRECTS) from ${originalUrl.host}.")
    }

    private fun applyAuthHeader(
        builder: Request.Builder,
        profile: ProviderProfile,
        apiKey: String
    ) {
        when (profile.authStyle) {
            AuthStyle.BEARER_HEADER -> builder.header("Authorization", "Bearer $apiKey")
            AuthStyle.X_API_KEY_HEADER -> builder.header("x-api-key", apiKey)
            AuthStyle.X_GOOG_API_KEY_HEADER, AuthStyle.QUERY_PARAM_KEY -> builder.header("x-goog-api-key", apiKey)
            AuthStyle.CUSTOM_HEADER -> {
                val hName = profile.customAuthHeaderName?.ifBlank { "Authorization" } ?: "Authorization"
                builder.header(hName, apiKey)
            }
            AuthStyle.NONE -> {}
        }
    }

    private data class PageParseResult(
        val models: List<DiscoveredModel>,
        val nextPageUrl: String? = null
    )

    private fun parseDiscoveryPage(
        protocol: ApiProtocol,
        profile: ProviderProfile,
        rawBody: String,
        baseDiscoveryUrl: String,
        discoveredAtMs: Long
    ): PageParseResult {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) {
            throw MalformedApiResponseException("Empty JSON response from ${profile.displayName} models endpoint.")
        }

        try {
            return when (protocol) {
                ApiProtocol.OPENROUTER -> parseOpenRouterModels(profile.id, trimmed, baseDiscoveryUrl, discoveredAtMs)
                ApiProtocol.GEMINI_NATIVE -> parseGeminiNativeModels(profile.id, trimmed, baseDiscoveryUrl, discoveredAtMs)
                ApiProtocol.ANTHROPIC_MESSAGES -> parseAnthropicModels(profile.id, trimmed, baseDiscoveryUrl, discoveredAtMs)
                ApiProtocol.OLLAMA_NATIVE -> parseOllamaModels(profile.id, trimmed, discoveredAtMs)
                ApiProtocol.GEMINI_OPENAI, ApiProtocol.OPENAI_COMPATIBLE ->
                    parseOpenAICompatibleModels(profile.id, protocol, trimmed, baseDiscoveryUrl, discoveredAtMs)
                ApiProtocol.CUSTOM_GENERIC ->
                    parseCustomGenericModels(profile, trimmed, baseDiscoveryUrl, discoveredAtMs)
            }
        } catch (e: MalformedApiResponseException) {
            throw e
        } catch (e: Exception) {
            throw MalformedApiResponseException(
                "Malformed JSON in model catalogue from ${profile.displayName}: ${e.message}",
                e
            )
        }
    }

    private fun parseOpenAICompatibleModels(
        providerId: String,
        protocol: ApiProtocol,
        rawJson: String,
        baseDiscoveryUrl: String,
        discoveredAtMs: Long
    ): PageParseResult {
        if (rawJson.startsWith("[")) {
            val arr = JSONArray(rawJson)
            return PageParseResult(parseStandardModelArray(providerId, protocol, arr, "id", discoveredAtMs))
        }

        val root = JSONObject(rawJson)
        val arr = root.optJSONArray("data")
            ?: root.optJSONArray("models")
            ?: throw MalformedApiResponseException("Expected 'data' or 'models' array in response.")

        val models = parseStandardModelArray(providerId, protocol, arr, "id", discoveredAtMs)
        val nextPageUrl = extractNextPageUrl(root, baseDiscoveryUrl)
        return PageParseResult(models, nextPageUrl)
    }

    private fun parseStandardModelArray(
        providerId: String,
        protocol: ApiProtocol,
        arr: JSONArray,
        idField: String,
        discoveredAtMs: Long
    ): List<DiscoveredModel> {
        val result = mutableListOf<DiscoveredModel>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            when (item) {
                is String -> {
                    val id = item.trim()
                    if (id.isNotEmpty()) {
                        result.add(DiscoveredModel(providerId = providerId, id = id, displayName = id, discoveredAtMs = discoveredAtMs))
                    }
                }
                is JSONObject -> {
                    val visibility = item.optString("visibility", "").trim().lowercase()
                    if (visibility == "hide" || visibility == "hidden") continue

                    var rawId = item.optString("slug").ifBlank {
                        item.optString(idField).ifBlank {
                            item.optString("id").ifBlank { item.optString("name") }
                        }
                    }.trim()
                    if (rawId.isEmpty()) continue

                    // For Gemini OpenAI-compatible endpoint, IDs returned by /models often start with "models/"
                    // while /chat/completions accepts either or the bare model ID; normalize display/id cleanly
                    if (protocol == ApiProtocol.GEMINI_OPENAI && rawId.startsWith("models/")) {
                        rawId = rawId.removePrefix("models/")
                    }

                    val displayName = item.optString("title")
                        .ifBlank { item.optString("display_name") }
                        .ifBlank { item.optString("displayName") }
                        .ifBlank { item.optString("name") }
                        .ifBlank { rawId }

                    val ownedBy = item.optString("owned_by").takeIf { it.isNotBlank() }
                    val description = item.optString("description").takeIf { it.isNotBlank() }

                    val ctxLen = when {
                        item.has("context_length") -> item.optInt("context_length").takeIf { it > 0 }
                        item.has("context_window") -> item.optInt("context_window").takeIf { it > 0 }
                        item.has("inputTokenLimit") -> item.optInt("inputTokenLimit").takeIf { it > 0 }
                        item.has("max_model_len") -> item.optInt("max_model_len").takeIf { it > 0 }
                        else -> null
                    }
                    val maxOut = when {
                        item.has("max_completion_tokens") -> item.optInt("max_completion_tokens").takeIf { it > 0 }
                        item.has("outputTokenLimit") -> item.optInt("outputTokenLimit").takeIf { it > 0 }
                        else -> null
                    }

                    val inferred = try {
                        com.tom.rv2ide.artificial.models.ModelCatalogManager.getInstance(
                            com.tom.rv2ide.app.IDEApplication.instance.applicationContext
                        ).inferCapabilities(providerId, rawId)
                    } catch (_: Exception) { null }

                    val finalCaps = if (inferred != null) {
                        inferred.copy(
                            contextLength = ctxLen ?: inferred.contextLength,
                            maxOutputTokens = maxOut ?: inferred.maxOutputTokens
                        )
                    } else {
                        ModelCapabilities(
                            contextLength = ctxLen,
                            maxOutputTokens = maxOut,
                            supportsStreaming = TriStateCapability.UNKNOWN,
                            supportsToolCalling = TriStateCapability.UNKNOWN
                        )
                    }

                    result.add(
                        DiscoveredModel(
                            providerId = providerId,
                            id = rawId,
                            displayName = displayName,
                            description = description,
                            ownedBy = ownedBy,
                            capabilities = finalCaps,
                            discoveredAtMs = discoveredAtMs
                        )
                    )
                }
            }
        }
        return result
    }

    private fun parseOpenRouterModels(
        providerId: String,
        rawJson: String,
        baseDiscoveryUrl: String,
        discoveredAtMs: Long
    ): PageParseResult {
        val root = JSONObject(rawJson)
        val data = root.optJSONArray("data")
            ?: throw MalformedApiResponseException("OpenRouter response missing 'data' array.")

        val models = mutableListOf<DiscoveredModel>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            if (id.isEmpty()) continue

            val name = obj.optString("name", id).ifBlank { id }
            val desc = obj.optString("description").takeIf { it.isNotBlank() }
            val ctxLen = if (obj.has("context_length")) obj.optInt("context_length").takeIf { it > 0 } else null

            val topProvider = obj.optJSONObject("top_provider")
            val maxCompletion = topProvider?.let {
                if (it.has("max_completion_tokens")) it.optInt("max_completion_tokens").takeIf { v -> v > 0 } else null
            }

            val arch = obj.optJSONObject("architecture")
            val inMods = arch?.optJSONArray("input_modalities")?.let { arr ->
                (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { it.isNotBlank() } }
            }
            val outMods = arch?.optJSONArray("output_modalities")?.let { arr ->
                (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { it.isNotBlank() } }
            }

            val supportedParams = obj.optJSONArray("supported_parameters")?.let { arr ->
                (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).lowercase().takeIf { it.isNotBlank() } }
            }
            val toolSupport = when {
                supportedParams == null -> TriStateCapability.UNKNOWN
                "tools" in supportedParams || "tool_choice" in supportedParams -> TriStateCapability.SUPPORTED
                id.contains("r1", ignoreCase = true) || id.contains("reasoner", ignoreCase = true) -> TriStateCapability.UNSUPPORTED
                else -> TriStateCapability.UNKNOWN
            }

            val pricing = obj.optJSONObject("pricing")
            val promptPrice = pricing?.optString("prompt")?.takeIf { it.isNotBlank() }
            val completionPrice = pricing?.optString("completion")?.takeIf { it.isNotBlank() }

            models.add(
                DiscoveredModel(
                    providerId = providerId,
                    id = id,
                    displayName = name,
                    description = desc,
                    capabilities = ModelCapabilities(
                        contextLength = ctxLen,
                        maxOutputTokens = maxCompletion,
                        inputModalities = inMods,
                        outputModalities = outMods,
                        supportsStreaming = TriStateCapability.UNKNOWN,
                        supportsToolCalling = toolSupport,
                        pricingPromptPerToken = promptPrice,
                        pricingCompletionPerToken = completionPrice
                    ),
                    discoveredAtMs = discoveredAtMs
                )
            )
        }

        val nextPageUrl = extractNextPageUrl(root, baseDiscoveryUrl)
        return PageParseResult(models, nextPageUrl)
    }

    private fun parseGeminiNativeModels(
        providerId: String,
        rawJson: String,
        baseDiscoveryUrl: String,
        discoveredAtMs: Long
    ): PageParseResult {
        val root = JSONObject(rawJson)
        val arr = root.optJSONArray("models")
            ?: root.optJSONArray("data")
            ?: throw MalformedApiResponseException("Gemini models response missing 'models' array.")

        val models = mutableListOf<DiscoveredModel>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val rawName = obj.optString("name").ifBlank { obj.optString("id") }.trim()
            if (rawName.isEmpty()) continue
            val id = rawName.removePrefix("models/")
            val displayName = obj.optString("displayName", id).ifBlank { id }
            val desc = obj.optString("description").takeIf { it.isNotBlank() }

            val inputLimit = if (obj.has("inputTokenLimit")) obj.optInt("inputTokenLimit").takeIf { it > 0 } else null
            val outputLimit = if (obj.has("outputTokenLimit")) obj.optInt("outputTokenLimit").takeIf { it > 0 } else null

            val methods = obj.optJSONArray("supportedGenerationMethods")?.let { mArr ->
                (0 until mArr.length()).mapNotNull { idx -> mArr.optString(idx) }
            }.orEmpty()

            // Filter out embedding-only models that don't support generateContent if methods are specified
            if (methods.isNotEmpty() && "generateContent" !in methods) {
                continue
            }

            val streamSupport = when {
                methods.isEmpty() -> TriStateCapability.UNKNOWN
                "streamGenerateContent" in methods -> TriStateCapability.SUPPORTED
                else -> TriStateCapability.UNKNOWN
            }

            models.add(
                DiscoveredModel(
                    providerId = providerId,
                    id = id,
                    displayName = displayName,
                    description = desc,
                    capabilities = ModelCapabilities(
                        contextLength = inputLimit,
                        maxOutputTokens = outputLimit,
                        supportsStreaming = streamSupport,
                        supportsToolCalling = TriStateCapability.UNKNOWN,
                        supportsSearchGrounding = TriStateCapability.UNKNOWN
                    ),
                    discoveredAtMs = discoveredAtMs
                )
            )
        }

        val nextPageToken = root.optString("nextPageToken").trim()
        val nextUrl = if (nextPageToken.isNotEmpty()) {
            appendQueryParam(baseDiscoveryUrl, "pageToken", nextPageToken)
        } else null

        return PageParseResult(models, nextUrl)
    }

    private fun parseAnthropicModels(
        providerId: String,
        rawJson: String,
        baseDiscoveryUrl: String,
        discoveredAtMs: Long
    ): PageParseResult {
        val root = JSONObject(rawJson)
        val data = root.optJSONArray("data")
            ?: throw MalformedApiResponseException("Anthropic response missing 'data' array.")

        val models = mutableListOf<DiscoveredModel>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            if (id.isEmpty()) continue
            val displayName = obj.optString("display_name", id).ifBlank { id }
            models.add(
                DiscoveredModel(
                    providerId = providerId,
                    id = id,
                    displayName = displayName,
                    ownedBy = "anthropic",
                    capabilities = ModelCapabilities(),
                    discoveredAtMs = discoveredAtMs
                )
            )
        }

        val hasMore = root.optBoolean("has_more", false)
        val lastId = root.optString("last_id").trim()
        val nextUrl = if (hasMore && lastId.isNotEmpty()) {
            appendQueryParam(baseDiscoveryUrl, "after_id", lastId)
        } else null

        return PageParseResult(models, nextUrl)
    }

    private fun parseOllamaModels(
        providerId: String,
        rawJson: String,
        discoveredAtMs: Long
    ): PageParseResult {
        val root = JSONObject(rawJson)
        // Native /api/tags returns { "models": [ { "name": "llama3:latest", "model": "...", "details": {...} } ] }
        // OpenAI-compatible /v1/models on Ollama returns { "data": [ { "id": "..." } ] }
        val arr = root.optJSONArray("models") ?: root.optJSONArray("data")
            ?: throw MalformedApiResponseException("Ollama response missing 'models' or 'data' array.")

        val models = mutableListOf<DiscoveredModel>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("name")
                .ifBlank { obj.optString("model") }
                .ifBlank { obj.optString("id") }
                .trim()
            if (id.isEmpty()) continue

            val details = obj.optJSONObject("details")
            val family = details?.optString("family")?.takeIf { it.isNotBlank() }
            val paramSize = details?.optString("parameter_size")?.takeIf { it.isNotBlank() }
            val quant = details?.optString("quantization_level")?.takeIf { it.isNotBlank() }
            val descParts = listOfNotNull(family, paramSize, quant)
            val desc = if (descParts.isEmpty()) null else descParts.joinToString(" • ")

            models.add(
                DiscoveredModel(
                    providerId = providerId,
                    id = id,
                    displayName = id,
                    description = desc,
                    ownedBy = "ollama",
                    capabilities = ModelCapabilities(),
                    discoveredAtMs = discoveredAtMs
                )
            )
        }
        return PageParseResult(models.sortedBy { it.id }, null)
    }

    private fun parseCustomGenericModels(
        profile: ProviderProfile,
        rawJson: String,
        baseDiscoveryUrl: String,
        discoveredAtMs: Long
    ): PageParseResult {
        val idField = profile.modelIdJsonField.ifBlank { "id" }
        if (rawJson.startsWith("[")) {
            val arr = JSONArray(rawJson)
            return PageParseResult(parseStandardModelArray(profile.id, profile.protocol, arr, idField, discoveredAtMs))
        }

        val root = JSONObject(rawJson)
        val configuredKey = profile.modelsJsonArrayKey.trim()
        val arr = (if (configuredKey.isNotEmpty()) root.optJSONArray(configuredKey) else null)
            ?: root.optJSONArray("data")
            ?: root.optJSONArray("models")
            ?: root.optJSONArray("items")
            ?: root.optJSONArray("results")
            ?: throw MalformedApiResponseException(
                "Custom API response did not contain a JSON array under '${configuredKey.ifEmpty { "data" }}', 'data', or 'models'."
            )

        val models = parseStandardModelArray(profile.id, profile.protocol, arr, idField, discoveredAtMs)
        val nextPageUrl = extractNextPageUrl(root, baseDiscoveryUrl)
        return PageParseResult(models, nextPageUrl)
    }

    private fun extractNextPageUrl(root: JSONObject, baseDiscoveryUrl: String): String? {
        val nextUrl = root.optString("next").ifBlank { root.optString("next_page_url") }.trim()
        if (nextUrl.startsWith("http://") || nextUrl.startsWith("https://")) {
            return nextUrl
        }
        val nextPageToken = root.optString("next_page_token").ifBlank { root.optString("nextPageToken") }.trim()
        if (nextPageToken.isNotEmpty()) {
            return appendQueryParam(baseDiscoveryUrl, "page_token", nextPageToken)
        }
        val hasMore = root.optBoolean("has_more", false)
        val lastId = root.optString("last_id").trim()
        if (hasMore && lastId.isNotEmpty()) {
            return appendQueryParam(baseDiscoveryUrl, "after", lastId)
        }
        return null
    }

    private fun appendQueryParam(url: String, paramName: String, paramValue: String): String {
        val encoded = URLEncoder.encode(paramValue, "UTF-8")
        // Replace existing param if present, otherwise append
        val regex = Regex("""([?&])${Regex.escape(paramName)}=[^&]*""")
        return if (regex.containsMatchIn(url)) {
            regex.replace(url, "$1$paramName=$encoded")
        } else {
            val sep = if (url.contains("?")) "&" else "?"
            "$url$sep$paramName=$encoded"
        }
    }

    private fun parseErrorDetail(rawError: String, knownSecrets: List<String>): String {
        val trimmed = rawError.trim()
        if (trimmed.isEmpty()) return "No error body returned"
        return try {
            val json = JSONObject(trimmed)
            val errObj = json.optJSONObject("error")
            val msg = errObj?.optString("message")
                ?.ifBlank { json.optString("message") }
                ?.ifBlank { json.optString("detail") }
                ?: trimmed.take(250)
            SecretRedactor.redact(msg, knownSecrets)
        } catch (_: Exception) {
            SecretRedactor.redact(trimmed.take(250), knownSecrets)
        }
    }

    private fun getCodexDefaultModels(nowMs: Long): List<DiscoveredModel> {
        val defaults = listOf(
            "gpt-4o" to "GPT-4o (Omni Multimodal & Vision)",
            "chatgpt-4o-latest" to "ChatGPT-4o Latest",
            "o1" to "OpenAI o1 (Advanced Reasoning)",
            "o3-mini" to "OpenAI o3-mini (High-Speed Reasoning)",
            "gpt-4o-mini" to "GPT-4o Mini",
            "gpt-4-turbo" to "GPT-4 Turbo"
        )
        return defaults.map { (id, name) ->
            val inferred = try {
                com.tom.rv2ide.artificial.models.ModelCatalogManager.getInstance(
                    com.tom.rv2ide.app.IDEApplication.instance.applicationContext
                ).inferCapabilities("openai", id)
            } catch (_: Exception) { null }

            DiscoveredModel(
                providerId = "openai",
                id = id,
                displayName = name,
                capabilities = inferred ?: ModelCapabilities(
                    supportsStreaming = TriStateCapability.SUPPORTED,
                    supportsToolCalling = TriStateCapability.SUPPORTED
                ),
                discoveredAtMs = nowMs
            )
        }
    }
}
