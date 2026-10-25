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

package com.tom.rv2ide.ui

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout

/**
 * A [DrawerLayout] that scales its content when navigation drawers are opened or closed,
 * and intercepts system back button events when the software keyboard is open inside the
 * side panel so the keyboard is dismissed first before closing the drawer.
 *
 * @author Akash Yadav
 */
class ContentTranslatingDrawerLayout : InterceptableDrawerLayout {

  constructor(context: Context) : super(context)

  constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

  constructor(
      context: Context,
      attrs: AttributeSet?,
      defStyleAttr: Int,
  ) : super(context, attrs, defStyleAttr)

  /**
   * The ID of the child view which will be translated when the navigation views are
   * expanded/collapsed.
   *
   * Set this value to `-1` to disable transition.
   */
  var childId: Int = -1

  /** The [TranslationBehavior] for the start navigation view. */
  var translationBehaviorStart: TranslationBehavior = TranslationBehavior.DEFAULT

  /** The [TranslationBehavior] for the end navigation view. */
  var translationBehaviorEnd: TranslationBehavior = TranslationBehavior.DEFAULT

  private val mListener =
      object : SimpleDrawerListener() {
        override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
          if (childId == -1) {
            return
          }

          val gravity = (drawerView.layoutParams as LayoutParams).gravity
          val view = findViewById<View>(childId) ?: return
          val (direction, maxOffset) =
              if (gravity == GravityCompat.START) {
                1 to translationBehaviorStart.maxOffset
              } else {
                -1 to translationBehaviorEnd.maxOffset
              }

