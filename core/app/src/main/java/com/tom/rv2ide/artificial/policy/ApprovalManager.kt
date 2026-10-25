package com.tom.rv2ide.artificial.policy

import android.content.Context
import androidx.preference.PreferenceManager
import com.tom.rv2ide.builder.SharedBuildOrchestrator

enum class ApprovalDecision {
    APPROVE_ONCE,
    APPROVE_ALWAYS,
    REFUSE
}

object ApprovalManager {

    const val MODE_TURBO = "turbo"
    const val MODE_APPROVE = "approve"

    private const val PREF_KEY_APPROVAL_MODE = "pref_ai_approval_mode"

    fun getApprovalMode(context: Context): String {
        return try {
            val sp = PreferenceManager.getDefaultSharedPreferences(context)
            sp.getString(PREF_KEY_APPROVAL_MODE, MODE_TURBO) ?: MODE_TURBO
        } catch (_: Exception) {
            MODE_TURBO
        }
    }

    fun setApprovalMode(context: Context, mode: String) {
        try {
            val sp = PreferenceManager.getDefaultSharedPreferences(context)
            sp.edit().putString(PREF_KEY_APPROVAL_MODE, mode).apply()
        } catch (_: Exception) {}
    }

    fun isTurbo(context: Context): Boolean = getApprovalMode(context) == MODE_TURBO

    fun isApproved(context: Context, actionKey: String): Boolean {
        return ActionPolicyGate.isActionPermittedSync(
            context = context,
            actionType = ActionType.MODIFY_FILE,
            targetDetail = actionKey
        )
    }

    suspend fun requestApproval(
        context: Context,
        actionName: String,
        targetDetail: String,
        actionKey: String = targetDetail,
        proposedDiff: String? = null
    ): ApprovalDecision {
        val actionType = when {
            actionName.contains("Build", ignoreCase = true) -> ActionType.EXECUTE_BUILD
            actionName.contains("Create", ignoreCase = true) -> ActionType.CREATE_FILE
            actionName.contains("Delete", ignoreCase = true) -> ActionType.DELETE_FILE
            actionName.contains("Import", ignoreCase = true) -> ActionType.RESOLVE_IMPORT
            actionName.contains("Command", ignoreCase = true) -> ActionType.EXECUTE_COMMAND
            else -> ActionType.MODIFY_FILE
        }

        val projectRoot = SharedBuildOrchestrator.getActiveProjectDir()
        val result = ActionPolicyGate.checkAndRequestApproval(
            actionType = actionType,
            targetDetail = targetDetail,
            projectRoot = projectRoot,
            proposedDiff = proposedDiff
        )

        return when (result) {
            UserApprovalResult.ALLOW_ONCE -> ApprovalDecision.APPROVE_ONCE
            UserApprovalResult.ALLOW_ALWAYS -> ApprovalDecision.APPROVE_ALWAYS
            UserApprovalResult.REFUSE,
            UserApprovalResult.DENIED_BY_POLICY -> ApprovalDecision.REFUSE
        }
    }
}
