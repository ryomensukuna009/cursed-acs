package com.tom.rv2ide.artificial.auth

import android.content.Context
import com.tom.rv2ide.artificial.secrets.SecureKeyStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class OAuthAccount(
    val id: String = UUID.randomUUID().toString(),
    val provider: String, // "chatgpt", "claude", "grok", "kimi", "gemini"
    val email: String = "",
    val plan: String = "",
    val accessToken: String,
    val refreshToken: String = "",
    val expiresAt: Long = 0L,
    var isQuotaExhausted: Boolean = false,
    var quotaExhaustedAt: Long = 0L,
    var usageSummary: String = "",
    var isActive: Boolean = false,
    val accountId: String? = null,
    val organizationId: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("provider", provider)
        put("email", email)
        put("plan", plan)
        put("accessToken", accessToken)
        put("refreshToken", refreshToken)
        put("expiresAt", expiresAt)
        put("isQuotaExhausted", isQuotaExhausted)
        put("quotaExhaustedAt", quotaExhaustedAt)
        put("usageSummary", usageSummary)
        put("isActive", isActive)
        put("accountId", accountId ?: "")
        put("organizationId", organizationId ?: "")
    }

    companion object {
        fun fromJson(obj: JSONObject): OAuthAccount {
            return OAuthAccount(
                id = obj.optString("id", UUID.randomUUID().toString()),
                provider = obj.optString("provider", ""),
                email = obj.optString("email", ""),
                plan = obj.optString("plan", ""),
                accessToken = obj.optString("accessToken", ""),
                refreshToken = obj.optString("refreshToken", ""),
                expiresAt = obj.optLong("expiresAt", 0L),
                isQuotaExhausted = obj.optBoolean("isQuotaExhausted", false),
                quotaExhaustedAt = obj.optLong("quotaExhaustedAt", 0L),
                usageSummary = obj.optString("usageSummary", ""),
                isActive = obj.optBoolean("isActive", false),
                accountId = obj.optString("accountId", "").takeIf { it.isNotBlank() },
                organizationId = obj.optString("organizationId", "").takeIf { it.isNotBlank() }
            )
        }
    }
}

class OAuthAccountRegistry private constructor(private val appContext: Context) {

    private val accountsFile = File(appContext.filesDir, "oauth_accounts.json")
    private val accounts = mutableListOf<OAuthAccount>()

    init {
        loadFromDisk()
    }

    companion object {
        @Volatile
        private var instance: OAuthAccountRegistry? = null

        fun getInstance(context: Context): OAuthAccountRegistry {
            return instance ?: synchronized(this) {
                instance ?: OAuthAccountRegistry(context.applicationContext ?: context).also {
                    instance = it
                }
            }
        }

        fun canonicalProvider(provider: String): String {
            return when (provider.lowercase().trim()) {
                "chatgpt", "openai" -> "openai"
                "claude", "anthropic" -> "claude"
                "grok", "xai" -> "grok"
                "kimi", "kimi code", "moonshot" -> "kimi"
                "gemini", "google" -> "gemini"
                else -> provider.lowercase().trim()
            }
        }

        fun providerAliases(provider: String): List<String> {
            return when (canonicalProvider(provider)) {
                "openai" -> listOf("openai", "chatgpt")
                "claude" -> listOf("claude", "anthropic")
                "grok" -> listOf("grok", "xai")
                "kimi" -> listOf("kimi", "moonshot")
                "gemini" -> listOf("gemini", "google")
                else -> listOf(provider.lowercase().trim())
            }
        }
    }

    private fun syncActiveAccountWithKeyStore(account: OAuthAccount) {
        val aliases = providerAliases(account.provider)
        for (alias in aliases) {
            SecureKeyStore.putProviderApiKey(appContext, alias, account.accessToken)
        }
    }

    @Synchronized
    fun getAllAccounts(): List<OAuthAccount> {
        return accounts.toList()
    }

    @Synchronized
    fun getAccounts(provider: String): List<OAuthAccount> {
        val target = canonicalProvider(provider)
        return accounts.filter { canonicalProvider(it.provider) == target }
    }

