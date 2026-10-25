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

package com.tom.rv2ide.artificial.providers

import org.json.JSONArray
import org.json.JSONObject

/**
 * Supported API protocols for AI providers.
 */
enum class ApiProtocol(val displayName: String) {
    OPENAI_COMPATIBLE("OpenAI-Compatible Chat Completions"),
    OPENROUTER("OpenRouter API"),
    GEMINI_OPENAI("Google Gemini (OpenAI-Compatible)"),
    GEMINI_NATIVE("Google Gemini (Native REST API)"),
    ANTHROPIC_MESSAGES("Anthropic Messages API"),
    OLLAMA_NATIVE("Ollama Native API (/api/chat)"),
    CUSTOM_GENERIC("Custom / Generic JSON API");

    companion object {
        fun fromNameOrDefault(name: String?): ApiProtocol {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: OPENAI_COMPATIBLE
        }
    }
}

/**
 * Authentication header styles for provider requests.
 */
enum class AuthStyle(val displayName: String) {
    BEARER_HEADER("Authorization: Bearer <key>"),
    X_API_KEY_HEADER("x-api-key: <key>"),
    X_GOOG_API_KEY_HEADER("x-goog-api-key: <key>"),
    CUSTOM_HEADER("Custom Header"),
    QUERY_PARAM_KEY("Query Parameter ?key=<key>"),
    NONE("No Authentication (e.g. Local LAN)");

    companion object {
        val BEARER_TOKEN: AuthStyle get() = BEARER_HEADER
        val X_API_KEY: AuthStyle get() = X_API_KEY_HEADER
        val X_GOOG_API_KEY: AuthStyle get() = X_GOOG_API_KEY_HEADER

        fun fromNameOrDefault(name: String?): AuthStyle {
            return when (name?.uppercase()) {
                "BEARER_TOKEN" -> BEARER_HEADER
                "X_API_KEY" -> X_API_KEY_HEADER
                "X_GOOG_API_KEY" -> X_GOOG_API_KEY_HEADER
                else -> entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: BEARER_HEADER
            }
        }
    }
}

/**
 * Tri-state capability flag. Capabilities that are not explicitly reported by the provider
 * or configured by the user remain [UNKNOWN] rather than being guessed from model names.
 */
enum class TriStateCapability {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN;

    companion object {
        fun fromNameOrDefault(name: String?): TriStateCapability {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: UNKNOWN
        }
    }
}

/**
 * Status of the most recent model discovery operation for a provider profile.
 */
enum class DiscoveryStatus {
    NEVER_FETCHED,
    SUCCESS,
    EMPTY,
    UNSUPPORTED_ENDPOINT,
    AUTH_ERROR,
    RATE_LIMITED,
    NETWORK_ERROR,
    PARSE_ERROR;

    companion object {
        val AUTH_FAILED: DiscoveryStatus get() = AUTH_ERROR
        val ENDPOINT_NOT_FOUND: DiscoveryStatus get() = UNSUPPORTED_ENDPOINT

        fun fromNameOrDefault(name: String?): DiscoveryStatus {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: NEVER_FETCHED
        }
    }
}

/**
 * Health and connection verification state of a configured provider profile.
 */
enum class ProviderConnectionStatus(val label: String) {
    VERIFIED("Verified"),
    CONFIGURED_UNTESTED("Configured (Untested)"),
    TEST_FAILED("Test Failed"),
    KEY_REQUIRED("Key Required")
}

/**
 * Normalized model capabilities and metadata returned by a provider's model catalogue.
 * Fields are populated only when supplied by the provider or explicitly configured by the user.
 */
