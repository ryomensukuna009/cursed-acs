package com.tom.rv2ide.ui

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Custom [FrameLayout] that wraps the entire side panel drawer.
 * Does not use MaterialBackOrchestrator, preventing aggressive back callbacks
 * from closing the drawer while the software keyboard is active in ANY side panel tab.
 *
 * Ensures that pressing Back first dismisses the software keyboard before closing the drawer.
 */
class SidePanelNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val maxWidthPx: Int
        get() {
            val dm = context.resources.displayMetrics
            val swDp = context.resources.configuration.screenWidthDp
            val targetDp = when {
                swDp <= 360 -> 336
                swDp <= 420 -> (swDp - 24).coerceAtLeast(336)
                swDp <= 600 -> 360
                else -> 400
            }
            return (targetDp * dm.density + 0.5f).toInt()
        }
    private var lastDismissTimestamp = 0L

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val newWidthSpec = when (widthMode) {
            MeasureSpec.EXACTLY -> widthMeasureSpec
            MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(widthSize.coerceAtMost(maxWidthPx), MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.AT_MOST)
        }
        super.onMeasure(newWidthSpec, heightMeasureSpec)
    }

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (isKeyboardActive()) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    return true
                }
                if (event.action == KeyEvent.ACTION_UP) {
                    Log.e("ACS_KEYBOARD_DEBUG", "SidePanelNavView.dispatchKeyEventPreIme: Dismissing keyboard first!")
                    dismissKeyboard()
                    return true
                }
            }
        }
        return super.dispatchKeyEventPreIme(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (isKeyboardActive()) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    return true
                }
                if (event.action == KeyEvent.ACTION_UP) {
                    Log.e("ACS_KEYBOARD_DEBUG", "SidePanelNavView.dispatchKeyEvent: Dismissing keyboard first!")
                    dismissKeyboard()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun isKeyboardActive(): Boolean {
        val act = context as? Activity
        val decor = act?.window?.decorView ?: rootView
        val insets = ViewCompat.getRootWindowInsets(this) ?: ViewCompat.getRootWindowInsets(decor)
        if (insets != null) {
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                return true
            }
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val sysBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            if (imeBottom > sysBottom + 80) {
                return true
            }
        }

        val r = android.graphics.Rect()
        decor.getWindowVisibleDisplayFrame(r)
        val screenHeight = decor.rootView.height.coerceAtLeast(decor.height)
        if (screenHeight > 0) {
            val keypadHeight = screenHeight - r.bottom
            if (keypadHeight > (screenHeight * 0.12)) {
                return true
            }
        }

        return false
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

    fun dismissKeyboard(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDismissTimestamp < 200) {
            return true
        }
        lastDismissTimestamp = now

        val act = context as? Activity
        val decor = act?.window?.decorView ?: rootView
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val focused = findFocus() ?: decor.findFocus() ?: act?.currentFocus

        if (imm != null) {
            val token = focused?.windowToken ?: decor.windowToken ?: windowToken
            if (token != null) {
                imm.hideSoftInputFromWindow(token, 0)
            }
            if (decor.windowToken != null && decor.windowToken != token) {
                imm.hideSoftInputFromWindow(decor.windowToken, 0)
            }
        }

        if (act != null) {
            try {
                WindowCompat.getInsetsController(act.window, decor).hide(WindowInsetsCompat.Type.ime())
            } catch (_: Throwable) {}
        }

        focused?.clearFocus()
        decor.clearFocus()
        clearFocus()
        return true
    }
}
