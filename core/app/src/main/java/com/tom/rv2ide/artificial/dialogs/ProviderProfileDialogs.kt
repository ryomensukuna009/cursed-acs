package com.tom.rv2ide.artificial.dialogs

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.artificial.discovery.ModelDiscoveryService
import com.tom.rv2ide.artificial.providers.ApiProtocol
import com.tom.rv2ide.artificial.providers.AuthStyle
import com.tom.rv2ide.artificial.providers.DiscoveredModel
import com.tom.rv2ide.artificial.providers.ProviderProfile
import com.tom.rv2ide.artificial.providers.ProviderRegistryManager
import com.tom.rv2ide.artificial.providers.ProviderTemplates
import com.tom.rv2ide.artificial.providers.TriStateCapability
import com.tom.rv2ide.artificial.secrets.SecretRedactor
import com.tom.rv2ide.artificial.secrets.SecureKeyStore
import com.tom.rv2ide.artificial.tools.SearchProviderType
import com.tom.rv2ide.artificial.tools.WebSearchConfig
import com.tom.rv2ide.artificial.tools.WebSearchManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ProviderProfileDialogs {

    fun showAddFromTemplateDialog(
        context: Context,
        scope: CoroutineScope,
        onProfileSaved: (ProviderProfile) -> Unit
    ) {
        val templates = ProviderTemplates.getSetupTemplates()
        val labels = templates.map { "${it.displayName} (${it.protocol.name})" }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setTitle("Add AI Provider Profile")
            .setItems(labels) { _, which ->
                val chosen = templates[which]
                val newProfile = ProviderTemplates.createCustomProfileFromTemplate(
                    template = chosen,
                    customDisplayName = if (chosen.id == "custom") "Custom Provider" else "${chosen.displayName} (Custom)"
                )
                showEditProfileDialog(
                    context = context,
                    scope = scope,
                    profile = newProfile,
                    isNew = true,
                    onProfileSaved = onProfileSaved,
                    onProfileDeleted = {}
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showEditProfileDialog(
        context: Context,
        scope: CoroutineScope,
        profile: ProviderProfile,
        isNew: Boolean = false,
        onProfileSaved: (ProviderProfile) -> Unit,
        onProfileDeleted: (String) -> Unit = {}
    ) {
        val registry = ProviderRegistryManager.getInstance(context)
        val discoveryService = ModelDiscoveryService()
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val scroll = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }
        scroll.addView(container)

        fun addLabel(text: String): TextView {
            return TextView(context).apply {
                this.text = text
                textSize = 12f
                setPadding(0, pad / 2, 0, 4)
                container.addView(this)
            }
        }

        addLabel("Display Name")
        val nameInput = EditText(context).apply {
            setText(profile.displayName)
            hint = "e.g., OpenRouter, Ollama LAN, Custom vLLM"
            container.addView(this)
        }

        addLabel("API Protocol / Compatibility Type")
        val protocols = ApiProtocol.entries
        val protocolSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                protocols.map { it.name }
            )
            setSelection(protocols.indexOf(profile.protocol).coerceAtLeast(0))
            container.addView(this)
        }

        addLabel("Base URL")
        val baseUrlInput = EditText(context).apply {
            setText(profile.baseUrl)
            hint = "https://api.openai.com/v1"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            container.addView(this)
        }

        val currentKey = SecureKeyStore.getProviderApiKey(context, profile.id).orEmpty()
        val maskedHint = if (currentKey.isNotBlank()) {
            "Saved: ${SecretRedactor.maskKey(currentKey)} (leave blank to keep)"
        } else {
            "Enter API key (stored encrypted)"
        }
        addLabel("API Key ($maskedHint)")
        val apiKeyInput = EditText(context).apply {
            hint = maskedHint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            container.addView(this)
        }

        if (profile.id.lowercase() in listOf("openai", "chatgpt", "gemini", "google", "claude", "anthropic")) {
            val loginBtn = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "🔑 Sign In with Account (OAuth / Device Code)"
                setOnClickListener {
                    com.tom.rv2ide.artificial.auth.ProviderOAuthManager.showAccountLoginDialog(
                        context,
                        scope,
                        profile.id
                    ) { token ->
                        apiKeyInput.setText(token)
                    }
                }
            }
            container.addView(loginBtn)
        }

        addLabel("Authentication Header Style")
        val authStyles = AuthStyle.entries
        val authSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                authStyles.map { it.name }
            )
            setSelection(authStyles.indexOf(profile.authStyle).coerceAtLeast(0))
            container.addView(this)
        }

        addLabel("Custom Auth Header Name (only for CUSTOM_HEADER)")
        val customAuthHeaderInput = EditText(context).apply {
            setText(profile.customAuthHeaderName.orEmpty())
            hint = "e.g., X-API-Key or Api-Key"
            container.addView(this)
        }

        addLabel("Models Discovery Endpoint Path (optional override)")
        val modelsPathInput = EditText(context).apply {
            setText(profile.modelsEndpointPath)
            hint = "/models"
            container.addView(this)
        }

        addLabel("Chat / Generation Endpoint Path (optional override)")
        val chatPathInput = EditText(context).apply {
            setText(profile.chatEndpointPath)
            hint = "/chat/completions"
            container.addView(this)
        }

        addLabel("Manual Model ID Override (optional, used if discovery is unavailable)")
        val manualModelInput = EditText(context).apply {
            setText(profile.manualModelIdOverride)
            hint = "e.g., openai/gpt-4o or llama3.2:latest"
            container.addView(this)
        }

        addLabel("Custom Headers (one 'Header-Name: value' per line)")
        val customHeadersInput = EditText(context).apply {
            val headerLines = profile.customHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            setText(headerLines)
            hint = "HTTP-Referer: https://example.com\nX-Title: My IDE"
            minLines = 2
            container.addView(this)
        }

        addLabel("Timeout (seconds)")
        val timeoutInput = EditText(context).apply {
            setText(profile.timeoutSec.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            container.addView(this)
        }

        val enabledCheck = CheckBox(context).apply {
            text = "Provider Enabled"
            isChecked = profile.enabled
            container.addView(this)
        }

        val streamingCheck = CheckBox(context).apply {
            text = "Enable SSE / NDJSON Streaming"
            isChecked = profile.streamingEnabled
            container.addView(this)
        }

        val cleartextCheck = CheckBox(context).apply {
            text = "Allow Cleartext HTTP (for trusted LAN / localhost only)"
            isChecked = profile.allowCleartextHttp
            container.addView(this)
        }

        val geminiGroundingCheck = CheckBox(context).apply {
            text = "Enable Gemini Native Google Search Grounding (GEMINI_NATIVE only)"
            isChecked = profile.enableGeminiNativeGrounding
            container.addView(this)
        }

        addLabel("Tool Calling Capability Override")
        val toolCapOptions = listOf(
            "Auto / Discovered (Default)",
            "Force Enabled (Support Tool Calling)",
            "Disabled (Plain Text Only)"
        )
        val toolCapValues = listOf(
            TriStateCapability.UNKNOWN,
            TriStateCapability.SUPPORTED,
            TriStateCapability.UNSUPPORTED
        )
        val toolCapSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, toolCapOptions)
            val currentIdx = toolCapValues.indexOf(profile.userOverrideToolCalling).coerceAtLeast(0)
            setSelection(currentIdx)
            container.addView(this)
        }

        val testStatusView = TextView(context).apply {
            text = ""
            textSize = 12f
            setPadding(0, pad / 2, 0, pad / 2)
            container.addView(this)
        }

        fun buildDraftProfile(): Pair<ProviderProfile, String?> {
            val enteredKey = apiKeyInput.text?.toString()?.trim().orEmpty()
            val effectiveKey = if (enteredKey.isNotEmpty()) enteredKey else currentKey.takeIf { it.isNotBlank() }

            val headersMap = mutableMapOf<String, String>()
            customHeadersInput.text?.toString().orEmpty().lines().forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim()
                    val v = line.substring(idx + 1).trim()
                    if (k.isNotEmpty()) headersMap[k] = v
                }
            }

            val draft = profile.copy(
                displayName = nameInput.text?.toString()?.trim()?.ifBlank { profile.displayName } ?: profile.displayName,
                protocol = protocols[protocolSpinner.selectedItemPosition],
                baseUrl = baseUrlInput.text?.toString()?.trim().orEmpty(),
                authStyle = authStyles[authSpinner.selectedItemPosition],
                customAuthHeaderName = customAuthHeaderInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                customHeaders = headersMap,
                modelsEndpointPath = modelsPathInput.text?.toString()?.trim().orEmpty(),
                chatEndpointPath = chatPathInput.text?.toString()?.trim().orEmpty(),
                manualModelIdOverride = manualModelInput.text?.toString()?.trim().orEmpty(),
                selectedModelId = manualModelInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: profile.selectedModelId,
                timeoutSec = timeoutInput.text?.toString()?.toIntOrNull()?.coerceIn(5, 600) ?: 90,
                enabled = enabledCheck.isChecked,
                streamingEnabled = streamingCheck.isChecked,
                allowCleartextHttp = cleartextCheck.isChecked,
                enableGeminiNativeGrounding = geminiGroundingCheck.isChecked,
                userOverrideToolCalling = toolCapValues[toolCapSpinner.selectedItemPosition.coerceIn(toolCapValues.indices)]
            )
            return Pair(draft, effectiveKey)
        }

        val testBtn = MaterialButton(context).apply {
            text = "Test Connection & Fetch Models"
            setOnClickListener {
                val (draft, effectiveKey) = buildDraftProfile()
                testStatusView.text = "Testing connection to ${draft.baseUrl}..."
                isEnabled = false
                scope.launch {
                    val res = discoveryService.testConnection(draft, effectiveKey)
                    if (res.success) {
                        val disc = discoveryService.discoverModels(draft, effectiveKey)
                        if (disc.models.isNotEmpty()) {
                            registry.cacheDiscoveredModels(draft.id, disc.models)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        isEnabled = true
                        testStatusView.text = if (res.success) "✅ ${res.message}" else "❌ ${res.message}"
                    }
                }
            }
            container.addView(this)
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(if (isNew) "Add Provider: ${profile.displayName}" else "Configure ${profile.displayName}")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val (updated, _) = buildDraftProfile()
                val enteredKey = apiKeyInput.text?.toString()?.trim().orEmpty()
                if (enteredKey.isNotEmpty()) {
                    registry.setProviderApiKey(updated.id, enteredKey)
                }
                registry.saveProfile(updated)
                onProfileSaved(updated)
            }
            .setNegativeButton("Cancel", null)

        if (!isNew && !profile.isBuiltInPreset) {
            builder.setNeutralButton("Delete") { _, _ ->
                registry.deleteProfile(profile.id)
                onProfileDeleted(profile.id)
            }
        } else if (!isNew && currentKey.isNotBlank()) {
            builder.setNeutralButton("Clear Key") { _, _ ->
                registry.clearProviderApiKey(profile.id)
                Toast.makeText(context, "Cleared API key for ${profile.displayName}", Toast.LENGTH_SHORT).show()
                onProfileSaved(profile)
            }
        }

        builder.show()
    }

    fun showSearchableModelPickerDialog(
        context: Context,
        scope: CoroutineScope,
        providerId: String,
        onModelSelected: (String) -> Unit
    ) {
        val registry = ProviderRegistryManager.getInstance(context)
        val profile = registry.getProfile(providerId) ?: return
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val statusHeader = TextView(context).apply {
            val cachedCount = registry.getCachedModels(providerId).size
            text = if (cachedCount > 0) {
                "Discovered models ($cachedCount cached) — tap Fetch to refresh"
            } else {
                "No live models fetched yet — tap Fetch Live Models or type a manual Model ID"
            }
            textSize = 12f
            setPadding(0, 0, 0, 8)
            root.addView(this)
        }

        val searchInput = EditText(context).apply {
            hint = "Search models by ID, name, or type manual Model ID..."
            root.addView(this)
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            root.addView(this)
        }

        val fetchBtn = MaterialButton(context).apply {
            text = "Fetch Live Models"
            buttonRow.addView(this)
        }

        val useManualBtn = MaterialButton(context).apply {
            text = "Use Typed ID"
            buttonRow.addView(this)
        }

        val listView = ListView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (320 * context.resources.displayMetrics.density).toInt()
            )
            root.addView(this)
        }

        var currentModels: List<DiscoveredModel> = registry.getCachedModels(providerId)

        fun formatModelRow(m: DiscoveredModel): String {
            val metaParts = mutableListOf<String>()
            m.capabilities.contextLength?.let { ctxLen ->
                metaParts.add("ctx: ${ctxLen / 1000}k")
            }
            when (m.capabilities.supportsTools) {
                TriStateCapability.SUPPORTED -> metaParts.add("tools: yes")
                TriStateCapability.UNSUPPORTED -> metaParts.add("tools: no")
                TriStateCapability.UNKNOWN -> {}
            }
            when (m.capabilities.supportsVision) {
                TriStateCapability.SUPPORTED -> metaParts.add("vision")
                else -> {}
            }
            val suffix = if (metaParts.isNotEmpty()) "  [${metaParts.joinToString(", ")}]" else ""
            return "${m.id}$suffix"
        }

        var displayedModels: List<DiscoveredModel> = currentModels

        val catalog = com.tom.rv2ide.artificial.models.ModelCatalogManager.getInstance(context)
        var modelAdapter: DiscoveredModelAdapter? = null
        var dialog: androidx.appcompat.app.AlertDialog? = null

        fun refreshList(query: String) {
            val q = query.trim().lowercase()
            displayedModels = if (q.isEmpty()) {
                currentModels
            } else {
                currentModels.filter {
                    it.id.lowercase().contains(q) ||
                        it.displayName.lowercase().contains(q) ||
                        it.ownedBy?.lowercase()?.contains(q) == true
                }
            }
            if (modelAdapter == null) {
                modelAdapter = DiscoveredModelAdapter(context, displayedModels, catalog) { chosen ->
                    onModelSelected(chosen.id)
                    dialog?.dismiss()
                }
                listView.adapter = modelAdapter
                listView.divider = null
                listView.dividerHeight = 0
            } else {
                modelAdapter?.updateItems(displayedModels)
            }
        }

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Select Model — ${profile.displayName}")
            .setView(root)
            .setNegativeButton("Close", null)
            .create()

        refreshList("")

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        fetchBtn.setOnClickListener {
            fetchBtn.isEnabled = false
            statusHeader.text = "Fetching live models from ${profile.displayName}..."
            scope.launch {
                val res = registry.fetchAndCacheModels(providerId)
                withContext(Dispatchers.Main) {
                    fetchBtn.isEnabled = true
                    if (res.models.isNotEmpty()) {
                        currentModels = res.models
                        statusHeader.text = "✅ Fetched ${res.models.size} live models from ${profile.displayName}"
                        refreshList(searchInput.text?.toString().orEmpty())
                    } else {
                        val codexFallback = if (providerId.equals("openai", ignoreCase = true)) {
                            listOf(
                                "gpt-4o" to "GPT-4o (Omni Multimodal & Vision)",
                                "chatgpt-4o-latest" to "ChatGPT-4o Latest",
                                "o1" to "OpenAI o1 (Advanced Reasoning)",
                                "o3-mini" to "OpenAI o3-mini (High-Speed Reasoning)",
                                "gpt-4o-mini" to "GPT-4o Mini",
                                "gpt-4-turbo" to "GPT-4 Turbo"
                            )
                        } else emptyList()
                        if (codexFallback.isNotEmpty()) {
                            currentModels = codexFallback.map { (id, name) ->
                                val caps = catalog.inferCapabilities(providerId, id)
                                com.tom.rv2ide.artificial.providers.DiscoveredModel(
                                    providerId = providerId,
                                    id = id,
                                    displayName = name,
                                    capabilities = caps,
                                    discoveredAtMs = System.currentTimeMillis()
                                )
                            }
                            statusHeader.text = "✅ Loaded ${currentModels.size} models from ChatGPT Codex"
                            refreshList(searchInput.text?.toString().orEmpty())
                        } else {
                            statusHeader.text = "⚠️ ${res.errorMessage ?: "Endpoint returned 0 models. Use manual Model ID."}"
                        }
                    }
                }
            }
        }

        useManualBtn.setOnClickListener {
            val typed = searchInput.text?.toString()?.trim().orEmpty()
            if (typed.isEmpty()) {
                Toast.makeText(context, "Type a Model ID in the search box first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            registry.setManualModelOverride(providerId, typed)
            onModelSelected(typed)
            dialog.dismiss()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val chosenId = if (displayedModels.isNotEmpty() && position < displayedModels.size) {
                displayedModels[position].id
            } else {
                val itemText = listView.adapter.getItem(position)?.toString().orEmpty()
                itemText.substringBefore("  [").trim()
            }
            if (chosenId.isNotEmpty()) {
                registry.setManualModelOverride(providerId, "")
                registry.selectModelForProvider(providerId, chosenId)
                onModelSelected(chosenId)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    fun showWebSearchConfigDialog(
        context: Context,
        scope: CoroutineScope,
        onConfigSaved: (WebSearchConfig) -> Unit
    ) {
        val searchManager = WebSearchManager(context)
        val currentConfig = searchManager.getConfig()
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val scroll = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }
        scroll.addView(container)

        fun addLabel(text: String): TextView {
            return TextView(context).apply {
                this.text = text
                textSize = 12f
                setPadding(0, pad / 2, 0, 4)
                container.addView(this)
            }
        }

        val enabledCheck = CheckBox(context).apply {
            text = "Enable Live Web Search & URL Fetching Tools"
            isChecked = currentConfig.enabled
            container.addView(this)
        }

        addLabel("Web Search Provider")
        val providerTypes = SearchProviderType.entries
        val providerSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                providerTypes.map { it.displayName }
            )
            setSelection(providerTypes.indexOf(currentConfig.providerType).coerceAtLeast(0))
            container.addView(this)
        }

        val existingSearchKey = searchManager.getSearchApiKey(currentConfig.providerType).orEmpty()
        val keyHint = if (existingSearchKey.isNotBlank()) {
            "Saved: ${SecretRedactor.maskKey(existingSearchKey)} (leave blank to keep)"
        } else {
            "Enter Search API Key (separate from LLM API key)"
        }
        addLabel("Search API Key ($keyHint)")
        val searchKeyInput = EditText(context).apply {
            hint = keyHint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            container.addView(this)
        }

        addLabel("Custom Search Endpoint URL (required for Custom JSON, optional override for Tavily/Brave)")
        val customUrlInput = EditText(context).apply {
            setText(currentConfig.customEndpointUrl)
            hint = "https://search.example.com/search"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            container.addView(this)
        }

        val cleartextSearchCheck = CheckBox(context).apply {
            text = "Allow Cleartext HTTP for Custom Search Endpoint (LAN only)"
            isChecked = currentConfig.allowCleartextCustomEndpoint
            container.addView(this)
        }

        addLabel("Max Search Results (1 - 10)")
        val limitInput = EditText(context).apply {
            setText(currentConfig.resultLimit.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            container.addView(this)
        }

        addLabel("Search / URL Fetch Timeout (seconds, 5 - 60)")
        val timeoutInput = EditText(context).apply {
            setText(currentConfig.timeoutSec.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            container.addView(this)
        }

        val statusView = TextView(context).apply {
            text = "Status: ${searchManager.getSearchStatus(currentConfig).label}"
            textSize = 12f
            setPadding(0, pad / 2, 0, pad / 2)
            container.addView(this)
        }

        fun buildDraftConfig(): Pair<WebSearchConfig, String?> {
            val selectedType = providerTypes[providerSpinner.selectedItemPosition]
            val enteredKey = searchKeyInput.text?.toString()?.trim().orEmpty()
            val effectiveKey = if (enteredKey.isNotEmpty()) {
                enteredKey
            } else {
                searchManager.getSearchApiKey(selectedType)
            }
            val cfg = WebSearchConfig(
                enabled = enabledCheck.isChecked,
                providerType = selectedType,
                customEndpointUrl = customUrlInput.text?.toString()?.trim().orEmpty(),
                allowCleartextCustomEndpoint = cleartextSearchCheck.isChecked,
                resultLimit = limitInput.text?.toString()?.toIntOrNull()?.coerceIn(1, 10) ?: 5,
                timeoutSec = timeoutInput.text?.toString()?.toIntOrNull()?.coerceIn(5, 60) ?: 15
            )
            return Pair(cfg, effectiveKey)
        }

        MaterialButton(context).apply {
            text = "Test Web Search Connection"
            setOnClickListener {
                val (draftCfg, effectiveKey) = buildDraftConfig()
                isEnabled = false
                statusView.text = "Testing ${draftCfg.providerType.displayName}..."
                scope.launch {
                    val res = searchManager.testSearchConnection(draftCfg, effectiveKey)
                    withContext(Dispatchers.Main) {
                        isEnabled = true
                        statusView.text = if (res.success) "✅ ${res.message}" else "❌ ${res.message}"
                    }
                }
            }
            container.addView(this)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Web Search & Grounding Tools")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val (newCfg, _) = buildDraftConfig()
                val enteredKey = searchKeyInput.text?.toString()?.trim().orEmpty()
                if (enteredKey.isNotEmpty()) {
                    searchManager.setSearchApiKey(newCfg.providerType, enteredKey)
                }
                searchManager.saveConfig(newCfg)
                onConfigSaved(newCfg)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

class DiscoveredModelAdapter(
    private val context: android.content.Context,
    private var items: List<com.tom.rv2ide.artificial.providers.DiscoveredModel>,
    private val catalog: com.tom.rv2ide.artificial.models.ModelCatalogManager,
    private val onItemClick: (com.tom.rv2ide.artificial.providers.DiscoveredModel) -> Unit
) : android.widget.BaseAdapter() {
    override fun getCount(): Int = items.size
    override fun getItem(position: Int): com.tom.rv2ide.artificial.providers.DiscoveredModel = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    fun updateItems(newItems: List<com.tom.rv2ide.artificial.providers.DiscoveredModel>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
        val view = convertView ?: android.view.LayoutInflater.from(context).inflate(com.tom.rv2ide.R.layout.item_discovered_model, parent, false)
        val m = getItem(position)

        val tvName = view.findViewById<android.widget.TextView>(com.tom.rv2ide.R.id.tvModelName)
        val tvFamily = view.findViewById<android.widget.TextView>(com.tom.rv2ide.R.id.tvModelFamily)
        val tvDesc = view.findViewById<android.widget.TextView>(com.tom.rv2ide.R.id.tvModelDescription)
        val badgeVision = view.findViewById<android.widget.TextView>(com.tom.rv2ide.R.id.badgeVision)
        val badgeTools = view.findViewById<android.widget.TextView>(com.tom.rv2ide.R.id.badgeTools)
        val badgeThinking = view.findViewById<android.widget.TextView>(com.tom.rv2ide.R.id.badgeThinking)
        val badgeContext = view.findViewById<android.widget.TextView>(com.tom.rv2ide.R.id.badgeContext)

        val displayName = if (m.displayName.isNotBlank() && m.displayName != m.id) {
            "${m.displayName} (${m.id})"
        } else {
            m.id
        }
        tvName.text = displayName

        val family = m.ownedBy ?: m.id.substringBefore("/", "").takeIf { it.isNotBlank() }
        if (!family.isNullOrBlank()) {
            tvFamily.text = family
            tvFamily.visibility = android.view.View.VISIBLE
        } else {
            tvFamily.visibility = android.view.View.GONE
        }

        if (!m.description.isNullOrBlank()) {
            tvDesc.text = m.description
            tvDesc.visibility = android.view.View.VISIBLE
        } else {
            tvDesc.visibility = android.view.View.GONE
        }

        val caps = m.capabilities
        val isVision = caps.supportsVision == com.tom.rv2ide.artificial.providers.TriStateCapability.SUPPORTED || catalog.isVisionSupported(m.id)
        val isTools = caps.supportsToolCalling == com.tom.rv2ide.artificial.providers.TriStateCapability.SUPPORTED || catalog.isToolCallingSupported(m.id)
        val isThinking = caps.supportsThinking == com.tom.rv2ide.artificial.providers.TriStateCapability.SUPPORTED || catalog.isReasoningSupported(m.id)
        val ctxLen = caps.contextLength ?: catalog.getCapabilities(m.id).contextLength

        badgeVision.visibility = if (isVision) android.view.View.VISIBLE else android.view.View.GONE
        badgeTools.visibility = if (isTools) android.view.View.VISIBLE else android.view.View.GONE
        badgeThinking.visibility = if (isThinking) android.view.View.VISIBLE else android.view.View.GONE

        if (ctxLen != null && ctxLen > 0) {
            val k = if (ctxLen >= 1000000) "${ctxLen / 1000000}M" else "${ctxLen / 1000}k"
            badgeContext.text = "⚡ $k"
            badgeContext.visibility = android.view.View.VISIBLE
        } else {
            badgeContext.visibility = android.view.View.GONE
        }

        view.setOnClickListener {
            onItemClick(m)
        }
        return view
    }
}

}
