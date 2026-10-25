package com.tom.rv2ide.ndk

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Orchestrates safe, robust NDK archive import from SAF Uris or local archive files.
 *
 * Enforces:
 * - Extension validation (.tar.gz, .tgz, .tar.xz, .txz).
 * - Safe copy of Uri streams to app-private cache before extraction.
 * - Staged extraction in an isolated temporary directory.
 * - Toolchain classification and verification before installation.
 * - Protection against silent overwrite of existing installations.
 * - Clean cleanup of temp and staging files on success, failure, or cancellation.
 */
class NdkArchiveImporter(
    private val extractor: SafeTarExtractor = SafeTarExtractor(),
    private val classifier: NdkToolchainClassifier = NdkToolchainClassifier(),
) {

  data class NdkImportResult(
      val success: Boolean,
      val installedDir: File? = null,
      val classification: NdkToolchainClassifier.NdkClassification? = null,
      val errorMessage: String? = null,
  )

  /**
   * Imports an NDK archive selected via Android Storage Access Framework URI.
   */
  fun importFromUri(
      context: Context,
      uri: Uri,
      targetBaseDir: File = File(Environment.HOME, "android-sdk/ndk"),
      onProgress: ((String) -> Unit)? = null,
      isCancelled: () -> Boolean = { false },
  ): NdkImportResult {
    val displayName = queryDisplayName(context, uri) ?: "ndk_archive.tar.gz"
    onProgress?.invoke("Preparing archive: $displayName...")

    val tempFile =
        File(
            context.cacheDir,
            "ndk_import_${System.currentTimeMillis()}_${displayName.substringAfterLast('/')}",
        )

    try {
      if (isCancelled()) throw CancellationException("Import cancelled")

      onProgress?.invoke("Copying archive to temporary storage...")
      val inputStream =
          context.contentResolver.openInputStream(uri)
              ?: return NdkImportResult(
                  success = false,
                  errorMessage = "Could not open stream for selected URI: $uri",
              )

      inputStream.use { src ->
        FileOutputStream(tempFile).use { dst ->
          val buf = ByteArray(16384)
          var len: Int
          while (src.read(buf).also { len = it } > 0) {
            if (isCancelled()) throw CancellationException("Import cancelled during copy")
            dst.write(buf, 0, len)
          }
        }
      }

      val suggestedName = sanitizeInstallationName(displayName.substringBefore('.'))
      return importArchive(
          archiveFile = tempFile,
          targetBaseDir = targetBaseDir,
          suggestedName = suggestedName,
          onProgress = onProgress,
          isCancelled = isCancelled,
      )
    } catch (e: CancellationException) {
      return NdkImportResult(success = false, errorMessage = "Import was cancelled by user.")
    } catch (e: Exception) {
      return NdkImportResult(
          success = false,
          errorMessage = "Failed to copy archive: ${e.message}",
      )
    } finally {
      if (tempFile.exists()) {
        tempFile.delete()
      }
    }
  }

  /**
   * Imports an NDK archive from a local File.
   */
  fun importArchive(
      archiveFile: File,
      targetBaseDir: File = File(Environment.HOME, "android-sdk/ndk"),
      suggestedName: String? = null,
      onProgress: ((String) -> Unit)? = null,
      isCancelled: () -> Boolean = { false },
  ): NdkImportResult {
    val lowerName = archiveFile.name.lowercase()
    val isValidExtension =
        lowerName.endsWith(".zip") ||
            lowerName.endsWith(".tar.gz") ||
            lowerName.endsWith(".tgz") ||
            lowerName.endsWith(".tar.xz") ||
            lowerName.endsWith(".txz")

    if (!isValidExtension) {
      return NdkImportResult(
          success = false,
          errorMessage =
              "Unsupported archive format: '${archiveFile.name}'. Supported formats: .zip, .tar.gz, .tgz, .tar.xz, .txz",
      )
    }

    val parentTempDir = archiveFile.parentFile ?: targetBaseDir
    val stagingDir =
        File(parentTempDir, "ndk_stage_${System.currentTimeMillis()}_${(1000..9999).random()}")
    if (!stagingDir.mkdirs()) {
      return NdkImportResult(
          success = false,
          errorMessage = "Failed to create staging directory: ${stagingDir.absolutePath}",
      )
    }

    try {
      if (isCancelled()) throw CancellationException("Import cancelled")

      onProgress?.invoke("Extracting archive into isolated staging area...")
      if (lowerName.endsWith(".zip")) {
        extractZip(
            archiveFile = archiveFile,
            destinationDir = stagingDir,
            isCancelled = isCancelled,
            onProgress = { bytes, entries ->
              onProgress?.invoke(
                  "Extracted ${entries} entries (${bytes / (1024 * 1024)} MB)...",
              )
            },
        )
      } else {
        extractor.extract(
            archiveFile = archiveFile,
            destinationDir = stagingDir,
            isCancelled = isCancelled,
            onProgress = { bytes, entries ->
              onProgress?.invoke(
                  "Extracted ${entries} entries (${bytes / (1024 * 1024)} MB)...",
              )
            },
        )
      }

      if (isCancelled()) throw CancellationException("Import cancelled after extraction")

      onProgress?.invoke("Validating and classifying toolchain...")
      val classification = classifier.classify(stagingDir)

      if (classification.type == NdkToolchainClassifier.NdkToolchainType.UNSUPPORTED) {
        val warnMsg = classification.warnings.joinToString("; ")
        return NdkImportResult(
            success = false,
            classification = classification,
            errorMessage = "Invalid NDK archive: $warnMsg",
        )
      }

      onProgress?.invoke("Finalizing NDK installation...")
      if (!targetBaseDir.exists()) {
        targetBaseDir.mkdirs()
      }

      val defaultFolderName =
          if (classification.ndkRoot != stagingDir) {
            classification.ndkRoot.name
          } else {
            "ndk"
          }
      val baseName =
          sanitizeInstallationName(
              suggestedName ?: defaultFolderName,
          )

      // Ensure no silent overwrite of existing installation
      var targetDir = File(targetBaseDir, baseName)
      var counter = 1
      while (targetDir.exists()) {
        targetDir = File(targetBaseDir, "$baseName-$counter")
        counter++
      }

      val sourceDir = classification.ndkRoot
      val moved = atomicOrRecursiveMove(sourceDir, targetDir)
      if (!moved) {
        return NdkImportResult(
            success = false,
            classification = classification,
            errorMessage = "Failed to move extracted toolchain to: ${targetDir.absolutePath}",
        )
      }

      // Preserve any companion directories/files from stagingDir (e.g. companion 'bin' directory with busybox)
      if (sourceDir != stagingDir && stagingDir.exists()) {
        stagingDir.listFiles()?.forEach { companion ->
          val companionTarget = File(targetDir, companion.name)
          if (!companionTarget.exists()) {
            atomicOrRecursiveMove(companion, companionTarget)
          } else if (companion.isDirectory && companionTarget.isDirectory) {
            companion.listFiles()?.forEach { subFile ->
              val subTarget = File(companionTarget, subFile.name)
              if (!subTarget.exists()) {
                atomicOrRecursiveMove(subFile, subTarget)
              }
            }
          }
        }
      }

      // Ensure executable permissions on essential toolchain binaries and companion utilities
      ensureExecutablePermissions(targetDir)

      val updatedClassification = classification.copy(ndkRoot = targetDir)
      return NdkImportResult(
          success = true,
          installedDir = targetDir,
          classification = updatedClassification,
      )
    } catch (e: CancellationException) {
      return NdkImportResult(success = false, errorMessage = "Import was cancelled by user.")
    } catch (e: Exception) {
      return NdkImportResult(
          success = false,
          errorMessage = "Import failed: ${e.message}",
      )
    } finally {
      // Clean up staging directory completely
      if (stagingDir.exists()) {
        stagingDir.deleteRecursively()
      }
    }
  }

  fun sanitizeInstallationName(name: String): String {
    val sanitized =
        name
            .replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
            .removePrefix(".")
            .removeSuffix(".")
            .trim('_')
    return sanitized.ifEmpty { "custom-ndk" }
  }

  private fun atomicOrRecursiveMove(source: File, target: File): Boolean {
    if (source.renameTo(target)) {
      return true
    }

    // Fallback if moving across filesystem boundaries
    return try {
      source.copyRecursively(target, overwrite = false)
      source.deleteRecursively()
      true
    } catch (_: Exception) {
      if (target.exists()) target.deleteRecursively()
      false
    }
  }

  fun ensureExecutablePermissions(targetDir: File) {
    File(targetDir, "ndk-build").takeIf { it.exists() }?.setExecutable(true, false)
    File(targetDir, "build/ndk-build").takeIf { it.exists() }?.setExecutable(true, false)

    // Companion bin utilities (e.g. busybox and symlinks)
    val binDir = File(targetDir, "bin")
    if (binDir.isDirectory) {
      binDir.walkTopDown().forEach { file ->
        if (file.isFile) file.setExecutable(true, false)
      }
    }

    // Prebuilt host tools (e.g. make)
    val prebuiltDir = File(targetDir, "prebuilt")
    if (prebuiltDir.isDirectory) {
      prebuiltDir.walkTopDown().forEach { file ->
        if (file.isFile && (file.parentFile?.name == "bin" || file.name == "make")) {
          file.setExecutable(true, false)
        }
      }
    }

    // LLVM / Clang compiler binaries
    val toolchainsDir = File(targetDir, "toolchains")
    if (toolchainsDir.isDirectory) {
      toolchainsDir.walkTopDown().forEach { file ->
        if (file.isFile && file.parentFile?.name == "bin") {
          file.setExecutable(true, false)
        }
      }
    }
  }

  private fun queryDisplayName(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") {
      return uri.lastPathSegment
    }
    return try {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
          ?.use { cursor ->
            if (cursor.moveToFirst()) {
              val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
              if (idx != -1) cursor.getString(idx) else null
            } else null
          }
    } catch (_: Exception) {
      uri.lastPathSegment
    }
  }

  private fun extractZip(
      archiveFile: File,
      destinationDir: File,
      isCancelled: () -> Boolean,
      onProgress: (currentBytes: Long, currentEntries: Int) -> Unit,
  ) {
    val destCanonical = destinationDir.canonicalFile
    var totalEntries = 0
    var totalBytes = 0L

    java.util.zip.ZipFile(archiveFile).use { zip ->
      val entries = zip.entries()
      while (entries.hasMoreElements()) {
        if (isCancelled()) throw CancellationException("Import cancelled during extraction")
        val entry = entries.nextElement()
        val entryFile = File(destCanonical, entry.name).canonicalFile
        if (!entryFile.path.startsWith(destCanonical.path + File.separator) && entryFile != destCanonical) {
          throw SecurityException("Zip Slip path traversal blocked: ${entry.name}")
        }

        if (entry.isDirectory) {
          entryFile.mkdirs()
        } else {
          entryFile.parentFile?.mkdirs()
          zip.getInputStream(entry).use { input ->
            entryFile.outputStream().use { output ->
              val buffer = ByteArray(16384)
              var read: Int
              while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                totalBytes += read
              }
            }
          }
          if (entry.name.contains("/bin/") || entry.name.endsWith(".sh")) {
            entryFile.setExecutable(true, false)
          }
        }
        totalEntries++
        onProgress(totalBytes, totalEntries)
      }
    }
  }
}
