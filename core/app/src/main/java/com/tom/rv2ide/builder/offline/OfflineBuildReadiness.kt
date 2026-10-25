/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Code Studio License as published by
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

import android.content.Context
import com.tom.rv2ide.managers.ToolsManager
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.GeneralFileUtils
import java.io.File

data class ReadinessStatus(
    val isReady: Boolean,
    val missingComponents: List<String>,
    val summary: String
)

object OfflineBuildReadiness {

  fun checkReadiness(context: Context?): ReadinessStatus {
    val missing = mutableListOf<String>()

    // 1. Check AAPT2
    var aapt2 = Environment.AAPT2
    if (aapt2 == null || !aapt2.exists() || aapt2.length() == 0L) {
      ToolsManager.extractAapt2()
      aapt2 = Environment.AAPT2
    }
    if (aapt2 == null || !aapt2.exists() || aapt2.length() == 0L) {
      missing.add("AAPT2 resource binary (libaapt2.so)")
    }

    // 2. Check android.jar
    var androidJar = Environment.ANDROID_JAR
    if (androidJar == null || !androidJar.exists() || androidJar.isDirectory || androidJar.length() == 0L) {
      ToolsManager.extractAndroidJar()
      androidJar = Environment.ANDROID_JAR
    }
    if (androidJar == null || !androidJar.exists() || androidJar.length() == 0L) {
      missing.add("Android platform JAR (android.jar)")
    }

    // 3. Check javac (Supports external OpenJDK 17 or bundled in-process OpenJDK compiler)
    val javacFile = File(Environment.JAVA_HOME, "bin/javac")
    val javacPrefix = File(Environment.PREFIX, "bin/javac")
    val hasInProcessJavac = try {
      Class.forName("openjdk.tools.javac.Main") != null
    } catch (_: Throwable) {
      false
    }
    if (!javacFile.exists() && !javacPrefix.exists() && !hasInProcessJavac) {
      missing.add("Java compiler (javac) from OpenJDK 17")
    }

    // 4. Check in-process D8
    try {
      Class.forName("com.android.tools.r8.D8")
    } catch (_: Throwable) {
      missing.add("Bundled D8 bytecode engine (com.android.tools:r8)")
    }

    // 5. Check in-process ApkSigner
    try {
      Class.forName("com.android.apksig.ApkSigner")
    } catch (_: Throwable) {
      missing.add("Bundled APK signing engine (com.android.tools.build:apksig)")
    }

    // 6. Check NDK (notice: projects with native code need NDK; we check if at least one NDK is imported)
    val ndkBaseDir = File(Environment.HOME, "android-sdk/ndk")
    val availableNdks = GeneralFileUtils.listDirsInDirectory(ndkBaseDir)
    val hasValidNdk = availableNdks.any { ndkDir ->
      File(ndkDir, "ndk-build").exists()
    }
    if (!hasValidNdk) {
      missing.add("Imported AIDE Plus NDK (please import in IDE Configurations > NDK)")
    }

    val isReady = missing.isEmpty()
    val summary = if (isReady) {
      "All offline builder tools and libraries are installed and ready."
    } else {
      "Offline builder is not ready. Missing components:\n" +
          missing.joinToString("\n") { "• $it" }
    }

    return ReadinessStatus(isReady, missing, summary)
  }
}
