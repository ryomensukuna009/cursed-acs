package com.tom.rv2ide.artificial.stats

import android.content.Context
import android.content.SharedPreferences
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.R
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TokenTelemetryTracker private constructor(context: Context) {

    private val appContext = context.applicationContext ?: context
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var sessionPromptTokens: Long = 0
    private var sessionCompletionTokens: Long = 0
    private var sessionCachedTokens: Long = 0
    private var sessionRequestCount: Int = 0
    private var sessionTotalLatencyMs: Long = 0

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    @Synchronized
    fun recordTurnUsage(
        promptTokens: Int,
        completionTokens: Int,
        cachedTokens: Int = 0,
        latencyMs: Long = 0
    ) {
        sessionPromptTokens += promptTokens
        sessionCompletionTokens += completionTokens
        sessionCachedTokens += cachedTokens
        sessionRequestCount++
        sessionTotalLatencyMs += latencyMs

        // Update persistent lifetime counters
        val totalPrompt = prefs.getLong(KEY_TOTAL_PROMPT, 0L) + promptTokens
        val totalCompletion = prefs.getLong(KEY_TOTAL_COMPLETION, 0L) + completionTokens
        val totalCached = prefs.getLong(KEY_TOTAL_CACHED, 0L) + cachedTokens
        val totalRequests = prefs.getInt(KEY_TOTAL_REQUESTS, 0) + 1

        val todayKey = dayFormat.format(Date())
        val dailyJsonStr = prefs.getString(KEY_DAILY_STATS_JSON, "{}") ?: "{}"
        val dailyObj = try { JSONObject(dailyJsonStr) } catch (_: Exception) { JSONObject() }
        val todayObj = dailyObj.optJSONObject(todayKey) ?: JSONObject()

        todayObj.put("prompt", todayObj.optLong("prompt", 0L) + promptTokens)
        todayObj.put("completion", todayObj.optLong("completion", 0L) + completionTokens)
        todayObj.put("cached", todayObj.optLong("cached", 0L) + cachedTokens)
        todayObj.put("requests", todayObj.optInt("requests", 0) + 1)
        dailyObj.put(todayKey, todayObj)

        prefs.edit()
            .putLong(KEY_TOTAL_PROMPT, totalPrompt)
            .putLong(KEY_TOTAL_COMPLETION, totalCompletion)
            .putLong(KEY_TOTAL_CACHED, totalCached)
            .putInt(KEY_TOTAL_REQUESTS, totalRequests)
            .putString(KEY_DAILY_STATS_JSON, dailyObj.toString())
            .apply()
    }

    fun showTelemetryDialog(context: Context) {
        val totalPrompt = prefs.getLong(KEY_TOTAL_PROMPT, 0L)
        val totalCompletion = prefs.getLong(KEY_TOTAL_COMPLETION, 0L)
        val totalCached = prefs.getLong(KEY_TOTAL_CACHED, 0L)
        val totalRequests = prefs.getInt(KEY_TOTAL_REQUESTS, 0)

        val totalTokens = totalPrompt + totalCompletion
        val avgLatency = if (sessionRequestCount > 0) sessionTotalLatencyMs / sessionRequestCount else 0L

        val message = SpannableStringBuilder().apply {
            append("CURRENT SESSION TELEMETRY:\n", StyleSpan(android.graphics.Typeface.BOLD), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("• Requests Completed: $sessionRequestCount\n")
            append("• Prompt / Input Tokens: ${formatNumber(sessionPromptTokens)}\n")
            append("• Completion / Output Tokens: ${formatNumber(sessionCompletionTokens)}\n")
            append("• Context Cached Tokens: ${formatNumber(sessionCachedTokens)}\n")
            append("• Total Session Tokens: ${formatNumber(sessionPromptTokens + sessionCompletionTokens)}\n")
            append("• Average Request Latency: ${avgLatency}ms\n\n")

            append("LIFETIME IDE CODING METRICS:\n", StyleSpan(android.graphics.Typeface.BOLD), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("• Total AI Interactions: ${formatNumber(totalRequests.toLong())}\n")
            append("• Cumulative Tokens: ${formatNumber(totalTokens)}\n")
            append("• Total Cached Reads: ${formatNumber(totalCached)}\n")
            if (totalTokens > 0) {
                val cacheEfficiency = (totalCached.toDouble() / (totalTokens + totalCached) * 100).toInt()
                append("• Cache Efficiency: $cacheEfficiency%\n")
            }
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Developer AI Telemetry")
            .setIcon(R.drawable.ic_ai_agent)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Reset Lifetime") { _, _ ->
                prefs.edit().clear().apply()
                sessionPromptTokens = 0
                sessionCompletionTokens = 0
                sessionCachedTokens = 0
                sessionRequestCount = 0
                sessionTotalLatencyMs = 0
            }
            .show()
    }

    private fun formatNumber(n: Long): String {
        return when {
            n >= 1_000_000 -> String.format(Locale.US, "%.2fM", n / 1_000_000.0)
            n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
            else -> n.toString()
        }
    }

    companion object {
        private const val PREFS_NAME = "cursed_acs_telemetry_prefs"
        private const val KEY_TOTAL_PROMPT = "total_prompt_tokens"
        private const val KEY_TOTAL_COMPLETION = "total_completion_tokens"
        private const val KEY_TOTAL_CACHED = "total_cached_tokens"
        private const val KEY_TOTAL_REQUESTS = "total_requests_count"
        private const val KEY_DAILY_STATS_JSON = "daily_stats_json"

        @Volatile
        private var instance: TokenTelemetryTracker? = null

        fun getInstance(context: Context): TokenTelemetryTracker {
            return instance ?: synchronized(this) {
                instance ?: TokenTelemetryTracker(context).also { instance = it }
            }
        }
    }
}
