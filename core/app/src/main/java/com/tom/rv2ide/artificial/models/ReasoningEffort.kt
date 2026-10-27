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
    }
}
