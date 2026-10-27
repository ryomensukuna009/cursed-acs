package com.tom.rv2ide.adapters







import android.content.ClipData



import android.content.ClipboardManager



import android.content.Context



import android.content.Intent



import android.graphics.Color



import android.graphics.Typeface



import android.graphics.drawable.GradientDrawable



import android.net.Uri



import android.text.method.LinkMovementMethod



import android.util.TypedValue



import android.view.Gravity



import android.view.View



import android.view.ViewGroup



import android.widget.HorizontalScrollView



import android.widget.LinearLayout



import android.widget.TextView



import android.widget.Toast



import androidx.recyclerview.widget.DiffUtil



import androidx.recyclerview.widget.RecyclerView



import com.google.android.material.card.MaterialCardView



import com.tom.rv2ide.R



import com.tom.rv2ide.artificial.chat.AgentCompletionReport



import com.tom.rv2ide.artificial.chat.ChatFileModification



import com.tom.rv2ide.artificial.chat.ChatMarkdownRenderer



import com.tom.rv2ide.artificial.chat.ChatMessageItem



import com.tom.rv2ide.artificial.chat.MarkdownBlock



import com.tom.rv2ide.artificial.chat.VerificationLevel



import com.tom.rv2ide.artificial.transport.ChatRole



import com.tom.rv2ide.artificial.transport.CitationItem



import java.io.File

import java.text.SimpleDateFormat



import java.util.Date



import java.util.Locale







/**



 * Chronological RecyclerView adapter for multi-turn AI chat messages.



 * Supports streaming updates in-place, Markdown + fenced code blocks with Copy Code,



 * separate tool-status pills, clickable file modification chips, citations, and completion reports.



 */