    @Synchronized
    fun getActiveAccount(provider: String): OAuthAccount? {
        val list = getAccounts(provider)
        return list.firstOrNull { it.isActive } ?: list.firstOrNull()
    }

    @Synchronized
    fun setActiveAccount(provider: String, accountId: String) {
        val target = canonicalProvider(provider)
        for (i in accounts.indices) {
            val acc = accounts[i]
            if (canonicalProvider(acc.provider) == target) {
                accounts[i] = acc.copy(isActive = (acc.id == accountId))
            }
        }
        val active = accounts.firstOrNull { it.id == accountId }
        if (active != null) {
            syncActiveAccountWithKeyStore(active)
        }
        saveToDisk()
    }

    @Synchronized
    fun saveAccount(account: OAuthAccount) {
        val canonical = canonicalProvider(account.provider)
        val normalized = account.copy(provider = canonical)
        val existingIndex = accounts.indexOfFirst { it.id == normalized.id }
        if (existingIndex >= 0) {
            accounts[existingIndex] = normalized
        } else {
            // If this is the first account for this provider, mark as active
            val hasActive = accounts.any { canonicalProvider(it.provider) == canonical && it.isActive }
            val toAdd = if (!hasActive) normalized.copy(isActive = true) else normalized
            accounts.add(toAdd)
        }
        val active = getActiveAccount(canonical)
        if (active != null) {
            syncActiveAccountWithKeyStore(active)
        }
        saveToDisk()
    }

    @Synchronized
    fun removeAccount(accountId: String) {
        val removed = accounts.find { it.id == accountId }
        accounts.removeAll { it.id == accountId }
        if (removed != null) {
            val canonical = canonicalProvider(removed.provider)
            val next = accounts.firstOrNull { canonicalProvider(it.provider) == canonical }
            if (next != null) {
                setActiveAccount(canonical, next.id)
            } else {
                for (alias in providerAliases(canonical)) {
                    SecureKeyStore.clearProviderApiKey(alias)
                }
            }
        }
        saveToDisk()
    }

    @Synchronized
    fun markQuotaExhausted(accountId: String, resetDurationMs: Long = 3600_000L) {
        val idx = accounts.indexOfFirst { it.id == accountId }
        if (idx >= 0) {
            val acc = accounts[idx]
            accounts[idx] = acc.copy(
                isQuotaExhausted = true,
                quotaExhaustedAt = System.currentTimeMillis()
            )
            saveToDisk()
        }
    }

    @Synchronized
    fun getNextAvailableAccount(provider: String): OAuthAccount? {
        val target = canonicalProvider(provider)
        val now = System.currentTimeMillis()

        // Reset quota for accounts that have passed the cooldown window (e.g. 1 hour)
        for (i in accounts.indices) {
            val acc = accounts[i]
            if (canonicalProvider(acc.provider) == target && acc.isQuotaExhausted) {
                if (now - acc.quotaExhaustedAt > 3600_000L) {
                    accounts[i] = acc.copy(isQuotaExhausted = false, quotaExhaustedAt = 0L)
                }
            }
        }

        val available = accounts.firstOrNull { 
            canonicalProvider(it.provider) == target && !it.isQuotaExhausted 
        }

        if (available != null && !available.isActive) {
            setActiveAccount(target, available.id)
        }
        return available
    }

    private fun loadFromDisk() {
        accounts.clear()
        if (!accountsFile.exists()) return
        try {
            val text = accountsFile.readText()
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                accounts.add(OAuthAccount.fromJson(obj))
            }
            // Sync all active accounts into SecureKeyStore immediately
            for (acc in accounts.filter { it.isActive }) {
                syncActiveAccountWithKeyStore(acc)
            }
        } catch (_: Exception) {}
    }

    private fun saveToDisk() {
        try {
            val arr = JSONArray()
            for (acc in accounts) {
                arr.put(acc.toJson())
            }
            accountsFile.writeText(arr.toString(2))
        } catch (_: Exception) {}
    }
}
