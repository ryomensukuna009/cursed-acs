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

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Inspects a project directory and validates it against the Offline NDK Builder contract.
 *
 * Supported formats:
 * 1. Classic AIDE / Eclipse structure:
 *    - <projectDir>/AndroidManifest.xml
 *    - <projectDir>/src (Java sources)
 *    - <projectDir>/res (Resources)
 *    - <projectDir>/assets (Optional assets)
 *    - <projectDir>/jni/Android.mk (Optional C/C++ JNI)
 *
 * 2. Standard Android Studio / Gradle single-module structure:
 *    - <projectDir>/app/src/main/AndroidManifest.xml (or <projectDir>/src/main/AndroidManifest.xml)
 *    - <projectDir>/app/src/main/java (Java sources)
 *    - <projectDir>/app/src/main/res (Resources)
 *    - <projectDir>/app/src/main/assets (Optional assets)
 *    - <projectDir>/app/src/main/jni/Android.mk or <projectDir>/jni/Android.mk (Optional JNI)
 */
class OfflineProjectInspector {

  fun inspect(projectDir: File): ProjectContractResult {
    if (!projectDir.exists() || !projectDir.isDirectory) {
      return ProjectContractResult.Invalid(
          reason = "Project directory does not exist or is not a directory: ${projectDir.absolutePath}"
      )
    }

    // Determine module directory (e.g. projectDir or projectDir/app)
    val moduleDir = findModuleDirectory(projectDir)
        ?: return ProjectContractResult.Invalid(
            reason = "No Android project or module found in '${projectDir.name}'. Missing AndroidManifest.xml.",
            details = "Expected AndroidManifest.xml at root or in app/src/main/ or src/main/."
        )

    val manifestFile = findManifestFile(moduleDir)
        ?: return ProjectContractResult.Invalid(
            reason = "AndroidManifest.xml not found in module '${moduleDir.name}'."
        )

    // Contract Guard 1: Reject Kotlin files
    val kotlinFiles = findFilesWithExtension(moduleDir, setOf("kt", "kts"))
    if (kotlinFiles.isNotEmpty()) {
      val sample = kotlinFiles.take(3).joinToString("\n") { "• " + it.relativeTo(projectDir).path }
      return ProjectContractResult.Invalid(
          reason = "Kotlin sources are not supported by the Offline NDK Builder.",
          details = "Found ${kotlinFiles.size} Kotlin file(s):\n$sample\n\n" +
              "The Offline NDK Builder is designed for pure Java/XML applications and C/C++ JNI.\n" +
              "Please switch to 'Standard Gradle' in Settings > Build & Run to compile Kotlin projects."
      )
    }

    // Contract Guard 2: Reject Jetpack Compose
    if (detectComposeUsage(moduleDir)) {
      return ProjectContractResult.Invalid(
          reason = "Jetpack Compose is not supported by the Offline NDK Builder.",
          details = "Jetpack Compose requires the Kotlin Compose compiler plugin which cannot run offline.\n" +
              "Please switch to 'Standard Gradle' build mode."
      )
    }

    // Parse AndroidManifest.xml
    val manifestData = try {
      parseManifest(manifestFile)
    } catch (e: Exception) {
      return ProjectContractResult.Invalid(
          reason = "Failed to parse AndroidManifest.xml: ${e.message}",
          details = e.stackTraceToString()
      )
    }

    if (manifestData.packageName.isBlank()) {
      return ProjectContractResult.Invalid(
          reason = "AndroidManifest.xml does not specify a valid package name attribute in <manifest> element."
      )
    }

    // Find resources, assets, and JNI directories
    val resDir = findResDir(moduleDir)
    val assetsDir = findAssetsDir(moduleDir)
    val (jniDir, androidMkFile) = findJniConfig(moduleDir, projectDir)
    val hasNativeCode = androidMkFile != null && androidMkFile.exists()

    // Find Java source directories and files
    val javaSourceDirs = findJavaSourceDirs(moduleDir)
    val javaFiles = javaSourceDirs.flatMap { dir ->
      dir.walkTopDown().filter { it.isFile && it.extension.equals("java", ignoreCase = true) }.toList()
    }

    if (javaFiles.isEmpty() && !hasNativeCode) {
      return ProjectContractResult.Invalid(
          reason = if (javaSourceDirs.isEmpty()) {
            "No Java source directory found (e.g. src/main/java/ or src/)."
          } else {
            "No .java source files found in: ${javaSourceDirs.joinToString { it.name }}"
          },
          details = "The offline builder requires Java source files or a native C/C++ project with Android.mk."
      )
    }

    // Note: Missing activity source files are NOT treated as build-blocking contract failures.
    // Like AIDE Plus and Gradle, the offline builder will automatically generate fallback workspace
    // stubs in workspace.genDir during compilation so native-first/mod/library projects build seamlessly.

    return ProjectContractResult.Valid(
        OfflineProjectContract(
            projectDir = projectDir,
            moduleDir = moduleDir,
            manifestFile = manifestFile,
            packageName = manifestData.packageName,
            versionCode = manifestData.versionCode,
            versionName = manifestData.versionName,
            minSdkVersion = manifestData.minSdkVersion,
            targetSdkVersion = manifestData.targetSdkVersion,
            applicationLabel = manifestData.applicationLabel,
            javaSourceDirs = javaSourceDirs,
            javaFiles = javaFiles,
            resDir = resDir,
            assetsDir = assetsDir,
            jniDir = jniDir,
            androidMkFile = androidMkFile,
            hasNativeCode = hasNativeCode,
            declaredActivities = manifestData.declaredActivities
        )
    )
  }

