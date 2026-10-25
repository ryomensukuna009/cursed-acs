package com.tom.rv2ide.artificial.chat

import com.tom.rv2ide.artificial.exceptions.ProviderRequestException
import com.tom.rv2ide.artificial.providers.ModelCapabilities
import com.tom.rv2ide.artificial.transport.ChatRole
import com.tom.rv2ide.artificial.transport.ToolCall
import com.tom.rv2ide.artificial.transport.ToolDefinition
import com.tom.rv2ide.artificial.transport.ToolResultMessage
import com.tom.rv2ide.artificial.transport.UnifiedChatMessage
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Budget calculations and metrics for context management.
 */
data class ContextBudget(
    val contextLength: Int,
    val isEstimatedLimit: Boolean,
    val usableInputBudget: Int,
    val triggerThresholdTokens: Int,
    val reservedHeadroomTokens: Int,
    val currentEstimatedTokens: Int
) {
    val needsCompaction: Boolean
        get() = currentEstimatedTokens >= triggerThresholdTokens
}

/**
 * Result of a conversation compaction operation.
 */
data class CompactionResult(
    val messages: List<UnifiedChatMessage>,
    val compacted: Boolean,
    val estimatedTokensBefore: Int,
    val estimatedTokensAfter: Int,
    val prunedToolOutputsCount: Int,
    val prunedTurnsCount: Int,
    val contextLimitUsed: Int,
    val isEstimatedLimit: Boolean,
    val summaryNotice: String? = null
)

/**
 * Provider-aware, deterministic context compaction and token budgeting engine.
 *
 * Capabilities:
 * 1. Provider-aware budgeting: uses reported [ModelCapabilities.contextLength] or a clearly
 *    identified conservative fallback estimate.
 * 2. Deterministic basic compaction: prunes older oversized tool outputs (file reads, compiler logs,
 *    directory listings) first, replacing them with retrieval markers without extra LLM round-trips.
 * 3. Essential context preservation: protects system prompts, the initial user task, recent turns,
 *    unresolved fatal compiler errors, and diagnostic deltas.
 * 4. Strict tool protocol pairing: preserves assistant tool-call and tool-result relationships
 *    so OpenAI, Anthropic, and Gemini adapters never suffer from dangling IDs.
 * 5. Idempotent: repeated passes do not re-prune or corrupt already-compacted messages.
 */
object ConversationCompactor {

    private val log = LoggerFactory.getLogger(ConversationCompactor::class.java)

    /** Conservative fallback context limit in tokens when model capabilities are unknown. */
    const val DEFAULT_CONSERVATIVE_CONTEXT_LIMIT = 32_000

    /** Usable share of context window allocated to input (reserving remainder for output headroom). */
    const val CONTEXT_WINDOW_INPUT_RATIO = 0.85

    /** Trigger compaction when estimated input reaches this fraction of usable input budget. */
    const val COMPACTION_TRIGGER_RATIO = 0.75

    /** Target input budget ratio after compaction to give ample runway before re-compacting. */
    const val COMPACTION_TARGET_RATIO = 0.60

    /** Reserved output headroom in tokens. */
    const val DEFAULT_RESERVED_HEADROOM_TOKENS = 4_096

    /** Character approximation factor: 4 characters per token average. */
    const val CHARS_PER_TOKEN = 4

    /** Maximum characters to retain for an older tool result before pruning. */
    const val MAX_HISTORICAL_TOOL_RESULT_CHARS = 1_500

    /** Number of most recent tool results to preserve verbatim without pruning. */
    const val PRESERVE_RECENT_TOOL_RESULTS_COUNT = 2

    /** Number of most recent turns to protect from turn-level compaction. */
    const val PRESERVE_RECENT_TURNS_COUNT = 3

    private const val PRUNED_MARKER_PREFIX = "[Historical "
    private const val PRUNED_MARKER_SUBSTRING = "pruned for context:"

    /**
     * Estimates tokens for plain text using a conservative character-based heuristic.
     * Note: This is an approximation and should be identified as such in reports.
     */
    fun estimateTextTokens(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        return (text.length + (CHARS_PER_TOKEN - 1)) / CHARS_PER_TOKEN
    }

