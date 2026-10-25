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
package com.tom.rv2ide.utils

import android.content.Context
import android.content.SharedPreferences
import com.tom.rv2ide.git.SecureGitCredentialStore

/**
 * Manages Git author configuration (author name/email) in private preferences
 * and coordinates hardware-backed AES-256-GCM Git credentials encryption via
 * [SecureGitCredentialStore].
 *
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
class PreferencesManager(context: Context) {

    private val normalPrefs: SharedPreferences =
        context.getSharedPreferences("git_config", Context.MODE_PRIVATE)

    private val secureStore: SecureGitCredentialStore =
        SecureGitCredentialStore.getInstance(context)

    init {
        // Automatically migrate any legacy unencrypted credentials stored in git_config
        secureStore.migrateLegacyPreferences(normalPrefs)
    }

    // Git Config (Author metadata)
    fun setGitUserName(name: String) {
        normalPrefs.edit().putString("git_user_name", name).apply()
    }

    fun getGitUserName(): String {
        return normalPrefs.getString("git_user_name", "User") ?: "User"
    }

    fun setGitUserEmail(email: String) {
        normalPrefs.edit().putString("git_user_email", email).apply()
    }

    fun getGitUserEmail(): String {
        return normalPrefs.getString("git_user_email", "user@example.com") ?: "user@example.com"
    }

    // Credentials
    fun setRememberCredentials(remember: Boolean) {
        normalPrefs.edit().putBoolean("remember_credentials", remember).apply()
        if (!remember) {
            clearCredentials()
        }
    }

    fun shouldRememberCredentials(): Boolean {
        return normalPrefs.getBoolean("remember_credentials", false)
    }

    fun saveCredentials(username: String, password: String) {
        if (shouldRememberCredentials()) {
            secureStore.saveCredentials(username, password)
        }
    }

    fun getUsername(): String? {
        return if (shouldRememberCredentials()) {
            secureStore.getUsername()
        } else null
    }

    fun getPassword(): String? {
        return if (shouldRememberCredentials()) {
            secureStore.getPassword()
        } else null
    }

    fun clearCredentials() {
        secureStore.clearCredentials()
    }
}
