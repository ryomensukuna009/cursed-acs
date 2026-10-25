/*
 *  This file is part of AndroidCodeStudio (ROXUM-ACS).
 *  Sidebar action that opens the categorized secondary tools overflow sheet.
 */

package com.tom.rv2ide.actions.sidebar

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tom.rv2ide.R
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.requireContext
import com.tom.rv2ide.activities.AssetStudioActivity
import com.tom.rv2ide.activities.IDEConfigurations
import com.tom.rv2ide.activities.PreferencesActivity
import com.tom.rv2ide.activities.editor.BaseEditorActivity
import com.tom.rv2ide.databinding.LayoutSidebarOverflowSheetBinding
import com.tom.rv2ide.utils.EditorSidebarActions
import kotlin.reflect.KClass
import kotlinx.coroutines.launch

class MoreToolsSidebarAction(context: Context, override val order: Int) : AbstractSidebarAction() {

    override val id: String = ID
    override val fragmentClass: KClass<out Fragment>? = null

    init {
        label = "More"
        subtitle = "IDE tools & overflow"
        icon = ContextCompat.getDrawable(context, com.tom.rv2ide.resources.R.drawable.ic_nav_more_badass)
        iconRes = com.tom.rv2ide.resources.R.drawable.ic_nav_more_badass
    }

    companion object {
        const val ID = "ide.editor.sidebar.more_tools"
    }

    override suspend fun execAction(data: ActionData): Any {
        val context = data.requireContext()
        val dialog = BottomSheetDialog(context)
        val binding = LayoutSidebarOverflowSheetBinding.inflate(dialog.layoutInflater)
        dialog.setContentView(binding.root)

        val isOffline = com.tom.rv2ide.preferences.internal.BuildPreferences.isOfflineNdkBuild
        binding.switchBuildMode.isChecked = isOffline
        fun updateBuildModeUI(offline: Boolean) {
            if (offline) {
                binding.tvBuildModeTitle.text = "Offline NDK Builder Active"
                binding.tvBuildModeSubtitle.text = "Fast offline native build pipeline without Gradle daemon"
            } else {
                binding.tvBuildModeTitle.text = "Standard Gradle Active"
                binding.tvBuildModeSubtitle.text = "Standard Gradle daemon with multi-module resolution"
            }
        }
        updateBuildModeUI(isOffline)

        binding.cardBuildModeToggle.setOnClickListener {
            binding.switchBuildMode.isChecked = !binding.switchBuildMode.isChecked
        }

        binding.switchBuildMode.setOnCheckedChangeListener { _, isChecked ->
            val targetMode = if (isChecked) {
                com.tom.rv2ide.preferences.internal.BuildPreferences.BUILD_MODE_OFFLINE_NDK
            } else {
                com.tom.rv2ide.preferences.internal.BuildPreferences.BUILD_MODE_GRADLE
            }
            com.tom.rv2ide.preferences.internal.BuildPreferences.buildMode = targetMode
            updateBuildModeUI(isChecked)

            (context as? BaseEditorActivity)?.let { activity ->
                activity.content.bottomSheet.setStatus("", android.view.Gravity.CENTER)
            }

            val modeName = if (isChecked) "Offline NDK Builder" else "Standard Gradle"
            com.tom.rv2ide.utils.flashInfo("Switched build mode to: " + modeName)
        }

        binding.toolGitClient.setOnClickListener {
            dialog.dismiss()
            val projectDir = com.tom.rv2ide.projects.IProjectManager.getInstance()?.projectDir
            val hasGitRepo = projectDir != null && java.io.File(projectDir, ".git").exists()
            if (hasGitRepo) {
                EditorSidebarActions.switchToSecondaryAction(GitClientAction.ID)
            } else {
                // Show choice dialog: Open Git Client or Clone Repository
                val choices = arrayOf("Open Git Client", "Clone Git Repository")
                com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                    .setTitle("Git Version Control")
                    .setItems(choices) { _, which ->
                        when (which) {
                            0 -> EditorSidebarActions.switchToSecondaryAction(GitClientAction.ID)
                            1 -> {
                                (context as? BaseEditorActivity)?.let { activity ->
                                    val cloneSheet = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
                                    val sheetBinding = com.tom.rv2ide.databinding.BottomsheetGitCloneBinding.inflate(activity.layoutInflater)
                                    sheetBinding.btnCancel.setOnClickListener { cloneSheet.dismiss() }
                                    sheetBinding.btnClone.setOnClickListener {
                                        val url = sheetBinding.etRepoUrl.text?.toString()?.trim()
                                        if (url.isNullOrBlank()) {
                                            com.tom.rv2ide.utils.flashError("Please enter a repository URL")
                                            return@setOnClickListener
                                        }
                                        cloneSheet.dismiss()
                                        val repoName = url.substringAfterLast('/').substringBeforeLast(".git")
                                        val destDir = java.io.File(com.tom.rv2ide.utils.Environment.PROJECTS_DIR, repoName)
                                        if (destDir.exists()) {
                                            com.tom.rv2ide.utils.flashError("Target directory already exists: " + destDir.name)
                                            return@setOnClickListener
                                        }
                                        com.tom.rv2ide.utils.flashInfo("Cloning repository: " + repoName)
                                        // Execute clone via JGit in background
                                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                            try {
                                                val cloneCmd = org.eclipse.jgit.api.Git.cloneRepository()
                                                    .setURI(url)
                                                    .setDirectory(destDir)
                                                val git = cloneCmd.call()
                                                git?.close()
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    com.tom.rv2ide.utils.flashSuccess("Repository cloned to: " + destDir.name)
                                                    com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                                                        .setTitle("Repository Cloned")
                                                        .setMessage("Successfully cloned to '" + destDir.name + "'. Open project now?")
                                                        .setPositiveButton("Open") { _, _ ->
                                                            activity.openProject(destDir)
                                                        }
                                                        .setNegativeButton("Later", null)
                                                        .show()
                                                }
                                            } catch (e: Exception) {
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    com.tom.rv2ide.utils.flashError("Clone failed: " + e.message)
                                                }
                                            }
                                        }
                                    }
                                    cloneSheet.setContentView(sheetBinding.root)
                                    cloneSheet.show()
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        binding.toolBuildVariants.setOnClickListener {
            dialog.dismiss()
            EditorSidebarActions.switchToSecondaryAction("ide.editor.sidebar.buildVariants")
        }

        binding.toolAssetStudio.setOnClickListener {
            dialog.dismiss()
            context.startActivity(Intent(context, AssetStudioActivity::class.java))
        }

        binding.toolSubModules.setOnClickListener {
            dialog.dismiss()
            EditorSidebarActions.switchToSecondaryAction("ide.editor.sidebar.subModule")
        }

        binding.toolNdkManagement.setOnClickListener {
            dialog.dismiss()
            context.startActivity(Intent(context, IDEConfigurations::class.java))
        }

        binding.toolTerminal.setOnClickListener {
            dialog.dismiss()
            (context as? BaseEditorActivity)?.let {
                // Execute terminal or show bottom sheet terminal
            }
        }

        binding.toolPreferences.setOnClickListener {
            dialog.dismiss()
            context.startActivity(Intent(context, PreferencesActivity::class.java))
        }

        binding.toolCloseProject.setOnClickListener {
            dialog.dismiss()
            (context as? BaseEditorActivity)?.doConfirmProjectClose()
        }

        dialog.show()
        return true
    }
}
