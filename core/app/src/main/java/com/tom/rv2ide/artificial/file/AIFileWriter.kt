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

package com.tom.rv2ide.artificial.file

import android.content.Context
import com.tom.rv2ide.artificial.permissions.AIPermissionManager
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/

class AIFileWriter(private val context: Context) {

    private val permissionManager = AIPermissionManager(context)
    private val backupDir = File(context.filesDir, "ai_backups")

    init {
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }
    }

    fun writeFile(
        filePath: String,
        content: String,
        createBackup: Boolean = true
    ): FileWriteResult {
        if (!permissionManager.isFileWriteEnabled()) {
            return FileWriteResult.PermissionDenied("File writing is disabled")
        }

        if (!permissionManager.isPathAllowed(filePath)) {
            return FileWriteResult.PermissionDenied("Path not in allowed directories")
        }

        val actionType = if (File(filePath).exists()) {
            com.tom.rv2ide.artificial.policy.ActionType.MODIFY_FILE
        } else {
            com.tom.rv2ide.artificial.policy.ActionType.CREATE_FILE
        }
        val isAllowed = com.tom.rv2ide.artificial.policy.ActionPolicyGate.isActionPermittedSync(
            context = context,
            actionType = actionType,
            targetDetail = filePath
        )
        if (!isAllowed) {
            return FileWriteResult.PermissionDenied("Action denied by AI Action Policy Gate")
        }

        val file = File(filePath)

        // Create parent directories if they don't exist
        try {
            file.parentFile?.mkdirs()
        } catch (e: Exception) {
            return FileWriteResult.Error("Failed to create directories: ${e.message}")
        }

        // Backup existing file
        if (file.exists() && createBackup && permissionManager.isAutoBackupEnabled()) {
            val backupResult = createBackup(file)
            if (backupResult is FileWriteResult.Error) {
                return backupResult
            }
        }

        // Write the file
        return try {
            file.writeText(content)
            FileWriteResult.Success(filePath, backupCreated = createBackup && file.exists())
        } catch (e: IOException) {
            FileWriteResult.Error("Failed to write file: ${e.message}")
        } catch (e: SecurityException) {
            FileWriteResult.Error("Security error: ${e.message}")
        }
    }

    private fun createBackup(file: File): FileWriteResult {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Date())
            val backupFileName = "${file.nameWithoutExtension}_backup_$timestamp.${file.extension}"
            val backupFile = File(backupDir, backupFileName)
            
            file.copyTo(backupFile, overwrite = true)
            FileWriteResult.Success(backupFile.absolutePath, backupCreated = true)
        } catch (e: Exception) {
            FileWriteResult.Error("Failed to create backup: ${e.message}")
        }
    }

    fun getBackups(): List<BackupFile> {
        return backupDir.listFiles()?.map { file ->
            BackupFile(
                path = file.absolutePath,
                name = file.name,
                timestamp = file.lastModified(),
                size = file.length()
            )
        }?.sortedByDescending { it.timestamp } ?: emptyList()
    }

    fun restoreBackup(backupPath: String, targetPath: String): FileWriteResult {
        return try {
            val backupFile = File(backupPath)
            val targetFile = File(targetPath)
            
            if (!backupFile.exists()) {
                return FileWriteResult.Error("Backup file not found")
            }
            
            targetFile.parentFile?.mkdirs()
            backupFile.copyTo(targetFile, overwrite = true)
            FileWriteResult.Success(targetPath, backupCreated = false)
        } catch (e: Exception) {
            FileWriteResult.Error("Failed to restore backup: ${e.message}")
        }
    }

    fun deleteBackup(backupPath: String): Boolean {
        return try {
            File(backupPath).delete()
        } catch (e: Exception) {
            false
        }
    }

    fun clearAllBackups(): Boolean {
        return try {
            backupDir.listFiles()?.forEach { it.delete() }
            true
        } catch (e: Exception) {
            false
        }
    }
}

sealed class FileWriteResult {
    data class Success(val path: String, val backupCreated: Boolean) : FileWriteResult()
    data class PermissionDenied(val reason: String) : FileWriteResult()
    data class Error(val message: String) : FileWriteResult()
}

data class BackupFile(
    val path: String,
    val name: String,
    val timestamp: Long,
    val size: Long
)