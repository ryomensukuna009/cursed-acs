/*

 *  This file is part of AndroidCodeStudio.

 *

 *  AndroidCodeStudio is free software: you can redistribute it and/or modify

 *  it under the terms of the GNU General Public License as published by

 *  the Free Software Foundation, either version 3 of the License, or

 *  (at your option) any later version.

 *

 *  AndroidCodeStudio is distributed in the hope that it will be useful,

 *  but WITHOUT ANY WARRANTY; without even the implied warranty of

 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the

 *  GNU General Public License for more details.

 *

 *  You should have received a copy of the GNU General Public License

 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.

*/



package com.tom.rv2ide.fragments



import android.content.Context

import android.graphics.Color

import android.util.TypedValue

import android.content.Intent

import android.content.SharedPreferences

import android.net.Uri

import android.os.Bundle

import android.text.Editable

import android.text.TextWatcher

import android.view.LayoutInflater

import android.view.View

import android.view.ViewGroup

import android.view.inputmethod.InputMethodManager

import android.widget.EditText

import android.widget.LinearLayout

import android.widget.TextView

import android.graphics.Typeface

import android.view.Gravity

import android.widget.FrameLayout

import android.widget.ScrollView

import com.google.android.material.card.MaterialCardView

import androidx.activity.OnBackPressedCallback

import androidx.core.view.ViewCompat

import androidx.core.view.WindowCompat

import androidx.core.view.WindowInsetsCompat

import androidx.fragment.app.Fragment

import androidx.lifecycle.lifecycleScope

import androidx.recyclerview.widget.LinearLayoutManager

import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.button.MaterialButton

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import com.google.android.material.progressindicator.CircularProgressIndicator

import com.google.android.material.snackbar.Snackbar

import com.google.android.material.textfield.TextInputEditText

import com.google.android.material.textfield.TextInputLayout

import com.google.android.material.textview.MaterialTextView

import com.tom.rv2ide.R

import com.tom.rv2ide.activities.editor.EditorHandlerActivity

import com.tom.rv2ide.adapters.ChatMessageAdapter

import com.tom.rv2ide.adapters.FileModificationAdapter

import com.tom.rv2ide.artificial.agents.AIAgentManager

import com.tom.rv2ide.artificial.agents.Agents

import com.tom.rv2ide.artificial.chat.ChatConversation

import com.tom.rv2ide.artificial.chat.ChatMessageItem

import com.tom.rv2ide.artificial.chat.ConversationSessionManager

import com.tom.rv2ide.artificial.dialogs.ProviderProfileDialogs

import com.tom.rv2ide.artificial.providers.ApiProtocol

import com.tom.rv2ide.artificial.providers.ProviderRegistryManager

import com.tom.rv2ide.artificial.providers.TriStateCapability

import com.tom.rv2ide.artificial.tools.WebSearchManager

import com.tom.rv2ide.artificial.tools.WebSearchStatus

import com.tom.rv2ide.artificial.transport.CitationItem

import com.tom.rv2ide.handlers.AIRequestHandler

import com.tom.rv2ide.managers.CodeCompletionManager

import com.tom.rv2ide.ui.ChatComposerEditText

import com.tom.rv2ide.utils.ProjectHelper.getProjectRoot

import java.io.File

import java.text.SimpleDateFormat

import java.util.Date

import java.util.Locale

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.Job

import kotlinx.coroutines.delay

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext



class ChatFragment : Fragment() {



    private lateinit var aiAgent: AIAgentManager

    private var currentPendingImageBase64: String? = null
    private var currentPendingImageMime: String? = null

