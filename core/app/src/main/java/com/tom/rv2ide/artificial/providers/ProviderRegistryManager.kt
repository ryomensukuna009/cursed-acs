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

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.tom.rv2ide.artificial.discovery.ModelDiscoveryService
import com.tom.rv2ide.artificial.secrets.SecureKeyStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Storage abstraction so [ProviderRegistryManager] can run both on Android SharedPreferences
 * and in pure JVM unit tests.
 */
interface KeyValueStore {
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

class SharedPrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun putString(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}

class InMemoryKeyValueStore(initial: Map<String, Any> = emptyMap()) : KeyValueStore {
    private val map = initial.toMutableMap()
    override fun getString(key: String, default: String?): String? = map[key] as? String ?: default
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) {
        map[key] = value
    }
}

/**
 * Manages AI provider profiles, cached discovered models, active provider/model selection,
 * and safe migration from legacy ACS settings.
 */
class ProviderRegistryManager(
    private val store: KeyValueStore,
    private val discoveryService: ModelDiscoveryService = ModelDiscoveryService()
) {

    constructor(
        context: Context?,
        discoveryService: ModelDiscoveryService = ModelDiscoveryService()
    ) : this(
        store = if (context != null) {
            SharedPrefsKeyValueStore(PreferenceManager.getDefaultSharedPreferences(context.applicationContext))
        } else {
            InMemoryKeyValueStore()
        },
        discoveryService = discoveryService
    )

    companion object {
        private const val KEY_PROFILES_JSON = "roxum_ai_provider_profiles_v1"
        private const val KEY_MODEL_CACHE_PREFIX = "roxum_ai_models_cache_v1_"
        private const val KEY_MIGRATED_V1 = "roxum_ai_migrated_v1"
        private const val LEGACY_PROVIDER_KEY = "ai_provider_name"
        private const val LEGACY_MODEL_KEY = "ai_agent_model_name"
        private const val LEGACY_LOCAL_URL_KEY = "local_llm_base_url"
        private const val LEGACY_LOCAL_MODEL_KEY = "local_llm_model_name"

        @Volatile
        private var instance: ProviderRegistryManager? = null

        @Synchronized
        fun getInstance(context: Context): ProviderRegistryManager {
            instance?.let { return it }
            SecureKeyStore.init(context.applicationContext)
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            return ProviderRegistryManager(SharedPrefsKeyValueStore(prefs)).also {
                instance = it
            }
        }

        @Synchronized
        fun setInstanceForTesting(manager: ProviderRegistryManager?) {
            instance = manager
        }
    }

    init {
        ensureInitializedAndMigrated()
    }

    @Synchronized
    private fun ensureInitializedAndMigrated() {
        val rawProfiles = store.getString(KEY_PROFILES_JSON, null)
        val alreadyMigrated = store.getBoolean(KEY_MIGRATED_V1, false)

        if (rawProfiles.isNullOrBlank()) {
            val legacyLocalUrl = store.getString(LEGACY_LOCAL_URL_KEY, null)
            val legacyLocalModel = store.getString(LEGACY_LOCAL_MODEL_KEY, null)
            val activeLegacyProvider = store.getString(LEGACY_PROVIDER_KEY, "gemini") ?: "gemini"
            val activeLegacyModel = store.getString(LEGACY_MODEL_KEY, null)

            val defaults = ProviderTemplates.allDefaultProfiles(legacyLocalUrl, legacyLocalModel).map { profile ->
                if (profile.id == activeLegacyProvider && !activeLegacyModel.isNullOrBlank()) {
                    profile.copy(selectedModelId = activeLegacyModel)
                } else {
                    profile
                }
            }
            saveProfilesInternal(defaults)
            store.putBoolean(KEY_MIGRATED_V1, true)
        } else if (!alreadyMigrated) {
            // Merge any missing preset templates without overwriting existing profiles
            val current = getAllProfiles().toMutableList()
            val existingIds = current.map { it.id }.toSet()
            val legacyLocalUrl = store.getString(LEGACY_LOCAL_URL_KEY, null)
            val legacyLocalModel = store.getString(LEGACY_LOCAL_MODEL_KEY, null)
            for (preset in ProviderTemplates.allDefaultProfiles(legacyLocalUrl, legacyLocalModel)) {
                if (preset.id !in existingIds) {
                    current.add(preset)
                }
            }
            saveProfilesInternal(current)
            store.putBoolean(KEY_MIGRATED_V1, true)
        }
    }

    @Synchronized
    fun getAllProfiles(): List<ProviderProfile> {
        val raw = store.getString(KEY_PROFILES_JSON, null)
        if (raw.isNullOrBlank()) return ProviderTemplates.allDefaultProfiles()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ProviderProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(ProviderProfile.fromJson(obj))
            }
            if (list.isEmpty()) ProviderTemplates.allDefaultProfiles() else list
        } catch (_: Exception) {
            ProviderTemplates.allDefaultProfiles()
        }
    }

    fun getEnabledProfiles(): List<ProviderProfile> {
        return getAllProfiles().filter { it.enabled }
    }

    fun getProfile(providerId: String): ProviderProfile? {
        val canonicalId = if (providerId == "anthropic") "claude" else providerId
        return getAllProfiles().firstOrNull { it.id.equals(canonicalId, ignoreCase = true) }
    }

    @Synchronized
    fun saveProfile(profile: ProviderProfile) {
        val list = getAllProfiles().toMutableList()
        val index = list.indexOfFirst { it.id.equals(profile.id, ignoreCase = true) }
        if (index >= 0) {
            list[index] = profile
        } else {
            list.add(profile)
        }
        saveProfilesInternal(list)

        // If this is the active provider, sync selected model to legacy key for backward compatibility
        if (getActiveProviderId().equals(profile.id, ignoreCase = true)) {
            val effectiveModel = profile.getEffectiveModelId()
            if (effectiveModel.isNotBlank()) {
                store.putString(LEGACY_MODEL_KEY, effectiveModel)
            }
        }
    }

    @Synchronized
    fun removeProfile(providerId: String): Boolean {
        val list = getAllProfiles().toMutableList()
        val removed = list.removeAll { it.id.equals(providerId, ignoreCase = true) }
        if (removed) {
            if (list.isEmpty()) {
                list.addAll(ProviderTemplates.allDefaultProfiles())
            }
            saveProfilesInternal(list)
            SecureKeyStore.clearProviderApiKey(providerId)
            clearCachedModels(providerId)
            if (getActiveProviderId().equals(providerId, ignoreCase = true)) {
                val next = list.first()
                setActiveProviderAndModel(next.id, next.getEffectiveModelId())
            }
        }
        return removed
    }

    fun getActiveProviderId(): String {
        val saved = store.getString(LEGACY_PROVIDER_KEY, "gemini") ?: "gemini"
        return if (saved == "anthropic") "claude" else saved
    }

    fun getActiveProfile(): ProviderProfile {
        val activeId = getActiveProviderId()
        return getProfile(activeId) ?: getAllProfiles().first()
    }

    fun getActiveModelId(): String {
        val profile = getActiveProfile()
        val effective = profile.getEffectiveModelId()
        if (effective.isNotBlank()) return effective
        return store.getString(LEGACY_MODEL_KEY, "gemini-2.5-flash") ?: "gemini-2.5-flash"
    }

    @Synchronized
    fun setActiveProvider(providerId: String) {
        val canonical = if (providerId == "anthropic") "claude" else providerId
        store.putString(LEGACY_PROVIDER_KEY, canonical)
        val profile = getProfile(canonical)
        if (profile != null) {
            val model = profile.getEffectiveModelId()
            if (model.isNotBlank()) {
                store.putString(LEGACY_MODEL_KEY, model)
            }
        }
    }

    @Synchronized
    fun setActiveModel(modelId: String) {
        val trimmed = modelId.trim()
        if (trimmed.isEmpty()) return
        store.putString(LEGACY_MODEL_KEY, trimmed)
        val activeProfile = getActiveProfile()
        saveProfile(activeProfile.copy(selectedModelId = trimmed))
    }

    @Synchronized
    fun setActiveProviderAndModel(providerId: String, modelId: String) {
        val canonical = if (providerId == "anthropic") "claude" else providerId
        store.putString(LEGACY_PROVIDER_KEY, canonical)
        val trimmedModel = modelId.trim()
        if (trimmedModel.isNotEmpty()) {
            store.putString(LEGACY_MODEL_KEY, trimmedModel)
        }
        val profile = getProfile(canonical)
        if (profile != null && trimmedModel.isNotEmpty()) {
            saveProfile(profile.copy(selectedModelId = trimmedModel))
        }
    }

    fun isProfileConfigured(providerId: String): Boolean {
        val profile = getProfile(providerId) ?: return false
        if (!profile.enabled) return false
        if (profile.baseUrl.isBlank()) return false
        return when (profile.authStyle) {
            AuthStyle.NONE -> true
            else -> SecureKeyStore.hasProviderApiKey(profile.id)
        }
    }

    /**
     * Returns cached models for [providerId]. Does NOT make any network call.
     */
    fun getCachedModels(providerId: String): List<DiscoveredModel> {
        val canonical = if (providerId == "anthropic") "claude" else providerId
        val raw = store.getString("$KEY_MODEL_CACHE_PREFIX$canonical", null)
        val cached = if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { idx ->
                    arr.optJSONObject(idx)?.let { DiscoveredModel.fromJson(it) }
                }
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val profile = getProfile(canonical)
        val manual = profile?.manualModelIdOverride?.trim().orEmpty()
        val selected = profile?.selectedModelId?.trim().orEmpty()

        val result = cached.toMutableList()
        if (manual.isNotEmpty() && result.none { it.id == manual }) {
            result.add(
                0,
                DiscoveredModel(
                    providerId = canonical,
                    id = manual,
                    displayName = manual,
                    isManualEntry = true
                )
            )
        }
        if (selected.isNotEmpty() && result.none { it.id == selected }) {
            result.add(
                DiscoveredModel(
                    providerId = canonical,
                    id = selected,
                    displayName = selected,
                    isManualEntry = true
                )
            )
        }
        return result
    }

    /**
     * Filters available models for [providerId] by search query across ID, display name, and description.
     */
    fun searchModels(providerId: String, query: String): List<DiscoveredModel> {
        val all = getCachedModels(providerId)
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter { m ->
            m.id.lowercase().contains(q) ||
                m.displayName.lowercase().contains(q) ||
                (m.description?.lowercase()?.contains(q) == true)
        }
    }

    /**
     * Resolves the effective [ModelCapabilities] for the given provider and model ID,
     * applying any explicit user overrides configured on the profile.
     */
    fun getEffectiveModelCapabilities(providerId: String, modelId: String): ModelCapabilities {
        val profile = getProfile(providerId)
        val catalogCaps = try {
            com.tom.rv2ide.artificial.models.ModelCatalogManager.getInstance().getCapabilities(modelId, providerId)
        } catch (_: Exception) {
            ModelCapabilities()
        }
        val cached = getCachedModels(providerId).firstOrNull { it.id == modelId }?.capabilities
        val baseCaps = cached ?: catalogCaps

        val effectiveVision = if (catalogCaps.supportsVision == TriStateCapability.SUPPORTED || baseCaps.supportsVision == TriStateCapability.SUPPORTED) {
            TriStateCapability.SUPPORTED
        } else if (baseCaps.supportsVision != TriStateCapability.UNKNOWN) {
            baseCaps.supportsVision
        } else {
            catalogCaps.supportsVision
        }

        val effectiveTools = if (profile?.userOverrideToolCalling != null && profile.userOverrideToolCalling != TriStateCapability.UNKNOWN) {
            profile.userOverrideToolCalling
        } else if (catalogCaps.supportsToolCalling == TriStateCapability.SUPPORTED || baseCaps.supportsToolCalling == TriStateCapability.SUPPORTED) {
            TriStateCapability.SUPPORTED
        } else if (baseCaps.supportsToolCalling != TriStateCapability.UNKNOWN) {
            baseCaps.supportsToolCalling
        } else {
            catalogCaps.supportsToolCalling
        }

        val effectiveThinking = if (catalogCaps.supportsThinking == TriStateCapability.SUPPORTED || baseCaps.supportsThinking == TriStateCapability.SUPPORTED) {
            TriStateCapability.SUPPORTED
        } else if (baseCaps.supportsThinking != TriStateCapability.UNKNOWN) {
            baseCaps.supportsThinking
        } else {
            catalogCaps.supportsThinking
        }

        val effectiveCtx = profile?.userOverrideContextLength ?: baseCaps.contextLength ?: catalogCaps.contextLength

        val groundingSupport = if (profile?.protocol == ApiProtocol.GEMINI_NATIVE && profile.enableNativeGeminiGrounding) {
            TriStateCapability.SUPPORTED
        } else {
            baseCaps.supportsSearchGrounding
        }

        val effectiveInMods = when (effectiveVision) {
            TriStateCapability.SUPPORTED -> listOf("text", "image")
            TriStateCapability.UNSUPPORTED -> listOf("text")
            TriStateCapability.UNKNOWN -> baseCaps.inputModalities ?: catalogCaps.inputModalities
        }

        return baseCaps.copy(
            contextLength = effectiveCtx,
            supportsToolCalling = effectiveTools,
            supportsThinking = effectiveThinking,
            supportsSearchGrounding = groundingSupport,
            inputModalities = effectiveInMods
        )
    }

    @Synchronized
    fun saveCachedModels(providerId: String, models: List<DiscoveredModel>) {
        val canonical = if (providerId == "anthropic") "claude" else providerId
        val arr = JSONArray()
        models.forEach { arr.put(it.toJson()) }
        store.putString("$KEY_MODEL_CACHE_PREFIX$canonical", arr.toString())
    }

    @Synchronized
    fun clearCachedModels(providerId: String) {
        val canonical = if (providerId == "anthropic") "claude" else providerId
        store.putString("$KEY_MODEL_CACHE_PREFIX$canonical", null)
    }

    /**
     * Refreshes the provider's model catalogue from its configured live endpoint and updates cache & status.
     */
    suspend fun refreshModelsForProfile(profileId: String): ModelDiscoveryResult {
        val profile = getProfile(profileId)
            ?: return ModelDiscoveryResult(
                status = DiscoveryStatus.NETWORK_ERROR,
                errorMessage = "Provider profile '$profileId' not found."
            )
        val apiKey = SecureKeyStore.getProviderApiKey(profile.id)
        val result = discoveryService.discoverModels(profile, apiKey)

        val now = System.currentTimeMillis()
        val updatedProfile = if (result.isSuccess) {
            saveCachedModels(profile.id, result.models)
            val currentSelected = profile.selectedModelId.trim()
            val newSelected = when {
                currentSelected.isNotEmpty() && result.models.any { it.id == currentSelected } -> currentSelected
                currentSelected.isNotEmpty() && !profile.manualModelIdOverride.isNullOrBlank() -> currentSelected
                result.models.isNotEmpty() && currentSelected.isEmpty() -> result.models.first().id
                else -> currentSelected
            }
            profile.copy(
                selectedModelId = newSelected,
                lastDiscoveryStatus = result.status,
                lastDiscoveryTimeMs = now,
                lastDiscoveryError = null
            )
        } else {
            profile.copy(
                lastDiscoveryStatus = result.status,
                lastDiscoveryTimeMs = now,
                lastDiscoveryError = result.errorMessage
            )
        }
        saveProfile(updatedProfile)
        return result
    }

    suspend fun testProfileConnection(profile: ProviderProfile, overrideApiKey: String? = null): ConnectionTestResult {
        val key = overrideApiKey ?: SecureKeyStore.getProviderApiKey(profile.id)
        val now = System.currentTimeMillis()
        val disc = discoveryService.discoverModels(profile, key, maxPages = 1)
        if (disc.isSuccess && disc.models.isNotEmpty() && getProfile(profile.id) != null) {
            saveCachedModels(profile.id, disc.models)
        }
        val result = when (disc.status) {
            DiscoveryStatus.SUCCESS, DiscoveryStatus.EMPTY -> ConnectionTestResult(
                success = true,
                httpStatusCode = disc.httpStatusCode,
                latencyMs = disc.latencyMs,
                message = "Connected successfully (${disc.models.size} models discovered in ${disc.latencyMs}ms).",
                discoveredModelCount = disc.models.size
            )
            DiscoveryStatus.UNSUPPORTED_ENDPOINT -> ConnectionTestResult(
                success = false,
                httpStatusCode = disc.httpStatusCode,
                latencyMs = disc.latencyMs,
                message = disc.errorMessage ?: "Endpoint does not support model listing (HTTP ${disc.httpStatusCode}). You can enter a model ID manually."
            )
            else -> ConnectionTestResult(
                success = false,
                httpStatusCode = disc.httpStatusCode,
                latencyMs = disc.latencyMs,
                message = disc.errorMessage ?: "Connection test failed (${disc.status})."
            )
        }

        // Persist verified connection state on profile if registered
        val existing = getProfile(profile.id)
        if (existing != null) {
            val updated = existing.copy(
                lastConnectionTestSuccess = result.success,
                lastConnectionTestTimeMs = now,
                lastConnectionTestLatencyMs = result.latencyMs,
                lastConnectionTestMessage = result.message
            )
            saveProfile(updated)
        }

        return result
    }

    /**
     * Determines the health and verification state of [profile].
     * Distinguishes a configured/untested provider from one that has passed a real connection test.
     */
    fun getConnectionState(profile: ProviderProfile): ProviderConnectionStatus {
        val hasKey = hasProviderApiKey(profile.id) || profile.authStyle == AuthStyle.NONE
        if (!hasKey) return ProviderConnectionStatus.KEY_REQUIRED
        return when (profile.lastConnectionTestSuccess) {
            true -> ProviderConnectionStatus.VERIFIED
            false -> ProviderConnectionStatus.TEST_FAILED
            null -> ProviderConnectionStatus.CONFIGURED_UNTESTED
        }
    }

    /**
     * Exports provider profiles as a JSON string.
     * By default, [includeSecrets] is false so API keys are NEVER included in exported configurations.
     */
    fun exportProfilesJson(includeSecrets: Boolean = false): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("activeProviderId", getActiveProviderId())
        root.put("activeModelId", getActiveModelId())
        val profilesArr = JSONArray()
        for (p in getAllProfiles()) {
            val pJson = p.toJson()
            if (includeSecrets) {
                val secret = SecureKeyStore.getProviderApiKey(p.id)
                if (secret.isNotBlank()) {
                    pJson.put("apiKey", secret)
                }
            } else {
                pJson.remove("apiKey")
            }
            profilesArr.put(pJson)
        }
        root.put("profiles", profilesArr)
        return root.toString(2)
    }

    fun setProviderApiKey(providerId: String, apiKey: String) {
        SecureKeyStore.putProviderApiKey(providerId, apiKey)
    }

    fun clearProviderApiKey(providerId: String) {
        SecureKeyStore.clearProviderApiKey(providerId)
    }

    fun hasProviderApiKey(providerId: String): Boolean {
        return SecureKeyStore.hasProviderApiKey(providerId)
    }

    fun deleteProfile(providerId: String): Boolean = removeProfile(providerId)

    fun cacheDiscoveredModels(providerId: String, models: List<DiscoveredModel>) =
        saveCachedModels(providerId, models)

    suspend fun fetchAndCacheModels(providerId: String): ModelDiscoveryResult =
        refreshModelsForProfile(providerId)

    suspend fun testProviderConnection(providerId: String): ConnectionTestResult {
        val p = getProfile(providerId)
            ?: return ConnectionTestResult(false, -1, 0L, "Provider profile '$providerId' not found.")
        return testProfileConnection(p)
    }

    fun selectModelForProvider(providerId: String, modelId: String) {
        val p = getProfile(providerId) ?: return
        saveProfile(p.copy(selectedModelId = modelId.trim()))
    }

    fun setManualModelOverride(providerId: String, manualModelId: String) {
        val p = getProfile(providerId) ?: return
        saveProfile(p.copy(manualModelIdOverride = manualModelId.trim()))
    }

    fun setActiveProviderId(providerId: String) = setActiveProvider(providerId)

    private fun saveProfilesInternal(profiles: List<ProviderProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        store.putString(KEY_PROFILES_JSON, arr.toString())
    }
}
