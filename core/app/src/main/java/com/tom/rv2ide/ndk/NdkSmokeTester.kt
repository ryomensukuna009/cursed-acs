package com.tom.rv2ide.ndk

import android.content.Context
import com.tom.rv2ide.utils.Environment
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Executes an isolated on-demand smoke test of an installed NDK toolchain
 * without Gradle, compiling a minimal native C library for arm64-v8a.
 *
 * Verifies:
 * - Presence and executable permission of ndk-build.
 * - Proper invocation of GNU Make and Clang host binaries.
 * - Integration with companion shell/bin utilities.
 * - Successful generation of a real arm64-v8a shared library (.so).
 */
class NdkSmokeTester(
    private val context: Context? = null,
    private val workingCacheDir: File =
        context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "."),
) {

  data class ElfVerificationResult(
      val isValid: Boolean,
      val sizeBytes: Long = 0L,
      val is64Bit: Boolean = false,
      val isLittleEndian: Boolean = false,
      val machine: Int = 0,
      val architecture: String = "unknown",
      val errorMessage: String? = null,
  )

  data class SmokeTestResult(
      val success: Boolean,
      val libraryName: String? = null,
      val librarySizeBytes: Long = 0L,
      val isElfValid: Boolean = false,
      val elfArchitecture: String? = null,
      val preservedLibraryFile: File? = null,
      val outputLibrary: File? = null,
      val exitCode: Int = -1,
      val stdout: String = "",
      val stderr: String = "",
      val errorMessage: String? = null,
      val durationMs: Long = 0L,
  )

  /**
   * Generates a minimal synthetic JNI project structure with Android.mk,
   * Application.mk, and smoke.c inside [projectDir].
   */
  fun createSmokeProject(projectDir: File): File {
    val jniDir = File(projectDir, "jni")
    if (!jniDir.exists()) {
      check(jniDir.mkdirs()) { "Failed to create JNI directory: ${jniDir.absolutePath}" }
    }

    val smokeC = File(jniDir, "smoke.c")
    smokeC.writeText(
        """
        #include <jni.h>

        JNIEXPORT jint JNICALL
        Java_com_tom_rv2ide_ndk_SmokeTest_testNativeAdd(JNIEnv *env, jclass clazz, jint a, jint b) {
            return a + b;
        }
        """.trimIndent()
    )

    val androidMk = File(jniDir, "Android.mk")
    androidMk.writeText(
        """
        LOCAL_PATH := $(call my-dir)

        include $(CLEAR_VARS)
        LOCAL_MODULE    := ndk_smoke
        LOCAL_SRC_FILES := smoke.c
        include $(BUILD_SHARED_LIBRARY)
        """.trimIndent()
    )

    val applicationMk = File(jniDir, "Application.mk")
    applicationMk.writeText(
        """
        APP_ABI := arm64-v8a
        APP_PLATFORM := android-21
        """.trimIndent()
    )

    return projectDir
  }

  /**
   * Locates the ndk-build script within the specified NDK root directory.
   */
  fun findNdkBuildScript(ndkDir: File): File? {
    val candidates =
        listOf(
            File(ndkDir, "ndk-build"),
            File(ndkDir, "build/ndk-build"),
        )
    return candidates.firstOrNull { it.exists() }
  }

  /**
   * Resolves the most suitable POSIX shell binary available on the host.
   */
  fun resolveShellBinary(ndkDir: File): String {
    val candidates =
        listOf(
            File(Environment.BIN_DIR, "sh"),
            File(ndkDir, "bin/sh"),
            File(ndkDir, "bin/busybox"),
            File("/system/bin/sh"),
        )
    for (candidate in candidates) {
      if (candidate.exists()) {
        return candidate.absolutePath
      }
    }
    return "sh"
  }

  /**
   * Runs the native NDK compilation smoke test targeting arm64-v8a.
   */
  fun runSmokeTest(
      ndkDir: File,
      onProgress: ((String) -> Unit)? = null,
  ): SmokeTestResult {
    val startTime = System.currentTimeMillis()

    if (!ndkDir.exists() || !ndkDir.isDirectory) {
      return SmokeTestResult(
          success = false,
          errorMessage = "NDK directory does not exist or is not a directory: ${ndkDir.absolutePath}",
      )
    }

    val ndkBuildScript = findNdkBuildScript(ndkDir)
    if (ndkBuildScript == null) {
      return SmokeTestResult(
          success = false,
          errorMessage = "Could not locate 'ndk-build' script inside: ${ndkDir.absolutePath}",
      )
    }

    onProgress?.invoke("Ensuring toolchain permissions...")
    NdkArchiveImporter().ensureExecutablePermissions(ndkDir)

    val projectDir =
        File(workingCacheDir, "ndk_smoke_test_${System.currentTimeMillis()}_${(100..999).random()}")
    var outputLibraryFile: File? = null

    try {
      onProgress?.invoke("Generating minimal native test project...")
      createSmokeProject(projectDir)

      val shBinary = resolveShellBinary(ndkDir)
      val androidMk = File(projectDir, "jni/Android.mk")
      val command =
          listOf(
              shBinary,
              ndkBuildScript.absolutePath,
              "NDK_PROJECT_PATH=${projectDir.absolutePath}",
              "APP_BUILD_SCRIPT=${androidMk.absolutePath}",
              "APP_ABI=arm64-v8a",
              "NDK_OUT=${File(projectDir, "obj").absolutePath}",
              "NDK_LIBS_OUT=${File(projectDir, "libs").absolutePath}",
              "V=1",
          )

      onProgress?.invoke("Executing ndk-build for arm64-v8a...")
      val processBuilder = ProcessBuilder(command)
      processBuilder.directory(projectDir)

      val env = processBuilder.environment()
      val ndkBin = File(ndkDir, "bin").absolutePath
      val prebuiltMakeBin = File(ndkDir, "prebuilt/linux-arm64/bin").absolutePath
      val usrBin = Environment.BIN_DIR.absolutePath
      val currentPath = env["PATH"] ?: "/system/bin"
      env["PATH"] = "$ndkBin:$prebuiltMakeBin:$usrBin:$currentPath"
      env["ANDROID_NDK_ROOT"] = ndkDir.absolutePath
      env["NDK_ROOT"] = ndkDir.absolutePath
      env["HOME"] = Environment.HOME.absolutePath
      env["TMPDIR"] = workingCacheDir.absolutePath

      val process = processBuilder.start()

      val stdoutBuilder = StringBuilder()
      val stderrBuilder = StringBuilder()

      val stdoutThread =
          Thread {
            drainStream(process.inputStream, stdoutBuilder)
          }.apply { start() }

      val stderrThread =
          Thread {
            drainStream(process.errorStream, stderrBuilder)
          }.apply { start() }

      val exited = process.waitFor(60, TimeUnit.SECONDS)
      stdoutThread.join(2000)
      stderrThread.join(2000)

      val duration = System.currentTimeMillis() - startTime

      if (!exited) {
        process.destroyForcibly()
        return SmokeTestResult(
            success = false,
            exitCode = -1,
            stdout = stdoutBuilder.toString(),
            stderr = stderrBuilder.toString(),
            errorMessage = "ndk-build timed out after 60 seconds",
            durationMs = duration,
        )
      }

      val exitCode = process.exitValue()
      val expectedSo = File(projectDir, "libs/arm64-v8a/libndk_smoke.so")

      // CRITICAL: Inspect and measure output library BEFORE workspace cleanup!
      val elfResult = verifyElfSharedLibrary(expectedSo)

      var preservedFile: File? = null
      if (elfResult.isValid) {
        val verifiedDir = File(workingCacheDir, "verified_ndk_smoke")
        if (!verifiedDir.exists()) verifiedDir.mkdirs()
        preservedFile = File(verifiedDir, "libndk_smoke_${System.currentTimeMillis()}.so")
        try {
          expectedSo.copyTo(preservedFile, overwrite = true)
        } catch (_: Exception) {
          preservedFile = null
        }
      }

      val success = (exitCode == 0) && elfResult.isValid
      val errorMsg =
          when {
            exitCode != 0 -> "ndk-build exited with non-zero code $exitCode"
            !elfResult.isValid -> elfResult.errorMessage ?: "Output library failed ELF validation"
            else -> null
          }

      return SmokeTestResult(
          success = success,
          libraryName = expectedSo.name,
          librarySizeBytes = elfResult.sizeBytes,
          isElfValid = elfResult.isValid,
          elfArchitecture = elfResult.architecture,
          preservedLibraryFile = preservedFile,
          outputLibrary = preservedFile ?: expectedSo,
          exitCode = exitCode,
          stdout = stdoutBuilder.toString(),
          stderr = stderrBuilder.toString(),
          errorMessage = errorMsg,
          durationMs = duration,
      )
    } catch (e: Exception) {
      val duration = System.currentTimeMillis() - startTime
      val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
      val hostNote =
          if (isWindows) {
            " (Host OS is Windows; native Android ARM64 NDK binaries require an Android ARM64 host device)"
          } else {
            ""
          }
      return SmokeTestResult(
          success = false,
          exitCode = -1,
          errorMessage = "Failed to execute ndk-build: ${e.message}$hostNote",
          durationMs = duration,
      )
    } finally {
      if (projectDir.exists()) {
        projectDir.deleteRecursively()
      }
    }
  }

  fun verifyElfSharedLibrary(file: File): ElfVerificationResult = Companion.verifyElfSharedLibrary(file)

  companion object {
    /**
     * Verifies that the specified file exists, is non-empty, contains a valid 64-bit ELF header,
     * and targets the arm64-v8a (EM_AARCH64) machine architecture.
     */
    fun verifyElfSharedLibrary(file: File): ElfVerificationResult {
    if (!file.exists() || !file.isFile) {
      return ElfVerificationResult(
          isValid = false,
          errorMessage = "Output library does not exist or is not a regular file: ${file.absolutePath}",
      )
    }

    val size = file.length()
    if (size == 0L) {
      return ElfVerificationResult(
          isValid = false,
          sizeBytes = 0L,
          errorMessage = "Output library is 0 bytes (empty file)",
      )
    }

    if (size < 52) {
      return ElfVerificationResult(
          isValid = false,
          sizeBytes = size,
          errorMessage = "Output library is too small to be a valid ELF binary ($size bytes < 52 bytes)",
      )
    }

    return try {
      file.inputStream().use { stream ->
        val header = ByteArray(64)
        val bytesRead = stream.read(header)
        if (bytesRead < 52) {
          return ElfVerificationResult(
              isValid = false,
              sizeBytes = size,
              errorMessage = "Could not read complete ELF header from library",
          )
        }

        // Verify Magic: 0x7F, 'E', 'L', 'F' (0x7F 0x45 0x4C 0x46)
        if (header[0] != 0x7F.toByte() ||
            header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte()) {
          val hexMagic =
              String.format("%02X %02X %02X %02X", header[0], header[1], header[2], header[3])
          return ElfVerificationResult(
              isValid = false,
              sizeBytes = size,
              errorMessage = "Invalid ELF magic: expected '7F 45 4C 46', found '$hexMagic'",
          )
        }

        val elfClass = header[4].toInt() and 0xFF // 1 = 32-bit, 2 = 64-bit
        val is64Bit = elfClass == 2
        if (!is64Bit) {
          return ElfVerificationResult(
              isValid = false,
              sizeBytes = size,
              is64Bit = false,
              errorMessage = "ELF class is not 64-bit (EI_CLASS = $elfClass, expected 2 for arm64-v8a)",
          )
        }

        val dataEncoding = header[5].toInt() and 0xFF // 1 = little endian, 2 = big endian
        val isLittleEndian = dataEncoding == 1

        // e_machine: bytes 18-19
        val machine =
            if (isLittleEndian) {
              (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
            } else {
              ((header[18].toInt() and 0xFF) shl 8) or (header[19].toInt() and 0xFF)
            }

        val arch =
            when (machine) {
              183 -> "arm64-v8a (AArch64)"
              40 -> "armeabi-v7a (ARM)"
              62 -> "x86_64"
              3 -> "x86"
              else -> "machine-$machine"
            }

        if (machine != 183) { // 183 = EM_AARCH64
          return ElfVerificationResult(
              isValid = false,
              sizeBytes = size,
              is64Bit = is64Bit,
              isLittleEndian = isLittleEndian,
              machine = machine,
              architecture = arch,
              errorMessage =
                  "Incompatible machine architecture: $arch (EM_MACHINE = $machine, expected 183 for arm64-v8a)",
          )
        }

        ElfVerificationResult(
            isValid = true,
            sizeBytes = size,
            is64Bit = true,
            isLittleEndian = isLittleEndian,
            machine = machine,
            architecture = arch,
        )
      }
    } catch (e: Exception) {
      ElfVerificationResult(
          isValid = false,
          sizeBytes = size,
          errorMessage = "Failed to inspect ELF header: ${e.message}",
      )
    }
  }
}

  private fun drainStream(stream: InputStream, builder: StringBuilder) {
    try {
      BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
        var line: String?
        while (reader.readLine().also { line = it } != null) {
          builder.appendLine(line)
        }
      }
    } catch (_: Exception) {}
  }
}
