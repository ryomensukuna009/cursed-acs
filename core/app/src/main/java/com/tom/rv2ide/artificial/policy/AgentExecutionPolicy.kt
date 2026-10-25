package com.tom.rv2ide.artificial.policy

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.preference.PreferenceManager
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.tools.AgentToolRegistry

enum class AgentExecutionMode(
    val key: String,
    val displayName: String,
    val shortBadge: String,
    val description: String,
    @DrawableRes val iconRes: Int
) {
    AUTONOMOUS(
        key = "autonomous",
        displayName = "Autonomous Agent",
        shortBadge = "🤖 Auto",
        description = "Autonomous multi-step coding, builds, and repair loops with transactional safety.",
        iconRes = R.drawable.ic_nav_ai_agent_badass
    ),
    SIDE_AGENT(
        key = "side_agent",
        displayName = "Side Agent",
        shortBadge = "👥 Side",
        description = "Interactive assistant beside your editor. Explains, reviews, and prepares patches for approval.",
        iconRes = R.drawable.ic_chat
    );

    companion object {
        private const val PREF_KEY_EXECUTION_MODE = "pref_ai_agent_execution_mode"

        fun fromKey(key: String?): AgentExecutionMode {
            return entries.find { it.key.equals(key, ignoreCase = true) } ?: AUTONOMOUS
        }

        fun getSavedMode(context: Context): AgentExecutionMode {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val savedKey = try {
                prefs.getString(PREF_KEY_EXECUTION_MODE, AUTONOMOUS.key)
            } catch (_: Exception) {
                AUTONOMOUS.key
            }
            return fromKey(savedKey)
        }

        fun saveMode(context: Context, mode: AgentExecutionMode) {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                prefs.edit().putString(PREF_KEY_EXECUTION_MODE, mode.key).apply()
            } catch (_: Exception) {}
        }
    }
}

enum class ToolCategory(val displayName: String) {
    READ_ONLY("Read-Only Inspection"),
    REVERSIBLE_EDIT("Reversible File Edit"),
    POTENTIALLY_DESTRUCTIVE("Destructive Operation"),
    BUILD_TEST("Build & Test Execution"),
    EXTERNAL_NETWORK("External Network Operation")
}

sealed class ToolPolicyDecision {
    object Allow : ToolPolicyDecision()
    data class RequireUserApproval(val reason: String, val proposedDiffOrSummary: String? = null) : ToolPolicyDecision()
    data class Deny(val reason: String) : ToolPolicyDecision()
}

object AgentToolPolicy {

    fun categorizeTool(toolName: String): ToolCategory {
        val canonical = AgentToolRegistry.canonicalToolName(toolName)
        return when (canonical) {
            AgentToolRegistry.TOOL_APPLY_FILE_CHANGES,
            AgentToolRegistry.TOOL_RESOLVE_IMPORT,
            AgentToolRegistry.TOOL_ROLLBACK_AGENT_BATCH -> ToolCategory.REVERSIBLE_EDIT

            AgentToolRegistry.TOOL_BUILD_CURRENT_PROJECT -> ToolCategory.BUILD_TEST

            AgentToolRegistry.TOOL_WEB_SEARCH,
            AgentToolRegistry.TOOL_OPEN_URL -> ToolCategory.EXTERNAL_NETWORK

            else -> ToolCategory.READ_ONLY
        }
    }

    fun evaluateExecution(
        toolName: String,
        mode: AgentExecutionMode,
        isExplicitlyApproved: Boolean = false
    ): ToolPolicyDecision {
        val category = categorizeTool(toolName)
        if (mode == AgentExecutionMode.AUTONOMOUS) {
            return ToolPolicyDecision.Allow
        }

        // Side-Agent Mode Execution Policies:
        return when (category) {
            ToolCategory.READ_ONLY,
            ToolCategory.EXTERNAL_NETWORK -> ToolPolicyDecision.Allow

            ToolCategory.REVERSIBLE_EDIT -> {
                if (isExplicitlyApproved) {
                    ToolPolicyDecision.Allow
                } else {
                    ToolPolicyDecision.RequireUserApproval(
                        reason = "Side-Agent mode requires user approval before modifying project files.",
                        proposedDiffOrSummary = null
                    )
                }
            }

            ToolCategory.BUILD_TEST -> {
                // In Side-Agent mode, builds run non-destructively for verification
                ToolPolicyDecision.Allow
            }

            ToolCategory.POTENTIALLY_DESTRUCTIVE -> {
                if (isExplicitlyApproved) ToolPolicyDecision.Allow
                else ToolPolicyDecision.Deny("Destructive actions are prohibited in Side-Agent mode.")
            }
        }
    }
}
