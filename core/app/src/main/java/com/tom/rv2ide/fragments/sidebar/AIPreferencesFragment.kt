package com.tom.rv2ide.fragments.sidebar

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.TextView
import com.tom.rv2ide.artificial.policy.ActionPolicyGate
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.dialogs.ProviderProfileDialogs
import com.tom.rv2ide.artificial.dialogs.ProviderSwitchDialog
import com.tom.rv2ide.artificial.providers.ApiProtocol
import com.tom.rv2ide.artificial.providers.ProviderProfile
import com.tom.rv2ide.artificial.providers.ProviderRegistryManager
import com.tom.rv2ide.artificial.providers.ProviderConnectionStatus
import com.tom.rv2ide.artificial.providers.TriStateCapability
import com.tom.rv2ide.artificial.providers.ModelCapabilities
import com.tom.rv2ide.artificial.tools.WebSearchManager
import com.tom.rv2ide.managers.CodeCompletionManager
import com.google.android.material.tabs.TabLayout
import com.tom.rv2ide.artificial.auth.OAuthAccountRegistry
import com.tom.rv2ide.artificial.auth.OAuthAccount
import com.tom.rv2ide.artificial.auth.ProviderOAuthManager
import com.tom.rv2ide.artificial.mcp.McpClientManager
import com.tom.rv2ide.artificial.mcp.McpServerConfig
import com.tom.rv2ide.artificial.mcp.McpTransportType
import com.tom.rv2ide.artificial.skills.SkillManager
import com.tom.rv2ide.artificial.skills.SkillRecord
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import android.widget.LinearLayout
import android.view.Gravity
import android.widget.EditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AIPreferencesFragment(
    private var aiAgentParam: AIAgentManager? = null,
    private var agentsParam: Agents? = null,
    private var codeCompletionManagerParam: CodeCompletionManager? = null
) : Fragment() {

    constructor() : this(null, null, null)

    private val aiAgent: AIAgentManager by lazy {
        aiAgentParam ?: AIAgentManager(requireContext())
    }
    private val agents: Agents by lazy {
        agentsParam ?: Agents(requireContext())
    }
    private val codeCompletionManager: CodeCompletionManager?
        get() = codeCompletionManagerParam

    private lateinit var providerDropdown: AutoCompleteTextView
    private lateinit var modelDropdown: AutoCompleteTextView
    private lateinit var editProviderBtn: MaterialButton
    private lateinit var addProviderBtn: MaterialButton
    private lateinit var testProviderBtn: MaterialButton
    private lateinit var fetchModelsBtn: MaterialButton
    private lateinit var searchModelsBtn: MaterialButton
    private lateinit var modelDiscoveryStatusText: MaterialTextView
    private lateinit var webSearchStatusText: MaterialTextView
    private lateinit var configureWebSearchBtn: MaterialButton
    private lateinit var autoSwitchToggle: MaterialSwitch
    private lateinit var codeCompletionToggle: MaterialSwitch
    private lateinit var currentProviderText: MaterialTextView
    private lateinit var currentModelText: MaterialTextView

    private lateinit var preferencesTabLayout: TabLayout
    private lateinit var containerApiProviders: LinearLayout
    private lateinit var containerAccountLogin: LinearLayout
    private lateinit var containerMcp: LinearLayout
    private lateinit var containerSkills: LinearLayout
    private lateinit var accountCardsContainer: LinearLayout
    private lateinit var mcpServersListContainer: LinearLayout
    private lateinit var skillsListContainer: LinearLayout
    private lateinit var btnAddMcpServer: MaterialButton
    private lateinit var btnAddSkill: MaterialButton

    private val providerSwitchDialog by lazy { ProviderSwitchDialog(requireContext()) }
    private val registryManager by lazy { ProviderRegistryManager.getInstance(requireContext()) }
    private val webSearchManager by lazy { WebSearchManager(requireContext()) }

    private var currentProfilesList: List<ProviderProfile> = emptyList()
    private var completionStateMonitorJob: Job? = null
    private var isCompletionEnabled = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_preferences, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViews(view)
        setupProviderDropdown()
        setupProviderActionButtons()
        setupModelDropdown()
        setupModelActionButtons()
        setupWebSearchSection()
        setupToggles()
        updateCurrentStatus()
        startCompletionStateMonitoring()
    }

    override fun onResume() {
        super.onResume()
        setupProviderDropdown()
        updateModelDropdown()
        updateCurrentStatus()
        updateWebSearchStatusDisplay()
        syncCodeCompletionToggle()
    }

    override fun onPause() {
        super.onPause()
        stopCompletionStateMonitoring()
    }

    private fun initializeViews(view: View) {
        providerDropdown = view.findViewById(R.id.providerDropdown)
        modelDropdown = view.findViewById(R.id.modelDropdown)
        editProviderBtn = view.findViewById(R.id.editProviderBtn)
        addProviderBtn = view.findViewById(R.id.addProviderBtn)
        testProviderBtn = view.findViewById(R.id.testProviderBtn)
        fetchModelsBtn = view.findViewById(R.id.fetchModelsBtn)
        searchModelsBtn = view.findViewById(R.id.searchModelsBtn)
        modelDiscoveryStatusText = view.findViewById(R.id.modelDiscoveryStatusText)
        webSearchStatusText = view.findViewById(R.id.webSearchStatusText)

        preferencesTabLayout = view.findViewById(R.id.preferencesTabLayout)
        containerApiProviders = view.findViewById(R.id.containerApiProviders)
        containerAccountLogin = view.findViewById(R.id.containerAccountLogin)
        containerMcp = view.findViewById(R.id.containerMcp)
        containerSkills = view.findViewById(R.id.containerSkills)
        accountCardsContainer = view.findViewById(R.id.accountCardsContainer)
        mcpServersListContainer = view.findViewById(R.id.mcpServersListContainer)
        skillsListContainer = view.findViewById(R.id.skillsListContainer)
        btnAddMcpServer = view.findViewById(R.id.btnAddMcpServer)
        btnAddSkill = view.findViewById(R.id.btnAddSkill)

        setupPreferencesTabs()
        setupMcpAndSkillsButtons()
        configureWebSearchBtn = view.findViewById(R.id.configureWebSearchBtn)
        autoSwitchToggle = view.findViewById(R.id.autoSwitchToggle)
        codeCompletionToggle = view.findViewById(R.id.codeCompletionToggle)
        currentProviderText = view.findViewById(R.id.currentProviderText)
        currentModelText = view.findViewById(R.id.currentModelText)

        view.findViewById<MaterialButton>(R.id.btnBackToAgent)?.setOnClickListener {
            val artFrag = (parentFragment as? ArtificialFragment)
                ?: (parentFragmentManager.fragments.firstOrNull { it is ArtificialFragment } as? ArtificialFragment)
            if (artFrag != null) {
                artFrag.showMainContent()
            } else if (parentFragmentManager.backStackEntryCount > 0) {
                parentFragmentManager.popBackStack()
            } else {
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
        }
    }

    private fun formatProviderLabel(profile: ProviderProfile): String {
        val state = registryManager.getConnectionState(profile)
        val badge = when (state) {
            ProviderConnectionStatus.VERIFIED -> "✓ Verified (${profile.lastConnectionTestLatencyMs}ms)"
            ProviderConnectionStatus.CONFIGURED_UNTESTED -> "⚠ Untested"
            ProviderConnectionStatus.TEST_FAILED -> "❌ Failed"
            ProviderConnectionStatus.KEY_REQUIRED -> "Key needed"
        }
        return "${profile.displayName} [$badge]"
    }

    private fun setupProviderDropdown() {
        currentProfilesList = registryManager.getAllProfiles()
        val labels = currentProfilesList.map { formatProviderLabel(it) }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        providerDropdown.setAdapter(adapter)

        updateProviderDropdownSelection()

        providerDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position in currentProfilesList.indices) {
                val chosen = currentProfilesList[position]
                handleProviderChange(chosen.id, chosen.displayName)
            }
        }
    }

    private fun setupProviderActionButtons() {
// Accounts now accessed via Accounts Tab
        editProviderBtn.setOnClickListener {
            val currentId = agents.getProvider()
            val profile = registryManager.getProfile(currentId) ?: currentProfilesList.firstOrNull() ?: return@setOnClickListener
            ProviderProfileDialogs.showEditProfileDialog(
                context = requireContext(),
                scope = viewLifecycleOwner.lifecycleScope,
                profile = profile,
                isNew = false,
                onProfileSaved = { updated ->
                    aiAgent.refreshRegisteredProfiles()
                    setupProviderDropdown()
                    handleProviderChange(updated.id, updated.displayName)
                },
                onProfileDeleted = {
                    aiAgent.refreshRegisteredProfiles()
                    val fallback = registryManager.getAllProfiles().firstOrNull()
                    if (fallback != null) {
                        handleProviderChange(fallback.id, fallback.displayName)
                    }
                    setupProviderDropdown()
                }
            )
        }

        addProviderBtn.setOnClickListener {
            ProviderProfileDialogs.showAddFromTemplateDialog(
                context = requireContext(),
                scope = viewLifecycleOwner.lifecycleScope
            ) { created ->
                aiAgent.refreshRegisteredProfiles()
                setupProviderDropdown()
                handleProviderChange(created.id, created.displayName)
            }
        }

        testProviderBtn.setOnClickListener {
            val currentId = agents.getProvider()
            val profile = registryManager.getProfile(currentId) ?: return@setOnClickListener
            testProviderBtn.isEnabled = false
            modelDiscoveryStatusText.text = "Testing connection to ${profile.displayName}..."
            viewLifecycleOwner.lifecycleScope.launch {
                val res = registryManager.testProviderConnection(currentId)
                testProviderBtn.isEnabled = true
                if (res.success) {
                    updateModelDropdown()
                }
                setupProviderDropdown()
                updateCurrentStatus()
                modelDiscoveryStatusText.text = if (res.success) {
                    "✅ ${res.message}"
                } else {
                    "❌ ${res.message}"
                }
                showSnackbar(if (res.success) "Connection OK (${res.latencyMs}ms)" else "Connection test failed")
            }
        }
    }

    private fun setupModelActionButtons() {
        fetchModelsBtn.setOnClickListener {
            val currentId = agents.getProvider()
            val profile = registryManager.getProfile(currentId) ?: return@setOnClickListener
            fetchModelsBtn.isEnabled = false
            modelDiscoveryStatusText.text = "Fetching live models from ${profile.displayName}..."
            viewLifecycleOwner.lifecycleScope.launch {
                val result = registryManager.fetchAndCacheModels(currentId)
                fetchModelsBtn.isEnabled = true
                if (result.models.isNotEmpty()) {
                    updateModelDropdown()
                    updateCurrentStatus()
                    modelDiscoveryStatusText.text = "✅ Discovered ${result.models.size} live models from ${profile.displayName}"
                    showSnackbar("Fetched ${result.models.size} models")
                } else {
                    val err = result.errorMessage ?: "No models returned by endpoint"
                    modelDiscoveryStatusText.text = "⚠️ $err"
                    showSnackbar("Model fetch failed — you can still enter a manual Model ID")
                }
            }
        }

        searchModelsBtn.setOnClickListener {
            val currentId = agents.getProvider()
            ProviderProfileDialogs.showSearchableModelPickerDialog(
                context = requireContext(),
                scope = viewLifecycleOwner.lifecycleScope,
                providerId = currentId
            ) { selectedModelId ->
                handleModelChange(selectedModelId)
                updateModelDropdown()
            }
        }
    }

    private fun setupWebSearchSection() {
        updateWebSearchStatusDisplay()
        configureWebSearchBtn.setOnClickListener {
            ProviderProfileDialogs.showWebSearchConfigDialog(
                context = requireContext(),
                scope = viewLifecycleOwner.lifecycleScope
            ) {
                updateWebSearchStatusDisplay()
                showSnackbar("Web search settings updated")
            }
        }
    }

    private fun updateWebSearchStatusDisplay() {
        val currentId = agents.getProvider()
        val profile = registryManager.getProfile(currentId)
        val geminiGrounding = profile?.protocol == ApiProtocol.GEMINI_NATIVE && profile.enableGeminiNativeGrounding
        val cfg = webSearchManager.getConfig()
        val status = webSearchManager.getSearchStatus(cfg, geminiGrounding)
        val extraInfo = if (cfg.enabled) {
            if (geminiGrounding) " • Gemini Native Grounding" else " • ${cfg.providerType.displayName} (max ${cfg.resultLimit})"
        } else {
            ""
        }
        webSearchStatusText.text = "${status.label}$extraInfo"
    }

    private fun updateProviderDropdownSelection() {
        val currentProviderId = agents.getProvider()
        val index = currentProfilesList.indexOfFirst { it.id == currentProviderId }
        if (index >= 0) {
            val p = currentProfilesList[index]
            providerDropdown.setText(formatProviderLabel(p), false)
        } else {
            val profile = registryManager.getProfile(currentProviderId)
            providerDropdown.setText(profile?.let { formatProviderLabel(it) } ?: currentProviderId, false)
        }
    }

    private fun updateCurrentStatus() {
        val currentProvider = agents.getProvider()
        val currentModel = agents.getAgent()
        val profile = registryManager.getProfile(currentProvider)
        val providerDisplayName = profile?.displayName ?: currentProvider
        val cachedCount = registryManager.getCachedModels(currentProvider).size

        currentProviderText.text = providerDisplayName
        currentModelText.text = currentModel

        val connState = if (profile != null) registryManager.getConnectionState(profile) else ProviderConnectionStatus.CONFIGURED_UNTESTED
        val connBadge = when (connState) {
            ProviderConnectionStatus.VERIFIED -> "✅ Verified (${profile?.lastConnectionTestLatencyMs}ms)"
            ProviderConnectionStatus.CONFIGURED_UNTESTED -> "⚠ Configured (Untested)"
            ProviderConnectionStatus.TEST_FAILED -> "❌ Connection Failed"
            ProviderConnectionStatus.KEY_REQUIRED -> "🔑 API Key Required"
        }

        val effectiveCaps = if (profile != null) registryManager.getEffectiveModelCapabilities(currentProvider, currentModel) else ModelCapabilities()
        val toolsLabel = when (effectiveCaps.supportsToolCalling) {
            TriStateCapability.SUPPORTED -> "Tools: ✓"
            TriStateCapability.UNSUPPORTED -> "Tools: ✗"
            TriStateCapability.UNKNOWN -> "Tools: ?"
        }
        val streamLabel = if (profile?.streamingEnabled == true) "Stream: ✓" else "Stream: ✗"
        val groundingLabel = if (profile?.protocol == ApiProtocol.GEMINI_NATIVE && profile.enableGeminiNativeGrounding) "Grounding: ✓" else "Grounding: Off"

        modelDiscoveryStatusText.text = buildString {
            append("$connBadge • $toolsLabel • $streamLabel • $groundingLabel")
            if (cachedCount > 0) {
                append(" • $cachedCount models cached")
            } else {
                append(" • Preset list")
            }
        }
    }

    private fun setupModelDropdown() {
        updateModelDropdown()

        modelDropdown.setOnItemClickListener { _, _, position, _ ->
            val currentProvider = agents.getProvider()
            val models = agents.getModelsForProvider(currentProvider)

            if (position < models.size) {
                val selectedModel = models[position]
                registryManager.setManualModelOverride(currentProvider, "")
                handleModelChange(selectedModel)
            }
        }
    }

    private fun updateModelDropdown() {
        val currentProvider = agents.getProvider()
        val models = agents.getModelsForProvider(currentProvider)

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, models.toList())
        modelDropdown.setAdapter(adapter)

        val currentModel = agents.getAgent()
        if (currentModel.isNotBlank()) {
            modelDropdown.setText(currentModel, false)
        } else if (models.isNotEmpty()) {
            modelDropdown.setText(models[0], false)
        }
    }

    private fun setupToggles() {
        autoSwitchToggle.isChecked = providerSwitchDialog.isAutoSwitchEnabled()
        autoSwitchToggle.setOnCheckedChangeListener { _, isChecked ->
            providerSwitchDialog.setAutoSwitch(isChecked)
            showSnackbar(if (isChecked) "Auto-switch enabled" else "Auto-switch disabled")
        }

        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)
        isCompletionEnabled = savedState
        codeCompletionToggle.isChecked = savedState

        codeCompletionToggle.setOnCheckedChangeListener { _, isChecked ->
            isCompletionEnabled = isChecked
            requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("code_completion_enabled", isChecked)
                .apply()

            lifecycleScope.launch {
                applyCompletionStateChange(isChecked)
            }

            showSnackbar(if (isChecked) "✅ Code completion enabled" else "❌ Code completion disabled")
        }
    }

    private fun startCompletionStateMonitoring() {
        stopCompletionStateMonitoring()

        completionStateMonitorJob = lifecycleScope.launch {
            while (true) {
                delay(100)
                val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                    .getBoolean("code_completion_enabled", true)

                if (savedState != isCompletionEnabled) {
                    isCompletionEnabled = savedState
                    if (codeCompletionToggle.isChecked != savedState) {
                        codeCompletionToggle.isChecked = savedState
                    }
                    applyCompletionStateChange(savedState)
                }
            }
        }
    }

    private fun stopCompletionStateMonitoring() {
        completionStateMonitorJob?.cancel()
        completionStateMonitorJob = null
    }

    private suspend fun applyCompletionStateChange(enabled: Boolean) {
        if (enabled) {
            codeCompletionManager?.reattachToCurrentEditor()
        } else {
            codeCompletionManager?.cleanup()
        }
    }

    private fun syncCodeCompletionToggle() {
        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)
        isCompletionEnabled = savedState
        codeCompletionToggle.isChecked = savedState
    }

    private fun handleProviderChange(providerId: String, providerName: String) {
        agents.setProvider(providerId)
        val modelForProv = agents.getAgentForProvider(providerId)
        if (modelForProv.isNotBlank()) {
            agents.setAgent(modelForProv)
        }

        updateProviderDropdownSelection()
        updateModelDropdown()
        updateWebSearchStatusDisplay()

        if (aiAgent.setProvider(providerId)) {
            aiAgent.reinitializeWithSelectedModel()
            updateCurrentStatus()

            lifecycleScope.launch {
                if (isCompletionEnabled) {
                    delay(500)
                    codeCompletionManager?.reattachToCurrentEditor()
                }
            }

            showSnackbar("Switched to $providerName")
        } else {
            updateCurrentStatus()
            showSnackbar("⚠️ Configure API key for $providerName (tap 'Configure / Key')")
        }
    }

    private fun handleModelChange(modelName: String) {
        agents.setAgent(modelName)
        aiAgent.reinitializeWithSelectedModel()
        updateCurrentStatus()

        lifecycleScope.launch {
            if (isCompletionEnabled) {
                delay(500)
                codeCompletionManager?.reattachToCurrentEditor()
            }
        }

        showSnackbar("Model switched to: $modelName")
    }

    private fun showSnackbar(message: String) {
        view?.let {
            Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun resolveThemeColor(ctx: Context, attr: Int, defaultColor: Int): Int {
        val typedValue = android.util.TypedValue()
        return if (ctx.theme.resolveAttribute(attr, typedValue, true)) {
            typedValue.data
        } else {
            defaultColor
        }
    }

    private fun setupPreferencesTabs() {
        preferencesTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        containerApiProviders.visibility = View.VISIBLE
                        containerAccountLogin.visibility = View.GONE
                        containerMcp.visibility = View.GONE
                        containerSkills.visibility = View.GONE
                    }
                    1 -> {
                        containerApiProviders.visibility = View.GONE
                        containerAccountLogin.visibility = View.VISIBLE
                        containerMcp.visibility = View.GONE
                        containerSkills.visibility = View.GONE
                        renderAccountCards()
                    }
                    2 -> {
                        containerApiProviders.visibility = View.GONE
                        containerAccountLogin.visibility = View.GONE
                        containerMcp.visibility = View.VISIBLE
                        containerSkills.visibility = View.GONE
                        renderMcpServers()
                    }
                    3 -> {
                        containerApiProviders.visibility = View.GONE
                        containerAccountLogin.visibility = View.GONE
                        containerMcp.visibility = View.GONE
                        containerSkills.visibility = View.VISIBLE
                        renderSkills()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupMcpAndSkillsButtons() {
        btnAddMcpServer.setOnClickListener {
            showAddMcpServerDialog()
        }
        btnAddSkill.setOnClickListener {
            showAddSkillDialog()
        }
    }

    private fun renderAccountCards() {
        val ctx = context ?: return
        accountCardsContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val registry = OAuthAccountRegistry.getInstance(ctx)

        data class ProviderMeta(val id: String, val name: String, val icon: String, val subtitle: String)
        val providers = listOf(
            ProviderMeta("openai", "ChatGPT", "🤖", "Codex Stream • Multiple Accounts"),
            ProviderMeta("claude", "Claude", "🧠", "Anthropic Messages • PKCE"),
            ProviderMeta("grok", "Grok", "⚡", "xAI Responses • Device Flow"),
            ProviderMeta("kimi", "Kimi Code", "🌙", "Moonshot Kimi • Coding API"),
            ProviderMeta("gemini", "Google Gemini", "🌐", "Google AI Studio • Live Connect")
        )

        for (p in providers) {
            val accounts = registry.getAccounts(p.id)

            val card = MaterialCardView(ctx).apply {
                radius = 14 * density
                strokeWidth = (1 * density).toInt()
                strokeColor = resolveThemeColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.DKGRAY)
                setCardBackgroundColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorSurfaceContainer, android.graphics.Color.parseColor("#1E1E2E")))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (12 * density).toInt()
                }
            }

            val cardContent = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
            }

            // Header row
            val headerRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            val iconTv = TextView(ctx).apply {
                text = p.icon
                textSize = 22f
                setPadding(0, 0, (10 * density).toInt(), 0)
            }
            headerRow.addView(iconTv)

            val titleCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleTv = TextView(ctx).apply {
                text = p.name
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.WHITE))
            }
            val subTv = TextView(ctx).apply {
                text = p.subtitle
                textSize = 11.5f
                setTextColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.LTGRAY))
            }
            titleCol.addView(titleTv)
            titleCol.addView(subTv)
            headerRow.addView(titleCol)

            val addBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "+ Add"
                textSize = 11.5f
                insetTop = 0
                insetBottom = 0
                setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                setOnClickListener {
                    ProviderOAuthManager.showAccountLoginDialog(ctx, lifecycleScope, p.id) { connected ->
                        aiAgent.refreshRegisteredProfiles()
                        renderAccountCards()
                        showSnackbar("Account connected: $connected")
                    }
                }
            }
            headerRow.addView(addBtn)
            cardContent.addView(headerRow)

            // Accounts list inside card
            if (accounts.isEmpty()) {
                val emptyTv = TextView(ctx).apply {
                    text = "No accounts connected yet. Tap '+ Add' to sign in."
                    textSize = 12f
                    setTextColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.LTGRAY))
                    setPadding(0, (10 * density).toInt(), 0, (4 * density).toInt())
                }
                cardContent.addView(emptyTv)
            } else {
                for (acc in accounts) {
                    val accRow = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
                    }

                    val infoCol = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val accEmailTv = TextView(ctx).apply {
                        text = (acc.email.ifBlank { "Account ID: ${acc.id.take(8)}" }) + if (acc.plan.isNotBlank()) " [${acc.plan.uppercase()}]" else ""
                        textSize = 13f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.WHITE))
                    }

                    val statusTv = TextView(ctx).apply {
                        text = when {
                            acc.isQuotaExhausted -> "⚠️ Quota Exhausted (Auto-Fallback Ready)"
                            acc.isActive -> "🟢 Active Account"
                            else -> "⚪ Standby Account"
                        }
                        textSize = 11f
                        setTextColor(if (acc.isQuotaExhausted) android.graphics.Color.parseColor("#EF4444") else if (acc.isActive) android.graphics.Color.parseColor("#10B981") else android.graphics.Color.GRAY)
                    }

                    infoCol.addView(accEmailTv)
                    infoCol.addView(statusTv)
                    accRow.addView(infoCol)

                    val btnRow = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    val modelsBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "Models"
                        textSize = 11f
                        insetTop = 0
                        insetBottom = 0
                        setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                        setOnClickListener {
                            ProviderProfileDialogs.showSearchableModelPickerDialog(
                                context = ctx,
                                scope = viewLifecycleOwner.lifecycleScope,
                                providerId = p.id
                            ) { selectedModel ->
                                agents.setProvider(p.id)
                                agents.setAgent(selectedModel)
                                aiAgent.setProvider(p.id)
                                aiAgent.reinitializeWithSelectedModel()
                                showSnackbar("Selected model: $selectedModel on ${p.name}")
                            }
                        }
                    }
                    btnRow.addView(modelsBtn)

                    if (!acc.isActive) {
                        val makeActiveBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                            text = "Use"
                            textSize = 11f
                            insetTop = 0
                            insetBottom = 0
                            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                            setOnClickListener {
                                registry.setActiveAccount(p.id, acc.id)
                                agents.setProvider(p.id)
                                aiAgent.setProvider(p.id)
                                aiAgent.reinitializeWithSelectedModel()
                                renderAccountCards()
                                showSnackbar("Active account set to ${acc.email.ifBlank { acc.id.take(8) }}")
                            }
                        }
                        btnRow.addView(makeActiveBtn)
                    }

                    val removeBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "✕"
                        textSize = 11f
                        insetTop = 0
                        insetBottom = 0
                        setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
                        setOnClickListener {
                            MaterialAlertDialogBuilder(ctx)
                                .setTitle("Log Out / Remove Account")
                                .setMessage("Are you sure you want to sign out and remove ${acc.email.ifBlank { acc.id.take(8) }} from ${p.name}?")
                                .setPositiveButton("Log Out") { _, _ ->
                                    registry.removeAccount(acc.id)
                                    renderAccountCards()
                                    showSnackbar("Removed account from ${p.name}")
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                    btnRow.addView(removeBtn)
                    accRow.addView(btnRow)

                    cardContent.addView(accRow)
                }

                val bottomActionRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, (8 * density).toInt(), 0, 0)
                }

                val selectModelsBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                    text = "🔍 Select ${p.name} Models"
                    textSize = 11.5f
                    setOnClickListener {
                        ProviderProfileDialogs.showSearchableModelPickerDialog(
                            context = ctx,
                            scope = viewLifecycleOwner.lifecycleScope,
                            providerId = p.id
                        ) { selectedModel ->
                            agents.setProvider(p.id)
                            agents.setAgent(selectedModel)
                            aiAgent.setProvider(p.id)
                            aiAgent.reinitializeWithSelectedModel()
                            showSnackbar("Selected model: $selectedModel on ${p.name}")
                        }
                    }
                }
                bottomActionRow.addView(selectModelsBtn)
                cardContent.addView(bottomActionRow)
            }

            card.addView(cardContent)
            accountCardsContainer.addView(card)
        }
    }

    private fun renderMcpServers() {
        val ctx = context ?: return
        mcpServersListContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val mcpManager = McpClientManager.getInstance(ctx)
        val servers = mcpManager.getServers()

        // Section 1: Configured Servers
        val sec1Header = TextView(ctx).apply {
            text = "⚡ Configured MCP Tool Servers"
            textSize = 13.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(resolveThemeColor(ctx, android.R.attr.colorPrimary, android.graphics.Color.WHITE))
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
        }
        mcpServersListContainer.addView(sec1Header)

        if (servers.isEmpty()) {
            val emptyTv = TextView(ctx).apply {
                text = "No custom MCP servers configured yet. Install from recommended below or tap '+ Add Server'."
                textSize = 12f
                setTextColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.LTGRAY))
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            mcpServersListContainer.addView(emptyTv)
        } else {
            for (srv in servers) {
                val card = MaterialCardView(ctx).apply {
                    radius = 10 * density
                    strokeWidth = (1 * density).toInt()
                    strokeColor = resolveThemeColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.DKGRAY)
                    setCardBackgroundColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorSurfaceContainer, android.graphics.Color.parseColor("#1E1E2E")))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (8 * density).toInt()
                    }
                }

                val cardContent = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                }

                val infoCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val titleTv = TextView(ctx).apply {
                    text = srv.name
                    textSize = 13.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.WHITE))
                }

                val subTv = TextView(ctx).apply {
                    val detail = if (srv.transportType == McpTransportType.STDIO) "STDIO: ${srv.command}" else "SSE: ${srv.endpointUrl}"
                    text = detail
                    textSize = 11f
                    setTextColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.LTGRAY))
                }

                infoCol.addView(titleTv)
                infoCol.addView(subTv)
                cardContent.addView(infoCol)

                val toggle = com.google.android.material.materialswitch.MaterialSwitch(ctx).apply {
                    isChecked = srv.enabled
                    setOnCheckedChangeListener { _, isChecked ->
                        mcpManager.saveServer(srv.copy(enabled = isChecked))
                        showSnackbar("MCP server ${srv.name} ${if (isChecked) "enabled" else "disabled"}")
                    }
                }
                cardContent.addView(toggle)

                val deleteBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "✕"
                    textSize = 11f
                    insetTop = 0
                    insetBottom = 0
                    setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
                    setOnClickListener {
                        mcpManager.deleteServer(srv.id)
                        renderMcpServers()
                        showSnackbar("Deleted server ${srv.name}")
                    }
                }
                cardContent.addView(deleteBtn)

                card.addView(cardContent)
                mcpServersListContainer.addView(card)
            }
        }

        // Section 2: Recommended Curated MCP Servers (1-Click Install)
        val sec2Header = TextView(ctx).apply {
            text = "⭐ Recommended MCP Tool Servers (1-Click Install)"
            textSize = 13.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(resolveThemeColor(ctx, android.R.attr.colorPrimary, android.graphics.Color.WHITE))
            setPadding(0, (14 * density).toInt(), 0, (8 * density).toInt())
        }
        mcpServersListContainer.addView(sec2Header)

        data class RecommendedMcp(
            val id: String,
            val name: String,
            val description: String,
            val transportType: McpTransportType,
            val command: String,
            val endpointUrl: String,
            val icon: String
        )

        val recommended = listOf(
            RecommendedMcp(
                "rec-filesystem",
                "Filesystem MCP",
                "Fast local directory scanning, reading, and line-level diff code edits.",
                McpTransportType.STDIO,
                "mcp-server-filesystem",
                "",
                "📁"
            ),
            RecommendedMcp(
                "rec-android-tools",
                "Android Tools & ADB",
                "Execute ADB shell commands, capture logcat traces, inspect view hierarchy.",
                McpTransportType.STDIO,
                "android-tools-mcp",
                "",
                "🤖"
            ),
            RecommendedMcp(
                "rec-web-fetch",
                "Web Fetch & Search",
                "Crawl web pages, fetch documentation markdown, and perform deep research.",
                McpTransportType.SSE_HTTP,
                "",
                "http://127.0.0.1:3000/sse",
                "🌐"
            ),
            RecommendedMcp(
                "rec-sqlite",
                "SQLite & Room Database",
                "Direct database inspection, live SQL queries, and schema migration debug.",
                McpTransportType.STDIO,
                "sqlite-mcp",
                "",
                "🗄️"
            ),
            RecommendedMcp(
                "rec-memory",
                "Memory & Knowledge Base",
                "Persistent cross-session knowledge graph for architecture and project notes.",
                McpTransportType.STDIO,
                "memory-mcp",
                "",
                "🧠"
            )
        )

        for (rec in recommended) {
            val isInstalled = servers.any { it.name.equals(rec.name, ignoreCase = true) || it.id == rec.id }

            val card = MaterialCardView(ctx).apply {
                radius = 10 * density
                strokeWidth = (1 * density).toInt()
                strokeColor = resolveThemeColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.DKGRAY)
                setCardBackgroundColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorSurfaceContainer, android.graphics.Color.parseColor("#1E1E2E")))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
            }

            val cardContent = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            }

            val iconTv = TextView(ctx).apply {
                text = rec.icon
                textSize = 20f
                setPadding(0, 0, (10 * density).toInt(), 0)
            }
            cardContent.addView(iconTv)

            val infoCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(ctx).apply {
                text = rec.name
                textSize = 13.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.WHITE))
            }

            val descTv = TextView(ctx).apply {
                text = rec.description
                textSize = 11f
                setTextColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.LTGRAY))
            }

            infoCol.addView(titleTv)
            infoCol.addView(descTv)
            cardContent.addView(infoCol)

            if (isInstalled) {
                val installedBadge = TextView(ctx).apply {
                    text = "✓ Installed"
                    textSize = 11.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(android.graphics.Color.parseColor("#10B981"))
                    setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                }
                cardContent.addView(installedBadge)
            } else {
                val installBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                    text = "📥 Install"
                    textSize = 11.5f
                    insetTop = 0
                    insetBottom = 0
                    cornerRadius = (8 * density).toInt()
                    setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                    setOnClickListener {
                        val cfg = McpServerConfig(
                            id = rec.id,
                            name = rec.name,
                            transportType = rec.transportType,
                            command = rec.command,
                            endpointUrl = rec.endpointUrl,
                            enabled = true
                        )
                        mcpManager.saveServer(cfg)
                        renderMcpServers()
                        showSnackbar("✅ Installed MCP server: ${rec.name}")
                    }
                }
                cardContent.addView(installBtn)
            }

            card.addView(cardContent)
            mcpServersListContainer.addView(card)
        }
    }

    private fun showAddMcpServerDialog() {
        val ctx = context ?: return
        val density = resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }

        val nameInput = EditText(ctx).apply {
            hint = "Server Name (e.g. Git Tools)"
            setSingleLine()
        }
        root.addView(nameInput)

        val cmdInput = EditText(ctx).apply {
            hint = "Command or URL (e.g. git-mcp or http://localhost:8080/sse)"
            setSingleLine()
        }
        root.addView(cmdInput)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Add MCP Server")
            .setView(root)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim()
                val target = cmdInput.text.toString().trim()
                if (name.isNotBlank() && target.isNotBlank()) {
                    val isUrl = target.startsWith("http://") || target.startsWith("https://")
                    val cfg = McpServerConfig(
                        name = name,
                        transportType = if (isUrl) McpTransportType.SSE_HTTP else McpTransportType.STDIO,
                        command = if (!isUrl) target else "",
                        endpointUrl = if (isUrl) target else "",
                        enabled = true
                    )
                    McpClientManager.getInstance(ctx).saveServer(cfg)
                    renderMcpServers()
                    showSnackbar("Added MCP server $name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderSkills() {
        val ctx = context ?: return
        skillsListContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val skillManager = SkillManager.getInstance(ctx)
        val skills = skillManager.discoverSkills()

        // Section 1: Active Skills
        val sec1Header = TextView(ctx).apply {
            text = "🛠️ Active Agent Skills"
            textSize = 13.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(resolveThemeColor(ctx, android.R.attr.colorPrimary, android.graphics.Color.WHITE))
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
        }
        skillsListContainer.addView(sec1Header)

        if (skills.isEmpty()) {
            val emptyTv = TextView(ctx).apply {
                text = "No skills discovered yet. Install from recommended below or tap '+ Add Skill'."
                textSize = 12f
                setTextColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.LTGRAY))
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            skillsListContainer.addView(emptyTv)
        } else {
            for (sk in skills) {
                val card = MaterialCardView(ctx).apply {
                    radius = 10 * density
                    strokeWidth = (1 * density).toInt()
                    strokeColor = resolveThemeColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.DKGRAY)
                    setCardBackgroundColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorSurfaceContainer, android.graphics.Color.parseColor("#1E1E2E")))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (8 * density).toInt()
                    }
                }

                val cardContent = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                }

                val infoCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val titleTv = TextView(ctx).apply {
                    text = sk.name
                    textSize = 13.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.WHITE))
                }

                val subTv = TextView(ctx).apply {
                    text = sk.description.ifBlank { "Custom Agent Workflow Skill" }
                    textSize = 11f
                    setTextColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.LTGRAY))
                }

                infoCol.addView(titleTv)
                infoCol.addView(subTv)
                cardContent.addView(infoCol)

                val toggle = com.google.android.material.materialswitch.MaterialSwitch(ctx).apply {
                    isChecked = sk.enabled
                    setOnCheckedChangeListener { _, isChecked ->
                        skillManager.setSkillEnabled(sk.id, isChecked)
                        showSnackbar("Skill ${sk.name} ${if (isChecked) "enabled" else "disabled"}")
                    }
                }
                cardContent.addView(toggle)

                card.addView(cardContent)
                skillsListContainer.addView(card)
            }
        }

        // Section 2: Recommended Curated Agent Skills (1-Click Install)
        val sec2Header = TextView(ctx).apply {
            text = "⭐ Recommended Agent Skills (1-Click Install)"
            textSize = 13.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(resolveThemeColor(ctx, android.R.attr.colorPrimary, android.graphics.Color.WHITE))
            setPadding(0, (14 * density).toInt(), 0, (8 * density).toInt())
        }
        skillsListContainer.addView(sec2Header)

        data class RecommendedSkill(
            val id: String,
            val name: String,
            val description: String,
            val instructions: String,
            val icon: String
        )

        val recommended = listOf(
            RecommendedSkill(
                "android-compose-expert",
                "Android & Compose Expert",
                "Specialist in Jetpack Compose UI, Material 3, Navigation, Coroutines & Flow.",
                "1. Build idiomatic Jetpack Compose UI with Material 3 standards.\n2. Keep composables stateless and hoist events.\n3. Utilize remember and derivedStateOf for 60fps rendering.\n4. Structure ViewModels with StateFlow.",
                "🎨"
            ),
            RecommendedSkill(
                "ndk-cpp-jni-wizard",
                "NDK C++ JNI Wizard",
                "Native C/C++ development, CMakeLists.txt tuning, JNI bridges, and memory safety.",
                "1. Maintain correct JNIEXPORT JNICALL signatures matching package namespaces.\n2. Ensure proper CMake target linking (log, android, EGL, GLESv3).\n3. Safely manage LocalRef and GlobalRef to prevent JNI leaks.\n4. Optimize SIMD (NEON) and multithreading.",
                "⚡"
            ),
            RecommendedSkill(
                "bug-hunter-diagnosis",
                "Bug Hunter & Error Diagnosis",
                "Scans Gradle build logs, logcat crash traces, and runtime exceptions with exact fixes.",
                "1. Isolate the exact exception and root cause stacktrace line.\n2. Diagnose classpath conflicts, manifest issues, or missing permissions.\n3. Implement surgical minimal fixes without introducing side-effects.",
                "🐛"
            ),
            RecommendedSkill(
                "ui-ux-designer-animator",
                "UI/UX Designer & Animator",
                "Modern sleek aesthetic polish, smooth transitions, custom drawables, and responsive design.",
                "1. Enforce smooth rounded rectangle geometry (8-12dp corners) across all buttons.\n2. Choose harmonized color palettes with clear contrast hierarchy.\n3. Ensure fluid touch ripple feedback and zero UI clutter.",
                "✨"
            ),
            RecommendedSkill(
                "clean-architecture-refactor",
                "Clean Architecture & Refactoring",
                "MVVM/MVI architecture, repository patterns, dependency injection, and clean refactoring.",
                "1. Decouple presentation from domain and data storage logic.\n2. Use clean repository interfaces.\n3. Keep business rules pure and easily testable.",
                "🏛️"
            ),
            RecommendedSkill(
                "web-search-research",
                "Web Search & Deep Research",
                "Autonomous web search, documentation fetching, GitHub open source exploration.",
                "1. Search technical documentation for current SDK APIs.\n2. Verify method signatures against official reference.\n3. Summarize key technical insights concisely.",
                "🌐"
            )
        )

        for (rec in recommended) {
            val isInstalled = skills.any { it.name.equals(rec.name, ignoreCase = true) || it.id == rec.id }

            val card = MaterialCardView(ctx).apply {
                radius = 10 * density
                strokeWidth = (1 * density).toInt()
                strokeColor = resolveThemeColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, android.graphics.Color.DKGRAY)
                setCardBackgroundColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorSurfaceContainer, android.graphics.Color.parseColor("#1E1E2E")))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
            }

            val cardContent = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            }

            val iconTv = TextView(ctx).apply {
                text = rec.icon
                textSize = 20f
                setPadding(0, 0, (10 * density).toInt(), 0)
            }
            cardContent.addView(iconTv)

            val infoCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(ctx).apply {
                text = rec.name
                textSize = 13.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.WHITE))
            }

            val descTv = TextView(ctx).apply {
                text = rec.description
                textSize = 11f
                setTextColor(resolveThemeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.LTGRAY))
            }

            infoCol.addView(titleTv)
            infoCol.addView(descTv)
            cardContent.addView(infoCol)

            if (isInstalled) {
                val installedBadge = TextView(ctx).apply {
                    text = "✓ Installed"
                    textSize = 11.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(android.graphics.Color.parseColor("#10B981"))
                    setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                }
                cardContent.addView(installedBadge)
            } else {
                val installBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                    text = "📥 Install"
                    textSize = 11.5f
                    insetTop = 0
                    insetBottom = 0
                    cornerRadius = (8 * density).toInt()
                    setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                    setOnClickListener {
                        val ok = skillManager.installPresetSkill(
                            id = rec.id,
                            name = rec.name,
                            description = rec.description,
                            instructions = rec.instructions
                        )
                        if (ok) {
                            renderSkills()
                            showSnackbar("✅ Installed skill: ${rec.name}")
                        } else {
                            showSnackbar("❌ Failed to install skill ${rec.name}")
                        }
                    }
                }
                cardContent.addView(installBtn)
            }

            card.addView(cardContent)
            skillsListContainer.addView(card)
        }
    }

    private fun showAddSkillDialog() {
        val ctx = context ?: return
        val density = resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }

        val nameInput = EditText(ctx).apply {
            hint = "Skill Name (e.g. NDK Architect)"
            setSingleLine()
        }
        root.addView(nameInput)

        val descInput = EditText(ctx).apply {
            hint = "Instructions / Description"
            minLines = 3
        }
        root.addView(descInput)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Add Agent Skill")
            .setView(root)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val desc = descInput.text.toString().trim()
                if (name.isNotBlank()) {
                    val skillManager = SkillManager.getInstance(ctx)
                    val globalDir = java.io.File(ctx.filesDir, "skills/" + name.lowercase().replace(" ", "-"))
                    globalDir.mkdirs()
                    val mdFile = java.io.File(globalDir, "SKILL.md")
                    mdFile.writeText("---\nname: $name\ndescription: $desc\n---\n\n# $name\n$desc\n")
                    renderSkills()
                    showSnackbar("Created skill $name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        stopCompletionStateMonitoring()
        super.onDestroyView()
    }
}