package com.tom.rv2ide.utils

import android.content.Context
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.createGraph
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.FragmentNavigatorDestinationBuilder
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.get
import androidx.navigation.navOptions
import androidx.recyclerview.widget.LinearLayoutManager
import com.tom.rv2ide.actions.ActionData
import com.tom.rv2ide.actions.ActionItem
import com.tom.rv2ide.actions.ActionsRegistry
import com.tom.rv2ide.actions.SidebarActionItem
import com.tom.rv2ide.actions.internal.DefaultActionsRegistry
import com.tom.rv2ide.actions.sidebar.AIAgentSidebarAction
import com.tom.rv2ide.actions.sidebar.AssetStudioSidebarAction
import com.tom.rv2ide.actions.sidebar.BuildVariantsSidebarAction
import com.tom.rv2ide.actions.sidebar.CloseProjectSidebarAction
import com.tom.rv2ide.actions.sidebar.FileTreeSidebarAction
import com.tom.rv2ide.actions.sidebar.PreferencesSidebarAction
import com.tom.rv2ide.actions.sidebar.SubModuleSidebarAction
import com.tom.rv2ide.actions.sidebar.GitClientAction
import com.tom.rv2ide.actions.sidebar.TerminalSidebarAction
import com.tom.rv2ide.actions.sidebar.ProblemsSidebarAction
import com.tom.rv2ide.actions.sidebar.MoreToolsSidebarAction
import com.tom.rv2ide.fragments.sidebar.EditorSidebarFragment
import androidx.fragment.app.Fragment
import java.lang.ref.WeakReference

internal object EditorSidebarActions {

  private val fragmentCache = mutableMapOf<String, Fragment>()
  private var selectActionCallback: ((String) -> Unit)? = null

  @JvmStatic
  fun switchToSecondaryAction(actionId: String) {
    selectActionCallback?.invoke(actionId)
  }

  @JvmStatic
  fun registerActions(context: Context) {
    val registry = ActionsRegistry.getInstance()
    var order = -1

    @Suppress("KotlinConstantConditions")
    registry.registerAction(FileTreeSidebarAction(context, ++order))
    registry.registerAction(AIAgentSidebarAction(context, ++order))
    registry.registerAction(ProblemsSidebarAction(context, ++order))
    registry.registerAction(MoreToolsSidebarAction(context, ++order))

    // Secondary tools available in overflow sheet
    registry.registerAction(GitClientAction(context, ++order))
    registry.registerAction(BuildVariantsSidebarAction(context, ++order))
    registry.registerAction(AssetStudioSidebarAction(context, ++order))
    registry.registerAction(SubModuleSidebarAction(context, ++order))
    registry.registerAction(PreferencesSidebarAction(context, ++order))
    registry.registerAction(CloseProjectSidebarAction(context, ++order))
  }

  @JvmStatic
  fun removeFragmentFromCache(fragmentId: String) {
    fragmentCache.remove(fragmentId)
  }

