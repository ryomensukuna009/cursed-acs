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

package com.tom.rv2ide.preferences.internal

/** @author Akash Yadav */
@Suppress("MemberVisibilityCanBePrivate")
object BuildPreferences {

  const val BUILD_MODE = "idepref_build_mode"
  const val BUILD_MODE_GRADLE = "gradle"
  const val BUILD_MODE_OFFLINE_NDK = "offline_ndk"

  /**
   * The current build mode.
   * Can be either [BUILD_MODE_GRADLE] or [BUILD_MODE_OFFLINE_NDK].
   * Defaults to [BUILD_MODE_GRADLE] for missing, empty, or invalid stored values.
   */
  var buildMode: String
    get() {
      val value = prefManager.getString(BUILD_MODE, BUILD_MODE_GRADLE)
      return resolveBuildMode(value)
    }
    set(value) {
      prefManager.putString(BUILD_MODE, resolveBuildMode(value))
    }

  /**
   * Helper function to normalize or validate a build mode string.
   * Missing, empty, or unrecognized values always fall back to [BUILD_MODE_GRADLE].
   */
  fun resolveBuildMode(value: String?): String {
    return if (value == BUILD_MODE_OFFLINE_NDK) BUILD_MODE_OFFLINE_NDK else BUILD_MODE_GRADLE
  }

  /** Whether the build mode is currently set to [BUILD_MODE_OFFLINE_NDK]. */
  val isOfflineNdkBuild: Boolean
    get() = buildMode == BUILD_MODE_OFFLINE_NDK

  const val STACKTRACE = "idepref_gradleCmd_stacktrace"
  const val DEBUG = "idepref_gradleCmd_debug"
  const val SCAN = "idepref_gradleCmd_scan"
  const val INFO = "idepref_gradleCmd_info"
  const val WARNING_MODE = "idepref_gradleCmd_warningMode"
  const val BUILD_CACHE = "idepref_gradleCmd_buildCache"
  const val OFFLINE_MODE = "idepref_gradleCmd_offlineMode"

  const val GRADLE_COMMANDS = "idepref_build_gradleCommands"
  const val GRADLE_CLEAR_CACHE = "idepref_build_gradleClearCache"
  const val CUSTOM_GRADLE_INSTALLATION = "idepref_build_customGradleInstallation"
  const val LAUNCH_APP_AFTER_INSTALL = "ide.build.run.launchAppAfterInstall"
  const val INSTALL_VIA_SHIZUKU = "ide.build.run.installViaShizuku"
  const val PREF_JAVA_HOME = "ide.build.javaHome"

  const val ENABLE_BUILD_OUTPUT = "ide.build.enableBuildOutput"
  const val DEPENDENCIES_UPDATER = "ide.build.enableDependenciesUpdater"
  const val KT_INDEXING_NOTIFICATION = "ide.build.enableKotlinIndexingNotification"

  var isKtIndexingNotificationEnabled: Boolean
    get() = prefManager.getBoolean(KT_INDEXING_NOTIFICATION, true)
    set(value) = prefManager.putBoolean(KT_INDEXING_NOTIFICATION, value)

  var isDependenciesUpdaterEnabled: Boolean
    get() = prefManager.getBoolean(DEPENDENCIES_UPDATER, false)
    set(value) = prefManager.putBoolean(DEPENDENCIES_UPDATER, value)

  var isBuildOutputEnabled: Boolean
    get() = prefManager.getBoolean(ENABLE_BUILD_OUTPUT, true)
    set(value) = prefManager.putBoolean(ENABLE_BUILD_OUTPUT, value)

  /** Switch for Gradle `--debug` option. */
  var isDebugEnabled: Boolean
    get() = prefManager.getBoolean(DEBUG)
    set(enabled) {
      prefManager.putBoolean(DEBUG, enabled)
    }

  /** Switch for Gradle `--scan` option. */
  var isScanEnabled: Boolean
    get() = prefManager.getBoolean(SCAN)
    set(enabled) {
      prefManager.putBoolean(SCAN, enabled)
    }

  /** Switch for Gradle `--warning-mode all` option. */
  var isWarningModeAllEnabled: Boolean
    get() = prefManager.getBoolean(WARNING_MODE)
    set(enabled) {
      prefManager.putBoolean(WARNING_MODE, enabled)
    }

  /** Switch for Gradle `--build-cache` option. */
  var isBuildCacheEnabled: Boolean
    get() = prefManager.getBoolean(BUILD_CACHE)
    set(enabled) {
      prefManager.putBoolean(BUILD_CACHE, enabled)
    }

  /** Switch for Gradle `--offline` option. */
  var isOfflineEnabled: Boolean
    get() = prefManager.getBoolean(OFFLINE_MODE)
    set(enabled) {
      prefManager.putBoolean(OFFLINE_MODE, enabled)
    }

  /** Switch for Gradle `--stacktrace` option. */
  var isStacktraceEnabled: Boolean
    get() = prefManager.getBoolean(STACKTRACE)
    set(value) {
      prefManager.putBoolean(STACKTRACE, value)
    }

  /** Switch for Gradle `--info` option. */
  var isInfoEnabled: Boolean
    get() = prefManager.getBoolean(INFO, GeneralPreferences.isFirstBuild)
    set(enabled) {
      prefManager.putBoolean(INFO, enabled)
    }

  /** Custom Gradle installation directory path. */
  var gradleInstallationDir: String
    get() = prefManager.getString(CUSTOM_GRADLE_INSTALLATION, "")
    set(value) {
      prefManager.putString(CUSTOM_GRADLE_INSTALLATION, value)
    }

  /** Whether the app should be launched automatically after installation (after build). */
  var launchAppAfterInstall: Boolean
    get() = prefManager.getBoolean(LAUNCH_APP_AFTER_INSTALL, true)
    set(value) {
      prefManager.putBoolean(LAUNCH_APP_AFTER_INSTALL, value)
    }

  var installViaShizuku: Boolean
    get() = prefManager.getBoolean(INSTALL_VIA_SHIZUKU, false)
    set(value) {
      prefManager.putBoolean(INSTALL_VIA_SHIZUKU, value)
    }

  /** The selected Java installation. */
  var javaHome: String
    get() = prefManager.getString(PREF_JAVA_HOME, "")
    set(value) {
      prefManager.putString(PREF_JAVA_HOME, value)
    }
}
