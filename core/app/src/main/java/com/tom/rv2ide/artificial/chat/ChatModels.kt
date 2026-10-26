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



import com.tom.rv2ide.artificial.transport.ChatRole

import com.tom.rv2ide.artificial.transport.CitationItem

import com.tom.rv2ide.artificial.transport.ToolCall

import com.tom.rv2ide.artificial.transport.ToolExecutionStatus

import com.tom.rv2ide.artificial.transport.ToolResultMessage

import com.tom.rv2ide.artificial.transport.UnifiedChatMessage

import java.util.UUID

import org.json.JSONArray

import org.json.JSONObject



enum class VerificationLevel(val label: String) {

    BUILT_AND_VERIFIED("Built & Verified"),

    RUNTIME_VERIFIED("Runtime Verified"),

    TESTS_PASSED("Tests Passed"),

    STATICALLY_VERIFIED("Statically Verified"),

    EDITED_UNVERIFIED("Edited (Unverified)"),

    IMPLEMENTED_UNVERIFIED("Implemented (Unverified)"),

    NOT_VERIFIED("Not Verified"),

    FAILED_WITH_ERRORS("Verification Failed"),

    ROLLED_BACK("Rolled Back")

}



data class ChatFileModification(

    val filePath: String,

    val fileName: String,

    val isNewFile: Boolean,

    val success: Boolean,

    val statusMessage: String = if (success) (if (isNewFile) "Created" else "Modified") else "Failed"

) {

    fun toJson(): JSONObject = JSONObject().apply {

        put("filePath", filePath)

        put("fileName", fileName)

        put("isNewFile", isNewFile)

        put("success", success)

        put("statusMessage", statusMessage)

    }



    companion object {

        fun fromJson(obj: JSONObject): ChatFileModification = ChatFileModification(

            filePath = obj.optString("filePath", ""),

            fileName = obj.optString("fileName", ""),

            isNewFile = obj.optBoolean("isNewFile", false),

            success = obj.optBoolean("success", false),

            statusMessage = obj.optString("statusMessage", "")

        )

    }

}



data class AgentCompletionReport(

    val goalOrRootCause: String,

    val filesModified: List<String>,

    val mainChangesSummary: String,

    val validationChecksRun: List<String>,

    val validationPassed: Boolean,

    val verificationLevel: VerificationLevel,

    val repairAttemptsUsed: Int = 0,

    val remainingErrors: List<String> = emptyList(),

    val nextActionableStep: String? = null,

    val rollbackSessionId: String? = null,

    val isRolledBack: Boolean = false

) {

    fun formatSummaryText(): String = buildString {

        if (isRolledBack) {

            appendLine("↩️ All file modifications from this run were reverted.")

            return@buildString

        }

        if (validationPassed) {

            appendLine("✅ Build and verification completed successfully.")

        } else if (remainingErrors.isNotEmpty()) {

            appendLine("⚠️ Verification found ${remainingErrors.size} issue${if (remainingErrors.size == 1) "" else "s"}.")

        }

        if (filesModified.isNotEmpty()) {

            appendLine("Files (${filesModified.size}): ${filesModified.joinToString(", ") { java.io.File(it).name }}")

        }

        if (mainChangesSummary.isNotBlank()) {

            appendLine(mainChangesSummary)

        } else if (goalOrRootCause.isNotBlank()) {

            appendLine(goalOrRootCause)

        }

        if (remainingErrors.isNotEmpty()) {

            remainingErrors.take(3).forEach { err ->

                appendLine("  • $err")

            }

        }

    }.trim()



    fun toJson(): JSONObject = JSONObject().apply {

        put("goalOrRootCause", goalOrRootCause)

        put("filesModified", JSONArray(filesModified))

        put("mainChangesSummary", mainChangesSummary)

        put("validationChecksRun", JSONArray(validationChecksRun))

        put("validationPassed", validationPassed)

        put("verificationLevel", verificationLevel.name)

        put("repairAttemptsUsed", repairAttemptsUsed)

        put("remainingErrors", JSONArray(remainingErrors))

        if (nextActionableStep != null) put("nextActionableStep", nextActionableStep)

        if (rollbackSessionId != null) put("rollbackSessionId", rollbackSessionId)

        put("isRolledBack", isRolledBack)

    }



    companion object {

        fun fromJson(obj: JSONObject): AgentCompletionReport {

            val filesArr = obj.optJSONArray("filesModified") ?: JSONArray()

            val checksArr = obj.optJSONArray("validationChecksRun") ?: JSONArray()

            val errsArr = obj.optJSONArray("remainingErrors") ?: JSONArray()

            val levelName = obj.optString("verificationLevel", VerificationLevel.EDITED_UNVERIFIED.name)

            val level = try {

                VerificationLevel.valueOf(levelName)

            } catch (_: Exception) {

                VerificationLevel.EDITED_UNVERIFIED

            }

            return AgentCompletionReport(

                goalOrRootCause = obj.optString("goalOrRootCause", ""),

                filesModified = (0 until filesArr.length()).mapNotNull { filesArr.optString(it).takeIf { s -> s.isNotEmpty() } },

                mainChangesSummary = obj.optString("mainChangesSummary", ""),

                validationChecksRun = (0 until checksArr.length()).mapNotNull { checksArr.optString(it).takeIf { s -> s.isNotEmpty() } },

                validationPassed = obj.optBoolean("validationPassed", true),

                verificationLevel = level,

                repairAttemptsUsed = obj.optInt("repairAttemptsUsed", 0),

                remainingErrors = (0 until errsArr.length()).mapNotNull { errsArr.optString(it).takeIf { s -> s.isNotEmpty() } },

                nextActionableStep = obj.optString("nextActionableStep", "").takeIf { it.isNotBlank() },

                rollbackSessionId = obj.optString("rollbackSessionId", "").takeIf { it.isNotBlank() },

                isRolledBack = obj.optBoolean("isRolledBack", false)

            )

        }

    }

}



