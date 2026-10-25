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
import androidx.core.content.ContextCompat
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.BaseBuildAction
import com.tom.rv2ide.fragments.RunTasksDialogFragment
import com.tom.rv2ide.preferences.internal.BuildPreferences
import com.tom.rv2ide.resources.R
import com.tom.rv2ide.utils.flashInfo

/** @author Akash Yadav */
class RunTasksAction(context: Context, override val order: Int) : BaseBuildAction() {
  override val id: String = "ide.editor.build.runTasks"
  private var dialog: RunTasksDialogFragment? = null

  init {
    label = context.getString(R.string.title_run_tasks)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_run_tasks)
  }

  override suspend fun execAction(data: ActionData): Any {
    if (BuildPreferences.isOfflineNdkBuild) {
      return false
    }
    dialog?.dismiss()
    dialog = null
    dialog = RunTasksDialogFragment()
    return dialog!!
  }

  override fun postExec(data: ActionData, result: Any) {
    if (BuildPreferences.isOfflineNdkBuild) {
      data.getActivity()?.flashInfo("Offline NDK mode is active. Tap the Play button to build, or switch Build Mode in Settings to Gradle.")
      return
    }

    if (result !is RunTasksDialogFragment) {
      return
    }

    val activity = data.getActivity()!!
    result.show(activity.supportFragmentManager, this.id)
  }

  override fun destroy() {
    super.destroy()
    try {
      dialog?.dismiss()
    } catch (e: Exception) {
      // ignored
    }
    dialog = null
  }
}
