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

package com.tom.rv2ide.templates.preferences

import android.content.Context
import android.content.SharedPreferences

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

object WizardPreferences {
  private const val PREFS_NAME = "atc_wizard_prefs"
  private const val KEY_LAST_SAVE_LOCATION = "last_save_location"
  private const val KEY_RECENT_PROJECTS = "recent_projects"
  private const val KEY_HIDDEN_PROJECTS = "hidden_projects"
  private const val MAX_RECENT_PROJECTS = 10

  @androidx.annotation.VisibleForTesting
  var prefsOverrideForTesting: SharedPreferences? = null

  private fun getPrefs(context: Context? = null): SharedPreferences {
    return prefsOverrideForTesting ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: throw IllegalStateException("Context required")
  }

  fun getLastSaveLocation(context: Context? = null): String? {
    return getPrefs(context).getString(KEY_LAST_SAVE_LOCATION, null)
  }

  fun setLastSaveLocation(context: Context? = null, path: String) {
    getPrefs(context).edit().putString(KEY_LAST_SAVE_LOCATION, path).apply()
  }

  // Add a project to recent projects list
  fun addRecentProject(context: Context? = null, projectPath: String) {
    unhideProject(context, projectPath)
    val prefs = getPrefs(context)
    val normPath = try {
      java.io.File(projectPath).canonicalPath
    } catch (_: Exception) {
      projectPath
    }
    val recentProjects = getRecentProjects(context).toMutableList()

    recentProjects.removeAll { it == normPath || it == projectPath }
    recentProjects.add(0, normPath)

    val trimmedList = recentProjects.take(MAX_RECENT_PROJECTS)
    prefs.edit().putString(KEY_RECENT_PROJECTS, trimmedList.joinToString(",")).apply()
  }

  // Get list of recent project paths
  fun getRecentProjects(context: Context? = null): List<String> {
    val prefs = getPrefs(context)
    val projectsString = prefs.getString(KEY_RECENT_PROJECTS, "") ?: ""

    if (projectsString.isEmpty()) {
      return emptyList()
    }

    return projectsString
        .split(",")
        .filter { it.isNotBlank() }
        .mapNotNull { path ->
          val file = java.io.File(path)
          // Only return paths that still exist
          if (file.exists() && file.isDirectory) path else null
        }
  }

  // Check if a project is in recent list
  fun isRecentProject(context: Context? = null, projectPath: String): Boolean {
    return getRecentProjects(context).contains(projectPath)
  }

  // Get the position/rank of a project in recent list (0 = most recent)
  fun getRecentProjectRank(context: Context? = null, projectPath: String): Int {
    val normPath = try {
      java.io.File(projectPath).canonicalPath
    } catch (_: Exception) {
      projectPath
    }
    val recents = getRecentProjects(context)
    val idx = recents.indexOf(normPath)
    if (idx >= 0) return idx
    return recents.indexOf(projectPath)
  }

  // Remove a project from recent projects list without touching files on disk (Task 2)
  fun removeRecentProject(context: Context? = null, projectPath: String): Boolean {
    val prefs = getPrefs(context)
    val normPath = try {
      java.io.File(projectPath).canonicalPath
    } catch (_: Exception) {
      projectPath
    }
    val recentProjects = getRecentProjects(context).toMutableList()
    val removed = recentProjects.removeAll { it == normPath || it == projectPath }
    if (removed) {
      prefs.edit().putString(KEY_RECENT_PROJECTS, recentProjects.joinToString(",")).apply()
    }
    try {
      if (com.tom.rv2ide.preferences.internal.GeneralPreferences.lastOpenedProject == projectPath ||
          com.tom.rv2ide.preferences.internal.GeneralPreferences.lastOpenedProject == normPath) {
        com.tom.rv2ide.preferences.internal.GeneralPreferences.lastOpenedProject = com.tom.rv2ide.preferences.internal.GeneralPreferences.NO_OPENED_PROJECT
      }
    } catch (_: Throwable) {}
    return removed
  }

  // Hide project from IDE list (Remove from IDE)
  fun hideProject(context: Context? = null, projectPath: String) {
    val prefs = getPrefs(context)
    val normPath = try {
      java.io.File(projectPath).canonicalPath
    } catch (_: Exception) {
      projectPath
    }
    val hidden = getHiddenProjects(context).toMutableSet()
    hidden.add(normPath)
    hidden.add(projectPath)
    prefs.edit().putString(KEY_HIDDEN_PROJECTS, hidden.joinToString(",")).apply()
  }

  // Unhide project (e.g. when reopened or reimported)
  fun unhideProject(context: Context? = null, projectPath: String) {
    val prefs = getPrefs(context)
    val normPath = try {
      java.io.File(projectPath).canonicalPath
    } catch (_: Exception) {
      projectPath
    }
    val hidden = getHiddenProjects(context).toMutableSet()
    hidden.remove(normPath)
    hidden.remove(projectPath)
    prefs.edit().putString(KEY_HIDDEN_PROJECTS, hidden.joinToString(",")).apply()
  }

  fun isProjectHidden(context: Context? = null, projectPath: String): Boolean {
    val normPath = try {
      java.io.File(projectPath).canonicalPath
    } catch (_: Exception) {
      projectPath
    }
    val hidden = getHiddenProjects(context)
    return hidden.contains(normPath) || hidden.contains(projectPath)
  }

  fun getHiddenProjects(context: Context? = null): Set<String> {
    val prefs = getPrefs(context)
    val str = prefs.getString(KEY_HIDDEN_PROJECTS, "") ?: ""
    if (str.isBlank()) return emptySet()
    return str.split(",").filter { it.isNotBlank() }.toSet()
  }

}
