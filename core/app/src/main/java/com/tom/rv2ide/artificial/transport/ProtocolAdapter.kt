package com.tom.rv2ide.artificial.transport

import com.tom.rv2ide.artificial.providers.ApiProtocol
import com.tom.rv2ide.artificial.providers.AuthStyle
import com.tom.rv2ide.artificial.providers.ProviderProfile
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.BufferedReader

data class BuiltHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val bodyJson: String
)

interface ProtocolAdapter {
    val supportedProtocols: Set<ApiProtocol>

    fun buildHttpRequest(
        request: UnifiedChatRequest,
        apiKey: String?
    ): BuiltHttpRequest

    fun parseNonStreamingResponse(
        responseBody: String,
        modelId: String
    ): UnifiedChatResponse

    fun parseStreamingResponse(
        reader: BufferedReader,
        modelId: String,
        isCancelled: () -> Boolean,
        onEvent: (StreamEvent) -> Unit
    ): UnifiedChatResponse
}

object ProtocolAdapterHelper {

    fun buildCommonHeaders(
        profile: ProviderProfile,
        apiKey: String?,
        acceptSse: Boolean
    ): MutableMap<String, String> {
        val headers = mutableMapOf<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Accept"] = if (acceptSse) "text/event-stream, application/json" else "application/json"

        val cleanKey = apiKey?.trim().orEmpty()
        if (cleanKey.isNotEmpty()) {
            when (profile.authStyle) {
                AuthStyle.BEARER_HEADER -> headers["Authorization"] = "Bearer $cleanKey"
                AuthStyle.X_API_KEY_HEADER -> headers["x-api-key"] = cleanKey
                AuthStyle.X_GOOG_API_KEY_HEADER -> headers["x-goog-api-key"] = cleanKey
                AuthStyle.CUSTOM_HEADER -> {
                    val name = profile.customAuthHeaderName?.trim()?.takeIf { it.isNotEmpty() } ?: "Authorization"
                    val prefix = profile.customAuthPrefix ?: ""
                    headers[name] = "$prefix$cleanKey"
                }
                AuthStyle.QUERY_PARAM_KEY, AuthStyle.NONE -> {
                    // Handled in URL or no auth
                }
            }
        }

        for ((k, v) in profile.customHeaders) {
            if (k.isNotBlank()) {
                headers[k.trim()] = v
            }
        }

        if (profile.protocol == ApiProtocol.OPENROUTER) {
            headers.putIfAbsent("HTTP-Referer", "https://androidcodestudio.com")
            headers.putIfAbsent("X-Title", "Android Code Studio")
        }
        if (profile.protocol == ApiProtocol.ANTHROPIC_MESSAGES) {
            headers.putIfAbsent("anthropic-version", "2023-06-01")
        }
        if (profile.id.equals("grok", ignoreCase = true) || profile.baseUrl.contains("x.ai") || profile.baseUrl.contains("grok.com")) {
            headers.putIfAbsent("X-XAI-Token-Auth", "xai-grok-cli")
            headers.putIfAbsent("x-grok-conv-id", java.util.UUID.randomUUID().toString())
        }
        val isChatGptOAuth = (profile.id.equals("openai", ignoreCase = true) || profile.id.equals("chatgpt", ignoreCase = true) || profile.baseUrl.contains("openai.com") || profile.baseUrl.contains("chatgpt.com")) &&
                (cleanKey.startsWith("ey") || cleanKey.length > 150)
        if (isChatGptOAuth) {
            headers["Authorization"] = "Bearer $cleanKey"
            headers["Content-Type"] = "application/json"
            headers["Accept"] = "text/event-stream"
            headers.remove("x-api-key")
            headers["originator"] = "kelivo"
            headers["version"] = "0.159.0"
            headers["User-Agent"] = "Kelivo"
            headers["OpenAI-Beta"] = "responses=experimental"
            val sessId = java.util.UUID.randomUUID().toString()
            headers["session_id"] = sessId
            headers["conversation_id"] = sessId
            headers["x-client-request-id"] = sessId

            try {
                val parts = cleanKey.split(".")
                if (parts.size >= 2) {
                    val decoded = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING), Charsets.UTF_8)
                    val json = org.json.JSONObject(decoded)
                    val auth = json.optJSONObject("https://api.openai.com/auth")
                    val profileObj = json.optJSONObject("https://api.openai.com/profile")
                    val accId = auth?.optString("chatgpt_account_id")?.takeIf { it.isNotBlank() }
                        ?: profileObj?.optString("chatgpt_account_id")?.takeIf { it.isNotBlank() }
                        ?: json.optString("chatgpt_account_id")?.takeIf { it.isNotBlank() }
                    if (!accId.isNullOrBlank() && !accId.startsWith("auth0|")) {
                        headers["chatgpt-account-id"] = accId
                    }
                    val residency = auth?.optString("chatgpt_data_residency")
                        ?: auth?.optString("chatgpt_compute_residency")
                    if (!residency.isNullOrBlank()) {
                        headers["x-openai-internal-codex-residency"] = residency
                    }
                }
            } catch (_: Exception) {}
        }

        return headers
    }

    fun applyQueryParamAuthIfNeeded(
        url: String,
        profile: ProviderProfile,
        apiKey: String?
    ): String {
        val cleanKey = apiKey?.trim().orEmpty()
        if (profile.authStyle != AuthStyle.QUERY_PARAM_KEY || cleanKey.isEmpty()) {
            return url
        }
        val parsed = url.toHttpUrlOrNull() ?: return url
        return parsed.newBuilder()
            .setQueryParameter("key", cleanKey)
            .build()
            .toString()
    }

    fun toOkHttpHeaders(map: Map<String, String>): Headers {
        val builder = Headers.Builder()
        for ((k, v) in map) {
            builder.add(k, v)
        }
        return builder.build()
    }
}
