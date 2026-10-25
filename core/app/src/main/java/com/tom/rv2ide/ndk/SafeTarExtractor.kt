package com.tom.rv2ide.ndk

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream

/**
 * Safe, robust streaming tar extractor supporting .tar and .tar.gz archives.
 *
 * Implements strict security defenses:
 * - Path traversal (Zip Slip) rejection.
 * - Symbolic link and hard link validation (must not escape staging directory).
 * - Resource limits (max entry count, max uncompressed size, max single file size).
 * - Executable permission preservation.
 * - Cooperative cancellation support.
 */
class SafeTarExtractor(
    private val maxEntries: Int = 100_000,
    private val maxTotalSize: Long = 5L * 1024 * 1024 * 1024, // 5 GB
    private val maxSingleFileSize: Long = 1L * 1024 * 1024 * 1024, // 1 GB
    val allowHardLinks: Boolean = false,
) {

  data class ExtractResult(
      val totalEntries: Int,
      val totalBytesExtracted: Long,
      val extractedFiles: List<File>,
  )

  @Throws(IOException::class, SecurityException::class, CancellationException::class)
  fun extract(
      archiveFile: File,
      destinationDir: File,
      isCancelled: () -> Boolean = { false },
      onProgress: (currentBytes: Long, currentEntries: Int) -> Unit = { _, _ -> },
  ): ExtractResult {
    require(archiveFile.exists() && archiveFile.isFile) {
      "Archive file does not exist or is not a regular file: ${archiveFile.absolutePath}"
    }

    val destCanonical = destinationDir.canonicalFile
    if (!destCanonical.exists()) {
      check(destCanonical.mkdirs()) {
        "Failed to create destination directory: ${destCanonical.absolutePath}"
      }
    }
    require(destCanonical.isDirectory && destCanonical.canWrite()) {
      "Destination path is not a writable directory: ${destCanonical.absolutePath}"
    }

    val lowerName = archiveFile.name.lowercase()
    return when {
      lowerName.endsWith(".tar.gz") || lowerName.endsWith(".tgz") -> {
        FileInputStream(archiveFile).buffered().use { fis ->
          GZIPInputStream(fis).use { gzis ->
            extractTarStream(gzis, destCanonical, isCancelled, onProgress)
          }
        }
      }
      lowerName.endsWith(".tar") -> {
        FileInputStream(archiveFile).buffered().use { fis ->
          extractTarStream(fis, destCanonical, isCancelled, onProgress)
        }
      }
      lowerName.endsWith(".tar.xz") || lowerName.endsWith(".txz") -> {
        extractXzArchive(archiveFile, destCanonical, isCancelled, onProgress)
      }
      else -> {
        throw IllegalArgumentException(
            "Unsupported archive format: ${archiveFile.name}. Expected .tar.gz, .tgz, .tar.xz, or .txz",
        )
      }
    }
  }

  @Throws(IOException::class, SecurityException::class, CancellationException::class)
  fun extractTarStream(
      input: InputStream,
      destinationDir: File,
      isCancelled: () -> Boolean = { false },
      onProgress: (currentBytes: Long, currentEntries: Int) -> Unit = { _, _ -> },
  ): ExtractResult {
    val destCanonical = destinationDir.canonicalFile
    var totalEntries = 0
    var totalBytesExtracted = 0L
    val extractedFiles = mutableListOf<File>()

    val headerBuf = ByteArray(512)
    var nextLongName: String? = null
    var nextLongLinkTarget: String? = null

    while (true) {
      if (isCancelled()) {
        throw CancellationException("Archive extraction was cancelled by user")
      }

      val bytesRead = readFully(input, headerBuf, 0, 512)
      if (bytesRead < 512) {
        break // Reached EOF
      }

      // Tar end-of-archive is marked by two consecutive 512-byte blocks of zeroes
      if (isAllZeroes(headerBuf)) {
        break
      }

      val rawName = parseString(headerBuf, 0, 100)
      val mode = parseOctal(headerBuf, 100, 8).toInt()
      val size = parseOctal(headerBuf, 124, 12)
      val typeFlag = headerBuf[156].toInt().toChar()
      val rawLinkName = parseString(headerBuf, 157, 100)
      val magic = parseString(headerBuf, 257, 6)
      val prefix = if (magic.startsWith("ustar")) parseString(headerBuf, 345, 155) else ""

      val fullPath =
          when {
            nextLongName != null -> {
              val name = nextLongName
              nextLongName = null
              name
            }
            prefix.isNotEmpty() -> "$prefix/$rawName"
            else -> rawName
          }

      val linkTarget =
          when {
            nextLongLinkTarget != null -> {
              val link = nextLongLinkTarget
              nextLongLinkTarget = null
              link
            }
            else -> rawLinkName
          }

      // GNU Tar LongLink headers
      if (typeFlag == 'L') {
        // Next entry's file name is in this entry's content
        val data = readEntryData(input, size)
        nextLongName = data.toString(Charsets.UTF_8).trimEnd('\u0000', '\n', '\r')
        continue
      } else if (typeFlag == 'K') {
        // Next entry's symlink target is in this entry's content
        val data = readEntryData(input, size)
        nextLongLinkTarget = data.toString(Charsets.UTF_8).trimEnd('\u0000', '\n', '\r')
        continue
      } else if (typeFlag == 'x' || typeFlag == 'g') {
        // PAX extended header, read and ignore data for now
        skipEntryData(input, size)
        continue
      }

      // Security check: validate entry count
      totalEntries++
      if (totalEntries > maxEntries) {
        throw SecurityException(
            "Archive contains too many entries ($totalEntries > limit $maxEntries). Potential zip-bomb.",
        )
      }

      // Security check: validate single file size
      if (size > maxSingleFileSize) {
        throw SecurityException(
            "Entry '$fullPath' size ($size bytes) exceeds maximum single file limit ($maxSingleFileSize bytes).",
        )
      }

      // Security check: sanitize and prevent path traversal
      validatePathSafety(fullPath)
      val targetFile = File(destCanonical, fullPath).canonicalFile
      if (
          !targetFile.path.startsWith(destCanonical.path + File.separator) &&
              targetFile != destCanonical
      ) {
        throw SecurityException(
            "Path traversal attempt detected in archive entry: '$fullPath' -> '${targetFile.path}'",
        )
      }

      when (typeFlag) {
        '5' -> {
          // Directory
          if (!targetFile.exists()) {
            check(targetFile.mkdirs()) {
              "Failed to create directory: ${targetFile.absolutePath}"
            }
          }
          extractedFiles.add(targetFile)
        }
        '2' -> {
          // Symbolic link ('2')
          validateLinkTargetSafety(linkTarget, targetFile, destCanonical)
          targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

          // Delete existing file if present
          if (targetFile.exists() || Files.isSymbolicLink(targetFile.toPath())) {
            targetFile.delete()
          }

          createSymbolicLinkSafe(targetFile, linkTarget)
          extractedFiles.add(targetFile)
          skipEntryData(input, size)
        }
        '1' -> {
          // Hard link ('1')
          validateLinkTargetSafety(linkTarget, targetFile, destCanonical)
          if (!allowHardLinks) {
            throw SecurityException(
                "Hard link entries ('1') are not permitted in NDK archives for security: '$fullPath' -> '$linkTarget'",
            )
          }

          val resolvedTarget = resolveLinkTarget(linkTarget, targetFile, destCanonical)
          if (!resolvedTarget.exists() || !resolvedTarget.isFile) {
            throw IOException(
                "Hard link target does not exist or has not been extracted yet: '$linkTarget' (for entry '$fullPath')",
            )
          }

          if (!resolvedTarget.canonicalPath.startsWith(destCanonical.canonicalPath + File.separator) &&
              resolvedTarget.canonicalPath != destCanonical.canonicalPath) {
            throw SecurityException(
                "Hard link target escapes staging directory: '$linkTarget' (resolves to '${resolvedTarget.canonicalPath}')",
            )
          }

          targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
          if (targetFile.exists() || Files.isSymbolicLink(targetFile.toPath())) {
            targetFile.delete()
          }

          createHardLinkSafe(targetFile, resolvedTarget)
          extractedFiles.add(targetFile)
          skipEntryData(input, size)
        }
        else -> {
          // Regular file ('0' or NUL)
          targetFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

          // Check total size limit
          if (totalBytesExtracted + size > maxTotalSize) {
            throw SecurityException(
                "Total uncompressed archive size exceeds limit ($maxTotalSize bytes). Potential zip-bomb.",
            )
          }

          FileOutputStream(targetFile).buffered().use { fos ->
            var remaining = size
            val buffer = ByteArray(8192)
            while (remaining > 0) {
              if (isCancelled()) {
                throw CancellationException("Archive extraction was cancelled by user")
              }
              val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
              val read = input.read(buffer, 0, toRead)
              if (read == -1) {
                throw IOException(
                    "Unexpected EOF while reading entry '$fullPath' (expected $size bytes, got ${size - remaining})",
                )
              }
              fos.write(buffer, 0, read)
              remaining -= read
              totalBytesExtracted += read
              onProgress(totalBytesExtracted, totalEntries)
            }
          }

          // Skip tar 512-byte block padding
          val padding = (512 - (size % 512)) % 512
          if (padding > 0) {
            skipFully(input, padding)
          }

          // Restore executable permission if mode has execute bits (0111)
          if ((mode and 0b001_001_001) != 0) {
            targetFile.setExecutable(true, false)
          }

          extractedFiles.add(targetFile)
        }
      }
    }

    return ExtractResult(totalEntries, totalBytesExtracted, extractedFiles)
  }

  private fun extractXzArchive(
      archiveFile: File,
      destinationDir: File,
      isCancelled: () -> Boolean,
      onProgress: (currentBytes: Long, currentEntries: Int) -> Unit,
  ): ExtractResult {
    // Attempt native tar command (available in Termux / ACS environment / Windows 10+)
    val tarExec = findTarExecutable()
    requireNotNull(tarExec) {
      "Native tar binary not found on this system. .tar.xz extraction requires 'tar' executable."
    }

    val processBuilder =
        ProcessBuilder(
            tarExec,
            "-xf",
            archiveFile.absolutePath,
            "-C",
            destinationDir.absolutePath,
        )

    val process = processBuilder.start()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
      val errorMsg = process.errorStream.bufferedReader().readText()
      throw IOException("Native tar extraction failed (exit code $exitCode): $errorMsg")
    }

    // Comprehensive security audit after extraction
    auditExtractedDirectory(destinationDir)

    val extractedList = destinationDir.walkTopDown().toList()
    val totalBytes = extractedList.filter { it.isFile }.sumOf { it.length() }
    onProgress(totalBytes, extractedList.size)
    return ExtractResult(extractedList.size, totalBytes, extractedList)
  }

  /** Recursively audits an extracted directory to guarantee no symlink or file escaped. */
  fun auditExtractedDirectory(destinationDir: File) {
    val destCanonical = destinationDir.canonicalFile
    var count = 0
    var totalBytes = 0L

    destinationDir.walkTopDown().forEach { file ->
      count++
      if (count > maxEntries) {
        throw SecurityException(
            "Extracted directory contains too many entries ($count > limit $maxEntries).",
        )
      }

      val canonical = file.canonicalFile
      if (
          !canonical.path.startsWith(destCanonical.path + File.separator) &&
              canonical != destCanonical
      ) {
        throw SecurityException(
            "Security violation: file '${file.path}' points outside staging directory '${destCanonical.path}'",
        )
      }

      if (Files.isSymbolicLink(file.toPath())) {
        try {
          val targetPath = Files.readSymbolicLink(file.toPath()).toString()
          validateLinkTargetSafety(targetPath, file, destCanonical)
        } catch (e: Exception) {
          if (e is SecurityException) throw e
          // Broken symlink inside staging dir is allowed if target doesn't escape
        }
      }

      if (file.isFile) {
        val len = file.length()
        if (len > maxSingleFileSize) {
          throw SecurityException(
              "File '${file.name}' size ($len bytes) exceeds maximum single file limit ($maxSingleFileSize bytes).",
          )
        }
        totalBytes += len
        if (totalBytes > maxTotalSize) {
          throw SecurityException(
              "Total extracted size exceeds maximum allowed limit ($maxTotalSize bytes).",
          )
        }
      }
    }
  }

  private fun validatePathSafety(entryName: String) {
    if (entryName.isBlank()) {
      throw SecurityException("Archive entry name cannot be empty")
    }
    if (entryName.startsWith("/") || entryName.startsWith("\\")) {
      throw SecurityException("Absolute path entries are rejected: '$entryName'")
    }
    if (entryName.length >= 2 && entryName[1] == ':' && entryName[0].isLetter()) {
      throw SecurityException("Drive-relative paths are rejected: '$entryName'")
    }
    // Reject explicit path traversal tokens
    val parts = entryName.replace('\\', '/').split('/')
    var depth = 0
    for (part in parts) {
      if (part == "..") {
        depth--
        if (depth < 0) {
          throw SecurityException("Path traversal attempt with '..' detected: '$entryName'")
        }
      } else if (part.isNotEmpty() && part != ".") {
        depth++
      }
    }
  }

  private fun validateLinkTargetSafety(linkTarget: String, linkFile: File, stagingRoot: File) {
    if (linkTarget.isBlank()) {
      throw SecurityException("Link target cannot be empty (in '${linkFile.name}')")
    }
    if (linkTarget.startsWith("/") || linkTarget.startsWith("\\")) {
      throw SecurityException(
          "Absolute link targets are forbidden: '$linkTarget' (in '${linkFile.name}')",
      )
    }
    if (linkTarget.length >= 2 && linkTarget[1] == ':' && linkTarget[0].isLetter()) {
      throw SecurityException(
          "Drive-letter link targets are forbidden: '$linkTarget' (in '${linkFile.name}')",
      )
    }

    val parentDir = linkFile.parentFile ?: stagingRoot
    val resolvedFromParent = File(parentDir, linkTarget).canonicalFile
    val resolvedFromRoot = File(stagingRoot, linkTarget).canonicalFile
    val stagingCanonicalPath = stagingRoot.canonicalPath

    val parentEscapes = !resolvedFromParent.path.startsWith(stagingCanonicalPath)
    val rootEscapes = !resolvedFromRoot.path.startsWith(stagingCanonicalPath)

    if (parentEscapes && rootEscapes) {
      throw SecurityException(
          "Link target escapes staging directory: '$linkTarget' (resolves outside '$stagingCanonicalPath')",
      )
    }
  }

  fun resolveLinkTarget(linkTarget: String, linkFile: File, stagingRoot: File): File {
    val fromParent = File(linkFile.parentFile ?: stagingRoot, linkTarget).canonicalFile
    if (fromParent.exists()) {
      return fromParent
    }
    val fromRoot = File(stagingRoot, linkTarget).canonicalFile
    if (fromRoot.exists()) {
      return fromRoot
    }
    return fromParent
  }

  private fun createHardLinkSafe(targetFile: File, existingTarget: File) {
    try {
      Files.createLink(targetFile.toPath(), existingTarget.toPath())
    } catch (e: Exception) {
      // Fallback on filesystems that do not support hard links
      existingTarget.copyTo(targetFile, overwrite = true)
    }
  }

  private fun createSymbolicLinkSafe(targetFile: File, linkTarget: String) {
    try {
      Files.createSymbolicLink(targetFile.toPath(), Paths.get(linkTarget))
    } catch (e: Exception) {
      // On platforms where unprivileged symlinks fail (e.g. Windows non-dev mode),
      // create a text reference file or continue gracefully
      targetFile.writeText(linkTarget)
    }
  }

  private fun findTarExecutable(): String? {
    // Check Termux / ACS BIN_DIR first
    val termuxTar = File("/data/data/com.tom.rv2ide/files/usr/bin/tar")
    if (termuxTar.exists() && termuxTar.canExecute()) {
      return termuxTar.absolutePath
    }

    // Check system tar
    val candidates = listOf("tar", "tar.exe", "/system/bin/tar", "/bin/tar")
    for (c in candidates) {
      try {
        val p = ProcessBuilder(c, "--version").start()
        if (p.waitFor() == 0) return c
      } catch (_: Exception) {}
    }
    return null
  }

  private fun parseString(buf: ByteArray, offset: Int, length: Int): String {
    var end = offset
    val max = offset + length
    while (end < max && buf[end] != 0.toByte()) {
      end++
    }
    return String(buf, offset, end - offset, Charsets.UTF_8).trim()
  }

  private fun parseOctal(buf: ByteArray, offset: Int, length: Int): Long {
    var result = 0L
    var i = offset
    val end = offset + length
    // Skip leading spaces or zeroes
    while (i < end && (buf[i] == ' '.code.toByte() || buf[i] == '0'.code.toByte())) {
      i++
    }
    while (i < end && buf[i] >= '0'.code.toByte() && buf[i] <= '7'.code.toByte()) {
      result = (result shl 3) + (buf[i] - '0'.code.toByte())
      i++
    }
    return result
  }

  private fun readEntryData(input: InputStream, size: Long): ByteArray {
    val data = ByteArray(size.toInt())
    readFully(input, data, 0, size.toInt())
    val padding = (512 - (size % 512)) % 512
    if (padding > 0) {
      skipFully(input, padding)
    }
    return data
  }

  private fun skipEntryData(input: InputStream, size: Long) {
    val padding = (512 - (size % 512)) % 512
    skipFully(input, size + padding)
  }

  private fun isAllZeroes(buf: ByteArray): Boolean {
    for (b in buf) {
      if (b != 0.toByte()) return false
    }
    return true
  }

  private fun readFully(input: InputStream, b: ByteArray, off: Int, len: Int): Int {
    var total = 0
    while (total < len) {
      val r = input.read(b, off + total, len - total)
      if (r == -1) break
      total += r
    }
    return total
  }

  private fun skipFully(input: InputStream, n: Long) {
    var remaining = n
    val buf = ByteArray(8192)
    while (remaining > 0) {
      val toRead = remaining.coerceAtMost(buf.size.toLong()).toInt()
      val r = input.read(buf, 0, toRead)
      if (r == -1) break
      remaining -= r
    }
  }
}
