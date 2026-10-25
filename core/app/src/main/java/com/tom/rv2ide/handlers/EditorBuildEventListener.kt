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

/*
 * Modified by Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 * ++ ndk checks
 */

package com.tom.rv2ide.handlers

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import com.tom.rv2ide.preferences.internal.BuildPreferences
import com.tom.rv2ide.preferences.internal.GeneralPreferences
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.resources.R.string
import com.tom.rv2ide.services.builder.GradleBuildService
import com.tom.rv2ide.tooling.api.messages.result.BuildInfo
import com.tom.rv2ide.tooling.events.ProgressEvent
import com.tom.rv2ide.tooling.events.configuration.ProjectConfigurationStartEvent
import com.tom.rv2ide.tooling.events.task.TaskStartEvent
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.flashSuccess
import java.io.File
import java.lang.ref.WeakReference
import org.slf4j.LoggerFactory

/**
 * Handles events received from [GradleBuildService] updates [EditorHandlerActivity].
 *
 * @author Akash Yadav
 */
class EditorBuildEventListener : GradleBuildService.EventListener {

  private var enabled = true
  private var activityReference: WeakReference<EditorHandlerActivity> = WeakReference(null)
  private var ndkCheckPerformed = false
  private val buildOutputBuffer = StringBuilder()

  companion object {

    private val log = LoggerFactory.getLogger(EditorBuildEventListener::class.java)
  }

  private val _activity: EditorHandlerActivity?
    get() = activityReference.get()

  private val activity: EditorHandlerActivity
    get() = checkNotNull(activityReference.get()) { "Activity reference has been destroyed!" }

  fun setActivity(activity: EditorHandlerActivity) {
    this.activityReference = WeakReference(activity)
    this.enabled = true
    this.ndkCheckPerformed = false // Reset NDK check when activity is set
  }

  fun release() {
    activityReference.clear()
    this.enabled = false
    this.ndkCheckPerformed = false
    synchronized(buildOutputBuffer) { buildOutputBuffer.setLength(0) }
  }

  override fun prepareBuild(buildInfo: BuildInfo) {
    checkActivity("prepareBuild") ?: return

    synchronized(buildOutputBuffer) { buildOutputBuffer.setLength(0) }
    try {
      val projectDir = com.tom.rv2ide.projects.IProjectManager.getInstance()?.projectDir
      com.tom.rv2ide.problems.ProjectProblemRegistry.getInstance().setProjectRoot(projectDir)
      com.tom.rv2ide.problems.ProjectProblemRegistry.getInstance().onBuildStarted()
    } catch (_: Throwable) {}

    val isFirstBuild = GeneralPreferences.isFirstBuild
    activity.setStatus(
        activity.getString(if (isFirstBuild) string.preparing_first else string.preparing)
    )

    if (isFirstBuild) {
      activity.showFirstBuildNotice()
    }

    // Check for NDK requirements before build starts
    checkNdkRequirements()

    activity.editorViewModel.isBuildInProgress = true
    activity.content.bottomSheet.clearBuildOutput()

    if (buildInfo.tasks.isNotEmpty()) {
      activity.content.bottomSheet.appendBuildOut(
          activity.getString(R.string.title_run_tasks) + " : " + buildInfo.tasks
      )
    }
  }

  override fun onBuildSuccessful(tasks: List<String?>) {
    checkActivity("onBuildSuccessful") ?: return

    try {
      val output = synchronized(buildOutputBuffer) { buildOutputBuffer.toString() }
      val projectDir = com.tom.rv2ide.projects.IProjectManager.getInstance()?.projectDir
      com.tom.rv2ide.problems.ProjectProblemRegistry.getInstance().recordBuildOutput(output, projectDir, "GRADLE")
    } catch (_: Throwable) {}

    analyzeCurrentFile()

    GeneralPreferences.isFirstBuild = false
    activity.editorViewModel.isBuildInProgress = false

    activity.flashSuccess(R.string.build_status_sucess)
  }

  override fun onProgressEvent(event: ProgressEvent) {
    checkActivity("onProgressEvent") ?: return

    if (event is ProjectConfigurationStartEvent || event is TaskStartEvent) {
      activity.setStatus(event.descriptor.displayName)
    }
  }

  override fun onBuildFailed(tasks: List<String?>) {
    checkActivity("onBuildFailed") ?: return

    try {
      val output = synchronized(buildOutputBuffer) { buildOutputBuffer.toString() }
      val projectDir = com.tom.rv2ide.projects.IProjectManager.getInstance()?.projectDir
      com.tom.rv2ide.problems.ProjectProblemRegistry.getInstance().recordBuildOutput(output, projectDir, "GRADLE")
    } catch (_: Throwable) {}

    analyzeCurrentFile()

    GeneralPreferences.isFirstBuild = false
    activity.editorViewModel.isBuildInProgress = false

    activity.flashError(R.string.build_status_failed)
  }

  override fun onOutput(line: String?) {
    checkActivity("onOutput") ?: return

    line?.let {
      activity.appendBuildOutput(it)
      synchronized(buildOutputBuffer) {
        buildOutputBuffer.appendLine(it)
      }
    }
    // TODO This can be handled better when ProgressEvents are received from Tooling API server
    if (line!!.contains("BUILD SUCCESSFUL") || line.contains("BUILD FAILED")) {
      activity.setStatus(line)
    }
  }

  /** Check if the project requires NDK and show dialog if NDK is not installed */
  private fun checkNdkRequirements() {
    if (ndkCheckPerformed) {
      return // Only check once per activity session
    }

    ndkCheckPerformed = true

    if (BuildPreferences.isOfflineNdkBuild) {
      return
    }

    try {
      val projectManager = IProjectManager.getInstance()
      val projectRoot = File(projectManager.projectDirPath)

      if (com.tom.rv2ide.ndk.NdkResolver.hasNativeFiles(projectRoot)) {
        val activeNdk = com.tom.rv2ide.ndk.NdkResolver.findActiveNdkDir(projectRoot)
        if (activeNdk == null) {
          com.tom.rv2ide.ndk.NdkResolver.showNdkNotInstalledDialog(activity)
        } else {
          com.tom.rv2ide.ndk.NdkResolver.ensureLocalProperties(projectRoot, activeNdk)
        }
      }
    } catch (e: Exception) {
      log.warn("Failed to check NDK requirements", e)
    }
  }

  /** Check if the project contains native files that require NDK */
  private fun hasNativeFiles(projectRoot: File): Boolean {
    return com.tom.rv2ide.ndk.NdkResolver.hasNativeFiles(projectRoot)
  }

  /** Check if NDK is installed */
  private fun isNdkInstalled(): Boolean {
    return com.tom.rv2ide.ndk.NdkResolver.isNdkInstalled()
  }

  /** Show dialog when NDK is not installed but required */
  private fun showNdkNotInstalledDialog(context: Context, onDismiss: () -> Unit = {}) {
    com.tom.rv2ide.ndk.NdkResolver.showNdkNotInstalledDialog(context, onDismiss)
  }

  private fun analyzeCurrentFile() {
    checkActivity("analyzeCurrentFile") ?: return

    val editorView = _activity?.getCurrentEditor()
    if (editorView != null) {
      val editor = editorView.editor
      editor?.analyze()
    }
  }

  private fun checkActivity(action: String): EditorHandlerActivity? {
    if (!enabled) return null

    return _activity.also {
      if (it == null) {
        log.warn("[{}] Activity reference has been destroyed!", action)
        enabled = false
      }
    }
  }
}
