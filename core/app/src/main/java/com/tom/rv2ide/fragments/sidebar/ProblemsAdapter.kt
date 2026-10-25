/*
 *  This file is part of AndroidCodeStudio (ROXUM-ACS / CURSED ACS).
 *  Hierarchical Adapter for Problems Panel with file group expansion, search filtering,
 *  tap-to-jump, AI quick repair, and diagnostic details dialog.
 */

package com.tom.rv2ide.fragments.sidebar

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom.rv2ide.R
import com.tom.rv2ide.databinding.ItemProblemDiagnosticBinding
import com.tom.rv2ide.databinding.ItemProblemFileGroupBinding
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import java.io.File

class ProblemsAdapter(
    private val onDiagnosticClick: (File, DiagnosticItem) -> Unit,
    private val onAiFixClick: (File, DiagnosticItem) -> Unit,
    private val onDetailsClick: (File, DiagnosticItem) -> Unit
) : RecyclerView.Adapter<ProblemsAdapter.FileGroupViewHolder>() {

    private val groups = mutableListOf<ProblemFileGroup>()
    private var currentFilter: ProblemFilter = ProblemFilter.ALL
    private var currentActiveFile: File? = null
    private var currentSearchQuery: String = ""

    fun submitData(
        newGroups: List<ProblemFileGroup>,
        filter: ProblemFilter = currentFilter,
        activeFile: File? = currentActiveFile,
        searchQuery: String = currentSearchQuery
    ) {
        currentFilter = filter
        currentActiveFile = activeFile
        currentSearchQuery = searchQuery
        groups.clear()
        val filtered = newGroups.filter { group ->
            group.filteredDiagnostics(filter, activeFile, searchQuery).isNotEmpty()
        }
        groups.addAll(filtered)
        notifyDataSetChanged()
    }

    fun expandAll() {
        for (g in groups) {
            g.isExpanded = true
        }
        notifyDataSetChanged()
    }

    fun collapseAll() {
        for (g in groups) {
            g.isExpanded = false
        }
        notifyDataSetChanged()
    }

    fun isAllExpanded(): Boolean {
        return groups.isNotEmpty() && groups.all { it.isExpanded }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileGroupViewHolder {
        val binding = ItemProblemFileGroupBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FileGroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileGroupViewHolder, position: Int) {
        holder.bind(groups[position])
    }

    override fun getItemCount(): Int = groups.size

    inner class FileGroupViewHolder(
        private val binding: ItemProblemFileGroupBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(group: ProblemFileGroup) {
            val context = binding.root.context
            binding.fileNameText.text = group.fileName
            binding.filePathText.text = group.filePath

            val errors = group.errorCount
            val warnings = group.warningCount

            if (errors > 0) {
                binding.errorCountPill.text = "$errors"
                binding.errorCountPill.visibility = View.VISIBLE
            } else {
                binding.errorCountPill.visibility = View.GONE
            }

            if (warnings > 0) {
                binding.warningCountPill.text = "$warnings"
                binding.warningCountPill.visibility = View.VISIBLE
            } else {
                binding.warningCountPill.visibility = View.GONE
            }

            val filteredList = group.filteredDiagnostics(currentFilter, currentActiveFile, currentSearchQuery)

            val childAdapter = ChildDiagnosticAdapter(group.file, filteredList)
            binding.childDiagnosticsRecycler.layoutManager = LinearLayoutManager(context)
            binding.childDiagnosticsRecycler.adapter = childAdapter

            updateExpandedState(group.isExpanded)

            binding.groupHeaderRow.setOnClickListener {
                group.isExpanded = !group.isExpanded
                updateExpandedState(group.isExpanded)
            }
        }

        private fun updateExpandedState(isExpanded: Boolean) {
            binding.chevronIcon.setImageResource(
                if (isExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
            )
            binding.childDiagnosticsRecycler.visibility = if (isExpanded) View.VISIBLE else View.GONE
        }
    }

    inner class ChildDiagnosticAdapter(
        private val file: File,
        private val items: List<DiagnosticItem>
    ) : RecyclerView.Adapter<ChildDiagnosticAdapter.DiagnosticViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DiagnosticViewHolder {
            val binding = ItemProblemDiagnosticBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return DiagnosticViewHolder(binding)
        }

        override fun onBindViewHolder(holder: DiagnosticViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class DiagnosticViewHolder(
            private val binding: ItemProblemDiagnosticBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(diagnostic: DiagnosticItem) {
                val context = binding.root.context
                val isError = diagnostic.severity == DiagnosticSeverity.ERROR
                val isWarning = diagnostic.severity == DiagnosticSeverity.WARNING

                binding.severityIcon.setImageResource(
                    when {
                        isError -> R.drawable.ic_error
                        isWarning -> R.drawable.ic_warning
                        else -> R.drawable.ic_check_circle
                    }
                )
                binding.severityIcon.setColorFilter(
                    ContextCompat.getColor(
                        context,
                        when {
                            isError -> R.color.status_error
                            isWarning -> R.color.status_warning
                            else -> R.color.status_success
                        }
                    )
                )

                binding.diagnosticMessageText.text = diagnostic.message

                val line = (diagnostic.range.start.line + 1).coerceAtLeast(1)
                val col = (diagnostic.range.start.column + 1).coerceAtLeast(1)
                binding.diagnosticLocationTag.text = "L$line:$col"

                val source = diagnostic.source.ifBlank { "COMPILER" }
                binding.diagnosticSourceCodeTag.text = "[$source]"

                // Navigate to file location on row tap
                binding.diagnosticItemRow.setOnClickListener {
                    onDiagnosticClick(file, diagnostic)
                }

                binding.diagnosticItemRow.setOnLongClickListener {
                    onDetailsClick(file, diagnostic)
                    true
                }

                // AI Fix action
                binding.btnFixDiagnosticWithAi.setOnClickListener {
                    onAiFixClick(file, diagnostic)
                }

                // Details dialog action
                binding.btnDiagnosticDetails.setOnClickListener {
                    onDetailsClick(file, diagnostic)
                }
            }
        }
    }
}
