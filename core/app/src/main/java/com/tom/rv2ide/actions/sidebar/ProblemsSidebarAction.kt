/*
 *  This file is part of AndroidCodeStudio (ROXUM-ACS).
 *  Sidebar action for showing the AIDE-style Problems panel.
 */

package com.tom.rv2ide.actions.sidebar

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.tom.rv2ide.R
import com.tom.rv2ide.fragments.sidebar.ProblemsFragment
import kotlin.reflect.KClass

class ProblemsSidebarAction(context: Context, override val order: Int) : AbstractSidebarAction() {

  override val id: String = ID
  override val fragmentClass: KClass<out Fragment> = ProblemsFragment::class

  init {
    label = context.getString(R.string.title_problems)
    subtitle = context.getString(R.string.msg_problems_summary)
    icon = ContextCompat.getDrawable(context, com.tom.rv2ide.resources.R.drawable.ic_nav_problems_badass)
    iconRes = com.tom.rv2ide.resources.R.drawable.ic_nav_problems_badass
  }

  companion object {
    const val ID = "ide.editor.sidebar.problems"
  }
}
