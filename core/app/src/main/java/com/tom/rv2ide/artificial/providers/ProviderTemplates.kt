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
 * Editable setup templates for built-in and custom AI providers.
 * Users can modify every field of these profiles or create additional custom profiles.
 */
object ProviderTemplates {

    fun defaultOpenAI(): ProviderProfile = ProviderProfile(
        id = "openai",
        displayName = "OpenAI",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://api.openai.com/v1",
        authStyle = AuthStyle.BEARER_HEADER,
        modelsEndpointPath = "/models",
        chatEndpointPath = "/chat/completions",
        selectedModelId = "gpt-4o",
        isPresetTemplate = true
    )

    fun defaultOpenRouter(): ProviderProfile = ProviderProfile(
        id = "openrouter",
        displayName = "OpenRouter",
        protocol = ApiProtocol.OPENROUTER,
        baseUrl = "https://openrouter.ai/api/v1",
        authStyle = AuthStyle.BEARER_HEADER,
        customHeaders = mapOf(
            "X-Title" to "ROXUM-ACS"
        ),
        modelsEndpointPath = "/models",
        chatEndpointPath = "/chat/completions",
        selectedModelId = "openai/gpt-4o-mini",
        isPresetTemplate = true
    )

    fun defaultGeminiOpenAI(): ProviderProfile = ProviderProfile(
        id = "gemini",
        displayName = "Google Gemini",
        protocol = ApiProtocol.GEMINI_OPENAI,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        authStyle = AuthStyle.BEARER_HEADER,
        modelsEndpointPath = "/models",
        chatEndpointPath = "/chat/completions",
        selectedModelId = "gemini-2.5-flash",
        isPresetTemplate = true
    )

    fun defaultGeminiNative(): ProviderProfile = ProviderProfile(
        id = "gemini_native",
        displayName = "Google Gemini (Native + Grounding)",
        protocol = ApiProtocol.GEMINI_NATIVE,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        authStyle = AuthStyle.X_GOOG_API_KEY_HEADER,
        modelsEndpointPath = "/models",
        chatEndpointPath = "/models/{model}:generateContent",
        selectedModelId = "gemini-2.5-flash",
        enableNativeGeminiGrounding = false,
        isPresetTemplate = true
    )

    fun defaultAnthropic(): ProviderProfile = ProviderProfile(
        id = "claude",
        displayName = "Anthropic Claude",
        protocol = ApiProtocol.ANTHROPIC_MESSAGES,
        baseUrl = "https://api.anthropic.com/v1",
        authStyle = AuthStyle.X_API_KEY_HEADER,
        apiVersionHeaderName = "anthropic-version",
        apiVersionHeaderValue = "2023-06-01",
        modelsEndpointPath = "/models",
        chatEndpointPath = "/messages",
        selectedModelId = "claude-3-7-sonnet-20250219",
        isPresetTemplate = true
    )

    fun defaultOllama(): ProviderProfile = ProviderProfile(
        id = "ollama",
        displayName = "Ollama (LAN / Custom Host)",
        protocol = ApiProtocol.OLLAMA_NATIVE,
        // Note: on Android phone, localhost is the phone itself, so default to an editable LAN IP template
        baseUrl = "http://192.168.1.100:11434",
        authStyle = AuthStyle.NONE,
        modelsEndpointPath = "/api/tags",
        chatEndpointPath = "/api/chat",
        allowCleartextHttp = false, // Requires explicit user permission in UI before connecting over HTTP
        selectedModelId = "llama3.2",
        isPresetTemplate = true
    )

    fun defaultDeepSeek(): ProviderProfile = ProviderProfile(
        id = "deepseek",
        displayName = "DeepSeek",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://api.deepseek.com/v1",
        authStyle = AuthStyle.BEARER_HEADER,
        modelsEndpointPath = "/models",
        chatEndpointPath = "/chat/completions",
        selectedModelId = "deepseek-chat",
        isPresetTemplate = true
    )

    fun defaultGroq(): ProviderProfile = ProviderProfile(
        id = "groq",
        displayName = "Groq",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://api.groq.com/openai/v1",
        authStyle = AuthStyle.BEARER_HEADER,
        modelsEndpointPath = "/models",
        chatEndpointPath = "/chat/completions",
        selectedModelId = "llama-3.3-70b-versatile",
        isPresetTemplate = true
    )

    fun defaultMistral(): ProviderProfile = ProviderProfile(
        id = "mistral",
        displayName = "Mistral AI",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://api.mistral.ai/v1",
        authStyle = AuthStyle.BEARER_HEADER,
        modelsEndpointPath = "/models",
        chatEndpointPath = "/chat/completions",
        selectedModelId = "mistral-large-latest",
        isPresetTemplate = true
    )

    fun defaultTogether(): ProviderProfile = ProviderProfile(
        id = "together",
        displayName = "Together AI",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://api.together.xyz/v1",
        authStyle = AuthStyle.BEARER_HEADER,
        modelsEndpointPath = "/models",
        chatEndpointPath = "/chat/completions",
        selectedModelId = "meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo",
        isPresetTemplate = true
    )

