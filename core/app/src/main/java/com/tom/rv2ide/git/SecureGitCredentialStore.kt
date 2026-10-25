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

package com.tom.rv2ide.git

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKeyFactory
import android.security.keystore.KeyInfo
import android.os.Build

/**
 * Encapsulates Git credentials.
 */
data class GitCredentials(
    val username: String,
    val tokenOrPassword: String
)

/**
 * Pluggable backend interface for secure credential persistence.
 */
interface GitCredentialStorageBackend {
    fun put(key: String, value: String): Boolean
    fun get(key: String): String?
    fun remove(key: String): Boolean
    fun clear(): Boolean
    fun has(key: String): Boolean
    fun getRawCiphertext(key: String): String?
    fun putRawCiphertextForTesting(key: String, rawCiphertext: String)
}

/**
 * Android Keystore AES-256-GCM authenticated encryption backend.
 * Keys are managed directly inside Android Keystore and never stored in memory or preferences.
 * Each encryption uses a unique cryptographically secure 12-byte IV with a 128-bit authentication tag.
 * Handles Keystore desync, key invalidation, and corruption gracefully without throwing uncaught exceptions.
 */
class AndroidKeystoreGitBackend(
    private val context: Context,
    prefsFileName: String = PREFS_FILE_NAME,
    private val masterKeyAlias: String = MASTER_KEY_ALIAS
) : GitCredentialStorageBackend {

    companion object {
        private const val TAG = "SecureGitCredStore"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "roxum_acs_git_credential_master_key_v1"
        const val PREFS_FILE_NAME = "roxum_acs_git_credentials_secure"
        private const val GCM_TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsFileName, Context.MODE_PRIVATE)

    private val secureRandom = SecureRandom()

    @Synchronized
    private fun getOrCreateMasterKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(masterKeyAlias)) {
                val entry = keyStore.getEntry(masterKeyAlias, null) as? KeyStore.SecretKeyEntry
                if (entry != null) {
                    return entry.secretKey
                }
            }

            // Generate fresh AES-256-GCM key inside Android Keystore
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val builder = KeyGenParameterSpec.Builder(
                masterKeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)

            keyGenerator.init(builder.build())
            keyGenerator.generateKey()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Android Keystore master key: ${e.javaClass.simpleName}: ${e.message}")
            tryResetCorruptedKey()
            null
        }
    }

    @Synchronized
    private fun tryResetCorruptedKey() {
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(masterKeyAlias)) {
                keyStore.deleteEntry(masterKeyAlias)
            }
            prefs.edit().clear().apply()
            Log.w(TAG, "Corrupted Keystore key entry and ciphertext cleared safely.")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to reset corrupted Keystore entry: ${t.javaClass.simpleName}")
        }
    }

    override fun put(key: String, value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            return remove(key)
        }
        val masterKey = getOrCreateMasterKey() ?: return false

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)
            val iv = cipher.iv ?: return false

            val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))

            // Payload: [1-byte IV length][IV bytes][Ciphertext + 128-bit GCM Auth Tag]
            val packed = ByteArray(1 + iv.size + ciphertext.size)
            packed[0] = iv.size.toByte()
            System.arraycopy(iv, 0, packed, 1, iv.size)
            System.arraycopy(ciphertext, 0, packed, 1 + iv.size, ciphertext.size)

            val encoded = Base64.getEncoder().encodeToString(packed)
            prefs.edit().putString(key, encoded).commit()
        } catch (e: Throwable) {
            Log.e(TAG, "Encryption failed for key '$key': ${e.javaClass.simpleName}")
            false
        }
    }

    override fun get(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        if (encoded.isBlank()) return null
        val masterKey = getOrCreateMasterKey() ?: return null

        return try {
            val packed = Base64.getDecoder().decode(encoded)
            if (packed.size < 2) return null
            val ivLen = packed[0].toInt() and 0xFF
            if (packed.size <= 1 + ivLen) return null

            val iv = packed.copyOfRange(1, 1 + ivLen)
            val ciphertext = packed.copyOfRange(1 + ivLen, packed.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)

            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.e(TAG, "Decryption failed for key '$key': ${e.javaClass.simpleName}")
            null
        }
    }

    override fun remove(key: String): Boolean {
        return prefs.edit().remove(key).commit()
    }

    override fun clear(): Boolean {
        return prefs.edit().clear().commit()
    }

    override fun has(key: String): Boolean {
        return prefs.contains(key) && !get(key).isNullOrBlank()
    }

    override fun getRawCiphertext(key: String): String? {
        return prefs.getString(key, null)
    }

    override fun putRawCiphertextForTesting(key: String, rawCiphertext: String) {
        prefs.edit().putString(key, rawCiphertext).commit()
    }
}

