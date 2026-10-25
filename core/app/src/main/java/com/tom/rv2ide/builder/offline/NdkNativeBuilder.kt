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

import com.tom.rv2ide.ndk.NdkSmokeTester
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.GeneralFileUtils
import com.tom.rv2ide.utils.IBuildSystemUtils
import java.io.File
import java.util.concurrent.TimeUnit

class NdkNativeBuilder {

  data class NativeBuildOutput(
      val stageResult: OfflineStageResult,
      val compiledLibraries: List<File> = emptyList()
  )

  fun build(
      contract: OfflineProjectContract,
      workspace: OfflineWorkspace,
      listener: OfflineBuildProgressListener? = null
  ): NativeBuildOutput {
    val startTime = System.currentTimeMillis()

    if (!contract.hasNativeCode || contract.androidMkFile == null) {
      listener?.onStageLog(OfflineBuildStage.NATIVE, "No JNI code (Android.mk) configured. Skipping native build.")
      return NativeBuildOutput(
          stageResult = OfflineStageResult(
              stage = OfflineBuildStage.NATIVE,
              success = true,
              message = "No native JNI code configured. Skipping.",
              durationMs = 0
          ),
          compiledLibraries = emptyList()
      )
    }

    listener?.onStageLog(OfflineBuildStage.NATIVE, "Found Android.mk at ${contract.androidMkFile.absolutePath}")

    // 1. Locate installed NDK dynamically via NdkResolver
    val activeNdkDir = com.tom.rv2ide.ndk.NdkResolver.findActiveNdkDir(contract.projectDir)
    if (activeNdkDir == null) {
      val ndkBaseDir = File(Environment.HOME, "android-sdk/ndk")
      return NativeBuildOutput(
          stageResult = OfflineStageResult(
              stage = OfflineBuildStage.NATIVE,
              success = false,
              message = "Native code compilation requested, but no valid NDK containing ndk-build is installed in ${ndkBaseDir.absolutePath}.",
              durationMs = System.currentTimeMillis() - startTime,
              details = "Please import an AIDE Plus NDK archive in IDE Settings > System > NDK before building JNI projects."
          )
      )
    }

    listener?.onStageLog(OfflineBuildStage.NATIVE, "Using NDK toolchain: ${activeNdkDir.name} (${activeNdkDir.absolutePath})")

    // 2. Locate ndk-build
    val ndkBuildScript = File(activeNdkDir, "ndk-build")
    if (!ndkBuildScript.exists()) {
      return NativeBuildOutput(
          stageResult = OfflineStageResult(
              stage = OfflineBuildStage.NATIVE,
              success = false,
              message = "ndk-build executable not found in ${activeNdkDir.absolutePath}",
              durationMs = System.currentTimeMillis() - startTime
          )
      )
    }

    if (!ndkBuildScript.canExecute()) {
      ndkBuildScript.setExecutable(true)
    }

    // 3. Prepare command respecting Application.mk, target ABIs, and special-character paths
    val ndkProjectDir = contract.jniDir?.parentFile ?: contract.projectDir
    val shBinary = NdkSmokeTester().resolveShellBinary(activeNdkDir)

    val appMkFile = contract.androidMkFile.parentFile?.let { File(it, "Application.mk") }
    val requestedAbi = if (appMkFile != null && appMkFile.exists()) {
      try {
        val abiLine = appMkFile.useLines { lines ->
          lines.find { it.trim().startsWith("APP_ABI") }
        }
        abiLine?.substringAfter(":=")?.substringAfter("=")?.trim() ?: "arm64-v8a"
      } catch (_: Exception) {
        "arm64-v8a"
      }
    } else {
      "arm64-v8a"
    }

    // Special-Character Path Protection (Task 7):
    // When project paths contain spaces, parentheses, '@', brackets, or shell metacharacters,
    // GNU Make and /system/bin/sh can fail with syntax errors (e.g. unexpected '(').
    // We establish a clean bridge inside the isolated workspace to insulate ndk-build and clang.
    val needsPathSanitization = hasSpecialPathCharacters(ndkProjectDir.absolutePath) ||
        hasSpecialPathCharacters(contract.androidMkFile.absolutePath) ||
        (appMkFile != null && hasSpecialPathCharacters(appMkFile.absolutePath))

    var bridgeCreated = false
    val (effectiveProjectDir, effectiveAndroidMk, effectiveAppMk) = if (needsPathSanitization) {
      val bridgeDir = workspace.projectBridgeDir
      val bridged = createSafePathBridge(ndkProjectDir, bridgeDir, listener)
      if (bridged) {
        bridgeCreated = true
        val relAndroidMk = try {
          contract.androidMkFile.relativeTo(ndkProjectDir).path
        } catch (_: Exception) {
          contract.androidMkFile.name
        }
        val safeAndroidMk = File(bridgeDir, relAndroidMk)
        val safeAppMk = appMkFile?.let {
          try {
            File(bridgeDir, it.relativeTo(ndkProjectDir).path)
          } catch (_: Exception) {
            File(bridgeDir, it.name)
          }
        }
        Triple(bridgeDir, safeAndroidMk, safeAppMk)
      } else {
        Triple(ndkProjectDir, contract.androidMkFile, appMkFile)
      }
    } else {
      Triple(ndkProjectDir, contract.androidMkFile, appMkFile)
    }

    val cmd = mutableListOf(
        shBinary,
        ndkBuildScript.absolutePath,
        "NDK_PROJECT_PATH=${effectiveProjectDir.absolutePath}",
        "APP_BUILD_SCRIPT=${effectiveAndroidMk.absolutePath}",
        "NDK_OUT=${workspace.nativeOutDir.absolutePath}",
        "NDK_LIBS_OUT=${workspace.libsOutDir.absolutePath}",
        "V=1"
    )

    if (effectiveAppMk != null && effectiveAppMk.exists()) {
      cmd.add("NDK_APPLICATION_MK=${effectiveAppMk.absolutePath}")
    }
    cmd.add("APP_ABI=$requestedAbi")

    listener?.onStageLog(OfflineBuildStage.NATIVE, "Invoking ndk-build for target ABI(s): $requestedAbi...")

    // 4. Execute ndk-build
    val processBuilder = ProcessBuilder(cmd)
        .directory(effectiveProjectDir)
        .redirectErrorStream(false)

    val env = processBuilder.environment()
    Environment.putEnvironment(env, false)
    val ndkBin = File(activeNdkDir, "bin").absolutePath
    val prebuiltMakeBin = File(activeNdkDir, "prebuilt/linux-arm64/bin").absolutePath
    val prebuiltArmBin = File(activeNdkDir, "prebuilt/android-arm/bin").absolutePath
    val usrBin = Environment.BIN_DIR.absolutePath
    val currentPath = env["PATH"] ?: "/system/bin"
    env["PATH"] = "$ndkBin:$prebuiltMakeBin:$prebuiltArmBin:$usrBin:$currentPath"
    env["ANDROID_NDK_ROOT"] = activeNdkDir.absolutePath
    env["NDK_ROOT"] = activeNdkDir.absolutePath
    env["SHELL"] = shBinary
    env["HOME"] = Environment.HOME.absolutePath
    env["TMPDIR"] = workspace.nativeOutDir.absolutePath

    val process = processBuilder.start()
    val stdout = process.inputStream.bufferedReader().use { it.readText() }
    val stderr = process.errorStream.bufferedReader().use { it.readText() }

    val finished = process.waitFor(180, TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      return NativeBuildOutput(
          stageResult = OfflineStageResult(
              stage = OfflineBuildStage.NATIVE,
              success = false,
              message = "ndk-build timed out after 180 seconds.",
              durationMs = System.currentTimeMillis() - startTime,
              details = "Stdout:\n$stdout\n\nStderr:\n$stderr"
          )
      )
    }

    if (process.exitValue() != 0) {
      return NativeBuildOutput(
          stageResult = OfflineStageResult(
              stage = OfflineBuildStage.NATIVE,
              success = false,
              message = "ndk-build compilation failed with exit code ${process.exitValue()}",
              durationMs = System.currentTimeMillis() - startTime,
              details = "Command: ${cmd.joinToString(" ")}\n\nStderr:\n$stderr\n\nStdout:\n$stdout"
          )
      )
    }

    // 5. Scan and verify compiled shared libraries (.so) across all target ABIs
    val soFiles = if (workspace.libsOutDir.exists()) {
      workspace.libsOutDir.walkTopDown()
          .filter { it.isFile && it.extension.equals("so", ignoreCase = true) }
          .toList()
    } else {
      emptyList()
    }

    if (soFiles.isEmpty()) {
      return NativeBuildOutput(
          stageResult = OfflineStageResult(
              stage = OfflineBuildStage.NATIVE,
              success = false,
              message = "ndk-build succeeded with exit 0, but no .so shared libraries were generated in ${workspace.libsOutDir.absolutePath}.",
              durationMs = System.currentTimeMillis() - startTime,
              details = "Stdout:\n$stdout"
          )
      )
    }

    // 6. Verify ELF headers for each compiled library
    for (soFile in soFiles) {
      val elfResult = NdkSmokeTester.verifyElfSharedLibrary(soFile)
      if (!elfResult.isValid) {
        return NativeBuildOutput(
            stageResult = OfflineStageResult(
                stage = OfflineBuildStage.NATIVE,
                success = false,
                message = "ELF verification failed for generated library '${soFile.name}': ${elfResult.errorMessage}",
                durationMs = System.currentTimeMillis() - startTime,
                details = "File: ${soFile.absolutePath} (${soFile.length()} bytes)"
            )
        )
      }

      val abiName = soFile.parentFile?.name ?: "native"
      val bitness = if (elfResult.is64Bit) 64 else 32
      listener?.onStageLog(
          OfflineBuildStage.NATIVE,
          "Verified library [$abiName] '${soFile.name}': ${soFile.length()} bytes, ${elfResult.architecture} ($bitness-bit ELF)."
      )
    }

    return NativeBuildOutput(
        stageResult = OfflineStageResult(
            stage = OfflineBuildStage.NATIVE,
            success = true,
            message = "Compiled and verified ${soFiles.size} native library(s) for target ABIs.",
            durationMs = System.currentTimeMillis() - startTime
        ),
        compiledLibraries = soFiles
    )
  }