  @JvmStatic
  fun setup(sidebarFragment: EditorSidebarFragment) {
    fragmentCache.clear()
    val binding = sidebarFragment.getBinding() ?: return
    val context = sidebarFragment.requireContext()
    val navigationRecycler =
        binding.navigation.findViewById<androidx.recyclerview.widget.RecyclerView>(
            com.tom.rv2ide.R.id.navigation_recycler
        )

    val registry = ActionsRegistry.getInstance()
    val actions = registry.getActions(ActionItem.Location.EDITOR_SIDEBAR)
    if (actions.isEmpty()) {
      return
    }

    val data = ActionData()
    data.put(Context::class.java, context)

    val titleRef = WeakReference(binding.title)
    val subtitleRef = WeakReference(binding.subtitle)

    fun updateTitleVisibility(title: String?, actionId: String? = null) {
      titleRef.get()?.let { titleView ->
        if (actionId == com.tom.rv2ide.actions.sidebar.AIAgentSidebarAction.ID || title == "AI Agent") {
          titleView.visibility = android.view.View.GONE
        } else if (!title.isNullOrEmpty()) {
          titleView.text = title
          titleView.visibility = android.view.View.VISIBLE
        } else {
          titleView.visibility = android.view.View.GONE
        }
      }
    }

    fun updateSubtitleVisibility(subtitle: String?, actionId: String? = null) {
      subtitleRef.get()?.let { subtitleView ->
        if (actionId == com.tom.rv2ide.actions.sidebar.AIAgentSidebarAction.ID) {
          subtitleView.visibility = android.view.View.GONE
        } else if (!subtitle.isNullOrEmpty()) {
          subtitleView.text = subtitle
          subtitleView.visibility = android.view.View.VISIBLE
        } else {
          subtitleView.visibility = android.view.View.GONE
        }
      }
    }

    val sortedActions =
        actions.entries.sortedBy { (_, action) ->
          (action as? SidebarActionItem)?.order ?: Int.MAX_VALUE
        }

    val primaryIds = setOf(
        FileTreeSidebarAction.ID,
        AIAgentSidebarAction.ID,
        ProblemsSidebarAction.ID,
        MoreToolsSidebarAction.ID,
    )

    val navigationItems =
        sortedActions
            .filter { primaryIds.contains(it.key) }
            .map { (actionId, action) ->
              action as SidebarActionItem

              action.prepare(data)

              SidebarNavigationItem(
                  id = actionId,
                  icon = ContextCompat.getDrawable(context, action.iconRes),
                  title = action.label,
                  subtitle = action.subtitle,
                  isSelected = actionId == FileTreeSidebarAction.ID,
                  action = action,
              )
            }

    var currentFragmentId: String? = null
    lateinit var adapter: SidebarNavigationAdapter

    selectActionCallback = { targetActionId ->
      val action = registry.getActions(ActionItem.Location.EDITOR_SIDEBAR)[targetActionId] as? SidebarActionItem
      if (action != null) {
        if (action.fragmentClass == null) {
          (registry as DefaultActionsRegistry).executeAction(action, data)
        } else {
          val fragmentManager = sidebarFragment.childFragmentManager
          val existingFragment = fragmentManager.findFragmentByTag(action.id)
          val fragment = if (existingFragment != null && existingFragment.isAdded) {
            existingFragment
          } else {
            action.fragmentClass!!.java.newInstance()
          }

          val transaction = fragmentManager.beginTransaction()
          fragmentManager.fragments.forEach { existing ->
            if (existing.isAdded) {
              transaction.hide(existing)
            }
          }

          if (fragment.isAdded) {
            transaction.show(fragment)
          } else {
            transaction.add(binding.fragmentContainer.id, fragment, action.id)
          }

          transaction.commitNow()
          currentFragmentId = action.id
          updateTitleVisibility(action.label, action.id)
          updateSubtitleVisibility(action.subtitle, action.id)
          val updatedItems =
              navigationItems.map { navItem -> 
                navItem.copy(isSelected = navItem.id == action.id) 
              }
          adapter.submitList(updatedItems)
        }
      }
    }

    adapter = SidebarNavigationAdapter(
            onItemClick = { item ->
              val action = item.action

              if (action.fragmentClass == null) {
                (registry as DefaultActionsRegistry).executeAction(action, data)
                return@SidebarNavigationAdapter
              }

              if (currentFragmentId == action.id) {
                return@SidebarNavigationAdapter
              }

              val fragmentManager = sidebarFragment.childFragmentManager
              val existingFragment = fragmentManager.findFragmentByTag(action.id)
              val fragment = if (existingFragment != null && existingFragment.isAdded) {
                existingFragment
              } else {
                action.fragmentClass!!.java.newInstance()
              }

              val transaction = fragmentManager.beginTransaction()

              fragmentManager.fragments.forEach { existing ->
                if (existing.isAdded) {
                  transaction.hide(existing)
                }
              }

              if (fragment.isAdded) {
                transaction.show(fragment)
              } else {
                transaction.add(binding.fragmentContainer.id, fragment, action.id)
              }

              transaction.commitNow()

              currentFragmentId = action.id

              updateTitleVisibility(item.title, item.id)
              updateSubtitleVisibility(item.subtitle, item.id)

              val updatedItems =
                  navigationItems.map { navItem -> 
                    navItem.copy(isSelected = navItem.id == item.id) 
                  }
              adapter.submitList(updatedItems)
            },
            onItemLongClick = { item ->
              if (item.action is TerminalSidebarAction) {
                true
              } else {
                false
              }
            },
        )

    navigationRecycler.layoutManager =
        LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
    navigationRecycler.adapter = adapter
    adapter.submitList(navigationItems)

    val fragmentManager = sidebarFragment.childFragmentManager
    val firstItem = navigationItems.first()
    val existingFirst = fragmentManager.findFragmentByTag(firstItem.id)
    val firstFragment = if (existingFirst != null && existingFirst.isAdded) {
      existingFirst
    } else {
      firstItem.action.fragmentClass?.java?.newInstance() 
          ?: throw IllegalStateException("First action must have a fragment")
    }

    val initTransaction = fragmentManager.beginTransaction()
    fragmentManager.fragments.forEach { existing ->
      if (existing.isAdded) {
        initTransaction.hide(existing)
      }
    }
    if (firstFragment.isAdded) {
      initTransaction.show(firstFragment)
    } else {
      initTransaction.add(binding.fragmentContainer.id, firstFragment, firstItem.id)
    }
    initTransaction.commitNow()

    currentFragmentId = firstItem.id

    updateTitleVisibility(firstItem.title, firstItem.id)
    updateSubtitleVisibility(firstItem.subtitle, firstItem.id)
  }

  @JvmStatic
  internal fun NavDestination.matchDestination(route: String): Boolean =
      hierarchy.any { it.route == route }

  @JvmStatic
  internal fun NavDestination.matchDestination(@IdRes destId: Int): Boolean =
      hierarchy.any { it.id == destId }
}