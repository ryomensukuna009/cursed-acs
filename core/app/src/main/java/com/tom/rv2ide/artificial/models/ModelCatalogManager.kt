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

    /**
     * Looks up capabilities from the catalog with intelligent pattern fallbacks.
     */
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

    fun isVisionSupported(rawModelId: String): Boolean {
        val normId = normalizeId(rawModelId)
        val bareId = if (normId.contains("/")) normId.substringAfterLast("/") else normId
        val catalogInfo = modelCache[normId] ?: modelCache[bareId]
        if (catalogInfo != null) {
            return catalogInfo.supportsVision
        }
        return checkVisionHeuristics(normId)
    }

    fun isReasoningSupported(rawModelId: String): Boolean {
        val normId = normalizeId(rawModelId)
        val bareId = if (normId.contains("/")) normId.substringAfterLast("/") else normId
        val catalogInfo = modelCache[normId] ?: modelCache[bareId]
        if (catalogInfo != null) {
            return catalogInfo.supportsReasoning
        }
        return checkReasoningHeuristics(normId)
    }

    fun isToolCallingSupported(rawModelId: String): Boolean {
        val normId = normalizeId(rawModelId)
        val bareId = if (normId.contains("/")) normId.substringAfterLast("/") else normId
        val catalogInfo = modelCache[normId] ?: modelCache[bareId]
        if (catalogInfo != null) {
            return catalogInfo.supportsTools
        }
        return checkToolHeuristics(normId)
    }

    private fun checkVisionHeuristics(normId: String): Boolean {
        // Exclude strictly text-only embeddings or non-vision
        val nonVision = listOf("embedding", "text-embedding", "bge-", "tts", "whisper", "dall-e", "midjourney")
        if (nonVision.any { normId.contains(it) }) return false

        // Known vision models
        val visionKeywords = listOf(
            "gemini", "gpt-4o", "gpt-4-turbo", "vision", "-vl", "claude-3", "omni",
            "pixtral", "llava", "qwen-vl", "minicpm-v", "phi-3-vision", "internvl",
            "glm-4v", "deepseek-vl", "o1", "o3"
        )
        return visionKeywords.any { normId.contains(it) }
    }

    private fun checkReasoningHeuristics(normId: String): Boolean {
        val reasoningKeywords = listOf(
            "o1", "o3", "r1", "reasoning", "thinking", "deepseek-r1", "qwq", "gemini-2.0-flash-thinking"
        )
        return reasoningKeywords.any { normId.contains(it) }
    }

    private fun checkToolHeuristics(normId: String): Boolean {
        val nonTool = listOf("embedding", "moderation", "tts", "whisper", "dall-e")
        if (nonTool.any { normId.contains(it) }) return false
        return true
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
