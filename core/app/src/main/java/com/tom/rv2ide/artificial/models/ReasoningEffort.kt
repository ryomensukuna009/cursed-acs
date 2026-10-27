package com.tom.rv2ide.artificial.models

enum class ReasoningEffort(
    val key: String,
    val displayName: String,
    val budgetTokens: Int
) {
    AUTO("auto", "Auto (Dynamic)", -1),
    OFF("off", "Off (Fast)", 0),
    LOW("low", "Low (~1k)", 1024),
    MEDIUM("medium", "Medium (~4k)", 4096),
    HIGH("high", "High (~16k)", 16384),
    MAXIMUM("max", "Maximum (~32k)", 32768);

    companion object {
        fun fromKey(key: String?): ReasoningEffort {
            if (key.isNullOrBlank()) return AUTO
            return entries.firstOrNull { 
                it.key.equals(key, ignoreCase = true) ||
                (key.equals("xhigh", ignoreCase = true) && it == MAXIMUM) ||
                (key.equals("max", ignoreCase = true) && it == MAXIMUM)
            } ?: AUTO
        }

        /**
         * Detects explicit reasoning effort declared in model names / IDs (e.g. `qwen...:low`,
         * `...-medium`, `...:high`, `...:xhigh`).
         * Returns detected ReasoningEffort, or null if the model name does not declare effort.
         */
        fun detectFromModelId(modelId: String?): ReasoningEffort? {
            if (modelId.isNullOrBlank()) return null
            val lower = modelId.lowercase()
            return when {
                lower.contains(":xhigh") || lower.contains("-xhigh") || lower.endsWith("-xhigh") ||
                lower.contains(":max") || lower.contains("-max") || lower.endsWith("-max") -> MAXIMUM
                lower.contains(":high") || lower.contains("-high") || lower.endsWith("-high") -> HIGH
                lower.contains(":medium") || lower.contains("-medium") || lower.endsWith("-medium") ||
                lower.contains(":med") || lower.contains("-med") || lower.endsWith("-med") -> MEDIUM
                lower.contains(":low") || lower.contains("-low") || lower.endsWith("-low") -> LOW
                lower.contains(":off") || lower.contains("-off") || lower.endsWith("-off") ||
                lower.contains(":none") || lower.contains("-none") || lower.endsWith("-none") -> OFF
                else -> null
            }
        }
    }
}
