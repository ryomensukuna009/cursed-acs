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

package com.tom.rv2ide.preferences

import android.content.Context
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.R as MainR
import com.tom.rv2ide.app.configuration.IJdkDistributionProvider
import com.tom.rv2ide.models.JdkDistribution
import com.tom.rv2ide.preferences.internal.BuildPreferences.BUILD_MODE
import com.tom.rv2ide.preferences.internal.BuildPreferences.BUILD_MODE_GRADLE
import com.tom.rv2ide.preferences.internal.BuildPreferences.BUILD_MODE_OFFLINE_NDK
import com.tom.rv2ide.preferences.internal.BuildPreferences.CUSTOM_GRADLE_INSTALLATION
import com.tom.rv2ide.preferences.internal.BuildPreferences.ENABLE_BUILD_OUTPUT
import com.tom.rv2ide.preferences.internal.BuildPreferences.DEPENDENCIES_UPDATER
import com.tom.rv2ide.preferences.internal.BuildPreferences.KT_INDEXING_NOTIFICATION
import com.tom.rv2ide.preferences.internal.BuildPreferences.GRADLE_CLEAR_CACHE
import com.tom.rv2ide.preferences.internal.BuildPreferences.GRADLE_COMMANDS
import com.tom.rv2ide.preferences.internal.BuildPreferences.INSTALL_VIA_SHIZUKU
import com.tom.rv2ide.preferences.internal.BuildPreferences.LAUNCH_APP_AFTER_INSTALL
import com.tom.rv2ide.preferences.internal.BuildPreferences.PREF_JAVA_HOME
import com.tom.rv2ide.preferences.internal.BuildPreferences.buildMode
import com.tom.rv2ide.preferences.internal.BuildPreferences.gradleInstallationDir
import com.tom.rv2ide.preferences.internal.BuildPreferences.installViaShizuku
import com.tom.rv2ide.preferences.internal.BuildPreferences.isBuildCacheEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isBuildOutputEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isDependenciesUpdaterEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isKtIndexingNotificationEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isDebugEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isInfoEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isOfflineEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isScanEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isStacktraceEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.isWarningModeAllEnabled
import com.tom.rv2ide.preferences.internal.BuildPreferences.javaHome
import com.tom.rv2ide.preferences.internal.BuildPreferences.launchAppAfterInstall
import com.tom.rv2ide.resources.R.drawable
import com.tom.rv2ide.resources.R.string
import com.tom.rv2ide.tasks.executeAsync
import com.tom.rv2ide.utils.Environment.GRADLE_USER_HOME
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.flashInfo
import com.tom.rv2ide.utils.flashSuccess
import java.io.File
import kotlin.reflect.KMutableProperty0
import kotlinx.parcelize.Parcelize

@Parcelize
class BuildAndRunPreferences(
    override val key: String = "idepref_build_n_run",
    override val title: Int = string.idepref_build_title,
    override val summary: Int? = string.idepref_buildnrun_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(BuildSystemOptions())
    addPreference(GradleOptions())
    addPreference(RunOptions())
  }
}

@Parcelize
private class BuildSystemOptions(
    override val key: String = "idepref_build_system_category",
    override val title: Int = MainR.string.idepref_build_mode_category,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  init {
    addPreference(BuildModePreference())
    addPreference(BuildToolchainManagerPreference())
  }
}