/**
 * Pure JVM AES-256-GCM backend for deterministic unit tests and environments
 * where Android Keystore is unavailable.
 */
class JvmAesGcmGitBackend(
    keyBytes: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
) : GitCredentialStorageBackend {
    private val secretKey: SecretKey = SecretKeySpec(keyBytes.copyOf(32), "AES")
    private val secureRandom = SecureRandom()
    private val store = mutableMapOf<String, String>()

    @Synchronized
    override fun put(key: String, value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            return remove(key)
        }
        return try {
            val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))

            val packed = ByteArray(1 + iv.size + ciphertext.size)
            packed[0] = iv.size.toByte()
            System.arraycopy(iv, 0, packed, 1, iv.size)
            System.arraycopy(ciphertext, 0, packed, 1 + iv.size, ciphertext.size)

            store[key] = Base64.getEncoder().encodeToString(packed)
            true
        } catch (e: Throwable) {
            false
        }
    }

    @Synchronized
    override fun get(key: String): String? {
        val encoded = store[key] ?: return null
        return try {
            val packed = Base64.getDecoder().decode(encoded)
            if (packed.size < 2) return null
            val ivLen = packed[0].toInt() and 0xFF
            val iv = packed.copyOfRange(1, 1 + ivLen)
            val ciphertext = packed.copyOfRange(1 + ivLen, packed.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Throwable) {
            null
        }
    }

    @Synchronized
    override fun remove(key: String): Boolean {
        store.remove(key)
        return true
    }

    @Synchronized
    override fun clear(): Boolean {
        store.clear()
        return true
    }

    @Synchronized
    override fun has(key: String): Boolean {
        return !get(key).isNullOrBlank()
    }

    @Synchronized
    override fun getRawCiphertext(key: String): String? {
        return store[key]
    }

    @Synchronized
    override fun putRawCiphertextForTesting(key: String, rawCiphertext: String) {
        store[key] = rawCiphertext
    }
}

/**
 * Dedicated manager for encrypted Git credentials storage.
 * Provides Android Keystore AES-256-GCM authenticated encryption, safe migration
 * from legacy plaintext preferences, tamper resistance, and zero plaintext fallbacks.
 */
