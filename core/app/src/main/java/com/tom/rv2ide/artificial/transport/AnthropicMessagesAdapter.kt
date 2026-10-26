package com.tom.rv2ide.artificial.transport

import com.tom.rv2ide.artificial.exceptions.MalformedApiResponseException
import com.tom.rv2ide.artificial.providers.ApiProtocol
import com.tom.rv2ide.artificial.providers.UrlNormalizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader

class AnthropicMessagesAdapter : ProtocolAdapter {

    override val supportedProtocols: Set<ApiProtocol> = setOf(ApiProtocol.ANTHROPIC_MESSAGES)

    override fun buildHttpRequest(
        request: UnifiedChatRequest,
        apiKey: String?
    ): BuiltHttpRequest {
        val rawUrl = UrlNormalizer.resolveChatEndpoint(
            baseUrl = request.profile.baseUrl,
            chatPathOverride = request.profile.chatEndpointPath,
            protocol = request.profile.protocol,
            modelId = request.modelId,
            stream = request.stream
        )
        val url = ProtocolAdapterHelper.applyQueryParamAuthIfNeeded(rawUrl, request.profile, apiKey)
        val headers = ProtocolAdapterHelper.buildCommonHeaders(
            profile = request.profile,
            apiKey = apiKey,
            acceptSse = request.stream
        )

        val systemParts = mutableListOf<String>()
        if (!request.systemPrompt.isNullOrBlank()) {
            systemParts.add(request.systemPrompt.trim())
        }

        val messagesArray = JSONArray()
        var pendingToolResults = JSONArray()

        fun flushPendingToolResults() {
            if (pendingToolResults.length() > 0) {
                messagesArray.put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", pendingToolResults)
                    }
                )
                pendingToolResults = JSONArray()
            }
        }

        for (msg in request.messages) {
            when (msg.role) {
                ChatRole.SYSTEM -> {
                    if (msg.content.isNotBlank()) {
                        systemParts.add(msg.content.trim())
                    }
                }
                ChatRole.USER -> {
                    flushPendingToolResults()
                    if (!msg.imageBase64.isNullOrBlank()) {
                        val mime = msg.imageMimeType ?: "image/jpeg"
                        val contentBlocks = JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "image")
                                put("source", JSONObject().apply {
                                    put("type", "base64")
                                    put("media_type", mime)
                                    put("data", msg.imageBase64)
                                })
                            })
                            if (msg.content.isNotBlank()) {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", msg.content)
                                })
                            }
                        }
                        messagesArray.put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", contentBlocks)
                            }
                        )
                    } else {
                        messagesArray.put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", msg.content)
                            }
                        )
                    }
                }
                ChatRole.ASSISTANT -> {
                    flushPendingToolResults()
                    if (msg.toolCalls.isEmpty()) {
                        messagesArray.put(
                            JSONObject().apply {
                                put("role", "assistant")
                                put("content", msg.content)
                            }
                        )
                    } else {
                        val contentBlocks = JSONArray()
                        if (msg.content.isNotBlank()) {
                            contentBlocks.put(
                                JSONObject().apply {
                                    put("type", "text")
                                    put("text", msg.content)
                                }
                            )
                        }
                        for (tc in msg.toolCalls) {
                            contentBlocks.put(
                                JSONObject().apply {
                                    put("type", "tool_use")
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    put("input", tc.parseArgumentsObject())
                                }
                            )
                        }
                        messagesArray.put(
                            JSONObject().apply {
                                put("role", "assistant")
                                put("content", contentBlocks)
                            }
                        )
                    }
                }
                ChatRole.TOOL -> {
                    val tr = msg.toolResult
                    pendingToolResults.put(
                        JSONObject().apply {
                            put("type", "tool_result")
                            put("tool_use_id", tr?.toolCallId ?: "toolu_0")
                            put("content", tr?.content ?: msg.content)
                            if (tr?.isError == true) {
                                put("is_error", true)
                            }
                        }
                    )
                }
            }
        }
        flushPendingToolResults()

        val body = JSONObject().apply {
            put("model", request.modelId)
            var baseMaxTokens = request.maxTokens ?: 4096
            request.reasoningEffort?.let { effort ->
                if (effort != com.tom.rv2ide.artificial.models.ReasoningEffort.OFF) {
                    val budget = effort.budgetTokens.coerceAtLeast(1024)
                    put("thinking", JSONObject().apply {
                        put("type", "enabled")
                        put("budget_tokens", budget)
                    })
                    baseMaxTokens = baseMaxTokens.coerceAtLeast(budget + 1024)
                }
            }
            put("max_tokens", baseMaxTokens)
            put("messages", messagesArray)
            put("stream", request.stream)
            if (systemParts.isNotEmpty()) {
                put("system", systemParts.joinToString("\n\n"))
            }
            request.temperature?.let { put("temperature", it) }

            if (request.tools.isNotEmpty()) {
                val toolsArr = JSONArray()
                for (tool in request.tools) {
                    toolsArr.put(
                        JSONObject().apply {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", tool.toJsonSchemaObject())
                        }
                    )
                }
                put("tools", toolsArr)
            }
        }

        return BuiltHttpRequest(
            url = url,
            headers = headers,
            bodyJson = body.toString()
        )
    }

    override fun parseNonStreamingResponse(
        responseBody: String,
        modelId: String
    ): UnifiedChatResponse {
        val root = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            throw MalformedApiResponseException("Invalid JSON response from Anthropic endpoint: ${e.message}", e)
        }

        val contentArr = root.optJSONArray("content")
            ?: throw MalformedApiResponseException("Anthropic response missing 'content' array")

        val textBuilder = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()

        for (i in 0 until contentArr.length()) {
            val block = contentArr.optJSONObject(i) ?: continue
            when (block.optString("type", "")) {
                "text" -> {
                    textBuilder.append(block.optString("text", ""))
                }
                "tool_use" -> {
                    val id = block.optString("id", "toolu_$i")
                    val name = block.optString("name", "").trim()
                    val inputObj = block.optJSONObject("input") ?: JSONObject()
                    if (name.isNotEmpty()) {
                        toolCalls.add(
                            ToolCall(
                                id = id,
                                name = name,
                                argumentsJson = inputObj.toString()
                            )
                        )
                    }
                }
            }
        }

        val stopReason = root.optString("stop_reason", "").takeIf { it.isNotBlank() && it != "null" }
        val modelUsed = root.optString("model", modelId).ifBlank { modelId }
        val usage = parseAnthropicUsage(root.optJSONObject("usage"))

        return UnifiedChatResponse(
            text = textBuilder.toString(),
            toolCalls = toolCalls,
            finishReason = stopReason,
            modelUsed = modelUsed,
            usage = usage
        )
    }

    override fun parseStreamingResponse(
        reader: BufferedReader,
        modelId: String,
        isCancelled: () -> Boolean,
        onEvent: (StreamEvent) -> Unit
    ): UnifiedChatResponse {
        val fullText = StringBuilder()
        val toolCallBuilders = linkedMapOf<Int, MutableAnthropicToolUse>()
        var stopReason: String? = null
        var modelUsed: String = modelId
        var inputTokens = 0
        var outputTokens = 0
        var cachedTokens: Int? = null

        SseLineParser.parseSseStream(reader, isCancelled) { frame ->
            val obj = try {
                JSONObject(frame.data)
            } catch (_: Exception) {
                return@parseSseStream true
            }

            val eventType = obj.optString("type", frame.event ?: "")
            when (eventType) {
                "message_start" -> {
                    val msgObj = obj.optJSONObject("message")
                    val m = msgObj?.optString("model", "").orEmpty()
                    if (m.isNotBlank()) modelUsed = m
                    val u = msgObj?.optJSONObject("usage")
                    if (u != null) {
                        inputTokens = u.optInt("input_tokens", 0)
                        val cr = u.optInt("cache_read_input_tokens", 0)
                        if (cr > 0) cachedTokens = cr
                    }
                }
                "content_block_start" -> {
                    val index = obj.optInt("index", 0)
                    val block = obj.optJSONObject("content_block")
                    if (block != null && block.optString("type") == "tool_use") {
                        val id = block.optString("id", "toolu_$index")
                        val name = block.optString("name", "")
                        val initialInput = block.optJSONObject("input")
                        val builder = MutableAnthropicToolUse(id = id, name = name)
                        if (initialInput != null && initialInput.length() > 0) {
                            builder.partialJson.append(initialInput.toString())
                        }
                        toolCallBuilders[index] = builder
                    } else if (block != null && block.optString("type") == "text") {
                        val initialText = block.optString("text", "")
                        if (initialText.isNotEmpty()) {
                            fullText.append(initialText)
                            onEvent(StreamEvent.TextDelta(initialText))
                        }
                    }
                }
                "content_block_delta" -> {
                    val index = obj.optInt("index", 0)
                    val delta = obj.optJSONObject("delta") ?: return@parseSseStream true
                    when (delta.optString("type", "")) {
                        "text_delta" -> {
                            val chunk = delta.optString("text", "")
                            if (chunk.isNotEmpty()) {
                                fullText.append(chunk)
                                onEvent(StreamEvent.TextDelta(chunk))
                            }
                        }
                        "input_json_delta" -> {
                            val partial = delta.optString("partial_json", "")
                            val builder = toolCallBuilders.getOrPut(index) {
                                MutableAnthropicToolUse(id = "toolu_$index", name = "")
                            }
                            builder.partialJson.append(partial)
                        }
                    }
                }
                "message_delta" -> {
                    val delta = obj.optJSONObject("delta")
                    val sr = delta?.optString("stop_reason", "").orEmpty()
                    if (sr.isNotBlank() && sr != "null") {
                        stopReason = sr
                    }
                    val u = obj.optJSONObject("usage")
                    if (u != null) {
                        outputTokens = u.optInt("output_tokens", outputTokens)
                    }
                }
            }
            true
        }

        val completedToolCalls = toolCallBuilders.values.mapNotNull { b ->
            if (b.name.isBlank()) return@mapNotNull null
            val tc = ToolCall(
                id = b.id,
                name = b.name,
                argumentsJson = b.partialJson.toString().trim().ifBlank { "{}" }
            )
            onEvent(StreamEvent.ToolCallCompleted(tc))
            tc
        }

        val streamUsage = if (inputTokens > 0 || outputTokens > 0) {
            TokenUsage(
                promptTokens = inputTokens,
                completionTokens = outputTokens,
                totalTokens = inputTokens + outputTokens,
                cachedPromptTokens = cachedTokens,
                isEstimated = false
            )
        } else null

        val response = UnifiedChatResponse(
            text = fullText.toString(),
            toolCalls = completedToolCalls,
            finishReason = stopReason,
            modelUsed = modelUsed,
            usage = streamUsage
        )
        onEvent(StreamEvent.Completed(response))
        return response
    }

    private fun parseAnthropicUsage(obj: JSONObject?): TokenUsage? {
        if (obj == null) return null
        val promptTokens = obj.optInt("input_tokens", 0)
        val completionTokens = obj.optInt("output_tokens", 0)
        val totalTokens = promptTokens + completionTokens
        val cached = obj.optInt("cache_read_input_tokens", 0).takeIf { it > 0 }
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            cachedPromptTokens = cached,
            isEstimated = false
        )
    }

    private class MutableAnthropicToolUse(
        val id: String,
        val name: String,
        val partialJson: StringBuilder = StringBuilder()
    )
}