  private fun findModuleDirectory(projectDir: File): File? {
    // 1. Direct root has AndroidManifest.xml
    if (File(projectDir, "AndroidManifest.xml").exists() ||
        File(projectDir, "src/main/AndroidManifest.xml").exists()) {
      return projectDir
    }

    // 2. Look for app submodule
    val appDir = File(projectDir, "app")
    if (appDir.exists() && appDir.isDirectory) {
      if (File(appDir, "AndroidManifest.xml").exists() ||
          File(appDir, "src/main/AndroidManifest.xml").exists()) {
        return appDir
      }
    }

    // 3. Scan first-level subdirectories for AndroidManifest.xml
    projectDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { sub ->
      if (File(sub, "AndroidManifest.xml").exists() ||
          File(sub, "src/main/AndroidManifest.xml").exists()) {
        return sub
      }
    }

    return null
  }

  private fun findManifestFile(moduleDir: File): File? {
    val candidates = listOf(
        File(moduleDir, "src/main/AndroidManifest.xml"),
        File(moduleDir, "AndroidManifest.xml")
    )
    return candidates.firstOrNull { it.exists() && it.isFile }
  }

  private fun findJavaSourceDirs(moduleDir: File): List<File> {
    val dirs = mutableListOf<File>()
    val srcMainJava = File(moduleDir, "src/main/java")
    if (srcMainJava.exists() && srcMainJava.isDirectory) {
      dirs.add(srcMainJava)
    }
    val javaDir = File(moduleDir, "java")
    if (javaDir.exists() && javaDir.isDirectory) {
      dirs.add(javaDir)
    }
    val srcDir = File(moduleDir, "src")
    val srcMain = File(moduleDir, "src/main")
    // Only treat 'src' as a Java root if 'src/main' does not exist to avoid treating src/main/* as Java sources
    if (srcDir.exists() && srcDir.isDirectory && !srcMain.exists()) {
      dirs.add(srcDir)
    }

    // Discover any additional Java source roots (e.g. jni/ or custom folder structures in AIDE projects)
    try {
      val orphanJavaFiles = moduleDir.walkTopDown()
          .filter { file ->
            file.isFile &&
            file.extension.equals("java", ignoreCase = true) &&
            !file.path.contains("/build/") &&
            !file.path.contains("/.acside/") &&
            !file.path.contains("/.gradle/") &&
            dirs.none { dir -> file.absolutePath.startsWith(dir.absolutePath) }
          }
          .toList()

      for (javaFile in orphanJavaFiles) {
        val inferredRoot = inferJavaSourceRoot(javaFile)
        if (inferredRoot != null && inferredRoot.exists() && dirs.none { it.absolutePath == inferredRoot.absolutePath }) {
          dirs.add(inferredRoot)
        }
      }
    } catch (_: Exception) {
      // Best effort discovery
    }

    return dirs
  }

  private fun inferJavaSourceRoot(javaFile: File): File? {
    try {
      val pkgPrefix = "package "
      var declaredPackage = ""
      javaFile.useLines { lines ->
        for (line in lines.take(40)) {
          val trimmed = line.trim()
          if (trimmed.startsWith(pkgPrefix)) {
            declaredPackage = trimmed.removePrefix(pkgPrefix).substringBefore(';').trim()
            break
          }
        }
      }

      if (declaredPackage.isNotBlank()) {
        val packageSegments = declaredPackage.split('.').filter { it.isNotBlank() }
        var currentParent: File? = javaFile.parentFile
        for (i in packageSegments.indices.reversed()) {
          val segment = packageSegments[i]
          if (currentParent != null && currentParent.name.equals(segment, ignoreCase = true)) {
            currentParent = currentParent.parentFile
          } else {
            return javaFile.parentFile
          }
        }
        return currentParent ?: javaFile.parentFile
      }
    } catch (_: Exception) {
      // Fallback to parent
    }
    return javaFile.parentFile
  }

  private fun findResDir(moduleDir: File): File? {
    val candidates = listOf(
        File(moduleDir, "src/main/res"),
        File(moduleDir, "res")
    )
    return candidates.firstOrNull { it.exists() && it.isDirectory }
  }

  private fun findAssetsDir(moduleDir: File): File? {
    val candidates = listOf(
        File(moduleDir, "src/main/assets"),
        File(moduleDir, "assets")
    )
    return candidates.firstOrNull { it.exists() && it.isDirectory }
  }