data class ChatMessageItem(

    val id: String = UUID.randomUUID().toString(),

    val conversationId: String,

    val role: ChatRole,

    val content: String,

    val rawModelResponse: String? = null,

    val timestamp: Long = System.currentTimeMillis(),

    val providerId: String? = null,

    val providerName: String? = null,

    val modelId: String? = null,

    val isStreaming: Boolean = false,

    val isError: Boolean = false,

    val isCancelled: Boolean = false,

    val activeToolStatus: String? = null,

    val toolStatusHistory: List<String> = emptyList(),

    val citations: List<CitationItem> = emptyList(),

    val fileModifications: List<ChatFileModification> = emptyList(),

    val completionReport: AgentCompletionReport? = null,

    val toolCalls: List<ToolCall> = emptyList(),

    val toolResult: ToolResultMessage? = null,

    val imageBase64: String? = null,

    val imageMimeType: String? = null
) {

    fun toJson(): JSONObject = JSONObject().apply {

        put("id", id)

        put("conversationId", conversationId)

        put("role", role.name)

        put("content", content)

        if (rawModelResponse != null) put("rawModelResponse", rawModelResponse)

        put("timestamp", timestamp)

        if (providerId != null) put("providerId", providerId)

        if (providerName != null) put("providerName", providerName)

        if (modelId != null) put("modelId", modelId)

        put("isStreaming", false) // Never persist an active streaming state on disk

        put("isError", isError)

        put("isCancelled", isCancelled)

        put("toolStatusHistory", JSONArray(toolStatusHistory))



        val citArr = JSONArray()

        citations.forEach { c ->

            citArr.put(c.toJson())

        }

        put("citations", citArr)



        val modArr = JSONArray()

        fileModifications.forEach { m -> modArr.put(m.toJson()) }

        put("fileModifications", modArr)



        if (imageBase64 != null) put("imageBase64", imageBase64)
        if (imageMimeType != null) put("imageMimeType", imageMimeType)

        if (toolCalls.isNotEmpty()) {

            val tcArr = JSONArray()

            toolCalls.forEach { tc ->

                val tcObj = JSONObject().apply {

                    put("id", tc.id)

                    put("name", tc.name)

                    put("argumentsJson", tc.argumentsJson)

                }

                tcArr.put(tcObj)

            }

            put("toolCalls", tcArr)

        }



        if (toolResult != null) {

            val trObj = JSONObject().apply {

                put("toolCallId", toolResult.toolCallId)

                put("toolName", toolResult.toolName)

                put("content", toolResult.content)

                put("isError", toolResult.isError)

                put("status", toolResult.status.name)

                put("executionStarted", toolResult.executionStarted)

                put("mutationOccurred", toolResult.mutationOccurred)

                put("isRecoverable", toolResult.isRecoverable)

                if (toolResult.exitCode != null) put("exitCode", toolResult.exitCode)

            }

            put("toolResult", trObj)

        }



        if (completionReport != null) {

            put("completionReport", completionReport.toJson())

        }

    }



    fun toUnifiedChatMessage(): UnifiedChatMessage {

        return UnifiedChatMessage(

            role = this.role,

            content = this.content,

            toolCalls = this.toolCalls,

            toolResult = this.toolResult,
            imageBase64 = this.imageBase64,
            imageMimeType = this.imageMimeType
        )

    }



    companion object {

        fun fromUnifiedChatMessage(uMsg: UnifiedChatMessage, convId: String): ChatMessageItem {

            return ChatMessageItem(

                conversationId = convId,

                role = uMsg.role,

                content = uMsg.content,

                toolCalls = uMsg.toolCalls,

                toolResult = uMsg.toolResult,
                imageBase64 = uMsg.imageBase64,
                imageMimeType = uMsg.imageMimeType
            )

        }



        fun fromJson(obj: JSONObject): ChatMessageItem {

            val roleStr = obj.optString("role", ChatRole.USER.name)

            val role = try {

                ChatRole.valueOf(roleStr)

            } catch (_: Exception) {

                ChatRole.USER

            }

            val toolHistArr = obj.optJSONArray("toolStatusHistory") ?: JSONArray()

            val toolHist = (0 until toolHistArr.length()).mapNotNull {

                toolHistArr.optString(it).takeIf { s -> s.isNotBlank() }

            }

            val citArr = obj.optJSONArray("citations") ?: JSONArray()

            val citations = (0 until citArr.length()).mapNotNull { idx ->

                citArr.optJSONObject(idx)?.let { c -> CitationItem.fromJson(c) }

            }

            val modArr = obj.optJSONArray("fileModifications") ?: JSONArray()

            val mods = (0 until modArr.length()).mapNotNull { idx ->

                modArr.optJSONObject(idx)?.let { ChatFileModification.fromJson(it) }

            }

            val reportObj = obj.optJSONObject("completionReport")

            val report = reportObj?.let { AgentCompletionReport.fromJson(it) }



            val tcArr = obj.optJSONArray("toolCalls")

            val parsedToolCalls = if (tcArr != null) {

                (0 until tcArr.length()).mapNotNull { idx ->

                    val tcObj = tcArr.optJSONObject(idx) ?: return@mapNotNull null

                    ToolCall(

                        id = tcObj.optString("id", ""),

                        name = tcObj.optString("name", ""),

                        argumentsJson = tcObj.optString("argumentsJson", "{}")

                    )

                }

            } else emptyList()



            val trObj = obj.optJSONObject("toolResult")

            val parsedToolResult = if (trObj != null) {

                val isErr = trObj.optBoolean("isError", false)

                val defaultStatus = if (isErr) ToolExecutionStatus.EXECUTION_FAILURE else ToolExecutionStatus.SUCCESS

                val statusStr = trObj.optString("status", "")

                val status = try {

                    if (statusStr.isNotEmpty()) ToolExecutionStatus.valueOf(statusStr) else defaultStatus

                } catch (_: Exception) {

                    defaultStatus

                }

                val exitCode = if (trObj.has("exitCode")) trObj.optInt("exitCode") else null

                ToolResultMessage(

                    toolCallId = trObj.optString("toolCallId", ""),

                    toolName = trObj.optString("toolName", ""),

                    content = trObj.optString("content", ""),

                    isError = isErr,

                    status = status,

                    executionStarted = trObj.optBoolean("executionStarted", true),

                    mutationOccurred = trObj.optBoolean("mutationOccurred", false),

                    isRecoverable = trObj.optBoolean("isRecoverable", true),

                    exitCode = exitCode

                )

            } else null



            return ChatMessageItem(

                id = obj.optString("id", UUID.randomUUID().toString()),

                conversationId = obj.optString("conversationId", ""),

                role = role,

                content = obj.optString("content", ""),

                rawModelResponse = obj.optString("rawModelResponse", "").takeIf { it.isNotEmpty() },

                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),

                providerId = obj.optString("providerId", "").takeIf { it.isNotEmpty() },

                providerName = obj.optString("providerName", "").takeIf { it.isNotEmpty() },

                modelId = obj.optString("modelId", "").takeIf { it.isNotEmpty() },

                isStreaming = false,

                isError = obj.optBoolean("isError", false),

                isCancelled = obj.optBoolean("isCancelled", false),

                activeToolStatus = null,

                toolStatusHistory = toolHist,

                citations = citations,

                fileModifications = mods,

                completionReport = report,

                toolCalls = parsedToolCalls,

                toolResult = parsedToolResult

            )

        }

    }

}



data class ChatConversation(

    val id: String = UUID.randomUUID().toString(),

    val title: String = "New Conversation",

    val projectPath: String = "",

    val createdAt: Long = System.currentTimeMillis(),

    val updatedAt: Long = System.currentTimeMillis(),

    val messages: List<ChatMessageItem> = emptyList()

) {

    fun toJson(): JSONObject = JSONObject().apply {

        put("id", id)

        put("title", title)

        put("projectPath", projectPath)

        put("createdAt", createdAt)

        put("updatedAt", updatedAt)

        val msgArr = JSONArray()

        messages.forEach { msgArr.put(it.toJson()) }

        put("messages", msgArr)

    }



    companion object {

        fun fromJson(obj: JSONObject): ChatConversation {

            val msgArr = obj.optJSONArray("messages") ?: JSONArray()

            val msgs = (0 until msgArr.length()).mapNotNull { idx ->

                msgArr.optJSONObject(idx)?.let { ChatMessageItem.fromJson(it) }

            }

            return ChatConversation(

                id = obj.optString("id", UUID.randomUUID().toString()),

                title = obj.optString("title", "Conversation"),

                projectPath = obj.optString("projectPath", ""),

                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),

                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),

                messages = msgs

            )

        }

    }

}

