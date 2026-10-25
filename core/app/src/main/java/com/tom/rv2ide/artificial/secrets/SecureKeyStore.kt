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

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Interface for encrypted secret storage at rest.
 */
interface SecretStorageBackend {
    fun putSecret(alias: String, plaintext: String)
    fun getSecret(alias: String): String?
    fun removeSecret(alias: String)
    fun hasSecret(alias: String): Boolean
    fun getAllSecretAliases(): Set<String>
    fun getRawCiphertextForVerification(alias: String): String?
}

/**
 * Android Keystore AES-256-GCM encrypted storage backend.
 * Stores only Base64(IV + Ciphertext) in a dedicated private SharedPreferences file;
 * the AES-256 key never leaves the AndroidKeyStore hardware/system module.
 */
class AndroidKeystoreAesGcmBackend(context: Context) : SecretStorageBackend {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "roxum_acs_ai_master_key_v1"
        private const val PREFS_FILE_NAME = "roxum_acs_encrypted_ai_secrets"
        private const val GCM_TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private val encryptedPrefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    @Synchronized
    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existingEntry = keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingEntry != null) {
            return existingEntry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun putSecret(alias: String, plaintext: String) {
        val trimmed = plaintext.trim()
        if (trimmed.isEmpty()) {
            removeSecret(alias)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))

        // Pack [1-byte IV length][IV bytes][Ciphertext bytes]
        val packed = ByteArray(1 + iv.size + ciphertext.size)
        packed[0] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 1, iv.size)
        System.arraycopy(ciphertext, 0, packed, 1 + iv.size, ciphertext.size)

        val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
        encryptedPrefs.edit().putString(alias, encoded).apply()
    }

    override fun getSecret(alias: String): String? {
        val encoded = encryptedPrefs.getString(alias, null) ?: return null
        if (encoded.isBlank()) return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size < 2) return null
            val ivLen = packed[0].toInt() and 0xFF
            if (packed.size <= 1 + ivLen) return null
            val iv = packed.copyOfRange(1, 1 + ivLen)
            val ciphertext = packed.copyOfRange(1 + ivLen, packed.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    override fun removeSecret(alias: String) {
        encryptedPrefs.edit().remove(alias).apply()
    }

    override fun hasSecret(alias: String): Boolean {
        return !getSecret(alias).isNullOrBlank()
    }

    override fun getAllSecretAliases(): Set<String> {
        return encryptedPrefs.all.keys.toSet()
    }

    override fun getRawCiphertextForVerification(alias: String): String? {
        return encryptedPrefs.getString(alias, null)
    }
}

/**
 * Pure JVM AES-256-GCM encrypted storage backend for deterministic unit tests
 * and environments where AndroidKeyStore hardware is unavailable.
 */
