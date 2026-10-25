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

package com.tom.rv2ide.artificial.agents

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import com.tom.rv2ide.artificial.providers.ProviderRegistryManager

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class Agents(private val ctx: Context) {

  private val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)
  private val AGENT_KEY = "ai_agent_model_name"
  private val PROVIDER_KEY = "ai_provider_name"
  private val registryManager: ProviderRegistryManager
    get() = ProviderRegistryManager.getInstance(ctx)

  private val openai_models = arrayOf(
    "gpt-5.1-codex-max",
    "gpt-5.1-codex",
    "gpt-5.1-codex-mini",
    "gpt-5-codex",
    "gpt-5-chat-latest",
    "gpt-5-2025-08-07",
    "gpt-5",
    "gpt-5-mini-2025-08-07",
    "gpt-5-mini",
    "gpt-5-nano-2025-08-07",
    "gpt-5-nano",
    "gpt-5-pro-2025-10-06",
    "gpt-5-pro",
    "gpt-5-search-api",
    "gpt-5-search-api-2025-10-14",
    "gpt-5.1-chat-latest",
    "gpt-5.1",
    "gpt-5.1-2025-11-13",
    "gpt-4.1-2025-04-14",
    "gpt-4.1",
    "gpt-4.1-mini-2025-04-14",
    "gpt-4.1-mini",
    "gpt-4.1-nano-2025-04-14",
    "gpt-4.1-nano",
    "gpt-4o",
    "gpt-4o-2024-05-13",
    "gpt-4o-mini-2024-07-18",
    "gpt-4o-mini",
    "gpt-4o-2024-08-06",
    "gpt-4o-2024-11-20",
    "gpt-4o-search-preview-2025-03-11",
    "gpt-4o-search-preview",
    "gpt-4o-mini-search-preview-2025-03-11",
    "gpt-4o-mini-search-preview",
    "o1-2024-12-17",
    "o1",
    "o3-mini",
    "o3-mini-2025-01-31",
    "o3-2025-04-16",
    "o3",
    "o4-mini-2025-04-16",
    "o4-mini",
    "gpt-3.5-turbo",
    "gpt-3.5-turbo-1106",
    "gpt-3.5-turbo-0125",
    "gpt-3.5-turbo-instruct",
    "gpt-3.5-turbo-instruct-0914",
    "gpt-3.5-turbo-16k",
    "davinci-002",
    "babbage-002"
  )

  private val claude_models = arrayOf(
    "claude-sonnet-4-5-20250929",
    "claude-haiku-4-5-20251001",
    "claude-opus-4-5-20251101",
    "claude-opus-4-1-20250805",
    "claude-opus-4-20250514",
    "claude-sonnet-4-20250514",
    "claude-3-7-sonnet-20250219",
    "claude-3-5-haiku-20241022",
    "claude-3-haiku-20240307"
  )

  private val gemini_models = arrayOf(
    "gemini-3-pro-preview",
    "gemini-2.5-pro",
    "gemini-2.5-flash",
    "gemini-2.5-flash-lite",
    "gemini-2.0-flash",
    "gemini-2.0-flash-lite",
    "gemini-1.5-flash",
    "gemini-1.5-pro"
  )

  private val deepseek_models = arrayOf(
    "deepseek-chat",
    "deepseek-reasoner"
  )

  private val grok_models = arrayOf(
    "grok-4-1-fast-reasoning",
    "grok-4-1-fast-non-reasoning",
    "grok-code-fast-1",
    "grok-4-fast-reasoning",
    "grok-4-fast-non-reasoning",
    "grok-4-0709",
    "grok-3",
    "grok-3-mini",
    "grok-beta",
    "grok-2",
    "grok-2-mini"
  )

  private val openrouter_fallback_models = arrayOf(
    "openai/gpt-4o",
    "anthropic/claude-3.5-sonnet",
    "google/gemini-2.5-pro",
    "deepseek/deepseek-chat"
  )

  private val localllm_models = arrayOf(
    "llama3.2",
    "qwen2.5-coder",
    "local-model"
  )

  val ai_agents = openai_models + claude_models + gemini_models + deepseek_models + grok_models + localllm_models

  fun getFallbackModelsForProvider(providerId: String): Array<String> {
    return when (providerId) {
      "openai" -> openai_models
      "gemini", "gemini_openai" -> gemini_models
      "claude" -> claude_models
      "deepseek" -> deepseek_models
      "grok" -> grok_models
      "openrouter" -> openrouter_fallback_models
      "ollama", "localllm" -> localllm_models
      else -> {
        val profile = registryManager.getProfile(providerId)
        val sel = profile?.effectiveModelId()?.takeIf { it.isNotBlank() }
        if (sel != null) arrayOf(sel) else arrayOf("gpt-4o")
      }
    }
  }

  /**
   * Returns discovered/cached models for [providerId] when available;
   * otherwise falls back to the preset fallback list while ensuring any
   * user-selected or manual model override is included at the top.
   */
  fun getModelsForProvider(providerId: String): Array<String> {
    val discoveredIds = registryManager.getCachedModels(providerId).map { it.id }
    val profile = registryManager.getProfile(providerId)
    val manualOrSelected = listOfNotNull(
      profile?.manualModelIdOverride?.takeIf { it.isNotBlank() },
      profile?.selectedModelId?.takeIf { it.isNotBlank() }
    )

    val baseList = if (discoveredIds.isNotEmpty()) {
      discoveredIds
    } else {
      getFallbackModelsForProvider(providerId).toList()
    }

    val combined = LinkedHashSet<String>()
    manualOrSelected.forEach { combined.add(it) }
    baseList.forEach { combined.add(it) }
    return combined.toTypedArray()
  }

  fun getProviderForModel(modelName: String): String? {
    val currentProv = getProvider()
    if (modelName in getModelsForProvider(currentProv)) {
      return currentProv
    }
    for (profile in registryManager.getAllProfiles()) {
      val cached = registryManager.getCachedModels(profile.id)
      if (cached.any { it.id == modelName } || profile.effectiveModelId() == modelName) {
        return profile.id
      }
    }
    return when {
      modelName in openai_models -> "openai"
      modelName in gemini_models -> "gemini"
      modelName in claude_models -> "claude"
      modelName in deepseek_models -> "deepseek"
      modelName in grok_models -> "grok"
      modelName in localllm_models -> "localllm"
      else -> null
    }
  }

  fun setAgent(name: String) {
    val cleanName = name.trim()
    if (cleanName.isEmpty()) return
    val currentProvider = getProvider()
    sp.edit().putString(AGENT_KEY, cleanName).apply()
    sp.edit().putString("ai_agent_model_for_$currentProvider", cleanName).apply()
    registryManager.selectModelForProvider(currentProvider, cleanName)
  }

  fun setAgentForProvider(providerId: String, modelName: String) {
    val cleanName = modelName.trim()
    if (cleanName.isEmpty()) return
    sp.edit().putString("ai_agent_model_for_$providerId", cleanName).apply()
    if (providerId == getProvider()) {
      sp.edit().putString(AGENT_KEY, cleanName).apply()
    }
    registryManager.selectModelForProvider(providerId, cleanName)
  }

  fun getAgentForProvider(providerId: String): String {
    val profile = registryManager.getProfile(providerId)
    if (profile != null) {
      val manual = profile.manualModelIdOverride.trim()
      if (manual.isNotEmpty()) return manual
      val selected = profile.selectedModelId.trim()
      if (selected.isNotEmpty()) return selected
    }
    val savedForProv = sp.getString("ai_agent_model_for_$providerId", null)
    if (!savedForProv.isNullOrBlank()) return savedForProv

    val fallback = getModelsForProvider(providerId).firstOrNull()
    return fallback ?: "gpt-4o"
  }

  fun getAgent(): String {
    val currentProv = getProvider()
    val profile = registryManager.getProfile(currentProv)
    if (profile != null && profile.manualModelIdOverride.isNotBlank()) {
      return profile.manualModelIdOverride.trim()
    }
    val savedModel = sp.getString(AGENT_KEY, null)
    if (!savedModel.isNullOrBlank()) return savedModel
    if (profile != null && profile.selectedModelId.isNotBlank()) {
      return profile.selectedModelId.trim()
    }

    return when (currentProv) {
      "openai" -> "gpt-4o"
      "gemini", "gemini_openai" -> "gemini-2.5-pro"
      "claude" -> "claude-sonnet-4-20250514"
      "deepseek" -> "deepseek-chat"
      "grok" -> "grok-beta"
      "openrouter" -> "openai/gpt-4o"
      else -> getModelsForProvider(currentProv).firstOrNull() ?: "gpt-4o"
    }
  }

  fun setProvider(provider: String) {
    sp.edit().putString(PROVIDER_KEY, provider).apply()
    registryManager.setActiveProviderId(provider)
    val modelForProv = getAgentForProvider(provider)
    if (modelForProv.isNotBlank()) {
      sp.edit().putString(AGENT_KEY, modelForProv).apply()
    }
  }

  fun getProvider(): String {
    return sp.getString(PROVIDER_KEY, null)
      ?: registryManager.getActiveProviderId()
  }

  /**
   * Accepts any non-blank model ID so that dynamically discovered models
   * and manual model IDs are never rejected or overwritten.
   */
  fun isValidModelForProvider(modelName: String, providerId: String): Boolean {
    return modelName.isNotBlank()
  }
}