    /**
     * Estimates tokens for a single message, accounting for role overhead, content,
     * tool calls, and tool results.
     */
    fun estimateMessageTokens(message: UnifiedChatMessage): Int {
        var chars = message.content.length
        for (tc in message.toolCalls) {
            chars += tc.name.length + tc.argumentsJson.length + tc.id.length + 32
        }
        message.toolResult?.let { tr ->
            chars += tr.toolCallId.length + tr.toolName.length + tr.content.length + 32
        }
        // Base message framing overhead (~4 tokens) + content tokens
        return (chars + (CHARS_PER_TOKEN - 1)) / CHARS_PER_TOKEN + 4
    }

    /**
     * Estimates total input tokens for an assembled request.
     */
    fun estimateRequestTokens(
        systemPrompt: String?,
        tools: List<ToolDefinition>,
        messages: List<UnifiedChatMessage>
    ): Int {
        var total = estimateTextTokens(systemPrompt)
        for (tool in tools) {
            total += (tool.name.length + tool.description.length + 64) / CHARS_PER_TOKEN
            for (p in tool.parameters) {
                total += (p.name.length + p.description.length + p.type.length + 32) / CHARS_PER_TOKEN
            }
        }
        for (msg in messages) {
            total += estimateMessageTokens(msg)
        }
        return total
    }

    /**
     * Computes the context budget for a given model and request.
     */
    fun computeBudget(
        capabilities: ModelCapabilities?,
        systemPrompt: String?,
        tools: List<ToolDefinition>,
        messages: List<UnifiedChatMessage>,
        overrideMaxInputTokens: Int? = null
    ): ContextBudget {
        val reportedLimit = overrideMaxInputTokens ?: capabilities?.contextLength
        val isEstimated = reportedLimit == null || reportedLimit <= 0
        val effectiveLimit = if (isEstimated) DEFAULT_CONSERVATIVE_CONTEXT_LIMIT else reportedLimit!!

        val headroom = capabilities?.maxOutputTokens?.coerceIn(1024, 16384)
            ?: DEFAULT_RESERVED_HEADROOM_TOKENS
        val usableInput = ((effectiveLimit - headroom) * CONTEXT_WINDOW_INPUT_RATIO).toInt()
            .coerceAtLeast(4096)
        val triggerThreshold = (usableInput * COMPACTION_TRIGGER_RATIO).toInt()
        val currentTokens = estimateRequestTokens(systemPrompt, tools, messages)

        return ContextBudget(
            contextLength = effectiveLimit,
            isEstimatedLimit = isEstimated,
            usableInputBudget = usableInput,
            triggerThresholdTokens = triggerThreshold,
            reservedHeadroomTokens = headroom,
            currentEstimatedTokens = currentTokens
        )
    }

