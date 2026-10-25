package com.tom.rv2ide.artificial.diff

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import androidx.core.graphics.ColorUtils

/**
 * Renders DiffFileResult into a rich, theme-aware SpannableStringBuilder.
 *
 * Automatically adapts text and background tints to Light, Dark, and CyanCream
 * color schemes using luminance detection against the active theme surface container.
 */
object DiffVisualFormatter {

    fun formatDiffSpannable(
        diff: DiffFileResult,
        context: Context,
        onlyHunks: Boolean = true
    ): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        val isLight = isLightTheme(context)

        val colAddFg = if (isLight) Color.parseColor("#1B5E20") else Color.parseColor("#81C784")
        val colAddBg = if (isLight) Color.argb(28, 46, 125, 50) else Color.argb(40, 46, 125, 50)

        val colDelFg = if (isLight) Color.parseColor("#B71C1C") else Color.parseColor("#E57373")
        val colDelBg = if (isLight) Color.argb(28, 198, 40, 40) else Color.argb(40, 198, 40, 40)

        val colHeaderFg = if (isLight) Color.parseColor("#006064") else Color.parseColor("#4DD0E1")
        val colHeaderBg = if (isLight) Color.argb(20, 0, 151, 167) else Color.argb(30, 0, 188, 212)

        val colLineNum = if (isLight) Color.parseColor("#78909C") else Color.parseColor("#90A4AE")
        val colNormalFg = resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface, if (isLight) Color.BLACK else Color.WHITE)

        if (diff.isUnchanged) {
            val start = ssb.length
            ssb.append("  [File is unchanged: ${diff.relativePath}]\n")
            ssb.setSpan(ForegroundColorSpan(colLineNum), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return ssb
        }

        val linesToRender = if (onlyHunks && diff.hunks.isNotEmpty()) {
            val list = mutableListOf<Any>() // contains DiffHunk or DiffLine
            for (hunk in diff.hunks) {
                list.add(hunk)
                list.addAll(hunk.lines)
            }
            list
        } else {
            diff.allLines
        }

        for (item in linesToRender) {
            if (item is DiffHunk) {
                val start = ssb.length
                val headerText = "─── ${item.header()} ───\n"
                ssb.append(headerText)
                val end = ssb.length
                ssb.setSpan(ForegroundColorSpan(colHeaderFg), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(BackgroundColorSpan(colHeaderBg), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }

            val line = item as DiffLine
            val oldStr = line.oldLineNumber?.toString()?.padStart(4) ?: "    "
            val newStr = line.newLineNumber?.toString()?.padStart(4) ?: "    "
            val marker = when (line.type) {
                DiffLineType.ADDED -> "+"
                DiffLineType.DELETED -> "-"
                DiffLineType.UNCHANGED -> " "
                DiffLineType.HEADER -> "@"
            }

            val lineStart = ssb.length

            // 1. Line numbers and gutter column
            val gutter = "$oldStr $newStr $marker "
            ssb.append(gutter)
            val gutterEnd = ssb.length
            ssb.setSpan(ForegroundColorSpan(colLineNum), lineStart, gutterEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            // 2. Line content and newline
            val contentStart = ssb.length
            ssb.append(line.text)
            ssb.append("\n")
            val lineEnd = ssb.length

            // 3. Highlight line content according to DiffLineType
            when (line.type) {
                DiffLineType.ADDED -> {
                    ssb.setSpan(ForegroundColorSpan(colAddFg), contentStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(BackgroundColorSpan(colAddBg), lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                DiffLineType.DELETED -> {
                    ssb.setSpan(ForegroundColorSpan(colDelFg), contentStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(BackgroundColorSpan(colDelBg), lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                DiffLineType.UNCHANGED -> {
                    ssb.setSpan(ForegroundColorSpan(colNormalFg), contentStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                DiffLineType.HEADER -> {
                    ssb.setSpan(ForegroundColorSpan(colHeaderFg), lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }

        return ssb
    }

    private fun isLightTheme(context: Context): Boolean {
        val surfaceColor = resolveThemeColor(
            context,
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE
        )
        return ColorUtils.calculateLuminance(surfaceColor) > 0.45
    }

    private fun resolveThemeColor(context: Context, attrRes: Int, fallback: Int): Int {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(attrRes, typedValue, true)) {
            typedValue.data
        } else {
            fallback
        }
    }
}
