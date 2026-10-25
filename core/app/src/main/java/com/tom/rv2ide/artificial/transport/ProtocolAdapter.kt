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
