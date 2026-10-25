package com.tom.rv2ide.ndk

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Classifies an extracted NDK toolchain directory based on markers, layout,
 * and ELF binary metadata without executing any binaries.
 */
class NdkToolchainClassifier {

  enum class NdkToolchainType {
    /** AIDE Plus / legacy r17-r18 style NDK (ndk-build + build/core makefiles + Make + Clang/GCC + platforms/sysroot). */
    LEGACY_MAKE_NDK,

    /** Modern Google NDK r19+ (toolchains/llvm/prebuilt + meta/platforms.json / meta/abis.json). */
    MODERN_CLANG_NDK,

    /** Partial toolchain with some compiler tools, but missing build drivers, platforms, or Make. */
    PARTIAL_TOOLCHAIN,

    /** Missing essential compilation components, corrupt, or incompatible. */
    UNSUPPORTED,
  }

  data class ElfInfo(
      val is64Bit: Boolean,
      val architecture: String,
      val machineId: Int,
      val interpreter: String?,
  )

  data class NdkClassification(
      val type: NdkToolchainType,
      val ndkRoot: File,
      val relativeRootPath: String,
      val hostArchitecture: String?,
      val isBionicNative: Boolean,
      val targetAbis: List<String>,
      val apiLevels: List<Int>,
      val hasNdkBuild: Boolean,
      val hasBuildCore: Boolean,
      val hasClang: Boolean,
      val hasMake: Boolean,
      val description: String,
      val warnings: List<String>,
      val missingComponents: List<String>,
  )

  fun classify(directory: File): NdkClassification {
    require(directory.exists() && directory.isDirectory) {
      "Directory does not exist or is not a directory: ${directory.absolutePath}"
    }

    val ndkRoot = locateNdkRoot(directory)
    val relPath =
        if (ndkRoot == directory) ""
        else ndkRoot.relativeTo(directory).path.replace('\\', '/')

    val hasNdkBuild =
        File(ndkRoot, "ndk-build").exists() || File(ndkRoot, "build/ndk-build").exists()
    val buildCoreDir = File(ndkRoot, "build/core")
    val hasBuildCore =
        buildCoreDir.isDirectory &&
            (buildCoreDir.listFiles { f -> f.extension == "mk" }?.isNotEmpty() == true ||
                File(buildCoreDir, "ndk-build.mak").exists())
    val hasMetaPlatforms = File(ndkRoot, "meta/platforms.json").exists()
    val hasMetaAbis = File(ndkRoot, "meta/abis.json").exists()

    val clangBinary = findClangBinary(ndkRoot)
    val makeBinary = findMakeBinary(ndkRoot)
    val hasClang = clangBinary != null
    val hasMake = makeBinary != null

    // Inspect ELF metadata of key host binaries (Clang or Make or toolchain binary)
    val sampleBinary = clangBinary ?: makeBinary ?: findAnyExecutableBinary(ndkRoot)
    val elfInfo = sampleBinary?.let { inspectElf(it) }

    val hostArch = elfInfo?.architecture
    val isBionicNative =
        elfInfo?.interpreter?.startsWith("/system/bin/linker") == true ||
            elfInfo?.interpreter?.contains("linker") == true

    // Target ABIs & API levels discovery
    val targetAbis = detectTargetAbis(ndkRoot)
    val apiLevels = detectApiLevels(ndkRoot)

    val warnings = mutableListOf<String>()
    val missingComponents = mutableListOf<String>()

    if (elfInfo != null && !isBionicNative) {
      warnings.add(
          "Toolchain binaries target desktop Linux (${elfInfo.interpreter ?: "glibc"}) rather than Android Bionic. Cannot run directly without proot/glibc compatibility layer.",
      )
    }

    if (!hasMake) {
      missingComponents.add("GNU Make (make / gnumake) not found under prebuilt/")
    }
    if (!hasClang) {
      missingComponents.add("Clang / Clang++ compiler not found under toolchains/llvm/")
    }
    if (apiLevels.isEmpty()) {
      missingComponents.add("No Android platform headers or stub libraries found under platforms/")
    }
    if (targetAbis.isEmpty()) {
      missingComponents.add("No target architecture sysroots found")
    }

    // Determine type
    val type: NdkToolchainType
    val description: String

    when {
      hasNdkBuild && hasBuildCore && (hasClang || hasMake) && (apiLevels.isNotEmpty() || File(ndkRoot, "sysroot").isDirectory) -> {
        type = NdkToolchainType.LEGACY_MAKE_NDK
        description =
            if (isBionicNative) {
              "AIDE Plus Standalone NDK (Android $hostArch Host, ndk-build + Clang + Make)"
            } else {
              "Legacy ndk-build Toolchain ($hostArch Host, ndk-build + Make)"
            }
      }
      hasMetaPlatforms && hasMetaAbis && hasClang -> {
        type = NdkToolchainType.MODERN_CLANG_NDK
        description = "Modern Android NDK (r19+ layout, Clang)"
      }
      hasClang || hasNdkBuild || hasMake -> {
        type = NdkToolchainType.PARTIAL_TOOLCHAIN
        description = "Partial Native Toolchain (Incomplete NDK)"
        warnings.add(
            "Archive has some compiler tools but lacks a full ndk-build build system or platform headers.",
        )
      }
      else -> {
        type = NdkToolchainType.UNSUPPORTED
        description = "Unsupported or Incompatible Archive"
        warnings.add("No valid Android NDK build system or toolchains detected.")
      }
    }

    return NdkClassification(
        type = type,
        ndkRoot = ndkRoot,
        relativeRootPath = relPath,
        hostArchitecture = hostArch,
        isBionicNative = isBionicNative,
        targetAbis = targetAbis,
        apiLevels = apiLevels,
        hasNdkBuild = hasNdkBuild,
        hasBuildCore = hasBuildCore,
        hasClang = hasClang,
        hasMake = hasMake,
        description = description,
        warnings = warnings,
        missingComponents = missingComponents,
    )
  }

