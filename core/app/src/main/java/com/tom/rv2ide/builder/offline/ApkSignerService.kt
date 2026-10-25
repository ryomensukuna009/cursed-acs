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

package com.tom.rv2ide.builder.offline

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

class ApkSignerService {

  companion object {
    const val KEYSTORE_PASS = "android"
    const val KEY_ALIAS = "androiddebugkey"
    const val KEY_PASS = "android"
    const val KEYSTORE_FILENAME = "offline_debug.keystore"
  }

  fun signAndVerify(
      inputApk: File,
      outputApk: File,
      minSdkVersion: Int = 21,
      listener: OfflineBuildProgressListener? = null
  ): OfflineStageResult {
    val startTime = System.currentTimeMillis()

    if (!inputApk.exists() || inputApk.length() == 0L) {
      return OfflineStageResult(
          stage = OfflineBuildStage.SIGN,
          success = false,
          message = "Input APK file not found or empty: ${inputApk.absolutePath}",
          durationMs = System.currentTimeMillis() - startTime
      )
    }

    // 1. Ensure debug keystore is present
    val keystoreFile = getOrCreateDebugKeystore(listener)
    if (keystoreFile == null || !keystoreFile.exists() || keystoreFile.length() == 0L) {
      return OfflineStageResult(
          stage = OfflineBuildStage.SIGN,
          success = false,
          message = "Failed to obtain or generate offline debug keystore.",
          durationMs = System.currentTimeMillis() - startTime
      )
    }

    listener?.onStageLog(
        OfflineBuildStage.SIGN,
        "Signing APK using in-process apksig (v1, v2, v3 schemes) with keystore ${keystoreFile.name}..."
    )

    // 2. Load private key and certificates
    val (privateKey, certChain) = try {
      loadSigningKey(keystoreFile)
    } catch (e: Exception) {
      return OfflineStageResult(
          stage = OfflineBuildStage.SIGN,
          success = false,
          message = "Failed to load signing credentials from keystore: ${e.message}",
          durationMs = System.currentTimeMillis() - startTime,
          details = e.stackTraceToString()
      )
    }

    // 3. Perform signing
    try {
      if (outputApk.exists()) {
        outputApk.delete()
      }
      outputApk.parentFile?.mkdirs()

      val signerConfig = ApkSigner.SignerConfig.Builder(
          "debug",
          privateKey,
          certChain
      ).build()

      val signer = ApkSigner.Builder(listOf(signerConfig))
          .setInputApk(inputApk)
          .setOutputApk(outputApk)
          .setV1SigningEnabled(true)
          .setV2SigningEnabled(true)
          .setV3SigningEnabled(true)
          .setMinSdkVersion(minSdkVersion)
          .build()

      signer.sign()
    } catch (e: Exception) {
      return OfflineStageResult(
          stage = OfflineBuildStage.SIGN,
          success = false,
          message = "ApkSigner failed to sign APK: ${e.message}",
          durationMs = System.currentTimeMillis() - startTime,
          details = e.stackTraceToString()
      )
    }

    // 4. Verify signed APK with ApkVerifier
    listener?.onStageLog(OfflineBuildStage.VERIFY, "Verifying signed APK cryptographic signatures...")
    val verifier = ApkVerifier.Builder(outputApk)
        .setMinCheckedPlatformVersion(minSdkVersion)
        .build()
    val verifyResult = try {
      verifier.verify()
    } catch (e: Exception) {
      return OfflineStageResult(
          stage = OfflineBuildStage.VERIFY,
          success = false,
          message = "ApkVerifier exception: ${e.message}",
          durationMs = System.currentTimeMillis() - startTime,
          details = e.stackTraceToString()
      )
    }

    if (!verifyResult.isVerified) {
      val errors = verifyResult.errors.joinToString("; ") { "${it.issueId}: $it" }
      return OfflineStageResult(
          stage = OfflineBuildStage.VERIFY,
          success = false,
          message = "APK signature verification failed: $errors",
          durationMs = System.currentTimeMillis() - startTime
      )
    }

    val signerCerts = verifyResult.signerCertificates
    listener?.onStageLog(
        OfflineBuildStage.VERIFY,
        "APK verified successfully: v1=${verifyResult.isVerifiedUsingV1Scheme}, v2=${verifyResult.isVerifiedUsingV2Scheme}, v3=${verifyResult.isVerifiedUsingV3Scheme}. Signers: ${signerCerts.size}."
    )

    return OfflineStageResult(
        stage = OfflineBuildStage.SIGN,
        success = true,
        message = "APK signed and verified successfully (${outputApk.length()} bytes).",
        durationMs = System.currentTimeMillis() - startTime
    )
  }

