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

package com.tom.rv2ide.ui.themes

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.DynamicColors
import com.google.auto.service.AutoService
import com.tom.rv2ide.preferences.internal.GeneralPreferences
import com.tom.rv2ide.utils.isSystemInDarkMode

/**
 * Theme manager for AndroidIDE (ROXUM-ACS).
 * Dynamically adjusts application themes according to device configurations,
 * system day/night modes, Material You palettes, and edge-to-edge system bar contrast.
 *
 * @author Akash Yadav
 */
@Suppress("unused")
@AutoService(IThemeManager::class)
class ThemeManager : IThemeManager {

  /**
   * Apply the current theme to the given activity, adapting dynamically to the device's
   * display mode (day/night), Material You dynamic palettes, and system insets/status bars.
   */
  override fun applyTheme(activity: Activity) {
    val theme = getCurrentTheme()

    val isDarkMode = when (GeneralPreferences.uiMode) {
      AppCompatDelegate.MODE_NIGHT_YES -> true
      AppCompatDelegate.MODE_NIGHT_NO -> false
      else -> activity.isSystemInDarkMode()
    }

    if (theme == IDETheme.MATERIAL_YOU) {
      if (DynamicColors.isDynamicColorAvailable()) {
        DynamicColors.applyToActivityIfAvailable(activity)
      }
      syncSystemBarsAppearance(activity, isDarkMode)
      return
    }

    val style = when (theme) {
      IDETheme.CYAN_CREAM -> if (isDarkMode) theme.styleDark else theme.styleLight
      IDETheme.CRIMSON_BLACK -> if (isDarkMode) theme.styleDark else theme.styleLight
      IDETheme.SLATE_GREY -> if (isDarkMode) theme.styleDark else theme.styleLight
      else -> if (isDarkMode) theme.styleDark else theme.styleLight
    }

    activity.setTheme(style)
    syncSystemBarsAppearance(activity, isDarkMode)
  }

  private fun syncSystemBarsAppearance(activity: Activity, isDarkMode: Boolean) {
    try {
      val window = activity.window ?: return
      val insetsController = WindowInsetsControllerCompat(window, window.decorView)
      insetsController.isAppearanceLightStatusBars = !isDarkMode
      insetsController.isAppearanceLightNavigationBars = !isDarkMode
    } catch (_: Throwable) {
    }
  }

  /** Get the currently selected theme. */
  override fun getCurrentTheme(): IDETheme {
    return GeneralPreferences.selectedTheme?.let {
      try {
        IDETheme.valueOf(it)
      } catch (_: Throwable) {
        null
      }
    } ?: IDETheme.DEFAULT
  }
}