    /**
     * Compacts [messages] safely to fit within the model's context budget.
     *
     * @param messages The working copy of conversation messages.
     * @param capabilities Capabilities of the target model (for context window length).
     * @param systemPrompt Active system prompt.
     * @param tools Offered tool definitions.
     * @param forceCompaction If true (e.g. During HTTP 400 overflow recovery), forces compaction
     *                        regardless of whether the trigger threshold was reached.
     */
    fun compactMessages(
        messages: List<UnifiedChatMessage>,
        capabilities: ModelCapabilities? = null,
        systemPrompt: String? = null,
        tools: List<ToolDefinition> = emptyList(),
        forceCompaction: Boolean = false,
        overrideMaxInputTokens: Int? = null
    ): CompactionResult {
        val budget = computeBudget(capabilities, systemPrompt, tools, messages, overrideMaxInputTokens)
        val initialTokens = budget.currentEstimatedTokens

        if (!forceCompaction && !budget.needsCompaction) {
            return CompactionResult(
                messages = messages,
                compacted = false,
                estimatedTokensBefore = initialTokens,
                estimatedTokensAfter = initialTokens,
                prunedToolOutputsCount = 0,
                prunedTurnsCount = 0,
                contextLimitUsed = budget.contextLength,
                isEstimatedLimit = budget.isEstimatedLimit,
                summaryNotice = null
            )
        }

        log.info(
            "Context compaction triggered (current tokens: {}, threshold: {}, limit: {} [estimated: {}], forced: {})",
            initialTokens,
            budget.triggerThresholdTokens,
            budget.contextLength,
            budget.isEstimatedLimit,
            forceCompaction
        )

        val targetTokens = (budget.usableInputBudget * COMPACTION_TARGET_RATIO).toInt()
        var currentWorking = messages.map { it.copy() }.toMutableList()
        var prunedToolOutputs = 0
        var prunedTurns = 0

        // =========================================================================
        // Pass 1: Prune older oversized tool outputs (deterministic pruning)
        // =========================================================================
        val toolMessageIndices = currentWorking.indices.filter { currentWorking[it].role == ChatRole.TOOL }
        val protectedToolIndices = toolMessageIndices.takeLast(PRESERVE_RECENT_TOOL_RESULTS_COUNT).toSet()

        for (idx in toolMessageIndices) {
            if (idx in protectedToolIndices) continue

            val msg = currentWorking[idx]
            val content = msg.content
            if (isAlreadyPruned(content)) continue
            if (content.length <= MAX_HISTORICAL_TOOL_RESULT_CHARS) continue

            // Protect critical fatal diagnostics or active repair wave diagnostic deltas
            if (containsCriticalDiagnostic(content)) continue

            val toolName = msg.toolResult?.toolName ?: "tool"
            val toolCallId = msg.toolResult?.toolCallId ?: "call"
            val originalLength = content.length

            com.tom.rv2ide.artificial.tools.HistoricalToolOutputStore.markPruned(toolCallId)

            val prunedContent = when (toolName) {
                "read_project_file", "read_file" -> {
                    "${PRUNED_MARKER_PREFIX}file read $PRUNED_MARKER_SUBSTRING $toolName (call ID: $toolCallId, original: $originalLength chars). To inspect sections without re-reading, use get_historical_tool_output(callId='$toolCallId', startLine, endLine) or search with query.]"
                }
                "build_current_project", "build_project" -> {
                    val statusPreview = if (content.contains("BUILD SUCCESSFUL", ignoreCase = true)) "SUCCESS" else "FAILED"
                    "${PRUNED_MARKER_PREFIX}build log $PRUNED_MARKER_SUBSTRING $toolName (call ID: $toolCallId, status: $statusPreview, original: $originalLength chars). To inspect lines, use get_historical_tool_output(callId='$toolCallId'). Active compiler errors are tracked in diagnostic delta.]"
                }
                "list_project_files", "list_files" -> {
                    "${PRUNED_MARKER_PREFIX}directory listing $PRUNED_MARKER_SUBSTRING $toolName (call ID: $toolCallId, original: $originalLength chars). To search, use get_historical_tool_output(callId='$toolCallId', query=...) or re-query with list_project_files.]"
                }
                "find_symbol_definition", "lookup_symbol" -> {
                    val compactLines = content.lines().filter { it.trim().startsWith("-") || it.contains("definition(s)") }.take(8).joinToString("\n")
                    "${PRUNED_MARKER_PREFIX}symbol definitions preserved for context: $toolName (call ID: $toolCallId, original: $originalLength chars)]:\n$compactLines"
                }
                "find_symbol_references", "find_references", "inspect_symbol_usages" -> {
                    val compactLines = content.lines().filter { it.trim().startsWith("-") || it.contains("reference(s)") || it.contains("Definitions") || it.contains("Usages") }.take(8).joinToString("\n")
                    "${PRUNED_MARKER_PREFIX}symbol references preserved for context: $toolName (call ID: $toolCallId, original: $originalLength chars)]:\n$compactLines"
                }
                "get_file_outline", "file_outline" -> {
                    val compactLines = content.lines().filter { it.trim().startsWith("L") || it.trim().startsWith("-") || it.contains("SYMBOL OUTLINE") }.take(10).joinToString("\n")
                    "${PRUNED_MARKER_PREFIX}file outline preserved for context: $toolName (call ID: $toolCallId, original: $originalLength chars)]:\n$compactLines"
                }
                "get_type_hierarchy", "type_hierarchy" -> {
                    val compactLines = content.lines().filter { it.trim().startsWith("-") || it.contains("TYPE HIERARCHY") || it.contains("Supertypes") || it.contains("Subtypes") }.take(10).joinToString("\n")
                    "${PRUNED_MARKER_PREFIX}type hierarchy preserved for context: $toolName (call ID: $toolCallId, original: $originalLength chars)]:\n$compactLines"
                }
                else -> {
                    "${PRUNED_MARKER_PREFIX}tool output $PRUNED_MARKER_SUBSTRING $toolName (call ID: $toolCallId, original: $originalLength chars). Use get_historical_tool_output(callId='$toolCallId') to inspect.]"
                }
            }

            val updatedToolResult = msg.toolResult?.copy(content = prunedContent)
                ?: ToolResultMessage(toolCallId = toolCallId, toolName = toolName, content = prunedContent)

            currentWorking[idx] = msg.copy(
                content = prunedContent,
                toolResult = updatedToolResult
            )
            prunedToolOutputs++
        }

        var currentTokens = estimateRequestTokens(systemPrompt, tools, currentWorking)

        // =========================================================================
        // Pass 2: Truncate oversized historical assistant explanations
        // =========================================================================
        if (currentTokens > targetTokens) {
            val assistantIndices = currentWorking.indices.filter { currentWorking[it].role == ChatRole.ASSISTANT }
            val protectedAssistantIndices = assistantIndices.takeLast(PRESERVE_RECENT_TURNS_COUNT).toSet()

            for (idx in assistantIndices) {
                if (idx in protectedAssistantIndices) continue
                val msg = currentWorking[idx]
                if (msg.content.length > 2500 && !isAlreadyPruned(msg.content)) {
                    val snippet = msg.content.take(1200).trimEnd()
                    val truncatedContent = "$snippet\n\n[...Historical assistant explanation compacted (${msg.content.length} chars). Applied changes recorded in session...]"
                    currentWorking[idx] = msg.copy(content = truncatedContent)
                }
            }
            currentTokens = estimateRequestTokens(systemPrompt, tools, currentWorking)
        }

        // =========================================================================
        // Pass 3: Summarize oldest intermediate conversation turns (if still over budget)
        // =========================================================================
        if (currentTokens > targetTokens && currentWorking.size > (PRESERVE_RECENT_TURNS_COUNT * 2 + 2)) {
            // Identify protected boundary:
            // - Index 0 (or first USER message): defines original task
            // - Last PRESERVE_RECENT_TURNS_COUNT * 2 messages: active recent context
            val firstUserIdx = currentWorking.indexOfFirst { it.role == ChatRole.USER }
            val protectedHeadCount = if (firstUserIdx >= 0) firstUserIdx + 1 else 1
            val protectedTailCount = (PRESERVE_RECENT_TURNS_COUNT * 2).coerceAtMost(currentWorking.size - protectedHeadCount)

            val pruneRangeStart = protectedHeadCount
            val pruneRangeEnd = currentWorking.size - protectedTailCount

            if (pruneRangeStart < pruneRangeEnd) {
                val candidates = currentWorking.subList(pruneRangeStart, pruneRangeEnd)

                // Group candidates into complete, valid tool-paired batches to prevent dangling IDs
                val safeToPrune = mutableListOf<UnifiedChatMessage>()
                var i = 0
                while (i < candidates.size) {
                    val msg = candidates[i]
                    if (msg.role == ChatRole.ASSISTANT && msg.toolCalls.isNotEmpty()) {
                        // Gather assistant message and all subsequent matching tool results
                        val batch = mutableListOf(msg)
                        var j = i + 1
                        while (j < candidates.size && candidates[j].role == ChatRole.TOOL) {
                            batch.add(candidates[j])
                            j++
                        }
                        // Only prune if all tool results are present in candidates
                        val expectedCalls = msg.toolCalls.map { it.id }.toSet()
                        val foundResults = batch.filter { it.role == ChatRole.TOOL }.mapNotNull { it.toolResult?.toolCallId }.toSet()
                        if (expectedCalls == foundResults) {
                            safeToPrune.addAll(batch)
                            i = j
                            continue
                        }
                    } else if (msg.role == ChatRole.USER || (msg.role == ChatRole.ASSISTANT && msg.toolCalls.isEmpty())) {
                        safeToPrune.add(msg)
                    }
                    i++
                }

                if (safeToPrune.isNotEmpty()) {
                    val summaryBlock = buildTurnSummary(safeToPrune)
                    val newWorking = mutableListOf<UnifiedChatMessage>()
                    newWorking.addAll(currentWorking.subList(0, pruneRangeStart))
                    newWorking.add(
                        UnifiedChatMessage(
                            role = ChatRole.USER,
                            content = "[Earlier turns summarized (${safeToPrune.size} messages compacted to fit context budget)]:\n$summaryBlock"
                        )
                    )
                    newWorking.add(
                        UnifiedChatMessage(
                            role = ChatRole.ASSISTANT,
                            content = "Understood. I retain full awareness of the tasks, files, and progress from those earlier turns."
                        )
                    )
                    newWorking.addAll(currentWorking.subList(pruneRangeEnd, currentWorking.size))
                    currentWorking = newWorking
                    prunedTurns = safeToPrune.size
                }
            }
        }

        val finalTokens = estimateRequestTokens(systemPrompt, tools, currentWorking)
        val notice = if (prunedToolOutputs > 0 || prunedTurns > 0) {
            "Compacted context: pruned $prunedToolOutputs historical tool output(s) and $prunedTurns older turn(s). Estimated tokens reduced from $initialTokens to $finalTokens."
        } else {
            "Context compacted: reduced estimated tokens from $initialTokens to $finalTokens."
        }

        log.info(notice)

        return CompactionResult(
            messages = currentWorking,
            compacted = true,
            estimatedTokensBefore = initialTokens,
            estimatedTokensAfter = finalTokens,
            prunedToolOutputsCount = prunedToolOutputs,
            prunedTurnsCount = prunedTurns,
            contextLimitUsed = budget.contextLength,
            isEstimatedLimit = budget.isEstimatedLimit,
            summaryNotice = notice
        )
    }

