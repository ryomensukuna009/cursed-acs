/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.secrets

/**
 * Utility for masking API keys in the UI and redacting secrets from logs,
 * exception messages, and exported configurations.
 */
object SecretRedactor {

    private val BEARER_REGEX = Regex("""(?i)\b(Bearer\s+)([A-Za-z0-9._\-~+/]+=*)""")
    private val HEADER_SECRET_REGEX = Regex(
        """(?i)\b(x-api-key|x-goog-api-key|x-subscription-token|authorization|api-key)\s*([:=])\s*([^\s"',;}\]]+)"""
    )
    private val QUERY_SECRET_REGEX = Regex(
        """(?i)([?&](?:key|api_key|apikey|token|access_token|secret)=)([^&\s"'\]]+)"""
    )
    private val COMMON_KEY_PREFIX_REGEX = Regex(
        """\b(sk-[A-Za-z0-9_\-]{12,}|AIza[0-9A-Za-z_\-]{20,}|xai-[A-Za-z0-9_\-]{12,}|tvly-[A-Za-z0-9_\-]{10,}|BSA[A-Za-z0-9_\-]{12,})\b"""
    )

    /**
     * Masks an API key for safe display in UI summaries/fields.
     */
    fun maskKey(apiKey: String?): String {
        val trimmed = apiKey?.trim().orEmpty()
        if (trimmed.isEmpty()) return "Not configured"
        return when {
            trimmed.length <= 4 -> "••••"
            trimmed.length <= 8 -> "${trimmed.take(2)}••••${trimmed.takeLast(2)}"
            else -> "${trimmed.take(4)}••••••••${trimmed.takeLast(4)}"
        }
    }

    /**
     * Redacts any known secrets, bearer tokens, secret headers, and secret query parameters from text.
     */
    fun redact(text: String?, knownSecrets: Collection<String> = emptyList()): String {
        if (text.isNullOrEmpty()) return ""
        var sanitized: String = text

        for (secret in knownSecrets) {
            val trimmed = secret.trim()
            if (trimmed.length >= 4 && sanitized.contains(trimmed)) {
                sanitized = sanitized.replace(trimmed, "[REDACTED]")
            }
        }

        sanitized = BEARER_REGEX.replace(sanitized) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }

        sanitized = HEADER_SECRET_REGEX.replace(sanitized) { match ->
            "${match.groupValues[1]}${match.groupValues[2]} [REDACTED]"
        }

        sanitized = QUERY_SECRET_REGEX.replace(sanitized) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }

        sanitized = COMMON_KEY_PREFIX_REGEX.replace(sanitized, "[REDACTED]")

        return sanitized
    }

    /**
     * Redacts a Throwable's message so it is safe to display or log.
     */
    fun safeErrorMessage(error: Throwable, knownSecrets: Collection<String> = emptyList()): String {
        return redact(error.message ?: error.javaClass.simpleName, knownSecrets)
    }
}
