package com.tom.rv2ide.artificial.dialogs

import android.content.Context
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.models.ReasoningEffort
import com.tom.rv2ide.artificial.preferences.AIAgentPreferences

object ReasoningEffortSliderDialog {

    fun show(
        context: Context,
        currentEffort: ReasoningEffort = AIAgentPreferences.getReasoningEffort(),
        onEffortChanged: (ReasoningEffort) -> Unit
    ) {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_reasoning_effort_slider, null)

        val tvSelectedBadge: MaterialTextView = view.findViewById(R.id.tvEffortSelectedBadge)
        val tvDescription: MaterialTextView = view.findViewById(R.id.tvEffortDescription)
        val slider: Slider = view.findViewById(R.id.effortSlider)

        val labels = listOf<TextView>(
            view.findViewById(R.id.lblStopAuto),
            view.findViewById(R.id.lblStopLow),
            view.findViewById(R.id.lblStopMed),
            view.findViewById(R.id.lblStopHigh),
            view.findViewById(R.id.lblStopMax)
        )

        val effortLevels = listOf(
            ReasoningEffort.AUTO,
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
            ReasoningEffort.MAXIMUM
        )

        val descriptions = listOf(
            "Model dynamically adjusts internal reasoning tokens based on task complexity.",
            "Fast responses with minimal reasoning (~1,024 thinking tokens). Ideal for quick edits.",
            "Balanced reasoning depth and latency (~4,096 tokens). Great for general coding.",
            "Deep step-by-step thinking (~16,384 tokens). Ideal for complex algorithms & multi-file bugs.",
            "Maximum reasoning depth (~32,768 tokens). Exhaustive architectural and NDK root-cause analysis."
        )

        val primaryColor = try {
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
            typedValue.data
        } catch (_: Exception) {
            android.graphics.Color.parseColor("#8AB4F8")
        }

        val onSurfaceVariantColor = try {
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true)
            typedValue.data
        } catch (_: Exception) {
            android.graphics.Color.GRAY
        }

        fun updateUI(index: Int) {
            val clamped = index.coerceIn(0, effortLevels.size - 1)
            val effort = effortLevels[clamped]
            tvSelectedBadge.text = "${effort.name} (${effort.displayName})"
            tvDescription.text = descriptions[clamped]

            labels.forEachIndexed { i, lbl ->
                if (i == clamped) {
                    lbl.setTextColor(primaryColor)
                    lbl.typeface = Typeface.DEFAULT_BOLD
                } else {
                    lbl.setTextColor(onSurfaceVariantColor)
                    lbl.typeface = Typeface.DEFAULT
                }
            }
        }

        val initialIndex = effortLevels.indexOf(currentEffort).let { if (it >= 0) it else 0 }
        slider.value = initialIndex.toFloat()
        updateUI(initialIndex)

        slider.addOnChangeListener { s, value, fromUser ->
            val idx = value.toInt()
            updateUI(idx)
            if (fromUser) {
                try {
                    s.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                } catch (_: Exception) {}
            }
        }

        MaterialAlertDialogBuilder(context)
            .setView(view)
            .setPositiveButton("Apply") { _, _ ->
                val chosenIndex = slider.value.toInt().coerceIn(0, effortLevels.size - 1)
                val chosenEffort = effortLevels[chosenIndex]
                AIAgentPreferences.setReasoningEffort(context, chosenEffort)
                onEffortChanged(chosenEffort)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
