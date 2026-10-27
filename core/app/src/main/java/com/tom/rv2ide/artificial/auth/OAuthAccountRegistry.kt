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
    }

    @Synchronized
    fun getAllAccounts(): List<OAuthAccount> {
        return accounts.toList()
    }

    @Synchronized
    fun getAccounts(provider: String): List<OAuthAccount> {
        return accounts.filter { it.provider.equals(provider, ignoreCase = true) }
    }

    @Synchronized
    fun getActiveAccount(provider: String): OAuthAccount? {
        val list = getAccounts(provider)
        return list.firstOrNull { it.isActive } ?: list.firstOrNull()
    }

    @Synchronized
    fun setActiveAccount(provider: String, accountId: String) {
        for (i in accounts.indices) {
            val acc = accounts[i]
            if (acc.provider.equals(provider, ignoreCase = true)) {
                accounts[i] = acc.copy(isActive = (acc.id == accountId))
            }
        }
        val active = accounts.firstOrNull { it.id == accountId }
        if (active != null) {
            SecureKeyStore.putProviderApiKey(appContext, active.provider, active.accessToken)
        }
        saveToDisk()
    }

    @Synchronized
    fun saveAccount(account: OAuthAccount) {
        val existingIndex = accounts.indexOfFirst { it.id == account.id }
        if (existingIndex >= 0) {
            accounts[existingIndex] = account
        } else {
            // If this is the first account for this provider, mark as active
            val hasActive = accounts.any { it.provider.equals(account.provider, ignoreCase = true) && it.isActive }
            val toAdd = if (!hasActive) account.copy(isActive = true) else account
            accounts.add(toAdd)
        }
        val active = getActiveAccount(account.provider)
        if (active != null) {
            SecureKeyStore.putProviderApiKey(appContext, active.provider, active.accessToken)
        }
        saveToDisk()
    }

    @Synchronized
    fun removeAccount(accountId: String) {
        val removed = accounts.find { it.id == accountId }
        accounts.removeAll { it.id == accountId }
        if (removed != null && removed.isActive) {
            val next = accounts.firstOrNull { it.provider.equals(removed.provider, ignoreCase = true) }
            if (next != null) {
                setActiveAccount(next.provider, next.id)
            } else {
                SecureKeyStore.clearProviderApiKey(removed.provider)
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
        val now = System.currentTimeMillis()
        val list = getAccounts(provider)
        
        // Reset quota for accounts that have passed the cooldown window (e.g. 1 hour)
        for (i in accounts.indices) {
            val acc = accounts[i]
            if (acc.provider.equals(provider, ignoreCase = true) && acc.isQuotaExhausted) {
                if (now - acc.quotaExhaustedAt > 3600_000L) {
                    accounts[i] = acc.copy(isQuotaExhausted = false, quotaExhaustedAt = 0L)
                }
            }
        }

        val available = accounts.firstOrNull { 
            it.provider.equals(provider, ignoreCase = true) && !it.isQuotaExhausted 
        }

        if (available != null && !available.isActive) {
            setActiveAccount(provider, available.id)
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
