package com.tom.rv2ide.artificial.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Direct account authentication manager ported from Kelivo.
 * Handles device-code and token OAuth acquisition for:
 * 1. ChatGPT (OpenAI Codex Device Flow)
 * 2. Claude (Anthropic Account Flow)
 * 3. Grok (xAI Device Flow)
 * 4. Kimi Code (Moonshot Device Flow)
 * 5. Google Gemini (Google AI Studio)
 */
object ProviderOAuthManager {

    // OpenAI Codex device auth
    private const val OPENAI_CLIENT_ID = "pdlLIX2Y72MIl2rhLhTE9VV9bN905kBh"
    private const val OPENAI_USER_CODE_URL = "https://auth.openai.com/api/accounts/deviceauth/usercode"
    private const val OPENAI_DEVICE_VERIFY_URL = "https://auth.openai.com/codex/device"
    private const val OPENAI_TOKEN_URL = "https://auth.openai.com/api/accounts/deviceauth/token"

    // Grok (xAI) device auth
    private const val GROK_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    private const val GROK_DEVICE_CODE_URL = "https://auth.x.ai/oauth2/device/code"
    private const val GROK_TOKEN_URL = "https://auth.x.ai/oauth2/token"

    // Kimi Code device auth
    private const val KIMI_CLIENT_ID = "17e5f671-d194-4dfb-9706-5516cb48c098"
    private const val KIMI_DEVICE_CODE_URL = "https://auth.kimi.com/api/oauth/device_authorization"
    private const val KIMI_TOKEN_URL = "https://auth.kimi.com/api/oauth/token"