class JvmAesGcmSecretBackend(
    keyBytes: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
) : SecretStorageBackend {

    private val secretKey: SecretKey = SecretKeySpec(keyBytes.copyOf(32), "AES")
    private val random = SecureRandom()
    private val cipherStore = mutableMapOf<String, String>()

    @Synchronized
    override fun putSecret(alias: String, plaintext: String) {
        val trimmed = plaintext.trim()
        if (trimmed.isEmpty()) {
            removeSecret(alias)
            return
        }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))

        val packed = ByteArray(1 + iv.size + ciphertext.size)
        packed[0] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 1, iv.size)
        System.arraycopy(ciphertext, 0, packed, 1 + iv.size, ciphertext.size)

        cipherStore[alias] = java.util.Base64.getEncoder().encodeToString(packed)
    }

    @Synchronized
    override fun getSecret(alias: String): String? {
        val encoded = cipherStore[alias] ?: return null
        return try {
            val packed = java.util.Base64.getDecoder().decode(encoded)
            if (packed.size < 2) return null
            val ivLen = packed[0].toInt() and 0xFF
            val iv = packed.copyOfRange(1, 1 + ivLen)
            val ciphertext = packed.copyOfRange(1 + ivLen, packed.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    override fun removeSecret(alias: String) {
        cipherStore.remove(alias)
    }

    @Synchronized
    override fun hasSecret(alias: String): Boolean {
        return !getSecret(alias).isNullOrBlank()
    }

    @Synchronized
    override fun getAllSecretAliases(): Set<String> {
        return cipherStore.keys.toSet()
    }

    @Synchronized
    override fun getRawCiphertextForVerification(alias: String): String? {
        return cipherStore[alias]
    }
}

/**
 * Central manager for encrypted provider API keys and search tool API keys.
 * Automatically migrates legacy plaintext keys from ordinary SharedPreferences.
 */
object SecureKeyStore {

    private const val PROVIDER_KEY_PREFIX = "provider_api_key_"
    private const val SEARCH_KEY_PREFIX = "search_api_key_"

    @Volatile
    private var backend: SecretStorageBackend? = null

    private val legacyProviderKeyMapping = mapOf(
        "gemini" to "ai_agent_gemini_api_key",
        "openai" to "ai_agent_openai_api_key",
        "deepseek" to "ai_agent_deepseek_api_key",
        "claude" to "ai_agent_anthropic_api_key",
        "anthropic" to "ai_agent_anthropic_api_key",
        "grok" to "ai_agent_grok_api_key"
    )

    @Synchronized
    fun init(context: Context) {
        if (backend == null) {
            backend = try {
                AndroidKeystoreAesGcmBackend(context.applicationContext)
            } catch (e: Exception) {
                JvmAesGcmSecretBackend()
            }
        }
        try {
            val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            migrateLegacyPlaintextKeys(defaultPrefs)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun setBackendForTesting(testBackend: SecretStorageBackend?) {
        backend = testBackend
    }

    private fun requireBackend(context: Context? = null): SecretStorageBackend {
        backend?.let { return it }
        synchronized(this) {
            backend?.let { return it }
            if (context != null) {
                init(context)
                backend?.let { return it }
            }
            val fallback = try {
                val app = com.tom.rv2ide.app.BaseApplication.getBaseInstance()
                val androidBackend = AndroidKeystoreAesGcmBackend(app)
                try {
                    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(app)
                    migrateLegacyPlaintextKeysWithBackend(prefs, androidBackend)
                } catch (_: Exception) {
                }
                androidBackend
            } catch (_: Throwable) {
                JvmAesGcmSecretBackend()
            }
            backend = fallback
            return fallback
        }
    }

    /**
     * Migrates any legacy plaintext API keys from SharedPreferences into the encrypted backend
     * and deletes the plaintext entries from SharedPreferences.
     */
    fun migrateLegacyPlaintextKeys(sharedPreferences: SharedPreferences) {
        migrateLegacyPlaintextKeysWithBackend(sharedPreferences, requireBackend())
    }

    private fun migrateLegacyPlaintextKeysWithBackend(
        sharedPreferences: SharedPreferences,
        targetBackend: SecretStorageBackend
    ) {
        val editor = sharedPreferences.edit()
        var migratedAny = false

        for ((providerId, prefKey) in legacyProviderKeyMapping) {
            val rawValue = sharedPreferences.getString(prefKey, null)?.trim()
            if (!rawValue.isNullOrEmpty()) {
                val canonicalId = if (providerId == "anthropic") "claude" else providerId
                val alias = providerAlias(canonicalId)
                if (!targetBackend.hasSecret(alias)) {
                    targetBackend.putSecret(alias, rawValue)
                }
                editor.remove(prefKey)
                migratedAny = true
            }
        }

        if (migratedAny) {
            editor.apply()
        }
    }

    private fun providerAlias(providerId: String): String {
        val canonical = if (providerId == "anthropic") "claude" else providerId.lowercase().trim()
        return "$PROVIDER_KEY_PREFIX$canonical"
    }

    private fun searchAlias(searchProviderId: String): String {
        return "$SEARCH_KEY_PREFIX${searchProviderId.lowercase().trim()}"
    }

    fun putProviderApiKey(providerId: String, apiKey: String) {
        requireBackend().putSecret(providerAlias(providerId), apiKey)
    }

    fun putProviderApiKey(context: Context?, providerId: String, apiKey: String) {
        requireBackend(context).putSecret(providerAlias(providerId), apiKey)
    }

    fun getProviderApiKey(providerId: String): String {
        return requireBackend().getSecret(providerAlias(providerId)).orEmpty()
    }

    fun getProviderApiKey(context: Context?, providerId: String): String? {
        return requireBackend(context).getSecret(providerAlias(providerId))?.takeIf { it.isNotBlank() }
    }

    fun hasProviderApiKey(providerId: String): Boolean {
        return getProviderApiKey(providerId).isNotBlank()
    }

    fun hasProviderApiKey(context: Context?, providerId: String): Boolean {
        return !getProviderApiKey(context, providerId).isNullOrBlank()
    }

    fun clearProviderApiKey(providerId: String) {
        requireBackend().removeSecret(providerAlias(providerId))
    }

    fun removeProviderApiKey(context: Context?, providerId: String) {
        requireBackend(context).removeSecret(providerAlias(providerId))
    }

    fun getMaskedProviderApiKey(providerId: String): String {
        return SecretRedactor.maskKey(getProviderApiKey(providerId))
    }

    fun putSearchApiKey(searchProviderId: String, apiKey: String) {
        requireBackend().putSecret(searchAlias(searchProviderId), apiKey)
    }

    fun putSearchApiKey(context: Context?, searchProviderId: String, apiKey: String) {
        requireBackend(context).putSecret(searchAlias(searchProviderId), apiKey)
    }

    fun getSearchApiKey(searchProviderId: String): String {
        return requireBackend().getSecret(searchAlias(searchProviderId)).orEmpty()
    }

    fun getSearchApiKey(context: Context?, searchProviderId: String): String? {
        return requireBackend(context).getSecret(searchAlias(searchProviderId))?.takeIf { it.isNotBlank() }
    }

    fun hasSearchApiKey(searchProviderId: String): Boolean {
        return getSearchApiKey(searchProviderId).isNotBlank()
    }

    fun hasSearchApiKey(context: Context?, searchProviderId: String): Boolean {
        return !getSearchApiKey(context, searchProviderId).isNullOrBlank()
    }

    fun clearSearchApiKey(searchProviderId: String) {
        requireBackend().removeSecret(searchAlias(searchProviderId))
    }

    fun getMaskedSearchApiKey(searchProviderId: String): String {
        return SecretRedactor.maskKey(getSearchApiKey(searchProviderId))
    }

    fun getAllKnownSecrets(): List<String> {
        val b = requireBackend()
        return b.getAllSecretAliases().mapNotNull { b.getSecret(it) }.filter { it.isNotBlank() }
    }
}
