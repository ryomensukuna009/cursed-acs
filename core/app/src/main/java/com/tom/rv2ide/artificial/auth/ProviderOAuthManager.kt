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
import com.tom.rv2ide.artificial.auth.OAuthAccountRegistry
import com.tom.rv2ide.artificial.auth.OAuthAccount
import com.tom.rv2ide.artificial.models.ModelCatalogManager
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
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.BufferedWriter
import java.io.OutputStreamWriter
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

    // OpenAI Codex device auth (Official Kelivo client ID and flow)
    private const val OPENAI_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val OPENAI_USER_CODE_URL = "https://auth.openai.com/api/accounts/deviceauth/usercode"
    private const val OPENAI_DEVICE_VERIFY_URL = "https://auth.openai.com/codex/device"
    private const val OPENAI_DEVICE_TOKEN_URL = "https://auth.openai.com/api/accounts/deviceauth/token"
    private const val OPENAI_OAUTH_TOKEN_URL = "https://auth.openai.com/oauth/token"
    private const val OPENAI_TOKEN_URL = OPENAI_OAUTH_TOKEN_URL

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
            "openai", "chatgpt" -> showOpenAiBrowserLogin(context, scope, onSuccess)
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
            AccountItem("openai", "ChatGPT", "OpenAI Codex Direct Login", "✨") {
                showOpenAiBrowserLogin(context, scope) { token ->
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
            val registry = OAuthAccountRegistry.getInstance(context)
            val providerAccounts = registry.getAccounts(acc.id)
            val hasKey = SecureKeyStore.hasProviderApiKey(context, acc.id) || providerAccounts.isNotEmpty()
            val activeAcc = registry.getActiveAccount(acc.id)

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
                text = if (activeAcc != null) {
                    "✓ Active: ${activeAcc.email} (${activeAcc.plan})"
                } else {
                    acc.subtitle
                }
                textSize = 11.5f
                setTextColor(if (activeAcc != null) android.graphics.Color.parseColor("#10B981") else resolveAttr(context, com.google.android.material.R.attr.colorOnSurfaceVariant))
                infoLayout.addView(this)
            }

            cardContent.addView(infoLayout)

            val actionBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                if (hasKey) {
                    text = "Manage"
                    setTextColor(android.graphics.Color.parseColor("#10B981"))
                } else {
                    text = "🔑 Sign In"
                }
                textSize = 12f
                insetTop = 0
                insetBottom = 0
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                setOnClickListener {
                    if (hasKey) {
                        val actionList = mutableListOf<String>()
                        actionList.add("Use ${acc.name} in Chat")
                        actionList.add("Select / Search Models")
                        actionList.add("Fetch / Refresh Available Models")
                        if (providerAccounts.size > 1) {
                            actionList.add("Switch Active Account (${providerAccounts.size} added)")
                        }
                        actionList.add("+ Add Another Account")
                        actionList.add("Log Out / Remove Account")

                        val actions = actionList.toTypedArray()
                        MaterialAlertDialogBuilder(context)
                            .setTitle("${acc.name} Account Options")
                            .setItems(actions) { _, which ->
                                val selected = actions[which]
                                when {
                                    selected.startsWith("Use") -> {
                                        onAccountConnected(acc.id)
                                        hubDialog?.dismiss()
                                    }
                                    selected.startsWith("Select") -> {
                                        com.tom.rv2ide.artificial.dialogs.ProviderProfileDialogs.showSearchableModelPickerDialog(
                                            context = context,
                                            scope = scope,
                                            providerId = acc.id
                                        ) { _ ->
                                            onAccountConnected(acc.id)
                                            hubDialog?.dismiss()
                                        }
                                    }
                                    selected.startsWith("Fetch") -> {
                                        val activeKey = activeAcc?.accessToken ?: SecureKeyStore.getProviderApiKey(context, acc.id).orEmpty()
                                        when (acc.id.lowercase()) {
                                            "openai" -> fetchAndRegisterCodexModels(context, activeKey)
                                            "grok" -> fetchAndRegisterGrokModels(context, activeKey)
                                            "kimi" -> fetchAndRegisterKimiModels(context, activeKey)
                                            "claude" -> fetchAndRegisterClaudeModels(context, activeKey)
                                            "gemini" -> fetchAndRegisterGeminiModels(context, activeKey)
                                        }
                                        Toast.makeText(context, "🔄 Fetching latest ${acc.name} models...", Toast.LENGTH_SHORT).show()
                                    }
                                    selected.startsWith("Switch Active") -> {
                                        val accNames = providerAccounts.map { item ->
                                            val mark = if (item.isActive) "✓ (Active) " else ""
                                            val mail = item.email.ifBlank { item.id }
                                            "$mark$mail [${item.plan.ifBlank { "Standard" }}]"
                                        }.toTypedArray()

                                        MaterialAlertDialogBuilder(context)
                                            .setTitle("Select Active ${acc.name} Account")
                                            .setItems(accNames) { _, idx ->
                                                val chosen = providerAccounts[idx]
                                                registry.setActiveAccount(acc.id, chosen.id)
                                                Toast.makeText(context, "Active ${acc.name} set to ${chosen.email.ifBlank { chosen.id }}", Toast.LENGTH_SHORT).show()
                                                hubDialog?.dismiss()
                                                showOAuthAccountsHubDialog(context, scope, onAccountConnected)
                                            }
                                            .setNegativeButton("Cancel", null)
                                            .show()
                                    }
                                    selected.startsWith("+ Add") -> acc.action()
                                    selected.startsWith("Log Out") -> {
                                        if (activeAcc != null) {
                                            registry.removeAccount(activeAcc.id)
                                        } else {
                                            SecureKeyStore.clearProviderApiKey(acc.id)
                                        }
                                        Toast.makeText(context, "Logged out from ${acc.name}", Toast.LENGTH_SHORT).show()
                                        hubDialog?.dismiss()
                                        showOAuthAccountsHubDialog(context, scope, onAccountConnected)
                                    }
                                }
                            }
                            .show()
                    } else {
                        acc.action()
                    }
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

    private fun generatePkceVerifier(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    private fun generatePkceChallenge(verifier: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(verifier.toByteArray(Charsets.US_ASCII))
        return android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    private fun generateRandomState(): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    private fun showOpenAiBrowserLogin(
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
            text = "Starting secure direct login...\n\nYour browser will open automatically. Simply select your OpenAI account and you will be signed in immediately."
            textSize = 13f
            setTextColor(resolveAttr(context, com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, 0, 0, pad / 2)
            root.addView(this)
        }

        val progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = pad / 2
                bottomMargin = pad / 2
            }
            root.addView(this)
        }

        var authJob: Job? = null
        var serverSocket: ServerSocket? = null

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Sign in with ChatGPT")
            .setView(root)
            .setNegativeButton("Cancel") { d, _ ->
                authJob?.cancel()
                try { serverSocket?.close() } catch (_: Exception) {}
                d.dismiss()
            }
            .setOnDismissListener {
                authJob?.cancel()
                try { serverSocket?.close() } catch (_: Exception) {}
            }
            .show()

        authJob = scope.launch(Dispatchers.IO) {
            val loopbackPort = 1455
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", loopbackPort))
                    soTimeout = 300_000 // 5 minutes
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ Failed to start local callback listener on port $loopbackPort: ${e.message}"
                }
                return@launch
            }

            try {
                val redirectUri = "http://localhost:$loopbackPort/auth/callback"
                val verifier = generatePkceVerifier()
                val challenge = generatePkceChallenge(verifier)
                val state = generateRandomState()

                val authUrl = "https://auth.openai.com/oauth/authorize?" +
                    "response_type=code" +
                    "&client_id=" + OPENAI_CLIENT_ID +
                    "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, "UTF-8") +
                    "&scope=" + java.net.URLEncoder.encode("openid profile email offline_access api.connectors.read api.connectors.invoke", "UTF-8") +
                    "&code_challenge=" + challenge +
                    "&code_challenge_method=S256" +
                    "&state=" + state +
                    "&id_token_add_organizations=true" +
                    "&codex_cli_simplified_flow=true" +
                    "&originator=kelivo"

                withContext(Dispatchers.Main) {
                    statusTv.text = "🌐 Chrome opened!\n\n1. Tap your OpenAI account.\n2. Tap Authorize.\n3. You will be automatically returned here!"
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(context, "Could not open browser automatically", Toast.LENGTH_SHORT).show()
                    }
                }

                var receivedCode: String? = null
                var receivedState: String? = null

                // Wait for the browser redirect GET request
                val client = serverSocket!!.accept()
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)), true)

                val reqLine = reader.readLine()
                if (!reqLine.isNullOrBlank() && reqLine.startsWith("GET ")) {
                    val rawPath = reqLine.split(" ").getOrNull(1).orEmpty()
                    val query = rawPath.substringAfter("?", "")
                    val params = query.split("&").associate { p ->
                        val parts = p.split("=")
                        val k = parts.getOrNull(0)?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                        val v = parts.getOrNull(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                        k to v
                    }
                    receivedCode = params["code"]
                    receivedState = params["state"]
                }

                val htmlSuccess = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1">
                      <title>Authentication Successful</title>
                      <style>
                        body { background: #0f1117; color: #fff; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; padding: 20px; box-sizing: border-box; }
                        .card { background: #1a1d26; border: 1px solid #7c3aed; border-radius: 16px; padding: 32px 24px; text-align: center; max-width: 380px; width: 100%; box-shadow: 0 10px 30px rgba(0,0,0,0.5); }
                        .icon { font-size: 48px; margin-bottom: 12px; }
                        h2 { margin: 0 0 10px; font-size: 20px; font-weight: 700; color: #f1f5f9; }
                        p { margin: 0; font-size: 14px; color: #94a3b8; line-height: 1.5; }
                      </style>
                    </head>
                    <body>
                      <div class="card">
                        <div class="icon">✨</div>
                        <h2>Successfully Signed In!</h2>
                        <p>You have connected your OpenAI account to CURSED ACS.<br><br>You can close this tab and return to the app.</p>
                      </div>
                    </body>
                    </html>
                """.trimIndent()

                val htmlBytes = htmlSuccess.toByteArray(Charsets.UTF_8)
                val responseHeaders = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: " + htmlBytes.size + "\r\n" +
                    "Connection: close\r\n" +
                    "Cache-Control: no-store\r\n\r\n"

                writer.print(responseHeaders)
                writer.print(htmlSuccess)
                writer.flush()
                try { client.close() } catch (_: Exception) {}
                try { serverSocket?.close() } catch (_: Exception) {}

                if (receivedState != state || receivedCode.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        statusTv.text = "⚠️ Authentication cancelled or state mismatched."
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    statusTv.text = "Exchanging authorization token with OpenAI..."
                }

                // Step 2: Exchange authorization_code with OAuth token endpoint
                val exchangeForm = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("client_id", OPENAI_CLIENT_ID)
                    .add("code", receivedCode)
                    .add("code_verifier", verifier)
                    .add("redirect_uri", redirectUri)
                    .build()

                var exchangeReq = Request.Builder()
                    .url(OPENAI_OAUTH_TOKEN_URL)
                    .header("Accept", "application/json")
                    .header("originator", "kelivo")
                    .header("User-Agent", "Kelivo")
                    .post(exchangeForm)
                    .build()

                var exchangeResp = httpClient.newCall(exchangeReq).execute()
                var exchangeBody = exchangeResp.body?.string().orEmpty()

                // If form payload returned HTTP 400 with dictionary / schema error, retry with application/json
                if (!exchangeResp.isSuccessful && (exchangeResp.code == 400 || exchangeBody.contains("dictionary"))) {
                    val jsonPayload = JSONObject().apply {
                        put("grant_type", "authorization_code")
                        put("client_id", OPENAI_CLIENT_ID)
                        put("code", receivedCode)
                        put("code_verifier", verifier)
                        put("redirect_uri", redirectUri)
                    }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                    exchangeReq = Request.Builder()
                        .url(OPENAI_OAUTH_TOKEN_URL)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("originator", "kelivo")
                        .header("User-Agent", "Kelivo")
                        .post(jsonPayload)
                        .build()

                    exchangeResp = httpClient.newCall(exchangeReq).execute()
                    exchangeBody = exchangeResp.body?.string().orEmpty()
                }

                if (!exchangeResp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        statusTv.text = "❌ Token exchange failed: HTTP ${exchangeResp.code}\n${exchangeBody.take(150)}"
                    }
                    return@launch
                }

                val exchangeJson = JSONObject(exchangeBody)
                val acquiredToken = exchangeJson.optString("access_token")
                val acquiredRefreshToken = exchangeJson.optString("refresh_token")

                if (acquiredToken.isBlank()) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        statusTv.text = "❌ No access token returned by OpenAI."
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    SecureKeyStore.putProviderApiKey(context, "openai", acquiredToken)
                    SecureKeyStore.putProviderApiKey(context, "chatgpt", acquiredToken)
                    val (email, plan, accountId) = extractJwtProfile(acquiredToken)
                    val account = OAuthAccount(
                        provider = "openai",
                        email = email.ifBlank { "ChatGPT User" },
                        plan = plan.ifBlank { "Codex" },
                        accessToken = acquiredToken,
                        refreshToken = acquiredRefreshToken.orEmpty(),
                        expiresAt = System.currentTimeMillis() + 86400_000L * 30,
                        isActive = true,
                        accountId = accountId.takeIf { it.isNotBlank() }
                    )
                    OAuthAccountRegistry.getInstance(context).saveAccount(account)
                    OAuthAccountRegistry.getInstance(context).setActiveAccount("openai", account.id)

                    val reg = ProviderRegistryManager.getInstance(context)
                    val openAiProf = reg.getProfile("openai")
                    if (openAiProf != null && !openAiProf.enabled) {
                        reg.saveProfile(openAiProf.copy(enabled = true))
                    }

                    fetchAndRegisterCodexModels(context, acquiredToken)
                    Toast.makeText(context, "✅ Signed in to OpenAI successfully! (${account.email})", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    onSuccess(acquiredToken)
                }

            } catch (_: SocketTimeoutException) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "⏱️ Sign-in timed out. Tap cancel and try again."
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusTv.text = "❌ Login error: ${e.message}"
                }
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
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
                    statusTv.text = "1. Code $userCode copied to clipboard!\n2. Opening browser to verify...\n3. Approve access and return here once finished."

                    try {
                        val clip = android.content.ClipData.newPlainText("Grok Device Code", userCode)
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                        Toast.makeText(context, "Code $userCode copied to clipboard!", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}

                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(verifyUrl)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }

                var acquiredToken: String? = null
                var acquiredRefreshToken: String? = null
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
                        acquiredRefreshToken = tokenJson.optString("refresh_token")
                        if (accessToken.isNotBlank()) {
                            acquiredToken = accessToken
                            break
                        }
                    }
                }

                var userEmail = ""
                var accountSub = ""
                if (acquiredToken != null) {
                    try {
                        val userReq = Request.Builder()
                            .url("https://auth.x.ai/oauth2/userinfo")
                            .header("Authorization", "Bearer $acquiredToken")
                            .header("X-XAI-Token-Auth", "xai-grok-cli")
                            .get()
                            .build()
                        val userResp = httpClient.newCall(userReq).execute()
                        val uBody = userResp.body?.string().orEmpty()
                        if (userResp.isSuccessful && uBody.isNotBlank()) {
                            val uJson = JSONObject(uBody)
                            userEmail = uJson.optString("email")
                            accountSub = uJson.optString("sub")
                        }
                    } catch (_: Exception) {}
                    if (userEmail.isBlank()) {
                        val (jwtEmail, _, _) = extractJwtProfile(acquiredToken)
                        userEmail = jwtEmail
                    }
                }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    if (acquiredToken != null) {
                        SecureKeyStore.putProviderApiKey(context, "grok", acquiredToken)
                        SecureKeyStore.putProviderApiKey(context, "xai", acquiredToken)

                        val account = OAuthAccount(
                            provider = "grok",
                            email = userEmail.ifBlank { "Grok User" + if (accountSub.isNotBlank()) " (${accountSub.take(6)})" else "" },
                            plan = "SuperGrok / Pro",
                            accessToken = acquiredToken,
                            refreshToken = acquiredRefreshToken.orEmpty(),
                            expiresAt = System.currentTimeMillis() + 86400_000L * 30,
                            isActive = true,
                            accountId = accountSub.takeIf { it.isNotBlank() }
                        )
                        SecureKeyStore.putProviderApiKey(context, "grok", acquiredToken)
                        SecureKeyStore.putProviderApiKey(context, "xai", acquiredToken)
                        OAuthAccountRegistry.getInstance(context).saveAccount(account)
                        OAuthAccountRegistry.getInstance(context).setActiveAccount("grok", account.id)

                        val reg = ProviderRegistryManager.getInstance(context)
                        val grokProfile = reg.getProfile("grok")
                        if (grokProfile != null) {
                            reg.saveProfile(grokProfile.copy(enabled = true, selectedModelId = "grok-2-latest"))
                        }

                        fetchAndRegisterGrokModels(context, acquiredToken)
                        Toast.makeText(context, "✅ Signed in to Grok successfully! (${account.email})", Toast.LENGTH_LONG).show()
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
                    statusTv.text = "1. Code $userCode copied to clipboard!\n2. Opening browser to verify...\n3. Approve and return here once finished."

                    try {
                        val clip = android.content.ClipData.newPlainText("Kimi Device Code", userCode)
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                        Toast.makeText(context, "Code $userCode copied to clipboard!", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}

                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(verifyUrl)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {}
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
                        SecureKeyStore.putProviderApiKey(context, "moonshot", acquiredToken)

                        val account = OAuthAccount(
                            provider = "kimi",
                            email = "Kimi User (${deviceId.take(6)})",
                            plan = "Kimi Code Pro",
                            accessToken = acquiredToken,
                            expiresAt = System.currentTimeMillis() + 86400_000L * 30,
                            isActive = true
                        )
                        SecureKeyStore.putProviderApiKey(context, "grok", acquiredToken)
                        SecureKeyStore.putProviderApiKey(context, "xai", acquiredToken)
                        OAuthAccountRegistry.getInstance(context).saveAccount(account)
                        OAuthAccountRegistry.getInstance(context).setActiveAccount("kimi", account.id)

                        val reg = ProviderRegistryManager.getInstance(context)
                        val kimiProf = reg.getProfile("kimi")
                        if (kimiProf != null) {
                            reg.saveProfile(kimiProf.copy(enabled = true, selectedModelId = "moonshot-v1-8k"))
                        }

                        fetchAndRegisterKimiModels(context, acquiredToken)
                        Toast.makeText(context, "✅ Signed in to Kimi Code successfully! (${account.email})", Toast.LENGTH_LONG).show()
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

                    val account = OAuthAccount(
                        provider = "claude",
                        email = "Claude Account (${key.take(8)}...)",
                        plan = "Anthropic Pro / Console",
                        accessToken = key,
                        expiresAt = System.currentTimeMillis() + 86400_000L * 30,
                        isActive = true
                    )
                    OAuthAccountRegistry.getInstance(context).saveAccount(account)
                    OAuthAccountRegistry.getInstance(context).setActiveAccount("claude", account.id)

                    val reg = ProviderRegistryManager.getInstance(context)
                    val prof = reg.getProfile("anthropic")
                    if (prof != null) {
                        reg.saveProfile(prof.copy(enabled = true, selectedModelId = "claude-3-5-sonnet-20241022"))
                    }

                    fetchAndRegisterClaudeModels(context, key)
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
                    SecureKeyStore.putProviderApiKey(context, "google", key)

                    val account = OAuthAccount(
                        provider = "gemini",
                        email = "Google AI Studio (${key.take(8)}...)",
                        plan = "Gemini Flash / Pro",
                        accessToken = key,
                        expiresAt = System.currentTimeMillis() + 86400_000L * 30,
                        isActive = true
                    )
                    OAuthAccountRegistry.getInstance(context).saveAccount(account)
                    OAuthAccountRegistry.getInstance(context).setActiveAccount("gemini", account.id)

                    val reg = ProviderRegistryManager.getInstance(context)
                    val prof = reg.getProfile("gemini") ?: reg.getProfile("gemini_native")
                    if (prof != null) {
                        reg.saveProfile(prof.copy(enabled = true, selectedModelId = "gemini-2.0-flash"))
                    }

                    fetchAndRegisterGeminiModels(context, key)
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

    private fun extractJwtProfile(token: String): Triple<String, String, String> {
        try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                val decoded = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING), Charsets.UTF_8)
                val json = JSONObject(decoded)
                val profile = json.optJSONObject("https://api.openai.com/profile")
                val auth = json.optJSONObject("https://api.openai.com/auth")
                val email = profile?.optString("email") ?: json.optString("email")
                val plan = auth?.optString("chatgpt_plan_type") ?: json.optString("plan")
                val accountId = auth?.optString("chatgpt_account_id")?.takeIf { it.isNotBlank() }
                    ?: profile?.optString("chatgpt_account_id")?.takeIf { it.isNotBlank() }
                    ?: json.optString("chatgpt_account_id")?.takeIf { it.isNotBlank() }
                    ?: ""
                return Triple(email, plan, accountId)
            }
        } catch (_: Exception) {}
        return Triple("", "", "")
    }

    fun fetchAndRegisterCodexModels(context: Context, token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val modelsFound = mutableListOf<com.tom.rv2ide.artificial.providers.DiscoveredModel>()
            val catalog = ModelCatalogManager.getInstance(context)

            try {
                val req = Request.Builder()
                    .url("https://chatgpt.com/backend-api/codex/models?client_version=0.159.0")
                    .header("Authorization", "Bearer $token")
                    .header("originator", "kelivo")
                    .header("version", "0.159.0")
                    .header("User-Agent", "Kelivo")
                    .get()
                    .build()
                val resp = httpClient.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data") ?: json.optJSONArray("models")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val m = data.getJSONObject(i)
                            val id = m.optString("id").ifBlank { m.optString("slug") }
                            val name = m.optString("name", id)
                            if (id.isNotBlank()) {
                                catalog.registerModel("openai", id, name)
                                val caps = catalog.inferCapabilities("openai", id)
                                modelsFound.add(
                                    com.tom.rv2ide.artificial.providers.DiscoveredModel(
                                        providerId = "openai",
                                        id = id,
                                        displayName = name,
                                        capabilities = caps,
                                        discoveredAtMs = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            if (modelsFound.isEmpty()) {
                val defaults = listOf(
                    "gpt-4o" to "GPT-4o (Omni Multimodal & Vision)",
                    "chatgpt-4o-latest" to "ChatGPT-4o Latest",
                    "o1" to "OpenAI o1 (Advanced Reasoning)",
                    "o3-mini" to "OpenAI o3-mini (High-Speed Reasoning)",
                    "gpt-4o-mini" to "GPT-4o Mini",
                    "gpt-4-turbo" to "GPT-4 Turbo"
                )
                for ((id, name) in defaults) {
                    catalog.registerModel("openai", id, name)
                    val caps = catalog.inferCapabilities("openai", id)
                    modelsFound.add(
                        com.tom.rv2ide.artificial.providers.DiscoveredModel(
                            providerId = "openai",
                            id = id,
                            displayName = name,
                            capabilities = caps,
                            discoveredAtMs = System.currentTimeMillis()
                        )
                    )
                }
            }

            val distinct = modelsFound.distinctBy { it.id }
            val reg = ProviderRegistryManager.getInstance(context)
            reg.cacheDiscoveredModels("openai", distinct)
        }
    }

    fun fetchAndRegisterGrokModels(context: Context, token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val modelsFound = mutableListOf<com.tom.rv2ide.artificial.providers.DiscoveredModel>()
            val catalog = ModelCatalogManager.getInstance(context)

            val urls = listOf(
                "https://cli-chat-proxy.grok.com/v1/models-v2",
                "https://api.x.ai/v1/models"
            )
            for (url in urls) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .header("X-XAI-Token-Auth", "xai-grok-cli")
                        .get()
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && body.isNotBlank()) {
                        val json = JSONObject(body)
                        val data = json.optJSONArray("data") ?: json.optJSONArray("models")
                        if (data != null) {
                            for (i in 0 until data.length()) {
                                val item = data.optJSONObject(i) ?: continue
                                val meta = item.optJSONObject("_meta")
                                val id = item.optString("model").ifBlank {
                                    item.optString("modelId").ifBlank {
                                        item.optString("id").ifBlank {
                                            meta?.optString("model").orEmpty()
                                        }
                                    }
                                }
                                if (id.isBlank() || item.optBoolean("hidden", false)) continue
                                val name = item.optString("name", id)
                                catalog.registerModel("grok", id, name)
                                val caps = catalog.inferCapabilities("grok", id)
                                modelsFound.add(
                                    com.tom.rv2ide.artificial.providers.DiscoveredModel(
                                        providerId = "grok",
                                        id = id,
                                        displayName = name,
                                        capabilities = caps,
                                        discoveredAtMs = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            if (modelsFound.isEmpty()) {
                val defaults = listOf(
                    "grok-3" to "Grok 3 (Deep Thinking & Multimodal)",
                    "grok-3-mini" to "Grok 3 Mini (Fast & Reasoning)",
                    "grok-2-latest" to "Grok 2 Latest (Vision & Tools)",
                    "grok-2" to "Grok 2",
                    "grok-vision-beta" to "Grok Vision Beta",
                    "grok-beta" to "Grok Beta"
                )
                for ((id, name) in defaults) {
                    catalog.registerModel("grok", id, name)
                    val caps = catalog.inferCapabilities("grok", id)
                    modelsFound.add(
                        com.tom.rv2ide.artificial.providers.DiscoveredModel(
                            providerId = "grok",
                            id = id,
                            displayName = name,
                            capabilities = caps,
                            discoveredAtMs = System.currentTimeMillis()
                        )
                    )
                }
            }

            val distinct = modelsFound.distinctBy { it.id }
            val reg = ProviderRegistryManager.getInstance(context)
            reg.cacheDiscoveredModels("grok", distinct)
        }
    }

    fun fetchAndRegisterKimiModels(context: Context, token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val modelsFound = mutableListOf<com.tom.rv2ide.artificial.providers.DiscoveredModel>()
            val catalog = ModelCatalogManager.getInstance(context)

            val urls = listOf("https://api.kimi.com/coding/v1/models", "https://api.moonshot.cn/v1/models")
            for (url in urls) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .header("User-Agent", "KimiCLI/1.0")
                        .get()
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && body.isNotBlank()) {
                        val json = JSONObject(body)
                        val data = json.optJSONArray("data")
                        if (data != null) {
                            for (i in 0 until data.length()) {
                                val item = data.optJSONObject(i) ?: continue
                                val id = item.optString("id")
                                if (id.isBlank()) continue
                                val name = item.optString("name", id)
                                catalog.registerModel("kimi", id, name)
                                val caps = catalog.inferCapabilities("kimi", id)
                                modelsFound.add(
                                    com.tom.rv2ide.artificial.providers.DiscoveredModel(
                                        providerId = "kimi",
                                        id = id,
                                        displayName = name,
                                        capabilities = caps,
                                        discoveredAtMs = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            if (modelsFound.isEmpty()) {
                val defaults = listOf(
                    "moonshot-v1-8k" to "Moonshot v1 8K (Coding)",
                    "moonshot-v1-32k" to "Moonshot v1 32K (Large Context)",
                    "moonshot-v1-128k" to "Moonshot v1 128K (Massive Context)",
                    "kimi-latest" to "Kimi Latest"
                )
                for ((id, name) in defaults) {
                    catalog.registerModel("kimi", id, name)
                    val caps = catalog.inferCapabilities("kimi", id)
                    modelsFound.add(
                        com.tom.rv2ide.artificial.providers.DiscoveredModel(
                            providerId = "kimi",
                            id = id,
                            displayName = name,
                            capabilities = caps,
                            discoveredAtMs = System.currentTimeMillis()
                        )
                    )
                }
            }

            val distinct = modelsFound.distinctBy { it.id }
            val reg = ProviderRegistryManager.getInstance(context)
            reg.cacheDiscoveredModels("kimi", distinct)
        }
    }

    fun fetchAndRegisterClaudeModels(context: Context, key: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val modelsFound = mutableListOf<com.tom.rv2ide.artificial.providers.DiscoveredModel>()
            val catalog = ModelCatalogManager.getInstance(context)

            try {
                val req = Request.Builder()
                    .url("https://api.anthropic.com/v1/models")
                    .header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01")
                    .get()
                    .build()
                val resp = httpClient.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val item = data.optJSONObject(i) ?: continue
                            val id = item.optString("id")
                            if (id.isBlank()) continue
                            val name = item.optString("display_name", id)
                            catalog.registerModel("claude", id, name)
                            catalog.registerModel("anthropic", id, name)
                            val caps = catalog.inferCapabilities("anthropic", id)
                            modelsFound.add(
                                com.tom.rv2ide.artificial.providers.DiscoveredModel(
                                    providerId = "anthropic",
                                    id = id,
                                    displayName = name,
                                    capabilities = caps,
                                    discoveredAtMs = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}

            if (modelsFound.isEmpty()) {
                val defaults = listOf(
                    "claude-3-7-sonnet-20250219" to "Claude 3.7 Sonnet (Hybrid Thinking & Vision)",
                    "claude-3-5-sonnet-20241022" to "Claude 3.5 Sonnet (State of the Art)",
                    "claude-3-5-haiku-20241022" to "Claude 3.5 Haiku (Lightning Fast)",
                    "claude-3-opus-20240229" to "Claude 3 Opus"
                )
                for ((id, name) in defaults) {
                    catalog.registerModel("claude", id, name)
                    catalog.registerModel("anthropic", id, name)
                    val caps = catalog.inferCapabilities("anthropic", id)
                    modelsFound.add(
                        com.tom.rv2ide.artificial.providers.DiscoveredModel(
                            providerId = "anthropic",
                            id = id,
                            displayName = name,
                            capabilities = caps,
                            discoveredAtMs = System.currentTimeMillis()
                        )
                    )
                }
            }

            val distinct = modelsFound.distinctBy { it.id }
            val reg = ProviderRegistryManager.getInstance(context)
            reg.cacheDiscoveredModels("anthropic", distinct)
            reg.cacheDiscoveredModels("claude", distinct)
        }
    }

    fun fetchAndRegisterGeminiModels(context: Context, key: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val modelsFound = mutableListOf<com.tom.rv2ide.artificial.providers.DiscoveredModel>()
            val catalog = ModelCatalogManager.getInstance(context)

            try {
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models?key=$key")
                    .get()
                    .build()
                val resp = httpClient.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val json = JSONObject(body)
                    val data = json.optJSONArray("models")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val item = data.optJSONObject(i) ?: continue
                            val rawName = item.optString("name")
                            val id = if (rawName.startsWith("models/")) rawName.removePrefix("models/") else rawName
                            if (id.isBlank() || !id.contains("gemini")) continue
                            val displayName = item.optString("displayName", id)
                            catalog.registerModel("gemini", id, displayName)
                            catalog.registerModel("gemini_native", id, displayName)
                            catalog.registerModel("gemini_openai", id, displayName)
                            val caps = catalog.inferCapabilities("gemini", id)
                            modelsFound.add(
                                com.tom.rv2ide.artificial.providers.DiscoveredModel(
                                    providerId = "gemini",
                                    id = id,
                                    displayName = displayName,
                                    capabilities = caps,
                                    discoveredAtMs = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}

            if (modelsFound.isEmpty()) {
                val defaults = listOf(
                    "gemini-2.0-flash" to "Gemini 2.0 Flash (Next-Gen Multimodal)",
                    "gemini-2.0-flash-thinking-exp-01-21" to "Gemini 2.0 Flash Thinking",
                    "gemini-1.5-pro" to "Gemini 1.5 Pro (1M Context)",
                    "gemini-1.5-flash" to "Gemini 1.5 Flash (Fast)"
                )
                for ((id, name) in defaults) {
                    catalog.registerModel("gemini", id, name)
                    val caps = catalog.inferCapabilities("gemini", id)
                    modelsFound.add(
                        com.tom.rv2ide.artificial.providers.DiscoveredModel(
                            providerId = "gemini",
                            id = id,
                            displayName = name,
                            capabilities = caps,
                            discoveredAtMs = System.currentTimeMillis()
                        )
                    )
                }
            }

            val distinct = modelsFound.distinctBy { it.id }
            val reg = ProviderRegistryManager.getInstance(context)
            reg.cacheDiscoveredModels("gemini", distinct)
            reg.cacheDiscoveredModels("gemini_native", distinct)
            reg.cacheDiscoveredModels("gemini_openai", distinct)
        }
    }
}