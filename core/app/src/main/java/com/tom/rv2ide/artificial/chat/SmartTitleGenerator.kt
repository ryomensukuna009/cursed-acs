package com.tom.rv2ide.artificial.chat

import java.util.Locale

/**
 * Intelligent conversation title derivation engine.
 * Strips conversational fillers, greetings, code fences, and punctuation
 * to produce clean, concise 3-5 word conversation titles.
 */
object SmartTitleGenerator {

    private val FILLER_WORDS = setOf(
        "hello", "hi", "hey", "hola", "namaste", "halo",
        "sun", "suno", "bhai", "bro", "sir", "yaar", "buddy",
        "please", "pls", "plz", "can", "could", "would", "you",
        "i", "want", "need", "help", "me", "to", "how", "kya",
        "ye", "dekho", "ek", "ab", "tell", "explain", "karo", "kardo"
    )

    fun generateTitle(firstUserMessage: String): String {
        // Strip code fences, inline backticks, and URLs first
        val withoutCode = firstUserMessage
            .replace(Regex("""```[\s\S]*?```"""), " ")
            .replace(Regex("""`[^`]+`"""), " ")
            .replace(Regex("""https?://\S+"""), " ")

        val lines = withoutCode.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return "New Conversation"

        // Search through lines for the first line with real intent
        for (line in lines) {
            val words = line.split(Regex("""[\s\p{Punct}&&[^\-_]]+""")).filter { it.isNotBlank() }
            if (words.isEmpty()) continue

            // Skip leading greetings/fillers
            val meaningful = mutableListOf<String>()
            var skipping = true
            for (word in words) {
                val lower = word.lowercase(Locale.ROOT)
                if (skipping && (lower in FILLER_WORDS || lower.length <= 1)) {
                    continue
                }
                skipping = false
                meaningful.add(word)
                if (meaningful.size >= 5) break
            }

            if (meaningful.isNotEmpty()) {
                val candidate = meaningful.joinToString(" ")
                val clean = candidate.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                return if (clean.length <= 36) clean else clean.take(33).trimEnd() + "…"
            }
        }

        // If only greetings/fillers were given, e.g. "Hello", keep as "New Conversation"
        // so subsequent messages can derive the real topic
        return "New Conversation"
    }
}