  fun getOrCreateDebugKeystore(listener: OfflineBuildProgressListener? = null): File? {
    val targetDir = Environment.ANDROIDIDE_HOME ?: File(System.getProperty("user.home"), ".androidide")
    targetDir.mkdirs()
    val keystoreFile = File(targetDir, KEYSTORE_FILENAME)

    if (keystoreFile.exists() && keystoreFile.length() > 0L) {
      return keystoreFile
    }

    // Attempt 1: Copy bundled asset from APK assets
    try {
      val app = BaseApplication.getBaseInstance()
      if (app != null) {
        val assetManager = app.assets
        assetManager.open(KEYSTORE_FILENAME).use { input ->
          FileOutputStream(keystoreFile).use { output ->
            input.copyTo(output)
          }
        }
        if (keystoreFile.exists() && keystoreFile.length() > 0L) {
          listener?.onStageLog(OfflineBuildStage.SIGN, "Extracted bundled debug keystore from application assets.")
          return keystoreFile
        }
      }
    } catch (_: Exception) {
      // Asset not found or not in Android runtime (e.g. running unit tests)
    }

    // Attempt 2: Generate keystore using keytool binary if available
    val keytoolBin = File(Environment.JAVA_HOME, "bin/keytool")
    if (keytoolBin.exists() && keytoolBin.canExecute()) {
      listener?.onStageLog(OfflineBuildStage.SIGN, "Generating new debug keystore using keytool...")
      val cmd = listOf(
          keytoolBin.absolutePath,
          "-genkeypair",
          "-alias", KEY_ALIAS,
          "-keypass", KEY_PASS,
          "-keystore", keystoreFile.absolutePath,
          "-storepass", KEYSTORE_PASS,
          "-dname", "CN=Android Debug,O=Android,C=US",
          "-keyalg", "RSA",
          "-keysize", "2048",
          "-validity", "10000",
          "-storetype", "PKCS12"
      )
      val proc = ProcessBuilder(cmd).start()
      proc.waitFor()
      if (keystoreFile.exists() && keystoreFile.length() > 0L) {
        return keystoreFile
      }
    }

    return if (keystoreFile.exists() && keystoreFile.length() > 0L) keystoreFile else null
  }

  private fun loadSigningKey(keystoreFile: File): Pair<PrivateKey, List<X509Certificate>> {
    val ks = try {
      KeyStore.getInstance("PKCS12").apply {
        FileInputStream(keystoreFile).use { fis -> load(fis, KEYSTORE_PASS.toCharArray()) }
      }
    } catch (_: Exception) {
      // Fallback to JKS
      KeyStore.getInstance("JKS").apply {
        FileInputStream(keystoreFile).use { fis -> load(fis, KEYSTORE_PASS.toCharArray()) }
      }
    }

    val privateKey = ks.getKey(KEY_ALIAS, KEY_PASS.toCharArray()) as? PrivateKey
        ?: throw IllegalStateException("Key alias '$KEY_ALIAS' not found in keystore")

    val chain = ks.getCertificateChain(KEY_ALIAS)
        ?: throw IllegalStateException("Certificate chain for alias '$KEY_ALIAS' not found")

    val certChain = chain.map { it as X509Certificate }
    return Pair(privateKey, certChain)
  }
}