@Parcelize
class BuildToolchainManagerPreference(
    override val key: String = "idepref_build_toolchain_manager",
    override val title: Int = MainR.string.title_install_tools,
    override val summary: Int? = MainR.string.subtitle_install_tools,
    override val icon: Int? = drawable.ic_terminal,
    override val dialogMessage: Int? = null,
) : DialogPreference() {

  override fun onConfigureDialog(preference: Preference, dialog: MaterialAlertDialogBuilder) {
    super.onConfigureDialog(preference, dialog)
    val context = preference.context
    val report = com.tom.rv2ide.ndk.ToolchainDoctor.runDiagnostics(context)
    val activeLabel = if (buildMode == BUILD_MODE_OFFLINE_NDK) "Offline NDK Build" else "Standard Gradle"
    val msg = buildString {
      append("Active Build Mode: ")
      append(activeLabel)
      append("\n\nToolchain Doctor Diagnosis:\n")
      append(report.summary)
      append("\n\nComponents:\n")
      report.components.forEach { comp ->
        val icon = when (comp.status) {
          com.tom.rv2ide.ndk.ToolchainStatus.INSTALLED_READY -> "✓"
          com.tom.rv2ide.ndk.ToolchainStatus.NOT_REQUIRED -> "-"
          com.tom.rv2ide.ndk.ToolchainStatus.DEGRADED -> "⚠"
          com.tom.rv2ide.ndk.ToolchainStatus.MISSING -> "✗"
        }
        append("[$icon] ${comp.name}: ${comp.status.label}")
        if (comp.detectedVersion != null) append(" (${comp.detectedVersion})")
        if (comp.reasonNotReady != null && comp.status != com.tom.rv2ide.ndk.ToolchainStatus.INSTALLED_READY && comp.status != com.tom.rv2ide.ndk.ToolchainStatus.NOT_REQUIRED) {
          append("\n    Reason: ${comp.reasonNotReady}")
        }
        append("\n")
      }
    }
    dialog.setTitle("Build Toolchain Doctor")
    dialog.setMessage(msg)
    dialog.setPositiveButton("Safe Repair") { dlg, _ ->
      dlg.dismiss()
      val repairResult = com.tom.rv2ide.ndk.ToolchainDoctor.performSafeRepair(context)
      com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
          .setTitle(if (repairResult.success) "Safe Repair Complete" else "Safe Repair Status")
          .setMessage(repairResult.message)
          .setPositiveButton(android.R.string.ok, null)
          .show()
    }
    dialog.setNeutralButton("IDE Configurations") { dlg, _ ->
      dlg.dismiss()
      context.startActivity(android.content.Intent(context, com.tom.rv2ide.activities.IDEConfigurations::class.java))
    }
    dialog.setNegativeButton(android.R.string.cancel, null)
  }
}

@Parcelize
class BuildModePreference(
    override val key: String = BUILD_MODE,
    override val title: Int = MainR.string.idepref_build_mode_title,
    override val icon: Int? = drawable.ic_settings,
) : SingleChoicePreference() {

  override fun getEntries(preference: Preference): Array<PreferenceChoices.Entry> {
    val context = preference.context
    val currentMode = buildMode
    val readiness = com.tom.rv2ide.builder.offline.OfflineBuildReadiness.checkReadiness(context)
    val ndkStatus = if (readiness.isReady) "Ready" else "Missing toolchain"
    return arrayOf(
        PreferenceChoices.Entry(
            context.getString(MainR.string.build_mode_gradle),
            currentMode == BUILD_MODE_GRADLE,
            BUILD_MODE_GRADLE,
        ),
        PreferenceChoices.Entry(
            "${context.getString(MainR.string.build_mode_offline_ndk)} ($ndkStatus)",
            currentMode == BUILD_MODE_OFFLINE_NDK,
            BUILD_MODE_OFFLINE_NDK,
        ),
    )
  }

  override fun onChoiceConfirmed(
      preference: Preference,
      entry: PreferenceChoices.Entry?,
      position: Int,
  ) {
    super.onChoiceConfirmed(preference, entry, position)
    val chosenMode = entry?.data as? String ?: BUILD_MODE_GRADLE
    if (chosenMode == BUILD_MODE_OFFLINE_NDK) {
      val readiness = com.tom.rv2ide.builder.offline.OfflineBuildReadiness.checkReadiness(preference.context)
      if (readiness.isReady) {
        buildMode = BUILD_MODE_OFFLINE_NDK
        flashSuccess(preference.context.getString(MainR.string.build_mode_offline_ndk))
      } else {
        MaterialAlertDialogBuilder(preference.context)
            .setTitle(MainR.string.build_mode_offline_ndk)
            .setMessage(readiness.summary)
            .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
            .show()
        buildMode = BUILD_MODE_GRADLE
      }
    } else {
      buildMode = BUILD_MODE_GRADLE
    }
    updatePreference(preference)
  }

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).also { preference -> updatePreference(preference) }
  }

  private fun updatePreference(preference: Preference) {
    val context = preference.context
    val modeString = when (buildMode) {
      BUILD_MODE_OFFLINE_NDK -> {
        val readiness = com.tom.rv2ide.builder.offline.OfflineBuildReadiness.checkReadiness(context)
        val status = if (readiness.isReady) "Ready" else "Missing compiler / toolchain"
        "${context.getString(MainR.string.build_mode_offline_ndk)} — $status"
      }
      else -> context.getString(MainR.string.build_mode_gradle)
    }
    preference.summary = modeString
  }
}

