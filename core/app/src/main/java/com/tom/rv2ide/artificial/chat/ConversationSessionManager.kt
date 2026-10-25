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

package com.tom.rv2ide.artificial.chat

import android.content.Context
import com.tom.rv2ide.artificial.transport.ChatRole
import com.tom.rv2ide.artificial.transport.CitationItem
import com.tom.rv2ide.artificial.transport.UnifiedChatMessage
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class ConversationSessionManager(
    private val storageDir: File
) {

    fun interface ConversationListener {
        fun onConversationUpdated(conversation: ChatConversation)
    }

    interface SessionListener : ConversationListener

    private val conversationsById = LinkedHashMap<String, ChatConversation>()
    private val activeConversationIdByProject = mutableMapOf<String, String>()
    private val draftByProject = mutableMapOf<String, String>()
    private val listeners = CopyOnWriteArrayList<ConversationListener>()
    private var lastStreamNotifyTime: Long = 0L
    private val STREAM_NOTIFY_INTERVAL_MS = 60L

    private val _activeConversationFlow = MutableStateFlow(ChatConversation())
    val activeConversationFlow: StateFlow<ChatConversation> = _activeConversationFlow.asStateFlow()

    private var currentProjectKey: String = ""

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        loadFromDisk()
    }

    @Synchronized
    fun setActiveProjectPath(projectPath: String) {
        val norm = normalizeProjectKey(projectPath)
        if (norm.isNotEmpty()) {
            currentProjectKey = norm
        }
    }

    @Synchronized
    fun addListener(listener: ConversationListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    @Synchronized
    fun removeListener(listener: ConversationListener) {
        listeners.remove(listener)
    }

    @Synchronized
    fun getActiveConversation(projectPath: String = currentProjectKey): ChatConversation {
        val normKey = normalizeProjectKey(projectPath)
        if (normKey.isNotEmpty()) {
            currentProjectKey = normKey
        }
        val activeId = activeConversationIdByProject[currentProjectKey]
        val existing = if (activeId != null) conversationsById[activeId] else null
        if (existing != null) {
            _activeConversationFlow.value = existing
            return existing
        }

        // Find most recent conversation for this project, or create a new one
        val forProject = conversationsById.values
            .filter { normalizeProjectKey(it.projectPath) == currentProjectKey }
            .maxByOrNull { it.updatedAt }

        val resolved = forProject ?: ChatConversation(
            id = UUID.randomUUID().toString(),
            title = "New Conversation",
            projectPath = currentProjectKey
        ).also { created ->
            conversationsById[created.id] = created
            persistToDiskLocked()
        }

        activeConversationIdByProject[currentProjectKey] = resolved.id
        _activeConversationFlow.value = resolved
        return resolved
    }

    @Synchronized
    fun startNewConversation(projectPath: String = currentProjectKey): ChatConversation {
        val normKey = normalizeProjectKey(projectPath).ifEmpty { currentProjectKey }
        currentProjectKey = normKey

        // If current active conversation is already completely empty, reuse it
        val currentId = activeConversationIdByProject[normKey]
        val current = currentId?.let { conversationsById[it] }
        if (current != null && current.messages.isEmpty()) {
            notifyListenersLocked(current)
            return current
        }

        val created = ChatConversation(
            id = UUID.randomUUID().toString(),
            title = "New Conversation",
            projectPath = normKey
        )
        conversationsById[created.id] = created
        activeConversationIdByProject[normKey] = created.id
        persistToDiskLocked()
        notifyListenersLocked(created)
        return created
    }

    @Synchronized
    fun clearActiveConversation(projectPath: String = currentProjectKey): ChatConversation {
        val normKey = normalizeProjectKey(projectPath).ifEmpty { currentProjectKey }
        currentProjectKey = normKey
        val active = getActiveConversation(normKey)
        val cleared = active.copy(
            title = "New Conversation",
            updatedAt = System.currentTimeMillis(),
            messages = emptyList()
        )
        conversationsById[cleared.id] = cleared
        persistToDiskLocked()
        notifyListenersLocked(cleared)
        return cleared
    }

    @Synchronized
    fun updateConversationMessages(conversationId: String, newMessages: List<ChatMessageItem>): ChatConversation? {
        val target = conversationsById[conversationId] ?: return null
        val updated = target.copy(
            updatedAt = System.currentTimeMillis(),
            messages = newMessages
        )
        conversationsById[conversationId] = updated
        persistToDiskLocked()
        notifyListenersLocked(updated)
        return updated
    }

    @Synchronized
    fun switchConversation(conversationId: String, projectPath: String = currentProjectKey): ChatConversation? {
        val target = conversationsById[conversationId] ?: return null
        val normKey = normalizeProjectKey(target.projectPath).ifEmpty { normalizeProjectKey(projectPath).ifEmpty { currentProjectKey } }
        currentProjectKey = normKey
        activeConversationIdByProject[normKey] = target.id
        persistToDiskLocked()
        notifyListenersLocked(target)
        return target
    }

    @Synchronized
    fun renameConversation(conversationId: String, newTitle: String): ChatConversation? {
        val target = conversationsById[conversationId] ?: return null
        val renamed = target.copy(
            title = newTitle.trim().ifEmpty { target.title },
            updatedAt = System.currentTimeMillis()
        )
        conversationsById[conversationId] = renamed
        persistToDiskLocked()
        notifyListenersLocked(renamed)
        return renamed
    }

    @Synchronized
    fun deleteConversation(conversationId: String, projectPath: String = currentProjectKey): ChatConversation {
        conversationsById.remove(conversationId)
        val normKey = normalizeProjectKey(projectPath).ifEmpty { currentProjectKey }
        if (activeConversationIdByProject[normKey] == conversationId) {
            activeConversationIdByProject.remove(normKey)
        }
        persistToDiskLocked()
        val nextActive = getActiveConversation(normKey)
        notifyListenersLocked(nextActive)
        return nextActive
    }

    @Synchronized
    fun listConversations(projectPath: String? = null): List<ChatConversation> {
        val filterKey = projectPath?.let { normalizeProjectKey(it) }
        return conversationsById.values
            .filter { conv ->
                if (!filterKey.isNullOrEmpty()) {
                    normalizeProjectKey(conv.projectPath) == filterKey
                } else {
                    true
                }
            }
            .sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun appendUserMessage(
        content: String,
        providerId: String? = null,
        providerName: String? = null,
        modelId: String? = null,
        projectPath: String = currentProjectKey
    ): ChatMessageItem {
        val conv = getActiveConversation(projectPath)
        val msg = ChatMessageItem(
            id = UUID.randomUUID().toString(),
            conversationId = conv.id,
            role = ChatRole.USER,
            content = content.trim(),
            timestamp = System.currentTimeMillis(),
            providerId = providerId,
            providerName = providerName,
            modelId = modelId
        )
        val newTitle = if (conv.messages.isEmpty() || conv.title == "New Conversation") {
            deriveConversationTitle(content)
        } else {
            conv.title
        }
        val updated = conv.copy(
            title = newTitle,
            updatedAt = msg.timestamp,
            messages = conv.messages + msg
        )
        conversationsById[conv.id] = updated
        persistToDiskLocked()
        notifyListenersLocked(updated)
        return msg
    }

    @Synchronized
    fun appendAssistantMessage(
        content: String,
        providerId: String? = null,
        providerName: String? = null,
        modelId: String? = null,
        projectPath: String = currentProjectKey
    ): ChatMessageItem {
        val begun = beginAssistantMessage(providerId, providerName, modelId, null, projectPath)
        return completeAssistantMessage(begun.id, content) ?: begun
    }

    @Synchronized
    fun beginAssistantMessage(
        providerId: String? = null,
        providerName: String? = null,
        modelId: String? = null,
        initialStatus: String? = "Analyzing your request...",
        projectPath: String = currentProjectKey
    ): ChatMessageItem {
        val conv = getActiveConversation(projectPath)
        val msg = ChatMessageItem(
            id = UUID.randomUUID().toString(),
            conversationId = conv.id,
            role = ChatRole.ASSISTANT,
            content = "",
            timestamp = System.currentTimeMillis(),
            providerId = providerId,
            providerName = providerName,
            modelId = modelId,
            isStreaming = true,
            activeToolStatus = initialStatus,
            toolStatusHistory = if (!initialStatus.isNullOrBlank()) listOf(initialStatus) else emptyList()
        )
        val updated = conv.copy(
            updatedAt = msg.timestamp,
            messages = conv.messages + msg
        )
        conversationsById[conv.id] = updated
        notifyListenersLocked(updated)
        return msg
    }

    @Synchronized
    fun updateAssistantStreamingState(
        messageId: String,
        fullTextSoFar: String? = null,
        activeToolStatus: String? = null,
        appendToolStatusToHistory: Boolean = false,
        citations: List<CitationItem>? = null,
        fileModifications: List<ChatFileModification>? = null
    ): ChatMessageItem? {
        val (conv, idx, existing) = findMessageLocked(messageId) ?: return null
        val displayContent = if (fullTextSoFar != null) {
            formatDisplayContentFromModelStream(fullTextSoFar)
        } else {
            existing.content
        }
        val updatedToolHistory = if (appendToolStatusToHistory && !activeToolStatus.isNullOrBlank()) {
            if (existing.toolStatusHistory.lastOrNull() != activeToolStatus) {
                existing.toolStatusHistory + activeToolStatus
            } else {
                existing.toolStatusHistory
            }
        } else {
            existing.toolStatusHistory
        }
        val updatedMsg = existing.copy(
            content = displayContent,
            rawModelResponse = fullTextSoFar ?: existing.rawModelResponse,
            isStreaming = true,
            activeToolStatus = activeToolStatus ?: existing.activeToolStatus,
            toolStatusHistory = updatedToolHistory,
            citations = citations ?: existing.citations,
            fileModifications = fileModifications ?: existing.fileModifications
        )
        val newMessages = conv.messages.toMutableList().apply { set(idx, updatedMsg) }
        val updatedConv = conv.copy(updatedAt = System.currentTimeMillis(), messages = newMessages)
        conversationsById[conv.id] = updatedConv
        val now = System.currentTimeMillis()
        val isToolTransition = activeToolStatus != null && activeToolStatus != existing.activeToolStatus
        if (now - lastStreamNotifyTime >= STREAM_NOTIFY_INTERVAL_MS || isToolTransition) {
            lastStreamNotifyTime = now
            notifyListenersLocked(updatedConv)
        }
        return updatedMsg
    }

    @Synchronized
    fun completeAssistantMessage(
        messageId: String,
        finalModelText: String,
        citations: List<CitationItem> = emptyList(),
        fileModifications: List<ChatFileModification> = emptyList(),
        completionReport: AgentCompletionReport? = null
    ): ChatMessageItem? {
        val (conv, idx, existing) = findMessageLocked(messageId) ?: return null
        val cleanVisibleText = formatDisplayContentFromModelStream(finalModelText).ifBlank {
            if (fileModifications.isNotEmpty()) {
                val count = fileModifications.count { it.success }
                "Applied changes to $count file(s)."
            } else {
                finalModelText.trim()
            }
        }
        val mergedCitations = if (citations.isNotEmpty()) citations else existing.citations
        val mergedMods = if (fileModifications.isNotEmpty()) fileModifications else existing.fileModifications

        val updatedMsg = existing.copy(
            content = cleanVisibleText,
            rawModelResponse = finalModelText,
            isStreaming = false,
            isError = false,
            isCancelled = false,
            activeToolStatus = null,
            citations = mergedCitations,
            fileModifications = mergedMods,
            completionReport = completionReport ?: existing.completionReport
        )
        val newMessages = conv.messages.toMutableList().apply { set(idx, updatedMsg) }
        val updatedConv = conv.copy(updatedAt = System.currentTimeMillis(), messages = newMessages)
        conversationsById[conv.id] = updatedConv
        persistToDiskLocked()
        notifyListenersLocked(updatedConv)
        return updatedMsg
    }

    @Synchronized
    fun failAssistantMessage(
        messageId: String,
        errorMessage: String,
        fileModifications: List<ChatFileModification> = emptyList(),
        completionReport: AgentCompletionReport? = null
    ): ChatMessageItem? {
        val (conv, idx, existing) = findMessageLocked(messageId) ?: return null
        val partialContent = existing.content.trim()
        val combinedContent = if (partialContent.isNotEmpty()) {
            "$partialContent\n\n---\n❌ **Error:** $errorMessage"
        } else {
            "❌ **Error:** $errorMessage"
        }
        val updatedMsg = existing.copy(
            content = combinedContent,
            isStreaming = false,
            isError = true,
            activeToolStatus = null,
            fileModifications = if (fileModifications.isNotEmpty()) fileModifications else existing.fileModifications,
            completionReport = completionReport ?: existing.completionReport
        )
        val newMessages = conv.messages.toMutableList().apply { set(idx, updatedMsg) }
        val updatedConv = conv.copy(updatedAt = System.currentTimeMillis(), messages = newMessages)
        conversationsById[conv.id] = updatedConv
        persistToDiskLocked()
        notifyListenersLocked(updatedConv)
        return updatedMsg
    }

    @Synchronized
    fun cancelAssistantMessage(
        messageId: String
    ): ChatMessageItem? {
        val (conv, idx, existing) = findMessageLocked(messageId) ?: return null
        val partialContent = existing.content.trim()
        val displayContent = if (partialContent.isNotEmpty()) {
            "$partialContent\n\n*(⏹️ Generation stopped by user)*"
        } else {
            "*(⏹️ Generation stopped by user)*"
        }
        val updatedMsg = existing.copy(
            content = displayContent,
            isStreaming = false,
            isCancelled = true,
            activeToolStatus = null
        )
        val newMessages = conv.messages.toMutableList().apply { set(idx, updatedMsg) }
        val updatedConv = conv.copy(updatedAt = System.currentTimeMillis(), messages = newMessages)
        conversationsById[conv.id] = updatedConv
        persistToDiskLocked()
        notifyListenersLocked(updatedConv)
        return updatedMsg
    }

    /**
     * Marks an assistant message's completion report as rolled back and persists the updated conversation.
     */
    @Synchronized
    fun markSessionRolledBack(messageId: String): ChatMessageItem? {
        val (conv, idx, existing) = findMessageLocked(messageId) ?: return null
        val report = existing.completionReport ?: return null
        val updatedReport = report.copy(
            isRolledBack = true,
            verificationLevel = VerificationLevel.ROLLED_BACK
        )
        val updatedMsg = existing.copy(completionReport = updatedReport)
        val newMessages = conv.messages.toMutableList().apply { set(idx, updatedMsg) }
        val updatedConv = conv.copy(updatedAt = System.currentTimeMillis(), messages = newMessages)
        conversationsById[conv.id] = updatedConv
        persistToDiskLocked()
        notifyListenersLocked(updatedConv)
        return updatedMsg
    }


    /**
     * Finds the user prompt corresponding to an assistant message (or user message) for Retry / Regenerate,
     * removes only the failed/regenerated assistant bubble if it is the latest message, and preserves all earlier history!
     */
    @Synchronized
    fun prepareRetryOrRegenerate(messageId: String): String? {
        val (conv, idx, targetMsg) = findMessageLocked(messageId) ?: return null
        if (targetMsg.role == ChatRole.USER) {
            return targetMsg.content
        }
        // Find the preceding USER message
        var userPrompt: String? = null
        for (i in (idx - 1) downTo 0) {
            if (conv.messages[i].role == ChatRole.USER) {
                userPrompt = conv.messages[i].content
                break
            }
        }
        if (userPrompt != null && idx == conv.messages.lastIndex) {
            // Remove only the last assistant response being regenerated; keep the user message and all prior turns intact!
            val trimmedMessages = conv.messages.subList(0, idx)
            val updatedConv = conv.copy(updatedAt = System.currentTimeMillis(), messages = trimmedMessages)
            conversationsById[conv.id] = updatedConv
            persistToDiskLocked()
            notifyListenersLocked(updatedConv)
        }
        return userPrompt
    }

    /**
     * Builds the multi-turn model request history with context-budget management.
     * - Never truncates the visible UI conversation list.
     * - Preserves prior USER and ASSISTANT turns in chronological order.
     * - Does not duplicate system prompts or full project file dumps across old turns.
     * - Summarizes oldest turns safely when character/token budget is exceeded.
     */
    @Synchronized
    fun buildModelRequestMessages(
        conversationId: String?,
        latestUserPromptWithProjectContext: String,
        latestRawUserText: String,
        maxContextTokens: Int? = null,
        reservedSystemAndOutputTokens: Int = 6000
    ): List<UnifiedChatMessage> {
        val conv = (if (conversationId != null) conversationsById[conversationId] else getActiveConversation())
            ?: return listOf(UnifiedChatMessage(role = ChatRole.USER, content = latestUserPromptWithProjectContext))

        // Exclude any currently streaming placeholder at the tail and the just-appended user turn matching latestRawUserText
        val completedMessages = conv.messages.filter { !it.isStreaming }
        val priorTurns = if (completedMessages.lastOrNull()?.role == ChatRole.USER &&
            completedMessages.last().content.trim() == latestRawUserText.trim()
        ) {
            completedMessages.dropLast(1)
        } else {
            completedMessages
        }

        if (priorTurns.isEmpty()) {
            return listOf(UnifiedChatMessage(role = ChatRole.USER, content = latestUserPromptWithProjectContext))
        }

        val effectiveTokenLimit = (maxContextTokens ?: ConversationCompactor.DEFAULT_CONSERVATIVE_CONTEXT_LIMIT).coerceAtLeast(8192)
        val latestPromptTokens = estimateTokens(latestUserPromptWithProjectContext)
        val availableHistoryTokens = (effectiveTokenLimit - reservedSystemAndOutputTokens - latestPromptTokens)
            .coerceAtLeast(MIN_HISTORY_TOKEN_BUDGET)
        val availableHistoryChars = availableHistoryTokens * CHARS_PER_TOKEN

        val formattedPriorTurns = priorTurns.mapNotNull { item ->
            when (item.role) {
                ChatRole.USER -> UnifiedChatMessage(
                    role = ChatRole.USER,
                    content = item.content
                )
                ChatRole.ASSISTANT -> {
                    if (item.isError && item.content.startsWith("❌")) {
                        // Skip pure transport error messages from polluting LLM context
                        null
                    } else {
                        val modSummary = if (item.fileModifications.isNotEmpty()) {
                            "\n[Modified files in this turn: ${item.fileModifications.joinToString(", ") { "${it.fileName} (${it.statusMessage})" }}]"
                        } else {
                            ""
                        }
                        UnifiedChatMessage(
                            role = ChatRole.ASSISTANT,
                            content = (item.content + modSummary).trim(),
                            toolCalls = item.toolCalls
                        )
                    }
                }
                ChatRole.TOOL -> UnifiedChatMessage(
                    role = ChatRole.TOOL,
                    content = item.content,
                    toolResult = item.toolResult
                )
                else -> null
            }
        }

        // Group formattedPriorTurns into atomic message batches to preserve tool-pairing integrity.
        // A tool interaction batch consists of an ASSISTANT message with toolCalls followed by its matching TOOL results.
        val atomicBatches = mutableListOf<List<UnifiedChatMessage>>()
        var batchIdx = 0
        while (batchIdx < formattedPriorTurns.size) {
            val currentMsg = formattedPriorTurns[batchIdx]
            if (currentMsg.role == ChatRole.ASSISTANT && currentMsg.toolCalls.isNotEmpty()) {
                val toolBatch = mutableListOf(currentMsg)
                var nextIdx = batchIdx + 1
                while (nextIdx < formattedPriorTurns.size && formattedPriorTurns[nextIdx].role == ChatRole.TOOL) {
                    toolBatch.add(formattedPriorTurns[nextIdx])
                    nextIdx++
                }
                atomicBatches.add(toolBatch)
                batchIdx = nextIdx
            } else {
                atomicBatches.add(listOf(currentMsg))
                batchIdx++
            }
        }

        // Walk backwards from most recent batches to keep recent turns verbatim within budget
        val keptRecent = ArrayDeque<UnifiedChatMessage>()
        var usedChars = 0
        var omittedCount = 0
        val omittedTurns = mutableListOf<UnifiedChatMessage>()

        for (bIdx in atomicBatches.indices.reversed()) {
            val batch = atomicBatches[bIdx]
            val batchChars = batch.sumOf { it.content.length }
            if (usedChars + batchChars <= availableHistoryChars || keptRecent.isEmpty()) {
                for (msg in batch.reversed()) {
                    val cappedContent = if (msg.content.length > MAX_SINGLE_HISTORICAL_TURN_CHARS) {
                        msg.content.take(MAX_SINGLE_HISTORICAL_TURN_CHARS) + "\n...[truncated historical turn]..."
                    } else {
                        msg.content
                    }
                    keptRecent.addFirst(msg.copy(content = cappedContent))
                    usedChars += cappedContent.length
                }
            } else {
                omittedCount += batch.size
                omittedTurns.addAll(0, batch)
            }
        }

        val result = mutableListOf<UnifiedChatMessage>()
        if (omittedCount > 0 && omittedTurns.isNotEmpty()) {
            val summaryText = buildOmittedTurnsSummary(omittedTurns)
            result.add(
                UnifiedChatMessage(
                    role = ChatRole.USER,
                    content = "[Earlier Conversation Context Summary (${omittedTurns.size} earlier messages summarized to fit context window)]:\n$summaryText"
                )
            )
            result.add(
                UnifiedChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "Understood. I have the context from those earlier turns."
                )
            )
        }

        result.addAll(keptRecent)
        result.add(UnifiedChatMessage(role = ChatRole.USER, content = latestUserPromptWithProjectContext))
        return result
    }

    @Synchronized
    fun saveDraft(projectPath: String = currentProjectKey, draftText: String) {
        val key = normalizeProjectKey(projectPath).ifEmpty { currentProjectKey }
        draftByProject[key] = draftText
    }

    @Synchronized
    fun saveDraftText(draftText: String, projectPath: String = currentProjectKey) {
        saveDraft(projectPath = projectPath, draftText = draftText)
    }

    @Synchronized
    fun getDraft(projectPath: String = currentProjectKey): String {
        val key = normalizeProjectKey(projectPath).ifEmpty { currentProjectKey }
        return draftByProject[key].orEmpty()
    }

    @Synchronized
    fun getDraftText(projectPath: String = currentProjectKey): String {
        return getDraft(projectPath)
    }

    /**
     * Converts raw model responses containing `FILE_TO_MODIFY:` blocks into clean readable Markdown
     * so the user sees the explanation and fenced code previews without raw parser markers.
     */
    fun formatDisplayContentFromModelStream(rawText: String): String {
        if (!rawText.contains("FILE_TO_MODIFY:")) {
            return rawText.trim()
        }
        val lines = rawText.lines()
        val out = StringBuilder()
        var currentFile: String? = null
        val fileCodeBuffer = StringBuilder()

        fun flushCurrentFile() {
            val filePath = currentFile ?: return
            val fileName = File(filePath).name
            val ext = File(filePath).extension.lowercase()
            val lang = when (ext) {
                "kt", "kts" -> "kotlin"
                "java" -> "java"
                "xml" -> "xml"
                "c", "h" -> "c"
                "cpp", "hpp", "cc" -> "cpp"
                "gradle" -> "groovy"
                "json" -> "json"
                else -> ext
            }
            val code = fileCodeBuffer.toString()
                .trim()
                .removePrefix("```$lang")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            if (out.isNotEmpty() && !out.endsWith("\n\n")) {
                out.append("\n\n")
            }
            out.append("📄 **`").append(fileName).append("`** (`").append(filePath).append("`)\n")
            if (code.isNotEmpty()) {
                out.append("```").append(lang).append("\n")
                out.append(code).append("\n")
                out.append("```\n")
            }
            currentFile = null
            fileCodeBuffer.clear()
        }

        for (line in lines) {
            if (line.trimStart().startsWith("FILE_TO_MODIFY:")) {
                flushCurrentFile()
                currentFile = line.substringAfter("FILE_TO_MODIFY:").trim()
            } else if (currentFile != null) {
                fileCodeBuffer.append(line).append("\n")
            } else {
                out.append(line).append("\n")
            }
        }
        flushCurrentFile()
        return out.toString().trim()
    }

    private fun buildOmittedTurnsSummary(omitted: List<UnifiedChatMessage>): String {
        return omitted.takeLast(10).joinToString("\n") { msg ->
            val roleLabel = if (msg.role == ChatRole.USER) "User" else "Assistant"
            val snippet = msg.content.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .take(180)
            "- $roleLabel: $snippet"
        }
    }

    private fun deriveConversationTitle(firstUserMessage: String): String {
        val firstLine = firstUserMessage.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: "New Conversation"
        return if (firstLine.length <= 44) firstLine else firstLine.take(41) + "..."
    }

    private fun findMessageLocked(messageId: String): Triple<ChatConversation, Int, ChatMessageItem>? {
        for (conv in conversationsById.values) {
            val idx = conv.messages.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                return Triple(conv, idx, conv.messages[idx])
            }
        }
        return null
    }

    fun normalizeProjectKey(projectPath: String?): String {
        val trimmed = projectPath?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return try {
            File(trimmed).canonicalPath
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun estimateTokens(text: String): Int {
        return (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }

    private fun notifyListenersLocked(conversation: ChatConversation) {
        if (activeConversationIdByProject[currentProjectKey] == conversation.id || currentProjectKey.isEmpty()) {
            _activeConversationFlow.value = conversation
        }
        for (listener in listeners) {
            listener.onConversationUpdated(conversation)
        }
    }

    private fun persistToDiskLocked() {
        try {
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }
            val file = File(storageDir, STORAGE_FILE_NAME)
            val root = JSONObject()
            root.put("version", 1)
            root.put("currentProjectKey", currentProjectKey)

            val activeMapObj = JSONObject()
            for ((k, v) in activeConversationIdByProject) {
                activeMapObj.put(k, v)
            }
            root.put("activeByProject", activeMapObj)

            val convArr = JSONArray()
            conversationsById.values
                .sortedByDescending { it.updatedAt }
                .take(MAX_STORED_CONVERSATIONS)
                .forEach { conv ->
                    convArr.put(conv.toJson())
                }
            root.put("conversations", convArr)

            val tmpFile = File(storageDir, "$STORAGE_FILE_NAME.tmp")
            tmpFile.writeText(root.toString())
            if (!tmpFile.renameTo(file)) {
                file.writeText(root.toString())
                tmpFile.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun loadFromDisk() {
        try {
            val file = File(storageDir, STORAGE_FILE_NAME)
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            currentProjectKey = root.optString("currentProjectKey", "")

            val activeObj = root.optJSONObject("activeByProject")
            if (activeObj != null) {
                val keys = activeObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = activeObj.optString(k, "")
                    if (v.isNotEmpty()) {
                        activeConversationIdByProject[k] = v
                    }
                }
            }

            val convArr = root.optJSONArray("conversations") ?: JSONArray()
            for (i in 0 until convArr.length()) {
                val obj = convArr.optJSONObject(i) ?: continue
                val conv = ChatConversation.fromJson(obj)
                conversationsById[conv.id] = conv
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val STORAGE_FILE_NAME = "conversations_v1.json"
        private const val MAX_STORED_CONVERSATIONS = 40
        private const val DEFAULT_CONTEXT_TOKEN_BUDGET = 28_000
        private const val MIN_HISTORY_TOKEN_BUDGET = 2_000
        private const val CHARS_PER_TOKEN = 4
        private const val MAX_SINGLE_HISTORICAL_TURN_CHARS = 12_000

        @Volatile
        private var instance: ConversationSessionManager? = null

        fun getInstance(context: Context): ConversationSessionManager {
            return instance ?: synchronized(this) {
                instance ?: ConversationSessionManager(
                    File(context.applicationContext.filesDir, "ai_conversations")
                ).also { instance = it }
            }
        }
    }
}
