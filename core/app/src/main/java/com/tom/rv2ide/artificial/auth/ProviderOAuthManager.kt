package com.tom.rv2ide.artificial.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.artificial.secrets.SecureKeyStore
import com.tom.rv2ide.artificial.providers.ProviderRegistryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Direct account authentication manager ported from Kelivo.
 * Handles device-code and browser OAuth token acquisition for:
 * 1. OpenAI (ChatGPT / Codex)
 * 2. Anthropic (Claude)
 * 3. Google (Gemini)
 */
object ProviderOAuthManager {

    private const val OPENAI_CLIENT_ID = "pdlLIX2Y72MIl2rhLhTE9VV9bN905kBh"
    private const val OPENAI_USER_CODE_URL = "https://auth.openai.com/api/accounts/deviceauth/usercode"
    private const val OPENAI_DEVICE_VERIFY_URL = "https://auth.openai.com/codex/device"
    private const val OPENAI_TOKEN_URL = "https://auth.openai.com/api/accounts/deviceauth/token"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun showAccountLoginDialog(
        context: Context,
        scope: CoroutineScope,
        providerId: String,
        onSuccess: (String) -> Unit
    ) {
        when (providerId.lowercase()) {
            "openai", "chatgpt" -> showOpenAiDeviceLogin(context, scope, onSuccess)
            "claude", "anthropic" -> showClaudeLogin(context, scope, onSuccess)
            "gemini", "google" -> showGeminiLogin(context, scope, onSuccess)
            else -> {
                Toast.makeText(context, "Account login is available for OpenAI, Claude, and Gemini", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showOpenAiDeviceLogin(
        context: Context,
        scope: CoroutineScope,
        onSuccess: (String) -> Unit
    ) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }

        val statusTv = TextView(context).apply {
            text = "Requesting device login code from OpenAI..."
            textSize = 13f
            setTextColor(resolveAttr(context, com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, 0, 0, pad / 2)
            root.addView(this)
        }

        val codeCard = MaterialCardView(context).apply {
            radius = 12 * context.resources.displayMetrics.density
            setCardBackgroundColor(resolveAttr(context, com.google.android.material.R.attr.colorSurfaceContainerHigh))
            strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
            strokeColor = resolveAttr(context, com.google.android.material.R.attr.colorOutlineVariant)
            visibility = View.GONE
            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(pad, pad, pad, pad)
            }
            addView(inner)
            root.addView(this)
        }

        val codeTv = TextView(context).apply {
            text = ""
            textSize = 24f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(resolveAttr(context, android.R.attr.colorPrimary))
            gravity = Gravity.CENTER
            (codeCard.getChildAt(0) as LinearLayout).addView(this)
        }

        val copyBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "📋 Copy Code"
            textSize = 12f
            setOnClickListener {
                val clip = android.content.ClipData.newPlainText("OpenAI Device Code", codeTv.text)
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                Toast.makeText(context, "Copied code to clipboard!", Toast.LENGTH_SHORT).show()
            }
            (codeCard.getChildAt(0) as LinearLayout).addView(this)
        }

        val progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            setPadding(0, pad / 2, 0, pad / 2)
            root.addView(this)
        }

        val openBrowserBtn = MaterialButton(context).apply {
            text = "🌐 Open OpenAI Approval Page"
            visibility = View.GONE
            setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(OPENAI_DEVICE_VERIFY_URL))
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, "Unable to open browser", Toast.LENGTH_SHORT).show()
                }
            }
            root.addView(this)
        }

        var pollJob: Job? = null

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Sign in to OpenAI (ChatGPT)")
            .setView(root)
            .setNegativeButton("Cancel") { d, _ ->
                pollJob?.cancel()
                d.dismiss()
            }
            .setOnDismissListener {
                pollJob?.cancel()
            }
            .show()

        pollJob = scope.launch(Dispatchers.IO) {
            try {
                // 1. Request device user code
                val jsonPayload = JSONObject().apply {
                    put("client_id", OPENAI_CLIENT_ID)
                }.toString()

                val initReq = Request.Builder()
                    .url(OPENAI_USER_CODE_URL)
                    .post(jsonPayload.toRequestBody("application/json".toMediaType()))
                    .build()

                val initResp = httpClient.newCall(initReq).execute()
                val initBody = initResp.body?.string().orEmpty()

                if (!initResp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        statusTv.text = "❌ Failed to initiate OpenAI login: HTTP ${initResp.code}"
                    }
                    return@launch
                }

                val initJson = JSONObject(initBody)
                val deviceAuthId = initJson.optString("device_auth_id")
                val userCode = initJson.optString("user_code")
                val intervalSec = initJson.optInt("interval", 5).coerceIn(3, 30)

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.VISIBLE
                    codeTv.text = userCode
                    codeCard.visibility = View.VISIBLE
                    openBrowserBtn.visibility = View.VISIBLE
                    statusTv.text = "1. Copy the code below\n2. Open the OpenAI page and enter the code\n3. Return here once approved"
                }

                // 2. Poll for authorization
                val pollPayload = JSONObject().apply {
                    put("device_auth_id", deviceAuthId)
                    put("user_code", userCode)
                }.toString()

                var acquiredToken: String? = null
                for (poll in 0 until 120) {
                    delay(intervalSec * 1000L)
                    val tokenReq = Request.Builder()
                        .url(OPENAI_TOKEN_URL)
                        .post(pollPayload.toRequestBody("application/json".toMediaType()))
                        .build()

                    val tokenResp = httpClient.newCall(tokenReq).execute()
                    val tokenBody = tokenResp.body?.string().orEmpty()

                    if (tokenResp.code in listOf(403, 404)) {
                        // Still waiting for approval
                        continue
                    }

                    if (tokenResp.isSuccessful) {
                        val tokenJson = JSONObject(tokenBody)
                        val accessToken = tokenJson.optString("access_token")
                        if (accessToken.isNotBlank()) {
                            acquiredToken = accessToken
                            break
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (acquiredToken != null) {
                        SecureKeyStore.putProviderApiKey(context, "openai", acquiredToken)
                        Toast.makeText(context, "✅ Signed in to OpenAI successfully!", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                        onSuccess(acquiredToken)
                    } else {
                        statusTv.text = "⚠️ Login timed out or cancelled. Tap cancel and try again."
                    }
                }

            } catch (_: CancellationException) {
                // Cancelled
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ Login error: ${e.message}"
                }
            }
        }
    }

    private fun showClaudeLogin(
        context: Context,
        scope: CoroutineScope,
        onSuccess: (String) -> Unit
    ) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }

        val infoTv = TextView(context).apply {
            text = "To use your Claude account with CURSED ACS, obtain your Claude API key / Session token from Anthropic Console:"
            textSize = 12f
            setTextColor(resolveAttr(context, com.google.android.material.R.attr.colorOnSurface))
            root.addView(this)
        }

        val openBtn = MaterialButton(context).apply {
            text = "🌐 Open Anthropic Console"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2; bottomMargin = pad / 2 }
            setOnClickListener {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")))
                } catch (_: Exception) {
                    Toast.makeText(context, "Unable to open browser", Toast.LENGTH_SHORT).show()
                }
            }
            root.addView(this)
        }

        val input = EditText(context).apply {
            hint = "Paste sk-ant-... key or auth token"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            root.addView(this)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Claude (Anthropic) Account Sign In")
            .setView(root)
            .setPositiveButton("Save & Connect") { d, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                if (key.isNotEmpty()) {
                    SecureKeyStore.putProviderApiKey(context, "claude", key)
                    SecureKeyStore.putProviderApiKey(context, "anthropic", key)
                    Toast.makeText(context, "✅ Claude connected successfully!", Toast.LENGTH_SHORT).show()
                    onSuccess(key)
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGeminiLogin(
        context: Context,
        scope: CoroutineScope,
        onSuccess: (String) -> Unit
    ) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }

        val infoTv = TextView(context).apply {
            text = "Connect your Google account to access Gemini 2.0 Flash, 1.5 Pro, and Thinking models via Google AI Studio:"
            textSize = 12f
            setTextColor(resolveAttr(context, com.google.android.material.R.attr.colorOnSurface))
            root.addView(this)
        }

        val openBtn = MaterialButton(context).apply {
            text = "🌐 Get Free Key from Google AI Studio"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2; bottomMargin = pad / 2 }
            setOnClickListener {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey")))
                } catch (_: Exception) {
                    Toast.makeText(context, "Unable to open browser", Toast.LENGTH_SHORT).show()
                }
            }
            root.addView(this)
        }

        val input = EditText(context).apply {
            hint = "Paste AIzaSy... key or OAuth token"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            root.addView(this)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Google Gemini Account Sign In")
            .setView(root)
            .setPositiveButton("Save & Connect") { d, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                if (key.isNotEmpty()) {
                    SecureKeyStore.putProviderApiKey(context, "gemini", key)
                    Toast.makeText(context, "✅ Google Gemini connected successfully!", Toast.LENGTH_SHORT).show()
                    onSuccess(key)
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resolveAttr(context: Context, attrRes: Int): Int {
        val tv = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attrRes, tv, true)) {
            tv.data
        } else {
            android.graphics.Color.WHITE
        }
    }

}
