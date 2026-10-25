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

package com.tom.rv2ide.artificial.providers

/**
 * Normalizes provider base URLs and endpoint paths without duplicating version prefixes
 * such as `/v1`, `/api/v1`, `/v1beta`, or `/v1beta/openai`.
 */
object UrlNormalizer {

    private val VERSION_SUFFIXES = listOf(
        "/v1beta/openai",
        "/api/v1",
        "/v1beta",
        "/v1",
        "/api"
    )

    /**
     * Normalizes a base URL by trimming whitespace and trailing slashes.
     */
    fun normalizeBaseUrl(baseUrl: String): String {
        var cleaned = baseUrl.trim()
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.dropLast(1)
        }
        return cleaned
    }

    /**
     * Resolves [baseUrl] and [endpointPath] into a single normalized URL string,
     * preventing duplicate `/v1/v1` or `//` segments.
     */
    fun resolveEndpoint(baseUrl: String, endpointPath: String): String {
        val trimmedPath = endpointPath.trim()
        if (trimmedPath.startsWith("https://", ignoreCase = true) ||
            trimmedPath.startsWith("http://", ignoreCase = true)
        ) {
            return normalizeBaseUrl(trimmedPath)
        }

        val normBase = normalizeBaseUrl(baseUrl)
        if (trimmedPath.isEmpty()) return normBase

        var normPath = if (trimmedPath.startsWith("/")) trimmedPath else "/$trimmedPath"
        // Collapse multiple consecutive slashes in path
        normPath = normPath.replace(Regex("/{2,}"), "/")

        // Check if normBase already ends with a version suffix that normPath also starts with
        for (suffix in VERSION_SUFFIXES) {
            if (normBase.endsWith(suffix, ignoreCase = true)) {
                if (normPath.equals(suffix, ignoreCase = true)) {
                    return normBase
                }
                if (normPath.startsWith("$suffix/", ignoreCase = true)) {
                    normPath = normPath.substring(suffix.length)
                    break
                }
            }
        }

        // Special case: base ends with /v1beta/openai and path starts with /v1/
        if (normBase.endsWith("/v1beta/openai", ignoreCase = true) &&
            normPath.startsWith("/v1/", ignoreCase = true)
        ) {
            normPath = normPath.removePrefix("/v1").removePrefix("/V1")
        }

        // Special case: base ends with /api/v1 and path starts with /v1/
        if (normBase.endsWith("/api/v1", ignoreCase = true) &&
            normPath.startsWith("/v1/", ignoreCase = true)
        ) {
            normPath = normPath.removePrefix("/v1").removePrefix("/V1")
        }

        return "$normBase$normPath"
    }

    fun resolveModelsEndpoint(
        baseUrl: String,
        modelsPathOverride: String = "",
        protocol: ApiProtocol = ApiProtocol.OPENAI_COMPATIBLE
    ): String {
        val defaultPath = when (protocol) {
            ApiProtocol.OLLAMA_NATIVE -> "/api/tags"
            ApiProtocol.GEMINI_NATIVE -> "/v1beta/models"
            ApiProtocol.ANTHROPIC_MESSAGES -> "/v1/models"
            ApiProtocol.OPENROUTER -> "/api/v1/models"
            ApiProtocol.GEMINI_OPENAI -> "/v1beta/openai/models"
            else -> "/v1/models"
        }
        val path = modelsPathOverride.trim().ifBlank { defaultPath }
        return resolveEndpoint(baseUrl, path)
    }

    fun resolveChatEndpoint(
        baseUrl: String,
        chatPathOverride: String = "",
        protocol: ApiProtocol = ApiProtocol.OPENAI_COMPATIBLE,
        modelId: String = "",
        stream: Boolean = false
    ): String {
        if (protocol == ApiProtocol.GEMINI_NATIVE) {
            val cleanModel = modelId.trim().removePrefix("models/")
            val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
            val rawPath = chatPathOverride.trim()
            val effectivePath = if (rawPath.isBlank() || rawPath.contains("{model}")) {
                "/v1beta/models/$cleanModel:$action"
            } else {
                rawPath
            }
            return resolveEndpoint(baseUrl, effectivePath)
        }

        val defaultPath = when (protocol) {
            ApiProtocol.ANTHROPIC_MESSAGES -> "/v1/messages"
            ApiProtocol.OLLAMA_NATIVE -> "/api/chat"
            ApiProtocol.OPENROUTER -> "/api/v1/chat/completions"
            ApiProtocol.GEMINI_OPENAI -> "/v1beta/openai/chat/completions"
            else -> "/v1/chat/completions"
        }
        val path = chatPathOverride.trim().ifBlank { defaultPath }
        val normBase = normalizeBaseUrl(baseUrl)
        // If baseUrl has no version segment and path is bare "/messages" or "/chat/completions", ensure /v1 is included when appropriate
        if (protocol == ApiProtocol.ANTHROPIC_MESSAGES && !normBase.endsWith("/v1") && path == "/messages") {
            return resolveEndpoint(baseUrl, "/v1/messages")
        }
        return resolveEndpoint(baseUrl, path)
    }
}
