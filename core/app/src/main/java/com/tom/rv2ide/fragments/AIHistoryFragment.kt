/*
 *  This file is part of AndroidCodeStudio (CURSED ACS).
 *  Persistent AI Conversation History Fragment.
 */

package com.tom.rv2ide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tom.rv2ide.R
import com.tom.rv2ide.adapters.ChatSessionAdapter
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.chat.ChatConversation
import com.tom.rv2ide.artificial.chat.ConversationSessionManager
import com.tom.rv2ide.fragments.sidebar.ArtificialFragment

class AIHistoryFragment : Fragment() {

    private lateinit var aiAgent: AIAgentManager
    private val sessionManager by lazy { ConversationSessionManager.getInstance(requireContext()) }

    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var emptyHistoryContainer: View
    private lateinit var historyHeaderTitle: TextView
    private lateinit var newChatHistoryBtn: MaterialButton
    private lateinit var startNewChatEmptyBtn: MaterialButton

    private lateinit var chatSessionAdapter: ChatSessionAdapter

    private val currentProjectRoot: String
        get() {
            return try {
                val pr = com.tom.rv2ide.utils.ProjectHelper.getProjectRoot().absolutePath
                if (pr.isNotBlank()) pr else (com.tom.rv2ide.builder.SharedBuildOrchestrator.getActiveProjectDir()?.absolutePath ?: "")
            } catch (_: Throwable) {
                com.tom.rv2ide.builder.SharedBuildOrchestrator.getActiveProjectDir()?.absolutePath ?: ""
            }
        }

    private val sessionListener = object : ConversationSessionManager.SessionListener {
        override fun onConversationUpdated(conversation: ChatConversation) {
            activity?.runOnUiThread {
                if (isAdded && view != null) {
                    loadConversations()
                }
            }
        }
    }

    companion object {
        fun newInstance(aiAgent: AIAgentManager): AIHistoryFragment {
            return AIHistoryFragment().apply {
                this.aiAgent = aiAgent
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!::aiAgent.isInitialized) {
            aiAgent = AIAgentManager(requireContext())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        historyRecyclerView = view.findViewById(R.id.historyRecyclerView)
        emptyHistoryContainer = view.findViewById(R.id.emptyHistoryContainer)
        historyHeaderTitle = view.findViewById(R.id.historyHeaderTitle)
        newChatHistoryBtn = view.findViewById(R.id.newChatHistoryBtn)
        startNewChatEmptyBtn = view.findViewById(R.id.startNewChatEmptyBtn)

        chatSessionAdapter = ChatSessionAdapter(
            onSessionClick = { conversation ->
                openConversation(conversation)
            },
            onSessionRename = { conversation ->
                showRenameDialog(conversation)
            },
            onSessionDelete = { conversation ->
                showDeleteDialog(conversation)
            }
        )

        historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = chatSessionAdapter
        }

        newChatHistoryBtn.setOnClickListener {
            startNewChat()
        }

        startNewChatEmptyBtn.setOnClickListener {
            startNewChat()
        }

        sessionManager.addListener(sessionListener)
        loadConversations()
    }

    override fun onResume() {
        super.onResume()
        loadConversations()
    }

    override fun onDestroyView() {
        sessionManager.removeListener(sessionListener)
        super.onDestroyView()
    }

    private fun loadConversations() {
        val projectPath = currentProjectRoot
        val conversations = sessionManager.listConversations(projectPath)
        val activeId = sessionManager.getActiveConversation(projectPath).id

        historyHeaderTitle.text = "Chat Sessions (${conversations.size})"

        val isEmpty = conversations.isEmpty()
        historyRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyHistoryContainer.visibility = if (isEmpty) View.VISIBLE else View.GONE

        chatSessionAdapter.submitData(conversations, activeId)
    }

    private fun openConversation(conversation: ChatConversation) {
        sessionManager.switchConversation(conversation.id, currentProjectRoot)
        loadConversations()
        (parentFragment as? ArtificialFragment)?.selectTab(0)
    }

    private fun startNewChat() {
        sessionManager.startNewConversation(currentProjectRoot)
        loadConversations()
        (parentFragment as? ArtificialFragment)?.selectTab(0)
    }

    private fun showRenameDialog(conversation: ChatConversation) {
        val input = EditText(requireContext()).apply {
            setText(conversation.title)
            setSelection(conversation.title.length)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rename Conversation")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = input.text?.toString()?.trim().orEmpty()
                if (newTitle.isNotEmpty()) {
                    sessionManager.renameConversation(conversation.id, newTitle)
                    loadConversations()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteDialog(conversation: ChatConversation) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Conversation")
            .setMessage("Are you sure you want to delete '${conversation.title}'? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                sessionManager.deleteConversation(conversation.id, currentProjectRoot)
                loadConversations()
                view?.let {
                    Snackbar.make(it, "Conversation deleted", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}