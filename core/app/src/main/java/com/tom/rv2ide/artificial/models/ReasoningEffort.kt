package com.tom.rv2ide.artificial.models

enum class ReasoningEffort(
    val key: String,
    val displayName: String,
    val budgetTokens: Int
) {
    OFF("off", "Off (Fast)", 0),
    LOW("low", "Low (~1k)", 1024),
    MEDIUM("medium", "Medium (~4k)", 4096),
    HIGH("high", "High (~16k)", 16000);

    companion object {
        fun fromKey(key: String?): ReasoningEffort {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: MEDIUM
        }
    }
}
