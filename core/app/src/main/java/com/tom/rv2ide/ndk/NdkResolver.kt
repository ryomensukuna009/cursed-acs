/*
 *  This file is part of AndroidCodeStudio (CURSED ACS).
 *  Dynamic NDK Resolver and Project Toolchain Configurator.
 */

package com.tom.rv2ide.ndk

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.IBuildSystemUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import org.slf4j.LoggerFactory

object NdkResolver {

  private val log = LoggerFactory.getLogger(NdkResolver::class.java)

  /**
   * Resolves the active NDK directory for a given project, checking:
   * 1. local.properties in projectRoot for `ndk.dir`
   * 2. Configured active offline NDK version from IBuildSystemUtils / PreferenceManager
   * 3. Valid installed NDKs in $ANDROID_HOME/ndk
   * 4. System / prefix fallbacks
   */
  fun findActiveNdkDir(projectRoot: File? = null): File? {
    // 1. Check local.properties in project
    if (projectRoot != null && projectRoot.exists()) {
      val localPropsFile = File(projectRoot, "local.properties")
      if (localPropsFile.exists() && localPropsFile.isFile) {
        try {
          val props = Properties()
          FileInputStream(localPropsFile).use { props.load(it) }
          val ndkDirProp = props.getProperty("ndk.dir")
          if (!ndkDirProp.isNullOrBlank()) {
            val candidate = File(ndkDirProp)
            if (isValidNdkDirectory(candidate)) {
              return candidate
            }
          }
        } catch (e: Exception) {
          log.warn("Failed to parse local.properties in {}: {}", projectRoot.name, e.message)
        }
      }
    }

    val ndkBaseDir = File(Environment.ANDROID_HOME, "ndk")
    if (!ndkBaseDir.exists() || !ndkBaseDir.isDirectory) {
      return null
    }

    // 2. Check active version chosen by user in IDE Configurations
    val selectedVersion = try {
      IBuildSystemUtils.getNdkVersion()
    } catch (_: Throwable) {
      null
    }

    if (!selectedVersion.isNullOrBlank()) {
      val candidate = File(ndkBaseDir, selectedVersion)
      if (isValidNdkDirectory(candidate)) {
        return candidate
      }
    }

    // 3. Scan all subdirectories in ndkBaseDir
    val candidates = ndkBaseDir.listFiles()?.filter { it.isDirectory && isValidNdkDirectory(it) } ?: emptyList()
    if (candidates.isNotEmpty()) {
      // Prefer aide_ndk_arm64 or android-ndk-aide, else newest
      val preferred = candidates.find { it.name == "aide_ndk_arm64" || it.name == "android-ndk-aide" || it.name.startsWith("aide_ndk") }
      return preferred ?: candidates.sortedBy { it.name }.last()
    }

    return null
  }

  /**
   * Verifies if a directory contains a usable NDK toolchain (either ndk-build, clang, or toolchains directory).
   */
  fun isValidNdkDirectory(dir: File): Boolean {
    if (!dir.exists() || !dir.isDirectory) return false
    val hasNdkBuild = File(dir, "ndk-build").exists()
    val hasClang = File(dir, "bin/clang").exists() || 
        File(dir, "toolchains/llvm/prebuilt/linux-arm64/bin/clang").exists() ||
        File(dir, "toolchains/llvm/prebuilt/linux-arm64/bin/clang++").exists()
    val hasToolchains = File(dir, "toolchains").exists() && File(dir, "platforms").exists()
    return hasNdkBuild || hasClang || hasToolchains
  }

  /**
   * Returns true if ANY usable NDK is installed.
   */
  fun isNdkInstalled(projectRoot: File? = null): Boolean {
    return findActiveNdkDir(projectRoot) != null
  }

