package com.tom.rv2ide.artificial.preferences

import android.content.Context
import com.tom.rv2ide.artificial.models.ReasoningEffort

object AIAgentPreferences {
    const val PREFS_NAME = "ai_agent_prefs"
    const val KEY_REASONING_EFFORT = "ai_agent_reasoning_effort"

    fun getReasoningEffort(context: Context? = null): ReasoningEffort {
        val ctx = context?.applicationContext
            ?: try { com.tom.rv2ide.app.IDEApplication.instance.applicationContext } catch (_: Exception) { null }
        if (ctx == null) return ReasoningEffort.MEDIUM
        val pref = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = pref.getString(KEY_REASONING_EFFORT, ReasoningEffort.MEDIUM.key)
        return ReasoningEffort.fromKey(key)
    }

    fun setReasoningEffort(context: Context, effort: ReasoningEffort) {
        val pref = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        pref.edit().putString(KEY_REASONING_EFFORT, effort.key).apply()
    }
}