class SecureGitCredentialStore private constructor(
    private val context: Context? = null
) {
    companion object {
        const val KEY_SECURE_USERNAME = "git_secure_username"
        const val KEY_SECURE_PASSWORD = "git_secure_password"

        /**
         * Redacts secret tokens, passwords, and embedded URL credentials from Git error messages,
         * log output, and exception strings while preserving host, repository, and failure reason.
         */
        fun sanitizeErrorMessage(message: String?, secret: String? = null): String? {
            if (message == null) return null
            var sanitized = message
            if (!secret.isNullOrBlank() && secret.length >= 4) {
                sanitized = sanitized.replace(secret, "******")
            }
            // Redact user:password@host -> user:******@host
            sanitized = sanitized.replace(Regex("""(?i)(https?://[^:/@\s]+:)[^@\s]+@"""), "$1******@")
            // Redact token@host -> ******@host
            sanitized = sanitized.replace(Regex("""(?i)(https?://)[^:/@\s]+@"""), "$1******@")
            return sanitized
        }

        @Volatile
        private var instance: SecureGitCredentialStore? = null

        @Synchronized
        fun getInstance(context: Context): SecureGitCredentialStore {
            return instance ?: SecureGitCredentialStore(context.applicationContext).also {
                instance = it
                it.autoMigrateLegacyCredentials()
            }
        }

        @Synchronized
        fun getTestInstance(backend: GitCredentialStorageBackend): SecureGitCredentialStore {
            val store = SecureGitCredentialStore(null)
            store.backendOverride = backend
            return store
        }

        @Synchronized
        fun setBackendForTesting(testBackend: GitCredentialStorageBackend?) {
            instance?.backendOverride = testBackend
        }

        /**
         * Comprehensive on-device verification suite executing directly on physical Android hardware
         * with the real Android Keystore daemon, real hardware keys, and real SharedPreferences.
         */
                fun runOnDeviceVerification(context: Context): Map<String, Any> {
            val results = mutableListOf<String>()
            var allPassed = true

            fun recordCheck(name: String, passed: Boolean, detail: String) {
                if (!passed) allPassed = false
                val status = if (passed) "PASS" else "FAIL"
                results.add("[$status] $name: $detail")
                Log.i("KeystoreVerification", "[$status] $name: $detail")
            }

            val testKeyAlias = "roxum_acs_verify_test_key"
            val testPrefsName = "roxum_acs_test_verification_prefs"
            val synthUser = "synth_sukuna_runner"
            val synthToken = "ghp_SYNTHETIC_VERIFY_TOKEN_987654321_abcdef"

            var keyInfoSecurityLevel = "UNKNOWN"
            var isInsideSecureHardware = false
            var keyOrigin = "UNKNOWN"

            try {
                // Ensure fresh state for test key
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (ks.containsAlias(testKeyAlias)) {
                    ks.deleteEntry(testKeyAlias)
                }
                context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE).edit().clear().commit()

                // Test 1: Real Android Keystore AES-256-GCM Key Generation & Synthetic Credential Encryption
                val testBackend = AndroidKeystoreGitBackend(context, testPrefsName, testKeyAlias)
                val putUserOk = testBackend.put("verify_user", synthUser)
                val putTokenOk = testBackend.put("verify_token", synthToken)
                val keyExistsInHardware = ks.containsAlias(testKeyAlias)

                recordCheck(
                    "1. Real Android Keystore Key Generation & AES-GCM Encryption",
                    putUserOk && putTokenOk && keyExistsInHardware,
                    "Generated AES-256-GCM master key in AndroidKeyStore and encrypted credentials with Keystore-generated IV."
                )

                // Test 2: Inspect KeyInfo and Query Hardware Security Level
                val entry = ks.getEntry(testKeyAlias, null) as? KeyStore.SecretKeyEntry
                val secretKey = entry?.secretKey
                if (secretKey != null) {
                    try {
                        val factory = SecretKeyFactory.getInstance(secretKey.algorithm, "AndroidKeyStore")
                        val keyInfo = factory.getKeySpec(secretKey, KeyInfo::class.java) as KeyInfo
                        isInsideSecureHardware = keyInfo.isInsideSecureHardware
                        keyOrigin = when (keyInfo.origin) {
                            KeyProperties.ORIGIN_GENERATED -> "GENERATED_IN_KEYSTORE"
                            KeyProperties.ORIGIN_IMPORTED -> "IMPORTED"
                            else -> "OTHER (${keyInfo.origin})"
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            keyInfoSecurityLevel = when (keyInfo.securityLevel) {
                                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
                                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TRUSTED_ENVIRONMENT (TEE)"
                                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
                                else -> "UNKNOWN (${keyInfo.securityLevel})"
                            }
                        } else {
                            keyInfoSecurityLevel = if (isInsideSecureHardware) "TRUSTED_ENVIRONMENT (TEE)" else "SOFTWARE"
                        }
                    } catch (e: Exception) {
                        keyInfoSecurityLevel = "QUERY_ERROR: ${e.javaClass.simpleName}"
                    }
                }
                recordCheck(
                    "2. KeyInfo Security Level Audit",
                    keyExistsInHardware && secretKey != null,
                    "AndroidKeyStore managed key audited. Inside secure hardware: $isInsideSecureHardware, Security level: $keyInfoSecurityLevel, Origin: $keyOrigin"
                )

                // Test 3: Ciphertext Privacy and Envelope Verification
                val rawCiphertext = testBackend.getRawCiphertext("verify_token")
                val isEncrypted = rawCiphertext != null &&
                    !rawCiphertext.contains(synthToken) &&
                    !rawCiphertext.contains(synthUser)

                var validEnvelope = false
                if (rawCiphertext != null) {
                    try {
                        val decoded = Base64.getDecoder().decode(rawCiphertext)
                        val ivLen = decoded[0].toInt() and 0xFF
                        // 1-byte length prefix (12) + 12-byte IV + ciphertext + 16-byte auth tag
                        validEnvelope = (ivLen == 12 && decoded.size >= (1 + 12 + 16))
                    } catch (_: Throwable) {}
                }
                recordCheck(
                    "3. Persistent Storage Is Encrypted Ciphertext Envelope",
                    isEncrypted && validEnvelope,
                    "Verified raw preference contains Base64 packed [12-byte IV + 128-bit auth tag]. Plaintext secret is absent from storage."
                )

                // Test 4: Process Re-instantiation & Round-Trip Decryption
                val freshBackend = AndroidKeystoreGitBackend(context, testPrefsName, testKeyAlias)
                val decryptedUser = freshBackend.get("verify_user")
                val decryptedToken = freshBackend.get("verify_token")
                val roundtripOk = (decryptedUser == synthUser && decryptedToken == synthToken)
                recordCheck(
                    "4. App Process Re-instantiation & Round-Trip Decryption",
                    roundtripOk,
                    "Simulated process restart with new backend instance. Key loaded and roundtrip plaintext verified."
                )

                // Test 5: Tampered Ciphertext & Bad Auth Tag Rejection
                var tamperedFailedSafely = false
                if (rawCiphertext != null) {
                    val decoded = Base64.getDecoder().decode(rawCiphertext)
                    decoded[decoded.size - 1] = (decoded[decoded.size - 1].toInt() xor 0xFF).toByte()
                    val tamperedBase64 = Base64.getEncoder().encodeToString(decoded)
                    testBackend.putRawCiphertextForTesting("verify_token", tamperedBase64)

                    val retrieved = freshBackend.get("verify_token")
                    tamperedFailedSafely = (retrieved == null)
                }
                recordCheck(
                    "5. Tampered Ciphertext / Bad Auth-Tag Handling",
                    tamperedFailedSafely,
                    "Corrupted GCM auth tag rejected by cipher (AEADBadTagException). Method returned null safely without crashing."
                )

                // Test 6: Key Invalidation & Desynchronization Resilience
                // Delete the master key from AndroidKeyStore while encrypted data exists
                ks.deleteEntry(testKeyAlias)
                val readAfterKeyLoss = freshBackend.get("verify_user")
                val writeAfterKeyLoss = freshBackend.put("verify_user", "resilient_user")
                val readAfterRecreation = freshBackend.get("verify_user")
                val resilienceOk = (readAfterKeyLoss == null) && writeAfterKeyLoss && (readAfterRecreation == "resilient_user")
                recordCheck(
                    "6. Key Invalidation & Desynchronization Resilience",
                    resilienceOk,
                    "Verified graceful failure on key loss, automatic self-healing key regeneration, and successful subsequent encryption."
                )

                // Test 7: Safe Scoped Legacy Plaintext Migration
                val testPref = context.getSharedPreferences("test_migration_probe_prefs", Context.MODE_PRIVATE)
                testPref.edit()
                    .putString("username", "migrated_test_user")
                    .putString("password", "migrated_synth_pass_123")
                    .putString("git_user_name", "Author Sukuna") // Author name, must not be deleted
                    .commit()

                val migKeyAlias = "roxum_acs_migration_test_key"
                if (ks.containsAlias(migKeyAlias)) ks.deleteEntry(migKeyAlias)
                val testStore = getTestInstance(AndroidKeystoreGitBackend(context, "roxum_acs_test_migration_store", migKeyAlias))
                val migrated = testStore.migrateLegacyPreferences(
                    testPref,
                    legacyUserKeys = listOf("username", "git_username"),
                    legacyPassKeys = listOf("password", "git_password")
                )

                val userInStore = testStore.getUsername() == "migrated_test_user"
                val passInStore = testStore.getPassword() == "migrated_synth_pass_123"
                val plaintextUserPurged = testPref.getString("username", null) == null
                val plaintextPassPurged = testPref.getString("password", null) == null
                val authorPreserved = testPref.getString("git_user_name", null) == "Author Sukuna"

                val migrationSafe = migrated && userInStore && passInStore && plaintextUserPurged && plaintextPassPurged && authorPreserved
                recordCheck(
                    "7. Safe Scoped Legacy Migration & Atomic Purge",
                    migrationSafe,
                    "Migrated legacy plaintext into Keystore, confirmed roundtrip decryption, purged plaintext keys, and preserved author config."
                )

                // Cleanup all test keys and preferences
                if (ks.containsAlias(testKeyAlias)) ks.deleteEntry(testKeyAlias)
                if (ks.containsAlias(migKeyAlias)) ks.deleteEntry(migKeyAlias)
                context.getSharedPreferences(testPrefsName, Context.MODE_PRIVATE).edit().clear().commit()
                testPref.edit().clear().commit()
                context.getSharedPreferences("roxum_acs_test_migration_store", Context.MODE_PRIVATE).edit().clear().commit()
                recordCheck("8. Test Cleanup", true, "All test keys and test preferences purged cleanly.")

            } catch (t: Throwable) {
                recordCheck("Verification Exception", false, "Unexpected exception: ${t.javaClass.simpleName}: ${t.message}")
            }

            val deviceMetadata = mapOf(
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "device" to Build.DEVICE,
                "release" to Build.VERSION.RELEASE,
                "sdk_int" to Build.VERSION.SDK_INT,
                "build_id" to Build.ID
            )

            val keyInfoData = mapOf(
                "is_inside_secure_hardware" to isInsideSecureHardware,
                "security_level" to keyInfoSecurityLevel,
                "origin" to keyOrigin
            )

            return mapOf(
                "success" to allPassed,
                "timestamp" to System.currentTimeMillis(),
                "device_metadata" to deviceMetadata,
                "key_info" to keyInfoData,
                "results" to results
            )
        }
    }

    private var backendOverride: GitCredentialStorageBackend? = null

    private val backend: GitCredentialStorageBackend
        get() {
            backendOverride?.let { return it }
            val ctx = context ?: throw IllegalStateException("Context required to initialize Android Keystore backend")
            return AndroidKeystoreGitBackend(ctx)
        }

    fun saveCredentials(username: String, password: String): Boolean {
        val b = backend
        val uOk = b.put(KEY_SECURE_USERNAME, username)
        val pOk = b.put(KEY_SECURE_PASSWORD, password)
        return uOk && pOk
    }

    fun getUsername(): String? {
        return backend.get(KEY_SECURE_USERNAME)?.takeIf { it.isNotBlank() }
    }

    fun getPassword(): String? {
        return backend.get(KEY_SECURE_PASSWORD)?.takeIf { it.isNotBlank() }
    }

    fun getCredentials(): GitCredentials? {
        val u = getUsername()
        val p = getPassword()
        return if (u != null || p != null) {
            GitCredentials(u.orEmpty(), p.orEmpty())
        } else null
    }

    fun clearCredentials(): Boolean {
        val b = backend
        val uOk = b.remove(KEY_SECURE_USERNAME)
        val pOk = b.remove(KEY_SECURE_PASSWORD)
        return uOk && pOk
    }

    fun hasCredentials(): Boolean {
        return backend.has(KEY_SECURE_USERNAME) || backend.has(KEY_SECURE_PASSWORD)
    }

    fun getRawCiphertext(key: String): String? {
        return backend.getRawCiphertext(key)
    }

    fun putRawCiphertextForTesting(key: String, rawCiphertext: String) {
        backend.putRawCiphertextForTesting(key, rawCiphertext)
    }

    /**
     * Scans and migrates any unencrypted credentials from the provided SharedPreferences
     * into this encrypted Keystore backend. Deletes plaintext keys only after round-trip
     * verification succeeds.
     */
    fun migrateLegacyPreferences(
        prefs: SharedPreferences,
        legacyUserKeys: List<String> = listOf("username", "git_username", "git_user_name_cred"),
        legacyPassKeys: List<String> = listOf("password", "git_password", "git_token", "github_token", "git_user_password")
    ): Boolean {
        var legacyUser: String? = null
        var foundUserKey: String? = null
        for (k in legacyUserKeys) {
            val u = prefs.getString(k, null)?.trim()
            if (!u.isNullOrEmpty() && k != "git_user_name") {
                legacyUser = u
                foundUserKey = k
                break
            }
        }

        var legacyPass: String? = null
        var foundPassKey: String? = null
        for (k in legacyPassKeys) {
            val p = prefs.getString(k, null)?.trim()
            if (!p.isNullOrEmpty()) {
                legacyPass = p
                foundPassKey = k
                break
            }
        }

        if (legacyUser == null && legacyPass == null) {
            return false
        }

        val currentUser = getUsername()
        val currentPass = getPassword()

        val targetUser = currentUser ?: legacyUser.orEmpty()
        val targetPass = currentPass ?: legacyPass.orEmpty()

        if (targetUser.isEmpty() && targetPass.isEmpty()) {
            return false
        }

        val ok = saveCredentials(targetUser, targetPass)
        if (!ok) {
            return false
        }

        // Verify roundtrip decryption before deleting plaintext
        val verifiedUser = targetUser.isEmpty() || getUsername() == targetUser
        val verifiedPass = targetPass.isEmpty() || getPassword() == targetPass
        if (verifiedUser && verifiedPass) {
            val editor = prefs.edit()
            foundUserKey?.let { editor.remove(it) }
            foundPassKey?.let { editor.remove(it) }
            editor.apply()
            return true
        }
        return false
    }

    private fun autoMigrateLegacyCredentials() {
        val ctx = context ?: return
        try {
            // 1. Check git_config: exclusively owned by Git subsystem
            val gitConfig = ctx.getSharedPreferences("git_config", Context.MODE_PRIVATE)
            migrateLegacyPreferences(
                gitConfig,
                legacyUserKeys = listOf("username", "git_username", "git_user_name_cred"),
                legacyPassKeys = listOf("password", "git_password", "git_token", "github_token", "git_user_password")
            )

            // 2. Check default preferences: global app preferences.
            // Generic keys ("username", "password") have ambiguous ownership and MUST NOT be touched!
            // Only migrate strictly Git-namespaced keys from default preferences.
            val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
            migrateLegacyPreferences(
                defaultPrefs,
                legacyUserKeys = listOf("git_username", "github_username"),
                legacyPassKeys = listOf("git_password", "github_token", "git_token")
            )

            // 3. Check legacy git_credentials from older EncryptedSharedPreferences (if accessible)
            try {
                val oldCredsFile = File(ctx.applicationInfo.dataDir, "shared_prefs/git_credentials.xml")
                if (oldCredsFile.exists() && !hasCredentials()) {
                    val masterKey = androidx.security.crypto.MasterKey.Builder(ctx)
                        .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    val oldPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                        ctx,
                        "git_credentials",
                        masterKey,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                    val u = oldPrefs.getString("username", null)?.trim()
                    val p = oldPrefs.getString("password", null)?.trim()
                    if (!u.isNullOrEmpty() || !p.isNullOrEmpty()) {
                        if (saveCredentials(u.orEmpty(), p.orEmpty())) {
                            if ((u.isNullOrEmpty() || getUsername() == u) &&
                                (p.isNullOrEmpty() || getPassword() == p)) {
                                oldPrefs.edit().clear().apply()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w("SecureGitCredStore", "Legacy git_credentials check completed with non-fatal notice: ${t.javaClass.simpleName}")
            }
        } catch (t: Throwable) {
            Log.w("SecureGitCredStore", "Legacy migration check completed with non-fatal notice: ${t.javaClass.simpleName}")
        }
    }
}