          val offset = (drawerView.width * slideOffset) * maxOffset
          view.translationX = direction * offset
        }
      }

  init {
    addDrawerListener(mListener)
    adjustMinDrawerMargin()
  }

  private fun adjustMinDrawerMargin() {
    try {
      val field = DrawerLayout::class.java.getDeclaredField("mMinDrawerMargin")
      field.isAccessible = true
      val density = resources.displayMetrics.density
      val swDp = resources.configuration.screenWidthDp
      val marginDp = if (swDp <= 400) 24 else 56
      field.setInt(this, (marginDp * density + 0.5f).toInt())
    } catch (_: Throwable) {
    }
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    adjustMinDrawerMargin()
    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
  }

  /**
   * Checks if any navigation drawer is currently visible (either START or END).
   */
  fun hasAnyVisibleDrawer(): Boolean {
    return isDrawerVisible(GravityCompat.START) || isDrawerVisible(GravityCompat.END)
  }

  /**
   * Determines whether the software keyboard is active or a text field is focused
   * inside the side panel drawer or across the window.
   */
  fun isSidePanelKeyboardActive(): Boolean {
    val act = context as? Activity
    val decor = act?.window?.decorView ?: rootView
    val currentFocus = act?.currentFocus ?: decor.findFocus() ?: findFocus()
    val isCurrentFocusEditor = currentFocus != null && (currentFocus is EditText || currentFocus.onCheckIsTextEditor())

    val isAnyEditorFocused = hasAnyFocusedTextEditor(this) || hasAnyFocusedTextEditor(decor)

    val insets = ViewCompat.getRootWindowInsets(this) ?: ViewCompat.getRootWindowInsets(decor)
    val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
    val sysBottom = insets?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
    val isImeVisibleByInsets = (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) || (imeBottom > sysBottom + 80)

    val r = Rect()
    decor.getWindowVisibleDisplayFrame(r)
    val screenHeight = decor.height.coerceAtLeast(decor.rootView.height)
    val keypadHeight = screenHeight - r.bottom
    val isKeypadVisible = screenHeight > 0 && keypadHeight > (screenHeight * 0.12)

    val isActive = isKeypadVisible || isImeVisibleByInsets
    Log.e(
        "ACS_KEYBOARD_DEBUG",
        "isSidePanelKeyboardActive: keypadHeight=$keypadHeight, isKeypadVisible=$isKeypadVisible, isImeVisibleByInsets=$isImeVisibleByInsets -> $isActive"
    )
    return isActive
  }

  private fun hasAnyFocusedTextEditor(root: View?): Boolean {
    if (root == null) return false
    if ((root.isFocused || root.hasFocus()) && (root is EditText || root.onCheckIsTextEditor())) {
      return true
    }
    if (root is ViewGroup) {
      for (i in 0 until root.childCount) {
        if (hasAnyFocusedTextEditor(root.getChildAt(i))) {
          return true
        }
      }
    }
    return false
  }

  /**
   * Hides the software keyboard and clears focus.
   * Returns true if the keyboard was dismissed or was open, keeping the drawer open.
   */
  fun dismissKeyboardIfOpen(): Boolean {
    val wasActive = isSidePanelKeyboardActive()
    val act = context as? Activity
    val decor = act?.window?.decorView ?: rootView
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val focused = decor.findFocus() ?: findFocus() ?: act?.currentFocus

    var dismissed = wasActive
    if (imm != null) {
      val token = focused?.windowToken ?: decor.windowToken ?: windowToken
      if (token != null) {
        if (imm.hideSoftInputFromWindow(token, 0)) {
          dismissed = true
        }
      }
      if (decor.windowToken != null && decor.windowToken != token) {
        if (imm.hideSoftInputFromWindow(decor.windowToken, 0)) {
          dismissed = true
        }
      }
    }

    if (act != null) {
      try {
        WindowCompat.getInsetsController(act.window, decor).hide(WindowInsetsCompat.Type.ime())
      } catch (_: Throwable) {}
    }

    if (isSidePanelKeyboardActive()) {
      dismissed = true
    }

    focused?.clearFocus()
    decor.clearFocus()
    clearFocus()

    Log.e("ACS_KEYBOARD_DEBUG", "dismissKeyboardIfOpen: dismissed=$dismissed")
    return dismissed
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.keyCode == KeyEvent.KEYCODE_BACK && hasAnyVisibleDrawer()) {
      if (event.action == KeyEvent.ACTION_DOWN) {
        if (isSidePanelKeyboardActive()) {
          return true
        }
      } else if (event.action == KeyEvent.ACTION_UP) {
        if (dismissKeyboardIfOpen()) {
          Log.e("ACS_KEYBOARD_DEBUG", "dispatchKeyEvent: Dismissed keyboard! Keeping drawer open.")
          return true
        }
      }
    }
    return super.dispatchKeyEvent(event)
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    if (keyCode == KeyEvent.KEYCODE_BACK && hasAnyVisibleDrawer()) {
      if (isSidePanelKeyboardActive()) {
        Log.e("ACS_KEYBOARD_DEBUG", "onKeyDown: Consuming BACK for keyboard!")
        event.startTracking()
        return true
      }
    }
    return super.onKeyDown(keyCode, event)
  }

  override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
    if (keyCode == KeyEvent.KEYCODE_BACK && hasAnyVisibleDrawer()) {
      if (dismissKeyboardIfOpen()) {
        Log.e("ACS_KEYBOARD_DEBUG", "onKeyUp: Dismissed keyboard! Keeping drawer open.")
        return true
      }
    }
    return super.onKeyUp(keyCode, event)
  }

  private var lastKeyboardDismissTimestamp = 0L

  fun shouldInterceptDrawerCloseForKeyboard(): Boolean {
    val now = System.currentTimeMillis()
    if (now - lastKeyboardDismissTimestamp < 350) {
      Log.e("ACS_KEYBOARD_DEBUG", "closeDrawer blocked: within cooldown of keyboard dismissal!")
      return true
    }
    if (isSidePanelKeyboardActive()) {
      Log.e("ACS_KEYBOARD_DEBUG", "closeDrawer intercepted: keyboard is active! Dismissing keyboard and keeping drawer open.")
      dismissKeyboardIfOpen()
      lastKeyboardDismissTimestamp = System.currentTimeMillis()
      return true
    }
    return false
  }

  override fun closeDrawers() {
    if (shouldInterceptDrawerCloseForKeyboard()) {
      return
    }
    super.closeDrawers()
  }

  override fun closeDrawer(drawerView: View, animate: Boolean) {
    if (shouldInterceptDrawerCloseForKeyboard()) {
      return
    }
    super.closeDrawer(drawerView, animate)
  }

  override fun closeDrawer(gravity: Int, animate: Boolean) {
    if (shouldInterceptDrawerCloseForKeyboard()) {
      return
    }
    super.closeDrawer(gravity, animate)
  }

  override fun closeDrawer(drawerView: View) {
    if (shouldInterceptDrawerCloseForKeyboard()) {
      return
    }
    super.closeDrawer(drawerView)
  }

  override fun closeDrawer(gravity: Int) {
    if (shouldInterceptDrawerCloseForKeyboard()) {
      return
    }
    super.closeDrawer(gravity)
  }

  /** Translation behavior for content view of [ContentTranslatingDrawerLayout]. */
  enum class TranslationBehavior(val maxOffset: Float) {

    /**
     * The default translation behavior. This makes the child view translate partially according to
     * the slide offset of the
     * [NavigationView][com.google.android.material.navigation.NavigationView]
     */
    DEFAULT(0.2f),

    /**
     * Makes the child child view translate according to the slide offset of the
     * [NavigationView][com.google.android.material.navigation.NavigationView]. The translation
     * offset is always equal to the slide offset in this behavior.
     */
    FULL(0.95f),
  }
}