    private val pickImageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            handleImagePicked(uri)
        }
    }

    private var classicPromptLayout: TextInputLayout? = null
    private var kelivoComposerCard: View? = null
    private var modeControlPanel: View? = null
    private var classicTopChipRow: View? = null
    private var modeChipScrollView: View? = null
    private lateinit var promptLayout: TextInputLayout

    private lateinit var promptInput: TextInputEditText

    private lateinit var activeModelChipBtn: MaterialButton

    private lateinit var webSearchToggleBtn: MaterialButton

    private lateinit var newChatBtn: MaterialButton

    private lateinit var chatOverflowMenuBtn: MaterialButton

    private lateinit var conversationTitleText: MaterialTextView

    private lateinit var modeSwitcherChipBtn: MaterialButton

    private lateinit var liveTokenBadge: MaterialTextView

    private lateinit var chatRecyclerView: RecyclerView

    private lateinit var emptyStateContainer: LinearLayout

    private lateinit var starterInspectBtn: MaterialButton

    private lateinit var starterFixErrorsBtn: MaterialButton

    private lateinit var jumpToLatestBtn: MaterialButton

    private lateinit var composerContainer: LinearLayout

    private lateinit var executeBtn: MaterialButton

    private lateinit var stopBtn: MaterialButton

    private lateinit var clearBtn: MaterialButton

    private lateinit var btnApprovalMode: MaterialButton
    private lateinit var btnReasoningEffort: MaterialButton

    private lateinit var toolStatusBanner: MaterialTextView

    private lateinit var statusText: MaterialTextView

    private lateinit var summaryText: MaterialTextView
    private var btnComposerModel: com.google.android.material.button.MaterialButton? = null
    private var btnComposerWebSearch: com.google.android.material.button.MaterialButton? = null
    private var btnComposerReasoning: com.google.android.material.button.MaterialButton? = null
    private var btnComposerMode: com.google.android.material.button.MaterialButton? = null
    private var btnComposerApproval: com.google.android.material.button.MaterialButton? = null
    private var btnComposerAttach: com.google.android.material.button.MaterialButton? = null
    private var btnComposerSend: com.google.android.material.button.MaterialButton? = null

    private lateinit var progressIndicator: CircularProgressIndicator

    private lateinit var fileModificationList: RecyclerView

    private lateinit var citationsCard: LinearLayout

    private lateinit var citationsContainer: LinearLayout

    private lateinit var summaryCard: LinearLayout

    private lateinit var holdRequestBanner: LinearLayout

    private lateinit var btnResumeHeldRequest: MaterialButton

    private lateinit var fileModificationAdapter: FileModificationAdapter

    private lateinit var chatMessageAdapter: ChatMessageAdapter



    private lateinit var codeCompletionManager: CodeCompletionManager

    private lateinit var aiRequestHandler: AIRequestHandler



    private val registryManager by lazy { ProviderRegistryManager.getInstance(requireContext()) }

    private val webSearchManager by lazy { WebSearchManager(requireContext()) }

    private val agentsHelper by lazy { Agents(requireContext()) }

    private val sessionManager by lazy { ConversationSessionManager.getInstance(requireContext()) }



    private var explicitWebSearchActive = false

    private var userScrolledUp = false

    private var isUserDragging = false

    private var isRestoringDraft = false

    private var baseComposerBottomPadding = 0



    private var typingJob: Job? = null

    private var fileMonitorJob: Job? = null

    private var completionStateMonitorJob: Job? = null

    private var lastMonitoredFile: File? = null

    private var isSettingUpCompletion = false



    private val userRootProject: String

        get() {

            return try {

                val pr = getProjectRoot().absolutePath

                if (pr.isNotBlank()) {

                    com.tom.rv2ide.builder.SharedBuildOrchestrator.setActiveProjectDir(File(pr))

                    return pr

                }

                com.tom.rv2ide.builder.SharedBuildOrchestrator.getActiveProjectDir()?.absolutePath ?: ""

            } catch (_: Throwable) {

                com.tom.rv2ide.builder.SharedBuildOrchestrator.getActiveProjectDir()?.absolutePath ?: ""

            }

        }



    /**

     * Intercepts Back presses when the software keyboard (IME) is visible inside ChatFragment

     * so the first Back press hides only the keyboard without closing the AI chat drawer.

     */

    private val imeBackPressedCallback = object : OnBackPressedCallback(false) {

        override fun handleOnBackPressed() {

            hideKeyboardKeepChatOpen()

        }

    }



    private val sessionListener = object : ConversationSessionManager.SessionListener {

        override fun onConversationUpdated(conversation: ChatConversation) {

            if (!isAdded || view == null) return

            view?.post {

                if (!isAdded || view == null) return@post

                    val lastMsg = conversation.messages.lastOrNull()

                    if (lastMsg != null && lastMsg.isStreaming && chatMessageAdapter.itemCount == conversation.messages.size) {

                        chatMessageAdapter.updateSingleMessage(lastMsg)

                        val isAtBottom = !chatRecyclerView.canScrollVertically(1)

                        if (isAtBottom && !userScrolledUp && !isUserDragging) {

                            scrollToLatest(smooth = false)

                        }

                    } else {

                        renderConversationState(conversation)

                    }

            }

        }

    }



    private val sharedPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->

        if (key == "code_completion_enabled") {

            val isEnabled = prefs.getBoolean(key, true)

            lifecycleScope.launch {

                handleCompletionStateChange(isEnabled)

            }

        }

    }



    companion object {

        @Volatile

        var pendingFixPrompt: String? = null



        @JvmStatic

        fun requestAiFix(prompt: String) {

            pendingFixPrompt = prompt

            com.tom.rv2ide.utils.EditorSidebarActions.switchToSecondaryAction(

                com.tom.rv2ide.actions.sidebar.AIAgentSidebarAction.ID

            )

        }



        fun newInstance(aiAgent: AIAgentManager): ChatFragment {

            return ChatFragment().apply {

                this.aiAgent = aiAgent

            }

        }

    }



    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        if (!::aiAgent.isInitialized) {

            aiAgent = AIAgentManager(requireContext())

        }

    }



    override fun onCreateView(

        inflater: LayoutInflater,

        container: ViewGroup?,

        savedInstanceState: Bundle?

    ): View? {

        return inflater.inflate(R.layout.fragment_chat, container, false)

    }



    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        super.onViewCreated(view, savedInstanceState)



        requireActivity().onBackPressedDispatcher.addCallback(

            viewLifecycleOwner,

            imeBackPressedCallback

        )



        initializeViews(view)

        setupKeyboardAndWindowInsets(view)

        setupRecyclerViews()

        setupManagers()

        setupListeners()

        refreshHeaderBar()

        loadProject()

        registerPreferenceListener()



        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val lastProj = prefs.getString("last_opened_project_path", null)
        val currentNorm = sessionManager.normalizeProjectKey(userRootProject)
        if (lastProj != null && currentNorm.isNotEmpty() && lastProj != currentNorm) {
            sessionManager.startNewConversation(userRootProject)
        }
        if (currentNorm.isNotEmpty()) {
            prefs.edit().putString("last_opened_project_path", currentNorm).apply()
        }

        sessionManager.setActiveProjectPath(userRootProject)

        sessionManager.addListener(sessionListener)

        restoreDraftText()

        renderConversationState(sessionManager.getActiveConversation(userRootProject))



        com.tom.rv2ide.artificial.policy.ActionPolicyGate.onHoldStateChanged = { isHeld, _ ->

            setChatInputBlockedForHold(isHeld)

        }

    }



    override fun onResume() {

        super.onResume()

        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val lastProj = prefs.getString("last_opened_project_path", null)
        val currentNorm = sessionManager.normalizeProjectKey(userRootProject)
        if (lastProj != null && currentNorm.isNotEmpty() && lastProj != currentNorm) {
            sessionManager.startNewConversation(userRootProject)
            prefs.edit().putString("last_opened_project_path", currentNorm).apply()
        }

        refreshHeaderBar()

        renderConversationState(sessionManager.getActiveConversation(userRootProject))

        startFileMonitoring()

        startCompletionStateMonitoring()



        val pending = pendingFixPrompt

        if (!pending.isNullOrBlank()) {

            pendingFixPrompt = null

            viewLifecycleOwner.lifecycleScope.launch {

                delay(300)

                promptInput.setText(pending)

                submitPrompt(pending)

            }

        }

    }



    override fun onPause() {

        super.onPause()

        stopFileMonitoring()

        stopCompletionStateMonitoring()

    }



    private fun initializeViews(view: View) {

        classicPromptLayout = view.findViewById(R.id.classicPromptLayout)
        kelivoComposerCard = view.findViewById(R.id.kelivoComposerCard)
        modeControlPanel = view.findViewById(R.id.modeControlPanel)
        classicTopChipRow = view.findViewById(R.id.classicTopChipRow)
        modeChipScrollView = view.findViewById(R.id.modeChipScrollView)

        val isKelivoTheme = com.tom.rv2ide.ui.themes.ThemeManager().getCurrentTheme() == com.tom.rv2ide.ui.themes.IDETheme.KELIVO_SLEEK
        if (isKelivoTheme) {
            promptLayout = view.findViewById(R.id.promptLayout)
            promptInput = view.findViewById(R.id.anyText)
            promptLayout.isEndIconVisible = false
            promptLayout.isStartIconVisible = false
            classicPromptLayout?.visibility = View.GONE
            kelivoComposerCard?.visibility = View.VISIBLE
            modeControlPanel?.visibility = View.GONE
            classicTopChipRow?.visibility = View.GONE
            modeChipScrollView?.visibility = View.GONE
        } else {
            promptLayout = classicPromptLayout ?: view.findViewById(R.id.promptLayout)
            promptInput = view.findViewById<com.tom.rv2ide.ui.ChatComposerEditText>(R.id.classicAnyText) ?: view.findViewById(R.id.anyText)
            kelivoComposerCard?.visibility = View.GONE
            classicPromptLayout?.visibility = View.VISIBLE
            modeControlPanel?.visibility = View.VISIBLE
            classicTopChipRow?.visibility = View.VISIBLE
            modeChipScrollView?.visibility = View.VISIBLE
        }

        activeModelChipBtn = view.findViewById(R.id.activeModelChipBtn)

        webSearchToggleBtn = view.findViewById(R.id.webSearchToggleBtn)

        newChatBtn = view.findViewById(R.id.newChatBtn)

        chatOverflowMenuBtn = view.findViewById(R.id.chatOverflowMenuBtn)

        conversationTitleText = view.findViewById(R.id.conversationTitleText)

        modeSwitcherChipBtn = view.findViewById(R.id.modeSwitcherChipBtn)

        liveTokenBadge = view.findViewById(R.id.liveTokenBadge)

        chatRecyclerView = view.findViewById(R.id.chatRecyclerView)

        emptyStateContainer = view.findViewById(R.id.emptyStateContainer)

        starterInspectBtn = view.findViewById(R.id.starterInspectBtn)

        starterFixErrorsBtn = view.findViewById(R.id.starterFixErrorsBtn)

        jumpToLatestBtn = view.findViewById(R.id.jumpToLatestBtn)

        composerContainer = view.findViewById(R.id.composerContainer)

        executeBtn = view.findViewById(R.id.executeBtn)

        stopBtn = view.findViewById(R.id.stopBtn)

        clearBtn = view.findViewById(R.id.clearBtn)

        btnApprovalMode = view.findViewById(R.id.btnApprovalMode)
        btnReasoningEffort = view.findViewById(R.id.btnReasoningEffort)
        btnComposerModel = view.findViewById(R.id.btnComposerModel)
        btnComposerWebSearch = view.findViewById(R.id.btnComposerWebSearch)
        btnComposerReasoning = view.findViewById(R.id.btnComposerReasoning)
        btnComposerMode = view.findViewById(R.id.btnComposerMode)
        btnComposerApproval = view.findViewById(R.id.btnComposerApproval)
        btnComposerAttach = view.findViewById(R.id.btnComposerAttach)
        btnComposerSend = view.findViewById(R.id.btnComposerSend)

        toolStatusBanner = view.findViewById(R.id.toolStatusBanner)

        statusText = view.findViewById(R.id.statusText)

        summaryText = view.findViewById(R.id.summaryText)

        progressIndicator = view.findViewById(R.id.progressIndicator)

        fileModificationList = view.findViewById(R.id.fileModificationList)

        citationsCard = view.findViewById(R.id.citationsCard)

        citationsContainer = view.findViewById(R.id.citationsContainer)

        summaryCard = view.findViewById(R.id.summaryCard)

        holdRequestBanner = view.findViewById(R.id.holdRequestBanner)

        btnResumeHeldRequest = view.findViewById(R.id.btnResumeHeldRequest)

        baseComposerBottomPadding = composerContainer.paddingBottom

    }



    private fun setupKeyboardAndWindowInsets(rootView: View) {

        (promptInput as? ChatComposerEditText)?.onImeDismissRequested = {

            imeBackPressedCallback.isEnabled = false

        }

        promptInput.setOnFocusChangeListener { _, hasFocus ->

            if (hasFocus) {

                imeBackPressedCallback.isEnabled = true

            }

        }



        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->

            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom

            val sysBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom



            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

            val isAccepting = imm?.isAcceptingText() == true

            imeBackPressedCallback.isEnabled = imeVisible || (imeBottom > sysBottom + 80) || promptInput.hasFocus() || isAccepting



            composerContainer.setPadding(

                composerContainer.paddingLeft,

                composerContainer.paddingTop,

                composerContainer.paddingRight,

                baseComposerBottomPadding

            )



            if (imeVisible && !userScrolledUp) {

                scrollToLatest(smooth = false)

            }

            insets

        }

    }



    private fun hideKeyboardKeepChatOpen() {

        val act = activity ?: return

        val decor = act.window?.decorView ?: return

        WindowCompat.getInsetsController(act.window, decor).hide(WindowInsetsCompat.Type.ime())

        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

        val token = promptInput.windowToken ?: decor.windowToken

        imm?.hideSoftInputFromWindow(token, 0)

        promptInput.clearFocus()

        imeBackPressedCallback.isEnabled = false

    }



    private fun setupRecyclerViews() {

        fileModificationAdapter = FileModificationAdapter()

        fileModificationList.apply {

            layoutManager = LinearLayoutManager(requireContext())

            adapter = fileModificationAdapter

            isNestedScrollingEnabled = false

        }

        fileModificationAdapter.setOnItemClickListener { fileName ->

            openFileInEditor(fileName)

        }



        val chatLayoutManager = LinearLayoutManager(requireContext()).apply {

            stackFromEnd = false

        }

        chatMessageAdapter = ChatMessageAdapter(

            onRetryMessage = { msgItem -> handleRetryOrRegenerate(msgItem) },

            onOpenFile = { filePathOrName -> openFileInEditor(filePathOrName) },

            onRollbackSession = { messageId, sessionId, files ->

                showRollbackConfirmationDialog(messageId, sessionId, files)

            },

            onReviewSessionChanges = { messageId, sessionId, files ->

                showReviewChangesDialog(messageId, sessionId, files)

            },

            onEditMessage = { msgItem ->

                loadMessageForEditing(msgItem.content)

            }

        )



        chatRecyclerView.apply {

            layoutManager = chatLayoutManager

            adapter = chatMessageAdapter

            itemAnimator = null // Avoid flicker during rapid streaming token updates

            addOnScrollListener(object : RecyclerView.OnScrollListener() {

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {

                    super.onScrollStateChanged(recyclerView, newState)

                    isUserDragging = (newState == RecyclerView.SCROLL_STATE_DRAGGING)

                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {

                        if (!recyclerView.canScrollVertically(1)) {

                            userScrolledUp = false

                            jumpToLatestBtn.visibility = View.GONE

                        }

                    }

                }



                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {

                    val total = chatLayoutManager.itemCount

                    if (total == 0) {

                        userScrolledUp = false

                        jumpToLatestBtn.visibility = View.GONE

                        return

                    }

                    val canScrollDown = recyclerView.canScrollVertically(1)

                    if (!canScrollDown) {

                        // User reached bottom

                        userScrolledUp = false

                        jumpToLatestBtn.visibility = View.GONE

                    } else {

                        // User is scrolled away from bottom - protect reading position against auto-scroll yanks

                        if (dy < -2 || isUserDragging) {

                            userScrolledUp = true

                        }

                        jumpToLatestBtn.visibility = if (userScrolledUp && total > 1) View.VISIBLE else View.GONE

                    }

                }

            })

        }

    }



    private fun setupManagers() {

        codeCompletionManager = CodeCompletionManager.getInstance(

            requireContext(),

            lifecycleScope,

            aiAgent

        )



        aiRequestHandler = AIRequestHandler(

            lifecycleScope = lifecycleScope,

            aiAgent = aiAgent,

            statusText = statusText,

            summaryText = summaryText,

            progressIndicator = progressIndicator,

            executeBtn = executeBtn,

            fileModificationList = fileModificationList,

            fileModificationAdapter = fileModificationAdapter,

            summaryCard = summaryCard,

            onFileOpen = { fileName -> openFileInEditor(fileName) },

            onTypeText = { text, delay -> typeText(text, delay) },

            getCurrentFile = { getCurrentFile() },

            refreshEditor = { refreshCurrentEditor() },

            stopBtn = stopBtn,

            toolStatusBanner = toolStatusBanner,

            onCitationsUpdated = { citations -> renderCitations(citations) },

            sessionManager = sessionManager,

            getActiveModelId = { agentsHelper.getAgent() }

        )



        aiRequestHandler.onGenerationStateChanged = { isRunning ->

            updateStopButtonState(isRunning)

        }

    }



    private fun setupListeners() {

        activeModelChipBtn.setOnClickListener {

            showModelInformationDialog()

        }



        modeSwitcherChipBtn.setOnClickListener {

            toggleAgentExecutionMode()

        }



        liveTokenBadge.setOnClickListener {

            showModelInformationDialog()

        }



        webSearchToggleBtn.setOnClickListener {

            val currentProv = agentsHelper.getProvider()

            val profile = registryManager.getProfile(currentProv)

            val geminiGrounding = profile?.protocol == ApiProtocol.GEMINI_NATIVE && profile.enableGeminiNativeGrounding

            val cfg = webSearchManager.getConfig()



            if (!explicitWebSearchActive && !cfg.enabled) {

                val statusWhenEnabled = webSearchManager.getSearchStatus(cfg.copy(enabled = true), geminiGrounding)

                if (statusWhenEnabled == WebSearchStatus.NEEDS_CONFIG) {

                    ProviderProfileDialogs.showWebSearchConfigDialog(

                        context = requireContext(),

                        scope = viewLifecycleOwner.lifecycleScope

                    ) { newCfg ->

                        explicitWebSearchActive = newCfg.enabled

                        refreshHeaderBar()

                    }

                    return@setOnClickListener

                }

            }



            explicitWebSearchActive = !explicitWebSearchActive

            webSearchManager.saveConfig(cfg.copy(enabled = explicitWebSearchActive))

            refreshHeaderBar()

            showSnackbar(if (explicitWebSearchActive) "🌐 Web Search enabled" else "🌐 Web Search disabled")

        }



        webSearchToggleBtn.setOnLongClickListener {

            ProviderProfileDialogs.showWebSearchConfigDialog(

                context = requireContext(),

                scope = viewLifecycleOwner.lifecycleScope

            ) { newCfg ->

                explicitWebSearchActive = newCfg.enabled

                refreshHeaderBar()

            }

            true

        }



        chatOverflowMenuBtn.setOnClickListener { anchor ->

            showChatOverflowMenu(anchor)

        }



        newChatBtn.setOnClickListener {

            aiRequestHandler.cancel()

            aiAgent.clearConversation()

            val created = sessionManager.startNewConversation(userRootProject)

            renderConversationState(created)

            showSnackbar("Started new conversation")

        }



        jumpToLatestBtn.setOnClickListener {

            userScrolledUp = false

            jumpToLatestBtn.visibility = View.GONE

            scrollToLatest(smooth = true)

        }



        starterInspectBtn.setOnClickListener {

            submitPrompt("Inspect this project's structure, active build mode, and key source files, and summarize how it works.")

        }



        starterFixErrorsBtn.setOnClickListener {

            submitPrompt("Check the project files for any compilation, resource, or NDK/Gradle issues and fix them.")

        }



        promptInput.addTextChangedListener(object : TextWatcher {

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {

                if (!isRestoringDraft) {

                    sessionManager.saveDraftText(s?.toString() ?: "", userRootProject)

                }

            }

        })



        executeBtn.setOnClickListener {

            val userRequest = promptInput.text?.toString()?.trim() ?: ""

            if (userRequest.isBlank()) {

                showSnackbar("Please enter a message")

                return@setOnClickListener

            }

            if (userRequest.equals("/compact", ignoreCase = true)) {

                promptInput.text?.clear()

                sessionManager.saveDraftText("", userRootProject)

                performManualContextCompaction()

                return@setOnClickListener

            }

            submitPrompt(userRequest)

        }



        stopBtn.setOnClickListener {

            aiRequestHandler.cancel()

            showSnackbar("Stopped generation")

        }



        clearBtn.setOnClickListener {

            val draft = promptInput.text?.toString() ?: ""

            if (draft.isNotBlank()) {

                promptInput.text?.clear()

                sessionManager.saveDraftText("", userRootProject)

                showSnackbar("Cleared composer draft")

            } else {

                confirmClearConversation()

            }

        }



        btnApprovalMode.setOnClickListener {

            toggleApprovalMode()

        }

        updateReasoningEffortButton()
        btnReasoningEffort.setOnClickListener {
            showReasoningEffortDialog()
        }




        holdRequestBanner.setOnClickListener {

            com.tom.rv2ide.artificial.policy.ActionPolicyGate.resumeHeldRequest()

        }



        btnResumeHeldRequest.setOnClickListener {

            com.tom.rv2ide.artificial.policy.ActionPolicyGate.resumeHeldRequest()

        }



        btnComposerModel?.setOnClickListener {
            showModelInformationDialog()
        }
        btnComposerMode?.setOnClickListener {
            toggleAgentExecutionMode()
        }
        btnComposerApproval?.setOnClickListener {
            toggleApprovalMode()
        }
        btnComposerWebSearch?.setOnClickListener {
            webSearchToggleBtn.performClick()
        }
        btnComposerWebSearch?.setOnLongClickListener {
            webSearchToggleBtn.performLongClick()
        }
        btnComposerReasoning?.setOnClickListener {
            showReasoningEffortDialog()
        }
        btnComposerAttach?.setOnClickListener {
            if (checkCurrentModelSupportsVision()) {
                pickImageLauncher.launch("image/*")
            } else {
                showSnackbar("Selected model (${agentsHelper.getAgent()}) does not support vision / images")
            }
        }
        btnComposerSend?.setOnClickListener {
            if (aiRequestHandler.isGenerating()) {
                aiRequestHandler.cancel()
                com.tom.rv2ide.artificial.policy.ActionPolicyGate.cancelHold()
                updateStopButtonState(false)
                showSnackbar("Stopped generation")
            } else {
                executeBtn.performClick()
            }
        }

        classicPromptLayout?.setStartIconOnClickListener {
            if (checkCurrentModelSupportsVision()) {
                pickImageLauncher.launch("image/*")
            } else {
                showSnackbar("Selected model (${agentsHelper.getAgent()}) does not support vision / images")
            }
        }
        classicPromptLayout?.setEndIconOnClickListener {
            if (aiRequestHandler.isGenerating()) {
                aiRequestHandler.cancel()
                com.tom.rv2ide.artificial.policy.ActionPolicyGate.cancelHold()
                updateStopButtonState(false)
                showSnackbar("Stopped generation")
            } else {
                executeBtn.performClick()
            }
        }

        promptLayout.setEndIconOnClickListener {

            if (aiRequestHandler.isGenerating()) {

                aiRequestHandler.cancel()

                com.tom.rv2ide.artificial.policy.ActionPolicyGate.cancelHold()

                updateStopButtonState(false)

                showSnackbar("Stopped generation")

            } else {

                executeBtn.performClick()

            }

        }



        stopBtn.setOnClickListener {

            aiRequestHandler.cancel()

            com.tom.rv2ide.artificial.policy.ActionPolicyGate.cancelHold()

            updateStopButtonState(false)

            showSnackbar("Stopped generation")

        }

        view?.findViewById<android.view.View>(R.id.btnRemoveAttachedImage)?.setOnClickListener {
            clearAttachedImage()
        }
        updateVisionButtonState()

    }



    private fun showChatOverflowMenu(anchorView: View) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchorView)

        popup.menuInflater.inflate(R.menu.menu_chat_overflow, popup.menu)

        popup.setOnMenuItemClickListener { menuItem ->

            when (menuItem.itemId) {

                R.id.action_plan_mode -> {
                    toggleAgentExecutionMode()
                    true
                }

                R.id.action_rollback_edits -> {
                    triggerLastSessionRollback()
                    true
                }

                R.id.action_project_rules -> {
                    showProjectRulesDialog()
                    true
                }

                R.id.action_compact_context -> {

                    performManualContextCompaction()

                    true

                }

                R.id.action_clear_chat -> {

                    confirmClearConversation()

                    true

                }

                R.id.action_developer_stats -> {

                    com.tom.rv2ide.artificial.stats.TokenTelemetryTracker.getInstance(requireContext())
                        .showTelemetryDialog(requireContext())

                    true

                }

                R.id.action_dismiss_keyboard -> {

                    hideKeyboardKeepChatOpen()

                    true

                }

                else -> false

            }

        }

        popup.show()

    }



    private fun updateApprovalModeButton() {

        val isTurbo = com.tom.rv2ide.artificial.policy.ApprovalManager.isTurbo(requireContext())

        if (isTurbo) {
            btnApprovalMode.text = "⚡ Turbo"
            btnApprovalMode.alpha = 0.85f
            btnComposerApproval?.text = "⚡ Turbo"
            btnComposerApproval?.alpha = 0.85f
        } else {
            btnApprovalMode.text = "🛡️ Approve"
            btnApprovalMode.alpha = 1.0f
            btnComposerApproval?.text = "🛡️ Approve"
            btnComposerApproval?.alpha = 1.0f
        }

    }



    private fun updateReasoningEffortButton() {
        val currentModel = agentsHelper.getAgent()
        val effort = com.tom.rv2ide.artificial.preferences.AIAgentPreferences.getEffectiveReasoningEffort(requireContext(), currentModel)
        val fromModel = com.tom.rv2ide.artificial.models.ReasoningEffort.detectFromModelId(currentModel)
        val effortText = when (effort) {
            com.tom.rv2ide.artificial.models.ReasoningEffort.AUTO -> "🧠 Auto"
            com.tom.rv2ide.artificial.models.ReasoningEffort.OFF -> "🧠 Off"
            com.tom.rv2ide.artificial.models.ReasoningEffort.LOW -> "🧠 Low"
            com.tom.rv2ide.artificial.models.ReasoningEffort.MEDIUM -> "🧠 Med"
            com.tom.rv2ide.artificial.models.ReasoningEffort.HIGH -> "🧠 High"
            com.tom.rv2ide.artificial.models.ReasoningEffort.MAXIMUM -> "🧠 Max"
        }
        btnReasoningEffort.text = effortText
        btnComposerReasoning?.text = effortText
    }

    private fun loadMessageForEditing(content: String) {
        promptInput.setText(content)
        promptInput.setSelection(content.length)
        promptInput.requestFocus()
        try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(promptInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Exception) {}
        showSnackbar("Prompt loaded for editing")
    }

    private fun showReasoningEffortDialog() {
        val currentModel = agentsHelper.getAgent()
        val detected = com.tom.rv2ide.artificial.models.ReasoningEffort.detectFromModelId(currentModel)
        if (detected != null) {
            showSnackbar("Model has requested reasoning effort: ${detected.displayName}")
        }
        val current = com.tom.rv2ide.artificial.preferences.AIAgentPreferences.getEffectiveReasoningEffort(requireContext(), currentModel)
        com.tom.rv2ide.artificial.dialogs.ReasoningEffortSliderDialog.show(
            requireContext(),
            current
        ) { chosen ->
            com.tom.rv2ide.artificial.preferences.AIAgentPreferences.setReasoningEffort(requireContext(), chosen)
            updateReasoningEffortButton()
            showSnackbar("Reasoning effort set to ${chosen.displayName}")
        }
    }

    private fun toggleApprovalMode() {

        val currentIsTurbo = com.tom.rv2ide.artificial.policy.ApprovalManager.isTurbo(requireContext())

        val newMode = if (currentIsTurbo) {

            com.tom.rv2ide.artificial.policy.ApprovalManager.MODE_APPROVE

        } else {

            com.tom.rv2ide.artificial.policy.ApprovalManager.MODE_TURBO

        }

        com.tom.rv2ide.artificial.policy.ApprovalManager.setApprovalMode(requireContext(), newMode)

        updateApprovalModeButton()

        if (newMode == com.tom.rv2ide.artificial.policy.ApprovalManager.MODE_APPROVE) {

            showSnackbar("🛡️ Approve Mode: AI Agent will ask for your approval before modifying files or executing actions.")

        } else {

            showSnackbar("⚡ Turbo Mode: Autonomous execution without approval prompts.")

        }

    }



    private fun updateModeSwitcherButton(mode: com.tom.rv2ide.artificial.policy.AgentExecutionMode) {
        modeSwitcherChipBtn.text = mode.shortBadge
        btnComposerMode?.text = mode.shortBadge
        when (mode) {
            com.tom.rv2ide.artificial.policy.AgentExecutionMode.AUTONOMOUS ->
                modeSwitcherChipBtn.setIconResource(R.drawable.ic_nav_ai_agent_badass)
            com.tom.rv2ide.artificial.policy.AgentExecutionMode.PLAN_MODE ->
                modeSwitcherChipBtn.setIconResource(R.drawable.ic_search)
            else ->
                modeSwitcherChipBtn.setIconResource(R.drawable.ic_chat)
        }
    }

    private fun toggleAgentExecutionMode() {
        val current = com.tom.rv2ide.artificial.policy.AgentExecutionMode.getSavedMode(requireContext())
        val next = when (current) {
            com.tom.rv2ide.artificial.policy.AgentExecutionMode.AUTONOMOUS ->
                com.tom.rv2ide.artificial.policy.AgentExecutionMode.PLAN_MODE
            com.tom.rv2ide.artificial.policy.AgentExecutionMode.PLAN_MODE ->
                com.tom.rv2ide.artificial.policy.AgentExecutionMode.SIDE_AGENT
            else ->
                com.tom.rv2ide.artificial.policy.AgentExecutionMode.AUTONOMOUS
        }

        com.tom.rv2ide.artificial.policy.AgentExecutionMode.saveMode(requireContext(), next)
        updateModeSwitcherButton(next)
        showSnackbar("Mode: ${next.displayName} • ${next.description}")
    }

    private fun triggerLastSessionRollback() {
        val messages = sessionManager.getActiveConversation().messages.reversed()
        val candidate = messages.firstOrNull { msg ->
            val rep = msg.completionReport
            rep != null && !rep.rollbackSessionId.isNullOrBlank() && !rep.filesModified.isNullOrEmpty()
        }
        if (candidate == null) {
            showSnackbar("No recent file modifications found to roll back.")
            return
        }
        val rep = candidate.completionReport!!
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("↺ Rollback Last AI Changes")
            .setMessage("Do you want to roll back modifications to ${rep.filesModified.size} file(s)?\n\n${rep.filesModified.joinToString("\n")}")
            .setPositiveButton("Rollback") { _, _ ->
                val projectRoot = com.tom.rv2ide.builder.SharedBuildOrchestrator.getActiveProjectDir()
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = com.tom.rv2ide.artificial.coding.AutonomousRollbackManager.rollbackSession(
                        sessionId = rep.rollbackSessionId!!,
                        projectRootOverride = projectRoot
                    )
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        if (result.revertedCount > 0) {
                            sessionManager.markSessionRolledBack(candidate.id)
                            refreshCurrentEditor()
                        }
                        showSnackbar("Rollback complete: ${result.revertedCount} file(s) reverted.")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProjectRulesDialog() {
        val projectRoot = com.tom.rv2ide.builder.SharedBuildOrchestrator.getActiveProjectDir()
        if (projectRoot == null) {
            showSnackbar("No active project opened.")
            return
        }
        val rulesFile = java.io.File(projectRoot, "AGENTS.md")
        val density = resources.displayMetrics.density
        val input = EditText(requireContext()).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            if (rulesFile.exists()) {
                setText(try { rulesFile.readText() } catch (_: Exception) { "" })
            } else {
                hint = "# Project Rules\nDefine coding guidelines, forbidden libraries, or formatting requirements here for the AI Agent..."
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Project Rules (AGENTS.md)")
            .setMessage("The AI Agent automatically reads and adheres to AGENTS.md on every prompt.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text?.toString().orEmpty()
                try {
                    rulesFile.writeText(text)
                    showSnackbar("Saved AGENTS.md (${text.lines().size} lines)")
                } catch (e: Exception) {
                    showSnackbar("Failed to save rules: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }



    private fun performManualContextCompaction(onFinished: ((com.tom.rv2ide.artificial.chat.CompactionResult) -> Unit)? = null) {

        val activeConv = sessionManager.getActiveConversation(userRootProject)

        if (activeConv.messages.isEmpty()) {

            showSnackbar("No messages in conversation to compact.")

            return

        }



        val providerId = agentsHelper.getProvider()

        val modelId = agentsHelper.getAgent()

        val caps = registryManager.getEffectiveModelCapabilities(providerId, modelId)



        val unifiedMessages = activeConv.messages.map { it.toUnifiedChatMessage() }

        val result = com.tom.rv2ide.artificial.chat.ConversationCompactor.compactMessages(

            messages = unifiedMessages,

            capabilities = caps,

            systemPrompt = null,

            tools = emptyList(),

            forceCompaction = true

        )



        if (result.compacted && result.messages.isNotEmpty()) {

            val newChatItems = result.messages.map { com.tom.rv2ide.artificial.chat.ChatMessageItem.fromUnifiedChatMessage(it, activeConv.id) }

            val updatedConv = sessionManager.updateConversationMessages(activeConv.id, newChatItems) ?: activeConv.copy(messages = newChatItems)

            renderConversationState(updatedConv)

            refreshHeaderBar()

            val savedTokens = result.estimatedTokensBefore - result.estimatedTokensAfter

            val notice = if (savedTokens > 0) {

                "🧹 Context compacted: saved ~${savedTokens} tokens (${result.estimatedTokensBefore} → ${result.estimatedTokensAfter} tk)"

            } else {

                "Context already optimal (${result.estimatedTokensAfter} tk)"

            }

            showSnackbar(notice)

            onFinished?.invoke(result)

        } else {

            showSnackbar("Context is already compact (${result.estimatedTokensBefore} tk).")

            onFinished?.invoke(result)

        }

    }



    
    private fun handleImagePicked(uri: android.net.Uri) {
        try {
            val ctx = context ?: return
            val cr = ctx.contentResolver
            val inputStream = cr.openInputStream(uri) ?: return
            val originalBmp = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (originalBmp != null) {
                val maxDim = 1280
                val w = originalBmp.width
                val h = originalBmp.height
                val scale = if (w > maxDim || h > maxDim) {
                    minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
                } else 1.0f

                val finalBmp = if (scale < 1.0f) {
                    android.graphics.Bitmap.createScaledBitmap(
                        originalBmp,
                        (w * scale).toInt(),
                        (h * scale).toInt(),
                        true
                    )
                } else originalBmp

                val baos = java.io.ByteArrayOutputStream()
                finalBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                val bytes = baos.toByteArray()
                currentPendingImageBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                currentPendingImageMime = "image/jpeg"

                val container = view?.findViewById<android.view.View>(R.id.attachedImageContainer)
                val preview = view?.findViewById<android.widget.ImageView>(R.id.attachedImagePreview)
                container?.visibility = android.view.View.VISIBLE
                preview?.setImageBitmap(finalBmp)
            }
        } catch (e: Exception) {
            showSnackbar("Failed to attach image: ${e.message}")
        }
    }

    private fun clearAttachedImage() {
        currentPendingImageBase64 = null
        currentPendingImageMime = null
        val container = view?.findViewById<android.view.View>(R.id.attachedImageContainer)
        val preview = view?.findViewById<android.widget.ImageView>(R.id.attachedImagePreview)
        container?.visibility = android.view.View.GONE
        preview?.setImageDrawable(null)
    }

    private fun checkCurrentModelSupportsVision(): Boolean {
        return try {
            val rawModel = agentsHelper.getAgent()
            val providerId = agentsHelper.getProvider()
            val catalog = com.tom.rv2ide.artificial.models.ModelCatalogManager.getInstance(requireContext())
            if (catalog.isVisionSupported(rawModel)) {
                return true
            }
            val caps = com.tom.rv2ide.artificial.providers.ProviderRegistryManager.getInstance(requireContext())
                .getEffectiveModelCapabilities(providerId, rawModel)
            if (caps.supportsVision == com.tom.rv2ide.artificial.providers.TriStateCapability.SUPPORTED) {
                return true
            }
            val norm = rawModel.lowercase()
            val visionKeywords = listOf("gemini", "gpt-4o", "gpt-4-turbo", "vision", "-vl", "claude", "omni", "multimodal", "pixtral", "llava", "qwen-vl", "o1", "o3", "grok", "kimi")
            visionKeywords.any { norm.contains(it) }
        } catch (_: Exception) {
            false
        }
    }

    private fun updateVisionButtonState() {
        try {
            val supportsVision = checkCurrentModelSupportsVision()
            if (supportsVision) {
                val tv = android.util.TypedValue()
                val primaryColor = if (requireContext().theme.resolveAttribute(android.R.attr.colorPrimary, tv, true)) {
                    tv.data
                } else {
                    android.graphics.Color.parseColor("#4285F4")
                }
                promptLayout.setStartIconTintList(android.content.res.ColorStateList.valueOf(primaryColor))
                promptLayout.setStartIconOnClickListener {
                    pickImageLauncher.launch("image/*")
                }
            } else {
                val greyColor = android.graphics.Color.parseColor("#888888")
                promptLayout.setStartIconTintList(android.content.res.ColorStateList.valueOf(greyColor))
                promptLayout.setStartIconOnClickListener {
                    showSnackbar("Selected model (${agentsHelper.getAgent()}) does not support vision / images")
                }
            }
            btnComposerAttach?.let { btn ->
                btn.isEnabled = supportsVision
                btn.alpha = if (supportsVision) 1.0f else 0.4f
            }
        } catch (_: Exception) {}
    }


    private fun submitPrompt(userRequest: String) {

        codeCompletionManager.clearSuggestion()

        isRestoringDraft = true

        promptInput.text?.clear()

        isRestoringDraft = false

        sessionManager.saveDraftText("", userRootProject)



        userScrolledUp = false

        jumpToLatestBtn.visibility = View.GONE

        val imgBase64 = currentPendingImageBase64
        val imgMime = currentPendingImageMime
        clearAttachedImage()

        aiRequestHandler.execute(

            userRequest = userRequest,

            explicitWebSearch = explicitWebSearchActive,

            appendUserTurn = true,

            imageBase64 = imgBase64,

            imageMimeType = imgMime

        )

        scrollToLatest(smooth = true)

    }



    private fun handleRetryOrRegenerate(item: ChatMessageItem) {

        if (aiRequestHandler.isGenerating()) {

            aiRequestHandler.cancel()

        }

        val promptToRun = sessionManager.prepareRetryOrRegenerate(item.id)

        if (promptToRun.isNullOrBlank()) {

            showSnackbar("Could not find prompt to retry")

            return

        }

        userScrolledUp = false

        val isFromUserBubble = item.role == com.tom.rv2ide.artificial.transport.ChatRole.USER

        aiRequestHandler.execute(

            userRequest = promptToRun,

            explicitWebSearch = explicitWebSearchActive,

            appendUserTurn = isFromUserBubble

        )

        scrollToLatest(smooth = true)

    }



    private fun renderConversationState(conversation: ChatConversation) {

        val turnCount = conversation.messages.count { it.role == com.tom.rv2ide.artificial.transport.ChatRole.USER }

        conversationTitleText.text = if (turnCount > 0) {

            "${conversation.title} ($turnCount turn${if (turnCount == 1) "" else "s"})"

        } else {

            conversation.title

        }



        val hasMessages = conversation.messages.isNotEmpty()
        emptyStateContainer.visibility = if (hasMessages) View.GONE else View.VISIBLE
        chatRecyclerView.visibility = View.VISIBLE
        chatMessageAdapter.submitMessages(conversation.messages)



        if (hasMessages && !userScrolledUp) {

            scrollToLatest(smooth = false)

        }

    }



    private fun updateStopButtonState(isRunning: Boolean) {

        if (!isAdded || view == null) return

        val isKelivoTheme = com.tom.rv2ide.ui.themes.ThemeManager().getCurrentTheme() == com.tom.rv2ide.ui.themes.IDETheme.KELIVO_SLEEK
        promptLayout.post {
            if (!isAdded || view == null) return@post
            val drawableRes = if (isRunning) R.drawable.ic_stop else R.drawable.ic_send
            if (!isKelivoTheme) {
                promptLayout.isEndIconVisible = true
                promptLayout.endIconDrawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), drawableRes)
                promptLayout.endIconContentDescription = if (isRunning) "Stop generation" else "Send message"
            } else {
                promptLayout.isEndIconVisible = false
            }
            classicPromptLayout?.endIconDrawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), drawableRes)
            classicPromptLayout?.endIconContentDescription = if (isRunning) "Stop generation" else "Send message"
        }
        btnComposerSend?.let { btn ->
            btn.post {
                if (isRunning) {
                    btn.setIconResource(R.drawable.ic_stop)
                    btn.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#EF4444"))
                } else {
                    btn.setIconResource(R.drawable.ic_arrow_upward)
                    val tv = android.util.TypedValue()
                    val primaryColor = if (requireContext().theme.resolveAttribute(android.R.attr.colorPrimary, tv, true)) tv.data else android.graphics.Color.parseColor("#8B5CF6")
                    btn.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
                }
            }
        }

    }



    fun setChatInputBlockedForHold(blocked: Boolean) {

        if (!isAdded || view == null) return

        view?.post {

            if (!isAdded || view == null) return@post

            holdRequestBanner.visibility = if (blocked) View.VISIBLE else View.GONE

            promptInput.isEnabled = !blocked

            promptInput.alpha = if (blocked) 0.5f else 1.0f

            newChatBtn.isEnabled = !blocked

            newChatBtn.alpha = if (blocked) 0.5f else 1.0f

            chatOverflowMenuBtn.isEnabled = !blocked

            chatOverflowMenuBtn.alpha = if (blocked) 0.5f else 1.0f

            modeSwitcherChipBtn.isEnabled = !blocked

            btnApprovalMode.isEnabled = !blocked
            btnReasoningEffort.isEnabled = !blocked

            activeModelChipBtn.isEnabled = !blocked

        }

    }



    private fun scrollToLatest(smooth: Boolean) {

        if (userScrolledUp || isUserDragging) return

        val count = chatMessageAdapter.itemCount

        if (count <= 0) return

        chatRecyclerView.post {

            if (!isAdded || userScrolledUp || isUserDragging) return@post

            val target = (chatMessageAdapter.itemCount - 1).coerceAtLeast(0)

            val lm = chatRecyclerView.layoutManager as? LinearLayoutManager

            if (smooth) {

                chatRecyclerView.smoothScrollToPosition(target)

            } else {

                lm?.scrollToPositionWithOffset(target, -999999) ?: chatRecyclerView.scrollToPosition(target)

            }

        }

    }



    private fun restoreDraftText() {

        val savedDraft = sessionManager.getDraftText(userRootProject)

        if (savedDraft.isNotEmpty() && promptInput.text.isNullOrEmpty()) {

            isRestoringDraft = true

            promptInput.setText(savedDraft)

            promptInput.setSelection(savedDraft.length)

            isRestoringDraft = false

        }

    }



    private fun showConversationsPickerDialog() {

        val conversations = sessionManager.listConversations(userRootProject)

        val activeId = sessionManager.getActiveConversation(userRootProject).id

        val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())



        val labels = conversations.map { conv ->

            val activeMark = if (conv.id == activeId) "● " else "   "

            val turns = conv.messages.count { it.role == com.tom.rv2ide.artificial.transport.ChatRole.USER }

            val timeStr = dateFormat.format(Date(conv.updatedAt))

            "$activeMark${conv.title} ($turns turns • $timeStr)"

        }.toTypedArray()



        MaterialAlertDialogBuilder(requireContext())

            .setTitle("Conversations (${conversations.size})")

            .setItems(labels) { _, which ->

                val chosen = conversations[which]

                showConversationItemOptionsDialog(chosen)

            }

            .setPositiveButton("+ New Chat") { _, _ ->

                aiRequestHandler.cancel()

                aiAgent.clearConversation()

                val created = sessionManager.startNewConversation(userRootProject)

                renderConversationState(created)

            }

            .setNegativeButton("Close", null)

            .show()

    }



    private fun showConversationItemOptionsDialog(conv: ChatConversation) {

        val options = arrayOf(

            "Switch to this conversation",

            "Rename conversation",

            "Delete conversation"

        )

        MaterialAlertDialogBuilder(requireContext())

            .setTitle(conv.title)

            .setItems(options) { _, which ->

                when (which) {

                    0 -> {

                        val switched = sessionManager.switchConversation(conv.id, userRootProject)

                        if (switched != null) {

                            renderConversationState(switched)

                            showSnackbar("Switched to: ${switched.title}")

                        }

                    }

                    1 -> {

                        val input = EditText(requireContext()).apply {

                            setText(conv.title)

                            setSelection(conv.title.length)

                        }

                        MaterialAlertDialogBuilder(requireContext())

                            .setTitle("Rename Conversation")

                            .setView(input)

                            .setPositiveButton("Save") { _, _ ->

                                val newTitle = input.text?.toString()?.trim().orEmpty()

                                if (newTitle.isNotEmpty()) {

                                    sessionManager.renameConversation(conv.id, newTitle)

                                    renderConversationState(sessionManager.getActiveConversation(userRootProject))

                                }

                            }

                            .setNegativeButton("Cancel", null)

                            .show()

                    }

                    2 -> {

                        sessionManager.deleteConversation(conv.id, userRootProject)

                        renderConversationState(sessionManager.getActiveConversation(userRootProject))

                        showSnackbar("Deleted conversation")

                    }

                }

            }
            .show()
    }

    private fun confirmClearConversation() {

        val active = sessionManager.getActiveConversation(userRootProject)

        if (active.messages.isEmpty()) {

            showSnackbar("Conversation is already empty")

            return

        }

        MaterialAlertDialogBuilder(requireContext())

            .setTitle("Clear Conversation?")

            .setMessage("This will clear the messages in '${active.title}'. You can also tap '＋ New' to start a new chat while keeping this one in history.")

            .setPositiveButton("Clear") { _, _ ->

                clearConversation()

            }

            .setNeutralButton("Start New Chat") { _, _ ->

                val created = sessionManager.startNewConversation(userRootProject)

                renderConversationState(created)

            }

            .setNegativeButton("Cancel", null)

            .show()

    }



    private fun refreshHeaderBar() {

        val providerId = agentsHelper.getProvider()

        val modelId = agentsHelper.getAgent()

        val profile = registryManager.getProfile(providerId)

        val providerName = profile?.displayName ?: providerId

        val hasKey = registryManager.hasProviderApiKey(providerId) || profile?.authStyle?.name == "NONE"

        val connDot = if (hasKey) "🟢" else "🟠"

        val caps = registryManager.getEffectiveModelCapabilities(providerId, modelId)

        val toolHint = if (caps.supportsTools == TriStateCapability.UNSUPPORTED) " [no-tools]" else ""

                activeModelChipBtn.text = "$connDot $providerName • $modelId$toolHint"
        val shortModel = modelId.substringAfterLast("/")
        val displayModel = if (shortModel.length > 10) shortModel.take(9) + "…" else shortModel
        btnComposerModel?.text = "📦 $displayModel"
        updateReasoningEffortButton()
        val isWebSearchOn = explicitWebSearchActive || webSearchManager.getConfig().enabled
        btnComposerWebSearch?.let { btn ->
            if (isWebSearchOn) {
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#06B6D4"))
            } else {
                val tv = android.util.TypedValue()
                val containerColor = if (requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerHighest, tv, true)) tv.data else android.graphics.Color.parseColor("#2A2F3D")
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(containerColor)
            }
        }



        val mode = com.tom.rv2ide.artificial.policy.AgentExecutionMode.getSavedMode(requireContext())

        updateModeSwitcherButton(mode)

        updateApprovalModeButton()

        if (mode == com.tom.rv2ide.artificial.policy.AgentExecutionMode.SIDE_AGENT) {

            promptLayout.hint = "Side Agent: Ask questions or send /edit to apply changes…"

        } else {

            promptLayout.hint = "Ask a question or describe code changes…"

        }



        val activeConv = sessionManager.getActiveConversation(userRootProject)

        val estTokens = activeConv.messages.sumOf { com.tom.rv2ide.artificial.chat.ConversationCompactor.estimateMessageTokens(it.toUnifiedChatMessage()) }

        val limit = caps.contextLength ?: com.tom.rv2ide.artificial.chat.ConversationCompactor.DEFAULT_CONSERVATIVE_CONTEXT_LIMIT

        val estStr = if (estTokens >= 1000) String.format(Locale.US, "%.1fk", estTokens / 1000.0) else "$estTokens"

        val limitStr = if (limit >= 1000) String.format(Locale.US, "%dk", limit / 1000) else "$limit"

        liveTokenBadge.text = "$estStr / $limitStr tk"



        val cfg = webSearchManager.getConfig()

        explicitWebSearchActive = cfg.enabled

        val geminiGrounding = profile?.protocol == ApiProtocol.GEMINI_NATIVE && profile.enableGeminiNativeGrounding

        val searchStatus = webSearchManager.getSearchStatus(cfg, geminiGrounding)



        webSearchToggleBtn.text = when (searchStatus) {

            WebSearchStatus.DISABLED -> "🌐 Off"

            WebSearchStatus.NEEDS_CONFIG -> "🌐 Setup"

            WebSearchStatus.READY -> "🌐 On"

        }

    }



    private fun showModelInformationDialog() {

        val providerId = agentsHelper.getProvider()

        val modelId = agentsHelper.getAgent()

        val profile = registryManager.getProfile(providerId)

        val providerName = profile?.displayName ?: providerId

        val hasKey = registryManager.hasProviderApiKey(providerId) || profile?.authStyle?.name == "NONE"

        val caps = registryManager.getEffectiveModelCapabilities(providerId, modelId)



        val dialogView = layoutInflater.inflate(R.layout.dialog_model_info, null)



        val titleView = dialogView.findViewById<TextView>(R.id.dialogModelTitle)

        val statusView = dialogView.findViewById<TextView>(R.id.dialogProviderStatus)

        val contextView = dialogView.findViewById<TextView>(R.id.dialogContextLength)

        val toolsView = dialogView.findViewById<TextView>(R.id.dialogToolStatus)

        val modalitiesView = dialogView.findViewById<TextView>(R.id.dialogModalities)

        val protocolView = dialogView.findViewById<TextView>(R.id.dialogProtocol)

        val meterText = dialogView.findViewById<TextView>(R.id.dialogContextMeterText)

        val progressBar = dialogView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.dialogContextProgress)

        val breakdownText = dialogView.findViewById<TextView>(R.id.dialogTokenBreakdownText)

        val cumulativeText = dialogView.findViewById<TextView>(R.id.dialogCumulativeText)

        val accountQuotaView = dialogView.findViewById<TextView>(R.id.dialogAccountQuotaText)

        val compactBtn = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.dialogBtnCompactContext)



        titleView.text = modelId

        val connStatus = if (hasKey) "🟢 Connected" else "🟠 Key Required"

        statusView.text = "$providerName • $connStatus"



        val ctx = caps.contextLength

        contextView.text = if (ctx != null && ctx > 0) {

            val chars = ctx * 4

            String.format("%,d tokens (~%.1fM chars)", ctx, chars / 1_000_000.0)

        } else {

            "Standard Context Window (Auto-budgeted)"

        }



        toolsView.text = when (caps.supportsTools) {

            com.tom.rv2ide.artificial.providers.TriStateCapability.SUPPORTED -> "⚡ Verified • 8 IDE Tools Active"

            com.tom.rv2ide.artificial.providers.TriStateCapability.UNSUPPORTED -> "⚠️ No Tool Calling Support"

            com.tom.rv2ide.artificial.providers.TriStateCapability.UNKNOWN -> "⚡ Auto-detected (IDE Tools Active)"

        }



        val inMods = caps.inputModalities?.joinToString(", ") ?: "Text, Code"

        modalitiesView.text = inMods

        protocolView.text = profile?.protocol?.displayName ?: "Standard API Protocol"



        fun updateTelemetryUi() {

            val conv = sessionManager.getActiveConversation(userRootProject)

            val estTokens = conv.messages.sumOf { com.tom.rv2ide.artificial.chat.ConversationCompactor.estimateMessageTokens(it.toUnifiedChatMessage()) }

            val limit = caps.contextLength ?: com.tom.rv2ide.artificial.chat.ConversationCompactor.DEFAULT_CONSERVATIVE_CONTEXT_LIMIT

            val pct = ((estTokens.toDouble() / limit.toDouble()) * 100.0).coerceIn(0.0, 100.0).toInt()



            meterText?.text = String.format(Locale.US, "%,d / %,d tk (%d%%)", estTokens, limit, pct)

            progressBar?.progress = pct



            val lastUsage = aiAgent.getLastTokenUsage()

            if (lastUsage != null) {

                val cached = lastUsage.cachedPromptTokens ?: 0

                val cachedInfo = if (cached > 0) " • Cached: $cached" else ""

                val reasoning = lastUsage.thinkingTokens ?: 0

                val reasoningInfo = if (reasoning > 0) " • Reason: $reasoning" else ""

                val latInfo = if (lastUsage.latencyMs > 0) String.format(Locale.US, " • Latency: %.1fs", lastUsage.latencyMs / 1000.0) else ""

                breakdownText?.text = "Last Request: In: ${lastUsage.promptTokens} • Out: ${lastUsage.completionTokens}$reasoningInfo$cachedInfo$latInfo"

            } else {

                breakdownText?.text = "Last Request: No metrics recorded yet in this session."

            }



            val cumUsage = aiAgent.getCumulativeTokenUsage()

            cumulativeText?.text = String.format(Locale.US, "Session Usage: %,d prompt + %,d completion tokens", cumUsage.promptTokens, cumUsage.completionTokens)

            accountQuotaView?.text = "Account Quota: Not reported by provider (managed via provider console)"

        }



        updateTelemetryUi()



        compactBtn?.setOnClickListener {

            performManualContextCompaction {

                updateTelemetryUi()

            }

        }



        val dialog = MaterialAlertDialogBuilder(requireContext())

            .setView(dialogView)

            .show()



        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.dialogBtnSearchModels).setOnClickListener {

            dialog.dismiss()

            ProviderProfileDialogs.showSearchableModelPickerDialog(

                context = requireContext(),

                scope = viewLifecycleOwner.lifecycleScope,

                providerId = providerId

            ) { selectedModel ->

                agentsHelper.setAgent(selectedModel)

                aiAgent.reinitializeWithSelectedModel()
                updateVisionButtonState()

                refreshHeaderBar()

                showSnackbar("Model: $selectedModel (conversation preserved)")

            }

        }



        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.dialogBtnSwitchProvider).setOnClickListener {
            dialog.dismiss()
            showDualTabSwitchProviderDialog()
        }



        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.dialogBtnConfigureKey).setOnClickListener {

            dialog.dismiss()

            if (profile != null) {

                ProviderProfileDialogs.showEditProfileDialog(

                    context = requireContext(),

                    scope = viewLifecycleOwner.lifecycleScope,

                    profile = profile,

                    isNew = false,

                    onProfileSaved = { updated ->

                        aiAgent.refreshRegisteredProfiles()

                        aiAgent.setProvider(updated.id)

                        refreshHeaderBar()

                    }

                )

            }

        }

    }



    private fun showQuickProviderModelMenu() {

        val providerId = agentsHelper.getProvider()

        val profile = registryManager.getProfile(providerId)

        val options = arrayOf(
            "🔑 Connected Accounts (ChatGPT, Claude, Grok, Kimi, Gemini)",
            "Select / Search Model (${agentsHelper.getAgent()})",
            "Switch AI Provider (${profile?.displayName ?: providerId})",
            "Configure Current Provider / API Key",
            "+ Add New Custom Provider Profile"
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("AI Model & Provider")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        com.tom.rv2ide.artificial.auth.ProviderOAuthManager.showOAuthAccountsHubDialog(
                            requireContext(),
                            viewLifecycleOwner.lifecycleScope
                        ) { connected ->
                            aiAgent.refreshRegisteredProfiles()
                            if (connected.isNotBlank()) {
                                agentsHelper.setProvider(connected)
                                aiAgent.setProvider(connected)
                                val p = registryManager.getProfile(connected)
                                if (p != null && p.selectedModelId.isNotBlank()) {
                                    agentsHelper.setAgent(p.selectedModelId)
                                }
                                aiAgent.reinitializeWithSelectedModel()
                                updateVisionButtonState()
                                updateReasoningEffortButton()
                                refreshHeaderBar()
                                showSnackbar("Switched to $connected")
                            }
                        }
                    }
                    1 -> {

                        ProviderProfileDialogs.showSearchableModelPickerDialog(

                            context = requireContext(),

                            scope = viewLifecycleOwner.lifecycleScope,

                            providerId = providerId

                        ) { selectedModel ->

                            agentsHelper.setAgent(selectedModel)

                            aiAgent.reinitializeWithSelectedModel()
                updateVisionButtonState()

                            refreshHeaderBar()

                            showSnackbar("Model: $selectedModel (conversation preserved)")

                        }

                    }

                    2 -> {
                        showDualTabSwitchProviderDialog()
                    }

                    3 -> {

                        if (profile != null) {

                            ProviderProfileDialogs.showEditProfileDialog(

                                context = requireContext(),

                                scope = viewLifecycleOwner.lifecycleScope,

                                profile = profile,

                                isNew = false,

                                onProfileSaved = { updated ->

                                    aiAgent.refreshRegisteredProfiles()

                                    aiAgent.setProvider(updated.id)

                                    refreshHeaderBar()

                                }

                            )

                        }

                    }

                    4 -> {

                        ProviderProfileDialogs.showAddFromTemplateDialog(

                            context = requireContext(),

                            scope = viewLifecycleOwner.lifecycleScope

                        ) { created ->

                            aiAgent.refreshRegisteredProfiles()

                            agentsHelper.setProvider(created.id)

                            aiAgent.setProvider(created.id)

                            refreshHeaderBar()

                        }

                    }

                }

            }
            .show()
    }

    private fun resolveAttrColor(ctx: Context, attr: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) {
                androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId)
            } else {
                tv.data
            }
        } else {
            fallback
        }
    }

    private fun showDualTabSwitchProviderDialog() {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val pad = (14 * density).toInt()

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (10 * density).toInt(), pad, (8 * density).toInt())
        }

        val tabLayout = com.google.android.material.tabs.TabLayout(context).apply {
            tabMode = com.google.android.material.tabs.TabLayout.MODE_FIXED
            tabGravity = com.google.android.material.tabs.TabLayout.GRAVITY_FILL
            setSelectedTabIndicatorColor(resolveAttrColor(context, android.R.attr.colorPrimary, Color.CYAN))
            setTabTextColors(
                resolveAttrColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY),
                resolveAttrColor(context, android.R.attr.colorPrimary, Color.CYAN)
            )
            addTab(newTab().setText("🔑 API Providers"))
            addTab(newTab().setText("👤 Account Login"))
        }
        rootLayout.addView(tabLayout)

        val container = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (380 * density).toInt()
            )
        }
        rootLayout.addView(container)

        var dialog: androidx.appcompat.app.AlertDialog? = null

        fun renderApiTab(): View {
            val scroll = ScrollView(context).apply { isFillViewport = true }
            val list = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            }
            scroll.addView(list)

            val profiles = registryManager.getAllProfiles().filter { it.enabled }
            val currentProv = agentsHelper.getProvider()

            for (p in profiles) {
                val hasKey = registryManager.hasProviderApiKey(p.id) || p.authStyle.name == "NONE"
                val isActive = p.id.equals(currentProv, ignoreCase = true)

                val card = MaterialCardView(context).apply {
                    radius = 12 * density
                    strokeWidth = (1 * density).toInt()
                    strokeColor = if (isActive) {
                        resolveAttrColor(context, android.R.attr.colorPrimary, Color.CYAN)
                    } else {
                        resolveAttrColor(context, com.google.android.material.R.attr.colorOutlineVariant, Color.DKGRAY)
                    }
                    setCardBackgroundColor(resolveAttrColor(context, com.google.android.material.R.attr.colorSurfaceContainer, Color.parseColor("#1E222A")))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (8 * density).toInt()
                    }
                    isClickable = true
                    isFocusable = true
                }

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                }

                val iconTv = TextView(context).apply {
                    text = when {
                        p.id.contains("gemini") -> "🌐"
                        p.id.contains("claude") || p.id.contains("anthropic") -> "🧠"
                        p.id.contains("openai") || p.id.contains("chatgpt") -> "✨"
                        p.id.contains("deepseek") -> "🐳"
                        p.id.contains("openrouter") -> "🔀"
                        p.id.contains("ollama") || p.id.contains("local") -> "💻"
                        else -> "⚡"
                    }
                    textSize = 20f
                    setPadding(0, 0, (10 * density).toInt(), 0)
                }
                row.addView(iconTv)

                val info = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val title = TextView(context).apply {
                    text = p.displayName
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(resolveAttrColor(context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE))
                }
                info.addView(title)

                val sub = TextView(context).apply {
                    val statusText = when {
                        isActive -> "● Active in Chat"
                        hasKey -> "✓ Configured & Ready"
                        else -> "⚠️ API Key Required"
                    }
                    text = "$statusText • Model: ${p.selectedModelId}"
                    textSize = 11.5f
                    setTextColor(
                        if (isActive) resolveAttrColor(context, android.R.attr.colorPrimary, Color.CYAN)
                        else if (hasKey) Color.parseColor("#4CAF50")
                        else Color.parseColor("#FFA726")
                    )
                }
                info.addView(sub)
                row.addView(info)

                val actionBtn = MaterialButton(context).apply {
                    text = if (isActive) "Active" else "Switch"
                    isEnabled = !isActive
                    textSize = 11.5f
                    cornerRadius = (8 * density).toInt()
                    setOnClickListener {
                        dialog?.dismiss()
                        agentsHelper.setProvider(p.id)
                        aiAgent.setProvider(p.id)
                        if (p.selectedModelId.isNotBlank()) {
                            agentsHelper.setAgent(p.selectedModelId)
                        }
                        aiAgent.reinitializeWithSelectedModel()
                        updateVisionButtonState()
                        updateReasoningEffortButton()
                        refreshHeaderBar()
                        showSnackbar("Switched to ${p.displayName} (${p.selectedModelId})")
                    }
                }
                row.addView(actionBtn)

                card.addView(row)
                card.setOnClickListener {
                    actionBtn.performClick()
                }
                list.addView(card)
            }
            return scroll
        }

        fun renderAccountTab(): View {
            val scroll = ScrollView(context).apply { isFillViewport = true }
            val list = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            }
            scroll.addView(list)

            val registry = com.tom.rv2ide.artificial.auth.OAuthAccountRegistry.getInstance(context)
            val currentProv = agentsHelper.getProvider()

            data class AccountProviderMeta(
                val providerId: String,
                val displayName: String,
                val emoji: String,
                val defaultModel: String
            )

            val accountProviders = listOf(
                AccountProviderMeta("openai", "ChatGPT", "✨", "gpt-4o"),
                AccountProviderMeta("grok", "Grok", "⚡", "grok-2-latest"),
                AccountProviderMeta("claude", "Claude", "🧠", "claude-3-7-sonnet-20250219"),
                AccountProviderMeta("kimi", "Kimi Code", "🌙", "kimi-k1.5-preview"),
                AccountProviderMeta("gemini", "Google Gemini", "🌐", "gemini-2.5-flash")
            )

            for (meta in accountProviders) {
                val accs = registry.getAccounts(meta.providerId)
                val active = registry.getActiveAccount(meta.providerId)
                val isProviderActive = meta.providerId.equals(currentProv, ignoreCase = true)

                val card = MaterialCardView(context).apply {
                    radius = 12 * density
                    strokeWidth = (1 * density).toInt()
                    strokeColor = if (isProviderActive) {
                        resolveAttrColor(context, android.R.attr.colorPrimary, Color.CYAN)
                    } else {
                        resolveAttrColor(context, com.google.android.material.R.attr.colorOutlineVariant, Color.DKGRAY)
                    }
                    setCardBackgroundColor(resolveAttrColor(context, com.google.android.material.R.attr.colorSurfaceContainer, Color.parseColor("#1E222A")))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (8 * density).toInt()
                    }
                    isClickable = true
                    isFocusable = true
                }

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                }

                val iconTv = TextView(context).apply {
                    text = meta.emoji
                    textSize = 20f
                    setPadding(0, 0, (10 * density).toInt(), 0)
                }
                row.addView(iconTv)

                val info = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val title = TextView(context).apply {
                    text = meta.displayName
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(resolveAttrColor(context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE))
                }
                info.addView(title)

                val sub = TextView(context).apply {
                    text = when {
                        isProviderActive && active != null -> "● Active: ${active.email.ifBlank { active.plan }} (${accs.size} connected)"
                        active != null -> "✓ Logged In: ${active.email.ifBlank { active.plan }} (${accs.size} account${if (accs.size > 1) "s" else ""})"
                        else -> "➕ Tap to login / connect account"
                    }
                    textSize = 11.5f
                    setTextColor(
                        if (isProviderActive) resolveAttrColor(context, android.R.attr.colorPrimary, Color.CYAN)
                        else if (active != null) Color.parseColor("#4CAF50")
                        else Color.parseColor("#90CAF9")
                    )
                }
                info.addView(sub)
                row.addView(info)

                val actionBtn = MaterialButton(context).apply {
                    text = when {
                        isProviderActive -> "Active"
                        active != null -> "Switch"
                        else -> "Login"
                    }
                    isEnabled = !isProviderActive
                    textSize = 11.5f
                    cornerRadius = (8 * density).toInt()
                    setOnClickListener {
                        if (active != null) {
                            dialog?.dismiss()
                            agentsHelper.setProvider(meta.providerId)
                            aiAgent.setProvider(meta.providerId)
                            val prof = registryManager.getProfile(meta.providerId)
                            val model = prof?.selectedModelId?.takeIf { it.isNotBlank() } ?: meta.defaultModel
                            agentsHelper.setAgent(model)
                            aiAgent.reinitializeWithSelectedModel()
                            updateVisionButtonState()
                            updateReasoningEffortButton()
                            refreshHeaderBar()
                            showSnackbar("Switched to ${meta.displayName} ($model)")
                        } else {
                            dialog?.dismiss()
                            com.tom.rv2ide.artificial.auth.ProviderOAuthManager.showAccountLoginDialog(
                                context,
                                viewLifecycleOwner.lifecycleScope,
                                meta.providerId
                            ) { connectedProv ->
                                aiAgent.refreshRegisteredProfiles()
                                agentsHelper.setProvider(connectedProv)
                                aiAgent.setProvider(connectedProv)
                                val p = registryManager.getProfile(connectedProv)
                                val mod = p?.selectedModelId?.takeIf { it.isNotBlank() } ?: meta.defaultModel
                                agentsHelper.setAgent(mod)
                                aiAgent.reinitializeWithSelectedModel()
                                updateVisionButtonState()
                                updateReasoningEffortButton()
                                refreshHeaderBar()
                                showSnackbar("Logged in & switched to $connectedProv ($mod)")
                            }
                        }
                    }
                }
                row.addView(actionBtn)

                card.addView(row)
                card.setOnClickListener { actionBtn.performClick() }
                list.addView(card)
            }

            val manageBtn = MaterialButton(context).apply {
                text = "⚙️ Manage Accounts & Multi-Account Fallback"
                textSize = 12f
                cornerRadius = (8 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (10 * density).toInt()
                }
                setOnClickListener {
                    dialog?.dismiss()
                    com.tom.rv2ide.artificial.auth.ProviderOAuthManager.showOAuthAccountsHubDialog(
                        context,
                        viewLifecycleOwner.lifecycleScope
                    ) { connected ->
                        if (connected.isNotBlank()) {
                            agentsHelper.setProvider(connected)
                            aiAgent.setProvider(connected)
                            aiAgent.reinitializeWithSelectedModel()
                            updateVisionButtonState()
                            updateReasoningEffortButton()
                            refreshHeaderBar()
                            showSnackbar("Switched to $connected")
                        }
                    }
                }
            }
            list.addView(manageBtn)

            return scroll
        }

        container.removeAllViews()
        container.addView(renderApiTab())

        tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                container.removeAllViews()
                if (tab?.position == 0) {
                    container.addView(renderApiTab())
                } else {
                    container.addView(renderAccountTab())
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Switch AI Provider")
            .setView(rootLayout)
            .setNegativeButton("Close", null)
            .show()
    }



    private fun renderCitations(citations: List<CitationItem>) {

        citationsContainer.removeAllViews()

        if (citations.isEmpty()) {

            citationsCard.visibility = View.GONE

            return

        }



        citationsCard.visibility = View.VISIBLE

        val pad = (6 * resources.displayMetrics.density).toInt()



        citations.forEachIndexed { index, item ->

            val itemView = TextView(requireContext()).apply {

                val snippetPart = if (item.snippet.isNotBlank()) "\n${item.snippet.take(140)}" else ""

                text = "[${index + 1}] ${item.title}\n🔗 ${item.url}$snippetPart"

                textSize = 12f

                setPadding(0, pad, 0, pad)

                setOnClickListener {

                    try {

                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url))

                        startActivity(intent)

                    } catch (_: Exception) {

                        showSnackbar("Cannot open URL: ${item.url}")

                    }

                }

            }

            citationsContainer.addView(itemView)

        }

    }



    private fun registerPreferenceListener() {

        val prefs = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)

        prefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)

    }



    private fun unregisterPreferenceListener() {

        val prefs = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)

        prefs.unregisterOnSharedPreferenceChangeListener(sharedPrefsListener)

    }



    private suspend fun handleCompletionStateChange(enabled: Boolean) {

        if (enabled) {

            delay(200)

            val editor = getCurrentEditor()

            val suggestionView = getCurrentSuggestionView()



            if (editor != null && suggestionView != null) {

                setupCodeCompletionForCurrentFile()

            }

        } else {

            codeCompletionManager.cleanup()

        }

    }



    private fun startCompletionStateMonitoring() {

        stopCompletionStateMonitoring()



        completionStateMonitorJob = lifecycleScope.launch {

            var lastKnownState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)

                .getBoolean("code_completion_enabled", true)



            while (true) {

                delay(200)



                val currentState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)

                    .getBoolean("code_completion_enabled", true)



                if (currentState != lastKnownState) {

                    lastKnownState = currentState

                    handleCompletionStateChange(currentState)

                }

            }

        }

    }



    private fun stopCompletionStateMonitoring() {

        completionStateMonitorJob?.cancel()

        completionStateMonitorJob = null

    }



    private fun loadProject() {

        lifecycleScope.launch {

            try {

                val rootPath = userRootProject

                if (rootPath.isNotBlank()) {

                    val rootDir = File(rootPath)

                    aiAgent.setProjectRoot(rootPath)

                    com.tom.rv2ide.builder.SharedBuildOrchestrator.setActiveProjectDir(rootDir)

                }

            } catch (_: Exception) {

            }

        }

    }



    private fun startFileMonitoring() {

        stopFileMonitoring()



        fileMonitorJob = lifecycleScope.launch {

            while (true) {

                delay(500)



                if (isSettingUpCompletion) {

                    continue

                }



                val prefs = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)

                val isEnabled = prefs.getBoolean("code_completion_enabled", true)



                if (!isEnabled) {

                    continue

                }



                val currentFile = getCurrentFile()



                if (currentFile != null && currentFile != lastMonitoredFile) {

                    lastMonitoredFile = currentFile

                    setupCodeCompletionForCurrentFile()

                }

            }

        }

    }



    private fun stopFileMonitoring() {

        fileMonitorJob?.cancel()

        fileMonitorJob = null

    }



    private fun setupCodeCompletionForCurrentFile() {

        if (isSettingUpCompletion) return



        val prefs = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)

        val isEnabled = prefs.getBoolean("code_completion_enabled", true)

        if (!isEnabled) return



        isSettingUpCompletion = true



        lifecycleScope.launch {

            delay(200)



            val editor = getCurrentEditor()

            val suggestionView = getCurrentSuggestionView()



            if (editor != null && suggestionView != null) {

                codeCompletionManager.setup(

                    editor,

                    suggestionView,

                    onReady = { isSettingUpCompletion = false },

                    onError = { isSettingUpCompletion = false }

                )

            } else {

                isSettingUpCompletion = false

            }

        }

    }



    fun getCodeCompletionManager(): CodeCompletionManager {

        return codeCompletionManager

    }



    private fun openFileInEditor(filePathOrName: String) {

        if (userRootProject.isBlank()) {

            showSnackbar("Project path not set")

            return

        }



        lifecycleScope.launch {

            try {

                val directFile = File(filePathOrName)

                val file = if (directFile.exists() && directFile.isFile) {

                    directFile

                } else {

                    findFileInProject(File(userRootProject), File(filePathOrName).name)

                }

                if (file == null) {

                    showSnackbar("File not found: $filePathOrName")

                    return@launch

                }



                val activity = requireActivity()

                if (activity is EditorHandlerActivity) {

                    activity.openFile(file)

                    showSnackbar("Opened: ${file.name}")



                    lastMonitoredFile = file

                    delay(500)

                    setupCodeCompletionForCurrentFile()

                }

            } catch (e: Exception) {

                showSnackbar("Error opening file: ${e.message}")

            }

        }

    }



    private fun findFileInProject(projectRoot: File, fileName: String): File? {

        if (!projectRoot.exists() || !projectRoot.isDirectory) {

            return null

        }



        return projectRoot.walkTopDown().firstOrNull {

            it.isFile && it.name == fileName

        }

    }



    private fun showReviewChangesDialog(

        messageId: String,

        sessionId: String,

        files: List<String>

    ) {

        val projectRoot = userRootProject.takeIf { it.isNotBlank() }?.let { File(it) } ?: run {

            showSnackbar("Project path not set")

            return

        }



        val journal = com.tom.rv2ide.artificial.coding.AutonomousRollbackManager.getJournal(sessionId, projectRoot)

        if (journal == null || journal.entries.isEmpty()) {

            showSnackbar("No rollback journal found for this session.")

            return

        }



        val conflicts = com.tom.rv2ide.artificial.coding.AutonomousRollbackManager.checkConflicts(sessionId, projectRoot)

        val conflictMap = conflicts.associateBy { it.relativePath }



        val dialogView = layoutInflater.inflate(R.layout.dialog_review_changes, null)

        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvReviewSubtitle)

        val cardConflict = dialogView.findViewById<View>(R.id.cardReviewConflictWarning)

        val tvConflictDetail = dialogView.findViewById<TextView>(R.id.tvReviewConflictDetail)

        val layoutChips = dialogView.findViewById<LinearLayout>(R.id.layoutReviewFileChips)

        val tvFilePath = dialogView.findViewById<TextView>(R.id.tvReviewFilePath)

        val tvOpBadge = dialogView.findViewById<TextView>(R.id.tvReviewOperationBadge)

        val tvLineStats = dialogView.findViewById<TextView>(R.id.tvReviewLineStats)

        val tvConflictStatus = dialogView.findViewById<TextView>(R.id.tvReviewConflictStatus)

        val tvDiff = dialogView.findViewById<TextView>(R.id.tvReviewDiffContent)
        val scrollReviewDiff = dialogView.findViewById<View>(R.id.scrollReviewDiff)
        val dm = resources.displayMetrics
        val maxReviewDiffPx = minOf((220 * dm.density).toInt(), (dm.heightPixels * 0.35).toInt())
        (scrollReviewDiff as? com.tom.rv2ide.ui.MaxHeightNestedScrollView)?.maxHeightPx = maxReviewDiffPx



        tvSubtitle?.text = "Session: ${sessionId.take(12)} • ${journal.entries.size} file(s) recorded"



        if (conflicts.isNotEmpty()) {

            cardConflict?.visibility = View.VISIBLE

            tvConflictDetail?.text = "${conflicts.size} file(s) have external user edits and will be protected from undo if reverted."

        } else {

            cardConflict?.visibility = View.GONE

        }



        val entriesList = journal.entries.values.toList()



        fun renderFile(idx: Int) {

            val entry = entriesList[idx]

            val conflict = conflictMap[entry.relativePath]



            tvFilePath?.text = entry.relativePath

            tvOpBadge?.text = entry.operation.name

            when (entry.operation) {

                com.tom.rv2ide.artificial.coding.RollbackOperation.CREATED -> {

                    tvOpBadge?.setBackgroundResource(com.tom.rv2ide.resources.R.drawable.bg_count_pill_accent)

                    tvOpBadge?.setTextColor(Color.BLACK)

                }

                com.tom.rv2ide.artificial.coding.RollbackOperation.MODIFIED -> {

                    tvOpBadge?.setBackgroundResource(com.tom.rv2ide.resources.R.drawable.bg_count_pill_warning)

                    tvOpBadge?.setTextColor(Color.BLACK)

                }

                com.tom.rv2ide.artificial.coding.RollbackOperation.DELETED -> {

                    tvOpBadge?.setBackgroundResource(com.tom.rv2ide.resources.R.drawable.bg_count_pill_error)

                    tvOpBadge?.setTextColor(Color.WHITE)

                }

            }



            if (conflict != null) {

                tvConflictStatus?.text = "⚠️ Conflict: ${conflict.reason.description}"

                tvConflictStatus?.setTextColor(Color.parseColor("#E57373"))

            } else {

                tvConflictStatus?.text = "✓ In sync with AI output (Safe to revert)"

                tvConflictStatus?.setTextColor(Color.parseColor("#81C784"))

            }



            val diff = com.tom.rv2ide.artificial.diff.DiffEngine.computeDiff(

                relativePath = entry.relativePath,

                originalContent = entry.originalContent,

                newContent = entry.latestWrittenContent

            )

            tvLineStats?.text = "+${diff.linesAdded}  -${diff.linesRemoved}"

            tvDiff?.text = com.tom.rv2ide.artificial.diff.DiffVisualFormatter.formatDiffSpannable(diff, requireContext(), onlyHunks = false)



            if (layoutChips != null) {

                for (i in 0 until layoutChips.childCount) {

                    val chip = layoutChips.getChildAt(i) as? TextView ?: continue

                    val isConflicted = conflictMap.containsKey(entriesList[i].relativePath)

                    if (i == idx) {

                        chip.setTextColor(Color.WHITE)

                        chip.background = android.graphics.drawable.GradientDrawable().apply {

                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE

                            cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics)

                            setColor(Color.parseColor("#00838F"))

                        }

                    } else if (isConflicted) {

                        chip.setTextColor(Color.parseColor("#FF8A80"))

                        chip.background = android.graphics.drawable.GradientDrawable().apply {

                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE

                            cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics)

                            setColor(Color.argb(40, 255, 138, 128))

                        }

                    } else {

                        chip.setTextColor(Color.parseColor("#90A4AE"))

                        chip.background = android.graphics.drawable.GradientDrawable().apply {

                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE

                            cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics)

                            setColor(Color.argb(30, 144, 164, 174))

                        }

                    }

                }

            }

        }



        if (layoutChips != null) {

            layoutChips.removeAllViews()

            entriesList.forEachIndexed { idx, entry ->

                val isConflicted = conflictMap.containsKey(entry.relativePath)

                val chip = TextView(requireContext()).apply {

                    text = (if (isConflicted) "⚠️ " else "") + File(entry.relativePath).name

                    textSize = 11f

                    val px10 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics).toInt()

                    val px4 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt()

                    setPadding(px10, px4, px10, px4)

                    val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {

                        rightMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()

                    }

                    layoutParams = params

                    setOnClickListener { renderFile(idx) }

                }

                layoutChips.addView(chip)

            }

        }



        if (entriesList.isNotEmpty()) {

            renderFile(0)

        }



        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())

            .setView(dialogView)

            .setCancelable(true)

            .setPositiveButton("↩ Revert AI Changes") { d, _ ->

                d.dismiss()

                showRollbackConfirmationDialog(messageId, sessionId, files)

            }

            .setNegativeButton("Keep Changes / Close", null)

            .show()

    }



    private fun showRollbackConfirmationDialog(

        messageId: String,

        sessionId: String,

        files: List<String>

    ) {

        val projectRoot = userRootProject.takeIf { it.isNotBlank() }?.let { File(it) } ?: run {

            showSnackbar("Project path not set")

            return

        }



        val messageText = buildString {

            appendLine("This will revert changes made to ${files.size} file(s) by this agent run to their state before the run began:")

            appendLine()

            files.take(8).forEach { appendLine("  • $it") }

            if (files.size > 8) {

                appendLine("  ... and ${files.size - 8} more")

            }

            appendLine()

            append("Subsequent manual edits made after the agent ran will be detected as conflicts and preserved untouched.")

        }



        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())

            .setTitle("Revert AI Agent Changes")

            .setMessage(messageText)

            .setPositiveButton("Revert Changes") { dialog, _ ->

                dialog.dismiss()

                lifecycleScope.launch(Dispatchers.IO) {

                    val result = com.tom.rv2ide.artificial.coding.AutonomousRollbackManager.rollbackSession(

                        sessionId = sessionId,

                        projectRootOverride = projectRoot

                    )

                    withContext(Dispatchers.Main) {

                        if (!isAdded) return@withContext

                        if (result.revertedCount > 0) {

                            sessionManager.markSessionRolledBack(messageId)

                            refreshCurrentEditor()

                        }



                        val outcomeMsg = buildString {

                            appendLine(result.message)

                            if (result.conflicts.isNotEmpty()) {

                                appendLine()

                                appendLine("Preserved Conflicting Files:")

                                result.conflicts.forEach { c ->

                                    appendLine("  • ${c.relativePath}: ${c.reason.description}")

                                }

                            }

                        }.trim()



                        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())

                            .setTitle(if (result.success) "Rollback Completed" else "Rollback Report")

                            .setMessage(outcomeMsg)

                            .setPositiveButton("OK", null)

                            .show()

                    }

                }

            }

            .setNeutralButton("🔍 Review Diff First") { d, _ ->

                d.dismiss()

                showReviewChangesDialog(messageId, sessionId, files)

            }

            .setNegativeButton("Keep Changes", null)

            .show()

    }





    private fun typeText(text: String, delayMs: Long = 10L) {

        typingJob?.cancel()

        typingJob = lifecycleScope.launch {

            try {

                val editor = getCurrentEditor() ?: return@launch

                val lines = text.lines()

                val currentText = StringBuilder()



                for (line in lines) {

                    val words = line.split(" ")

                    for (i in words.indices) {

                        currentText.append(words[i])

                        if (i < words.size - 1) {

                            currentText.append(" ")

                        }

                        editor.setText(currentText.toString())

                        delay(delayMs)

                    }

                    currentText.append("\n")

                    editor.setText(currentText.toString())

                }

            } catch (_: Exception) {

            }

        }

    }



    fun clearConversation() {

        lifecycleScope.launch {

            try {

                typingJob?.cancel()

                aiRequestHandler.cancel()

                codeCompletionManager.clearSuggestion()

                aiAgent.clearConversation()

                sessionManager.clearActiveConversation(userRootProject)



                isRestoringDraft = true

                promptInput.text?.clear()

                isRestoringDraft = false

                sessionManager.saveDraftText("", userRootProject)



                toolStatusBanner.visibility = View.GONE

                statusText.text = "Conversation cleared. Ready for new request."

                fileModificationList.visibility = View.GONE

                citationsCard.visibility = View.GONE

                citationsContainer.removeAllViews()

                summaryCard.visibility = View.GONE

                fileModificationAdapter.clear()



                renderConversationState(sessionManager.getActiveConversation(userRootProject))

                showSnackbar("Conversation cleared")

            } catch (e: Exception) {

                showSnackbar("Error clearing: ${e.message}")

            }

        }

    }



    private fun getCurrentEditor() = try {

        val activity = requireActivity()

        if (activity is EditorHandlerActivity) {

            activity.getCurrentEditor()?.editor

        } else {

            null

        }

    } catch (_: Exception) {

        null

    }



    private fun getCurrentFile() = try {

        val activity = requireActivity()

        if (activity is EditorHandlerActivity) {

            activity.getCurrentEditor()?.file

        } else null

    } catch (_: Exception) {

        null

    }



    private fun getCurrentSuggestionView() = try {

        val activity = requireActivity()

        if (activity is EditorHandlerActivity) {

            activity.getCurrentEditor()?.suggestionView

        } else {

            null

        }

    } catch (_: Exception) {

        null

    }



    private fun refreshCurrentEditor() {

        try {

            val activity = requireActivity()

            if (activity is EditorHandlerActivity) {

                val currentEditor = activity.getCurrentEditor()

                val file = currentEditor?.file

                val newContent = file?.readText()

                val editorText = currentEditor?.editor?.text



                if (editorText != null && newContent != null) {

                    editorText.replace(0, editorText.length, newContent)

                }

            }

        } catch (_: Exception) {

        }

    }



    private fun showSnackbar(message: String) {

        val anchorView = activity?.findViewById<View>(android.R.id.content)

            ?: view

            ?: return



        Snackbar.make(anchorView, message, Snackbar.LENGTH_SHORT).show()

    }



    override fun onDestroyView() {

        sessionManager.removeListener(sessionListener)

        typingJob?.cancel()

        fileMonitorJob?.cancel()

        completionStateMonitorJob?.cancel()

        unregisterPreferenceListener()

        super.onDestroyView()

    }

}

