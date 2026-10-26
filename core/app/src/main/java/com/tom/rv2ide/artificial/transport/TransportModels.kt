package com.tom.rv2ide.artificial.transport

import com.tom.rv2ide.artificial.chat.AgentCompletionReport
import com.tom.rv2ide.artificial.providers.ProviderProfile
import org.json.JSONArray
import org.json.JSONObject

enum class ChatRole(val wireName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool")
}

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
) {
    fun parseArgumentsObject(): JSONObject {
        return try {
            if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
        } catch (_: Exception) {
            JSONObject()
        }
    }
}

enum class ToolExecutionStatus {
    SUCCESS,
    EXECUTION_FAILURE,
    AUTHORIZATION_DENIED,
    CANCELLED,
    TIMED_OUT,
    INVALID_ARGUMENTS,
    SKIPPED_POLICY_LIMIT
}

data class ToolResultMessage(
    val toolCallId: String,
    val toolName: String,
    val content: String,
    val isError: Boolean = false,
    val status: ToolExecutionStatus = if (isError) ToolExecutionStatus.EXECUTION_FAILURE else ToolExecutionStatus.SUCCESS,
    val executionStarted: Boolean = true,
    val mutationOccurred: Boolean = false,
    val isRecoverable: Boolean = true,
    val exitCode: Int? = null
)

data class UnifiedChatMessage(
    val role: ChatRole,
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResult: ToolResultMessage? = null,
    val imageBase64: String? = null,
    val imageMimeType: String? = null
)

data class ToolParameterProperty(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String> = emptyList()
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameterProperty>
) {
    fun toJsonSchemaObject(): JSONObject {
        val properties = JSONObject()
        val requiredArr = JSONArray()
        for (param in parameters) {
            val propObj = JSONObject().apply {
                put("type", param.type)
                put("description", param.description)
                if (param.enumValues.isNotEmpty()) {
                    val enumArr = JSONArray()
                    param.enumValues.forEach { enumArr.put(it) }
                    put("enum", enumArr)
                }
            }
            properties.put(param.name, propObj)
            if (param.required) {
                requiredArr.put(param.name)
            }
        }
        return JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            if (requiredArr.length() > 0) {
                put("required", requiredArr)
            }
        }
    }

    /**
     * Converts to Gemini-compliant OpenAPI schema with recursive sanitization.
     * Strict Gemini API rules:
     * 1. Types are uppercase (STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT).
     * 2. No $schema, default, or unsupported keywords.
     * 3. required array must only be included if non-empty (empty required causes HTTP 400).
     * 4. Array parameters must always specify an items schema.
     */
    fun toGeminiSchemaObject(): JSONObject {
        return sanitizeSchemaForGemini(toJsonSchemaObject())
    }

    companion object {
        /**
         * Pure, recursive OpenAPI schema sanitizer for Gemini Native endpoint.
         * Creates a clean deep copy without mutating the input schema.
         *
         * Preserves schema semantics:
         * - Normalizes OpenAPI types to uppercase (STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT).
         * - Preserves title, description, default values, nullable, and format.
         * - Preserves additionalProperties validation constraints (boolean or object schema).
         * - Preserves numeric/string/array constraints (minimum, maximum, minLength, maxLength, pattern, minItems, maxItems).
         * - Preserves array items schema when defined; does NOT silently force STRING if unconstrained.
         * - Recursively handles nested OBJECT properties and ARRAY items.
         * - Strips JSON Schema metamodels ($schema, $id) not supported by OpenAPI 3.0.
         * - Strictly omits empty `required: []` arrays at all hierarchy levels to avoid Gemini HTTP 400.
         */
        fun sanitizeSchemaForGemini(schema: JSONObject): JSONObject {
            val sanitized = JSONObject()

            // 1. Normalize type
            val rawType = schema.optString("type", "")
            val hasProperties = schema.has("properties")
            val hasItems = schema.has("items")

            val geminiType = when (rawType.lowercase()) {
                "string" -> "STRING"
                "integer", "int" -> "INTEGER"
                "number", "float", "double" -> "NUMBER"
                "boolean", "bool" -> "BOOLEAN"
                "array" -> "ARRAY"
                "object" -> "OBJECT"
                else -> {
                    if (hasProperties) "OBJECT"
                    else if (hasItems) "ARRAY"
                    else if (rawType.isNotBlank()) rawType.uppercase()
                    else "OBJECT"
                }
            }
            sanitized.put("type", geminiType)

            // 2. Title & Description
            if (schema.has("title")) {
                val title = schema.optString("title", "").trim()
                if (title.isNotEmpty()) {
                    sanitized.put("title", title)
                }
            }
            val desc = schema.optString("description", "").trim()
            if (desc.isNotEmpty()) {
                sanitized.put("description", desc)
            }

            // 3. Default value (supported in Gemini OpenAPI Schema)
            if (schema.has("default") && !schema.isNull("default")) {
                sanitized.put("default", schema.get("default"))
            }

            // 4. Nullable & Format
            if (schema.has("nullable")) {
                sanitized.put("nullable", schema.optBoolean("nullable"))
            }
            val format = schema.optString("format", "").trim()
            if (format.isNotEmpty()) {
                sanitized.put("format", format)
            }

            // 5. Validation constraints (Numeric, String, Array, Object)
            if (schema.has("minimum")) sanitized.put("minimum", schema.optDouble("minimum"))
            if (schema.has("maximum")) sanitized.put("maximum", schema.optDouble("maximum"))
            if (schema.has("minLength")) sanitized.put("minLength", schema.get("minLength"))
            if (schema.has("maxLength")) sanitized.put("maxLength", schema.get("maxLength"))
            if (schema.has("pattern")) sanitized.put("pattern", schema.optString("pattern"))
            if (schema.has("minItems")) sanitized.put("minItems", schema.get("minItems"))
            if (schema.has("maxItems")) sanitized.put("maxItems", schema.get("maxItems"))
            if (schema.has("minProperties")) sanitized.put("minProperties", schema.get("minProperties"))
            if (schema.has("maxProperties")) sanitized.put("maxProperties", schema.get("maxProperties"))

            // 6. Enum values
            val enumArr = schema.optJSONArray("enum")
            if (enumArr != null && enumArr.length() > 0) {
                val copyEnum = JSONArray()
                for (i in 0 until enumArr.length()) {
                    copyEnum.put(enumArr.opt(i))
                }
                sanitized.put("enum", copyEnum)
            }

            // 7. Additional Properties (preserves validation constraints without weakening semantics)
            if (schema.has("additionalProperties")) {
                val addProp = schema.get("additionalProperties")
                if (addProp is JSONObject) {
                    sanitized.put("additionalProperties", sanitizeSchemaForGemini(addProp))
                } else if (addProp is Boolean) {
                    sanitized.put("additionalProperties", addProp)
                }
            }

            // 8. Properties (recursive for OBJECT)
            val propsObj = schema.optJSONObject("properties")
            if (propsObj != null) {
                val sanitizedProps = JSONObject()
                val keys = propsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val propVal = propsObj.optJSONObject(key)
                    if (propVal != null) {
                        sanitizedProps.put(key, sanitizeSchemaForGemini(propVal))
                    }
                }
                sanitized.put("properties", sanitizedProps)
            }

            // 9. Items (recursive for ARRAY if specified; avoid corrupting untyped arrays)
            if (geminiType == "ARRAY") {
                val itemsObj = schema.optJSONObject("items")
                if (itemsObj != null) {
                    sanitized.put("items", sanitizeSchemaForGemini(itemsObj))
                }
            }

            // 10. Required array (omitted if empty to prevent HTTP 400)
            val reqArr = schema.optJSONArray("required")
            if (reqArr != null && reqArr.length() > 0) {
                val nonBlankReq = JSONArray()
                for (i in 0 until reqArr.length()) {
                    val r = reqArr.optString(i, "").trim()
                    if (r.isNotEmpty()) {
                        nonBlankReq.put(r)
                    }
                }
                if (nonBlankReq.length() > 0) {
                    sanitized.put("required", nonBlankReq)
                }
            }

            return sanitized
        }
    }
}