    /**
     * Determines whether an HTTP error is specifically caused by context window
     * or prompt length exhaustion.
     *
     * Hardened to strictly avoid false positives on:
     * - Invalid credentials / authorization tokens (containing "token")
     * - Rate limits / RPM / TPM / quotas (containing "rate_limit_exceeded", "limit exceeded", or HTTP 429)
     * - Generic limits (e.g. "usage limit exceeded", "credit limit")
     * - Invalid request parameters (e.g. parameter 'temperature' or 'max_tokens' exceeds output range)
     * - Execution/system contexts (e.g. "execution context timeout")
     */
    fun isContextLengthError(
        statusCode: Int,
        errorMessage: String?,
        errorCode: String? = null,
        errorType: String? = null
    ): Boolean {
        // Context length overflow is uniquely reported as HTTP 400 or HTTP 413.
        // HTTP 429, 401, 403, 404, 5xx are never context-length errors.
        if (statusCode !in listOf(400, 413)) {
            return false
        }

        val code = errorCode?.trim()?.lowercase().orEmpty()
        val type = errorType?.trim()?.lowercase().orEmpty()
        val msg = errorMessage?.lowercase().orEmpty()

        // -------------------------------------------------------------------------
        // Disqualifier Phase 1: Structured Rate Limit & Quota Codes
        // Rate limits must NEVER trigger context compaction recovery.
        // -------------------------------------------------------------------------
        if (code in listOf(
                "rate_limit_exceeded",
                "requests_per_minute_exceeded",
                "tokens_per_minute_exceeded",
                "insufficient_quota",
                "quota_exceeded",
                "resource_exhausted"
            )
        ) {
            return false
        }
        if (type in listOf("rate_limit_error", "insufficient_quota")) {
            return false
        }

        // -------------------------------------------------------------------------
        // Disqualifier Phase 2: Message-based Rate Limit / Quota / Billing
        // -------------------------------------------------------------------------
        if (msg.contains("rate_limit_exceeded") ||
            msg.contains("rate limit") ||
            msg.contains("too many requests") ||
            msg.contains("quota exceeded") ||
            msg.contains("exceeded your current quota") ||
            msg.contains("insufficient quota") ||
            msg.contains("insufficient balance") ||
            msg.contains("credit limit") ||
            msg.contains("billing") ||
            msg.contains("requests per minute") ||
            msg.contains("tokens per minute") ||
            msg.contains("per minute (rpm)") ||
            msg.contains("per minute (tpm)") ||
            msg.contains("resource_exhausted") ||
            msg.contains("daily limit") ||
            msg.contains("usage limit")
        ) {
            return false
        }

        // -------------------------------------------------------------------------
        // Disqualifier Phase 3: Authentication / Credential / Auth Token Errors
        // "token" here refers to bearer/API credentials, not context tokens.
        // -------------------------------------------------------------------------
        if (code in listOf("invalid_api_key", "unauthorized", "invalid_token", "expired_token", "revoked_token")) {
            return false
        }
        if (type in listOf("authentication_error", "permission_error")) {
            return false
        }
        if (msg.contains("api key") ||
            msg.contains("bearer token") ||
            msg.contains("auth token") ||
            msg.contains("authorization token") ||
            msg.contains("access token") ||
            msg.contains("invalid token") ||
            msg.contains("expired token") ||
            msg.contains("token expired") ||
            msg.contains("jwt") ||
            msg.contains("unauthorized") ||
            msg.contains("authentication failed")
        ) {
            return false
        }

        // -------------------------------------------------------------------------
        // Disqualifier Phase 4: Non-Context System Execution
        // -------------------------------------------------------------------------
        if (msg.contains("execution context") ||
            msg.contains("security context") ||
            msg.contains("thread context") ||
            msg.contains("coroutine context") ||
            msg.contains("ssl context")
        ) {
            return false
        }

        // -------------------------------------------------------------------------
        // Disqualifier Phase 5: Invalid Parameters / Unsupported Features
        // e.g. "max_tokens is too large: 8192. This model supports at most 4096 completion tokens"
        // -------------------------------------------------------------------------
        if (msg.contains("completion tokens") ||
            msg.contains("parameter 'temperature'") ||
            msg.contains("unsupported parameter") ||
            msg.contains("unknown parameter") ||
            msg.contains("missing required parameter") ||
            msg.contains("invalid json") ||
            msg.contains("malformed json") ||
            msg.contains("tools are not supported") ||
            msg.contains("tools not supported")
        ) {
            return false
        }

        // -------------------------------------------------------------------------
        // Match Phase 1: Structured Provider Error Codes & Types
        // -------------------------------------------------------------------------
        if (code in listOf(
                "context_length_exceeded",
                "string_above_max_length",
                "prompt_too_long",
                "context_window_exceeded",
                "max_tokens_exceeded"
            )
        ) {
            return true
        }
        if (type in listOf("request_too_large", "context_length_exceeded")) {
            return true
        }

        // -------------------------------------------------------------------------
        // Match Phase 2: High-Precision Context Window Signatures (OpenAI, Anthropic, Gemini, Ollama)
        // -------------------------------------------------------------------------
        // OpenAI / OpenAI-compatible signatures:
        if (msg.contains("context_length_exceeded") ||
            msg.contains("maximum context length") ||
            msg.contains("max context length") ||
            msg.contains("exceeds the maximum context length") ||
            msg.contains("exceeds the context window") ||
            msg.contains("exceeds context window") ||
            msg.contains("context window is too small") ||
            msg.contains("context limit exceeded") ||
            msg.contains("context length exceeded") ||
            msg.contains("context window exceeded") ||
            msg.contains("input tokens exceed maximum context length") ||
            msg.contains("prompt exceeds context") ||
            msg.contains("prompt is larger than the context window") ||
            (msg.contains("failed to allocate kv cache") && (msg.contains("prompt") || msg.contains("context")))
        ) {
            return true
        }

        // Anthropic signatures:
        if (msg.contains("prompt is too long") ||
            msg.contains("prompt too long") ||
            msg.contains("prompt exceeds the maximum") ||
            msg.contains("prompt length exceeds") ||
            (msg.contains("request is too large") && (msg.contains("token") || msg.contains("maximum"))) ||
            (msg.contains("prompt token count") && msg.contains("exceed"))
        ) {
            return true
        }

        // Google Gemini signatures:
        if ((msg.contains("input token count") && (msg.contains("exceed") || msg.contains("maximum"))) ||
            (msg.contains("input tokens") && msg.contains("exceed") && (msg.contains("maximum") || msg.contains("allowed"))) ||
            (msg.contains("tokens in the prompt") && msg.contains("exceed")) ||
            (msg.contains("request payload size exceeds") && (msg.contains("limit") || msg.contains("maximum")))
        ) {
            return true
        }

        // -------------------------------------------------------------------------
        // Match Phase 3: HTTP 413 (Payload Too Large / Request Entity Too Large)
        // -------------------------------------------------------------------------
        if (statusCode == 413) {
            if (msg.contains("payload too large") ||
                msg.contains("request entity too large") ||
                msg.contains("request too large") ||
                msg.contains("entity too large") ||
                msg.contains("size exceeds") ||
                msg.contains("content length") ||
                msg.isEmpty() ||
                msg.contains("413")
            ) {
                return true
            }
        }

        return false
    }

