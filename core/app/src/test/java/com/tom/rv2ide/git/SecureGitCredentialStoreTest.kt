package com.tom.rv2ide.git

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class SecureGitCredentialStoreTest {

    private lateinit var testBackend: JvmAesGcmGitBackend
    private lateinit var credentialStore: SecureGitCredentialStore

    @Before
    fun setUp() {
        testBackend = JvmAesGcmGitBackend()
        credentialStore = SecureGitCredentialStore.getTestInstance(testBackend)
    }

    @After
    fun tearDown() {
        SecureGitCredentialStore.setBackendForTesting(null)
    }

    @Test
    fun testSaveAndRetrieveCredentialsRoundTrip() {
        val username = "sukuna-developer"
        val token = "ghp_1234567890abcdefghijklmnopqrstuvwxyz"

        val saved = credentialStore.saveCredentials(username, token)
        assertTrue(saved)
        assertTrue(credentialStore.hasCredentials())

        assertEquals(username, credentialStore.getUsername())
        assertEquals(token, credentialStore.getPassword())

        val creds = credentialStore.getCredentials()
        assertNotNull(creds)
        assertEquals(username, creds?.username)
        assertEquals(token, creds?.tokenOrPassword)
    }

    @Test
    fun testLongGitHubFineGrainedToken() {
        val username = "git-bot"
        val fineGrainedPat = "github_pat_11AABCDEF0123456789_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_long_token"

        assertTrue(credentialStore.saveCredentials(username, fineGrainedPat))
        assertEquals(fineGrainedPat, credentialStore.getPassword())
    }

    @Test
    fun testSpecialCharactersInCredentials() {
        val username = "complex+user@example.com"
        val complexSecret = "P@$$\"w0rd!#%^&*()_+-=[]{}|;':,.<>/?~`\uD83D\uDD12"

        assertTrue(credentialStore.saveCredentials(username, complexSecret))
        assertEquals(username, credentialStore.getUsername())
        assertEquals(complexSecret, credentialStore.getPassword())
    }

    @Test
    fun testClearCredentials() {
        credentialStore.saveCredentials("john_doe", "ghp_my_secret_token")
        assertTrue(credentialStore.hasCredentials())

        val cleared = credentialStore.clearCredentials()
        assertTrue(cleared)
        assertFalse(credentialStore.hasCredentials())
        assertNull(credentialStore.getUsername())
        assertNull(credentialStore.getPassword())
        assertNull(credentialStore.getCredentials())
    }

    @Test
    fun testCiphertextIsEncryptedAndNotPlaintext() {
        val plainPassword = "super_secret_github_token_xyz"
        credentialStore.saveCredentials("dev", plainPassword)

        val rawCiphertext = credentialStore.getRawCiphertext(SecureGitCredentialStore.KEY_SECURE_PASSWORD)
        assertNotNull(rawCiphertext)
        assertFalse(
            "Plaintext secret was leaked in storage!",
            rawCiphertext!!.contains(plainPassword)
        )

        // Verify valid Base64 payload structure
        val decoded = Base64.getDecoder().decode(rawCiphertext)
        assertTrue(decoded.size > 1 + 12 + 16) // IV len (1) + IV (12) + Tag (16)
        assertEquals(12, decoded[0].toInt()) // 12-byte IV
    }

    @Test
    fun testTamperedCiphertextFailsGracefullyWithoutCrash() {
        val plainPassword = "tamper_proof_secret"
        credentialStore.saveCredentials("dev", plainPassword)

        val rawCiphertext = credentialStore.getRawCiphertext(SecureGitCredentialStore.KEY_SECURE_PASSWORD)!!
        val bytes = Base64.getDecoder().decode(rawCiphertext)

        // Flip bits in the ciphertext / tag region
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        val corruptedBase64 = Base64.getEncoder().encodeToString(bytes)

        // Inject corrupted raw ciphertext directly into the storage
        testBackend.putRawCiphertextForTesting(SecureGitCredentialStore.KEY_SECURE_PASSWORD, corruptedBase64)

        // Must fail gracefully and return null without throwing exception
        val retrieved = credentialStore.getPassword()
        assertNull(retrieved)
    }

    @Test
    fun testLegacyPreferencesMigration() {
        val inMemoryPrefs = InMemoryTestSharedPreferences()
        inMemoryPrefs.edit()
            .putString("username", "legacy_user")
            .putString("password", "legacy_plaintext_secret_123")
            .putString("git_user_name", "Author Name") // commit author, should be preserved
            .apply()

        val migrated = credentialStore.migrateLegacyPreferences(inMemoryPrefs)
        assertTrue("Expected legacy credentials to be migrated", migrated)

        // Verify secure store received credentials
        assertEquals("legacy_user", credentialStore.getUsername())
        assertEquals("legacy_plaintext_secret_123", credentialStore.getPassword())

        // Verify plaintext was purged from unencrypted preferences
        assertNull(inMemoryPrefs.getString("username", null))
        assertNull(inMemoryPrefs.getString("password", null))

        // Verify commit author name was NOT deleted
        assertEquals("Author Name", inMemoryPrefs.getString("git_user_name", null))
    }

    @Test
    fun testScopedMigrationDoesNotTouchUnrelatedAppPreferences() {
        val globalAppPrefs = InMemoryTestSharedPreferences()
        globalAppPrefs.edit()
            .putString("username", "account_holder")
            .putString("password", "account_password")
            .putString("ide_theme", "darcula")
            .apply()

        // When scanning global default preferences with explicit git scopes:
        val migrated = credentialStore.migrateLegacyPreferences(
            globalAppPrefs,
            legacyUserKeys = listOf("git_username", "github_username"),
            legacyPassKeys = listOf("git_password", "github_token", "git_token")
        )

        assertFalse("Unrelated global preferences must not be migrated", migrated)
        // Verify unrelated keys are untouched
        assertEquals("account_holder", globalAppPrefs.getString("username", null))
        assertEquals("account_password", globalAppPrefs.getString("password", null))
        assertEquals("darcula", globalAppPrefs.getString("ide_theme", null))
    }

    @Test
    fun testMigrationFailurePreservesOriginalPlaintextWithoutDeletion() {
        // Backend that fails on put
        val failingBackend = object : GitCredentialStorageBackend {
            override fun put(key: String, value: String): Boolean = false
            override fun get(key: String): String? = null
            override fun remove(key: String): Boolean = true
            override fun clear(): Boolean = true
            override fun has(key: String): Boolean = false
            override fun getRawCiphertext(key: String): String? = null
            override fun putRawCiphertextForTesting(key: String, rawCiphertext: String) {}
        }
        val failingStore = SecureGitCredentialStore.getTestInstance(failingBackend)

        val targetPrefs = InMemoryTestSharedPreferences()
        targetPrefs.edit()
            .putString("username", "important_user")
            .putString("password", "important_pass")
            .apply()

        val migrated = failingStore.migrateLegacyPreferences(targetPrefs)
        assertFalse("Migration must report failure when storage write fails", migrated)

        // Plaintext MUST remain intact because write failed
        assertEquals("important_user", targetPrefs.getString("username", null))
        assertEquals("important_pass", targetPrefs.getString("password", null))
    }

    @Test
    fun testSanitizeErrorMessageRemovesSecretAndBothUrlCredentialFormats() {
        val secret = "ghp_sensitiveTokenABC123"

        // Format 1: user:token@host -> user:******@host
        val rawError1 = "fatal: Authentication failed for 'https://myuser:ghp_sensitiveTokenABC123@github.com/org/repo.git'"
        val sanitized1 = SecureGitCredentialStore.sanitizeErrorMessage(rawError1, secret)

        assertNotNull(sanitized1)
        assertFalse(sanitized1!!.contains(secret))
        assertTrue(sanitized1.contains("https://myuser:******@github.com/org/repo.git"))

        // Format 2: token@host (token-only URL) -> ******@host
        val rawError2 = "fatal: repository 'https://ghp_sensitiveTokenABC123@github.com/org/repo.git/' not found"
        val sanitized2 = SecureGitCredentialStore.sanitizeErrorMessage(rawError2, secret)

        assertNotNull(sanitized2)
        assertFalse(sanitized2!!.contains(secret))
        assertTrue(sanitized2.contains("https://******@github.com/org/repo.git/"))

        // Format 3: Meaningful failure details and host are preserved
        assertTrue(sanitized2.contains("github.com/org/repo.git/"))
        assertTrue(sanitized2.contains("not found"))
    }
}

/**
 * Lightweight in-memory SharedPreferences for JVM unit testing of migration logic.
 */
class InMemoryTestSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(data)
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String> ?: defValues)
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = EditorImpl()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class EditorImpl : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) temp[key] = values
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) temp[key] = null
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) data.clear()
            for ((k, v) in temp) {
                if (v == null) data.remove(k) else data[k] = v
            }
            temp.clear()
        }
    }
}
