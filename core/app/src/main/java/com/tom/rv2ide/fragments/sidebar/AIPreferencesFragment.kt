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

    override fun onDestroyView() {
        stopCompletionStateMonitoring()
        super.onDestroyView()
    }
}