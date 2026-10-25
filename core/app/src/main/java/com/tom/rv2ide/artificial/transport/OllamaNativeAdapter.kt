package com.tom.rv2ide.artificial.transport

import com.tom.rv2ide.artificial.exceptions.MalformedApiResponseException
import com.tom.rv2ide.artificial.providers.ApiProtocol
import com.tom.rv2ide.artificial.providers.UrlNormalizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader

class OllamaNativeAdapter : ProtocolAdapter {

    override val supportedProtocols: Set<ApiProtocol> = setOf(ApiProtocol.OLLAMA_NATIVE)

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
            acceptSse = false
        )

        val messagesArray = JSONArray()
        val hasSystemMessage = request.messages.any { it.role == ChatRole.SYSTEM }
        if (!hasSystemMessage && !request.systemPrompt.isNullOrBlank()) {
            messagesArray.put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", request.systemPrompt)
                }
            )
        }

        for (msg in request.messages) {
            when (msg.role) {
                ChatRole.SYSTEM, ChatRole.USER -> {
                    messagesArray.put(
                        JSONObject().apply {
                            put("role", msg.role.wireName)
                            put("content", msg.content)
                        }
                    )
                }
                ChatRole.ASSISTANT -> {
                    val msgObj = JSONObject().apply {
                        put("role", "assistant")
                        put("content", msg.content)
                    }
                    if (msg.toolCalls.isNotEmpty()) {
                        val tcArray = JSONArray()
                        for (tc in msg.toolCalls) {
                            tcArray.put(
                                JSONObject().apply {
                                    put(
                                        "function",
                                        JSONObject().apply {
                                            put("name", tc.name)
                                            put("arguments", tc.parseArgumentsObject())
                                        }
                                    )
                                }
                            )
                        }
                        msgObj.put("tool_calls", tcArray)
                    }
                    messagesArray.put(msgObj)
                }
                ChatRole.TOOL -> {
                    messagesArray.put(
                        JSONObject().apply {
                            put("role", "tool")
                            put("content", msg.toolResult?.content ?: msg.content)
                        }
                    )
                }
            }
        }

        val body = JSONObject().apply {
            put("model", request.modelId)
            put("messages", messagesArray)
            put("stream", request.stream)

            val options = JSONObject()
            request.temperature?.let { options.put("temperature", it) }
            request.maxTokens?.let { options.put("num_predict", it) }
            if (options.length() > 0) {
                put("options", options)
            }

            if (request.tools.isNotEmpty()) {
                val toolsArr = JSONArray()
                for (tool in request.tools) {
                    toolsArr.put(
                        JSONObject().apply {
                            put("type", "function")
                            put(
                                "function",
                                JSONObject().apply {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.toJsonSchemaObject())
                                }
                            )
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
            throw MalformedApiResponseException("Invalid JSON response from Ollama endpoint: ${e.message}", e)
        }

        val modelUsed = root.optString("model", modelId).ifBlank { modelId }
        val message = root.optJSONObject("message")
        if (message != null) {
            val content = message.optString("content", "")
            val toolCalls = parseOllamaToolCalls(message.optJSONArray("tool_calls"))
            return UnifiedChatResponse(
                text = content,
                toolCalls = toolCalls,
                finishReason = root.optString("done_reason", "stop"),
                modelUsed = modelUsed
            )
        }

        if (root.has("response")) {
            return UnifiedChatResponse(
                text = root.optString("response", ""),
                modelUsed = modelUsed
            )
        }

        throw MalformedApiResponseException("Ollama response missing 'message' object")
    }

    override fun parseStreamingResponse(
        reader: BufferedReader,
        modelId: String,
        isCancelled: () -> Boolean,
        onEvent: (StreamEvent) -> Unit
    ): UnifiedChatResponse {
        val fullText = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        var finishReason: String? = null
        var modelUsed: String = modelId

        SseLineParser.parseNdjsonStream(reader, isCancelled) { jsonLine ->
            val cleanLine = if (jsonLine.startsWith("data:")) {
                jsonLine.removePrefix("data:").trim()
            } else {
                jsonLine
            }
            if (cleanLine.isEmpty() || cleanLine == "[DONE]") return@parseNdjsonStream false

            val obj = try {
                JSONObject(cleanLine)
            } catch (_: Exception) {
                return@parseNdjsonStream true
            }

            val m = obj.optString("model", "")
            if (m.isNotBlank()) modelUsed = m

            val message = obj.optJSONObject("message")
            if (message != null) {
                val chunk = message.optString("content", "")
                if (chunk.isNotEmpty()) {
                    fullText.append(chunk)
                    onEvent(StreamEvent.TextDelta(chunk))
                }
                val parsedCalls = parseOllamaToolCalls(message.optJSONArray("tool_calls"))
                for (tc in parsedCalls) {
                    toolCalls.add(tc)
                    onEvent(StreamEvent.ToolCallCompleted(tc))
                }
            } else if (obj.has("response")) {
                val chunk = obj.optString("response", "")
                if (chunk.isNotEmpty()) {
                    fullText.append(chunk)
                    onEvent(StreamEvent.TextDelta(chunk))
                }
            }

            if (obj.optBoolean("done", false)) {
                finishReason = obj.optString("done_reason", "stop")
                return@parseNdjsonStream false
            }
            true
        }

        val response = UnifiedChatResponse(
            text = fullText.toString(),
            toolCalls = toolCalls,
            finishReason = finishReason,
            modelUsed = modelUsed
        )
        onEvent(StreamEvent.Completed(response))
        return response
    }

    private fun parseOllamaToolCalls(tcArray: JSONArray?): List<ToolCall> {
        if (tcArray == null) return emptyList()
        val result = mutableListOf<ToolCall>()
        for (i in 0 until tcArray.length()) {
            val tcObj = tcArray.optJSONObject(i) ?: continue
            val fn = tcObj.optJSONObject("function") ?: continue
            val name = fn.optString("name", "").trim()
            if (name.isEmpty()) continue
            val argsAny = fn.opt("arguments")
            val argsJson = when (argsAny) {
                is JSONObject -> argsAny.toString()
                is String -> argsAny.ifBlank { "{}" }
                else -> "{}"
            }
            val id = tcObj.optString("id", "ollama_call_$i")
            result.add(ToolCall(id = id, name = name, argumentsJson = argsJson))
        }
        return result
    }
}
