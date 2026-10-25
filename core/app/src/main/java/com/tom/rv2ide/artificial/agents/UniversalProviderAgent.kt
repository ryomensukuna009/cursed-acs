package com.tom.rv2ide.artificial.agents

import android.content.Context
import com.tom.rv2ide.artificial.coding.AcsRepoMap
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.providers.AuthStyle
import com.tom.rv2ide.artificial.providers.ProviderProfile
import com.tom.rv2ide.artificial.providers.ProviderRegistryManager
import com.tom.rv2ide.artificial.rules.WritingRules
import com.tom.rv2ide.artificial.secrets.SecureKeyStore
import com.tom.rv2ide.artificial.tools.SafeUrlFetcher
import com.tom.rv2ide.artificial.tools.ToolOrchestrator
import com.tom.rv2ide.artificial.tools.WebSearchManager
import com.tom.rv2ide.artificial.transport.ChatRole
import com.tom.rv2ide.artificial.transport.CitationItem
import com.tom.rv2ide.artificial.transport.StreamEvent
import com.tom.rv2ide.artificial.transport.TransportCancellationToken
import com.tom.rv2ide.artificial.transport.UnifiedChatMessage
import com.tom.rv2ide.artificial.transport.UnifiedChatRequest
import com.tom.rv2ide.artificial.transport.UnifiedChatResponse
import com.tom.rv2ide.artificial.transport.UnifiedTransportClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

interface StreamingToolCapableAgent : AIAgent {
    suspend fun generateStreamingChat(
        prompt: String,
        context: String? = null,
        language: String = "kotlin",
        projectStructure: String? = null,
        explicitWebSearch: Boolean = false,
        onEvent: (StreamEvent) -> Unit = {}
    ): Result<UnifiedChatResponse>

    fun cancelActiveRequest()
    fun getLastCitations(): List<CitationItem>
    fun getLastInspectedFiles(): List<String> = emptyList()
    fun getLastRollbackSessionId(): String? = null
    fun getLastModifiedFiles(): List<String> = emptyList()
    fun getLastTokenUsage(): com.tom.rv2ide.artificial.transport.TokenUsage? = null
    fun getCumulativeTokenUsage(): com.tom.rv2ide.artificial.transport.TokenUsage = com.tom.rv2ide.artificial.transport.TokenUsage()
    fun getLastCompletionReport(): com.tom.rv2ide.artificial.chat.AgentCompletionReport? = null
}