  private fun hasSpecialPathCharacters(path: String): Boolean {
    return path.any { ch -> ch.isWhitespace() || ch in "()@[]{}'\"$;*?&|<>`!\\" }
  }

  internal fun createSafePathBridge(
      sourceDir: File,
      bridgeDir: File,
      listener: OfflineBuildProgressListener?
  ): Boolean {
    try {
      if (bridgeDir.exists()) {
        try {
          java.nio.file.Files.deleteIfExists(bridgeDir.toPath())
        } catch (_: Throwable) {
          bridgeDir.delete()
        }
      }

      // Attempt 1: Java NIO symbolic link (works natively on Linux/Android ext4/f2fs and Windows Dev Mode)
      try {
        java.nio.file.Files.createSymbolicLink(bridgeDir.toPath(), sourceDir.toPath())
        listener?.onStageLog(
            OfflineBuildStage.NATIVE,
            "Created sanitized path bridge via symbolic link: ${bridgeDir.name} -> ${sourceDir.name}"
        )
        return true
      } catch (_: Throwable) {
        // Fallback for Windows without DevMode: Directory Junction (mklink /J, no admin needed)
        val osName = System.getProperty("os.name")?.lowercase() ?: ""
        if (osName.contains("win")) {
          val pb = ProcessBuilder("cmd", "/c", "mklink", "/J", bridgeDir.absolutePath, sourceDir.absolutePath)
          val p = pb.start()
          p.waitFor(5, TimeUnit.SECONDS)
          if (p.exitValue() == 0 && bridgeDir.exists()) {
            listener?.onStageLog(
                OfflineBuildStage.NATIVE,
                "Created sanitized path bridge via directory junction: ${bridgeDir.name} -> ${sourceDir.name}"
            )
            return true
          }
        }
      }

      // Fallback 2: Direct tree copy for filesystems without symlink/junction support
      listener?.onStageLog(
          OfflineBuildStage.NATIVE,
          "Symlink/junction unavailable; creating isolated build shadow copy for native build..."
      )
      sourceDir.copyRecursively(bridgeDir, overwrite = true)
      return true
    } catch (e: Exception) {
      listener?.onStageLog(
          OfflineBuildStage.NATIVE,
          "Warning: Could not create sanitized path bridge: ${e.message}. Proceeding with direct path."
      )
      return false
    }
  }

  companion object {
    fun shQuote(arg: String): String {
      if (arg.isEmpty()) return "''"
      if (arg.matches(Regex("^[a-zA-Z0-9_./@=-]+$"))) return arg
      return "'" + arg.replace("'", "'\\''") + "'"
    }
  }
}
