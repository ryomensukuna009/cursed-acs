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

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

sealed class MarkdownBlock {
    data class TextBlock(val text: String) : MarkdownBlock()
    data class CodeBlock(
        val language: String,
        val code: String,
        val isComplete: Boolean = true
    ) : MarkdownBlock()
}

object ChatMarkdownRenderer {

    /**
     * Splits a markdown string into alternating [MarkdownBlock.TextBlock] and [MarkdownBlock.CodeBlock] segments.
     * Gracefully handles unclosed fenced code blocks during live token streaming.
     */
    fun parseBlocks(markdown: String): List<MarkdownBlock> {
        if (markdown.isEmpty()) return emptyList()

        val lines = markdown.replace("\r\n", "\n").lines()
        val blocks = mutableListOf<MarkdownBlock>()
        val currentText = StringBuilder()
        val currentCode = StringBuilder()
        var inCodeBlock = false
        var codeLang = ""

        fun flushText() {
            val t = currentText.toString().trim('\n')
            if (t.isNotBlank()) {
                blocks.add(MarkdownBlock.TextBlock(t))
            }
            currentText.clear()
        }

        for (line in lines) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                if (!inCodeBlock) {
                    flushText()
                    inCodeBlock = true
                    codeLang = trimmed.removePrefix("```").trim().lowercase()
                    currentCode.clear()
                } else {
                    blocks.add(
                        MarkdownBlock.CodeBlock(
                            language = codeLang.ifEmpty { "code" },
                            code = currentCode.toString().trimEnd('\n'),
                            isComplete = true
                        )
                    )
                    inCodeBlock = false
                    codeLang = ""
                    currentCode.clear()
                }
            } else {
                if (inCodeBlock) {
                    if (currentCode.isNotEmpty()) currentCode.append('\n')
                    currentCode.append(line)
                } else {
                    if (currentText.isNotEmpty()) currentText.append('\n')
                    currentText.append(line)
                }
            }
        }

        if (inCodeBlock) {
            blocks.add(
                MarkdownBlock.CodeBlock(
                    language = codeLang.ifEmpty { "code" },
                    code = currentCode.toString().trimEnd('\n'),
                    isComplete = false
                )
            )
        } else {
            flushText()
        }

        return blocks
    }

    /**
     * Converts a [MarkdownBlock.TextBlock] into an Android [SpannableStringBuilder] with headings,
     * lists, inline code, bold/italic, and clickable links.
     */
    fun renderTextSpannable(
        rawText: String,
        inlineCodeBgColor: Int = 0x26888888,
        inlineCodeFgColor: Int? = null
    ): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        val lines = rawText.lines()

        lines.forEachIndexed { index, rawLine ->
            val trimmedStart = rawLine.trimStart()
            when {
                trimmedStart.startsWith("### ") -> {
                    val heading = trimmedStart.removePrefix("### ").trim()
                    val start = builder.length
                    appendInlineFormatted(builder, heading, inlineCodeBgColor, inlineCodeFgColor)
                    val end = builder.length
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(RelativeSizeSpan(1.08f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                trimmedStart.startsWith("## ") -> {
                    val heading = trimmedStart.removePrefix("## ").trim()
                    val start = builder.length
                    appendInlineFormatted(builder, heading, inlineCodeBgColor, inlineCodeFgColor)
                    val end = builder.length
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(RelativeSizeSpan(1.15f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                trimmedStart.startsWith("# ") -> {
                    val heading = trimmedStart.removePrefix("# ").trim()
                    val start = builder.length
                    appendInlineFormatted(builder, heading, inlineCodeBgColor, inlineCodeFgColor)
                    val end = builder.length
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(RelativeSizeSpan(1.22f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                trimmedStart.startsWith("- ") || trimmedStart.startsWith("* ") -> {
                    val indent = "  ".repeat((rawLine.length - trimmedStart.length) / 2)
                    builder.append(indent).append("• ")
                    appendInlineFormatted(
                        builder,
                        trimmedStart.substring(2),
                        inlineCodeBgColor,
                        inlineCodeFgColor
                    )
                }
                trimmedStart.startsWith("> ") -> {
                    builder.append("┃ ")
                    val start = builder.length
                    appendInlineFormatted(
                        builder,
                        trimmedStart.removePrefix("> "),
                        inlineCodeBgColor,
                        inlineCodeFgColor
                    )
                    val end = builder.length
                    builder.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    appendInlineFormatted(builder, rawLine, inlineCodeBgColor, inlineCodeFgColor)
                }
            }

            if (index < lines.lastIndex) {
                builder.append('\n')
            }
        }

        return builder
    }

    private fun appendInlineFormatted(
        builder: SpannableStringBuilder,
        line: String,
        inlineCodeBgColor: Int,
        inlineCodeFgColor: Int?
    ) {
        var i = 0
        val n = line.length
        while (i < n) {
            // 1. Inline code: `...`
            if (line[i] == '`') {
                val closeIdx = line.indexOf('`', i + 1)
                if (closeIdx > i + 1) {
                    val codeContent = line.substring(i + 1, closeIdx)
                    val start = builder.length
                    builder.append('\u00A0').append(codeContent).append('\u00A0')
                    val end = builder.length
                    builder.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(BackgroundColorSpan(inlineCodeBgColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (inlineCodeFgColor != null) {
                        builder.setSpan(ForegroundColorSpan(inlineCodeFgColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    i = closeIdx + 1
                    continue
                }
            }

            // 2. Bold: **...**
            if (i + 1 < n && line[i] == '*' && line[i + 1] == '*') {
                val closeIdx = line.indexOf("**", i + 2)
                if (closeIdx > i + 2) {
                    val boldContent = line.substring(i + 2, closeIdx)
                    val start = builder.length
                    appendInlineFormatted(builder, boldContent, inlineCodeBgColor, inlineCodeFgColor)
                    val end = builder.length
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = closeIdx + 2
                    continue
                }
            }

            // 3. Markdown Link: [label](url)
            if (line[i] == '[') {
                val closeBracket = line.indexOf(']', i + 1)
                if (closeBracket > i + 1 && closeBracket + 1 < n && line[closeBracket + 1] == '(') {
                    val closeParen = line.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 2) {
                        val label = line.substring(i + 1, closeBracket)
                        val url = line.substring(closeBracket + 2, closeParen).trim()
                        val start = builder.length
                        builder.append(label)
                        val end = builder.length
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            builder.setSpan(URLSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        i = closeParen + 1
                        continue
                    }
                }
            }

            builder.append(line[i])
            i++
        }
    }
}
