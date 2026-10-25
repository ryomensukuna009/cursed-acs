package com.tom.rv2ide.handlers

import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.adapters.FileModificationAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.chat.ChatFileModification
import com.tom.rv2ide.artificial.chat.ConversationSessionManager
import com.tom.rv2ide.artificial.transport.CitationItem
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AIRequestHandler(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val aiAgent: AIAgentManager,
    private val statusText: MaterialTextView,
    private val summaryText: MaterialTextView,
    private val progressIndicator: CircularProgressIndicator,
    private val executeBtn: MaterialButton,
    private val fileModificationList: RecyclerView,
    private val fileModificationAdapter: FileModificationAdapter,
    private val summaryCard: LinearLayout,
    private val onFileOpen: (String) -> Unit,
    private val onTypeText: (String, Long) -> Unit,
    private val getCurrentFile: () -> File?,
    private val refreshEditor: () -> Unit,
    private val stopBtn: MaterialButton? = null,
    private val toolStatusBanner: MaterialTextView? = null,
    private val onCitationsUpdated: ((List<CitationItem>) -> Unit)? = null,
    private val sessionManager: ConversationSessionManager? = null,
    private val getActiveModelId: (() -> String)? = null
) {

    private var executionJob: Job? = null
    @Volatile
    private var activeAssistantMessageId: String? = null
    private val inFlightFileMods = mutableMapOf<String, ChatFileModification>()
    var onGenerationStateChanged: ((Boolean) -> Unit)? = null

    fun isGenerating(): Boolean = executionJob?.isActive == true

    fun execute(
        userRequest: String,
        explicitWebSearch: Boolean = false,
        appendUserTurn: Boolean = true
    ) {
        aiAgent.cancelActiveRequest()
        executionJob?.cancel()

        val providerId = aiAgent.getCurrentProviderId()
        val providerName = aiAgent.getCurrentProviderName()
        val modelId = getActiveModelId?.invoke() ?: ""

        inFlightFileMods.clear()

        if (appendUserTurn) {
            sessionManager?.appendUserMessage(
                content = userRequest,
                providerId = providerId,
                providerName = providerName,
                modelId = modelId
            )
        }

        val placeholder = sessionManager?.beginAssistantMessage(
            providerId = providerId,
            providerName = providerName,
            modelId = modelId,
            initialStatus = "Analyzing your request..."
        )
        activeAssistantMessageId = placeholder?.id

        executionJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    executeBtn.isEnabled = false
                    stopBtn?.visibility = View.VISIBLE
                    onGenerationStateChanged?.invoke(true)
                    progressIndicator.visibility = View.VISIBLE
                    toolStatusBanner?.visibility = View.VISIBLE
                    toolStatusBanner?.text = "Analyzing your request..."
                    summaryCard.visibility = View.GONE
                    fileModificationAdapter.clear()
                    fileModificationList.visibility = View.GONE
                    onCitationsUpdated?.invoke(emptyList())
                }

                executeAIRequest(userRequest, explicitWebSearch)
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) {
                    activeAssistantMessageId?.let { msgId ->
                        sessionManager?.cancelAssistantMessage(msgId)
                    }
                    activeAssistantMessageId = null
                    executeBtn.isEnabled = true
                    stopBtn?.visibility = View.GONE
        onGenerationStateChanged?.invoke(false)
                    onGenerationStateChanged?.invoke(false)
                    progressIndicator.visibility = View.GONE
                    toolStatusBanner?.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    activeAssistantMessageId?.let { msgId ->
                        sessionManager?.failAssistantMessage(
                            messageId = msgId,
                            errorMessage = e.message ?: "Unexpected error",
                            fileModifications = inFlightFileMods.values.toList(),
                            completionReport = aiAgent.getLastCompletionReport()
                        )
                    }
                    activeAssistantMessageId = null
                    executeBtn.isEnabled = true
                    stopBtn?.visibility = View.GONE
        onGenerationStateChanged?.invoke(false)
                    onGenerationStateChanged?.invoke(false)
                    progressIndicator.visibility = View.GONE
                    toolStatusBanner?.visibility = View.GONE
                    statusText.text = "❌ Error: ${e.message}"
                }
            }
        }
    }

    private suspend fun executeAIRequest(userRequest: String, explicitWebSearch: Boolean) {
        aiAgent.executeRequest(
            userRequest = userRequest,
            explicitWebSearch = explicitWebSearch,
            callback = object : AIAgentManager.AIAgentCallback {
                override fun onProcessing(message: String) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        toolStatusBanner?.visibility = View.VISIBLE
                        toolStatusBanner?.text = message
                        activeAssistantMessageId?.let { msgId ->
                            sessionManager?.updateAssistantStreamingState(
                                messageId = msgId,
                                activeToolStatus = message
                            )
                        }
                    }
                }

                override fun onToolStatus(statusMessage: String) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        toolStatusBanner?.visibility = View.VISIBLE
                        toolStatusBanner?.text = "🔎 $statusMessage"
                        activeAssistantMessageId?.let { msgId ->
                            sessionManager?.updateAssistantStreamingState(
                                messageId = msgId,
                                activeToolStatus = statusMessage,
                                appendToolStatusToHistory = true
                            )
                        }
                    }
                }

                override fun onStreamTextDelta(delta: String, fullTextSoFar: String) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        statusText.text = fullTextSoFar
                        activeAssistantMessageId?.let { msgId ->
                            sessionManager?.updateAssistantStreamingState(
                                messageId = msgId,
                                fullTextSoFar = fullTextSoFar
                            )
                        }
                    }
                }

                override fun onCitationsUpdated(citations: List<CitationItem>) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        onCitationsUpdated?.invoke(citations)
                        activeAssistantMessageId?.let { msgId ->
                            sessionManager?.updateAssistantStreamingState(
                                messageId = msgId,
                                citations = citations
                            )
                        }
                    }
                }

                override fun onFileModifying(filePath: String, fileName: String) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (fileModificationList.visibility == View.GONE) {
                            fileModificationList.visibility = View.VISIBLE
                        }
                        fileModificationAdapter.addItem(fileName)
                        inFlightFileMods[filePath] = ChatFileModification(
                            filePath = filePath,
                            fileName = fileName,
                            isNewFile = false,
                            success = true,
                            statusMessage = "Writing…"
                        )
                        activeAssistantMessageId?.let { msgId ->
                            sessionManager?.updateAssistantStreamingState(
                                messageId = msgId,
                                activeToolStatus = "Writing $fileName…",
                                fileModifications = inFlightFileMods.values.toList()
                            )
                        }
                    }
                }

                override fun onFileModified(filePath: String, fileName: String, success: Boolean) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        fileModificationAdapter.updateItemStatus(fileName, success)
                        inFlightFileMods[filePath] = ChatFileModification(
                            filePath = filePath,
                            fileName = fileName,
                            isNewFile = false,
                            success = success,
                            statusMessage = if (success) "Modified" else "Failed"
                        )
                        activeAssistantMessageId?.let { msgId ->
                            sessionManager?.updateAssistantStreamingState(
                                messageId = msgId,
                                fileModifications = inFlightFileMods.values.toList()
                            )
                        }

                        if (getCurrentFile()?.name == fileName && success) {
                            refreshEditor()
                        }
                    }
                }

                override fun onSuccess(
                    response: String,
                    modifications: List<AIAgentManager.ModificationResult>,
                    summary: AIAgentManager.ModificationSummary
                ) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        handleSuccess(response, modifications, summary)
                    }
                }

                override fun onTextResponse(
                    response: String,
                    summary: AIAgentManager.ModificationSummary
                ) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        handleTextResponse(response)
                    }
                }

                override fun onError(message: String) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        handleError(message)
                    }
                }

                override fun onRetry(attemptNumber: Int, message: String) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        val retryStatus = "Retry #$attemptNumber: $message"
                        toolStatusBanner?.visibility = View.VISIBLE
                        toolStatusBanner?.text = "🔄 $retryStatus"
                        activeAssistantMessageId?.let { msgId ->
                            sessionManager?.updateAssistantStreamingState(
                                messageId = msgId,
                                activeToolStatus = retryStatus,
                                appendToolStatusToHistory = true
                            )
                        }
                    }
                }
            }
        )
    }

    private fun handleSuccess(
        response: String,
        modifications: List<AIAgentManager.ModificationResult>,
        summary: AIAgentManager.ModificationSummary
    ) {
        progressIndicator.visibility = View.GONE
        stopBtn?.visibility = View.GONE
        onGenerationStateChanged?.invoke(false)
        toolStatusBanner?.visibility = View.GONE
        statusText.text = "✅ Operation completed"
        summaryText.text = buildSummaryText(summary)
        summaryCard.visibility = View.VISIBLE

        val chatMods = modifications.map { mod ->
            ChatFileModification(
                filePath = mod.filePath,
                fileName = File(mod.filePath).name,
                isNewFile = mod.isNewFile,
                success = mod.success,
                statusMessage = if (mod.success) (if (mod.isNewFile) "Created" else "Modified") else "Failed"
            )
        }

        activeAssistantMessageId?.let { msgId ->
            sessionManager?.completeAssistantMessage(
                messageId = msgId,
                finalModelText = response,
                citations = aiAgent.getLastCitations(),
                fileModifications = chatMods,
                completionReport = aiAgent.getLastCompletionReport()
            )
        }
        activeAssistantMessageId = null

        if (modifications.isNotEmpty()) {
            val firstMod = modifications.first()
            val file = File(firstMod.filePath)
            if (file.exists()) {
                onFileOpen(file.name)
            }
        }

        executeBtn.isEnabled = true
    }

    private fun handleTextResponse(response: String) {
        progressIndicator.visibility = View.GONE
        stopBtn?.visibility = View.GONE
        onGenerationStateChanged?.invoke(false)
        toolStatusBanner?.visibility = View.GONE
        executeBtn.isEnabled = true
        statusText.text = response
        summaryCard.visibility = View.GONE
        fileModificationList.visibility = View.GONE

        activeAssistantMessageId?.let { msgId ->
            sessionManager?.completeAssistantMessage(
                messageId = msgId,
                finalModelText = response,
                citations = aiAgent.getLastCitations(),
                fileModifications = emptyList(),
                completionReport = aiAgent.getLastCompletionReport()
            )
        }
        activeAssistantMessageId = null
    }

    private fun handleError(message: String) {
        progressIndicator.visibility = View.GONE
        stopBtn?.visibility = View.GONE
        onGenerationStateChanged?.invoke(false)
        toolStatusBanner?.visibility = View.GONE
        executeBtn.isEnabled = true

        val displayMsg = message.removePrefix("PROVIDER_SWITCH_REQUIRED::")
        statusText.text = """
❌ ERROR OCCURRED

$displayMsg

Please check your provider settings or API key and try again.
        """.trimIndent()

        activeAssistantMessageId?.let { msgId ->
            sessionManager?.failAssistantMessage(
                messageId = msgId,
                errorMessage = displayMsg,
                fileModifications = inFlightFileMods.values.toList(),
                completionReport = aiAgent.getLastCompletionReport()
            )
        }
        activeAssistantMessageId = null
    }

    private fun buildSummaryText(summary: AIAgentManager.ModificationSummary): String {
        val builder = StringBuilder()
        builder.append("📊 Total Files: ${summary.totalFiles}\n")
        builder.append("✅ Successful: ${summary.successfulFiles}\n")
        if (summary.failedFiles > 0) {
            builder.append("❌ Failed: ${summary.failedFiles}\n")
        }
        builder.append("🆕 New Files: ${summary.newFiles}\n")
        builder.append("✏️ Modified Files: ${summary.modifiedFiles}\n\n")

        builder.append("Files:\n")
        summary.fileDetails.forEach { detail ->
            val icon = if (detail.status == AIAgentManager.FileStatus.SUCCESS) "✅" else "❌"
            val type = if (detail.changeType == AIAgentManager.ChangeType.CREATED) "Created" else "Modified"
            builder.append("$icon $type: ${detail.fileName}\n")
        }

        return builder.toString()
    }

    fun cancel() {
        aiAgent.cancelActiveRequest()
        executionJob?.cancel()
        activeAssistantMessageId?.let { msgId ->
            sessionManager?.cancelAssistantMessage(msgId)
        }
        activeAssistantMessageId = null
        progressIndicator.visibility = View.GONE
        stopBtn?.visibility = View.GONE
        onGenerationStateChanged?.invoke(false)
        toolStatusBanner?.visibility = View.GONE
        executeBtn.isEnabled = true
    }
}