data class ModelCapabilities(
    val contextLength: Int? = null,
    val maxOutputTokens: Int? = null,
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
    val supportsStreaming: TriStateCapability = TriStateCapability.UNKNOWN,
    val supportsToolCalling: TriStateCapability = TriStateCapability.UNKNOWN,
    val supportsSearchGrounding: TriStateCapability = TriStateCapability.UNKNOWN,
    val pricingPromptPerToken: String? = null,
    val pricingCompletionPerToken: String? = null
) {
    constructor(
        contextLength: Int? = null,
        maxOutputTokens: Int? = null,
        supportsTools: TriStateCapability,
        supportsVision: TriStateCapability = TriStateCapability.UNKNOWN
    ) : this(
        contextLength = contextLength,
        maxOutputTokens = maxOutputTokens,
        inputModalities = when (supportsVision) {
            TriStateCapability.SUPPORTED -> listOf("text", "image")
            TriStateCapability.UNSUPPORTED -> listOf("text")
            TriStateCapability.UNKNOWN -> null
        },
        supportsToolCalling = supportsTools
    )

    val supportsTools: TriStateCapability
        get() = supportsToolCalling

    val supportsVision: TriStateCapability
        get() = when {
            inputModalities == null -> TriStateCapability.UNKNOWN
            inputModalities.any { it.equals("image", ignoreCase = true) || it.equals("vision", ignoreCase = true) } ->
                TriStateCapability.SUPPORTED
            else -> TriStateCapability.UNSUPPORTED
        }

    fun toJson(): JSONObject = JSONObject().apply {
        contextLength?.let { put("contextLength", it) }
        maxOutputTokens?.let { put("maxOutputTokens", it) }
        inputModalities?.let { put("inputModalities", JSONArray(it)) }
        outputModalities?.let { put("outputModalities", JSONArray(it)) }
        put("supportsStreaming", supportsStreaming.name)
        put("supportsToolCalling", supportsToolCalling.name)
        put("supportsSearchGrounding", supportsSearchGrounding.name)
        pricingPromptPerToken?.let { put("pricingPromptPerToken", it) }
        pricingCompletionPerToken?.let { put("pricingCompletionPerToken", it) }
    }

    companion object {
        fun fromJson(json: JSONObject?): ModelCapabilities {
            if (json == null) return ModelCapabilities()
            val inMods = json.optJSONArray("inputModalities")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }
            val outMods = json.optJSONArray("outputModalities")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
            }
            return ModelCapabilities(
                contextLength = if (json.has("contextLength")) json.optInt("contextLength") else null,
                maxOutputTokens = if (json.has("maxOutputTokens")) json.optInt("maxOutputTokens") else null,
                inputModalities = inMods,
                outputModalities = outMods,
                supportsStreaming = TriStateCapability.fromNameOrDefault(json.optString("supportsStreaming")),
                supportsToolCalling = TriStateCapability.fromNameOrDefault(json.optString("supportsToolCalling")),
                supportsSearchGrounding = TriStateCapability.fromNameOrDefault(json.optString("supportsSearchGrounding")),
                pricingPromptPerToken = json.optString("pricingPromptPerToken").takeIf { it.isNotBlank() },
                pricingCompletionPerToken = json.optString("pricingCompletionPerToken").takeIf { it.isNotBlank() }
            )
        }
    }
}

/**
 * Normalized internal representation of a discovered or manually entered model.
 */
data class DiscoveredModel(
    val providerId: String = "",
    val id: String,
    val displayName: String = id,
    val description: String? = null,
    val ownedBy: String? = null,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val discoveredAtMs: Long = System.currentTimeMillis(),
    val isManualEntry: Boolean = false
) {
    val pricingPrompt: String?
        get() = capabilities.pricingPromptPerToken

    val pricingCompletion: String?
        get() = capabilities.pricingCompletionPerToken

    fun formatMetadataBadge(): String {
        val parts = mutableListOf<String>()
        capabilities.contextLength?.let { ctx ->
            val formatted = if (ctx >= 1000) "${ctx / 1000}k ctx" else "$ctx ctx"
            parts.add(formatted)
        }
        when (capabilities.supportsToolCalling) {
            TriStateCapability.SUPPORTED -> parts.add("tools")
            TriStateCapability.UNSUPPORTED -> parts.add("no-tools")
            TriStateCapability.UNKNOWN -> {}
        }
        if (capabilities.supportsSearchGrounding == TriStateCapability.SUPPORTED) {
            parts.add("search-grounding")
        }
        if (isManualEntry) {
            parts.add("manual")
        }
        return if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("providerId", providerId)
        put("id", id)
        put("displayName", displayName)
        description?.let { put("description", it) }
        ownedBy?.let { put("ownedBy", it) }
        put("capabilities", capabilities.toJson())
        put("discoveredAtMs", discoveredAtMs)
        put("isManualEntry", isManualEntry)
    }

    companion object {
        fun fromJson(json: JSONObject): DiscoveredModel {
            val id = json.getString("id")
            return DiscoveredModel(
                providerId = json.optString("providerId", ""),
                id = id,
                displayName = json.optString("displayName", id).ifBlank { id },
                description = json.optString("description").takeIf { it.isNotBlank() },
                ownedBy = json.optString("ownedBy").takeIf { it.isNotBlank() },
                capabilities = ModelCapabilities.fromJson(json.optJSONObject("capabilities")),
                discoveredAtMs = json.optLong("discoveredAtMs", 0L),
                isManualEntry = json.optBoolean("isManualEntry", false)
            )
        }
    }
}

/**
 * Complete configuration for an AI Provider Profile.
 * Note: Secret API keys are NEVER stored in [ProviderProfile]; they are stored in `SecureKeyStore`
 * keyed by [id].
 */
