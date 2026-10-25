/*
 *  This file is part of AndroidCodeStudio (CURSED ACS).
 *  Adapter for persistent AI chat conversations.
 */

package com.tom.rv2ide.adapters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.chat.ChatConversation
import com.tom.rv2ide.artificial.transport.ChatRole
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatSessionAdapter(
    private val onSessionClick: (ChatConversation) -> Unit,
    private val onSessionRename: (ChatConversation) -> Unit,
    private val onSessionDelete: (ChatConversation) -> Unit
) : RecyclerView.Adapter<ChatSessionAdapter.SessionViewHolder>() {

    private val items = mutableListOf<ChatConversation>()
    private var activeConversationId: String? = null
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())

    fun submitData(newItems: List<ChatConversation>, activeId: String?) {
        items.clear()
        items.addAll(newItems)
        activeConversationId = activeId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_session, parent, false)
        return SessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.chatSessionCard)
        private val icon: ImageView = itemView.findViewById(R.id.sessionIcon)
        private val titleText: TextView = itemView.findViewById(R.id.sessionTitleText)
        private val timeText: TextView = itemView.findViewById(R.id.sessionTimeText)
        private val activeBadge: TextView = itemView.findViewById(R.id.sessionActiveBadge)
        private val menuBtn: MaterialButton = itemView.findViewById(R.id.sessionMenuBtn)
        private val snippetText: TextView = itemView.findViewById(R.id.sessionSnippetText)
        private val turnBadge: TextView = itemView.findViewById(R.id.sessionTurnBadge)
        private val projectText: TextView = itemView.findViewById(R.id.sessionProjectText)

        fun bind(item: ChatConversation) {
            val context = itemView.context
            val isActive = item.id == activeConversationId

            titleText.text = item.title.ifBlank { "Untitled Conversation" }
            timeText.text = formatTimestamp(item.updatedAt)

            // Active Badge & Highlight
            if (isActive) {
                activeBadge.visibility = View.VISIBLE
                card.strokeColor = Color.parseColor("#FF2A55") // Sukuna Crimson
                card.setCardBackgroundColor(Color.parseColor("#171D27"))
            } else {
                activeBadge.visibility = View.GONE
                card.strokeColor = Color.parseColor("#1E293B")
                card.setCardBackgroundColor(Color.parseColor("#0F141C"))
            }

            // Message Snippet
            val lastAssistantMsg = item.messages.findLast { it.role == ChatRole.ASSISTANT && it.content.isNotBlank() }
            val lastUserMsg = item.messages.findLast { it.role == ChatRole.USER && it.content.isNotBlank() }
            val effectiveSnippet = when {
                lastAssistantMsg != null -> {
                    val raw = lastAssistantMsg.content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                    if (raw.length > 120) raw.take(117) + "..." else raw
                }
                lastUserMsg != null -> {
                    val raw = lastUserMsg.content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                    "User: " + if (raw.length > 120) raw.take(117) + "..." else raw
                }
                else -> "No messages yet"
            }
            snippetText.text = effectiveSnippet

            // Turn Badge
            val turnCount = item.messages.count { it.role == ChatRole.USER }
            val msgCount = item.messages.size
            turnBadge.text = if (turnCount > 0) "$turnCount turn${if (turnCount == 1) "" else "s"}" else "$msgCount msg"

            // Project badge
            val projName = if (item.projectPath.isNotBlank()) {
                File(item.projectPath).name
            } else {
                "Global"
            }
            projectText.text = projName

            // Click listener -> Switch and navigate
            card.setOnClickListener {
                onSessionClick(item)
            }

            // Overflow popup menu
            menuBtn.setOnClickListener { anchor ->
                val popup = PopupMenu(context, anchor)
                popup.menu.add(0, 1, 0, "Rename Conversation")
                popup.menu.add(0, 2, 1, "Copy Transcript")
                popup.menu.add(0, 3, 2, "Delete Conversation")
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> {
                            onSessionRename(item)
                            true
                        }
                        2 -> {
                            copyTranscriptToClipboard(context, item)
                            true
                        }
                        3 -> {
                            onSessionDelete(item)
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }
        }

        private fun formatTimestamp(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            return when {
                diff < 60_000L -> "Just now"
                diff < 3600_000L -> "${diff / 60_000L}m ago"
                DateUtils.isToday(timestamp) -> "Today, ${timeFormat.format(Date(timestamp))}"
                DateUtils.isToday(timestamp + DateUtils.DAY_IN_MILLIS) -> "Yesterday, ${timeFormat.format(Date(timestamp))}"
                else -> dateFormat.format(Date(timestamp))
            }
        }

        private fun copyTranscriptToClipboard(context: Context, item: ChatConversation) {
            val transcript = buildString {
                appendLine("# Conversation: ${item.title}")
                appendLine("Project: ${item.projectPath}")
                appendLine("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.updatedAt))}")
                appendLine()
                for (msg in item.messages) {
                    val role = if (msg.role == ChatRole.USER) "User" else "Assistant"
                    appendLine("## $role:")
                    appendLine(msg.content)
                    appendLine()
                }
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Chat Transcript", transcript))
        }
    }
}
