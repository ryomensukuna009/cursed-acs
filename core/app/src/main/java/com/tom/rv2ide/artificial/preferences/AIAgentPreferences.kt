package com.tom.rv2ide.artificial.preferences

import android.content.Context
import com.tom.rv2ide.artificial.models.ReasoningEffort

object AIAgentPreferences {
    const val PREFS_NAME = "ai_agent_prefs"
    const val KEY_REASONING_EFFORT = "ai_agent_reasoning_effort"

    fun getReasoningEffort(context: Context? = null): ReasoningEffort {
        val ctx = context?.applicationContext
            ?: try { com.tom.rv2ide.app.IDEApplication.instance.applicationContext } catch (_: Exception) { null }
        if (ctx == null) return ReasoningEffort.AUTO
        val pref = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = pref.getString(KEY_REASONING_EFFORT, ReasoningEffort.AUTO.key)
        return ReasoningEffort.fromKey(key)
    }

    /**
     * Resolves effective reasoning effort:
     * If the model name explicitly declares reasoning effort (e.g. qwen...:low, ...:medium, ...:xhigh),
     * respects and listens to the model's requested effort.
     * Otherwise, falls back to the user's slider setting.
     */
    fun getEffectiveReasoningEffort(context: Context? = null, modelId: String? = null): ReasoningEffort {
        val modelDeclared = ReasoningEffort.detectFromModelId(modelId)
        if (modelDeclared != null) {
            return modelDeclared
        }
        return getReasoningEffort(context)
    }

    fun setReasoningEffort(context: Context, effort: ReasoningEffort) {
        val pref = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        pref.edit().putString(KEY_REASONING_EFFORT, effort.key).apply()
    }
}
