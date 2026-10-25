/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.actions.build

import android.content.Context
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.core.content.ContextCompat
import com.tom.rv2ide.R.string
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.BaseBuildAction
import com.tom.rv2ide.actions.getContext
import com.tom.rv2ide.actions.markInvisible
import com.tom.rv2ide.actions.openApplicationModuleChooser
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.managers.PreferenceManager
import com.tom.rv2ide.models.ApkMetadata
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult
import com.tom.rv2ide.tooling.api.models.BasicAndroidVariantMetadata
import com.tom.rv2ide.ui.InstallingDialog
import com.tom.rv2ide.utils.ApkInstaller
import com.tom.rv2ide.utils.InstallationResultHandler
import com.tom.rv2ide.utils.IntentUtils
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.resolveAttr
import io.github.miyazkaori.silentinstaller.SilentInstaller
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import com.tom.rv2ide.builder.offline.OfflineBuildEngine
import com.tom.rv2ide.builder.offline.OfflineBuildProgressListener
import com.tom.rv2ide.builder.offline.OfflineBuildStage
import com.tom.rv2ide.builder.offline.OfflineStageResult
import com.tom.rv2ide.builder.SharedBuildOrchestrator
import com.tom.rv2ide.builder.BuildMode
import com.tom.rv2ide.builder.BuildProgressListener
import com.tom.rv2ide.preferences.internal.BuildPreferences
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.utils.flashInfo
import com.tom.rv2ide.utils.flashSuccess
import kotlinx.coroutines.Job
const val INSTALL_VIA_SHIZUKU = "ide.build.run.installViaShizuku"

/**
 * The 'Quick Run' and 'Cancel build' action in the editor activity.
 *
 * If a build is in progress, executing this action cancels the build. Otherwise, the selected build
 * variant is built and installed to the device.
 *
 * @author Akash Yadav
 */
