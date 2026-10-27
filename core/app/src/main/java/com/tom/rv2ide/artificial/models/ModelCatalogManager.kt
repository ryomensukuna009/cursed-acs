package com.tom.rv2ide.artificial.models

import android.content.Context
import com.tom.rv2ide.artificial.providers.ModelCapabilities
import com.tom.rv2ide.artificial.providers.TriStateCapability
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Rich model capability catalog ported from Kelivo's models_dev.json system.
 * Identifies Vision, Tool Calling, Reasoning (Thinking), and Context Limits
 * across hundreds of first-party and aggregator models.
 */
data class CatalogModelInfo(
    val id: String,
    val name: String,
    val family: String?,
    val supportsVision: Boolean,
    val supportsTools: Boolean,
    val supportsReasoning: Boolean,
    val contextLength: Int?,
    val maxOutputTokens: Int?,
    val reasoningEfforts: List<String> = emptyList()
)

class ModelCatalogManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val modelCache = ConcurrentHashMap<String, CatalogModelInfo>()
    @Volatile private var isLoaded = false
    private val loadLock = Any()

    init {
        ensureLoadedAsync()
    }

    private fun ensureLoadedAsync() {
        if (isLoaded) return
        Thread {
            ensureLoaded()
        }.start()
    }

    private fun ensureLoaded() {
        if (isLoaded) return
        synchronized(loadLock) {
            if (isLoaded) return
            try {
                appContext.assets.open("model_catalog/models_dev.json").use { stream ->
                    val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                    val sb = StringBuilder()
                    var line = reader.readLine()
                    while (line != null) {
                        sb.append(line)
                        line = reader.readLine()
                    }
                    val root = JSONObject(sb.toString())
                    val providers = root.optJSONObject("providers") ?: JSONObject()
                    val keys = providers.keys()
                    while (keys.hasNext()) {
                        val provKey = keys.next()
                        val provObj = providers.optJSONObject(provKey) ?: continue
                        val models = provObj.optJSONObject("models") ?: continue
                        val mKeys = models.keys()
                        while (mKeys.hasNext()) {
                            val mKey = mKeys.next()
                            val mObj = models.optJSONObject(mKey) ?: continue
                            val id = mObj.optString("id", mKey)
                            val name = mObj.optString("name", id)
                            val family = mObj.optString("family").takeIf { it.isNotBlank() }

                            // Input modalities (contains "image" -> supports vision)
                            val inputs = mObj.optJSONArray("in")
                            var hasVision = false
                            if (inputs != null) {
                                for (i in 0 until inputs.length()) {
                                    val mod = inputs.optString(i)
                                    if (mod.equals("image", ignoreCase = true)) {
                                        hasVision = true
                                        break
                                    }
                                }
                            }

                            // Tool calling
                            val toolCalling = mObj.optBoolean("tool", false)

                            // Reasoning / Thinking
                            val reasoning = mObj.optBoolean("rsn", false)

                            // Reasoning options (e.g. effort values)
                            val efforts = mutableListOf<String>()
                            val roArr = mObj.optJSONArray("ro")
                            if (roArr != null) {
                                for (ri in 0 until roArr.length()) {
                                    val roObj = roArr.optJSONObject(ri) ?: continue
                                    val values = roObj.optJSONArray("values")
                                    if (values != null) {
                                        for (vi in 0 until values.length()) {
                                            efforts.add(values.optString(vi))
                                        }
                                    }
                                }
                            }

                            // Limits
                            val limit = mObj.optJSONObject("limit")
                            val ctx = limit?.optInt("context")?.takeIf { it > 0 }
                            val maxOut = limit?.optInt("output")?.takeIf { it > 0 }

                            val info = CatalogModelInfo(
                                id = id,
                                name = name,
                                family = family,
                                supportsVision = hasVision,
                                supportsTools = toolCalling,
                                supportsReasoning = reasoning,
                                contextLength = ctx,
                                maxOutputTokens = maxOut,
                                reasoningEfforts = efforts
                            )

                            val normId = normalizeId(id)
                            modelCache.putIfAbsent(normId, info)

                            // Also index without provider prefix if applicable
                            if (normId.contains("/")) {
                                val bare = normId.substringAfterLast("/")
                                modelCache.putIfAbsent(bare, info)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // If asset loading fails or is delayed, pattern heuristics act as complete fallback
            } finally {
                isLoaded = true
            }
        }
    }

    private fun normalizeId(rawId: String): String {
        return rawId.trim().lowercase(Locale.ROOT).removePrefix("models/")
    }

    fun registerModel(
        providerId: String,
        modelId: String,
        displayName: String,
        supportsVision: Boolean = false,
        supportsReasoning: Boolean = false,
        supportsTools: Boolean = true,
        contextLength: Int = 128000
    ) {
        val normId = normalizeId(modelId)
        modelCache[normId] = CatalogModelInfo(
            id = modelId,
            name = displayName,
            family = providerId,
            supportsVision = supportsVision || checkVisionHeuristics(normId),
            supportsTools = supportsTools || checkToolHeuristics(normId),
            supportsReasoning = supportsReasoning || checkReasoningHeuristics(normId),
            contextLength = contextLength,
            maxOutputTokens = 8192
        )
    }

    /**
     * Looks up capabilities from the catalog with intelligent pattern fallbacks.
     */
    fun inferCapabilities(providerId: String?, rawModelId: String): ModelCapabilities {
        return getCapabilities(rawModelId, providerId)
    }

    fun getCapabilities(rawModelId: String, providerId: String? = null): ModelCapabilities {
        val normId = normalizeId(rawModelId)
        val bareId = if (normId.contains("/")) normId.substringAfterLast("/") else normId

        // Try exact / bare lookup in catalog
        val catalogInfo = modelCache[normId] ?: modelCache[bareId]

        if (catalogInfo != null) {
            return ModelCapabilities(
                contextLength = catalogInfo.contextLength,
                maxOutputTokens = catalogInfo.maxOutputTokens,
                supportsStreaming = TriStateCapability.SUPPORTED,
                supportsToolCalling = if (catalogInfo.supportsTools) TriStateCapability.SUPPORTED else TriStateCapability.UNSUPPORTED,
                supportsThinking = if (catalogInfo.supportsReasoning) TriStateCapability.SUPPORTED else TriStateCapability.UNSUPPORTED,
                supportsSearchGrounding = if (providerId == "gemini" || normId.contains("gemini")) TriStateCapability.SUPPORTED else TriStateCapability.UNKNOWN,
                inputModalities = if (catalogInfo.supportsVision) listOf("text", "image") else listOf("text")
            )
        }

        // Heuristic patterns for unknown / newly released models
        val isVision = checkVisionHeuristics(normId)
        val isReasoning = checkReasoningHeuristics(normId)
        val isTools = checkToolHeuristics(normId)
        val estimatedContext = estimateContextLength(normId)

        return ModelCapabilities(
            contextLength = estimatedContext,
            supportsStreaming = TriStateCapability.SUPPORTED,
            supportsToolCalling = if (isTools) TriStateCapability.SUPPORTED else TriStateCapability.UNKNOWN,
            supportsThinking = if (isReasoning) TriStateCapability.SUPPORTED else TriStateCapability.UNSUPPORTED,
            supportsSearchGrounding = if (providerId == "gemini" || normId.contains("gemini")) TriStateCapability.SUPPORTED else TriStateCapability.UNKNOWN,
            inputModalities = if (isVision) listOf("text", "image") else listOf("text")
        )
    }

    private val KELIVO_VISION_REGEX = Regex(
        """(gpt-4o|gpt-4\.1|gpt-5|gpt-6|o\d|gemini|claude|kimi|grok|muse-spark|doubao|step-3|intern-s1|minimax-m3|mimo-v2|sensenova|vision|-vl|omni|pixtral|llava|minicpm-v|internvl|phi-3-vision|phi-4-multimodal|qwen-vl|qwen2\.5-vl|qwen2-vl)""",
        RegexOption.IGNORE_CASE
    )

    private val KELIVO_REASONING_REGEX = Regex(
        """(gpt-oss|gpt-5|gpt-6|o\d|gemini-(?:2\.5|3|4)|gemini-(?:flash-latest|pro-latest)|gemini-3-pro|gemma[-_]?4|claude|qwen-?3|doubao|grok(?:-[2-4])?|grok-3-mini|kimi|muse-spark|step-3|intern-s1|glm-4|glm-5|minimax-(?:m2|m3)|deepseek-(?:r1|v3|v4|flash)|deepseek-reasoner|mimo-v2|qwq|thinking|reasoner)""",
        RegexOption.IGNORE_CASE
    )

    private val KELIVO_TOOL_REGEX = Regex(
        """(gpt-4o|gpt-4\.1|gpt-oss|gpt-5|gpt-6|o\d|gemini|claude|qwen-?3|doubao|grok|kimi|muse-spark|step-3|intern-s1|glm-4|glm-5|minimax|deepseek|mimo-v2|sensenova)""",
        RegexOption.IGNORE_CASE
    )

    fun isVisionSupported(rawModelId: String): Boolean {
        val normId = normalizeId(rawModelId)
        val bareId = if (normId.contains("/")) normId.substringAfterLast("/") else normId
        val catalogInfo = modelCache[normId] ?: modelCache[bareId]
        if (catalogInfo != null && catalogInfo.supportsVision) {
            return true
        }
        return checkVisionHeuristics(normId)
    }

    fun isReasoningSupported(rawModelId: String): Boolean {
        val normId = normalizeId(rawModelId)
        val bareId = if (normId.contains("/")) normId.substringAfterLast("/") else normId
        val catalogInfo = modelCache[normId] ?: modelCache[bareId]
        if (catalogInfo != null && catalogInfo.supportsReasoning) {
            return true
        }
        return checkReasoningHeuristics(normId)
    }

    fun isToolCallingSupported(rawModelId: String): Boolean {
        val normId = normalizeId(rawModelId)
        val bareId = if (normId.contains("/")) normId.substringAfterLast("/") else normId
        val catalogInfo = modelCache[normId] ?: modelCache[bareId]
        if (catalogInfo != null && catalogInfo.supportsTools) {
            return true
        }
        return checkToolHeuristics(normId)
    }

    private fun checkVisionHeuristics(normId: String): Boolean {
        // Exclude strictly text-only embeddings or non-vision
        val nonVision = listOf("embedding", "text-embedding", "bge-", "tts", "whisper", "dall-e", "midjourney", "rerank", "moderation")
        if (nonVision.any { normId.contains(it) }) return false

        return KELIVO_VISION_REGEX.containsMatchIn(normId)
    }

    private fun checkReasoningHeuristics(normId: String): Boolean {
        val nonReasoning = listOf("embedding", "moderation", "tts", "whisper", "dall-e")
        if (nonReasoning.any { normId.contains(it) }) return false
        return KELIVO_REASONING_REGEX.containsMatchIn(normId)
    }

    private fun checkToolHeuristics(normId: String): Boolean {
        val nonTool = listOf("embedding", "moderation", "tts", "whisper", "dall-e")
        if (nonTool.any { normId.contains(it) }) return false
        return KELIVO_TOOL_REGEX.containsMatchIn(normId) || true
    }

    private fun estimateContextLength(normId: String): Int {
        return when {
            normId.contains("1m") || normId.contains("gemini-1.5") || normId.contains("gemini-2.0") -> 1000000
            normId.contains("200k") || normId.contains("claude-3") -> 200000
            normId.contains("128k") || normId.contains("gpt-4o") || normId.contains("deepseek") || normId.contains("llama-3") -> 128000
            normId.contains("64k") -> 64000
            normId.contains("32k") -> 32000
            else -> 128000
        }
    }

    companion object {
        @Volatile private var instance: ModelCatalogManager? = null

        fun getInstance(context: Context? = null): ModelCatalogManager {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val ctx = context?.applicationContext
                        ?: try { com.tom.rv2ide.app.IDEApplication.instance.applicationContext } catch (_: Exception) { null }
                    if (ctx != null) {
                        ModelCatalogManager(ctx).also { instance = it }
                    } else {
                        throw IllegalStateException("Context required to initialize ModelCatalogManager")
                    }
                }
            }
        }
    }
}
