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

/**
 * Stage identifiers in the standalone offline build pipeline.
 */
enum class OfflineBuildStage(val title: String) {
  INSPECT("Project Inspection"),
  WORKSPACE_PREP("Workspace Preparation"),
  RESOURCES("Resource Compilation & Linking (AAPT2)"),
  NATIVE("Native JNI Compilation (ndk-build)"),
  JAVA_COMPILE("Java Compilation (javac)"),
  DEX("DEX Bytecode Conversion (D8)"),
  PACKAGE("APK Packaging & Alignment"),
  SIGN("APK Signing (apksig)"),
  VERIFY("Verification & Finalization")
}

/**
 * Result of a single build stage.
 */
data class OfflineStageResult(
    val stage: OfflineBuildStage,
    val success: Boolean,
    val message: String,
    val durationMs: Long,
    val details: String? = null
)

/**
 * Comprehensive result of the entire offline build.
 */
data class OfflineBuildResult(
    val success: Boolean,
    val finalApk: File?,
    val apkSizeBytes: Long,
    val packageName: String?,
    val totalDurationMs: Long,
    val stages: List<OfflineStageResult>,
    val errorMessage: String? = null,
    val failedStage: OfflineBuildStage? = null
)

/**
 * Listener for reporting progress to UI and log receivers.
 */
interface OfflineBuildProgressListener {
  fun onStageStarted(stage: OfflineBuildStage, description: String)
  fun onStageLog(stage: OfflineBuildStage, message: String)
  fun onStageCompleted(stage: OfflineBuildStage, result: OfflineStageResult)
}

/**
 * Verified project contract representation.
 */
data class OfflineProjectContract(
    val projectDir: File,
    val moduleDir: File,
    val manifestFile: File,
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val minSdkVersion: Int,
    val targetSdkVersion: Int,
    val applicationLabel: String?,
    val javaSourceDirs: List<File>,
    val javaFiles: List<File>,
    val resDir: File?,
    val assetsDir: File?,
    val jniDir: File?,
    val androidMkFile: File?,
    val hasNativeCode: Boolean,
    val declaredActivities: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

/**
 * Result of inspecting a project directory.
 */
sealed class ProjectContractResult {
  data class Valid(val contract: OfflineProjectContract) : ProjectContractResult()
  data class Invalid(val reason: String, val details: String? = null) : ProjectContractResult()
}