data class CitationItem(
    val title: String,
    val url: String,
    val snippet: String = "",
    val sourceType: String = "web"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("url", url)
        put("snippet", snippet)
        put("sourceType", sourceType)
    }

    companion object {
        fun fromJson(obj: JSONObject): CitationItem = CitationItem(
            title = obj.optString("title", ""),
            url = obj.optString("url", ""),
            snippet = obj.optString("snippet", ""),
            sourceType = obj.optString("sourceType", "web")
        )
    }
}

data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val thinkingTokens: Int? = null,
    val cachedPromptTokens: Int? = null,
    val latencyMs: Long = 0L,
    val isEstimated: Boolean = false
) {
    operator fun plus(other: TokenUsage): TokenUsage {
        return TokenUsage(
            promptTokens = this.promptTokens + other.promptTokens,
            completionTokens = this.completionTokens + other.completionTokens,
            totalTokens = this.totalTokens + other.totalTokens,
            thinkingTokens = if (this.thinkingTokens != null || other.thinkingTokens != null) {
                (this.thinkingTokens ?: 0) + (other.thinkingTokens ?: 0)
            } else null,
            cachedPromptTokens = if (this.cachedPromptTokens != null || other.cachedPromptTokens != null) {
                (this.cachedPromptTokens ?: 0) + (other.cachedPromptTokens ?: 0)
            } else null,
            latencyMs = this.latencyMs + other.latencyMs,
            isEstimated = this.isEstimated || other.isEstimated
        )
    }
}

data class UnifiedChatRequest(
    val profile: ProviderProfile,
    val modelId: String,
    val messages: List<UnifiedChatMessage>,
    val systemPrompt: String? = null,
    val tools: List<ToolDefinition> = emptyList(),
    val enableGeminiNativeGrounding: Boolean = false,
    val stream: Boolean = true,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val reasoningEffort: com.tom.rv2ide.artificial.models.ReasoningEffort? = null
)

data class UnifiedChatResponse(
    val text: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val citations: List<CitationItem> = emptyList(),
    val finishReason: String? = null,
    val modelUsed: String? = null,
    val modifiedFiles: List<String> = emptyList(),
    val rollbackSessionId: String? = null,
    val usage: TokenUsage? = null,
    val completionReport: AgentCompletionReport? = null
)

sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()
    data class ToolCallCompleted(val toolCall: ToolCall) : StreamEvent()
    data class CitationAdded(val citation: CitationItem) : StreamEvent()
    data class StatusUpdate(val status: String) : StreamEvent()
    data class Completed(val response: UnifiedChatResponse) : StreamEvent()
    data class Error(val exception: Throwable) : StreamEvent()
}