  /**
   * Detects whether a project contains native build files.
   * Handles multi-module (app/src/main/jni), single-module (src/main/jni), legacy AIDE (jni/),
   * and standalone CMake projects.
   */
  fun hasNativeFiles(projectRoot: File): Boolean {
    if (!projectRoot.exists()) return false

    val candidatePaths = listOf(
        "app/src/main/jni/Android.mk",
        "app/src/main/jni/CMakeLists.txt",
        "src/main/jni/Android.mk",
        "src/main/jni/CMakeLists.txt",
        "jni/Android.mk",
        "jni/CMakeLists.txt",
        "CMakeLists.txt",
        "Android.mk"
    )

    for (rel in candidatePaths) {
      if (File(projectRoot, rel).exists()) {
        return true
      }
    }

    // Deep search up to 4 levels for any Android.mk or CMakeLists.txt
    return try {
      projectRoot.walkTopDown()
          .maxDepth(4)
          .any { file ->
            !file.path.contains("/.git/") &&
            !file.path.contains("/build/") &&
            !file.path.contains("/.gradle/") &&
            (file.name.equals("Android.mk", ignoreCase = true) || file.name.equals("CMakeLists.txt", ignoreCase = true))
          }
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Shows a friendly, accurate dialog when a project requires NDK but none is installed.
   */
  fun showNdkNotInstalledDialog(context: Context, onDismiss: () -> Unit = {}) {
    MaterialAlertDialogBuilder(context)
        .setTitle("Android NDK Required")
        .setMessage(
            "This project contains native C/C++ source code, but no valid Android NDK was found in:\n\n" +
                "${File(Environment.ANDROID_HOME, "ndk").absolutePath}\n\n" +
                "To enable native builds, please import an NDK archive via:\n" +
                "IDE Configurations > System > Android NDK > Import."
        )
        .setPositiveButton("OK") { dialog, _ ->
          dialog.dismiss()
          onDismiss()
        }
        .setCancelable(false)
        .show()
  }

  /**
   * Generates or safely updates local.properties for the target project
   * with correct sdk.dir and ndk.dir paths.
   */
  fun ensureLocalProperties(projectRoot: File, activeNdk: File? = findActiveNdkDir(projectRoot)) {
    if (!projectRoot.exists() || !projectRoot.isDirectory) return

    val localPropsFile = File(projectRoot, "local.properties")
    val props = Properties()

    if (localPropsFile.exists()) {
      try {
        FileInputStream(localPropsFile).use { props.load(it) }
      } catch (e: Exception) {
        log.warn("Could not read existing local.properties in {}: {}", projectRoot.name, e.message)
      }
    }

    var changed = false
    val sdkPath = Environment.ANDROID_HOME?.absolutePath
    if (sdkPath != null && props.getProperty("sdk.dir") != sdkPath) {
      props.setProperty("sdk.dir", sdkPath)
      changed = true
    }

    if (activeNdk != null && activeNdk.exists()) {
      val ndkPath = activeNdk.absolutePath
      if (props.getProperty("ndk.dir") != ndkPath) {
        props.setProperty("ndk.dir", ndkPath)
        changed = true
      }
    }

    if (changed || !localPropsFile.exists()) {
      try {
        FileOutputStream(localPropsFile).use { out ->
          props.store(out, "Generated and managed by CURSED ACS")
        }
        log.info("Updated local.properties in {} with sdk.dir and ndk.dir", projectRoot.name)
      } catch (e: Exception) {
        log.error("Failed to write local.properties in {}: {}", projectRoot.name, e.message)
      }
    }
  }

  /**
   * Returns environment variables for NDK builds.
   */
  fun getNdkEnvironment(activeNdk: File? = findActiveNdkDir()): Map<String, String> {
    val env = mutableMapOf<String, String>()
    if (activeNdk != null && activeNdk.exists()) {
      env["ANDROID_NDK_ROOT"] = activeNdk.absolutePath
      env["ANDROID_NDK_HOME"] = activeNdk.absolutePath
      env["NDK_ROOT"] = activeNdk.absolutePath
      env["NDK"] = activeNdk.absolutePath
    }
    return env
  }
}