    fun defaultGrok(): ProviderProfile = ProviderProfile(
        id = "grok",
        displayName = "xAI Grok",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://api.x.ai/v1",
        authStyle = AuthStyle.BEARER_HEADER,
        modelsEndpointPath = "/models",
        chatEndpointPath = "/chat/completions",
        selectedModelId = "grok-2-latest",
        isPresetTemplate = true
    )

    fun defaultLocalLLM(
        savedBaseUrl: String? = null,
        savedModelName: String? = null
    ): ProviderProfile = ProviderProfile(
        id = "localllm",
        displayName = "Local / LAN OpenAI Server",
        protocol = ApiProtocol.OPENAI_COMPATIBLE,
        baseUrl = savedBaseUrl?.takeIf { it.isNotBlank() } ?: "http://192.168.1.100:1234/v1",
        authStyle = AuthStyle.NONE,
        modelsEndpointPath = "/models",
        chatEndpointPath = "/chat/completions",
        allowCleartextHttp = false,
        selectedModelId = savedModelName?.takeIf { it.isNotBlank() } ?: "local-model",
        isPresetTemplate = true
    )

    fun createNewCustomTemplate(customId: String, protocol: ApiProtocol = ApiProtocol.OPENAI_COMPATIBLE): ProviderProfile {
        return when (protocol) {
            ApiProtocol.ANTHROPIC_MESSAGES -> defaultAnthropic().copy(
                id = customId,
                displayName = "Custom Anthropic Endpoint",
                isPresetTemplate = false
            )
            ApiProtocol.OLLAMA_NATIVE -> defaultOllama().copy(
                id = customId,
                displayName = "Custom Ollama Server",
                isPresetTemplate = false
            )
            ApiProtocol.GEMINI_NATIVE -> defaultGeminiNative().copy(
                id = customId,
                displayName = "Custom Gemini Native",
                isPresetTemplate = false
            )
            ApiProtocol.OPENROUTER -> defaultOpenRouter().copy(
                id = customId,
                displayName = "Custom OpenRouter Profile",
                isPresetTemplate = false
            )
            ApiProtocol.CUSTOM_GENERIC -> ProviderProfile(
                id = customId,
                displayName = "Custom Generic API",
                protocol = ApiProtocol.CUSTOM_GENERIC,
                baseUrl = "https://api.example.com/v1",
                authStyle = AuthStyle.BEARER_HEADER,
                modelsEndpointPath = "/models",
                chatEndpointPath = "/chat/completions",
                modelsJsonArrayKey = "data",
                modelIdJsonField = "id",
                selectedModelId = "",
                isPresetTemplate = false
            )
            else -> ProviderProfile(
                id = customId,
                displayName = "Custom OpenAI-Compatible",
                protocol = protocol,
                baseUrl = "https://api.example.com/v1",
                authStyle = AuthStyle.BEARER_HEADER,
                modelsEndpointPath = "/models",
                chatEndpointPath = "/chat/completions",
                selectedModelId = "",
                isPresetTemplate = false
            )
        }
    }

    fun openAiPreset(): ProviderProfile = defaultOpenAI()
    fun openRouterPreset(): ProviderProfile = defaultOpenRouter()
    fun geminiOpenAiPreset(): ProviderProfile = defaultGeminiOpenAI()
    fun geminiNativePreset(): ProviderProfile = defaultGeminiNative()
    fun anthropicPreset(): ProviderProfile = defaultAnthropic()
    fun ollamaPreset(): ProviderProfile = defaultOllama().copy(allowCleartextHttp = true)
    fun groqPreset(): ProviderProfile = defaultGroq()
    fun mistralPreset(): ProviderProfile = defaultMistral()
    fun togetherPreset(): ProviderProfile = defaultTogether()

    fun getSetupTemplates(): List<ProviderProfile> = allDefaultProfiles() + listOf(
        createNewCustomTemplate("custom_openai", ApiProtocol.OPENAI_COMPATIBLE),
        createNewCustomTemplate("custom_generic", ApiProtocol.CUSTOM_GENERIC)
    )

    fun createCustomProfileFromTemplate(template: ProviderProfile, customDisplayName: String): ProviderProfile {
        val cleanId = "custom_" + customDisplayName.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "provider" } + "_" + (System.currentTimeMillis() % 100000)
        return template.copy(
            id = cleanId,
            displayName = customDisplayName.ifBlank { template.displayName },
            isPresetTemplate = false
        )
    }

    fun allDefaultProfiles(
        legacyLocalBaseUrl: String? = null,
        legacyLocalModel: String? = null
    ): List<ProviderProfile> = listOf(
        defaultGeminiOpenAI(),
        defaultGeminiNative(),
        defaultOpenAI(),
        defaultOpenRouter(),
        defaultAnthropic(),
        defaultDeepSeek(),
        defaultGroq(),
        defaultMistral(),
        defaultTogether(),
        defaultGrok(),
        defaultOllama(),
        defaultLocalLLM(legacyLocalBaseUrl, legacyLocalModel)
    )
}
