package com.tom.rv2ide.artificial.transport

import com.tom.rv2ide.artificial.exceptions.MalformedApiResponseException
import com.tom.rv2ide.artificial.providers.ApiProtocol
import com.tom.rv2ide.artificial.providers.UrlNormalizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader

class GeminiNativeAdapter : ProtocolAdapter {

    override val supportedProtocols: Set<ApiProtocol> = setOf(ApiProtocol.GEMINI_NATIVE)

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

        val contentsArray = JSONArray()
        var lastModelToolCalls = emptyList<ToolCall>()
        var i = 0
        while (i < request.messages.size) {
            val msg = request.messages[i]
            when (msg.role) {
                ChatRole.SYSTEM -> {
                    if (msg.content.isNotBlank()) {
                        systemParts.add(msg.content.trim())
                    }
                    i++
                }
                ChatRole.USER -> {
                    val parts = JSONArray()
                    if (msg.content.isNotBlank()) {
                        parts.put(JSONObject().put("text", msg.content))
                    }
                    if (!msg.imageBase64.isNullOrBlank()) {
                        val mime = msg.imageMimeType ?: "image/jpeg"
                        parts.put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", mime)
                                put("data", msg.imageBase64)
                            })
                        })
                    }
                    if (parts.length() == 0) {
                        parts.put(JSONObject().put("text", ""))
                    }
                    contentsArray.put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", parts)
                        }
                    )
                    lastModelToolCalls = emptyList()
                    i++
                }
                ChatRole.ASSISTANT -> {
                    val parts = JSONArray()
                    if (msg.content.isNotBlank()) {
                        parts.put(JSONObject().put("text", msg.content))
                    }
                    for (tc in msg.toolCalls) {
                        val fcObj = JSONObject().apply {
                            put("name", tc.name)
                            put("args", tc.parseArgumentsObject())
                            if (tc.id.isNotBlank()) {
                                put("id", tc.id)
                            }
                        }
                        parts.put(JSONObject().put("functionCall", fcObj))
                    }
                    if (parts.length() > 0) {
                        contentsArray.put(
                            JSONObject().apply {
                                put("role", "model")
                                put("parts", parts)
                            }
                        )
                    }
                    lastModelToolCalls = msg.toolCalls
                    i++
                }
                ChatRole.TOOL -> {
                    // Protocol Hardening: Group consecutive function responses into a single user turn
                    // to satisfy Gemini's strict multi-tool turn pairing constraint (HTTP 400 prevention).
                    // Also guarantee 1-to-1 ordered correlation with open function calls and ID matching.
                    val consecutiveToolMsgs = mutableListOf<Pair<Int, UnifiedChatMessage>>()
                    var localIdx = 0
                    while (i < request.messages.size && request.messages[i].role == ChatRole.TOOL) {
                        consecutiveToolMsgs.add(Pair(localIdx++, request.messages[i]))
                        i++
                    }

                    val responseParts = JSONArray()
                    if (lastModelToolCalls.isNotEmpty()) {
                        val matchedIndices = mutableSetOf<Int>()

                        // Order function responses to match the exact order of open function calls
                        for (tc in lastModelToolCalls) {
                            var matchIdx = consecutiveToolMsgs.indexOfFirst {
                                it.first !in matchedIndices && it.second.toolResult?.toolCallId == tc.id
                            }
                            if (matchIdx == -1) {
                                matchIdx = consecutiveToolMsgs.indexOfFirst {
                                    it.first !in matchedIndices &&
                                        (it.second.toolResult?.toolName.equals(tc.name, ignoreCase = true) ||
                                         com.tom.rv2ide.artificial.tools.AgentToolRegistry.canonicalToolName(it.second.toolResult?.toolName ?: "") ==
                                         com.tom.rv2ide.artificial.tools.AgentToolRegistry.canonicalToolName(tc.name))
                                }
                            }

                            val callId = tc.id.takeIf { it.isNotBlank() }

                            if (matchIdx != -1) {
                                matchedIndices.add(matchIdx)
                                val toolMsg = consecutiveToolMsgs[matchIdx].second
                                val tr = toolMsg.toolResult
                                val resultText = tr?.content ?: toolMsg.content
                                responseParts.put(
                                    JSONObject().put(
                                        "functionResponse",
                                        JSONObject().apply {
                                            put("name", tc.name)
                                            if (callId != null) {
                                                put("id", callId)
                                            }
                                            put(
                                                "response",
                                                JSONObject().apply {
                                                    put("result", resultText)
                                                    if (tr?.isError == true) {
                                                        put("error", true)
                                                    }
                                                }
                                            )
                                        }
                                    )
                                )
                            } else {
                                // Missing response for open function call: emit fallback error response so Gemini turn succeeds
                                responseParts.put(
                                    JSONObject().put(
                                        "functionResponse",
                                        JSONObject().apply {
                                            put("name", tc.name)
                                            if (callId != null) {
                                                put("id", callId)
                                            }
                                            put(
                                                "response",
                                                JSONObject().apply {
                                                    put("result", "[Tool call produced no output or was cancelled]")
                                                    put("error", true)
                                                }
                                            )
                                        }
                                    )
                                )
                            }
                        }

                        // Leftover tool messages in this group did NOT match any open function call.
                        // DO NOT emit them as user text! Log diagnostic and safely omit to preserve role fidelity.
                        for ((idx, toolMsg) in consecutiveToolMsgs) {
                            if (idx !in matchedIndices) {
                                safeLogWarning("Omitted unmatched tool result '${toolMsg.toolResult?.toolName}' (callId='${toolMsg.toolResult?.toolCallId}') - no matching open function call in model turn.")
                            }
                        }
                    } else {
                        // Orphaned tool messages without preceding model function calls.
                        // DO NOT emit them as user text! Log diagnostic and safely omit to prevent role corruption.
                        for ((_, toolMsg) in consecutiveToolMsgs) {
                            safeLogWarning("Omitted orphaned tool result '${toolMsg.toolResult?.toolName}' (callId='${toolMsg.toolResult?.toolCallId}') - no preceding model functionCall turn found.")
                        }
                    }

                    if (responseParts.length() > 0) {
                        contentsArray.put(
                            JSONObject().apply {
                                put("role", "user")
                                put("parts", responseParts)
                            }
                        )
                    }
                    lastModelToolCalls = emptyList()
                }
            }
        }

        val body = JSONObject().apply {
            if (systemParts.isNotEmpty()) {
                put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put("text", systemParts.joinToString("\n\n"))
                        )
                    )
                )
            }
            put("contents", contentsArray)

            val genConfig = JSONObject()
            request.temperature?.let { genConfig.put("temperature", it) }
            request.maxTokens?.let { genConfig.put("maxOutputTokens", it) }
            request.reasoningEffort?.let { effort ->
                val thinkingConfig = JSONObject()
                if (effort != com.tom.rv2ide.artificial.models.ReasoningEffort.OFF) {
                    if (effort == com.tom.rv2ide.artificial.models.ReasoningEffort.AUTO) {
                        thinkingConfig.put("thinkingBudget", -1)
                        thinkingConfig.put("includeThoughts", true)
                    } else if (effort.budgetTokens > 0) {
                        thinkingConfig.put("thinkingBudget", effort.budgetTokens)
                        thinkingConfig.put("includeThoughts", true)
                    }
                } else {
                    thinkingConfig.put("thinkingBudget", 0)
                    thinkingConfig.put("includeThoughts", false)
                }
                genConfig.put("thinkingConfig", thinkingConfig)
            }
            if (genConfig.length() > 0) {
                put("generationConfig", genConfig)
            }

            val toolsArr = JSONArray()
            if (request.enableGeminiNativeGrounding) {
                toolsArr.put(JSONObject().put("google_search", JSONObject()))
            } else if (request.tools.isNotEmpty()) {
                val fnDecls = JSONArray()
                for (tool in request.tools) {
                    fnDecls.put(
                        JSONObject().apply {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.toGeminiSchemaObject())
                        }
                    )
                }
                toolsArr.put(JSONObject().put("functionDeclarations", fnDecls))
            }
            if (toolsArr.length() > 0) {
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
            throw MalformedApiResponseException("Invalid JSON response from Gemini Native endpoint: ${e.message}", e)
        }

        val textBuilder = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        val citations = mutableListOf<CitationItem>()
        var finishReason: String? = null

        extractCandidateData(
            root = root,
            textBuilder = textBuilder,
            toolCalls = toolCalls,
            citations = citations,
            onTextDelta = null,
            onCitation = null
        )?.let { finishReason = it }

        val modelVersion = root.optString("modelVersion", modelId).ifBlank { modelId }
        val usage = parseGeminiUsage(root.optJSONObject("usageMetadata"))

        return UnifiedChatResponse(
            text = textBuilder.toString(),
            toolCalls = toolCalls,
            citations = citations,
            finishReason = finishReason,
            modelUsed = modelVersion,
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
        val toolCalls = mutableListOf<ToolCall>()
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

            val mv = obj.optString("modelVersion", "")
            if (mv.isNotBlank()) modelUsed = mv

            if (obj.has("usageMetadata") && !obj.isNull("usageMetadata")) {
                val u = parseGeminiUsage(obj.optJSONObject("usageMetadata"))
                if (u != null) streamUsage = u
            }

            val fr = extractCandidateData(
                root = obj,
                textBuilder = fullText,
                toolCalls = toolCalls,
                citations = citations,
                onTextDelta = { chunk -> onEvent(StreamEvent.TextDelta(chunk)) },
                onCitation = { c -> onEvent(StreamEvent.CitationAdded(c)) }
            )
            if (!fr.isNullOrBlank()) {
                finishReason = fr
            }
            true
        }

        for (tc in toolCalls) {
            onEvent(StreamEvent.ToolCallCompleted(tc))
        }

        val response = UnifiedChatResponse(
            text = fullText.toString(),
            toolCalls = toolCalls,
            citations = citations,
            finishReason = finishReason,
            modelUsed = modelUsed,
            usage = streamUsage
        )
        onEvent(StreamEvent.Completed(response))
        return response
    }

    private fun parseGeminiUsage(obj: JSONObject?): TokenUsage? {
        if (obj == null) return null
        val promptTokens = obj.optInt("promptTokenCount", 0)
        val completionTokens = obj.optInt("candidatesTokenCount", 0)
        val totalTokens = obj.optInt("totalTokenCount", promptTokens + completionTokens)
        val cached = obj.optInt("cachedContentTokenCount", 0).takeIf { it > 0 }
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            cachedPromptTokens = cached,
            isEstimated = false
        )
    }

    private fun extractCandidateData(
        root: JSONObject,
        textBuilder: StringBuilder,
        toolCalls: MutableList<ToolCall>,
        citations: MutableList<CitationItem>,
        onTextDelta: ((String) -> Unit)?,
        onCitation: ((CitationItem) -> Unit)?
    ): String? {
        val candidates = root.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val cand0 = candidates.optJSONObject(0) ?: return null

        val finishReason = cand0.optString("finishReason", "").takeIf { it.isNotBlank() && it != "null" }
        val content = cand0.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.has("text") && !part.isNull("text")) {
                    val chunk = part.optString("text", "")
                    if (chunk.isNotEmpty()) {
                        textBuilder.append(chunk)
                        onTextDelta?.invoke(chunk)
                    }
                }
                val fnCall = part.optJSONObject("functionCall")
                if (fnCall != null) {
                    val name = fnCall.optString("name", "").trim()
                    val args = fnCall.optJSONObject("args") ?: JSONObject()
                    val callId = fnCall.optString("id", "").trim()
                    if (name.isNotEmpty()) {
                        toolCalls.add(
                            ToolCall(
                                id = callId,
                                name = name,
                                argumentsJson = args.toString()
                            )
                        )
                    }
                }
            }
        }

        val groundingMetadata = cand0.optJSONObject("groundingMetadata")
        val groundingChunks = groundingMetadata?.optJSONArray("groundingChunks")
        if (groundingChunks != null) {
            for (i in 0 until groundingChunks.length()) {
                val chunkObj = groundingChunks.optJSONObject(i) ?: continue
                val webObj = chunkObj.optJSONObject("web") ?: continue
                val uri = webObj.optString("uri", "").trim()
                val title = webObj.optString("title", uri).trim().ifBlank { uri }
                if (uri.isNotEmpty() && citations.none { it.url == uri }) {
                    val citation = CitationItem(
                        title = title,
                        url = uri,
                        sourceType = "gemini_grounding"
                    )
                    citations.add(citation)
                    onCitation?.invoke(citation)
                }
            }
        }

        return finishReason
    }

    private fun safeLogWarning(message: String) {
        try {
            android.util.Log.w("GeminiNativeAdapter", message)
        } catch (_: Throwable) {
            System.err.println("[GeminiNativeAdapter] $message")
        }
    }
}
