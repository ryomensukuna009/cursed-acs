package com.tom.rv2ide.artificial.tools

import android.content.Context
import android.content.SharedPreferences
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.MalformedApiResponseException
import com.tom.rv2ide.artificial.exceptions.NetworkErrorException
import com.tom.rv2ide.artificial.exceptions.ProviderRequestException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import com.tom.rv2ide.artificial.providers.ConnectionTestResult
import com.tom.rv2ide.artificial.secrets.EndpointSecurityValidator
import com.tom.rv2ide.artificial.secrets.SecretRedactor
import com.tom.rv2ide.artificial.secrets.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class WebSearchManager(
    context: Context? = null,
    private val baseHttpClient: OkHttpClient = defaultHttpClient()
) {

    private val appContext: Context? = context?.applicationContext ?: context
    private val prefs: SharedPreferences? = try {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }

    @Volatile
    private var memoryConfig: WebSearchConfig = WebSearchConfig()

    fun getConfig(): WebSearchConfig {
        val raw = prefs?.getString(KEY_WEB_SEARCH_CONFIG, null)
        if (!raw.isNullOrBlank()) {
            return try {
                WebSearchConfig.fromJson(JSONObject(raw))
            } catch (_: Exception) {
                memoryConfig
            }
        }
        return memoryConfig
    }

    fun saveConfig(config: WebSearchConfig) {
        memoryConfig = config
        prefs?.edit()?.putString(KEY_WEB_SEARCH_CONFIG, config.toJson().toString())?.apply()
    }

    fun setSearchApiKey(providerType: SearchProviderType, apiKey: String) {
        SecureKeyStore.putSearchApiKey(appContext, providerType.id, apiKey.trim())
    }

    fun getSearchApiKey(providerType: SearchProviderType = getConfig().providerType): String? {
        return SecureKeyStore.getSearchApiKey(appContext, providerType.id)
    }

    fun hasSearchApiKey(providerType: SearchProviderType = getConfig().providerType): Boolean {
        return SecureKeyStore.hasSearchApiKey(appContext, providerType.id)
    }

    fun getSearchStatus(
        config: WebSearchConfig = getConfig(),
        geminiNativeGroundingActive: Boolean = false
    ): WebSearchStatus {
        if (!config.enabled) return WebSearchStatus.DISABLED
        if (geminiNativeGroundingActive) return WebSearchStatus.READY

        return when (config.providerType) {
            SearchProviderType.TAVILY, SearchProviderType.BRAVE_SEARCH -> {
                if (hasSearchApiKey(config.providerType)) {
                    WebSearchStatus.READY
                } else {
                    WebSearchStatus.NEEDS_CONFIG
                }
            }
            SearchProviderType.CUSTOM_JSON -> {
                if (config.customEndpointUrl.isNotBlank()) {
                    WebSearchStatus.READY
                } else {
                    WebSearchStatus.NEEDS_CONFIG
                }
            }
        }
    }

    suspend fun search(
        query: String,
        limitOverride: Int? = null,
        configOverride: WebSearchConfig? = null,
        apiKeyOverride: String? = null
    ): WebSearchResponse = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return@withContext WebSearchResponse(
                query = "",
                results = emptyList(),
                providerUsed = (configOverride ?: getConfig()).providerType
            )
        }

        val config = configOverride ?: getConfig()
        val limit = (limitOverride ?: config.resultLimit).coerceIn(1, 10)
        val apiKey = apiKeyOverride ?: getSearchApiKey(config.providerType)
        val secrets = listOfNotNull(apiKey?.takeIf { it.isNotBlank() })

        val timeoutSec = config.timeoutSec.coerceIn(5, 60).toLong()
        val client = baseHttpClient.newBuilder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()

        when (config.providerType) {
            SearchProviderType.TAVILY -> executeTavilySearch(
                client = client,
                query = cleanQuery,
                limit = limit,
                apiKey = apiKey,
                customUrl = config.customEndpointUrl,
                allowCleartext = config.allowCleartextCustomEndpoint,
                secrets = secrets
            )
            SearchProviderType.BRAVE_SEARCH -> executeBraveSearch(
                client = client,
                query = cleanQuery,
                limit = limit,
                apiKey = apiKey,
                customUrl = config.customEndpointUrl,
                allowCleartext = config.allowCleartextCustomEndpoint,
                secrets = secrets
            )
            SearchProviderType.CUSTOM_JSON -> executeCustomJsonSearch(
                client = client,
                query = cleanQuery,
                limit = limit,
                apiKey = apiKey,
                endpointUrl = config.customEndpointUrl,
                allowCleartext = config.allowCleartextCustomEndpoint,
                secrets = secrets
            )
        }
    }

    suspend fun testSearchConnection(
        configOverride: WebSearchConfig? = null,
        apiKeyOverride: String? = null
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val config = configOverride ?: getConfig()
        try {
            val response = search(
                query = "android developer documentation",
                limitOverride = 2,
                configOverride = config.copy(enabled = true),
                apiKeyOverride = apiKeyOverride
            )
            val latency = System.currentTimeMillis() - startMs
            ConnectionTestResult(
                success = true,
                latencyMs = latency,
                message = "Connected to ${config.providerType.displayName} (${response.results.size} results in ${latency}ms)",
                discoveredModelCount = response.results.size
            )
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startMs
            ConnectionTestResult(
                success = false,
                latencyMs = latency,
                message = SecretRedactor.redact(e.message ?: "Web search connection test failed")
            )
        }
    }

    private fun executeTavilySearch(
        client: OkHttpClient,
        query: String,
        limit: Int,
        apiKey: String?,
        customUrl: String,
        allowCleartext: Boolean,
        secrets: List<String>
    ): WebSearchResponse {
        val cleanKey = apiKey?.trim().orEmpty()
        if (cleanKey.isEmpty()) {
            throw InvalidApiKeyException("Tavily Search API key is not configured.")
        }

        val endpoint = customUrl.trim().ifBlank { TAVILY_DEFAULT_ENDPOINT }
        EndpointSecurityValidator.validateEndpointUrl(endpoint, allowCleartext)

        val bodyJson = JSONObject().apply {
            put("query", query)
            put("max_results", limit)
            put("search_depth", "basic")
            put("include_answer", false)
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $cleanKey")
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val rawBody = executeSearchHttp(client, request, secrets)
        val root = try {
            JSONObject(rawBody)
        } catch (e: Exception) {
            throw MalformedApiResponseException("Invalid JSON from Tavily Search: ${e.message}", e)
        }

        val resultsArr = root.optJSONArray("results") ?: JSONArray()
        val items = parseSearchResultsArray(resultsArr, limit, "tavily")
        return WebSearchResponse(
            query = query,
            results = items,
            providerUsed = SearchProviderType.TAVILY
        )
    }

    private fun executeBraveSearch(
        client: OkHttpClient,
        query: String,
        limit: Int,
        apiKey: String?,
        customUrl: String,
        allowCleartext: Boolean,
        secrets: List<String>
    ): WebSearchResponse {
        val cleanKey = apiKey?.trim().orEmpty()
        if (cleanKey.isEmpty()) {
            throw InvalidApiKeyException("Brave Search API key is not configured.")
        }

        val baseEndpoint = customUrl.trim().ifBlank { BRAVE_DEFAULT_ENDPOINT }
        EndpointSecurityValidator.validateEndpointUrl(baseEndpoint, allowCleartext)

        val httpUrl = baseEndpoint.toHttpUrlOrNull()?.newBuilder()
            ?.setQueryParameter("q", query)
            ?.setQueryParameter("count", limit.toString())
            ?.build()
            ?: throw MalformedApiResponseException("Invalid Brave Search endpoint URL")

        val request = Request.Builder()
            .url(httpUrl)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", cleanKey)
            .get()
            .build()

        val rawBody = executeSearchHttp(client, request, secrets)
        val root = try {
            JSONObject(rawBody)
        } catch (e: Exception) {
            throw MalformedApiResponseException("Invalid JSON from Brave Search: ${e.message}", e)
        }

        val resultsArr = root.optJSONObject("web")?.optJSONArray("results")
            ?: root.optJSONArray("results")
            ?: JSONArray()

        val items = parseSearchResultsArray(resultsArr, limit, "brave_search")
        return WebSearchResponse(
            query = query,
            results = items,
            providerUsed = SearchProviderType.BRAVE_SEARCH
        )
    }

    private fun executeCustomJsonSearch(
        client: OkHttpClient,
        query: String,
        limit: Int,
        apiKey: String?,
        endpointUrl: String,
        allowCleartext: Boolean,
        secrets: List<String>
    ): WebSearchResponse {
        val cleanEndpoint = endpointUrl.trim()
        if (cleanEndpoint.isEmpty()) {
            throw MalformedApiResponseException("Custom JSON search endpoint URL is not configured.")
        }

        EndpointSecurityValidator.validateEndpointUrl(cleanEndpoint, allowCleartext)

        val httpUrl = cleanEndpoint.toHttpUrlOrNull()?.newBuilder()
            ?.setQueryParameter("q", query)
            ?.setQueryParameter("limit", limit.toString())
            ?.apply {
                if (cleanEndpoint.toHttpUrlOrNull()?.queryParameter("format") == null) {
                    setQueryParameter("format", "json")
                }
            }
            ?.build()
            ?: throw MalformedApiResponseException("Invalid Custom JSON search endpoint URL")

        val reqBuilder = Request.Builder()
            .url(httpUrl)
            .header("Accept", "application/json")
            .get()

        val cleanKey = apiKey?.trim().orEmpty()
        if (cleanKey.isNotEmpty()) {
            reqBuilder.header("Authorization", "Bearer $cleanKey")
        }

        val rawBody = executeSearchHttp(client, reqBuilder.build(), secrets)
        val trimmed = rawBody.trim()
        val resultsArr = try {
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val root = JSONObject(trimmed)
                root.optJSONArray("results")
                    ?: root.optJSONObject("web")?.optJSONArray("results")
                    ?: root.optJSONArray("organic")
                    ?: root.optJSONArray("items")
                    ?: root.optJSONArray("data")
                    ?: JSONArray()
            }
        } catch (e: Exception) {
            throw MalformedApiResponseException("Invalid JSON from custom search endpoint: ${e.message}", e)
        }

        val items = parseSearchResultsArray(resultsArr, limit, "custom_search")
        return WebSearchResponse(
            query = query,
            results = items,
            providerUsed = SearchProviderType.CUSTOM_JSON
        )
    }

    private fun executeSearchHttp(
        client: OkHttpClient,
        request: Request,
        secrets: List<String>
    ): String {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw NetworkErrorException(
                "Web search network error: ${SecretRedactor.redact(e.message, secrets)}",
                e
            )
        }

        response.use { resp ->
            val bodyStr = try {
                resp.body?.string().orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (!resp.isSuccessful) {
                val safeDetail = SecretRedactor.redact(bodyStr.take(200), secrets)
                when (resp.code) {
                    401, 403 -> throw InvalidApiKeyException("Search API authentication failed (HTTP ${resp.code}): $safeDetail")
                    429 -> throw RateLimitException("Search API rate limit exceeded (HTTP 429): $safeDetail")
                    else -> throw ProviderRequestException(resp.code, "Search API failed (HTTP ${resp.code}): $safeDetail")
                }
            }
            return bodyStr
        }
    }

    private fun parseSearchResultsArray(
        arr: JSONArray,
        limit: Int,
        sourceName: String
    ): List<WebSearchResultItem> {
        val list = mutableListOf<WebSearchResultItem>()
        for (i in 0 until arr.length()) {
            if (list.size >= limit) break
            val obj = arr.optJSONObject(i) ?: continue
            val url = obj.optString("url", obj.optString("link", obj.optString("href", ""))).trim()
            if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                continue
            }
            val title = obj.optString("title", obj.optString("name", url)).trim().ifBlank { url }
            val snippet = obj.optString(
                "content",
                obj.optString(
                    "description",
                    obj.optString("snippet", obj.optString("body", ""))
                )
            ).trim()
            val publishedDate = obj.optString(
                "published_date",
                obj.optString("page_age", obj.optString("age", obj.optString("publishedDate", "")))
            ).trim().takeIf { it.isNotEmpty() && it != "null" }

            list.add(
                WebSearchResultItem(
                    title = title,
                    url = url,
                    snippet = snippet.take(1200),
                    publishedDate = publishedDate,
                    source = sourceName
                )
            )
        }
        return list
    }

    companion object {
        private const val PREFS_NAME = "roxum_acs_web_search_prefs"
        private const val KEY_WEB_SEARCH_CONFIG = "web_search_config_json"

        const val TAVILY_DEFAULT_ENDPOINT = "https://api.tavily.com/search"
        const val BRAVE_DEFAULT_ENDPOINT = "https://api.search.brave.com/res/v1/web/search"

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