    /**
     * Determines whether an exception represents a genuine context-length error.
     */
    fun isContextLengthError(throwable: Throwable): Boolean {
        return when (throwable) {
            is ProviderRequestException -> isContextLengthError(
                statusCode = throwable.statusCode,
                errorMessage = throwable.message,
                errorCode = throwable.errorCode,
                errorType = throwable.errorType
            )
            else -> false
        }
    }

    private fun isAlreadyPruned(content: String): Boolean {
        return content.startsWith(PRUNED_MARKER_PREFIX) && content.contains(PRUNED_MARKER_SUBSTRING)
    }

    private fun containsCriticalDiagnostic(content: String): Boolean {
        return content.contains("=== DELTA COMPILER DIAGNOSTICS ===") ||
            content.contains("[!] NEW ERRORS INTRODUCED") ||
            content.contains("[*] PERSISTENT ROOT-CAUSE ERRORS")
    }

    private fun buildTurnSummary(messages: List<UnifiedChatMessage>): String {
        val actions = mutableListOf<String>()
        val filesModified = mutableSetOf<String>()
        val filesRead = mutableSetOf<String>()

        for (msg in messages) {
            for (tc in msg.toolCalls) {
                when (tc.name) {
                    "read_project_file", "read_file" -> {
                        val path = tc.parseArgumentsObject().optString("filePath", "")
                        if (path.isNotBlank()) filesRead.add(File(path).name)
                    }
                    "apply_file_changes", "apply_patch" -> {
                        val path = tc.parseArgumentsObject().optString("filePath", "")
                        if (path.isNotBlank()) filesModified.add(File(path).name)
                    }
                    "build_current_project", "build_project" -> {
                        actions.add("Executed application build")
                    }
                }
            }
        }

        val sb = StringBuilder()
        if (filesModified.isNotEmpty()) {
            sb.appendLine("- Modified files: ${filesModified.joinToString(", ")}")
        }
        if (filesRead.isNotEmpty()) {
            sb.appendLine("- Inspected files: ${filesRead.joinToString(", ")}")
        }
        if (actions.isNotEmpty()) {
            sb.appendLine("- Actions completed: ${actions.distinct().joinToString("; ")}")
        }
        if (sb.isEmpty()) {
            sb.appendLine("- Iterative coding discussion and diagnostic analysis.")
        }

        val latestCheckpoint = com.tom.rv2ide.artificial.coding.TaskCheckpointManager.getLatestCheckpoint()
        if (latestCheckpoint != null) {
            sb.appendLine("\n" + latestCheckpoint.toMarkdownSummary())
        }

        return sb.toString().trim()
    }
}
