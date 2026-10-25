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
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ndk

import com.tom.rv2ide.builder.SharedBuildOrchestrator
import com.tom.rv2ide.managers.PreferenceManager
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.IBuildSystemUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Result of an NDK deletion evaluation or execution.
 */
sealed class SafeNdkDeleteResult {
    data class Success(
        val version: String,
        val targetPath: String,
        val freedBytes: Long,
        val remainingVersions: List<String>,
        val wasActive: Boolean,
        val newActiveVersion: String?
    ) : SafeNdkDeleteResult()

    data class ValidationSummary(
        val version: String,
        val targetPath: String,
        val sizeBytes: Long,
        val formattedSize: String,
        val isActive: Boolean,
        val isLastNdk: Boolean,
        val remainingVersionsAfterDelete: List<String>
    ) : SafeNdkDeleteResult()

    data class Rejected(
        val version: String,
        val reason: String,
        val details: String? = null
    ) : SafeNdkDeleteResult()

    data class Error(
        val version: String,
        val message: String,
        val exception: Throwable? = null
    ) : SafeNdkDeleteResult()
}

/**
 * Handles safe, verified deletion of installed NDK versions with strict boundary checks,
 * active-build rejection, active-version protection, marker verification, and automatic
 * active version migration.
 */
class SafeNdkDeleter(
    private val baseNdkDir: File = File(Environment.HOME, "android-sdk/ndk"),
    private val prefManager: PreferenceManager? = null
) {
    companion object {
        private val log = LoggerFactory.getLogger(SafeNdkDeleter::class.java)
        private val deleteLock = Any()

        // Markers that identify a legitimate NDK directory
        private val NDK_MARKERS = listOf(
            "source.properties",
            "ndk-build",
            "ndk-build.cmd",
            "build/core",
            "toolchains",
            "platforms",
            "sysroot"
        )

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
            return String.format(java.util.Locale.US, "%.2f %s", value, units[digitGroups])
        }

        fun isValidNdkDir(dir: File): Boolean {
            if (!dir.exists() || !dir.isDirectory || dir.name.startsWith(".")) return false
            if (dir.name.equals("bin", ignoreCase = true) || dir.name.equals("tmp", ignoreCase = true) || dir.name.equals("lost+found", ignoreCase = true)) return false
            return NDK_MARKERS.any { marker -> File(dir, marker).exists() }
        }
    }

    /**
     * Inspects and validates whether an NDK version can be safely deleted, computing
     * its exact directory size and whether it is active or the last remaining NDK.
     */
    fun inspectNdkForDeletion(version: String): SafeNdkDeleteResult {
        val check = validateNdkPreconditions(version)
        if (check != null) return check

        val canonicalBase = baseNdkDir.canonicalFile
        val targetDir = if (Files.isSymbolicLink(File(baseNdkDir, version).toPath())) {
            File(baseNdkDir, version)
        } else {
            File(canonicalBase, version).canonicalFile
        }
        val sizeBytes = calculateDirectorySize(targetDir)
        val installedVersions = getInstalledVersions()
        val activeVersion = getActiveNdkVersion()
        val isActive = version.equals(activeVersion, ignoreCase = true)
        val isLastNdk = installedVersions.size <= 1
        val remaining = installedVersions.filterNot { it.equals(version, ignoreCase = true) }

        return SafeNdkDeleteResult.ValidationSummary(
            version = version,
            targetPath = targetDir.absolutePath,
            sizeBytes = sizeBytes,
            formattedSize = formatBytes(sizeBytes),
            isActive = isActive,
            isLastNdk = isLastNdk,
            remainingVersionsAfterDelete = remaining
        )
    }

    /**
     * Executes the deletion of the specified NDK version after validating all safety rules.
     *
     * @param version The NDK folder name to delete
     * @param forceDeleteActive If true, allows deleting the active NDK (will auto-migrate active version)
     * @param allowDeleteLast If true, allows deleting the last remaining NDK (will clear ndk_installed)
     */
    fun deleteNdkSafely(
        version: String,
        forceDeleteActive: Boolean = false,
        allowDeleteLast: Boolean = false
    ): SafeNdkDeleteResult = synchronized(deleteLock) {
        val check = validateNdkPreconditions(version)
        if (check != null) return check

        // 1. Check if build is running
        try {
            if (SharedBuildOrchestrator.isBuildRunning()) {
                return SafeNdkDeleteResult.Rejected(
                    version = version,
                    reason = "Cannot delete NDK while a build is in progress.",
                    details = "Wait for the active build to complete or cancel it before removing NDK versions."
                )
            }
        } catch (t: Throwable) {
            log.warn("Could not check SharedBuildOrchestrator build status: {}", t.message)
        }

        val canonicalBase = baseNdkDir.canonicalFile
        val targetDir = if (Files.isSymbolicLink(File(baseNdkDir, version).toPath())) {
            File(baseNdkDir, version)
        } else {
            File(canonicalBase, version).canonicalFile
        }
        val installedVersions = getInstalledVersions()
        val activeVersion = getActiveNdkVersion()
        val isActive = version.equals(activeVersion, ignoreCase = true)
        val isLastNdk = installedVersions.size <= 1

        // 2. Active NDK check
        if (isActive && !forceDeleteActive && !isLastNdk) {
            return SafeNdkDeleteResult.Rejected(
                version = version,
                reason = "NDK '$version' is currently the active NDK in IDE settings.",
                details = "Please switch to another installed NDK version first, or confirm replacement."
            )
        }

        // 3. Last NDK check
        if (isLastNdk && !allowDeleteLast) {
            return SafeNdkDeleteResult.Rejected(
                version = version,
                reason = "NDK '$version' is the only installed NDK.",
                details = "Deleting it will disable offline C/C++ builds until a new NDK is installed."
            )
        }

        // 4. Calculate size before deletion
        val freedBytes = calculateDirectorySize(targetDir)

        // 5. Perform recursive deletion safely
        try {
            log.info("Deleting NDK directory safely: {}", targetDir.absolutePath)
            baseNdkDir.setWritable(true, false)
            targetDir.setWritable(true, false)
            targetDir.setExecutable(true, false)
            deleteRecursivelySafe(targetDir)
        } catch (e: Exception) {
            log.warn("Java recursive deletion encountered error: {}. Attempting fallback...", e.message)
            try {
                executeRmRfFallback(targetDir, canonicalBase)
            } catch (fallbackEx: Exception) {
                log.error("Failed to delete NDK directory with fallback: {}", targetDir.absolutePath, fallbackEx)
            }
        }

        // Check if directory still exists after Java deletion, retry with fallback before failing
        if (targetDir.exists()) {
            log.warn("Target directory still exists, attempting fallback rm -rf: {}", targetDir.absolutePath)
            try {
                executeRmRfFallback(targetDir, canonicalBase)
            } catch (t: Throwable) {
                log.error("Fallback rm -rf failed: {}", t.message)
            }
        }

        // 6. Verify directory is gone
        if (targetDir.exists()) {
            return SafeNdkDeleteResult.Error(
                version = version,
                message = "Directory still exists after deletion attempt: ${targetDir.absolutePath}"
            )
        }

        // Invalidate cached NDK size
        try {
            prefManager?.remove("cached_ndk_total_size")
        } catch (_: Throwable) {}

        // 7. Update active NDK settings if needed
        val remainingVersions = getInstalledVersions()
        var newActiveVersion: String? = null

        if (isActive) {
            if (remainingVersions.isNotEmpty()) {
                // Select the highest/latest remaining NDK version
                newActiveVersion = remainingVersions.sorted().last()
                try {
                    IBuildSystemUtils.setNdkVersion(newActiveVersion)
                    prefManager?.putString("bs_native_kit_version", newActiveVersion)
                    prefManager?.putBoolean("ndk_installed", true)
                    log.info("Switched active NDK to remaining version: {}", newActiveVersion)
                } catch (t: Throwable) {
                    log.warn("Could not update IBuildSystemUtils active version: {}", t.message)
                }
            } else {
                try {
                    prefManager?.putBoolean("ndk_installed", false)
                    prefManager?.remove("bs_native_kit_version")
                    log.info("No NDK versions remaining. Marked ndk_installed=false.")
                } catch (t: Throwable) {
                    log.warn("Could not update preferences: {}", t.message)
                }
            }
        }

        log.info("Successfully deleted NDK '$version', freed {} bytes.", freedBytes)
        return SafeNdkDeleteResult.Success(
            version = version,
            targetPath = targetDir.absolutePath,
            freedBytes = freedBytes,
            remainingVersions = remainingVersions,
            wasActive = isActive,
            newActiveVersion = newActiveVersion
        )
    }

    /**
     * Validates path traversal, canonical containment, existence, and NDK directory markers.
     * Returns null if all preconditions pass, or SafeNdkDeleteResult.Rejected otherwise.
     */
    private fun validateNdkPreconditions(version: String): SafeNdkDeleteResult.Rejected? {
        val trimmed = version.trim()
        if (trimmed.isEmpty()) {
            return SafeNdkDeleteResult.Rejected(version, "NDK version identifier cannot be blank.")
        }

        // Path traversal checks
        if (trimmed.contains("..") || trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("\u0000")) {
            return SafeNdkDeleteResult.Rejected(
                version = version,
                reason = "Invalid NDK version string contains illegal path characters or traversal attempts."
            )
        }

        if (!baseNdkDir.exists() || !baseNdkDir.isDirectory) {
            return SafeNdkDeleteResult.Rejected(
                version = version,
                reason = "Base NDK directory does not exist: ${baseNdkDir.absolutePath}"
            )
        }

        val canonicalBase: File
        val targetDir: File
        try {
            canonicalBase = baseNdkDir.canonicalFile
            targetDir = File(canonicalBase, trimmed).canonicalFile
        } catch (e: IOException) {
            return SafeNdkDeleteResult.Rejected(
                version = version,
                reason = "Cannot resolve canonical path: ${e.message}"
            )
        }

        // Canonical containment check with Android multi-user symlink alias support
        val directChild = File(baseNdkDir, trimmed)
        val isDirectChild = directChild.parentFile?.canonicalFile == canonicalBase
        val basePath = canonicalBase.path.trimEnd(File.separatorChar) + File.separator
        val isContained = targetDir.path.startsWith(basePath) && targetDir.path != canonicalBase.path

        val normalizedTarget = targetDir.path.replace("/data/user/0/", "/data/data/")
        val normalizedBase = basePath.replace("/data/user/0/", "/data/data/")
        val isNormalizedContained = normalizedTarget.startsWith(normalizedBase) && normalizedTarget != normalizedBase.trimEnd(File.separatorChar)

        if (!isContained && !isNormalizedContained && !isDirectChild) {
            return SafeNdkDeleteResult.Rejected(
                version = version,
                reason = "Security violation: Target directory '${targetDir.path}' escapes base NDK directory '${canonicalBase.path}'."
            )
        }

        if (!targetDir.exists()) {
            return SafeNdkDeleteResult.Rejected(
                version = version,
                reason = "NDK directory does not exist: ${targetDir.absolutePath}"
            )
        }

        if (!targetDir.isDirectory) {
            return SafeNdkDeleteResult.Rejected(
                version = version,
                reason = "Target path is not a directory: ${targetDir.absolutePath}"
            )
        }

        // NDK marker check
        val hasMarker = NDK_MARKERS.any { marker ->
            File(targetDir, marker).exists()
        }
        if (!hasMarker) {
            return SafeNdkDeleteResult.Rejected(
                version = version,
                reason = "Directory does not appear to be an Android NDK installation (missing source.properties, ndk-build, or toolchains).",
                details = "Target: ${targetDir.absolutePath}"
            )
        }

        return null
    }

    fun getInstalledVersions(): List<String> {
        if (!baseNdkDir.exists() || !baseNdkDir.isDirectory) return emptyList()
        val dirs = baseNdkDir.listFiles()?.filter { isValidNdkDir(it) } ?: emptyList()
        return dirs.map { it.name }.sorted()
    }

    fun calculateTotalNdkSize(): Long {
        if (!baseNdkDir.exists() || !baseNdkDir.isDirectory) return 0L
        return baseNdkDir.listFiles()?.filter { isValidNdkDir(it) }?.sumOf { calculateDirectorySize(it) } ?: 0L
    }

    fun getActiveNdkVersion(): String? {
        val configured = try {
            IBuildSystemUtils.getNdkVersion() ?: prefManager?.getString("bs_native_kit_version", null)
        } catch (_: Throwable) {
            prefManager?.getString("bs_native_kit_version", null)
        }

        if (!configured.isNullOrBlank()) {
            val dir = File(baseNdkDir, configured)
            if (isValidNdkDir(dir)) {
                return configured
            }
        }

        // Fallback: If configured is invalid or null, but installed versions exist, pick the latest valid
        val installed = getInstalledVersions()
        if (installed.isNotEmpty()) {
            val fallback = installed.last()
            try {
                IBuildSystemUtils.setNdkVersion(fallback)
                prefManager?.putString("bs_native_kit_version", fallback)
                prefManager?.putBoolean("ndk_installed", true)
            } catch (_: Throwable) {}
            return fallback
        }

        return null
    }

    fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var size = 0L
        try {
            dir.walkTopDown()
                .onFail { _, exc -> log.debug("Skipping unreadable in size calc: {}", exc.message) }
                .forEach { file ->
                    try {
                        if (file.isFile && !Files.isSymbolicLink(file.toPath())) {
                            size += file.length()
                        }
                    } catch (_: Throwable) {}
                }
        } catch (_: Throwable) {}
        return size
    }

    @androidx.annotation.VisibleForTesting
    internal fun deleteRecursivelySafe(file: File) {
        val isLink = try {
            Files.isSymbolicLink(file.toPath())
        } catch (_: Throwable) {
            false
        }

        if (isLink) {
            try {
                Files.deleteIfExists(file.toPath())
            } catch (_: Throwable) {
                file.delete()
            }
            return
        }

        // If directory: Unlock parent directory BEFORE listing or deleting children.
        // In POSIX/Linux, to delete a child inside a directory, the parent directory MUST have write+execute permissions!
        if (file.isDirectory) {
            file.setWritable(true, false)
            file.setReadable(true, false)
            file.setExecutable(true, false)

            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    deleteRecursivelySafe(child)
                }
            }
        }

        // Unlock file/dir itself before deleting
        file.setWritable(true, false)
        file.setExecutable(true, false)

        val deleted = try {
            Files.deleteIfExists(file.toPath())
            true
        } catch (_: Throwable) {
            file.delete()
        }

        val stillExists = try {
            file.exists() || Files.isSymbolicLink(file.toPath())
        } catch (_: Throwable) {
            file.exists()
        }

        if (!deleted && stillExists) {
            file.setWritable(true, false)
            file.setExecutable(true, false)
            if (!file.delete() && (file.exists() || Files.isSymbolicLink(file.toPath()))) {
                throw IOException("Failed to delete file or directory: ${file.absolutePath}")
            }
        }
    }

    private fun executeRmRfFallback(targetDir: File, canonicalBase: File) {
        val targetPath = targetDir.canonicalFile.path
        val basePath = canonicalBase.path.trimEnd(File.separatorChar) + File.separator
        val normTarget = targetPath.replace("/data/user/0/", "/data/data/")
        val normBase = basePath.replace("/data/user/0/", "/data/data/")

        val isContained = (targetPath.startsWith(basePath) && targetPath != canonicalBase.path) ||
                (normTarget.startsWith(normBase) && normTarget != normBase.trimEnd(File.separatorChar))

        if (!isContained) {
            throw SecurityException("Security violation: Refusing to rm -rf uncontained directory: $targetPath")
        }

        // 1. Chmod all descendants to writable
        try {
            val chmodPb = ProcessBuilder("chmod", "-R", "u+wx", targetDir.absolutePath)
                .redirectErrorStream(true)
            val chmodProc = chmodPb.start()
            chmodProc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Throwable) {}

        // 2. Execute rm -rf
        try {
            val rmPb = ProcessBuilder("rm", "-rf", targetDir.absolutePath)
                .redirectErrorStream(true)
            val rmProc = rmPb.start()
            rmProc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        } catch (t: Throwable) {
            log.warn("ProcessBuilder rm -rf execution failed: {}", t.message)
        }
    }
}