@Parcelize
private class EnableBuildOutput(
    override val key: String = ENABLE_BUILD_OUTPUT,
    override val title: Int = R.string.idepref_enableBuildOutput_title,
    override val summary: Int? = R.string.idepref_enableBuildOutput_summary,
    override val icon: Int? = drawable.ic_text,
) :
    SwitchPreference(
        setValue = ::isBuildOutputEnabled::set,
        getValue = ::isBuildOutputEnabled::get,
    )
    
@Parcelize
private class DependenciesUpdater(
    override val key: String = DEPENDENCIES_UPDATER,
    override val title: Int = R.string.idepref_dependencies_updater_title,
    override val summary: Int? = R.string.idepref_dependencies_updater_summary,
    override val icon: Int? = MainR.drawable.ic_update,
) :
    SwitchPreference(
        setValue = ::isDependenciesUpdaterEnabled::set,
        getValue = ::isDependenciesUpdaterEnabled::get,
    )

@Parcelize
private class KotlinIndexingNotification(
    override val key: String = KT_INDEXING_NOTIFICATION,
    override val title: Int = R.string.idepref_kotlin_indexing_notif_title,
    override val summary: Int? = R.string.idepref_kotlin_indexing_notif_summary,
    override val icon: Int? = MainR.drawable.ic_notification,
) :
    SwitchPreference(
        setValue = ::isKtIndexingNotificationEnabled::set,
        getValue = ::isKtIndexingNotificationEnabled::get,
    )

@Parcelize
private class GradleOptions(
    override val key: String = "idepref_build_gradle",
    override val title: Int = string.gradle,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  init {
    addPreference(EnableBuildOutput())
    addPreference(DependenciesUpdater())
    addPreference(KotlinIndexingNotification())
    addPreference(GradleCommands())
    addPreference(GradleDistrubution())
    addPreference(GradleJDKVersionPreference())
    addPreference(GradleClearCache())
  }
}

@Parcelize
private class GradleCommands(
    override val key: String = GRADLE_COMMANDS,
    override val title: Int = string.idepref_build_customgradlecommands_title,
    override val summary: Int? = string.idepref_build_customgradlecommands_summary,
    override val icon: Int? = drawable.ic_bash_commands,
) : PropertyBasedMultiChoicePreference() {

  override fun getProperties(): Map<String, KMutableProperty0<Boolean>> {
    return linkedMapOf(
        "--stacktrace" to ::isStacktraceEnabled,
        "--info" to ::isInfoEnabled,
        "--debug" to ::isDebugEnabled,
        "--scan" to ::isScanEnabled,
        "--warning-mode all" to ::isWarningModeAllEnabled,
        "--build-cache" to ::isBuildCacheEnabled,
        "--offline" to ::isOfflineEnabled,
    )
  }
}

@Parcelize
private class GradleDistrubution(
    override val key: String = CUSTOM_GRADLE_INSTALLATION,
    override val title: Int = string.idepref_title_customGradleInstallation,
    override val summary: Int? = string.idepref_msg_customGradleInstallation,
    override val icon: Int? = drawable.ic_gradle,
) : EditTextPreference() {

  override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
    gradleInstallationDir = newValue as String? ?: ""
    return true
  }

  override fun onConfigureTextInput(input: TextInputLayout) {
    input.setStartIconDrawable(drawable.ic_gradle)
    input.setHint(string.msg_gradle_installation_path)
    input.helperText = input.context.getString(string.msg_gradle_installation_input_help)
    input.isCounterEnabled = false
    input.editText!!.setText(gradleInstallationDir)
  }
}

