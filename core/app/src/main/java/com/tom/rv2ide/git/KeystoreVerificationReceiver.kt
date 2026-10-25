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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Process
import android.util.Log
import com.tom.rv2ide.BuildConfig
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Diagnostic BroadcastReceiver executing on-device Android Keystore hardware verification.
 * Hardened with multi-layer defense:
 * 1. Packaged exclusively via src/debug/AndroidManifest.xml (completely omitted from release APKs).
 * 2. Runtime guard restricting execution strictly to BuildConfig.DEBUG.
 * 3. Caller UID guard permitting only ADB shell (UID 2000), root (UID 0), or the app's own process UID.
 * 4. Complete isolation using synthetic test keys and preferences, never touching actual user Git credentials.
 */
class KeystoreVerificationReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "KeystoreVerification"
        const val ACTION_VERIFY = "com.tom.rv2ide.git.ACTION_VERIFY_KEYSTORE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_VERIFY) return

        // Guard 1: Restrict to debug builds only
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "Rejected verification request: KeystoreVerificationReceiver is disabled in production builds.")
            setResultCode(2)
            setResultData("DISABLED_IN_PRODUCTION")
            return
        }

        // Guard 2: Restrict to ADB shell (UID 2000), root (UID 0), or self
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.myUid() && callingUid != 2000 && callingUid != 0) {
            Log.w(TAG, "Rejected verification request: Calling UID $callingUid is not authorized.")
            setResultCode(3)
            setResultData("UNAUTHORIZED_CALLER")
            return
        }

        Log.i(TAG, "Starting authorized on-device Android Keystore verification on physical hardware (caller UID: $callingUid)...")

        val resultMap = SecureGitCredentialStore.runOnDeviceVerification(context)
        val devMeta = resultMap["device_metadata"] as? Map<*, *>
        val keyInfoMeta = resultMap["key_info"] as? Map<*, *>

        val json = JSONObject().apply {
            put("success", resultMap["success"])
            put("timestamp", resultMap["timestamp"])
            if (devMeta != null) {
                put("device_metadata", JSONObject(devMeta))
            }
            if (keyInfoMeta != null) {
                put("key_info", JSONObject(keyInfoMeta))
            }
            val arr = JSONArray()
            @Suppress("UNCHECKED_CAST")
            (resultMap["results"] as? List<String>)?.forEach { arr.put(it) }
            put("results", arr)
        }.toString(2)

        Log.i(TAG, "Verification completed with result:\n$json")

        try {
            val outFile = File(context.getExternalFilesDir(null), "keystore_verification_result.json")
            outFile.writeText(json)
            Log.i(TAG, "Result written to: ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write result file: ${e.message}")
        }

        setResultCode(if (resultMap["success"] == true) 0 else 1)
        setResultData(json)
    }
}
