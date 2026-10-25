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

import com.tom.rv2ide.utils.Environment
import java.io.File
import org.slf4j.LoggerFactory

/**
 * Main orchestrator for the Standalone Offline APK Builder.
 *
 * Builds Java/XML and C/C++ JNI Android applications on-device without invoking Gradle.
 */
class OfflineBuildEngine(
    private val inspector: OfflineProjectInspector = OfflineProjectInspector(),
    private val resourceCompiler: Aapt2ResourceCompiler = Aapt2ResourceCompiler(),
    private val nativeBuilder: NdkNativeBuilder = NdkNativeBuilder(),
    private val javaCompiler: JavaSourceCompiler = JavaSourceCompiler(),
    private val dexConverter: DexConverter = DexConverter(),
    private val zipAligner: ApkZipAligner = ApkZipAligner(),
    private val signerService: ApkSignerService = ApkSignerService()
) {

  companion object {
    private val log = LoggerFactory.getLogger(OfflineBuildEngine::class.java)
  }

  fun inspectProject(projectDir: File): OfflineProjectContract? {
    return when (val res = inspector.inspect(projectDir)) {
      is ProjectContractResult.Valid -> res.contract
      else -> null
    }
  }

  fun build(
      projectDir: File,
      listener: OfflineBuildProgressListener? = null,
      customTmpDir: File? = null
  ): OfflineBuildResult {
    val overallStartTime = System.currentTimeMillis()
    val stages = mutableListOf<OfflineStageResult>()

    fun recordAndNotify(result: OfflineStageResult): Boolean {
      stages.add(result)
      listener?.onStageCompleted(result.stage, result)
      if (!result.success) {
        log.error("Stage '{}' failed: {}", result.stage.title, result.message)
        if (!result.details.isNullOrBlank()) {
          result.details.lineSequence().forEach { line ->
            if (line.isNotBlank()) {
              listener?.onStageLog(result.stage, line)
            }
          }
        } else if (!result.message.isNullOrBlank()) {
          listener?.onStageLog(result.stage, "error: ${result.message}")
        }
      } else {
        log.info("Stage '{}' completed successfully in {} ms", result.stage.title, result.durationMs)
      }
      return result.success
    }

    // -------------------------------------------------------------
    // Stage 1: Inspect Project Contract
    // -------------------------------------------------------------
    val inspectStartTime = System.currentTimeMillis()
    listener?.onStageStarted(OfflineBuildStage.INSPECT, "Validating project structure and contract...")

    val contractResult = inspector.inspect(projectDir)
    val contract = when (contractResult) {
      is ProjectContractResult.Valid -> contractResult.contract
      is ProjectContractResult.Invalid -> {
        val inspectResult = OfflineStageResult(
            stage = OfflineBuildStage.INSPECT,
            success = false,
            message = contractResult.reason,
            durationMs = System.currentTimeMillis() - inspectStartTime,
            details = contractResult.details
        )
        recordAndNotify(inspectResult)
        return OfflineBuildResult(
            success = false,
            finalApk = null,
            apkSizeBytes = 0L,
            packageName = null,
            totalDurationMs = System.currentTimeMillis() - overallStartTime,
            stages = stages,
            errorMessage = contractResult.reason,
            failedStage = OfflineBuildStage.INSPECT
        )
      }
    }

    val inspectSuccess = OfflineStageResult(
        stage = OfflineBuildStage.INSPECT,
        success = true,
        message = "Project contract valid for '${contract.packageName}' (${contract.javaFiles.size} Java files, native=${contract.hasNativeCode}).",
        durationMs = System.currentTimeMillis() - inspectStartTime
    )
    recordAndNotify(inspectSuccess)

    // -------------------------------------------------------------
    // Stage 2: Workspace Preparation
    // -------------------------------------------------------------
    val workspaceStartTime = System.currentTimeMillis()
    listener?.onStageStarted(OfflineBuildStage.WORKSPACE_PREP, "Preparing isolated build workspace...")

    val baseTmpDir = when {
      customTmpDir != null && (customTmpDir.isDirectory || customTmpDir.mkdirs()) && customTmpDir.canWrite() -> customTmpDir
      Environment.TMP_DIR != null && (Environment.TMP_DIR.isDirectory || Environment.TMP_DIR.mkdirs()) && Environment.TMP_DIR.canWrite() -> Environment.TMP_DIR
      else -> File(projectDir, "build/offline_tmp")
    }
    var workspace = OfflineWorkspace.create(baseTmpDir, projectDir.name)

    if (!workspace.prepare()) {
      val fallbackTmpDir = File(projectDir, "build/offline_tmp")
      if (baseTmpDir.absolutePath != fallbackTmpDir.absolutePath) {
        log.warn("Failed to prepare workspace in {}, falling back to {}", baseTmpDir.absolutePath, fallbackTmpDir.absolutePath)
        workspace = OfflineWorkspace.create(fallbackTmpDir, projectDir.name)
      }
    }

    if (!workspace.prepare()) {
      val wsError = OfflineStageResult(
          stage = OfflineBuildStage.WORKSPACE_PREP,
          success = false,
          message = "Failed to create isolated build directories in ${workspace.rootDir.absolutePath}",
          durationMs = System.currentTimeMillis() - workspaceStartTime
      )
      recordAndNotify(wsError)
      return OfflineBuildResult(
          success = false,
          finalApk = null,
          apkSizeBytes = 0L,
          packageName = contract.packageName,
          totalDurationMs = System.currentTimeMillis() - overallStartTime,
          stages = stages,
          errorMessage = wsError.message,
          failedStage = OfflineBuildStage.WORKSPACE_PREP
      )
    }

    val wsSuccess = OfflineStageResult(
        stage = OfflineBuildStage.WORKSPACE_PREP,
        success = true,
        message = "Workspace prepared at ${workspace.rootDir.absolutePath}",
        durationMs = System.currentTimeMillis() - workspaceStartTime
    )
    recordAndNotify(wsSuccess)

    try {
      // -------------------------------------------------------------
      // Stage 3: Resources (AAPT2)
      // -------------------------------------------------------------
      listener?.onStageStarted(OfflineBuildStage.RESOURCES, "Compiling and linking resources with AAPT2...")
      val resResult = resourceCompiler.compileAndLink(contract, workspace, listener)
      if (!recordAndNotify(resResult)) {
        return buildFailureResult(overallStartTime, stages, contract.packageName, resResult)
      }

      // -------------------------------------------------------------
      // Stage 4: Native JNI Compilation (ndk-build)
      // -------------------------------------------------------------
      listener?.onStageStarted(OfflineBuildStage.NATIVE, "Building native C/C++ libraries with ndk-build...")
      val nativeOutput = nativeBuilder.build(contract, workspace, listener)
      if (!recordAndNotify(nativeOutput.stageResult)) {
        return buildFailureResult(overallStartTime, stages, contract.packageName, nativeOutput.stageResult)
      }

      // -------------------------------------------------------------
      // Stage 5: Java Compilation (javac)
      // -------------------------------------------------------------
      listener?.onStageStarted(OfflineBuildStage.JAVA_COMPILE, "Compiling Java sources against android.jar...")
      val javacResult = javaCompiler.compile(contract, workspace, listener)
      if (!recordAndNotify(javacResult)) {
        return buildFailureResult(overallStartTime, stages, contract.packageName, javacResult)
      }

      // -------------------------------------------------------------
      // Stage 6: DEX Conversion (D8)
      // -------------------------------------------------------------
      listener?.onStageStarted(OfflineBuildStage.DEX, "Translating bytecode to DEX via in-process D8...")
      val dexResult = dexConverter.convertToDex(contract, workspace, listener)
      if (!recordAndNotify(dexResult)) {
        return buildFailureResult(overallStartTime, stages, contract.packageName, dexResult)
      }

      // -------------------------------------------------------------
      // Stage 7: Packaging & Alignment
      // -------------------------------------------------------------
      listener?.onStageStarted(OfflineBuildStage.PACKAGE, "Assembling and aligning APK entries...")
      val dexFiles = workspace.dexDir.listFiles()?.filter { it.extension == "dex" } ?: emptyList()
      val packageResult = zipAligner.packageAndAlign(
          resourcesApFile = workspace.resourcesApFile,
          dexFiles = dexFiles,
          nativeLibraries = nativeOutput.compiledLibraries,
          outputApkFile = workspace.alignedApkFile,
          listener = listener
      )
      if (!recordAndNotify(packageResult)) {
        return buildFailureResult(overallStartTime, stages, contract.packageName, packageResult)
      }

      // -------------------------------------------------------------
      // Stage 8: Signing (apksig)
      // -------------------------------------------------------------
      listener?.onStageStarted(OfflineBuildStage.SIGN, "Signing APK with in-process apksig...")
      val signResult = signerService.signAndVerify(
          inputApk = workspace.alignedApkFile,
          outputApk = workspace.signedApkFile,
          minSdkVersion = contract.minSdkVersion,
          listener = listener
      )
      if (!recordAndNotify(signResult)) {
        return buildFailureResult(overallStartTime, stages, contract.packageName, signResult)
      }

      // -------------------------------------------------------------
      // Stage 9: Finalize & Copy to Project Output
      // -------------------------------------------------------------
      val finalizeStartTime = System.currentTimeMillis()
      listener?.onStageStarted(OfflineBuildStage.VERIFY, "Copying APK to project build directory...")

      val projectBinDir = File(contract.moduleDir, "bin")
      projectBinDir.mkdirs()
      val finalApkName = "${projectDir.name}-debug.apk"
      val destinationApk = File(projectBinDir, finalApkName)

      workspace.signedApkFile.copyTo(destinationApk, overwrite = true)
      val finalSize = destinationApk.length()

      val finalizeResult = OfflineStageResult(
          stage = OfflineBuildStage.VERIFY,
          success = true,
          message = "Final debug APK produced at ${destinationApk.absolutePath} ($finalSize bytes).",
          durationMs = System.currentTimeMillis() - finalizeStartTime
      )
      recordAndNotify(finalizeResult)

      return OfflineBuildResult(
          success = true,
          finalApk = destinationApk,
          apkSizeBytes = finalSize,
          packageName = contract.packageName,
          totalDurationMs = System.currentTimeMillis() - overallStartTime,
          stages = stages
      )
    } finally {
      // Clean temporary scratch files
      workspace.clean()
    }
  }

  private fun buildFailureResult(
      overallStartTime: Long,
      stages: List<OfflineStageResult>,
      packageName: String?,
      failedStageResult: OfflineStageResult
  ): OfflineBuildResult {
    return OfflineBuildResult(
        success = false,
        finalApk = null,
        apkSizeBytes = 0L,
        packageName = packageName,
        totalDurationMs = System.currentTimeMillis() - overallStartTime,
        stages = stages,
        errorMessage = failedStageResult.message + if (failedStageResult.details != null) "\n\nDetails:\n${failedStageResult.details}" else "",
        failedStage = failedStageResult.stage
    )
  }
}