class QuickRunWithCancellationAction(context: Context, override val order: Int) :
    BaseBuildAction() {

  private val prefManager: PreferenceManager
    get() = BaseApplication.getBaseInstance().prefManager

  private var isOfflineBuildRunning = false
  private var offlineBuildJob: Job? = null

  companion object {

    private val log = LoggerFactory.getLogger(QuickRunWithCancellationAction::class.java)
  }

  init {
    label = context.getString(R.string.quick_run_debug)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_run_outline)
    enabled = false
  }

  override val id: String = "ide.editor.build.quickRun"

  // Execute on UI thread as this action might try to show dialogs to the user
  override var requiresUIThread: Boolean = true

  override fun prepare(data: ActionData) {
    super.prepare(data)
    val context =
        data.getActivity()
            ?: run {
              markInvisible()
              return
            }

    if (data.isBuildInProgress()) {
      label = context.getString(R.string.title_cancel_build)
      icon = ContextCompat.getDrawable(context, R.drawable.ic_stop_daemons)
    } else {
      label = context.getString(R.string.quick_run_debug)
      icon = ContextCompat.getDrawable(context, R.drawable.ic_run_outline)
    }

    visible = true
    enabled = true
  }

  override fun createColorFilter(data: ActionData): ColorFilter? {
    return data.getContext()?.let {
      PorterDuffColorFilter(
          it.resolveAttr(if (data.isBuildInProgress()) R.attr.colorError else R.attr.colorSuccess),
          PorterDuff.Mode.SRC_ATOP,
      )
    }
  }

  override suspend fun execAction(data: ActionData): Boolean {
    if (data.isBuildInProgress()) {
      return cancelBuild()
    }

    return quickRun(data)
  }

  private fun quickRun(data: ActionData): Boolean {
    if (BuildPreferences.isOfflineNdkBuild) {
      return runOfflineBuild(data)
    }

    openApplicationModuleChooser(data) { module ->
      val activity = data.requireActivity()

      val variant =
          module.getSelectedVariant()
              ?: run {
                activity.flashError(activity.getString(R.string.err_selected_variant_not_found))
                return@openApplicationModuleChooser
              }

      val taskName = "${module.path}:${variant.mainArtifact.assembleTaskName}"
      log.info(
          "Running task '$taskName' to assemble variant '${variant.name}' of project '${module.path}'"
      )

      onModuleSelected(data, module, variant, taskName)
    }
    return true
  }

  private fun onModuleSelected(
      data: ActionData,
      module: AndroidModule,
      variant: BasicAndroidVariantMetadata,
      taskName: String,
  ) {

    val buildService =
        this.buildService
            ?: run {
              log.error("Cannot execute task '{}'. BuildService not found.", taskName)
              return
            }

    if (!buildService.isToolingServerStarted()) {
      flashError(string.msg_tooling_server_unavailable)
      return
    }

    val activity =
        data.getActivity()
            ?: run {
              log.error(
                  "Cannot execute task '{}'. Activity instance not provided in ActionData.",
                  taskName,
              )
              return
            }

    actionScope
        .launch(Dispatchers.Default) {
          activity.saveAllResult()

          val result = withContext(Dispatchers.IO) { buildService.executeTasks(taskName).get() }

          log.debug("Task execution result: {}", result)

          if (result?.isSuccessful != true) {
            log.error("Tasks failed to execute: '{}'", taskName)
            return@launch
          }

          handleResult(data, result, module, variant)
        }
        .invokeOnCompletion { error ->
          if (error != null) {
            log.error("Failed to run '{}'", taskName, error)
          }
        }
  }

  private fun cancelBuild(): Boolean {
    if (isOfflineBuildRunning || SharedBuildOrchestrator.isBuildRunning()) {
      log.info("Sending offline build cancellation request...")
      SharedBuildOrchestrator.cancelCurrentBuild()
      offlineBuildJob?.cancel()
      isOfflineBuildRunning = false
      flashInfo("Offline build cancelled.")
      return true
    }

    log.info("Sending build cancellation request...")
    val builder = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
    if (builder?.isToolingServerStarted() != true) {
      flashError(com.tom.rv2ide.projects.R.string.msg_tooling_server_unavailable)
      return false
    }

    builder?.cancelCurrentBuild()?.whenComplete { result, error ->
      if (error != null) {
        log.error("Failed to send build cancellation request", error)
        return@whenComplete
      }

      if (!result.wasEnqueued) {
        log.warn(
            "Unable to enqueue cancellation request reason={} reason.message={}",
            result.failureReason,
            result.failureReason!!.message,
        )
        return@whenComplete
      }

      log.info("Build cancellation request was successfully enqueued...")
    }

    return true
  }

  private fun handleResult(
      data: ActionData,
      result: TaskExecutionResult?,
      module: AndroidModule,
      variant: BasicAndroidVariantMetadata,
  ) {
    if (result == null || !result.isSuccessful) {
      log.debug("Cannot install APK. Task execution failed.")
      return
    }

    log.debug("Installing APK(s) for project: '{}' variant: '{}'", module.path, variant.name)

    val main = variant.mainArtifact
    val outputListingFile = main.assembleTaskOutputListingFile
    if (outputListingFile == null) {
      log.error("No output listing file provided with project model")
      return
    }

    log.trace("Parsing metadata")
    val apkFile = ApkMetadata.findApkFile(outputListingFile)
    if (apkFile == null) {
      log.error("No apk file specified in output listing file: {}", outputListingFile)
      return
    }

    if (!apkFile.exists()) {
      log.error("APK file specified in output listing file does not exist! {}", apkFile)
      return
    }

    install(data, apkFile)
  }

  private fun install(data: ActionData, apk: File) {
    val activity =
        data.getActivity()
            ?: run {
              log.error("Cannot install APK. Unable to get activity instance.")
              return
            }

    if (!apk.exists()) {
      log.error("APK file does not exist!")
      return
    }

    log.debug("Installing APK: {}", apk)

    if (prefManager.getBoolean(INSTALL_VIA_SHIZUKU, false)) {
      var installingDialog: InstallingDialog? = null
      activity.runOnUiThread {
        installingDialog = InstallingDialog.create(activity, "Installing…")
        installingDialog?.show()
      }

      log.info("Installing APK via shizuku: {}", apk)
      SilentInstaller.install(
          apk,
          object : SilentInstaller.InstallCallback {
            override fun onPermissionDenied() {
              activity.runOnUiThread {
                installingDialog?.dismiss()
                flashError("Permission denied")
                // fallback
                activity.runOnUiThread {
                  ApkInstaller.installApk(
                      activity,
                      InstallationResultHandler.createEditorActivitySender(activity),
                      apk,
                      activity.installationSessionCallback(),
                  )
                }
              }
            }

            override fun onSuccess(status: Int, message: String) {
              activity.runOnUiThread { installingDialog?.dismiss() }
              if (BuildPreferences.isOfflineNdkBuild) {
                val pkg = try {
                  OfflineBuildEngine().inspectProject(IProjectManager.getInstance().projectDir)?.packageName
                } catch (e: Exception) {
                  null
                }
                if (pkg != null) {
                  log.info("Launching offline application via Shizuku: {}", pkg)
                  IntentUtils.launchApp(data.requireActivity(), pkg, logError = false)
                  return
                }
              }
              openApplicationModuleChooser(data) { app ->
                val variant = app.getSelectedVariant()

                log.debug("Selected variant: {}", variant?.name)

                if (variant == null) {
                  activity.runOnUiThread { flashError("Selected build variant not found") }
                  return@openApplicationModuleChooser
                }

                val applicationId = variant.mainArtifact.applicationId
                if (applicationId == null) {
                  log.error(
                      "Unable to launch application. variant.mainArtifact.applicationId is null"
                  )
                  activity.runOnUiThread {
                    flashError("Cannot run application. Unable to determine package name.")
                  }
                  return@openApplicationModuleChooser
                }

                log.info("Launching application: {}", applicationId)

                IntentUtils.launchApp(data.requireActivity(), applicationId, logError = false)
              }
            }

            override fun onFailure(status: Int, message: String, tr: Throwable?) {
              activity.runOnUiThread {
                installingDialog?.dismiss()
                flashError("Install failure: $message")
              }
              log.error(message)
            }
          },
      )

      return
    }

    activity.runOnUiThread {
      ApkInstaller.installApk(
          activity,
          InstallationResultHandler.createEditorActivitySender(activity),
          apk,
          activity.installationSessionCallback(),
      )
    }
  }

  private fun runOfflineBuild(data: ActionData): Boolean {
    val activity = data.getActivity()
        ?: run {
          flashError("Cannot run build. Activity instance is not available.")
          return false
        }

    val projectDir = try {
      IProjectManager.getInstance().projectDir
    } catch (e: Exception) {
      null
    }

    if (projectDir == null || !projectDir.exists()) {
      activity.flashError("Cannot start offline build. No active project directory found.")
      return false
    }

    isOfflineBuildRunning = true
    activity.editorViewModel.isBuildInProgress = true
    activity.invalidateOptionsMenu()

    activity.runOnUiThread {
      activity.showBuildOutput()
      activity.content.bottomSheet.clearBuildOutput()
      activity.setStatus("Offline Build: Starting...")
      activity.appendBuildOutput(
          "======================================================================\n" +
          "Offline NDK Builder: ${projectDir.name}\n" +
          "======================================================================\n"
      )
    }

    offlineBuildJob = actionScope.launch(Dispatchers.Default) {
      SharedBuildOrchestrator.registerActiveJob(offlineBuildJob)
      try {
        activity.saveAllResult()

        val customTmp = File(activity.cacheDir, "offline_build_tmp")
        try {
          com.tom.rv2ide.problems.ProjectProblemRegistry.getInstance().setProjectRoot(projectDir)
          com.tom.rv2ide.problems.ProjectProblemRegistry.getInstance().onBuildStarted()
        } catch (_: Throwable) {}
        val result = SharedBuildOrchestrator.executeBuild(
            projectDir = projectDir,
            customTmpDir = customTmp,
            requestedMode = BuildMode.OFFLINE_NDK,
            listener = object : BuildProgressListener {
              override fun onStageStarted(stage: String, description: String) {
                log.info("[OfflineBuild] [Stage Started: {}] {}", stage, description)
                activity.runOnUiThread {
                  activity.setStatus("Building: $stage")
                  activity.appendBuildOutput("\n> Task :${stage.lowercase()}\n$description\n")
                }
              }

              override fun onStageLog(stage: String, line: String) {
                log.debug("[OfflineBuild] [{}] {}", stage, line)
                activity.runOnUiThread {
                  activity.appendBuildOutput("$line\n")
                }
              }

              override fun onStageCompleted(stage: String, success: Boolean, durationMs: Long, message: String?) {
                log.info(
                    "[OfflineBuild] [Stage Done: {}] Success={} in {}ms",
                    stage,
                    success,
                    durationMs,
                )
                activity.runOnUiThread {
                  if (success) {
                    activity.appendBuildOutput(
                        "[SUCCESS] $stage completed in ${durationMs}ms\n"
                    )
                  } else {
                    activity.appendBuildOutput(
                        "[FAILED] $stage: ${message ?: "failed"}\n"
                    )
                  }
                }
              }
            }
        )

        try {
          val rawLog = SharedBuildOrchestrator.getRawLog(result.runId, 2000)
          com.tom.rv2ide.problems.ProjectProblemRegistry.getInstance().recordBuildOutput(rawLog, projectDir, "OFFLINE_NDK")
        } catch (_: Throwable) {}

        withContext(Dispatchers.Main) {
          if (result.isSuccess && result.apkFile != null) {
            activity.setStatus("BUILD SUCCESSFUL")
            activity.appendBuildOutput(
                "\n======================================================================\n" +
                "BUILD SUCCESSFUL in ${result.durationMs}ms\n" +
                "Output APK: ${result.apkFile.absolutePath}\n" +
                "Size: ${result.apkSizeBytes} bytes\n" +
                "======================================================================\n"
            )
            activity.flashSuccess(
                "Offline Build Succeeded! APK: ${result.apkFile.name} (${result.apkSizeBytes} bytes)"
            )
            install(data, result.apkFile)
          } else {
            val err = result.errorMessage ?: "Build failed without details."
            log.error("Offline build failed: {}", err)
            activity.setStatus("BUILD FAILED")
            activity.appendBuildOutput(
                "\n======================================================================\n" +
                "BUILD FAILED in ${result.durationMs}ms\n" +
                "Stage: ${result.failedStage ?: "Unknown"}\n" +
                "Error: $err\n" +
                "======================================================================\n"
            )
            activity.flashError("Offline build failed: $err")
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("Offline Build Failed")
                .setMessage("Stage: ${result.failedStage ?: "Unknown"}\n\n$err")
                .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
                .show()
          }
        }
      } catch (e: kotlinx.coroutines.CancellationException) {
        log.info("Offline build was cancelled by user.")
        withContext(Dispatchers.Main) {
          activity.setStatus("BUILD CANCELLED")
          activity.appendBuildOutput("\nBUILD CANCELLED by user.\n")
          activity.flashInfo("Offline build cancelled.")
        }
      } catch (e: Exception) {
        log.error("Offline build execution error", e)
        withContext(Dispatchers.Main) {
          activity.setStatus("BUILD ERROR")
          activity.appendBuildOutput("\nBUILD ERROR: ${e.message}\n")
          activity.flashError("Offline build error: ${e.message}")
        }
      } finally {
        isOfflineBuildRunning = false
        withContext(Dispatchers.Main) {
          activity.editorViewModel.isBuildInProgress = false
          activity.invalidateOptionsMenu()
        }
      }
    }

    return true
  }

  private fun ActionData.isBuildInProgress(): Boolean {
    if (isOfflineBuildRunning || SharedBuildOrchestrator.isBuildRunning()) {
      return true
    }
    if (BuildPreferences.isOfflineNdkBuild) {
      return isOfflineBuildRunning
    }
    val context = getActivity()
    val buildService = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
    return context?.editorViewModel?.let { it.isInitializing || it.isBuildInProgress } == true ||
        buildService?.isBuildInProgress == true
  }
}