  /**
   * Locates the actual NDK root directory within a directory tree.
   * If a subfolder (such as `android-ndk-aide` or `android-ndk-r*`) contains
   * `ndk-build`, `build/core`, or `toolchains`, returns that subfolder.
   */
  fun locateNdkRoot(baseDir: File): File {
    if (isNdkRoot(baseDir)) return baseDir

    // Search up to depth 5 using BFS so shallowest NDK root wins
    var currentLevel = listOf(baseDir)
    for (depth in 1..5) {
      val nextLevel = mutableListOf<File>()
      for (dir in currentLevel) {
        val files = dir.listFiles() ?: continue
        for (file in files) {
          if (file.isDirectory) {
            if (isNdkRoot(file)) {
              return file
            }
            nextLevel.add(file)
          }
        }
      }
      currentLevel = nextLevel
      if (currentLevel.isEmpty()) break
    }

    return baseDir
  }

  private fun isNdkRoot(dir: File): Boolean {
    val hasBuild = File(dir, "ndk-build").exists() || File(dir, "build/ndk-build").exists()
    val hasBuildCore = File(dir, "build/core").isDirectory
    val hasToolchains = File(dir, "toolchains").isDirectory
    val hasMeta = File(dir, "meta/platforms.json").exists()
    return (hasBuild && hasBuildCore) || (hasToolchains && (hasBuild || hasMeta))
  }

  private fun findClangBinary(ndkRoot: File): File? {
    val candidates =
        listOf(
            File(ndkRoot, "toolchains/llvm/prebuilt/linux-arm64/bin/clang"),
            File(ndkRoot, "toolchains/llvm/prebuilt/linux-arm64/bin/clang-7"),
            File(ndkRoot, "toolchains/llvm/prebuilt/linux-x86_64/bin/clang"),
            File(ndkRoot, "bin/clang"),
        )
    for (c in candidates) {
      if (c.exists() && (c.isFile || c.length() > 0)) return c
    }

    // Search under toolchains/llvm
    val llvmDir = File(ndkRoot, "toolchains/llvm")
    if (llvmDir.exists()) {
      return llvmDir
          .walkTopDown()
          .firstOrNull { it.name.startsWith("clang") && !it.name.contains('.') && it.length() > 0 }
    }
    return null
  }

  private fun findMakeBinary(ndkRoot: File): File? {
    val candidates =
        listOf(
            File(ndkRoot, "prebuilt/linux-arm64/bin/make"),
            File(ndkRoot, "prebuilt/linux-x86_64/bin/make"),
            File(ndkRoot, "bin/make"),
        )
    for (c in candidates) {
      if (c.exists() && c.isFile) return c
    }
    return null
  }

  private fun findAnyExecutableBinary(ndkRoot: File): File? {
    val binDirs =
        listOf(
            File(ndkRoot, "toolchains/aarch64-linux-android-4.9/prebuilt/linux-arm64/bin"),
            File(ndkRoot, "prebuilt/linux-arm64/bin"),
            File(ndkRoot, "bin"),
        )
    for (d in binDirs) {
      if (d.isDirectory) {
        val file = d.listFiles()?.firstOrNull { it.isFile && it.length() > 1000 }
        if (file != null) return file
      }
    }
    return null
  }

