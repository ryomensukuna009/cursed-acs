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

import com.tom.rv2ide.preferences.internal.prefManager

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

object ApiKey {

    private fun readOrMigrateKey(providerId: String, legacyPrefKey: String? = null): String {
        val existingEncrypted = SecureKeyStore.getProviderApiKey(providerId)
        if (existingEncrypted.isNotBlank()) {
            return existingEncrypted
        }
        if (legacyPrefKey != null) {
            try {
                val legacyVal = prefManager.getString(legacyPrefKey, "").trim()
                if (legacyVal.isNotEmpty()) {
                    SecureKeyStore.putProviderApiKey(providerId, legacyVal)
                    prefManager.putString(legacyPrefKey, "")
                    return legacyVal
                }
            } catch (_: Throwable) {
            }
        }
        return ""
    }

    fun isAIAgentEnabled(): Boolean {
        return try {
            prefManager.getBoolean("ai_agent_enabled", false)
        } catch (_: Throwable) {
            false
        }
    }

    fun setProviderKey(providerId: String, apiKey: String) {
        SecureKeyStore.putProviderApiKey(providerId, apiKey)
    }

    fun getProviderKey(providerId: String): String {
        val legacyKey = when (providerId.lowercase()) {
            "gemini", "gemini_native" -> "ai_agent_gemini_api_key"
            "openai" -> "ai_agent_openai_api_key"
            "deepseek" -> "ai_agent_deepseek_api_key"
            "claude", "anthropic" -> "ai_agent_anthropic_api_key"
            "grok" -> "ai_agent_grok_api_key"
            else -> null
        }
        return readOrMigrateKey(providerId, legacyKey)
    }

    fun hasProviderKey(providerId: String): Boolean {
        return getProviderKey(providerId).isNotBlank()
    }

    // Gemini API Key
    fun getGeminiApiKey(): String = readOrMigrateKey("gemini", "ai_agent_gemini_api_key")
    fun setGeminiApiKey(key: String) = SecureKeyStore.putProviderApiKey("gemini", key)
    fun hasGeminiKey(): Boolean = getGeminiApiKey().isNotBlank()

    // OpenAI API Key
    fun getOpenAIApiKey(): String = readOrMigrateKey("openai", "ai_agent_openai_api_key")
    fun setOpenAIApiKey(key: String) = SecureKeyStore.putProviderApiKey("openai", key)
    fun hasOpenAIKey(): Boolean = getOpenAIApiKey().isNotBlank()

    // Deepseek API Key
    fun getDeepseekApiKey(): String = readOrMigrateKey("deepseek", "ai_agent_deepseek_api_key")
    fun setDeepseekApiKey(key: String) = SecureKeyStore.putProviderApiKey("deepseek", key)
    fun hasDeepseekKey(): Boolean = getDeepseekApiKey().isNotBlank()

    // Anthropic API Key
    fun getAnthropicApiKey(): String = readOrMigrateKey("claude", "ai_agent_anthropic_api_key")
    fun setAnthropicApiKey(key: String) = SecureKeyStore.putProviderApiKey("claude", key)
    fun hasAnthropicKey(): Boolean = getAnthropicApiKey().isNotBlank()

    // Grok API Key
    fun getGrokApiKey(): String = readOrMigrateKey("grok", "ai_agent_grok_api_key")
    fun setGrokApiKey(key: String) = SecureKeyStore.putProviderApiKey("grok", key)
    fun hasGrokKey(): Boolean = getGrokApiKey().isNotBlank()

    // OpenRouter API Key
    fun getOpenRouterApiKey(): String = readOrMigrateKey("openrouter", null)
    fun setOpenRouterApiKey(key: String) = SecureKeyStore.putProviderApiKey("openrouter", key)
    fun hasOpenRouterKey(): Boolean = getOpenRouterApiKey().isNotBlank()

    @Deprecated("Use getGeminiApiKey() instead", ReplaceWith("getGeminiApiKey()"))
    fun getApiKey(): String = getGeminiApiKey()

    fun getAvailableProviders(): List<String> {
        val providers = mutableListOf<String>()
        if (hasGeminiKey()) providers.add("Gemini")
        if (hasOpenAIKey()) providers.add("OpenAI")
        if (hasDeepseekKey()) providers.add("Deepseek")
        if (hasAnthropicKey()) providers.add("Anthropic")
        if (hasGrokKey()) providers.add("Grok")
        if (hasOpenRouterKey()) providers.add("OpenRouter")
        return providers
    }

    fun getAllApiKeys(): Map<String, String> {
        return mapOf(
            "gemini" to getGeminiApiKey(),
            "openai" to getOpenAIApiKey(),
            "deepseek" to getDeepseekApiKey(),
            "anthropic" to getAnthropicApiKey(),
            "grok" to getGrokApiKey(),
            "openrouter" to getOpenRouterApiKey()
        ).filterValues { it.isNotBlank() }
    }

    fun hasAnyApiKey(): Boolean {
        return hasGeminiKey() || hasOpenAIKey() || hasDeepseekKey() ||
            hasAnthropicKey() || hasGrokKey() || hasOpenRouterKey()
    }
}