class ChatMessageAdapter(



    private val onRetryMessage: (ChatMessageItem) -> Unit,



    private val onOpenFile: (String) -> Unit,



    private val onRollbackSession: ((messageId: String, sessionId: String, files: List<String>) -> Unit)? = null,



    private val onReviewSessionChanges: ((messageId: String, sessionId: String, files: List<String>) -> Unit)? = null,
    private val onEditMessage: ((ChatMessageItem) -> Unit)? = null
) : RecyclerView.Adapter<ChatMessageAdapter.MessageViewHolder>() {







    private val items = mutableListOf<ChatMessageItem>()



    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())



    private val expandedThoughtMessageIds = mutableSetOf<String>()



    private val userCollapsedThoughtMessageIds = mutableSetOf<String>()



    private val expandedTimelineIds = mutableSetOf<String>()



    private val userCollapsedTimelineIds = mutableSetOf<String>()



    private val collapsedSubgroupIds = mutableSetOf<String>()







    data class ExtractedThoughts(



        val thought: String?,



        val mainContent: String



    )







    private fun extractThoughtContent(content: String): ExtractedThoughts {
        val pattern = Regex("""(?s)<(thought|think|reasoning|analysis)>(.*?)(?:</\1>|$)""")
        val matches = pattern.findAll(content).toList()
        if (matches.isEmpty()) {
            val detailsPattern = Regex("""(?s)<details>\s*<summary>(.*?)</summary>(.*?)</details>""")
            val detailMatches = detailsPattern.findAll(content).toList()
            if (detailMatches.isNotEmpty()) {
                val detailsText = detailMatches.map { "${it.groupValues[1]}:\n${it.groupValues[2].trim()}" }.joinToString("\n\n")
                val clean = detailsPattern.replace(content, "").trim()
                return ExtractedThoughts(detailsText, clean)
            }
            return ExtractedThoughts(null, content)
        }

        val thoughts = matches.map { it.groupValues[2].trim() }.filter { it.isNotEmpty() }
        val thoughtText = thoughts.joinToString("\n\n").ifEmpty { null }
        val cleanContent = pattern.replace(content, "").trim()
        return ExtractedThoughts(thoughtText, cleanContent)
    }







    companion object {



        private const val PAYLOAD_STREAM_UPDATE = "stream_update"



    }







    fun submitMessages(newItems: List<ChatMessageItem>) {



        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {



            override fun getOldListSize(): Int = items.size



            override fun getNewListSize(): Int = newItems.size







            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {



                return items[oldItemPosition].id == newItems[newItemPosition].id



            }







            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {



                return items[oldItemPosition] == newItems[newItemPosition]



            }







            override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {



                val oldItem = items[oldItemPosition]



                val newItem = newItems[newItemPosition]



                if (oldItem.role == newItem.role) {



                    return PAYLOAD_STREAM_UPDATE



                }



                return null



            }



        })



        items.clear()



        items.addAll(newItems)



        diff.dispatchUpdatesTo(this)



    }







    fun updateSingleMessage(updated: ChatMessageItem) {



        val idx = items.indexOfFirst { it.id == updated.id }



        if (idx >= 0) {



            items[idx] = updated



            notifyItemChanged(idx, PAYLOAD_STREAM_UPDATE)



        } else {



            items.add(updated)



            notifyItemInserted(items.size - 1)



        }



    }







    override fun getItemCount(): Int = items.size







    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {



        val context = parent.context



        val rootLayout = LinearLayout(context).apply {



            layoutParams = RecyclerView.LayoutParams(



                ViewGroup.LayoutParams.MATCH_PARENT,



                ViewGroup.LayoutParams.WRAP_CONTENT



            ).apply {



                topMargin = dp(context, 6)



                bottomMargin = dp(context, 6)



            }



            orientation = LinearLayout.VERTICAL



            setPadding(dp(context, 8), dp(context, 2), dp(context, 8), dp(context, 2))



        }



        return MessageViewHolder(rootLayout)



    }







    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {



        holder.bind(items[position])



    }







    override fun onBindViewHolder(



        holder: MessageViewHolder,



        position: Int,



        payloads: MutableList<Any>



    ) {



        if (payloads.contains(PAYLOAD_STREAM_UPDATE)) {



            holder.bind(items[position])



        } else {



            super.onBindViewHolder(holder, position, payloads)



        }



    }







    inner class MessageViewHolder(private val root: LinearLayout) : RecyclerView.ViewHolder(root) {







        private fun isLightBackground(ctx: Context): Boolean {



            val surface = resolveAttrColor(ctx, com.google.android.material.R.attr.colorSurface, Color.BLACK)



            return androidx.core.graphics.ColorUtils.calculateLuminance(surface) > 0.45



        }







        fun bind(item: ChatMessageItem) {
            root.removeAllViews()
            (root.layoutParams as? RecyclerView.LayoutParams)?.let {
                it.width = ViewGroup.LayoutParams.MATCH_PARENT
            } ?: run {
                root.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }



            val ctx = root.context



            if (item.role == ChatRole.USER) {



                bindUserMessage(ctx, item)



            } else {



                bindAssistantMessage(ctx, item)



            }



        }







        private fun bindUserMessage(ctx: Context, item: ChatMessageItem) {



            root.gravity = Gravity.END







            val card = MaterialCardView(ctx).apply {



                layoutParams = LinearLayout.LayoutParams(



                    ViewGroup.LayoutParams.WRAP_CONTENT,



                    ViewGroup.LayoutParams.WRAP_CONTENT



                ).apply {



                    marginStart = dp(ctx, 40)



                    gravity = Gravity.END



                }



                radius = dp(ctx, 16).toFloat()



                cardElevation = dp(ctx, 1).toFloat()



                setCardBackgroundColor(



                    resolveAttrColor(



                        ctx,



                        com.google.android.material.R.attr.colorPrimaryContainer,



                        Color.parseColor("#2B3A55")



                    )



                )



                strokeWidth = 0



            }







            val inner = LinearLayout(ctx).apply {



                orientation = LinearLayout.VERTICAL



                setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 8))



            }







            val roleLabel = TextView(ctx).apply {



                text = "You • ${timeFormatter.format(Date(item.timestamp))}"



                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)



                setTextColor(



                    resolveAttrColor(



                        ctx,



                        com.google.android.material.R.attr.colorOnPrimaryContainer,



                        Color.WHITE



                    )



                )



                alpha = 0.75f



                setTypeface(typeface, Typeface.BOLD)



            }



            inner.addView(roleLabel)

            if (!item.imageBase64.isNullOrBlank()) {
                try {
                    val bytes = android.util.Base64.decode(item.imageBase64, android.util.Base64.DEFAULT)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        val imgView = com.google.android.material.imageview.ShapeableImageView(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                dp(ctx, 180),
                                dp(ctx, 180)
                            ).apply {
                                topMargin = dp(ctx, 6)
                                bottomMargin = dp(ctx, 6)
                            }
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                                .setAllCornerSizes(dp(ctx, 8).toFloat())
                                .build()
                            setImageBitmap(bmp)
                            isClickable = true
                            isFocusable = true
                            setOnClickListener {
                                showImagePreviewDialog(ctx, bmp)
                            }
                        }
                        inner.addView(imgView)
                    }
                } catch (_: Exception) {}
            }







            val contentView = TextView(ctx).apply {



                text = item.content



                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)



                setTextColor(



                    resolveAttrColor(



                        ctx,



                        com.google.android.material.R.attr.colorOnPrimaryContainer,



                        Color.WHITE



                    )



                )



                // setTextIsSelectable(false)



                setLineSpacing(dp(ctx, 2).toFloat(), 1.05f)



                layoutParams = LinearLayout.LayoutParams(



                    ViewGroup.LayoutParams.WRAP_CONTENT,



                    ViewGroup.LayoutParams.WRAP_CONTENT



                ).apply {



                    topMargin = dp(ctx, 4)



                }



            }



            inner.addView(contentView)
            val actionRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(ctx, 4)
                    gravity = Gravity.END
                }
            }
            actionRow.addView(
                createIconButtonChip(ctx, R.drawable.ic_edit, "Edit prompt") {
                    onEditMessage?.invoke(item)
                }
            )
            actionRow.addView(
                createIconButtonChip(ctx, R.drawable.ic_copy, "Copy prompt") {
                    copyToClipboard(ctx, "User prompt", item.content)
                }
            )
            card.addView(inner)
            root.addView(card)
            root.addView(actionRow)



        }







        private fun bindAssistantMessage(ctx: Context, item: ChatMessageItem) {



            root.gravity = Gravity.START







            val surfaceColor = resolveAttrColor(



                ctx,



                com.google.android.material.R.attr.colorSurfaceVariant,



                Color.parseColor("#1E222A")



            )



            val onSurfaceColor = resolveAttrColor(



                ctx,



                com.google.android.material.R.attr.colorOnSurface,



                Color.parseColor("#E8EAED")



            )



            val outlineColor = resolveAttrColor(



                ctx,



                com.google.android.material.R.attr.colorOutlineVariant,



                Color.parseColor("#3C4043")



            )







            val card = MaterialCardView(ctx).apply {



                layoutParams = LinearLayout.LayoutParams(



                    ViewGroup.LayoutParams.MATCH_PARENT,



                    ViewGroup.LayoutParams.WRAP_CONTENT



                ).apply {



                    marginEnd = dp(ctx, 8)



                }



                radius = dp(ctx, 16).toFloat()



                cardElevation = dp(ctx, 1).toFloat()



                setCardBackgroundColor(surfaceColor)



                strokeColor = outlineColor



                strokeWidth = dp(ctx, 1)



            }







            val inner = LinearLayout(ctx).apply {



                orientation = LinearLayout.VERTICAL



                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 10))



            }







            // 1. Assistant Header (Identity + Model Badge + Timestamp)



            val headerRow = LinearLayout(ctx).apply {



                orientation = LinearLayout.HORIZONTAL



                gravity = Gravity.CENTER_VERTICAL



            }







            val modelBadgeText = buildString {



                append("🤖 Cursed AI")



                if (!item.providerName.isNullOrBlank() || !item.modelId.isNullOrBlank()) {



                    append(" • ")



                    if (!item.providerName.isNullOrBlank()) append(item.providerName)



                    if (!item.modelId.isNullOrBlank()) {



                        if (!item.providerName.isNullOrBlank()) append(" / ")



                        append(item.modelId)



                    }



                }



                append(" • ${timeFormatter.format(Date(item.timestamp))}")



            }







            val headerTitle = TextView(ctx).apply {



                text = modelBadgeText



                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)



                setTextColor(onSurfaceColor)



                alpha = 0.8f



                setTypeface(typeface, Typeface.BOLD)



                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)



            }



            headerRow.addView(headerTitle)







            if (item.isStreaming) {



                val streamingPill = TextView(ctx).apply {



                    text = "● LIVE"



                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)



                    setTextColor(getStatusColor(ctx, R.color.status_success, "#4CAF50"))



                    setTypeface(typeface, Typeface.BOLD)



                }



                headerRow.addView(streamingPill)



            }







            inner.addView(headerRow)







            // 2. Antigravity-Style Collapsible Execution Timeline (Task 8)



            val timelineView = createAntigravityTimelineView(ctx, item, onSurfaceColor)



            if (timelineView != null) {



                inner.addView(timelineView)



            }







            // 3. Thoughts and Markdown + Code Blocks Content



            val contentBody = item.content.trim()



            val (thoughtText, cleanBody) = extractThoughtContent(contentBody)







            if (!thoughtText.isNullOrBlank()) {



                val thoughtView = createThoughtView(



                    ctx = ctx,



                    thought = thoughtText,



                    isStreaming = item.isStreaming,



                    hasMainContent = cleanBody.isNotEmpty(),



                    messageId = item.id,



                    onSurfaceColor = onSurfaceColor



                )



                inner.addView(thoughtView)



            }







            if (cleanBody.isNotEmpty()) {



                val blocks = ChatMarkdownRenderer.parseBlocks(cleanBody)



                for (block in blocks) {



                    when (block) {



                        is MarkdownBlock.TextBlock -> {



                            val textContent = block.text



                            val isEditDirective = textContent.contains("/edit", ignoreCase = true) &&



                                (textContent.contains("reply with", ignoreCase = true) ||



                                 textContent.contains("To apply", ignoreCase = true) ||



                                 textContent.contains("apply these", ignoreCase = true))







                            if (isEditDirective) {



                                val highlightCard = MaterialCardView(ctx).apply {



                                    layoutParams = LinearLayout.LayoutParams(



                                        ViewGroup.LayoutParams.MATCH_PARENT,



                                        ViewGroup.LayoutParams.WRAP_CONTENT



                                    ).apply {



                                        topMargin = dp(ctx, 10)



                                        bottomMargin = dp(ctx, 6)



                                    }



                                    radius = dp(ctx, 10).toFloat()



                                    cardElevation = 0f



                                    val accentColor = resolveAttrColor(



                                        ctx,



                                        android.R.attr.colorPrimary,



                                        Color.parseColor("#4285F4")



                                    )



                                    val containerColor = resolveAttrColor(



                                        ctx,



                                        com.google.android.material.R.attr.colorPrimaryContainer,



                                        Color.parseColor("#1E2D4A")



                                    )



                                    setCardBackgroundColor(containerColor)



                                    strokeColor = accentColor



                                    strokeWidth = dp(ctx, 1)



                                }



                                val tv = TextView(ctx).apply {



                                    text = ChatMarkdownRenderer.renderTextSpannable(textContent)



                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)



                                    setTextColor(



                                        resolveAttrColor(



                                            ctx,



                                            com.google.android.material.R.attr.colorOnPrimaryContainer,



                                            Color.WHITE



                                        )



                                    )



                                    setTextIsSelectable(true)



                                    movementMethod = LinkMovementMethod.getInstance()



                                    setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))



                                    setTypeface(typeface, Typeface.BOLD)



                                    setLineSpacing(dp(ctx, 2).toFloat(), 1.05f)



                                }



                                highlightCard.addView(tv)



                                inner.addView(highlightCard)



                            } else {



                                val tv = TextView(ctx).apply {



                                    text = ChatMarkdownRenderer.renderTextSpannable(textContent)



                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)



                                    setTextColor(onSurfaceColor)



                                    setTextIsSelectable(true)



                                    movementMethod = LinkMovementMethod.getInstance()



                                    setLineSpacing(dp(ctx, 3).toFloat(), 1.08f)



                                    layoutParams = LinearLayout.LayoutParams(



                                        ViewGroup.LayoutParams.MATCH_PARENT,



                                        ViewGroup.LayoutParams.WRAP_CONTENT



                                    ).apply {



                                        topMargin = dp(ctx, 8)



                                    }



                                }



                                inner.addView(tv)



                            }



                        }







                        is MarkdownBlock.CodeBlock -> {



                            inner.addView(createCodeBlockView(ctx, block))



                        }



                    }



                }



            } else if (item.isStreaming && item.activeToolStatus.isNullOrBlank() && thoughtText.isNullOrBlank()) {



                val thinkingTv = TextView(ctx).apply {



                    text = "Thinking…"



                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)



                    setTextColor(onSurfaceColor)



                    alpha = 0.65f



                    setTypeface(typeface, Typeface.ITALIC)



                    layoutParams = LinearLayout.LayoutParams(



                        ViewGroup.LayoutParams.WRAP_CONTENT,



                        ViewGroup.LayoutParams.WRAP_CONTENT



                    ).apply {



                        topMargin = dp(ctx, 8)



                    }



                }



                inner.addView(thinkingTv)



            }







            // 4. Modified Files Card (clickable chips to open in editor)



            if (item.fileModifications.isNotEmpty()) {



                inner.addView(createModifiedFilesCard(ctx, item.fileModifications))



            }







            // 5. Agent Completion & Verification Report Card



            item.completionReport?.let { report ->



                inner.addView(createCompletionReportCard(ctx, report, item))



            }







            // 6. Verified Web Citations Card



            if (item.citations.isNotEmpty()) {



                inner.addView(createCitationsCard(ctx, item.citations))



            }







            // 7. Assistant Action Footer (Copy Response + Regenerate/Retry + Revert Run)
            card.addView(inner)
            root.addView(card)
            if (!item.isStreaming) {
                val footerRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(ctx, 4)
                        bottomMargin = dp(ctx, 4)
                    }
                }
                val compReport = item.completionReport
                val isRollbackEligible = compReport != null &&
                    compReport.rollbackSessionId != null &&
                    compReport.filesModified.isNotEmpty() &&
                    !compReport.isRolledBack
                if (isRollbackEligible) {
                    footerRow.addView(
                        createIconButtonChip(ctx, R.drawable.ic_search, "Review changes") {
                            onReviewSessionChanges?.invoke(item.id, compReport.rollbackSessionId, compReport.filesModified)
                        }
                    )
                    footerRow.addView(
                        createIconButtonChip(ctx, R.drawable.ic_undo, "Revert changes") {
                            onRollbackSession?.invoke(item.id, compReport.rollbackSessionId, compReport.filesModified)
                        }
                    )
                } else if (compReport != null && compReport.isRolledBack) {
                    footerRow.addView(
                        createIconButtonChip(ctx, R.drawable.ic_undo, "Changes reverted") {}
                    )
                }
                if (item.content.isNotBlank()) {
                    footerRow.addView(
                        createIconButtonChip(ctx, R.drawable.ic_copy, "Copy response") {
                            copyToClipboard(ctx, "AI Response", item.content)
                        }
                    )
                }
                val retryLabel = if (item.isError || item.isCancelled) "Retry" else "Regenerate"
                footerRow.addView(
                    createIconButtonChip(ctx, R.drawable.ic_update, retryLabel) {
                        onRetryMessage(item)
                    }
                )
                root.addView(footerRow)
            }



        }







        private fun createThoughtView(



            ctx: Context,



            thought: String,



            isStreaming: Boolean,



            hasMainContent: Boolean,



            messageId: String,



            onSurfaceColor: Int



        ): View {



            val container = LinearLayout(ctx).apply {



                orientation = LinearLayout.VERTICAL



                layoutParams = LinearLayout.LayoutParams(



                    ViewGroup.LayoutParams.MATCH_PARENT,



                    ViewGroup.LayoutParams.WRAP_CONTENT



                ).apply {



                    topMargin = dp(ctx, 8)



                    bottomMargin = dp(ctx, 4)



                }



                background = GradientDrawable().apply {



                    shape = GradientDrawable.RECTANGLE



                    cornerRadius = dp(ctx, 10).toFloat()



                    setColor(Color.parseColor("#121722"))



                    setStroke(dp(ctx, 1), Color.parseColor("#2A3345"))



                }



                setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))



            }







            val isExpandedInitially = if (userCollapsedThoughtMessageIds.contains(messageId)) {



                false



            } else if (expandedThoughtMessageIds.contains(messageId)) {



                true



            } else if (isStreaming && !hasMainContent) {



                true



            } else {



                false



            }







            val headerRow = LinearLayout(ctx).apply {



                orientation = LinearLayout.HORIZONTAL



                gravity = Gravity.CENTER_VERTICAL



                isClickable = true



                isFocusable = true



                setBackgroundResource(R.drawable.bg_ripple)



                setPadding(dp(ctx, 6), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4))



            }







            val tvIconAndTitle = TextView(ctx).apply {



                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)



                setTextColor(Color.parseColor("#9AA4B2"))



                setTypeface(typeface, Typeface.BOLD)



                layoutParams = LinearLayout.LayoutParams(



                    0,



                    ViewGroup.LayoutParams.WRAP_CONTENT,



                    1f



                )



            }







            val tvStatus = TextView(ctx).apply {



                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)



                setTextColor(Color.parseColor("#6B778C"))



            }







            headerRow.addView(tvIconAndTitle)



            headerRow.addView(tvStatus)



            container.addView(headerRow)







            val divider = View(ctx).apply {



                layoutParams = LinearLayout.LayoutParams(



                    ViewGroup.LayoutParams.MATCH_PARENT,



                    dp(ctx, 1)



                ).apply {



                    topMargin = dp(ctx, 4)



                    bottomMargin = dp(ctx, 6)



                }



                setBackgroundColor(Color.parseColor("#1E2638"))



                visibility = if (isExpandedInitially) View.VISIBLE else View.GONE



            }



            container.addView(divider)







            val thoughtBodyContainer = LinearLayout(ctx).apply {



                orientation = LinearLayout.HORIZONTAL



                layoutParams = LinearLayout.LayoutParams(



                    ViewGroup.LayoutParams.MATCH_PARENT,



                    ViewGroup.LayoutParams.WRAP_CONTENT



                )



                visibility = if (isExpandedInitially) View.VISIBLE else View.GONE



            }







            val quoteBar = View(ctx).apply {



                layoutParams = LinearLayout.LayoutParams(



                    dp(ctx, 3),



                    ViewGroup.LayoutParams.MATCH_PARENT



                ).apply {



                    marginEnd = dp(ctx, 8)



                }



                setBackgroundColor(Color.parseColor("#FF2A55"))



            }



            thoughtBodyContainer.addView(quoteBar)







            val tvThoughtBody = TextView(ctx).apply {



                text = thought



                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)



                setTextColor(Color.parseColor("#8E99A8"))



                setTextIsSelectable(true)



                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)



                setLineSpacing(dp(ctx, 2).toFloat(), 1.05f)



                layoutParams = LinearLayout.LayoutParams(



                    0,



                    ViewGroup.LayoutParams.WRAP_CONTENT,



                    1f



                )



            }



            thoughtBodyContainer.addView(tvThoughtBody)



            container.addView(thoughtBodyContainer)







            fun updateUI(expanded: Boolean) {



                if (expanded) {



                    tvIconAndTitle.text = "⌄ > Thought"



                    val streamingHint = if (isStreaming) "thinking... • " else ""



                    tvStatus.text = "${streamingHint}tap to collapse"



                    divider.visibility = View.VISIBLE



                    thoughtBodyContainer.visibility = View.VISIBLE



                } else {



                    tvIconAndTitle.text = "> ⌄ Thought"



                    val words = thought.split(Regex("\\s+")).filter { it.isNotBlank() }.size



                    val streamingHint = if (isStreaming) "thinking in background... • " else ""



                    tvStatus.text = "$words words • ${streamingHint}tap to expand"



                    divider.visibility = View.GONE



                    thoughtBodyContainer.visibility = View.GONE



                }



            }







            updateUI(isExpandedInitially)







            headerRow.setOnClickListener {



                val currentlyExpanded = thoughtBodyContainer.visibility == View.VISIBLE



                val newState = !currentlyExpanded



                if (newState) {



                    userCollapsedThoughtMessageIds.remove(messageId)



                    expandedThoughtMessageIds.add(messageId)



                } else {



                    userCollapsedThoughtMessageIds.add(messageId)



                    expandedThoughtMessageIds.remove(messageId)



                }



                updateUI(newState)



            }







            return container



        }







        private fun createCodeBlockView(



            ctx: Context,



            block: MarkdownBlock.CodeBlock



        ): View {



            // High-contrast dark code block styling across all light/dark themes



            val codeBg = Color.parseColor("#12151C")



            val codeBorder = Color.parseColor("#2F3542")



            val codeHeaderBg = Color.parseColor("#1C212C")







            val container = LinearLayout(ctx).apply {



                orientation = LinearLayout.VERTICAL



                background = GradientDrawable().apply {



                    shape = GradientDrawable.RECTANGLE



                    cornerRadius = dp(ctx, 12).toFloat()



                    setColor(codeBg)



                    setStroke(dp(ctx, 1), codeBorder)



                }



                layoutParams = LinearLayout.LayoutParams(



                    ViewGroup.LayoutParams.MATCH_PARENT,



                    ViewGroup.LayoutParams.WRAP_CONTENT



                ).apply {



                    topMargin = dp(ctx, 8)



                    bottomMargin = dp(ctx, 4)



                }



            }







            val header = LinearLayout(ctx).apply {



                orientation = LinearLayout.HORIZONTAL



                gravity = Gravity.CENTER_VERTICAL



                setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))



                background = GradientDrawable().apply {



                    shape = GradientDrawable.RECTANGLE



                    cornerRadii = floatArrayOf(



                        dp(ctx, 12).toFloat(), dp(ctx, 12).toFloat(),



                        dp(ctx, 12).toFloat(), dp(ctx, 12).toFloat(),



                        0f, 0f, 0f, 0f



                    )



                    setColor(codeHeaderBg)



                }



            }







            val langLabel = TextView(ctx).apply {



                text = block.language.ifBlank { "code" }



                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)



                setTextColor(Color.parseColor("#9AA0A6"))



                typeface = Typeface.MONOSPACE



                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)



            }



            header.addView(langLabel)







            val copyCodeBtn = TextView(ctx).apply {



                text = "Copy Code"



                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)



                setTextColor(Color.parseColor("#80DEEA"))



                setTypeface(typeface, Typeface.BOLD)



                gravity = Gravity.CENTER



                minHeight = dp(ctx, 48)



                minWidth = dp(ctx, 48)



                setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))



                setOnClickListener {



                    copyToClipboard(ctx, "Code snippet", block.code)



                }



            }



            header.addView(copyCodeBtn)



            container.addView(header)







            val hScroll = HorizontalScrollView(ctx).apply {



                isHorizontalScrollBarEnabled = true



                layoutParams = LinearLayout.LayoutParams(



                    ViewGroup.LayoutParams.MATCH_PARENT,



                    ViewGroup.LayoutParams.WRAP_CONTENT



                )



            }







            val codeTv = TextView(ctx).apply {



                text = block.code



                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)



                setTextColor(Color.parseColor("#E8EAED"))



                typeface = Typeface.MONOSPACE



                setTextIsSelectable(true)



                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))



                setLineSpacing(dp(ctx, 2).toFloat(), 1.05f)



            }



            hScroll.addView(codeTv)



            container.addView(hScroll)







            return container



        }







        private fun createModifiedFilesCard(



            ctx: Context,



            files: List<ChatFileModification>



        ): View {



            val successColor = getStatusColor(ctx, R.color.status_success, "#81C995")



            val successContainer = getStatusColor(ctx, R.color.status_success_container, "#1A4CAF50")







            val box = LinearLayout(ctx).apply {



                orientation = LinearLayout.VERTICAL



                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))



                background = GradientDrawable().apply {



                    shape = GradientDrawable.RECTANGLE



                    cornerRadius = dp(ctx, 12).toFloat()



                    setColor(successContainer)



                    setStroke(dp(ctx, 1), successColor)



                }



                layoutParams = LinearLayout.LayoutParams(



                    ViewGroup.LayoutParams.MATCH_PARENT,



                    ViewGroup.LayoutParams.WRAP_CONTENT



                ).apply {



                    topMargin = dp(ctx, 8)



                }



            }







            val title = TextView(ctx).apply {



                text = "📁 Project Files (${files.size}) — Tap to open"



                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)



                setTextColor(successColor)



                setTypeface(typeface, Typeface.BOLD)



            }



            box.addView(title)







            for (mod in files) {



                val fileBtn = TextView(ctx).apply {



                    val icon = if (mod.success) "✅" else "❌"



                    text = "$icon [${mod.statusMessage}] ${mod.fileName}"



                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)



                    setTextColor(resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.parseColor("#E8EAED")))



                    typeface = Typeface.MONOSPACE



                    gravity = Gravity.CENTER_VERTICAL



                    minHeight = dp(ctx, 48)



                    setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))



                    background = GradientDrawable().apply {



                        shape = GradientDrawable.RECTANGLE



                        cornerRadius = dp(ctx, 8).toFloat()



                        setColor(resolveAttrColor(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.parseColor("#22FFFFFF")))



                    }



                    layoutParams = LinearLayout.LayoutParams(



                        ViewGroup.LayoutParams.MATCH_PARENT,



                        ViewGroup.LayoutParams.WRAP_CONTENT



                    ).apply {



                        topMargin = dp(ctx, 4)



                    }



                    setOnClickListener {



                        onOpenFile(mod.filePath)



                    }



                }



                box.addView(fileBtn)



            }







            return box



        }







        private fun createCompletionReportCard(



            ctx: Context,



            report: AgentCompletionReport,



            item: ChatMessageItem



        ): View {



            val isVerifiedOk = report.validationPassed && !report.isRolledBack



            val isRolledBack = report.isRolledBack || report.verificationLevel == VerificationLevel.ROLLED_BACK



            val isFailed = report.verificationLevel == VerificationLevel.FAILED_WITH_ERRORS



            val isLight = isLightBackground(ctx)







            val borderColor = when {



                isRolledBack -> if (isLight) Color.parseColor("#7B1FA2") else Color.parseColor("#D7AEFB")



                isVerifiedOk -> if (isLight) Color.parseColor("#2E7D32") else Color.parseColor("#81C995")



                isFailed -> if (isLight) Color.parseColor("#C62828") else Color.parseColor("#FF5449")



                else -> if (isLight) Color.parseColor("#F57F17") else Color.parseColor("#FDD663")



            }



            val bgColor = when {



                isRolledBack -> if (isLight) Color.parseColor("#F3E5F5") else Color.parseColor("#32144E")



                isVerifiedOk -> if (isLight) Color.parseColor("#E8F5E9") else Color.parseColor("#0F381E")



                isFailed -> if (isLight) Color.parseColor("#FFEBEE") else Color.parseColor("#3B0D0B")



                else -> if (isLight) Color.parseColor("#FFF8E1") else Color.parseColor("#3E2700")



            }



            val textFgColor = when {



                isRolledBack -> if (isLight) Color.parseColor("#4A148C") else Color.parseColor("#F2DAFF")



                isVerifiedOk -> if (isLight) Color.parseColor("#1B5E20") else Color.parseColor("#C4EED0")



                isFailed -> if (isLight) Color.parseColor("#B71C1C") else Color.parseColor("#FFDAD6")



                else -> if (isLight) Color.parseColor("#E65100") else Color.parseColor("#FFEFC3")



            }







            val box = LinearLayout(ctx).apply {



                orientation = LinearLayout.VERTICAL



                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))



                background = GradientDrawable().apply {



                    shape = GradientDrawable.RECTANGLE



                    cornerRadius = dp(ctx, 12).toFloat()



                    setColor(bgColor)



                    setStroke(dp(ctx, 1), borderColor)



                }



                layoutParams = LinearLayout.LayoutParams(



                    ViewGroup.LayoutParams.MATCH_PARENT,



                    ViewGroup.LayoutParams.WRAP_CONTENT



                ).apply {



                    topMargin = dp(ctx, 8)



                }



            }







            val badgeIcon = when {



                isRolledBack -> "↩️"



                report.verificationLevel == VerificationLevel.BUILT_AND_VERIFIED -> "✅"



                report.verificationLevel == VerificationLevel.STATICALLY_VERIFIED -> "✅"



                report.verificationLevel == VerificationLevel.EDITED_UNVERIFIED -> "ℹ️"



                report.verificationLevel == VerificationLevel.FAILED_WITH_ERRORS -> "⚠️"



                else -> "ℹ️"



            }







            val titleText = when {



                isRolledBack -> "↩️ Changes Reverted"



                else -> "$badgeIcon ${report.verificationLevel.label}"



            }
            var isReportExpanded = false
            val detailsTv = TextView(ctx).apply {
                text = report.formatSummaryText()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(textFgColor)
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(ctx, 4)
                }
            }

            val titleTv = TextView(ctx).apply {
                text = "▶ " + titleText + " [Tap to view details]"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(borderColor)
                setTypeface(typeface, Typeface.BOLD)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    isReportExpanded = !isReportExpanded
                    if (isReportExpanded) {
                        text = "▼ " + titleText + " [Tap to collapse]"
                        detailsTv.visibility = View.VISIBLE
                    } else {
                        text = "▶ " + titleText + " [Tap to view details]"
                        detailsTv.visibility = View.GONE
                    }
                }
            }

            box.addView(titleTv)
            box.addView(detailsTv)








            val isRollbackEligible = report.rollbackSessionId != null &&



                report.filesModified.isNotEmpty() &&



                !report.isRolledBack







            if (isRollbackEligible) {



                val actionsLayout = LinearLayout(ctx).apply {



                    orientation = LinearLayout.HORIZONTAL



                    layoutParams = LinearLayout.LayoutParams(



                        ViewGroup.LayoutParams.WRAP_CONTENT,



                        ViewGroup.LayoutParams.WRAP_CONTENT



                    ).apply {



                        topMargin = dp(ctx, 8)



                    }



                }







                val reviewColor = getStatusColor(ctx, R.color.status_in_progress, "#80DEEA")



                val reviewContainer = getStatusColor(ctx, R.color.status_in_progress_container, "#1A8AB4F8")



                val reviewBtn = TextView(ctx).apply {



                    text = "🔍 Review Changes (${report.filesModified.size})"



                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)



                    setTextColor(reviewColor)



                    setTypeface(typeface, Typeface.BOLD)



                    gravity = Gravity.CENTER_VERTICAL



                    minHeight = dp(ctx, 44)



                    setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))



                    background = GradientDrawable().apply {



                        shape = GradientDrawable.RECTANGLE



                        cornerRadius = dp(ctx, 8).toFloat()



                        setColor(reviewContainer)



                        setStroke(dp(ctx, 1), reviewColor)



                    }



                    layoutParams = LinearLayout.LayoutParams(



                        ViewGroup.LayoutParams.WRAP_CONTENT,



                        ViewGroup.LayoutParams.WRAP_CONTENT



                    ).apply {



                        rightMargin = dp(ctx, 8)



                    }



                    setOnClickListener {



                        onReviewSessionChanges?.invoke(item.id, report.rollbackSessionId!!, report.filesModified)



                    }



                }



                actionsLayout.addView(reviewBtn)







                val revertColor = getStatusColor(ctx, R.color.status_rollback, "#D7AEFB")



                val revertContainer = getStatusColor(ctx, R.color.status_rollback_container, "#32144E")



                val revertBtn = TextView(ctx).apply {



                    text = "↩ Revert Run"



                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)



                    setTextColor(revertColor)



                    setTypeface(typeface, Typeface.BOLD)



                    gravity = Gravity.CENTER_VERTICAL



                    minHeight = dp(ctx, 44)



                    setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))



                    background = GradientDrawable().apply {



                        shape = GradientDrawable.RECTANGLE



                        cornerRadius = dp(ctx, 8).toFloat()



                        setColor(revertContainer)



                        setStroke(dp(ctx, 1), revertColor)



                    }



                    setOnClickListener {



                        onRollbackSession?.invoke(item.id, report.rollbackSessionId!!, report.filesModified)



                    }



                }



                actionsLayout.addView(revertBtn)



                box.addView(actionsLayout)



            }















            return box



        }







        private fun createCitationsCard(



            ctx: Context,



            citations: List<CitationItem>



        ): View {



            val outlineColor = resolveAttrColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, Color.parseColor("#33FFFFFF"))



            val surfaceColor = resolveAttrColor(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.parseColor("#1AFFFFFF"))







            val box = LinearLayout(ctx).apply {



                orientation = LinearLayout.VERTICAL



                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))



                background = GradientDrawable().apply {



                    shape = GradientDrawable.RECTANGLE



                    cornerRadius = dp(ctx, 12).toFloat()



                    setColor(surfaceColor)



                    setStroke(dp(ctx, 1), outlineColor)



                }



                layoutParams = LinearLayout.LayoutParams(



                    ViewGroup.LayoutParams.MATCH_PARENT,



                    ViewGroup.LayoutParams.WRAP_CONTENT



                ).apply {



                    topMargin = dp(ctx, 8)



                }



            }







            val citationsList = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }

            val header = TextView(ctx).apply {
                text = "▶ 🌐 Verified Web Sources (${citations.size}) [Tap to view]"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                val primaryColor = resolveAttrColor(ctx, android.R.attr.colorPrimary, Color.parseColor("#8AB4F8"))
                setTextColor(primaryColor)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (citationsList.visibility == View.VISIBLE) {
                        citationsList.visibility = View.GONE
                        text = "▶ 🌐 Verified Web Sources (${citations.size}) [Tap to view]"
                    } else {
                        citationsList.visibility = View.VISIBLE
                        text = "▼ 🌐 Verified Web Sources (${citations.size}) [Tap to collapse]"
                    }
                }
            }

            box.addView(header)
            box.addView(citationsList)







            citations.forEachIndexed { idx, citation ->



                val itemTv = TextView(ctx).apply {



                    text = "[${idx + 1}] ${citation.title}\n${citation.url}"



                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)



                    setTextColor(resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.parseColor("#BDC1C6")))



                    gravity = Gravity.CENTER_VERTICAL



                    minHeight = dp(ctx, 48)



                    setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))



                    setOnClickListener {



                        try {



                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(citation.url))



                            ctx.startActivity(intent)



                        } catch (_: Exception) {



                            copyToClipboard(ctx, "Source URL", citation.url)



                        }



                    }



                }



                citationsList.addView(itemTv)



            }







            return box



        }







        private fun createIconButtonChip(



            ctx: Context,



            @androidx.annotation.DrawableRes iconRes: Int,



            tooltip: String,



            onClick: () -> Unit



        ): View {



            val sizePx = dp(ctx, 28)



            return android.widget.ImageView(ctx).apply {



                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {



                    marginStart = dp(ctx, 6)



                }



                setImageResource(iconRes)



                val iconPadding = dp(ctx, 5)



                setPadding(iconPadding, iconPadding, iconPadding, iconPadding)



                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER



                val primaryColor = resolveAttrColor(



                    ctx,



                    android.R.attr.colorPrimary,



                    Color.parseColor("#8AB4F8")



                )



                imageTintList = android.content.res.ColorStateList.valueOf(primaryColor)



                contentDescription = tooltip



                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, tooltip)







                val chipBg = resolveAttrColor(



                    ctx,



                    com.google.android.material.R.attr.colorSecondaryContainer,



                    getStatusColor(ctx, R.color.status_in_progress_container, "#188AB4F8")



                )



                val shape = GradientDrawable().apply {



                    shape = GradientDrawable.RECTANGLE



                    cornerRadius = dp(ctx, 10).toFloat()



                    setColor(chipBg)



                }



                val rippleColor = android.content.res.ColorStateList.valueOf(
                    resolveAttrColor(ctx, android.R.attr.colorControlHighlight, Color.parseColor("#338AB4F8"))
                )
                background = android.graphics.drawable.RippleDrawable(rippleColor, shape, shape)



                isClickable = true



                isFocusable = true



                setOnClickListener { onClick() }



            }



        }







        private fun createCompactActionChip(



            ctx: Context,



            label: String,



            onClick: () -> Unit



        ): View {



            return TextView(ctx).apply {



                text = label



                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)



                val primaryColor = resolveAttrColor(



                    ctx,



                    android.R.attr.colorPrimary,



                    Color.parseColor("#8AB4F8")



                )



                setTextColor(primaryColor)



                setTypeface(typeface, Typeface.BOLD)



                gravity = Gravity.CENTER



                minHeight = dp(ctx, 36)



                setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))



                background = GradientDrawable().apply {



                    shape = GradientDrawable.RECTANGLE



                    cornerRadius = dp(ctx, 10).toFloat()



                    val chipBg = resolveAttrColor(



                        ctx,



                        com.google.android.material.R.attr.colorSecondaryContainer,



                        getStatusColor(ctx, R.color.status_in_progress_container, "#188AB4F8")



                    )



                    setColor(chipBg)



                }



                layoutParams = LinearLayout.LayoutParams(



                    ViewGroup.LayoutParams.WRAP_CONTENT,



                    ViewGroup.LayoutParams.WRAP_CONTENT



                ).apply {



                    marginStart = dp(ctx, 6)



                }



                setOnClickListener { onClick() }



            }



        }



    }











        private fun createAntigravityTimelineView(



            ctx: Context,



            item: ChatMessageItem,



            onSurfaceColor: Int



        ): View? {



            val allStatuses = mutableListOf<String>()



            allStatuses.addAll(item.toolStatusHistory)



            if (!item.activeToolStatus.isNullOrBlank() && !allStatuses.contains(item.activeToolStatus)) {



                allStatuses.add(item.activeToolStatus)



            }



            if (allStatuses.isEmpty() && item.fileModifications.isEmpty()) {



                return null



            }







            // Automatic collapse when turn finishes:



            // If streaming: expanded by default unless user explicitly collapsed it



            // If finished: collapsed by default unless user explicitly expanded it



            val isTopExpanded = if (item.isStreaming) {



                !userCollapsedTimelineIds.contains(item.id)



            } else {



                expandedTimelineIds.contains(item.id)



            }







            val elapsedSeconds = if (item.timestamp > 0) {



                ((System.currentTimeMillis() - item.timestamp) / 1000).coerceAtLeast(1)



            } else 0



            val durationStr = if (elapsedSeconds < 60) {



                "${elapsedSeconds}s"



            } else {



                "${elapsedSeconds / 60}m ${elapsedSeconds % 60}s"



            }







            val containerCard = MaterialCardView(ctx).apply {



                layoutParams = LinearLayout.LayoutParams(



                    ViewGroup.LayoutParams.MATCH_PARENT,



                    ViewGroup.LayoutParams.WRAP_CONTENT



                ).apply {



                    topMargin = dp(ctx, 8)



                    bottomMargin = dp(ctx, 8)



                }



                radius = dp(ctx, 10).toFloat()



                cardElevation = 0f



                val surfaceColor = resolveAttrColor(



                    ctx,



                    com.google.android.material.R.attr.colorSurfaceContainerLow,



                    Color.parseColor("#181B22")



                )



                val outlineColor = resolveAttrColor(



                    ctx,



                    com.google.android.material.R.attr.colorOutlineVariant,



                    Color.parseColor("#2D3139")



                )



                setCardBackgroundColor(surfaceColor)



                strokeColor = outlineColor



                strokeWidth = dp(ctx, 1)



            }







            val contentLayout = LinearLayout(ctx).apply {



                orientation = LinearLayout.VERTICAL



                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))



            }







            // Top Header Row



            val headerRow = LinearLayout(ctx).apply {



                orientation = LinearLayout.HORIZONTAL



                gravity = Gravity.CENTER_VERTICAL



                isClickable = true



                isFocusable = true



                setBackgroundResource(resolveAttrResource(ctx, android.R.attr.selectableItemBackground))



                setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))



                setOnClickListener {



                    if (item.isStreaming) {



                        if (userCollapsedTimelineIds.contains(item.id)) {



                            userCollapsedTimelineIds.remove(item.id)



                        } else {



                            userCollapsedTimelineIds.add(item.id)



                        }



                    } else {



                        if (expandedTimelineIds.contains(item.id)) {



                            expandedTimelineIds.remove(item.id)



                        } else {



                            expandedTimelineIds.add(item.id)



                        }



                    }



                    val pos = items.indexOfFirst { it.id == item.id }



                    if (pos >= 0) notifyItemChanged(pos, PAYLOAD_STREAM_UPDATE)



                }



            }







            val headerChevron = TextView(ctx).apply {



                text = if (isTopExpanded) "▼" else "▶"



                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)



                setTextColor(resolveAttrColor(ctx, android.R.attr.colorPrimary, Color.parseColor("#8AB4F8")))



                setPadding(0, 0, dp(ctx, 8), 0)



            }



            headerRow.addView(headerChevron)







            val headerText = TextView(ctx).apply {



                text = if (item.isStreaming) "Working ($durationStr)" else "Worked for $durationStr"



                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)



                setTypeface(typeface, Typeface.BOLD)



                setTextColor(resolveAttrColor(ctx, android.R.attr.colorPrimary, Color.parseColor("#8AB4F8")))



                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)



            }



            headerRow.addView(headerText)















            contentLayout.addView(headerRow)







            // If expanded, render hierarchical nested activity groups



            if (isTopExpanded) {



                val groupsLayout = LinearLayout(ctx).apply {



                    orientation = LinearLayout.VERTICAL



                    setPadding(dp(ctx, 16), dp(ctx, 6), dp(ctx, 4), dp(ctx, 4))



                }







                class ActivityGroup(



                    val key: String,



                    val title: String,



                    val items: MutableList<String>



                )







                val gThinking = ActivityGroup("thinking", "🧠 Thinking & Analysis", mutableListOf())



                val gWorking = ActivityGroup("working", "⏱️ Working & Time Tracking", mutableListOf())



                val gExploration = ActivityGroup("exploration", "📂 File & Project Exploration", mutableListOf())



                val gSearch = ActivityGroup("search", "🔎 Searching & Discovery", mutableListOf())



                val gCommands = ActivityGroup("commands", "⚙️ Commands & Tool Execution", mutableListOf())



                val gChanges = ActivityGroup("changes", "✏️ Code Changes", mutableListOf())



                val gBuild = ActivityGroup("build", "🏗️ Build & Compilation", mutableListOf())



                val gTesting = ActivityGroup("testing", "🧪 Testing & Verification", mutableListOf())



                val gGit = ActivityGroup("git", "🌿 Git & Version Control", mutableListOf())



                val gErrors = ActivityGroup("errors", "🚨 Errors & Recovery", mutableListOf())



                val gResearch = ActivityGroup("research", "📚 Research & Planning", mutableListOf())



                val gSubagent = ActivityGroup("subagent", "🤖 Agent & Subtask Activities", mutableListOf())







                for (status in allStatuses) {



                    val sLower = status.lowercase()



                    val formatted = formatActivityTitle(status)



                    when {



                        sLower.contains("git") -> gGit.items.add(formatted)



                        sLower.contains("error") || sLower.contains("fail") || sLower.contains("diagnos") || sLower.contains("root cause") || sLower.contains("recovery") -> gErrors.items.add(formatted)



                        sLower.contains("build") || sLower.contains("compil") || sLower.contains("assemble") || sLower.contains("apk") || sLower.contains("package") -> gBuild.items.add(formatted)



                        sLower.contains("test") || sLower.contains("verif") || sLower.contains("regression") -> gTesting.items.add(formatted)



                        sLower.contains("search") || sLower.contains("find") || sLower.contains("located") -> gSearch.items.add(formatted)



                        sLower.contains("read") || sLower.contains("explore") || sLower.contains("inspect") || sLower.contains("tree") -> gExploration.items.add(formatted)



                        sLower.contains("command") || sLower.contains("terminal") || sLower.contains("script") || sLower.contains("process") -> gCommands.items.add(formatted)



                        sLower.contains("edit") || sLower.contains("modif") || sLower.contains("creat") || sLower.contains("patch") || sLower.contains("writ") || sLower.contains("refactor") -> gChanges.items.add(formatted)



                        sLower.contains("think") || sLower.contains("analyz") || sLower.contains("option") -> gThinking.items.add(formatted)



                        sLower.contains("plan") || sLower.contains("research") || sLower.contains("approach") -> gResearch.items.add(formatted)



                        sLower.contains("subagent") || sLower.contains("subtask") || sLower.contains("delegate") -> gSubagent.items.add(formatted)



                        else -> gWorking.items.add(formatted)



                    }



                }







                for (mod in item.fileModifications) {



                    val modTitle = if (mod.isNewFile) "📄 Created File ${mod.fileName}" else "📝 Edited File ${mod.fileName}"



                    if (!gChanges.items.any { it.contains(mod.fileName) }) {



                        gChanges.items.add(modTitle)



                    }



                }







                val allGroups = listOf(



                    gThinking, gWorking, gExploration, gSearch,



                    gCommands, gChanges, gBuild, gTesting,



                    gGit, gErrors, gResearch, gSubagent



                ).filter { it.items.isNotEmpty() }







                for (group in allGroups) {



                    val subgroupId = "${item.id}:${group.key}"



                    val isSubExpanded = !collapsedSubgroupIds.contains(subgroupId)







                    val groupContainer = LinearLayout(ctx).apply {



                        orientation = LinearLayout.VERTICAL



                        setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))



                    }







                    // Subgroup Header



                    val subHeader = LinearLayout(ctx).apply {



                        orientation = LinearLayout.HORIZONTAL



                        gravity = Gravity.CENTER_VERTICAL



                        isClickable = true



                        isFocusable = true



                        setBackgroundResource(resolveAttrResource(ctx, android.R.attr.selectableItemBackground))



                        setPadding(dp(ctx, 2), dp(ctx, 3), dp(ctx, 2), dp(ctx, 3))



                        setOnClickListener {



                            if (collapsedSubgroupIds.contains(subgroupId)) {



                                collapsedSubgroupIds.remove(subgroupId)



                            } else {



                                collapsedSubgroupIds.add(subgroupId)



                            }



                            val pos = items.indexOfFirst { it.id == item.id }



                            if (pos >= 0) notifyItemChanged(pos, PAYLOAD_STREAM_UPDATE)



                        }



                    }







                    val subChevron = TextView(ctx).apply {



                        text = if (isSubExpanded) "▼" else "▶"



                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)



                        setTextColor(onSurfaceColor)



                        alpha = 0.7f



                        setPadding(0, 0, dp(ctx, 6), 0)



                    }



                    subHeader.addView(subChevron)







                    val subTitle = TextView(ctx).apply {



                        text = "${group.title} (${group.items.size})"



                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)



                        setTypeface(typeface, Typeface.BOLD)



                        setTextColor(onSurfaceColor)



                        alpha = 0.9f



                    }



                    subHeader.addView(subTitle)



                    groupContainer.addView(subHeader)







                    // Subgroup Children



                    if (isSubExpanded) {



                        val childrenLayout = LinearLayout(ctx).apply {



                            orientation = LinearLayout.VERTICAL



                            setPadding(dp(ctx, 16), dp(ctx, 2), dp(ctx, 4), dp(ctx, 2))



                        }







                        for (child in group.items) {



                            val childRow = LinearLayout(ctx).apply {



                                orientation = LinearLayout.HORIZONTAL



                                gravity = Gravity.CENTER_VERTICAL



                                setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))



                            }







                            val bullet = TextView(ctx).apply {



                                text = "•"



                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)



                                setTextColor(onSurfaceColor)



                                alpha = 0.5f



                                setPadding(0, 0, dp(ctx, 6), 0)



                            }



                            childRow.addView(bullet)







                            val childText = TextView(ctx).apply {



                                text = child



                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)



                                setTextColor(onSurfaceColor)



                                alpha = 0.8f



                            }



                            childRow.addView(childText)



                            childrenLayout.addView(childRow)



                        }







                        groupContainer.addView(childrenLayout)



                    }







                    groupsLayout.addView(groupContainer)



                }







                contentLayout.addView(groupsLayout)



            }







            containerCard.addView(contentLayout)



            return containerCard



        }







        private fun extractFileName(raw: String): String {



            val regex = Regex("""(?i)(?:filePath|path|file|target)\s*=\s*['"]?([^\s,'"]+)['"]?""")



            val match = regex.find(raw)



            if (match != null) {



                val fullPath = match.groupValues[1]



                return File(fullPath).name



            }



            val extRegex = Regex("""([a-zA-Z0-9_\-./\\]+\.(?:kt|java|xml|gradle|kts|cpp|h|c|py|md|txt))""")



            val extMatch = extRegex.find(raw)



            if (extMatch != null) {



                return File(extMatch.groupValues[1]).name



            }



            return ""



        }







        private fun formatActivityTitle(raw: String): String {



            val clean = raw.removePrefix("🔧").removePrefix("Analyzing").removePrefix("Running").trim()



            val fileName = extractFileName(raw)



            val sLower = raw.lowercase()







            return when {



                sLower.contains("thought for") -> raw



                sLower.contains("analyz") && sLower.contains("task") -> if (sLower.contains("analyzing")) "🔍 Analyzing Task" else "🔍 Analyzed Task"



                sLower.contains("analyz") && (sLower.contains("file") || fileName.isNotEmpty()) -> {



                    val fn = if (fileName.isNotEmpty()) " $fileName" else ""



                    if (sLower.contains("analyzing")) "📄 Analyzing File$fn" else "📄 Analyzed File$fn"



                }



                sLower.contains("analyz") && sLower.contains("code") -> if (sLower.contains("analyzing")) "💻 Analyzing Code" else "💻 Analyzed Code"



                sLower.contains("analyz") && sLower.contains("log") -> if (sLower.contains("analyzing")) "📋 Analyzing Task Log" else "📋 Analyzed Task Log"



                sLower.contains("analyz") && sLower.contains("error") -> if (sLower.contains("analyzing")) "⚠️ Analyzing Error" else "⚠️ Analyzed Error"



                sLower.contains("analyz") && (sLower.contains("build") || sLower.contains("failure")) -> if (sLower.contains("analyzing")) "🛠️ Analyzing Build Failure" else "🛠️ Analyzed Build Failure"



                sLower.contains("analyz") && sLower.contains("depend") -> if (sLower.contains("analyzing")) "📦 Analyzing Dependencies" else "📦 Analyzed Dependencies"



                sLower.contains("analyz") && sLower.contains("structure") -> if (sLower.contains("analyzing")) "🗂️ Analyzing Project Structure" else "🗂️ Analyzed Project Structure"



                sLower.contains("option") -> "⚖️ Evaluated Implementation Options"







                sLower.contains("explor") && (sLower.contains("file") || fileName.isNotEmpty()) -> {



                    val fn = if (fileName.isNotEmpty()) " $fileName" else ""



                    if (sLower.contains("exploring")) "📄 Exploring File$fn" else "📄 Explored File$fn"



                }



                sLower.contains("explor") && sLower.contains("project") -> if (sLower.contains("exploring")) "🗂️ Exploring Project" else "🗂️ Explored Project"



                sLower.contains("explor") && sLower.contains("codebase") -> if (sLower.contains("exploring")) "🧠 Exploring Codebase" else "🧠 Explored Codebase"



                sLower.contains("explor") && sLower.contains("directory") -> if (sLower.contains("exploring")) "📁 Exploring Directory" else "📁 Explored Directory"



                sLower.contains("explor") && sLower.contains("repo") -> if (sLower.contains("exploring")) "🗃️ Exploring Repository" else "🗃️ Explored Repository"



                sLower.contains("inspect") && (sLower.contains("file") || fileName.isNotEmpty()) -> {



                    val fn = if (fileName.isNotEmpty()) " $fileName" else ""



                    if (sLower.contains("inspecting")) "🔎 Inspecting File$fn" else "🔎 Inspected File$fn"



                }



                sLower.contains("inspect") && sLower.contains("structure") -> if (sLower.contains("inspecting")) "🏗️ Inspecting Project Structure" else "🏗️ Inspected Project Structure"



                sLower.contains("read") && fileName.isNotEmpty() -> "📖 Read File $fileName"



                sLower.contains("read") && sLower.contains("config") -> "⚙️ Read Configuration"







                sLower.contains("search") && fileName.isNotEmpty() -> "📂 Searching Files $fileName"



                sLower.contains("search") && sLower.contains("code") -> if (sLower.contains("searched")) "💻 Searched Code" else "💻 Searching Code"



                sLower.contains("search") && sLower.contains("ref") -> if (sLower.contains("searched")) "🔗 Searched References" else "🔗 Searching References"



                sLower.contains("search") && sLower.contains("doc") -> if (sLower.contains("searched")) "📚 Searched Documentation" else "📚 Searching Documentation"



                sLower.contains("search") && sLower.contains("depend") -> if (sLower.contains("searched")) "📦 Searched Dependencies" else "📦 Searching Dependencies"



                sLower.contains("search") -> if (sLower.contains("searched")) "📂 Searched Files" else "🔍 Searching..."



                sLower.contains("found") && sLower.contains("file") -> "📄 Found Relevant Files"



                sLower.contains("found") && sLower.contains("impl") -> "💡 Found Implementation"



                sLower.contains("found") && sLower.contains("error") -> "🐛 Found Error Source"



                sLower.contains("found") && sLower.contains("config") -> "⚙️ Found Configuration"



                sLower.contains("located") -> "📍 Located Definition"







                sLower.contains("terminal") || sLower.contains("exec_command") -> "💻 Ran Terminal Command"



                sLower.contains("build command") || sLower.contains("run_build") -> "🔨 Ran Build Command"



                sLower.contains("test") && (sLower.contains("ran") || sLower.contains("run")) -> "🧪 Ran Tests"



                sLower.contains("diff") -> "📝 Ran Git Diff Summary"



                sLower.contains("script") -> "📜 Executed Script"



                sLower.contains("process") -> "🚀 Started Process"



                sLower.contains("command") -> "▶️ Ran Command"







                sLower.contains("edit") && fileName.isNotEmpty() -> "📝 Edited File $fileName"



                sLower.contains("creat") && fileName.isNotEmpty() -> "📄 Created File $fileName"



                sLower.contains("modif") && sLower.contains("code") -> "💻 Modified Source Code"



                sLower.contains("updat") && sLower.contains("config") -> "⚙️ Updated Configuration"



                sLower.contains("creat") && sLower.contains("dir") -> "📁 Created Directory"



                sLower.contains("refactor") -> "♻️ Refactored Code"



                sLower.contains("applied") -> "✅ Applied Changes"



                sLower.contains("revert") -> "↩️ Reverted Changes"



                sLower.contains("conflict") -> "🔀 Resolved Conflict"



                sLower.contains("generat") -> "🤖 Generated Code"







                sLower.contains("building apk") -> "📦 Building APK..."



                sLower.contains("build debug apk finished") -> "✅ Build Debug APK Finished"



                sLower.contains("compiling kotlin") -> "⚙️ Compiling Kotlin Code..."



                sLower.contains("compile debug kotlin finished") -> "✅ Compile Debug Kotlin Finished"



                sLower.contains("assembling") -> "🔨 Assembling Debug APK..."



                sLower.contains("assemble debug apk finished") -> "✅ Assemble Debug APK Finished"



                sLower.contains("resolving dep") -> "📥 Resolving Dependencies..."



                sLower.contains("packaging") -> "📦 Packaging Outputs"



                sLower.contains("build failed") -> "❌ Build Failed"



                sLower.contains("build verification") -> "🔍 Build Verification Completed"







                sLower.contains("verif") && sLower.contains("pass") -> "✅ Verified Changes"



                sLower.contains("verif") && sLower.contains("fail") -> "❌ Verification Failed"



                sLower.contains("verif") -> "🔎 Verifying Changes..."



                sLower.contains("compil") && sLower.contains("success") -> "✅ Compilation Successful"



                sLower.contains("checking compil") -> "🔍 Checking Compilation..."



                sLower.contains("unit test") -> "🧪 Running Unit Tests..."



                sLower.contains("integration test") -> "🧪 Running Integration Tests..."



                sLower.contains("test result") -> "📊 Checking Test Results"



                sLower.contains("regression") -> "🛡️ Checking Regressions"







                sLower.contains("git status") -> "📊 Checked Git Status"



                sLower.contains("branch") -> "🌿 Checked Git Branches"



                sLower.contains("commit") -> "🕒 Checked Recent Commits"



                sLower.contains("untracked") -> "📂 Checked Untracked Files"



                sLower.contains("changed file") -> "📋 Reviewed Changed Files"







                sLower.contains("error") && sLower.contains("encount") -> "⚠️ Encountered Error"



                sLower.contains("investigat") -> "🔍 Investigating Failure"



                sLower.contains("diagnos") -> "🩺 Diagnosing Build Error"



                sLower.contains("root cause") -> "🎯 Identified Root Cause"



                sLower.contains("recovery") && sLower.contains("complet") -> "✅ Recovery Completed"



                sLower.contains("recovery") && sLower.contains("fail") -> "❌ Recovery Failed"



                sLower.contains("recovery") -> "🛠️ Attempting Recovery"



                sLower.contains("retry") -> "🔄 Retrying Operation"



                sLower.contains("fixing") -> "🔧 Fixing Compilation Errors"



                sLower.contains("error log") -> "📋 Checking Error Logs"







                sLower.contains("plan") -> "📝 Preparing Implementation Plan"



                sLower.contains("review") && sLower.contains("plan") -> "📋 Reviewing Implementation Plan"



                sLower.contains("risk") -> "⚠️ Assessing Risks"



                sLower.contains("report") -> "📑 Preparing Final Report"







                sLower.contains("subagent") && sLower.contains("start") -> "🚀 Started Subagent"



                sLower.contains("subagent") && sLower.contains("complet") -> "✅ Subagent Completed"



                sLower.contains("subagent") && sLower.contains("fail") -> "❌ Subagent Failed"



                sLower.contains("subagent") -> "👀 Monitoring Subagent"



                sLower.contains("delegat") -> "📤 Delegated Task"







                sLower.contains("worked for") -> raw



                sLower.contains("working") -> raw







                else -> if (clean.length > 60) clean.take(57) + "…" else clean.ifEmpty { "⚙️ Working..." }



            }



        }







    private fun copyToClipboard(context: Context, label: String, text: String) {



        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager



        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))



        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()



    }







    private fun resolveAttrResource(context: Context, attr: Int): Int {



        val tv = TypedValue()



        return if (context.theme.resolveAttribute(attr, tv, true)) {



            tv.resourceId



        } else {



            0



        }



    }







    private fun resolveAttrColor(context: Context, attr: Int, fallback: Int): Int {



        val tv = TypedValue()



        return if (context.theme.resolveAttribute(attr, tv, true)) {



            tv.data



        } else {



            fallback



        }



    }







    private fun getStatusColor(context: Context, colorResId: Int, fallbackHex: String): Int {



        return try {



            androidx.core.content.ContextCompat.getColor(context, colorResId)



        } catch (_: Throwable) {



            Color.parseColor(fallbackHex)



        }



    }







    private fun showImagePreviewDialog(context: Context, bmp: android.graphics.Bitmap) {
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .create()
        val scroll = android.widget.ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val img = android.widget.ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
            val pad = dp(context, 16)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(img)
        dialog.setView(scroll)
        dialog.setButton(android.content.DialogInterface.BUTTON_POSITIVE, context.getString(android.R.string.ok)) { d, _ ->
            d.dismiss()
        }
        dialog.show()
    }

    private fun dp(context: Context, value: Int): Int {



        return TypedValue.applyDimension(



            TypedValue.COMPLEX_UNIT_DIP,



            value.toFloat(),



            context.resources.displayMetrics



        ).toInt()



    }



}