@Parcelize
private class GradleClearCache(
    override val key: String = GRADLE_CLEAR_CACHE,
    override val title: Int = string.idepref_build_clearCache_title,
    override val summary: Int? = string.idepref_build_clearCache_summary,
    override val icon: Int? = drawable.ic_delete,
    override val dialogMessage: Int? = string.msg_clear_cache,
) : DialogPreference() {

  override fun onConfigureDialog(preference: Preference, dialog: MaterialAlertDialogBuilder) {
    super.onConfigureDialog(preference, dialog)
    dialog.setPositiveButton(string.yes) { dlg, _ ->
      dlg.dismiss()
      executeAsync(callable = this::deleteCaches) {
        if (it == true) {
          flashSuccess(string.deleted)
        } else {
          flashError(string.delete_failed)
        }
      }
    }
    dialog.setNegativeButton(string.no) { dlg, _ -> dlg.dismiss() }
  }

  private fun deleteCaches(): Boolean {
    val caches = File(GRADLE_USER_HOME, "caches")
    if (caches.exists()) {
      return caches.deleteRecursively()
    }
    return false
  }
}

@Parcelize
private class RunOptions(
    override val key: String = "ide.build.runOptions",
    override val title: Int = R.string.title_run_options,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  init {
    addPreference(InstallViaShizuku())
    addPreference(LaunchAppAfterInstall())
  }
}

@Parcelize
private class InstallViaShizuku(
    override val key: String = INSTALL_VIA_SHIZUKU,
    override val title: Int = R.string.idepref_installViaShizuku_title,
    override val summary: Int? = R.string.idepref_installViaShizuku_summary,
    override val icon: Int? = drawable.ic_apk_install,
) : SwitchPreference(setValue = ::installViaShizuku::set, getValue = ::installViaShizuku::get)

@Parcelize
private class LaunchAppAfterInstall(
    override val key: String = LAUNCH_APP_AFTER_INSTALL,
    override val title: Int = R.string.idepref_launchAppAfterInstall_title,
    override val summary: Int? = R.string.idepref_launchAppAfterInstall_summary,
    override val icon: Int? = drawable.ic_open_external,
) :
    SwitchPreference(
        setValue = ::launchAppAfterInstall::set,
        getValue = ::launchAppAfterInstall::get,
    )

@Parcelize
class GradleJDKVersionPreference(
    override val key: String = PREF_JAVA_HOME,
    override val title: Int = R.string.idepref_jdkVersion_title,
    override val icon: Int? = R.drawable.ic_language_java,
) : SingleChoicePreference() {

  override fun getEntries(preference: Preference): Array<PreferenceChoices.Entry> {
    val distributions = IJdkDistributionProvider.getInstance().installedDistributions
    check(distributions.isNotEmpty()) { "No JDK installations are available." }

    return distributions
        .map { dist -> PreferenceChoices.Entry(dist.javaVersion, javaHome == dist.javaHome, dist) }
        .toTypedArray()
  }

  override fun onChoiceConfirmed(
      preference: Preference,
      entry: PreferenceChoices.Entry?,
      position: Int,
  ) {
    super.onChoiceConfirmed(preference, entry, position)
    javaHome = (entry?.data as? JdkDistribution?)?.javaHome ?: ""
    updatePreference(preference)
  }

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).also { preference -> updatePreference(preference) }
  }

  private fun updatePreference(preference: Preference) {
    val jdkDistProvider = IJdkDistributionProvider.getInstance()
    val javaVersion = jdkDistProvider.forJavaHome(javaHome)?.javaVersion ?: "<unknown>"

    preference.summary =
        preference.context.getString(R.string.idepref_jdkVersion_summary, javaVersion)
    preference.isEnabled = jdkDistProvider.installedDistributions.size > 1
  }
}