  private fun findJniConfig(moduleDir: File, projectDir: File): Pair<File?, File?> {
    val candidates = listOf(
        File(moduleDir, "jni/Android.mk"),
        File(moduleDir, "src/main/jni/Android.mk"),
        File(projectDir, "jni/Android.mk")
    )

    for (mk in candidates) {
      if (mk.exists() && mk.isFile) {
        return Pair(mk.parentFile, mk)
      }
    }
    return Pair(null, null)
  }

  private fun findFilesWithExtension(root: File, extensions: Set<String>): List<File> {
    val results = mutableListOf<File>()
    root.walkTopDown().filter { file ->
      file.isFile &&
          !file.path.contains(File.separator + "build" + File.separator) &&
          !file.path.contains(File.separator + ".gradle" + File.separator) &&
          !file.path.contains(File.separator + ".git" + File.separator)
    }.forEach { file ->
      if (extensions.contains(file.extension.lowercase())) {
        results.add(file)
      }
    }
    return results
  }

  private fun detectComposeUsage(moduleDir: File): Boolean {
    // Check build files
    val buildFiles = listOf(File(moduleDir, "build.gradle"), File(moduleDir, "build.gradle.kts"))
    for (buildFile in buildFiles) {
      if (buildFile.exists() && buildFile.isFile) {
        val text = buildFile.readText()
        if (text.contains("androidx.compose", ignoreCase = true) ||
            text.contains("compose = true", ignoreCase = true)) {
          return true
        }
      }
    }

    // Check source files for compose imports
    val javaDirs = findJavaSourceDirs(moduleDir)
    for (dir in javaDirs) {
      val found = dir.walkTopDown()
          .filter { it.isFile && it.extension.equals("java", ignoreCase = true) }
          .any { file ->
            try {
              file.readLines().take(50).any { it.contains("androidx.compose") }
            } catch (_: Exception) {
              false
            }
          }
      if (found) return true
    }

    return false
  }

  private data class ManifestParsedData(
      val packageName: String,
      val versionCode: Int,
      val versionName: String,
      val minSdkVersion: Int,
      val targetSdkVersion: Int,
      val applicationLabel: String?,
      val declaredActivities: List<String> = emptyList()
  )

  private fun parseManifest(manifestFile: File): ManifestParsedData {
    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    val document = builder.parse(manifestFile)
    document.documentElement.normalize()

    val root = document.documentElement
    val packageName = root.getAttribute("package").ifBlank {
      root.getAttribute("package")
    }

    val androidNs = "http://schemas.android.com/apk/res/android"
    val versionCodeStr = root.getAttributeNS(androidNs, "versionCode").ifBlank {
      root.getAttribute("android:versionCode")
    }
    val versionCode = versionCodeStr.toIntOrNull() ?: 1

    val versionName = root.getAttributeNS(androidNs, "versionName").ifBlank {
      root.getAttribute("android:versionName")
    }.ifBlank { "1.0" }

    var minSdkVersion = 21
    var targetSdkVersion = 33

    val usesSdkNodes = root.getElementsByTagName("uses-sdk")
    if (usesSdkNodes.length > 0) {
      val sdkElement = usesSdkNodes.item(0) as? Element
      if (sdkElement != null) {
        val minStr = sdkElement.getAttributeNS(androidNs, "minSdkVersion").ifBlank {
          sdkElement.getAttribute("android:minSdkVersion")
        }
        val targetStr = sdkElement.getAttributeNS(androidNs, "targetSdkVersion").ifBlank {
          sdkElement.getAttribute("android:targetSdkVersion")
        }
        minSdkVersion = minStr.toIntOrNull() ?: 21
        targetSdkVersion = targetStr.toIntOrNull() ?: 33
      }
    }

    var applicationLabel: String? = null
    val appNodes = root.getElementsByTagName("application")
    if (appNodes.length > 0) {
      val appElement = appNodes.item(0) as? Element
      applicationLabel = appElement?.getAttributeNS(androidNs, "label")?.ifBlank {
        appElement.getAttribute("android:label")
      }
    }

    val declaredActivities = mutableListOf<String>()
    val activityNodes = root.getElementsByTagName("activity")
    for (i in 0 until activityNodes.length) {
      val actElement = activityNodes.item(i) as? Element ?: continue
      val rawName = actElement.getAttributeNS(androidNs, "name").ifBlank {
        actElement.getAttribute("android:name")
      }
      if (rawName.isNotBlank()) {
        val resolvedName = when {
          rawName.startsWith(".") -> packageName + rawName
          !rawName.contains(".") -> "$packageName.$rawName"
          else -> rawName
        }
        declaredActivities.add(resolvedName)
      }
    }

    return ManifestParsedData(
        packageName = packageName,
        versionCode = versionCode,
        versionName = versionName,
        minSdkVersion = minSdkVersion,
        targetSdkVersion = targetSdkVersion,
        applicationLabel = applicationLabel,
        declaredActivities = declaredActivities
    )
  }
}
