package com.tom.rv2ide.artificial.transport

import com.tom.rv2ide.artificial.exceptions.MalformedApiResponseException
import com.tom.rv2ide.artificial.providers.ApiProtocol
import com.tom.rv2ide.artificial.providers.UrlNormalizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader

class OpenAICompatibleAdapter : ProtocolAdapter {

    override val supportedProtocols: Set<ApiProtocol> = setOf(
        ApiProtocol.OPENAI_COMPATIBLE,
        ApiProtocol.OPENROUTER,
        ApiProtocol.GEMINI_OPENAI,
        ApiProtocol.CUSTOM_GENERIC
    )

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
                ChatRole.SYSTEM -> {
                    messagesArray.put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", msg.content)
                        }
                    )
                }
                ChatRole.USER -> {
                    val msgObj = JSONObject().apply {
                        put("role", "user")
                        if (!msg.imageBase64.isNullOrBlank()) {
                            val mime = msg.imageMimeType ?: "image/jpeg"
                            val parts = JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", msg.content)
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", "data:$mime;base64,${msg.imageBase64}")
                                    })
                                })
                            }
                            put("content", parts)
                        } else {
                            put("content", msg.content)
                        }
                    }
                    messagesArray.put(msgObj)
                }
                ChatRole.ASSISTANT -> {
                    val msgObj = JSONObject().apply {
                        put("role", "assistant")
                        if (msg.content.isNotEmpty() || msg.toolCalls.isEmpty()) {
                            put("content", msg.content)
                        } else {
                            put("content", JSONObject.NULL)
                        }
                    }
                    if (msg.toolCalls.isNotEmpty()) {
                        val tcArray = JSONArray()
                        for (tc in msg.toolCalls) {
                            tcArray.put(
                                JSONObject().apply {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put(
                                        "function",
                                        JSONObject().apply {
                                            put("name", tc.name)
                                            put("arguments", tc.argumentsJson.ifBlank { "{}" })
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
                    val tr = msg.toolResult
                    messagesArray.put(
                        JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", tr?.toolCallId ?: "call_0")
                            if (!tr?.toolName.isNullOrBlank()) {
                                put("name", tr?.toolName)
                            }
                            put("content", tr?.content ?: msg.content)
                        }
                    )
                }
            }
        }

        val body = JSONObject().apply {
            put("model", request.modelId)
            put("messages", messagesArray)
            put("stream", request.stream)
            if (request.stream) {
                put("stream_options", JSONObject().apply { put("include_usage", true) })
            }
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_tokens", it) }
            request.reasoningEffort?.let { effort ->
                if (effort != com.tom.rv2ide.artificial.models.ReasoningEffort.OFF) {
                    put("reasoning_effort", effort.key)
                }
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
                put("tool_choice", "auto")
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
            throw MalformedApiResponseException("Invalid JSON response from provider: ${e.message}", e)
        }

        val citations = extractCitations(root)
        val modelUsed = root.optString("model", modelId).ifBlank { modelId }
        val usage = parseOpenAiUsage(root.optJSONObject("usage"))

        val choices = root.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val choice0 = choices.optJSONObject(0) ?: JSONObject()
            val finishReason = choice0.optString("finish_reason", "").takeIf { it.isNotBlank() && it != "null" }
            val message = choice0.optJSONObject("message")
            if (message != null) {
                val content = if (message.isNull("content")) "" else message.optString("content", "")
                val explicitToolCalls = parseToolCallsArray(message.optJSONArray("tool_calls"))
                val toolCalls = explicitToolCalls.ifEmpty { extractInlineToolCalls(content) }
                return UnifiedChatResponse(
                    text = content,
                    toolCalls = toolCalls,
                    citations = citations,
                    finishReason = finishReason,
                    modelUsed = modelUsed,
                    usage = usage
                )
            }
            val legacyText = choice0.optString("text", "")
            return UnifiedChatResponse(
                text = legacyText,
                citations = citations,
                finishReason = finishReason,
                modelUsed = modelUsed,
                usage = usage
            )
        }

        // Custom generic fallback shapes: {"response": "..."} or {"content": "..."} or {"output": "..."}
        val genericText = when {
            root.has("response") && !root.isNull("response") -> root.optString("response", "")
            root.has("content") && !root.isNull("content") -> root.optString("content", "")
            root.has("output") && !root.isNull("output") -> root.optString("output", "")
            else -> throw MalformedApiResponseException("Response JSON missing 'choices' array")
        }

        return UnifiedChatResponse(
            text = genericText,
            citations = citations,
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
        val toolCallBuilders = linkedMapOf<Int, MutableToolCall>()
        val citations = mutableListOf<CitationItem>()
        var finishReason: String? = null
        var modelUsed: String = modelId
        var streamUsage: TokenUsage? = null

        SseLineParser.parseSseStream(reader, isCancelled) { frame ->
            val obj = try {
                JSONObject(frame.data)
            } catch (_: Exception) {
                return@parseSseStream true
            }

            val m = obj.optString("model", "")
            if (m.isNotBlank() && m != "null") {
                modelUsed = m
            }

            if (obj.has("usage") && !obj.isNull("usage")) {
                val u = parseOpenAiUsage(obj.optJSONObject("usage"))
                if (u != null) {
                    streamUsage = u
                }
            }

            val frameCitations = extractCitations(obj)
            for (c in frameCitations) {
                if (citations.none { it.url == c.url }) {
                    citations.add(c)
                    onEvent(StreamEvent.CitationAdded(c))
                }
            }

            val choices = obj.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val choice0 = choices.optJSONObject(0) ?: return@parseSseStream true
                val fr = choice0.optString("finish_reason", "")
                if (fr.isNotBlank() && fr != "null") {
                    finishReason = fr
                }

                val delta = choice0.optJSONObject("delta")
                if (delta != null) {
                    if (delta.has("content") && !delta.isNull("content")) {
                        val chunk = delta.optString("content", "")
                        if (chunk.isNotEmpty()) {
                            fullText.append(chunk)
                            onEvent(StreamEvent.TextDelta(chunk))
                        }
                    }

                    val tcArray = delta.optJSONArray("tool_calls")
                    if (tcArray != null) {
                        for (i in 0 until tcArray.length()) {
                            val tcObj = tcArray.optJSONObject(i) ?: continue
                            val index = tcObj.optInt("index", i)
                            val builder = toolCallBuilders.getOrPut(index) { MutableToolCall() }

                            val id = tcObj.optString("id", "")
                            if (id.isNotBlank() && id != "null") {
                                builder.id = id
                            }
                            val fn = tcObj.optJSONObject("function")
                            if (fn != null) {
                                val name = fn.optString("name", "")
                                if (name.isNotBlank() && name != "null") {
                                    builder.name.append(name)
                                }
                                if (fn.has("arguments") && !fn.isNull("arguments")) {
                                    builder.arguments.append(fn.optString("arguments", ""))
                                }
                            }
                        }
                    }
                } else if (choice0.has("message")) {
                    // Server returned a full non-streaming JSON object on a stream request
                    val msg = choice0.optJSONObject("message")
                    if (msg != null) {
                        val content = if (msg.isNull("content")) "" else msg.optString("content", "")
                        if (content.isNotEmpty()) {
                            fullText.append(content)
                            onEvent(StreamEvent.TextDelta(content))
                        }
                        val tcs = parseToolCallsArray(msg.optJSONArray("tool_calls"))
                        tcs.forEachIndexed { idx, tc ->
                            toolCallBuilders[idx] = MutableToolCall(
                                id = tc.id,
                                name = StringBuilder(tc.name),
                                arguments = StringBuilder(tc.argumentsJson)
                            )
                        }
                    }
                }
            } else if (obj.has("response") && !obj.isNull("response")) {
                val chunk = obj.optString("response", "")
                if (chunk.isNotEmpty()) {
                    fullText.append(chunk)
                    onEvent(StreamEvent.TextDelta(chunk))
                }
            }
            true
        }

        val completedToolCalls = toolCallBuilders.entries.mapIndexed { idx, entry ->
            val b = entry.value
            val tc = ToolCall(
                id = b.id.ifBlank { "call_$idx" },
                name = b.name.toString().trim(),
                argumentsJson = b.arguments.toString().trim().ifBlank { "{}" }
            )
            onEvent(StreamEvent.ToolCallCompleted(tc))
            tc
        }.filter { it.name.isNotEmpty() }

        val effectiveToolCalls = completedToolCalls.ifEmpty {
            val inline = extractInlineToolCalls(fullText.toString())
            inline.forEach { onEvent(StreamEvent.ToolCallCompleted(it)) }
            inline
        }

        val response = UnifiedChatResponse(
            text = fullText.toString(),
            toolCalls = effectiveToolCalls,
            citations = citations,
            finishReason = finishReason,
            modelUsed = modelUsed,
            usage = streamUsage
        )
        onEvent(StreamEvent.Completed(response))
        return response
    }

    private fun parseOpenAiUsage(obj: JSONObject?): TokenUsage? {
        if (obj == null) return null
        val promptTokens = obj.optInt("prompt_tokens", 0)
        val completionTokens = obj.optInt("completion_tokens", 0)
        val totalTokens = obj.optInt("total_tokens", promptTokens + completionTokens)

        val promptDetails = obj.optJSONObject("prompt_tokens_details")
        val cached = promptDetails?.optInt("cached_tokens", 0)?.takeIf { it > 0 }
            ?: obj.optInt("cached_prompt_tokens", 0).takeIf { it > 0 }

        val completionDetails = obj.optJSONObject("completion_tokens_details")
        val reasoning = completionDetails?.optInt("reasoning_tokens", 0)?.takeIf { it > 0 }
            ?: obj.optInt("reasoning_tokens", 0).takeIf { it > 0 }

        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            thinkingTokens = reasoning,
            cachedPromptTokens = cached,
            isEstimated = false
        )
    }

    private fun parseToolCallsArray(tcArray: JSONArray?): List<ToolCall> {
        if (tcArray == null) return emptyList()
        val result = mutableListOf<ToolCall>()
        for (i in 0 until tcArray.length()) {
            val tcObj = tcArray.optJSONObject(i) ?: continue
            val id = tcObj.optString("id", "call_$i")
            val fn = tcObj.optJSONObject("function") ?: continue
            val name = fn.optString("name", "").trim()
            if (name.isEmpty()) continue
            val args = if (fn.isNull("arguments")) "{}" else fn.optString("arguments", "{}")
            result.add(ToolCall(id = id, name = name, argumentsJson = args))
        }
        return result
    }

    private fun extractCitations(root: JSONObject): List<CitationItem> {
        val result = mutableListOf<CitationItem>()
        val citationsArr = root.optJSONArray("citations")
        if (citationsArr != null) {
            for (i in 0 until citationsArr.length()) {
                val item = citationsArr.opt(i)
                when (item) {
                    is String -> if (item.startsWith("http")) {
                        result.add(CitationItem(title = item, url = item, sourceType = "provider_citation"))
                    }
                    is JSONObject -> {
                        val url = item.optString("url", item.optString("uri", ""))
                        val title = item.optString("title", url)
                        if (url.isNotBlank()) {
                            result.add(
                                CitationItem(
                                    title = title.ifBlank { url },
                                    url = url,
                                    snippet = item.optString("snippet", ""),
                                    sourceType = "provider_citation"
                                )
                            )
                        }
                    }
                }
            }
        }
        return result
    }

    private fun extractInlineToolCalls(text: String): List<ToolCall> {
        if (text.isBlank()) return emptyList()
        val list = mutableListOf<ToolCall>()
        var idx = 0

        // Format 1: <tool_call>{"name": "...", "arguments": {...}}</tool_call>
        val tagRegex = Regex("""<tool_call>\s*(\{.*?\})\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
        tagRegex.findAll(text).forEach { m ->
            val jsonStr = m.groupValues[1]
            try {
                val obj = JSONObject(jsonStr)
                val name = obj.optString("name", "").trim()
                if (name.isNotEmpty()) {
                    val args = if (obj.has("arguments")) {
                        val a = obj.get("arguments")
                        if (a is JSONObject) a.toString() else a.toString()
                    } else "{}"
                    list.add(ToolCall(id = "inline_call_${idx++}", name = name, argumentsJson = args))
                }
            } catch (_: Exception) {}
        }

        // Format 2: ```json\n{"name": "...", "arguments": {...}}\n```
        if (list.isEmpty()) {
            val blockRegex = Regex("""```(?:json)?\s*(\{\s*"name"\s*:\s*".*?"\s*,\s*"arguments"\s*:\s*\{.*?\}\s*\})\s*```""", RegexOption.DOT_MATCHES_ALL)
            blockRegex.findAll(text).forEach { m ->
                val jsonStr = m.groupValues[1]
                try {
                    val obj = JSONObject(jsonStr)
                    val name = obj.optString("name", "").trim()
                    if (name.isNotEmpty()) {
                        val args = if (obj.has("arguments")) {
                            val a = obj.get("arguments")
                            if (a is JSONObject) a.toString() else a.toString()
                        } else "{}"
                        list.add(ToolCall(id = "inline_call_${idx++}", name = name, argumentsJson = args))
                    }
                } catch (_: Exception) {}
            }
        }
        return list
    }

    private class MutableToolCall(
        var id: String = "",
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    )
}