    // Claude
    private const val CLAUDE_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
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
            "grok", "xai" -> showGrokDeviceLogin(context, scope, onSuccess)
            "kimi", "kimi code", "moonshot" -> showKimiDeviceLogin(context, scope, onSuccess)
            "gemini", "google" -> showGeminiLogin(context, scope, onSuccess)
            else -> showOAuthAccountsHubDialog(context, scope) { onSuccess("") }
        }
    }

    /**
     * Dedicated Connected Accounts Hub Dialog matching Kelivo's OAuthLoginPanel.
     * Displays all 5 accounts with live connection badges and 1-click login.
     */
    fun showOAuthAccountsHubDialog(
        context: Context,
        scope: CoroutineScope,
        onAccountConnected: (String) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val pad = (14 * density).toInt()

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, (16 * density).toInt())
        }
        scrollView.addView(root)

        val headerDesc = TextView(context).apply {
            text = "1-Click login directly with your AI provider accounts. No complex manual keys needed."
            textSize = 12.5f
            setTextColor(resolveAttr(context, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, 0, 0, (12 * density).toInt())
            root.addView(this)
        }

        data class AccountItem(
            val id: String,
            val name: String,
            val subtitle: String,
            val iconEmoji: String,
            val action: () -> Unit
        )

        var hubDialog: androidx.appcompat.app.AlertDialog? = null

        val accounts = listOf(
            AccountItem("openai", "ChatGPT", "OpenAI Codex Device Flow", "🤖") {
                showOpenAiDeviceLogin(context, scope) { token ->
                    onAccountConnected("openai")
                    hubDialog?.dismiss()
                }
            },
            AccountItem("claude", "Claude", "Anthropic Console / Pro Flow", "🧠") {
                showClaudeLogin(context, scope) { token ->
                    onAccountConnected("claude")
                    hubDialog?.dismiss()
                }
            },
            AccountItem("grok", "Grok", "xAI Grok-2 / Grok-3 Device Flow", "⚡") {
                showGrokDeviceLogin(context, scope) { token ->
                    onAccountConnected("grok")
                    hubDialog?.dismiss()
                }
            },
            AccountItem("kimi", "Kimi Code", "Moonshot Kimi Coding API Flow", "🌙") {
                showKimiDeviceLogin(context, scope) { token ->
                    onAccountConnected("kimi")
                    hubDialog?.dismiss()
                }
            },
            AccountItem("gemini", "Google Gemini", "Google AI Studio Quick Connect", "🌐") {
                showGeminiLogin(context, scope) { token ->
                    onAccountConnected("gemini")
                    hubDialog?.dismiss()
                }
            }
        )

        accounts.forEach { acc ->
            val hasKey = SecureKeyStore.hasProviderApiKey(context, acc.id)

            val card = MaterialCardView(context).apply {
                radius = 12 * density
                strokeWidth = (1 * density).toInt()
                strokeColor = resolveAttr(context, com.google.android.material.R.attr.colorOutlineVariant)
                setCardBackgroundColor(resolveAttr(context, com.google.android.material.R.attr.colorSurfaceContainer))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
            }

            val cardContent = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            }

            val iconTv = TextView(context).apply {
                text = acc.iconEmoji
                textSize = 24f
                setPadding(0, 0, (10 * density).toInt(), 0)
                cardContent.addView(this)
            }

            val infoLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameTv = TextView(context).apply {
                text = acc.name
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(resolveAttr(context, com.google.android.material.R.attr.colorOnSurface))
                infoLayout.addView(this)
            }

            val subTv = TextView(context).apply {
                text = acc.subtitle
                textSize = 11.5f
                setTextColor(resolveAttr(context, com.google.android.material.R.attr.colorOnSurfaceVariant))
                infoLayout.addView(this)
            }

            cardContent.addView(infoLayout)

            val actionBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                if (hasKey) {
                    text = "✓ Connected"
                    setTextColor(android.graphics.Color.parseColor("#10B981"))
                } else {
                    text = "🔑 Sign In"
                }
                textSize = 12f
                insetTop = 0
                insetBottom = 0
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                setOnClickListener {
                    acc.action()
                }
                cardContent.addView(this)
            }

            card.addView(cardContent)
            root.addView(card)
        }

        hubDialog = MaterialAlertDialogBuilder(context)
            .setTitle("🔑 Connected AI Accounts (Kelivo Hub)")
            .setView(scrollView)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showOpenAiDeviceLogin(
        context: Context,
        scope: CoroutineScope,
        onSuccess: (String) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

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
            radius = 12 * density
            strokeWidth = (1 * density).toInt()
            strokeColor = resolveAttr(context, com.google.android.material.R.attr.colorOutlineVariant)
            setCardBackgroundColor(resolveAttr(context, com.google.android.material.R.attr.colorSurfaceContainer))
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = pad
                bottomMargin = pad
            }
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
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ Login error: ${e.message}"
                }
            }
        }
    }

    private fun showGrokDeviceLogin(
        context: Context,
        scope: CoroutineScope,
        onSuccess: (String) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }

        val statusTv = TextView(context).apply {
            text = "Requesting device login code from xAI (Grok)..."
            textSize = 13f
            setTextColor(resolveAttr(context, com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, 0, 0, pad / 2)
            root.addView(this)
        }

        val codeCard = MaterialCardView(context).apply {
            radius = 12 * density
            strokeWidth = (1 * density).toInt()
            strokeColor = resolveAttr(context, com.google.android.material.R.attr.colorOutlineVariant)
            setCardBackgroundColor(resolveAttr(context, com.google.android.material.R.attr.colorSurfaceContainer))
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
                val clip = android.content.ClipData.newPlainText("Grok Device Code", codeTv.text)
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                Toast.makeText(context, "Copied code to clipboard!", Toast.LENGTH_SHORT).show()
            }
            (codeCard.getChildAt(0) as LinearLayout).addView(this)
        }

        val progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = pad
                bottomMargin = pad
            }
            root.addView(this)
        }

        var verifyUrl = "https://auth.x.ai"
        val openBrowserBtn = MaterialButton(context).apply {
            text = "🌐 Open xAI Approval Page"
            visibility = View.GONE
            setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(verifyUrl))
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, "Unable to open browser", Toast.LENGTH_SHORT).show()
                }
            }
            root.addView(this)
        }

        var pollJob: Job? = null

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Sign in to xAI (Grok)")
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
                val formBody = FormBody.Builder()
                    .add("client_id", GROK_CLIENT_ID)
                    .add("scope", "openid profile email offline_access grok-cli:access api:access")
                    .build()

                val initReq = Request.Builder()
                    .url(GROK_DEVICE_CODE_URL)
                    .post(formBody)
                    .build()

                val initResp = httpClient.newCall(initReq).execute()
                val initBody = initResp.body?.string().orEmpty()

                if (!initResp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        statusTv.text = "❌ Failed to initiate Grok login: HTTP ${initResp.code}"
                    }
                    return@launch
                }

                val initJson = JSONObject(initBody)
                val deviceCode = initJson.optString("device_code")
                val userCode = initJson.optString("user_code")
                val url = initJson.optString("verification_uri_complete").takeIf { it.isNotBlank() }
                    ?: initJson.optString("verification_uri", "https://auth.x.ai")
                verifyUrl = url
                val intervalSec = initJson.optInt("interval", 5).coerceIn(3, 30)

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.VISIBLE
                    codeTv.text = userCode
                    codeCard.visibility = View.VISIBLE
                    openBrowserBtn.visibility = View.VISIBLE
                    statusTv.text = "1. Copy the code below\n2. Open the xAI verification page\n3. Approve access and return here"
                }

                var acquiredToken: String? = null
                for (poll in 0 until 120) {
                    delay(intervalSec * 1000L)
                    val pollForm = FormBody.Builder()
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                        .add("client_id", GROK_CLIENT_ID)
                        .add("device_code", deviceCode)
                        .build()

                    val tokenReq = Request.Builder()
                        .url(GROK_TOKEN_URL)
                        .post(pollForm)
                        .build()

                    val tokenResp = httpClient.newCall(tokenReq).execute()
                    val tokenBody = tokenResp.body?.string().orEmpty()

                    if (tokenResp.code in listOf(400, 403, 404)) {
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
                        SecureKeyStore.putProviderApiKey(context, "grok", acquiredToken)
                        Toast.makeText(context, "✅ Signed in to Grok successfully!", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                        onSuccess(acquiredToken)
                    } else {
                        statusTv.text = "⚠️ Login timed out or cancelled. Tap cancel and try again."
                    }
                }

            } catch (_: CancellationException) {
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ Grok login error: ${e.message}"
                }
            }
        }
    }

    private fun showKimiDeviceLogin(
        context: Context,
        scope: CoroutineScope,
        onSuccess: (String) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }

        val statusTv = TextView(context).apply {
            text = "Requesting device login code from Moonshot Kimi..."
            textSize = 13f
            setTextColor(resolveAttr(context, com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, 0, 0, pad / 2)
            root.addView(this)
        }

        val codeCard = MaterialCardView(context).apply {
            radius = 12 * density
            strokeWidth = (1 * density).toInt()
            strokeColor = resolveAttr(context, com.google.android.material.R.attr.colorOutlineVariant)
            setCardBackgroundColor(resolveAttr(context, com.google.android.material.R.attr.colorSurfaceContainer))
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
                val clip = android.content.ClipData.newPlainText("Kimi Device Code", codeTv.text)
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                Toast.makeText(context, "Copied code to clipboard!", Toast.LENGTH_SHORT).show()
            }
            (codeCard.getChildAt(0) as LinearLayout).addView(this)
        }

        val progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = pad
                bottomMargin = pad
            }
            root.addView(this)
        }

        var verifyUrl = "https://auth.kimi.com"
        val openBrowserBtn = MaterialButton(context).apply {
            text = "🌐 Open Kimi Approval Page"
            visibility = View.GONE
            setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(verifyUrl))
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, "Unable to open browser", Toast.LENGTH_SHORT).show()
                }
            }
            root.addView(this)
        }

        var pollJob: Job? = null

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Sign in to Kimi Code")
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
                val deviceId = UUID.randomUUID().toString().replace("-", "")
                val formBody = FormBody.Builder()
                    .add("client_id", KIMI_CLIENT_ID)
                    .build()

                val initReq = Request.Builder()
                    .url(KIMI_DEVICE_CODE_URL)
                    .header("User-Agent", "KimiCLI/1.0")
                    .header("X-Msh-Platform", "kimi_cli")
                    .header("X-Msh-Version", "1.0")
                    .header("X-Msh-Device-Name", "Kelivo")
                    .header("X-Msh-Device-Model", "android")
                    .header("X-Msh-Device-Id", deviceId)
                    .post(formBody)
                    .build()

                val initResp = httpClient.newCall(initReq).execute()
                val initBody = initResp.body?.string().orEmpty()

                if (!initResp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        statusTv.text = "❌ Failed to initiate Kimi login: HTTP ${initResp.code}"
                    }
                    return@launch
                }

                val initJson = JSONObject(initBody)
                val deviceCode = initJson.optString("device_code")
                val userCode = initJson.optString("user_code")
                val url = initJson.optString("verification_uri_complete").takeIf { it.isNotBlank() }
                    ?: initJson.optString("verification_uri", "https://auth.kimi.com")
                verifyUrl = url
                val intervalSec = initJson.optInt("interval", 5).coerceIn(3, 30)

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.VISIBLE
                    codeTv.text = userCode
                    codeCard.visibility = View.VISIBLE
                    openBrowserBtn.visibility = View.VISIBLE
                    statusTv.text = "1. Copy the code below\n2. Open the Kimi authorization page\n3. Approve and return here"
                }

                var acquiredToken: String? = null
                for (poll in 0 until 120) {
                    delay(intervalSec * 1000L)
                    val pollForm = FormBody.Builder()
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                        .add("client_id", KIMI_CLIENT_ID)
                        .add("device_code", deviceCode)
                        .build()

                    val tokenReq = Request.Builder()
                        .url(KIMI_TOKEN_URL)
                        .header("User-Agent", "KimiCLI/1.0")
                        .header("X-Msh-Device-Id", deviceId)
                        .post(pollForm)
                        .build()

                    val tokenResp = httpClient.newCall(tokenReq).execute()
                    val tokenBody = tokenResp.body?.string().orEmpty()

                    if (tokenResp.code in listOf(400, 403, 404)) {
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
                        SecureKeyStore.putProviderApiKey(context, "kimi", acquiredToken)
                        Toast.makeText(context, "✅ Signed in to Kimi Code successfully!", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                        onSuccess(acquiredToken)
                    } else {
                        statusTv.text = "⚠️ Login timed out or cancelled. Tap cancel and try again."
                    }
                }

            } catch (_: CancellationException) {
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ Kimi login error: ${e.message}"
                }
            }
        }
    }

    private fun showClaudeLogin(
        context: Context,
        scope: CoroutineScope,
        onSuccess: (String) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }

        val infoTv = TextView(context).apply {
            text = "Connect your Claude (Anthropic) account. Tap the link to open Claude Console / OAuth Authorization, copy your session token or API key, and paste it below:"
            textSize = 12f
            setTextColor(resolveAttr(context, com.google.android.material.R.attr.colorOnSurface))
            root.addView(this)
        }

        val openBtn = MaterialButton(context).apply {
            text = "🌐 Open Claude Authorization / Console"
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
            hint = "Paste sk-ant-... token or session key"
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
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

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