  private fun detectTargetAbis(ndkRoot: File): List<String> {
    val abis = mutableSetOf<String>()
    val platformsDir = File(ndkRoot, "platforms")
    if (platformsDir.isDirectory) {
      platformsDir.walkTopDown().maxDepth(3).forEach { f ->
        when (f.name) {
          "arch-arm" -> abis.add("armeabi-v7a")
          "arch-arm64" -> abis.add("arm64-v8a")
          "arch-x86" -> abis.add("x86")
          "arch-x86_64" -> abis.add("x86_64")
        }
      }
    }

    val toolchainsDir = File(ndkRoot, "toolchains")
    if (toolchainsDir.isDirectory) {
      toolchainsDir.listFiles()?.forEach { f ->
        val name = f.name
        if (name.contains("aarch64")) abis.add("arm64-v8a")
        if (name.contains("arm-linux-androideabi")) abis.add("armeabi-v7a")
        if (name.contains("x86_64")) abis.add("x86_64")
        else if (name.contains("x86-")) abis.add("x86")
      }
    }

    return abis.sorted()
  }

  private fun detectApiLevels(ndkRoot: File): List<Int> {
    val levels = mutableSetOf<Int>()
    val platformsDir = File(ndkRoot, "platforms")
    if (platformsDir.isDirectory) {
      platformsDir.listFiles()?.forEach { f ->
        if (f.isDirectory && f.name.startsWith("android-")) {
          f.name.removePrefix("android-").toIntOrNull()?.let { levels.add(it) }
        }
      }
    }
    return levels.sorted()
  }

  /**
   * Safely reads ELF header and PT_INTERP from a binary file without executing it.
   */
  fun inspectElf(file: File): ElfInfo? {
    if (!file.exists() || !file.isFile || file.length() < 52) return null

    try {
      RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(64)
        raf.readFully(header)

        // Magic: 0x7F, 'E', 'L', 'F'
        if (header[0] != 0x7F.toByte() ||
            header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte()
        ) {
          return null
        }

        val is64Bit = (header[4] == 2.toByte())
        val isLittleEndian = (header[5] == 1.toByte())
        val byteOrder = if (isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

        val bb = ByteBuffer.wrap(header).order(byteOrder)
        bb.position(18)
        val machineId = bb.short.toInt() and 0xFFFF

        val arch =
            when (machineId) {
              183 -> "arm64-v8a"
              40 -> "armeabi-v7a"
              62 -> "x86_64"
              3 -> "x86"
              else -> "unknown-$machineId"
            }

        // Read program header offset
        val phOff: Long
        val phEntSize: Int
        val phNum: Int

        if (is64Bit) {
          bb.position(32)
          phOff = bb.long
          bb.position(54)
          phEntSize = bb.short.toInt() and 0xFFFF
          phNum = bb.short.toInt() and 0xFFFF
        } else {
          bb.position(28)
          phOff = bb.int.toLong() and 0xFFFFFFFFL
          bb.position(42)
          phEntSize = bb.short.toInt() and 0xFFFF
          phNum = bb.short.toInt() and 0xFFFF
        }

        var interpreter: String? = null
        if (phOff > 0 && phEntSize > 0 && phNum > 0 && phNum < 100) {
          raf.seek(phOff)
          val phBuf = ByteArray(phEntSize)
          for (i in 0 until phNum) {
            raf.readFully(phBuf)
            val ph = ByteBuffer.wrap(phBuf).order(byteOrder)
            val pType = ph.int
            if (pType == 3) { // PT_INTERP
              val pOffset: Long
              val pFilesz: Long
              if (is64Bit) {
                ph.position(8)
                pOffset = ph.long
                ph.position(32)
                pFilesz = ph.long
              } else {
                pOffset = ph.int.toLong() and 0xFFFFFFFFL
                ph.position(16)
                pFilesz = ph.int.toLong() and 0xFFFFFFFFL
              }

              if (pFilesz in 1..4096) {
                val savedPos = raf.filePointer
                raf.seek(pOffset)
                val interpBytes = ByteArray(pFilesz.toInt())
                raf.readFully(interpBytes)
                raf.seek(savedPos)
                interpreter =
                    String(interpBytes, Charsets.UTF_8).split('\u0000').firstOrNull()?.trim()
                break
              }
            }
          }
        }

        return ElfInfo(is64Bit, arch, machineId, interpreter)
      }
    } catch (_: Exception) {
      return null
    }
  }
}