class UniversalProviderAgent(
    override val providerId: String,
    private val appContext: Context,
    private val registryManager: ProviderRegistryManager = ProviderRegistryManager.getInstance(appContext),
    private val transportClient: UnifiedTransportClient = UnifiedTransportClient(),
    private val webSearchManager: WebSearchManager = WebSearchManager(appContext),
    private val safeUrlFetcher: SafeUrlFetcher = SafeUrlFetcher()
) : StreamingToolCapableAgent {

    private var apiKey: String? = null
    private val writingRules = WritingRules.Instructions()
    private var projectTreeResult: ProjectTreeResult? = null
    private var fileWriter: AIFileWriter? = AIFileWriter(appContext)
    private val conversationHistory = mutableListOf<UnifiedChatMessage>()
    private val modificationHistory = mutableListOf<ModificationAttempt>()
    private var currentAttemptCount = 0
    private val maxRetryAttempts = 3
    private val activeCancellationToken = AtomicReference<TransportCancellationToken?>(null)

    @Volatile
    private var lastCitations: List<CitationItem> = emptyList()

    @Volatile
    private var lastInspectedFiles: List<String> = emptyList()

    @Volatile
    private var lastRollbackSessionId: String? = null

    @Volatile
    private var lastModifiedFiles: List<String> = emptyList()

    @Volatile
    private var lastTokenUsage: com.tom.rv2ide.artificial.transport.TokenUsage? = null

    @Volatile
    private var cumulativeTokenUsage: com.tom.rv2ide.artificial.transport.TokenUsage = com.tom.rv2ide.artificial.transport.TokenUsage()

    @Volatile
    private var lastCompletionReport: com.tom.rv2ide.artificial.chat.AgentCompletionReport? = null

    private val toolOrchestrator by lazy {
        ToolOrchestrator(
            transportClient = transportClient,
            webSearchManager = webSearchManager,
            safeUrlFetcher = safeUrlFetcher
        )
    }

    override val providerName: String
        get() = registryManager.getProfile(providerId)?.displayName ?: providerId

    override fun initialize(apiKey: String, context: Context) {
        this.apiKey = apiKey
        this.fileWriter = AIFileWriter(context)
        val agents = Agents(context)
        val currentModel = agents.getAgent()
        val profile = registryManager.getProfile(providerId)
        if (profile != null && currentModel.isNotBlank()) {
            // Preserve user's selected or manual model
            if (profile.selectedModelId.isBlank()) {
                registryManager.selectModelForProvider(providerId, currentModel)
            }
        }
    }

    override fun reinitializeWithNewModel(apiKey: String, context: Context) {
        initialize(apiKey, context)
    }

    override fun setContext(context: Context) {
        fileWriter = AIFileWriter(context)
    }

    override fun setProjectData(projectTreeResult: ProjectTreeResult) {
        this.projectTreeResult = projectTreeResult
    }

    override fun clearConversation() {
        conversationHistory.clear()
        modificationHistory.clear()
        lastCitations = emptyList()
        currentAttemptCount = 0
    }

    override fun recordModification(
        filePath: String,
        oldContent: String?,
        newContent: String,
        success: Boolean
    ) {
        modificationHistory.add(
            ModificationAttempt(
                timestamp = System.currentTimeMillis(),
                filePath = filePath,
                previousContent = oldContent,
                newContent = newContent,
                attemptNumber = currentAttemptCount,
                success = success
            )
        )
    }

    override fun undoLastModification(): Boolean {
        if (modificationHistory.isEmpty()) return false
        val lastMod = modificationHistory.lastOrNull { it.success } ?: return false

        if (lastMod.previousContent != null) {
            val result = writeFile(lastMod.filePath, lastMod.previousContent)
            if (result is FileWriteResult.Success) {
                modificationHistory.removeAt(modificationHistory.lastIndexOf(lastMod))
                return true
            }
        } else {
            try {
                File(lastMod.filePath).delete()
                modificationHistory.removeAt(modificationHistory.lastIndexOf(lastMod))
                return true
            } catch (_: Exception) {
                return false
            }
        }
        return false
    }

    override fun getModificationHistory(): List<ModificationAttempt> {
        return modificationHistory.toList()
    }

    override fun resetAttemptCount() {
        currentAttemptCount = 0
    }

    override fun incrementAttemptCount() {
        currentAttemptCount++
    }

    override fun getCurrentAttemptCount(): Int = currentAttemptCount

    override fun canRetry(): Boolean = currentAttemptCount < maxRetryAttempts

    override fun writeFile(filePath: String, content: String): FileWriteResult {
        val writer = fileWriter ?: return FileWriteResult.Error("File writer not initialized")
        return writer.writeFile(filePath, content, createBackup = true)
    }

    override fun isInitialized(): Boolean {
        val profile = registryManager.getProfile(providerId) ?: return !apiKey.isNullOrBlank()
        if (profile.authStyle == AuthStyle.NONE) {
            return profile.baseUrl.isNotBlank()
        }
        val effectiveKey = apiKey?.takeIf { it.isNotBlank() }
            ?: SecureKeyStore.getProviderApiKey(appContext, providerId)
        return !effectiveKey.isNullOrBlank()
    }

    override fun cancelActiveRequest() {
        activeCancellationToken.getAndSet(null)?.cancel()
    }

    override fun getLastCitations(): List<CitationItem> = lastCitations

    override fun getLastInspectedFiles(): List<String> = lastInspectedFiles
    override fun getLastRollbackSessionId(): String? = lastRollbackSessionId
    override fun getLastModifiedFiles(): List<String> = lastModifiedFiles
    override fun getLastTokenUsage(): com.tom.rv2ide.artificial.transport.TokenUsage? = lastTokenUsage
    override fun getCumulativeTokenUsage(): com.tom.rv2ide.artificial.transport.TokenUsage = cumulativeTokenUsage
    override fun getLastCompletionReport(): com.tom.rv2ide.artificial.chat.AgentCompletionReport? = lastCompletionReport

    override suspend fun generateCode(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?
    ): Result<String> {
        // Fast path for inline code completion requests
        if (isInlineCompletionPrompt(prompt)) {
            return executeInlineCompletion(prompt)
        }

        val chatResult = generateStreamingChat(
            prompt = prompt,
            context = context,
            language = language,
            projectStructure = projectStructure,
            explicitWebSearch = false,
            onEvent = {}
        )
        return chatResult.map { it.text }
    }

    override suspend fun generateStreamingChat(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?,
        explicitWebSearch: Boolean,
        onEvent: (StreamEvent) -> Unit
    ): Result<UnifiedChatResponse> = withContext(Dispatchers.IO) {
        val token = TransportCancellationToken()
        activeCancellationToken.set(token)
        lastCitations = emptyList()

        try {
            val profile = registryManager.getProfile(providerId)
                ?: return@withContext Result.failure(
                    IllegalStateException("Provider profile '$providerId' not found")
                )

            val effectiveKey = apiKey?.takeIf { it.isNotBlank() }
                ?: SecureKeyStore.getProviderApiKey(appContext, providerId)

            if (profile.authStyle != AuthStyle.NONE && effectiveKey.isNullOrBlank()) {
                return@withContext Result.failure(
                    com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException(
                        "API key is not configured for ${profile.displayName}."
                    )
                )
            }

            val agents = Agents(appContext)
            val modelId = resolveModelId(profile, agents)
            val capabilities = registryManager.getEffectiveModelCapabilities(providerId, modelId)

            val fullUserPrompt = buildFullProjectAwarePrompt(prompt, context)

            // Use persistent multi-turn conversation history from ConversationSessionManager
            val sessionMessages = try {
                val sessionMgr = com.tom.rv2ide.artificial.chat.ConversationSessionManager.getInstance(appContext)
                sessionMgr.buildModelRequestMessages(
                    conversationId = null,
                    latestUserPromptWithProjectContext = fullUserPrompt,
                    latestRawUserText = prompt,
                    maxContextTokens = capabilities.contextLength
                )
            } catch (_: Exception) {
                emptyList()
            }

            val requestMessages = if (sessionMessages.size > 1) {
                sessionMessages
            } else {
                buildList {
                    addAll(conversationHistory)
                    add(UnifiedChatMessage(role = ChatRole.USER, content = fullUserPrompt))
                }
            }

            val globalWebSearchEnabled = webSearchManager.getConfig().enabled
            val shouldUseWebSearch = explicitWebSearch || globalWebSearchEnabled
            val currentMode = com.tom.rv2ide.artificial.policy.AgentExecutionMode.getSavedMode(appContext)

            val response = toolOrchestrator.executeOrchestratedChat(
                profile = profile,
                modelId = modelId,
                modelCapabilities = capabilities,
                apiKey = effectiveKey,
                messages = requestMessages,
                systemPrompt = writingRules.useThis(),
                webSearchRequested = shouldUseWebSearch,
                forceSearchPrefetch = false,
                stream = profile.streamingEnabled,
                maxToolIterations = 15,
                temperature = 0.7,
                maxTokens = 4096,
                executionMode = currentMode,
                cancellationToken = token,
                onEvent = onEvent
            )

            if (response.text.isBlank() && response.toolCalls.isEmpty()) {
                return@withContext Result.failure(Exception("Empty response from ${profile.displayName}"))
            }

            lastCitations = response.citations
            lastRollbackSessionId = response.rollbackSessionId
            lastModifiedFiles = response.modifiedFiles
            lastTokenUsage = response.usage
            lastCompletionReport = response.completionReport
            response.usage?.let { cumulativeTokenUsage += it }

            conversationHistory.add(UnifiedChatMessage(role = ChatRole.USER, content = prompt))
            conversationHistory.add(UnifiedChatMessage(role = ChatRole.ASSISTANT, content = response.text))
            while (conversationHistory.size > 20) {
                conversationHistory.removeAt(0)
            }

            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            activeCancellationToken.compareAndSet(token, null)
        }
    }

    private suspend fun executeInlineCompletion(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val profile = registryManager.getProfile(providerId)
                ?: return@withContext Result.failure(IllegalStateException("Provider profile not found"))
            val effectiveKey = apiKey?.takeIf { it.isNotBlank() }
                ?: SecureKeyStore.getProviderApiKey(appContext, providerId)
            val agents = Agents(appContext)
            val modelId = resolveModelId(profile, agents)

            val req = UnifiedChatRequest(
                profile = profile,
                modelId = modelId,
                messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = prompt)),
                systemPrompt = "You are an inline code completion engine. Output only the code completion text with no markdown or explanations.",
                tools = emptyList(),
                enableGeminiNativeGrounding = false,
                stream = false,
                temperature = 0.2,
                maxTokens = 200
            )
            val resp = transportClient.executeChat(request = req, apiKey = effectiveKey)
            Result.success(resp.text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isInlineCompletionPrompt(prompt: String): Boolean {
        return prompt.startsWith("You are an intelligent code completion AI for ")
    }

    private fun resolveModelId(profile: ProviderProfile, agents: Agents): String {
        val manual = profile.manualModelIdOverride.trim()
        if (manual.isNotEmpty()) return manual

        val fromProfile = profile.selectedModelId.trim()
        val fromAgents = agents.getAgentForProvider(profile.id).trim()

        return when {
            fromAgents.isNotEmpty() -> fromAgents
            fromProfile.isNotEmpty() -> fromProfile
            else -> "gpt-4o"
        }
    }

    private fun buildFullProjectAwarePrompt(prompt: String, extraContext: String?): String {
        val fileContents = readRelevantFiles(prompt)
        val needsCorrection = isUserRequestingCorrection(prompt)

        return buildString {
            val compactTree = formatCompactProjectTree()
            if (compactTree.isNotBlank()) {
                append("=== PROJECT STRUCTURE (EXACT PATHS) ===\n")
                append(compactTree)
                append("\n\n")
            }

            val projectDir = com.tom.rv2ide.builder.SharedBuildOrchestrator.getActiveProjectDir()
            if (projectDir != null && projectDir.exists()) {
                val agentsMd = File(projectDir, "AGENTS.md").takeIf { it.isFile }
                    ?: File(projectDir, ".agents/rules.md").takeIf { it.isFile }
                if (agentsMd != null) {
                    val rulesContent = try {
                        agentsMd.readText().take(2000)
                    } catch (_: Exception) {
                        ""
                    }
                    if (rulesContent.isNotBlank()) {
                        append("=== PROJECT RULES (AGENTS.MD) ===\n")
                        append(rulesContent.trim())
                        append("\n\n")
                    }
                }

                val symbolMap = try {
                    AcsRepoMap.rankAndFormatMap(
                        projectRoot = projectDir,
                        userQuery = prompt,
                        maxTokens = 400
                    )
                } catch (_: Exception) {
                    ""
                }
                if (symbolMap.isNotBlank()) {
                    append(symbolMap)
                    append("\n\n")
                }
            }

            if (fileContents.isNotEmpty()) {
                append("=== RELEVANT FILES CONTENT ===\n")
                fileContents.forEach { (path, content) ->
                    append("FILE: $path\n")
                    append("CONTENT:\n")
                    append(content)
                    append("\n\n")
                }
            }

            if (extraContext != null) {
                append("=== ADDITIONAL CONTEXT ===\n")
                append(extraContext)
                append("\n\n")
            }

            if (needsCorrection && modificationHistory.isNotEmpty()) {
                append("=== CORRECTION REQUIRED ===\n")
                append("The user indicated the previous modification was WRONG.\n")
                append("Previous failed attempts:\n")
                modificationHistory.takeLast(3).forEach { attempt ->
                    append("Attempt ${attempt.attemptNumber}: ${attempt.filePath}\n")
                    append("Result: ${if (attempt.success) "Applied but user rejected" else "Failed"}\n\n")
                }
                append("You MUST try a DIFFERENT approach. Do NOT repeat the same solution.\n\n")
            }

            if (currentAttemptCount > 0) {
                append("=== RETRY ATTEMPT $currentAttemptCount/$maxRetryAttempts ===\n")
                append("Previous attempts did not satisfy the user. Provide a better solution.\n\n")
            }

            append("=== USER REQUEST ===\n")
            append(prompt)
        }
    }

    private fun formatCompactProjectTree(): String {
        val tree = projectTreeResult?.tree ?: return ""
        val lines = tree.lines().filter { 
            it.isNotBlank() && 
            !it.contains("/build/") && 
            !it.contains("/.gradle/") && 
            !it.contains("/.git/") &&
            !it.contains("/intermediates/")
        }
        if (lines.size <= 40) {
            return lines.joinToString("\n")
        }
        val top = lines.take(35).joinToString("\n")
        return "$top\n[... ${lines.size - 35} additional project files omitted. Use list_project_files or search_project tools to locate files ...]"
    }

    private fun isUserRequestingCorrection(message: String): Boolean {
        val correctionKeywords = listOf(
            "wrong", "not what", "mistake", "error", "incorrect",
            "that's not", "not right", "fix", "undo", "revert",
            "different", "try again", "not working"
        )
        return correctionKeywords.any { message.lowercase().contains(it) }
    }

    private fun readRelevantFiles(prompt: String): Map<String, String> {
        val filesContent = mutableMapOf<String, String>()
        val root = com.tom.rv2ide.builder.SharedBuildOrchestrator.getActiveProjectDir() ?: return filesContent
        if (!root.exists()) return filesContent

        // Extract filenames mentioned specifically in the prompt
        val words = prompt.split(Regex("[\\s,;:'\"()]+"))
        val candidateNames = words.filter { w ->
            w.contains('.') && (
                w.endsWith(".kt", ignoreCase = true) ||
                w.endsWith(".java", ignoreCase = true) ||
                w.endsWith(".xml", ignoreCase = true) ||
                w.endsWith(".c", ignoreCase = true) ||
                w.endsWith(".cpp", ignoreCase = true) ||
                w.endsWith(".h", ignoreCase = true) ||
                w.endsWith(".gradle", ignoreCase = true) ||
                w.endsWith(".kts", ignoreCase = true)
            )
        }.map { File(it).name }.toSet()

        if (candidateNames.isEmpty()) {
            lastInspectedFiles = emptyList()
            return filesContent
        }

        root.walkTopDown().maxDepth(6).forEach { file ->
            if (file.isFile && file.name in candidateNames &&
                !file.path.contains("/build/") &&
                !file.path.contains("/.gradle/") &&
                !file.path.contains("/intermediates/")
            ) {
                try {
                    val text = file.readText()
                    val capped = if (text.length > 4000) {
                        text.take(4000) + "\n...[truncated for initial context; use read_project_file to inspect full file]..."
                    } else {
                        text
                    }
                    filesContent[file.absolutePath] = capped
                } catch (_: Exception) {
                }
            }
        }
        lastInspectedFiles = filesContent.keys.map { File(it).name }
        return filesContent
    }

    companion object {
        fun registerForProfile(context: Context, profileId: String) {
            val appCtx = context.applicationContext ?: context
            AIAgentRegistry.register(
                profileId,
                object : AIAgentRegistry.AgentFactory {
                    override fun create(context: Context): AIAgent {
                        return UniversalProviderAgent(profileId, context.applicationContext ?: context)
                    }

                    override fun hasValidApiKey(): Boolean {
                        val reg = ProviderRegistryManager.getInstance(appCtx)
                        val profile = reg.getProfile(profileId) ?: return false
                        if (!profile.enabled) return false
                        if (profile.authStyle == AuthStyle.NONE) {
                            return profile.baseUrl.isNotBlank()
                        }
                        return SecureKeyStore.hasProviderApiKey(appCtx, profileId)
                    }

                    override fun getApiKey(): String? {
                        val reg = ProviderRegistryManager.getInstance(appCtx)
                        val profile = reg.getProfile(profileId)
                        if (profile?.authStyle == AuthStyle.NONE) {
                            return ""
                        }
                        return SecureKeyStore.getProviderApiKey(appCtx, profileId)
                    }
                }
            )
        }

        fun registerAllProfiles(context: Context) {
            val appCtx = context.applicationContext ?: context
            val reg = ProviderRegistryManager.getInstance(appCtx)
            for (profile in reg.getAllProfiles()) {
                registerForProfile(appCtx, profile.id)
            }
        }
    }
}