data class ProviderProfile(
    val id: String,
    val displayName: String,
    val protocol: ApiProtocol,
    val baseUrl: String,
    val authStyle: AuthStyle = AuthStyle.BEARER_HEADER,
    val customAuthHeaderName: String? = "Authorization",
    val customAuthPrefix: String? = null,
    val apiVersionHeaderName: String? = null,
    val apiVersionHeaderValue: String? = null,
    val customHeaders: Map<String, String> = emptyMap(),
    val modelsEndpointPath: String = "/models",
    val chatEndpointPath: String = "/chat/completions",
    val modelsJsonArrayKey: String = "data",
    val modelIdJsonField: String = "id",
    val enabled: Boolean = true,
    val allowCleartextHttp: Boolean = false,
    val selectedModelId: String = "",
    val manualModelIdOverride: String = "",
    val connectTimeoutSec: Int = 30,
    val readTimeoutSec: Int = 90,
    val timeoutSec: Int = readTimeoutSec,
    val maxRetries: Int = 2,
    val temperature: Double? = 0.7,
    val maxTokens: Int? = 4096,
    val streamingEnabled: Boolean = true,
    val userOverrideToolCalling: TriStateCapability = TriStateCapability.UNKNOWN,
    val userOverrideContextLength: Int? = null,
    val enableNativeGeminiGrounding: Boolean = false,
    val enableGeminiNativeGrounding: Boolean = enableNativeGeminiGrounding,
    val isPresetTemplate: Boolean = false,
    val isBuiltInPreset: Boolean = isPresetTemplate,
    val lastDiscoveryStatus: DiscoveryStatus = DiscoveryStatus.NEVER_FETCHED,
    val lastDiscoveryTimeMs: Long = 0L,
    val lastDiscoveryError: String? = null,
    val lastConnectionTestSuccess: Boolean? = null,
    val lastConnectionTestTimeMs: Long = 0L,
    val lastConnectionTestLatencyMs: Long = 0L,
    val lastConnectionTestMessage: String? = null
) {
    /**
     * Resolves the effective model ID to use for requests (manual override if non-blank, else selectedModelId).
     */
    fun getEffectiveModelId(): String {
        val manual = manualModelIdOverride.trim()
        if (manual.isNotEmpty()) return manual
        return selectedModelId.trim()
    }

    fun effectiveModelId(): String = getEffectiveModelId()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("displayName", displayName)
        put("protocol", protocol.name)
        put("baseUrl", baseUrl)
        put("authStyle", authStyle.name)
        customAuthHeaderName?.let { put("customAuthHeaderName", it) }
        customAuthPrefix?.let { put("customAuthPrefix", it) }
        apiVersionHeaderName?.let { put("apiVersionHeaderName", it) }
        apiVersionHeaderValue?.let { put("apiVersionHeaderValue", it) }
        val headersObj = JSONObject()
        customHeaders.forEach { (k, v) -> headersObj.put(k, v) }
        put("customHeaders", headersObj)
        put("modelsEndpointPath", modelsEndpointPath)
        put("chatEndpointPath", chatEndpointPath)
        put("modelsJsonArrayKey", modelsJsonArrayKey)
        put("modelIdJsonField", modelIdJsonField)
        put("enabled", enabled)
        put("allowCleartextHttp", allowCleartextHttp)
        put("selectedModelId", selectedModelId)
        if (manualModelIdOverride.isNotBlank()) {
            put("manualModelIdOverride", manualModelIdOverride)
        }
        put("connectTimeoutSec", connectTimeoutSec)
        put("readTimeoutSec", timeoutSec)
        put("maxRetries", maxRetries)
        temperature?.let { put("temperature", it) }
        maxTokens?.let { put("maxTokens", it) }
        put("streamingEnabled", streamingEnabled)
        put("userOverrideToolCalling", userOverrideToolCalling.name)
        userOverrideContextLength?.let { put("userOverrideContextLength", it) }
        put("enableNativeGeminiGrounding", enableGeminiNativeGrounding || enableNativeGeminiGrounding)
        put("isPresetTemplate", isPresetTemplate || isBuiltInPreset)
        put("lastDiscoveryStatus", lastDiscoveryStatus.name)
        put("lastDiscoveryTimeMs", lastDiscoveryTimeMs)
        lastDiscoveryError?.let { put("lastDiscoveryError", it) }
        lastConnectionTestSuccess?.let { put("lastConnectionTestSuccess", it) }
        put("lastConnectionTestTimeMs", lastConnectionTestTimeMs)
        put("lastConnectionTestLatencyMs", lastConnectionTestLatencyMs)
        lastConnectionTestMessage?.let { put("lastConnectionTestMessage", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): ProviderProfile {
            val headersMap = mutableMapOf<String, String>()
            json.optJSONObject("customHeaders")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    headersMap[k] = obj.optString(k, "")
                }
            }
            val readTo = json.optInt("readTimeoutSec", 90).coerceIn(5, 600)
            val geminiGrounding = json.optBoolean("enableNativeGeminiGrounding", false) ||
                json.optBoolean("enableGeminiNativeGrounding", false)
            val isPreset = json.optBoolean("isPresetTemplate", false) ||
                json.optBoolean("isBuiltInPreset", false)
            return ProviderProfile(
                id = json.getString("id"),
                displayName = json.optString("displayName", json.getString("id")),
                protocol = ApiProtocol.fromNameOrDefault(json.optString("protocol")),
                baseUrl = json.optString("baseUrl", ""),
                authStyle = AuthStyle.fromNameOrDefault(json.optString("authStyle")),
                customAuthHeaderName = json.optString("customAuthHeaderName", "Authorization"),
                customAuthPrefix = json.optString("customAuthPrefix").takeIf { it.isNotBlank() },
                apiVersionHeaderName = json.optString("apiVersionHeaderName").takeIf { it.isNotBlank() },
                apiVersionHeaderValue = json.optString("apiVersionHeaderValue").takeIf { it.isNotBlank() },
                customHeaders = headersMap,
                modelsEndpointPath = json.optString("modelsEndpointPath", "/models"),
                chatEndpointPath = json.optString("chatEndpointPath", "/chat/completions"),
                modelsJsonArrayKey = json.optString("modelsJsonArrayKey", "data"),
                modelIdJsonField = json.optString("modelIdJsonField", "id"),
                enabled = json.optBoolean("enabled", true),
                allowCleartextHttp = json.optBoolean("allowCleartextHttp", false),
                selectedModelId = json.optString("selectedModelId", ""),
                manualModelIdOverride = json.optString("manualModelIdOverride", ""),
                connectTimeoutSec = json.optInt("connectTimeoutSec", 30).coerceIn(5, 300),
                readTimeoutSec = readTo,
                timeoutSec = readTo,
                maxRetries = json.optInt("maxRetries", 2).coerceIn(0, 5),
                temperature = if (json.has("temperature")) json.optDouble("temperature", 0.7) else null,
                maxTokens = if (json.has("maxTokens")) json.optInt("maxTokens", 4096) else null,
                streamingEnabled = json.optBoolean("streamingEnabled", true),
                userOverrideToolCalling = TriStateCapability.fromNameOrDefault(json.optString("userOverrideToolCalling")),
                userOverrideContextLength = if (json.has("userOverrideContextLength")) json.optInt("userOverrideContextLength") else null,
                enableNativeGeminiGrounding = geminiGrounding,
                enableGeminiNativeGrounding = geminiGrounding,
                isPresetTemplate = isPreset,
                isBuiltInPreset = isPreset,
                lastDiscoveryStatus = DiscoveryStatus.fromNameOrDefault(json.optString("lastDiscoveryStatus")),
                lastDiscoveryTimeMs = json.optLong("lastDiscoveryTimeMs", 0L),
                lastDiscoveryError = json.optString("lastDiscoveryError").takeIf { it.isNotBlank() },
                lastConnectionTestSuccess = if (json.has("lastConnectionTestSuccess")) json.optBoolean("lastConnectionTestSuccess") else null,
                lastConnectionTestTimeMs = json.optLong("lastConnectionTestTimeMs", 0L),
                lastConnectionTestLatencyMs = json.optLong("lastConnectionTestLatencyMs", 0L),
                lastConnectionTestMessage = json.optString("lastConnectionTestMessage").takeIf { it.isNotBlank() }
            )
        }
    }
}

/**
 * Result of a model discovery fetch operation.
 */
data class ModelDiscoveryResult(
    val status: DiscoveryStatus,
    val models: List<DiscoveredModel> = emptyList(),
    val httpStatusCode: Int = 0,
    val latencyMs: Long = 0L,
    val errorMessage: String? = null,
    val pagesFetched: Int = 1
) {
    val isSuccess: Boolean
        get() = status == DiscoveryStatus.SUCCESS || status == DiscoveryStatus.EMPTY
}

/**
 * Result of testing a provider connection.
 */
data class ConnectionTestResult(
    val success: Boolean,
    val httpStatusCode: Int = 200,
    val latencyMs: Long = 0L,
    val message: String,
    val discoveredModelCount: Int = 0
)
