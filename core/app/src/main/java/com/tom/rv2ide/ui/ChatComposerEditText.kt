package com.tom.rv2ide.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText

/**
 * Keyboard-aware multi-line chat composer EditText.
 * Intercepts Back key events while the software keyboard (IME) is visible so that the first
 * Back press hides only the keyboard without closing the AI chat sidebar drawer or losing draft text.
 */
class ChatComposerEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : TextInputEditText(context, attrs, defStyleAttr) {

    var onImeDismissRequested: (() -> Unit)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event != null) {
            if (isSoftwareKeyboardVisible()) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    return true
                }
                if (event.action == KeyEvent.ACTION_UP) {
                    hideSoftwareKeyboardOnly()
                    onImeDismissRequested?.invoke()
                    return true
                }
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }

    fun isSoftwareKeyboardVisible(): Boolean {
        val insets = ViewCompat.getRootWindowInsets(this)
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
        getWindowVisibleDisplayFrame(r)
        val screenHeight = rootView?.height ?: 0
        if (screenHeight > 0) {
            val keypadHeight = screenHeight - r.bottom
            if (keypadHeight > (screenHeight * 0.12)) {
                return true
            }
        }
        return false
    }

    fun hideSoftwareKeyboardOnly() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        ViewCompat.getWindowInsetsController(this)?.hide(WindowInsetsCompat.Type.ime())
        clearFocus()
    }
}
