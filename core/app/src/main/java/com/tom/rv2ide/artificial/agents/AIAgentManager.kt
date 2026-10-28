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

package com.tom.rv2ide.artificial.agents

import android.content.Context
import com.tom.rv2ide.artificial.agents.anthropic.Anthropic
import com.tom.rv2ide.artificial.agents.deepseek.DeepSeek
import com.tom.rv2ide.artificial.agents.google.Gemini
import com.tom.rv2ide.artificial.agents.grok.Grok
import com.tom.rv2ide.artificial.agents.local.LocalLLM
import com.tom.rv2ide.artificial.agents.openai.OpenAI
import com.tom.rv2ide.artificial.dialogs.ProviderSwitchDialog
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.parser.SnippetParser
import com.tom.rv2ide.artificial.permissions.AIPermissionManager
import com.tom.rv2ide.artificial.project.awareness.ProjectData
import com.tom.rv2ide.artificial.providers.ProviderRegistryManager
import com.tom.rv2ide.artificial.secrets.SecretRedactor
import com.tom.rv2ide.artificial.transport.CitationItem
import com.tom.rv2ide.artificial.transport.StreamEvent
import com.tom.rv2ide.artificial.tools.HistoricalToolOutputStore
import com.tom.rv2ide.artificial.coding.TaskCheckpointManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class AIAgentManager(private val context: Context) {

    private val snippetParser = SnippetParser()
    private val permissionManager = AIPermissionManager(context)
    private val registryManager = ProviderRegistryManager.getInstance(context)
    private var currentProjectRoot: File? = null
    private var currentProviderId: String = Agents(context).getProvider()
    private var currentAgent: AIAgent? = null
    private val providerSwitchDialog = ProviderSwitchDialog(context)

    init {
        try {
            val aiStorageDir = File(context.filesDir, "ai")
            HistoricalToolOutputStore.setStorageDirectory(File(aiStorageDir, "historical_outputs"))
            TaskCheckpointManager.setStorageDirectory(File(aiStorageDir, "checkpoints"))
        } catch (_: Throwable) {}

        Gemini.registerAgent()
        OpenAI.registerAgent()
        Anthropic.registerAgent()
        Grok.registerAgent()
        DeepSeek.registerAgent()
        LocalLLM.registerAgent()

        // Upgrade all preset and custom profiles to UniversalProviderAgent
        UniversalProviderAgent.registerAllProfiles(context)

        permissionManager.setFileWriteEnabled(true)
        permissionManager.setRequireConfirmation(false)

        if (!setProvider(currentProviderId)) {
            // Keep uninitialized UniversalProviderAgent reference so provider name/model are still visible
            currentAgent = UniversalProviderAgent(currentProviderId, context)
        }
    }

    fun refreshRegisteredProfiles() {
        UniversalProviderAgent.registerAllProfiles(context)
    }

    fun getCurrentAgent(): AIAgent? = currentAgent

    fun cancelActiveRequest() {
        (currentAgent as? StreamingToolCapableAgent)?.cancelActiveRequest()
    }

    @Volatile
    private var lastCompletionReport: com.tom.rv2ide.artificial.chat.AgentCompletionReport? = null

    fun getLastCompletionReport(): com.tom.rv2ide.artificial.chat.AgentCompletionReport? = lastCompletionReport

    fun getLastCitations(): List<CitationItem> {
        return (currentAgent as? StreamingToolCapableAgent)?.getLastCitations() ?: emptyList()
    }

    fun getLastTokenUsage(): com.tom.rv2ide.artificial.transport.TokenUsage? {
        return (currentAgent as? StreamingToolCapableAgent)?.getLastTokenUsage()
    }

    fun getCumulativeTokenUsage(): com.tom.rv2ide.artificial.transport.TokenUsage {
        return (currentAgent as? StreamingToolCapableAgent)?.getCumulativeTokenUsage() ?: com.tom.rv2ide.artificial.transport.TokenUsage()
    }

    fun setProvider(providerId: String): Boolean {
        if (!AIAgentRegistry.hasProvider(providerId) && registryManager.getProfile(providerId) != null) {
            UniversalProviderAgent.registerForProfile(context, providerId)
        }

        val factory = AIAgentRegistry.getFactory(providerId) ?: return false

        currentProviderId = providerId
        currentAgent = factory.create(context)

        if (!factory.hasValidApiKey()) {
            currentAgent?.setContext(context)
            return false
        }

        val apiKey = factory.getApiKey() ?: ""
        currentAgent?.initialize(apiKey, context)
        currentAgent?.setContext(context)

        currentProjectRoot?.let { root ->
            val projectData = ProjectData(context)
            val projectTree = projectData.showProjectTree(root)
            currentAgent?.setProjectData(projectTree)
        }

        return currentAgent?.isInitialized() ?: false
    }

    fun getCurrentProviderId(): String = currentProviderId

    fun getCurrentProviderName(): String {
        return registryManager.getProfile(currentProviderId)?.displayName
            ?: currentAgent?.providerName
            ?: currentProviderId
    }

    fun getAvailableProviders(): List<ProviderInfo> {
        refreshRegisteredProfiles()
        return registryManager.getEnabledProfiles().map { profile ->
            val factory = AIAgentRegistry.getFactory(profile.id)
            ProviderInfo(
                id = profile.id,
                name = profile.displayName,
                isAvailable = factory?.hasValidApiKey() ?: false
            )
        }
    }

    fun setProjectRoot(projectPath: String): Boolean {
        val projectRoot = File(projectPath)
        if (!projectRoot.exists()) return false

        currentProjectRoot = projectRoot
        com.tom.rv2ide.builder.SharedBuildOrchestrator.setActiveProjectDir(projectRoot)
        val projectData = ProjectData(context)
        val projectTree = projectData.showProjectTree(projectRoot)

        currentAgent?.setProjectData(projectTree)
        permissionManager.addAllowedDirectory(projectRoot.absolutePath)

        return true
    }

    fun clearConversation() {
        currentAgent?.clearConversation()
    }

    suspend fun executeRequest(
        userRequest: String,
        callback: AIAgentCallback,
        explicitWebSearch: Boolean = false
    ) {
        var success = false
        var providerSwitched = false

        // Sync execution mode and /edit command state
        val executionMode = com.tom.rv2ide.artificial.policy.AgentExecutionMode.getSavedMode(context)
        val isEditCommand = userRequest.trim().startsWith("/edit", ignoreCase = true) || userRequest.contains("/edit", ignoreCase = true)
        com.tom.rv2ide.artificial.rules.WritingRules.currentExecutionMode = executionMode
        com.tom.rv2ide.artificial.rules.WritingRules.isEditCommandActive = isEditCommand

        // Ensure current provider is synced with Agents preference
        val selectedProviderId = Agents(context).getProvider()
        if (selectedProviderId != currentProviderId || currentAgent == null || currentAgent?.isInitialized() != true) {
            setProvider(selectedProviderId)
        }

        currentAgent?.resetAttemptCount()
        callback.onProcessing("Analyzing your request...")

        var lastAttemptError: Throwable? = null
        while (!success && (currentAgent?.canRetry() == true)) {
            try {
                val currentAttempt = currentAgent?.getCurrentAttemptCount() ?: 0

                if (currentAttempt > 0 && !providerSwitched) {
                    callback.onRetry(currentAttempt, "Thinking differently...")
                    delay(1000)
                }

                val previousFileStates = captureCurrentFileStates()
                val agent = currentAgent ?: UniversalProviderAgent(currentProviderId, context).also {
                    currentAgent = it
                }

                val streamingTextBuffer = StringBuilder()
                val citationsList = mutableListOf<CitationItem>()

                val result: Result<String> = if (agent is StreamingToolCapableAgent) {
                    val chatRes = agent.generateStreamingChat(
                        prompt = userRequest,
                        context = null,
                        language = "kotlin",
                        projectStructure = null,
                        explicitWebSearch = explicitWebSearch
                    ) { event ->
                        when (event) {
                            is StreamEvent.StatusUpdate -> {
                                callback.onToolStatus(event.status)
                                callback.onProcessing(event.status)
                            }
                            is StreamEvent.TextDelta -> {
                                streamingTextBuffer.append(event.text)
                                callback.onStreamTextDelta(event.text, streamingTextBuffer.toString())
                            }
                            is StreamEvent.CitationAdded -> {
                                if (citationsList.none { it.url == event.citation.url }) {
                                    citationsList.add(event.citation)
                                    callback.onCitationsUpdated(citationsList.toList())
                                }
                            }
                            is StreamEvent.Completed -> {
                                if (event.response.citations.isNotEmpty()) {
                                    callback.onCitationsUpdated(event.response.citations)
                                }
                            }
                            else -> {}
                        }
                    }
                    chatRes.map { it.text }
                } else {
                    agent.generateCode(
                        prompt = userRequest,
                        context = null,
                        language = "kotlin",
                        projectStructure = null
                    )
                }

                result.fold(
                    onSuccess = { initialResponse ->
                        val hasFileBlocks = initialResponse.contains("FILE_TO_MODIFY:") ||
                            initialResponse.contains("FILE_TO_CREATE:") ||
                            initialResponse.contains("FILE_TO_PATCH:")

                        val inspectedFiles = (agent as? StreamingToolCapableAgent)?.getLastInspectedFiles() ?: emptyList()

                        val toolModifiedFiles = (agent as? StreamingToolCapableAgent)?.getLastModifiedFiles() ?: emptyList()
                        val toolRollbackSessionId = (agent as? StreamingToolCapableAgent)?.getLastRollbackSessionId()

                        val isExplicitBuildRepairRequest = userRequest.contains("build", ignoreCase = true) &&
                            (userRequest.contains("fix", ignoreCase = true) || userRequest.contains("repair", ignoreCase = true) || userRequest.contains("keep fixing", ignoreCase = true))

                        val isAutonomousAllowed = (executionMode == com.tom.rv2ide.artificial.policy.AgentExecutionMode.AUTONOMOUS) || isEditCommand
                        val hasWorkToVerify = isAutonomousAllowed && (hasFileBlocks || isExplicitBuildRepairRequest || toolModifiedFiles.isNotEmpty())

                        if (hasWorkToVerify) {
                            val projectRoot = currentProjectRoot ?: com.tom.rv2ide.builder.SharedBuildOrchestrator.getActiveProjectDir()
                            if (projectRoot != null && projectRoot.exists()) {
                                if (currentProjectRoot == null) {
                                    setProjectRoot(projectRoot.absolutePath)
                                }
                                callback.onProcessing("Starting Autonomous Engineering Loop...")

                                val stateListener = object : com.tom.rv2ide.artificial.coding.AutonomousStateListener {
                                    override fun onStateChanged(state: com.tom.rv2ide.artificial.coding.AutonomousExecutionState, description: String) {
                                        val label = if (description.isNotBlank()) description else state.displayName
                                        callback.onToolStatus("⚙️ $label")
                                        callback.onProcessing(label)
                                    }
                                }

                                com.tom.rv2ide.artificial.coding.AutonomousExecutionCoordinator.addListener(stateListener)
                                try {
                                    val initialCandidate = if (hasFileBlocks && toolModifiedFiles.isEmpty()) initialResponse else null
                                    val loopSummary = com.tom.rv2ide.artificial.coding.AutonomousExecutionCoordinator.runAutonomousEngineeringLoop(
                                        projectRoot = projectRoot,
                                        userGoal = userRequest,
                                        initialCodeCandidate = initialCandidate,
                                        codeGenerator = { prompt ->
                                            if (agent is StreamingToolCapableAgent) {
                                                agent.generateStreamingChat(
                                                    prompt = prompt,
                                                    explicitWebSearch = false
                                                ) { event ->
                                                    when (event) {
                                                        is StreamEvent.TextDelta -> {
                                                            callback.onStreamTextDelta(event.text, "")
                                                        }
                                                        else -> {}
                                                    }
                                                }.map { it.text }.getOrNull()
                                            } else {
                                                agent.generateCode(prompt = prompt, context = null, language = "kotlin", projectStructure = null).getOrNull()
                                            }
                                        },
                                        onFileModifying = { path, name ->
                                            callback.onFileModifying(path, name)
                                        },
                                        onFileModified = { path, name, ok ->
                                            currentAgent?.recordModification(path, null, "", ok)
                                            callback.onFileModified(path, name, ok)
                                        },
                                        preModifiedFiles = toolModifiedFiles,
                                        existingSessionId = toolRollbackSessionId
                                    )

                                    lastCompletionReport = loopSummary.report

                                    if (loopSummary.isSuccess) {
                                        val results = loopSummary.totalFilesModified.map { relPath ->
                                            val f = File(projectRoot, relPath)
                                            ModificationResult(
                                                filePath = f.absolutePath,
                                                content = if (f.exists()) f.readText() else "",
                                                success = true,
                                                message = "Verified via actual ${loopSummary.lastBuildResult?.buildMode ?: "build"}",
                                                isNewFile = false
                                            )
                                        }
                                        val summary = createSummary(results)
                                        val responseText = if (initialResponse.isNotBlank()) {
                                            "$initialResponse\n\n✅ ${loopSummary.report.mainChangesSummary}"
                                        } else {
                                            "✅ ${loopSummary.report.mainChangesSummary}"
                                        }
                                        callback.onSuccess(responseText, results, summary)
                                        success = true
                                    } else {
                                        val errMsg = loopSummary.failureBlockerMessage
                                            ?: "Build failed: ${loopSummary.lastBuildResult?.errorMessage ?: "compiler errors remaining"}"
                                        callback.onError(errMsg)
                                        success = true
                                    }
                                } finally {
                                    com.tom.rv2ide.artificial.coding.AutonomousExecutionCoordinator.removeListener(stateListener)
                                }
                            } else {
                                val agentReport = (agent as? StreamingToolCapableAgent)?.getLastCompletionReport()
                                if (agentReport != null) {
                                    lastCompletionReport = agentReport
                                    val results = agentReport.filesModified.map { relPath ->
                                        val f = File(relPath)
                                        ModificationResult(
                                            filePath = f.absolutePath,
                                            content = if (f.exists()) f.readText() else "",
                                            success = true,
                                            message = "Modified successfully",
                                            isNewFile = false
                                        )
                                    }
                                    val summary = createSummary(results)
                                    callback.onSuccess(initialResponse, results, summary)
                                    success = true
                                } else if (toolModifiedFiles.isNotEmpty() && toolRollbackSessionId != null) {
                                    val report = com.tom.rv2ide.artificial.chat.AgentCompletionReport(
                                        goalOrRootCause = userRequest.take(140),
                                        filesModified = toolModifiedFiles,
                                        mainChangesSummary = "Modified ${toolModifiedFiles.size} file(s) via agent tools: " + toolModifiedFiles.joinToString(", "),
                                        validationChecksRun = listOf("File change-set transaction applied"),
                                        validationPassed = true,
                                        verificationLevel = com.tom.rv2ide.artificial.chat.VerificationLevel.IMPLEMENTED_UNVERIFIED,
                                        repairAttemptsUsed = 1,
                                        remainingErrors = emptyList<String>(),
                                        nextActionableStep = null,
                                        rollbackSessionId = toolRollbackSessionId
                                    )
                                    lastCompletionReport = report
                                    val results = toolModifiedFiles.map { relPath ->
                                        val f = File(relPath)
                                        ModificationResult(
                                            filePath = f.absolutePath,
                                            content = if (f.exists()) f.readText() else "",
                                            success = true,
                                            message = "Modified successfully",
                                            isNewFile = false
                                        )
                                    }
                                    val summary = createSummary(results)
                                    callback.onSuccess(initialResponse, results, summary)
                                    success = true
                                } else {
                                    val modifications = processModifications(initialResponse, previousFileStates, callback)
                                    if (modifications.isNotEmpty() && modifications.all { it.writeResult is FileWriteResult.Success }) {
                                        val results = modifications.map { mod ->
                                            val isNewFile = !previousFileStates.containsKey(mod.filePath)
                                            ModificationResult(
                                                filePath = mod.filePath,
                                                content = mod.content,
                                                success = true,
                                                message = "Modified successfully",
                                                isNewFile = isNewFile
                                            )
                                        }
                                        val summary = createSummary(results)
                                        callback.onSuccess(initialResponse, results, summary)
                                        success = true
                                    } else {
                                        callback.onProcessing("No files were modified. Retrying...")
                                        currentAgent?.incrementAttemptCount()
                                        delay(1000)
                                    }
                                }
                            }
                        } else {
                            lastCompletionReport = null
                            val summary = ModificationSummary(0, 0, 0, 0, 0, emptyList())
                            callback.onTextResponse(initialResponse, summary)
                            success = true
                        }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) {
                            callback.onProcessing("⏹️ Generation stopped by user.")
                            success = true
                            return@fold
                        }

                        val shouldSwitchProvider =
                            error is com.tom.rv2ide.artificial.exceptions.RateLimitException ||
                                error is com.tom.rv2ide.artificial.exceptions.QuotaExceededException ||
                                error is com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException ||
                                error is com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException

                        if (shouldSwitchProvider && !providerSwitched) {
                            val currentProviderName = getCurrentProviderName()
                            val errorMsg = SecretRedactor.redact(error.message ?: "Unknown error")

                            if (providerSwitchDialog.isAutoSwitchEnabled()) {
                                val alternativeProvider = getAlternativeProvider()
                                if (alternativeProvider != null) {
                                    callback.onProcessing("⚠️ $currentProviderName: $errorMsg")
                                    callback.onProcessing("🔄 Auto-switching to another provider...")
                                    delay(1500)

                                    if (setProvider(alternativeProvider)) {
                                        providerSwitched = true
                                        currentAgent?.resetAttemptCount()

                                        val newProviderName = getCurrentProviderName()
                                        callback.onProcessing("✅ Switched to $newProviderName")
                                    } else {
                                        val errorDisplay = formatErrorMessage(error)
                                        callback.onError("$errorDisplay\n\n❌ Failed to switch providers.")
                                        success = true
                                    }
                                } else {
                                    val errorDisplay = formatErrorMessage(error)
                                    callback.onError("$errorDisplay\n\n❌ No alternative providers available.")
                                    success = true
                                }
                            } else {
                                val errorDisplay = formatErrorMessage(error)
                                callback.onError("PROVIDER_SWITCH_REQUIRED::$errorDisplay")
                                success = true
                            }
                        } else if ((currentAgent?.canRetry() == true) && !providerSwitched) {
                            lastAttemptError = error
                            val safeMsg = SecretRedactor.redact(error.message?.take(80) ?: "Unknown error")
                            callback.onRetry(
                                currentAgent?.getCurrentAttemptCount() ?: 0,
                                "Error: $safeMsg. Retrying..."
                            )
                            currentAgent?.incrementAttemptCount()
                            delay(1500)
                        } else {
                            val errorDisplay = formatErrorMessage(error)
                            callback.onError(errorDisplay)
                            success = true
                        }
                    }
                )
            } catch (e: CancellationException) {
                callback.onProcessing("⏹️ Generation stopped by user.")
                success = true
            } catch (e: Exception) {
                if (currentAgent?.canRetry() == true) {
                    lastAttemptError = e
                    val safeMsg = SecretRedactor.redact(e.message?.take(80) ?: "Unknown")
                    callback.onRetry(
                        currentAgent?.getCurrentAttemptCount() ?: 0,
                        "Exception: $safeMsg. Trying again..."
                    )
                    currentAgent?.incrementAttemptCount()
                    delay(1500)
                } else {
                    val errorDisplay = formatErrorMessage(e)
                    callback.onError(errorDisplay)
                    success = true
                }
            }
        }

        if (!success) {
            val attemptCount = currentAgent?.getCurrentAttemptCount() ?: 0
            val agentName = getCurrentProviderName()
            val detail = lastAttemptError?.let { formatErrorMessage(it) } ?: "Please check your API key and endpoint settings."
            callback.onError("Failed after $attemptCount attempts with $agentName:\n$detail")
            undoLastModification()
        }
    }

    private fun getAlternativeProvider(): String? {
        val availableProviders = AIAgentRegistry.getAvailableProviders()
        return availableProviders.firstOrNull { it != currentProviderId }
    }

    private suspend fun processModifications(
        response: String,
        previousFileStates: Map<String, String>,
        callback: AIAgentCallback
    ): List<BaseFileModification> {
        val modifications = mutableListOf<BaseFileModification>()
        val parser = SnippetParser()

        val execMode = com.tom.rv2ide.artificial.policy.AgentExecutionMode.getSavedMode(context)
        val isEditCmd = com.tom.rv2ide.artificial.rules.WritingRules.isEditCommandActive
        if (execMode == com.tom.rv2ide.artificial.policy.AgentExecutionMode.SIDE_AGENT && !isEditCmd) {
            return emptyList()
        }

        if (response.contains("FILE_TO_MODIFY:")) {
            val lines = response.lines()
            var currentFile: String? = null
            val contentBuilder = StringBuilder()
            var inContent = false

            for (line in lines) {
                if (line.startsWith("FILE_TO_MODIFY:")) {
                    if (currentFile != null && contentBuilder.isNotEmpty()) {
                        val fileName = File(currentFile).name
                        callback.onFileModifying(currentFile, fileName)

                        val rawContent = contentBuilder.toString().trim()
                        val cleanedContent = parser.cleanFileContent(rawContent)

                        val approval = com.tom.rv2ide.artificial.policy.ApprovalManager.requestApproval(
                            context = context,
                            actionName = "Modify Project File",
                            targetDetail = currentFile,
                            actionKey = "file:$currentFile",
                            proposedDiff = cleanedContent
                        )
                        if (approval == com.tom.rv2ide.artificial.policy.ApprovalDecision.REFUSE) {
                            callback.onFileModified(currentFile, fileName, false)
                            callback.onProcessing("Modification of $fileName refused by user.")
                            currentFile = null
                            contentBuilder.clear()
                            continue
                        }

                        val previousContent = previousFileStates[currentFile]

                        val writeResult = currentAgent?.writeFile(currentFile, cleanedContent)
                        ?: FileWriteResult.Error("Agent unavailable")
                            ?: FileWriteResult.Error("No agent initialized")

                        val success = writeResult is FileWriteResult.Success
                        currentAgent?.recordModification(currentFile, previousContent, cleanedContent, success)

                        callback.onFileModified(currentFile, fileName, success)
                        delay(300)

                        modifications.add(BaseFileModification(currentFile, cleanedContent, writeResult))
                    }

                    currentFile = line.substringAfter("FILE_TO_MODIFY:").trim()
                    contentBuilder.clear()
                    inContent = true
                } else if (inContent) {
                    contentBuilder.append(line).append("\n")
                }
            }

            if (currentFile != null && contentBuilder.isNotEmpty()) {
                val fileName = File(currentFile).name
                callback.onFileModifying(currentFile, fileName)

                val rawContent = contentBuilder.toString().trim()
                val cleanedContent = parser.cleanFileContent(rawContent)

                val approval = com.tom.rv2ide.artificial.policy.ApprovalManager.requestApproval(
                    context = context,
                    actionName = "Modify Project File",
                    targetDetail = currentFile,
                    actionKey = "file:$currentFile",
                    proposedDiff = cleanedContent
                )
                if (approval != com.tom.rv2ide.artificial.policy.ApprovalDecision.REFUSE) {
                    val previousContent = previousFileStates[currentFile]

                    val writeResult = currentAgent?.writeFile(currentFile, cleanedContent)
                        ?: FileWriteResult.Error("Agent unavailable")
                    val success = writeResult is FileWriteResult.Success
                    currentAgent?.recordModification(currentFile, previousContent, cleanedContent, success)

                    callback.onFileModified(currentFile, fileName, success)
                    delay(300)

                    modifications.add(BaseFileModification(currentFile, cleanedContent, writeResult))
                } else {
                    callback.onFileModified(currentFile, fileName, false)
                    callback.onProcessing("Modification of $fileName refused by user.")
                }
            }
        }

        return modifications
    }

    private fun formatErrorMessage(error: Throwable): String {
        val errorMessage = SecretRedactor.redact(error.message ?: "Unknown error occurred")
        val providerName = getCurrentProviderName()

        return when (error) {
            is com.tom.rv2ide.artificial.exceptions.RateLimitException ->
                "⚠️ RATE LIMIT EXCEEDED\n\nThe API rate limit has been exceeded.\nPlease wait before trying again.\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.QuotaExceededException ->
                "⚠️ QUOTA EXCEEDED\n\nYour API quota has been exhausted.\nPlease check your billing or upgrade your plan.\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException ->
                "💳 INSUFFICIENT BALANCE\n\nYour account balance is too low to process this request.\n\nProvider: $providerName\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException ->
                "❌ INVALID OR MISSING API KEY\n\nThe API key for $providerName is missing, invalid, or expired.\nPlease configure your API key in AI Settings.\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.UnsupportedEndpointException ->
                "🔗 ENDPOINT / MODEL NOT FOUND\n\nVerify Base URL, API protocol, and Model ID for $providerName.\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.InsecureEndpointException ->
                "🔒 INSECURE HTTP BLOCKED\n\n$errorMessage"
            is java.net.UnknownHostException, is com.tom.rv2ide.artificial.exceptions.NetworkErrorException ->
                "🌐 NETWORK ERROR\n\nCould not connect to $providerName.\nPlease check your internet connection and Base URL.\n\nDetails: $errorMessage"
            is java.net.SocketTimeoutException ->
                "⏱️ TIMEOUT ERROR\n\nThe request took too long to complete.\n\nDetails: $errorMessage"
            else ->
                "❌ ERROR OCCURRED\n\nProvider: $providerName\nError Type: ${error.javaClass.simpleName}\n\nMessage: $errorMessage"
        }
    }

    fun showProviderErrorDialogFromFragment(
        activity: android.app.Activity,
        errorMessage: String,
        onProviderSelected: (String) -> Unit
    ) {
        val currentProviderName = getCurrentProviderName()
        val availableProviders = getAvailableProviders()
            .filter { it.id != currentProviderId && it.isAvailable }
            .map { Pair(it.id, it.name) }

        providerSwitchDialog.showProviderErrorDialog(
            currentProviderName,
            errorMessage,
            availableProviders,
            onProviderSelected = { providerId ->
                setProvider(providerId)
                val agents = Agents(context)
                agents.setProvider(providerId)
                val availableModels = agents.getModelsForProvider(providerId)
                if (availableModels.isNotEmpty()) {
                    agents.setAgent(availableModels[0])
                }
                reinitializeWithSelectedModel()
                onProviderSelected(providerId)
            },
            onEnableAutoSwitch = {
                val alternativeProvider = getAlternativeProvider()
                if (alternativeProvider != null) {
                    setProvider(alternativeProvider)
                    val agents = Agents(context)
                    agents.setProvider(alternativeProvider)
                    val availableModels = agents.getModelsForProvider(alternativeProvider)
                    if (availableModels.isNotEmpty()) {
                        agents.setAgent(availableModels[0])
                    }
                    reinitializeWithSelectedModel()
                }
            }
        )
    }

    fun isAutoSwitchEnabled(): Boolean {
        return providerSwitchDialog.isAutoSwitchEnabled()
    }

    fun setAutoSwitch(enabled: Boolean) {
        providerSwitchDialog.setAutoSwitch(enabled)
    }

    private fun createSummary(results: List<ModificationResult>): ModificationSummary {
        val successful = results.count { it.success }
        val failed = results.count { !it.success }
        val newFiles = results.count { it.isNewFile }
        val modifiedFiles = results.count { !it.isNewFile }

        val fileDetails = results.map { result ->
            FileDetail(
                fileName = File(result.filePath).name,
                filePath = result.filePath,
                status = if (result.success) FileStatus.SUCCESS else FileStatus.FAILED,
                changeType = if (result.isNewFile) ChangeType.CREATED else ChangeType.MODIFIED
            )
        }

        return ModificationSummary(
            totalFiles = results.size,
            successfulFiles = successful,
            failedFiles = failed,
            newFiles = newFiles,
            modifiedFiles = modifiedFiles,
            fileDetails = fileDetails
        )
    }

    private fun captureCurrentFileStates(): Map<String, String> {
        val states = mutableMapOf<String, String>()
        val projectRoot = currentProjectRoot ?: return states

        if (!projectRoot.exists()) return states

        projectRoot.walkTopDown()
            .filter { it.isFile }
            .filter {
                it.extension in listOf("kt", "java", "xml", "gradle", "kts", "c", "cpp", "h", "mk") &&
                    !it.path.contains("/build/") &&
                    !it.path.contains("/.gradle/")
            }
            .forEach { file ->
                try {
                    if (file.length() <= 128 * 1024) {
                        states[file.absolutePath] = file.readText()
                    }
                } catch (_: Exception) {
                }
            }

        return states
    }

    fun undoLastModification(): Boolean {
        return currentAgent?.undoLastModification() ?: false
    }

    fun reinitializeWithSelectedModel() {
        val factory = AIAgentRegistry.getFactory(currentProviderId)
        val key = factory?.getApiKey() ?: ""
        currentAgent?.reinitializeWithNewModel(key, context)
    }

    fun getCurrentModelName(): String {
        val agents = Agents(context)
        return agents.getAgent()
    }

    fun getConversationHistory(): List<UnifiedModificationAttempt> {
        return currentAgent?.getModificationHistory()?.map {
            UnifiedModificationAttempt(
                timestamp = it.timestamp,
                filePath = it.filePath,
                previousContent = it.previousContent,
                newContent = it.newContent,
                attemptNumber = it.attemptNumber,
                success = it.success
            )
        } ?: emptyList()
    }

    interface AIAgentCallback {
        fun onProcessing(message: String)
        fun onFileModifying(filePath: String, fileName: String)
        fun onFileModified(filePath: String, fileName: String, success: Boolean)
        fun onSuccess(response: String, modifications: List<ModificationResult>, summary: ModificationSummary)
        fun onTextResponse(response: String, summary: ModificationSummary)
        fun onError(message: String)
        fun onRetry(attemptNumber: Int, message: String)
        fun onStreamTextDelta(delta: String, fullTextSoFar: String) {}
        fun onToolStatus(statusMessage: String) {}
        fun onCitationsUpdated(citations: List<CitationItem>) {}
    }

    data class ModificationResult(
        val filePath: String,
        val content: String,
        val success: Boolean,
        val message: String,
        val isNewFile: Boolean = false
    )

    data class ModificationSummary(
        val totalFiles: Int,
        val successfulFiles: Int,
        val failedFiles: Int,
        val newFiles: Int,
        val modifiedFiles: Int,
        val fileDetails: List<FileDetail>
    )

    data class FileDetail(
        val fileName: String,
        val filePath: String,
        val status: FileStatus,
        val changeType: ChangeType
    )

    enum class FileStatus { SUCCESS, FAILED }
    enum class ChangeType { CREATED, MODIFIED }

    data class ProviderInfo(
        val id: String,
        val name: String,
        val isAvailable: Boolean
    )
}

data class BaseFileModification(
    val filePath: String,
    val content: String,
    val writeResult: FileWriteResult
)

data class UnifiedModificationAttempt(
    val timestamp: Long,
    val filePath: String,
    val previousContent: String?,
    val newContent: String,
    val attemptNumber: Int = 0,
    val success: Boolean = false
)