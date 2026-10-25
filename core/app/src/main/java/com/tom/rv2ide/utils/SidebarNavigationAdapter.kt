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

package com.tom.rv2ide.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.tom.rv2ide.R
import com.tom.rv2ide.actions.SidebarActionItem

/**
 * Data class representing a navigation item in the sidebar.
 *
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
data class SidebarNavigationItem(
    val id: String,
    val icon: Drawable?,
    val title: String,
    val subtitle: String? = null,
    val isSelected: Boolean = false,
    val action: SidebarActionItem,
)

class SidebarNavigationAdapter(
    private val onItemClick: (SidebarNavigationItem) -> Unit,
    private val onItemLongClick: ((SidebarNavigationItem) -> Boolean)? = null,
) : ListAdapter<SidebarNavigationItem, SidebarNavigationAdapter.ViewHolder>(DiffCallback()) {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val view =
        LayoutInflater.from(parent.context).inflate(R.layout.item_sidebar_navigation, parent, false)
    return ViewHolder(view)
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    holder.bind(getItem(position))
  }

  inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val button: MaterialButton = itemView.findViewById(R.id.navigation_button)

    fun bind(item: SidebarNavigationItem) {
      button.apply {
        contentDescription = item.title
        isSelected = item.isSelected
        isChecked = item.isSelected

        val density = context.resources.displayMetrics.density
        iconSize = (28 * density).toInt()

        val baseColorInt = if (item.isSelected) {
          MaterialColors.getColor(button, com.google.android.material.R.attr.colorPrimaryContainer, 0xFF2A080E.toInt())
        } else {
          MaterialColors.getColor(button, com.google.android.material.R.attr.colorSurfaceContainerLow, 0xFF000000.toInt())
        }
        backgroundTintList = ColorStateList.valueOf(baseColorInt)
        strokeWidth = 0

        if (item.id == com.tom.rv2ide.actions.sidebar.AIAgentSidebarAction.ID) {
          // AI Agent Sukuna Icon: Pristine multi-color full-spectrum rendering without color tinting
          iconTint = null
          item.icon?.let { drawable ->
            val d = drawable.constantState?.newDrawable(context.resources)?.mutate() ?: drawable.mutate()
            d.clearColorFilter()
            androidx.core.graphics.drawable.DrawableCompat.setTintList(d, null)
            icon = d
          }
        } else {
          val iconColorInt = if (item.isSelected) {
            MaterialColors.getColor(button, android.R.attr.colorPrimary, 0xFFFF2A55.toInt())
          } else {
            MaterialColors.getColor(button, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFA8989B.toInt())
          }
          iconTint = ColorStateList.valueOf(iconColorInt)
          item.icon?.let { drawable ->
            icon = drawable.constantState?.newDrawable(context.resources)?.mutate() ?: drawable.mutate()
          }
        }

        setOnClickListener { onItemClick(item) }

        onItemLongClick?.let { longClickHandler ->
          setOnLongClickListener { longClickHandler(item) }
        }
      }
    }
  }

  private fun resolveColorAttr(context: Context, @AttrRes attrRes: Int): Int {
    val typedValue = TypedValue()
    val theme = context.theme
    if (theme.resolveAttribute(attrRes, typedValue, true)) {
      return typedValue.data
    }
    return ContextCompat.getColor(context, android.R.color.black)
  }

  private fun resolveColorStateListAttr(context: Context, @AttrRes attrRes: Int): ColorStateList? {
    val typedValue = TypedValue()
    val theme = context.theme
    if (theme.resolveAttribute(attrRes, typedValue, true)) {
      return ContextCompat.getColorStateList(context, typedValue.resourceId)
    }
    return ContextCompat.getColorStateList(context, android.R.color.transparent)
  }

  private class DiffCallback : DiffUtil.ItemCallback<SidebarNavigationItem>() {
    override fun areItemsTheSame(
        oldItem: SidebarNavigationItem,
        newItem: SidebarNavigationItem,
    ): Boolean = oldItem.id == newItem.id

    override fun areContentsTheSame(
        oldItem: SidebarNavigationItem,
        newItem: SidebarNavigationItem,
    ): Boolean = oldItem == newItem
  }
}
