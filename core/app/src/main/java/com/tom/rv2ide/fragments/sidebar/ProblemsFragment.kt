/*
 *  This file is part of AndroidCodeStudio (CURSED ACS).
 *  Production-Grade Advanced Problems Center for Project Diagnostics,
 *  Real-Time Build Errors, Grouping/Filtering, and Autonomous AI Repair.
 */

package com.tom.rv2ide.fragments.sidebar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.R
import com.tom.rv2ide.databinding.DialogDiagnosticDetailsBinding
import com.tom.rv2ide.databinding.FragmentProblemsBinding
import com.tom.rv2ide.fragments.ChatFragment
import com.tom.rv2ide.fragments.FragmentWithBinding
import com.tom.rv2ide.interfaces.IEditorHandler
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.problems.ProjectProblemRegistry
import java.io.File

class ProblemsFragment :
    FragmentWithBinding<FragmentProblemsBinding>(FragmentProblemsBinding::inflate) {

    private lateinit var adapter: ProblemsAdapter
    private var allGroups: List<ProblemFileGroup> = emptyList()
    private var currentFilter: ProblemFilter = ProblemFilter.ALL
    private var currentGrouping: ProblemGrouping = ProblemGrouping.BY_FILE
    private var currentSorting: ProblemSorting = ProblemSorting.BY_SEVERITY
    private var currentSearchQuery: String = ""

    private val problemListener: () -> Unit = {
        if (isAdded && view != null) {
            reloadDiagnostics()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ProblemsAdapter(
            onDiagnosticClick = { file, diagnostic ->
                if (file.name == "[Project Build]" || !file.exists()) {
                    showDiagnosticDetailsDialog(file, diagnostic)
                } else {
                    (activity as? IEditorHandler)?.let { editorHandler ->
                        editorHandler.openFileAndSelect(file, diagnostic.range)
                    }
                }
            },
            onAiFixClick = { file, diagnostic ->
                val prompt = ProjectProblemRegistry.getInstance().buildAiFixPrompt(diagnostic, file)
                ChatFragment.requestAiFix(prompt)
            },
            onDetailsClick = { file, diagnostic ->
                showDiagnosticDetailsDialog(file, diagnostic)
            }
        )

        binding.problemsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.problemsRecyclerView.adapter = adapter

        setupOverviewAndHeader()
        setupFilterChips()
        setupSearchBar()
        setupGroupingBar()

        binding.refreshDiagnosticsBtn.setOnClickListener {
            reloadDiagnostics()
        }

        binding.problemsMoreOptionsBtn.setOnClickListener { anchor ->
            showMoreOptionsMenu(anchor)
        }

        ProjectProblemRegistry.getInstance().addListener(problemListener)
        reloadDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        reloadDiagnostics()
    }

    override fun onDestroyView() {
        ProjectProblemRegistry.getInstance().removeListener(problemListener)
        super.onDestroyView()
    }

    private fun setupOverviewAndHeader() {
        binding.buildStateBadge.setOnClickListener {
            val registry = ProjectProblemRegistry.getInstance()
            val summary = registry.getLastBuildSummary() ?: registry.getBuildState().displayName
            Toast.makeText(requireContext(), summary, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFilterChips() {
        binding.chipFilterAll.setOnClickListener {
            currentFilter = ProblemFilter.ALL
            applyFilter()
        }

        binding.chipFilterErrors.setOnClickListener {
            currentFilter = ProblemFilter.ERRORS_ONLY
            applyFilter()
        }

        binding.chipFilterWarnings.setOnClickListener {
            currentFilter = ProblemFilter.WARNINGS_ONLY
            applyFilter()
        }

        binding.chipFilterCurrentFile.setOnClickListener {
            currentFilter = ProblemFilter.CURRENT_FILE
            applyFilter()
        }

        binding.chipFilterBuild.setOnClickListener {
            currentFilter = ProblemFilter.BUILD_ONLY
            applyFilter()
        }
    }

    private fun setupSearchBar() {
        binding.problemsSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                binding.problemsClearSearchBtn.visibility = if (currentSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.problemsClearSearchBtn.setOnClickListener {
            binding.problemsSearchInput.setText("")
        }
    }

    private fun setupGroupingBar() {
        binding.toggleExpandAllBtn.setOnClickListener {
            if (adapter.isAllExpanded()) {
                adapter.collapseAll()
                binding.toggleExpandAllBtn.text = "Expand All"
            } else {
                adapter.expandAll()
                binding.toggleExpandAllBtn.text = "Collapse All"
            }
        }
    }

    private fun showMoreOptionsMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.apply {
            val groupSub = addSubMenu("Group by")
            groupSub.add(0, 101, 0, "Group by File").apply { isCheckable = true; isChecked = (currentGrouping == ProblemGrouping.BY_FILE) }
            groupSub.add(0, 102, 1, "Group by Severity").apply { isCheckable = true; isChecked = (currentGrouping == ProblemGrouping.BY_SEVERITY) }
            groupSub.add(0, 103, 2, "Group by Diagnostic Source").apply { isCheckable = true; isChecked = (currentGrouping == ProblemGrouping.BY_SOURCE) }

            val sortSub = addSubMenu("Sort by")
            sortSub.add(0, 201, 0, "Sort by Severity").apply { isCheckable = true; isChecked = (currentSorting == ProblemSorting.BY_SEVERITY) }
            sortSub.add(0, 202, 1, "Sort by File Name").apply { isCheckable = true; isChecked = (currentSorting == ProblemSorting.BY_FILE_NAME) }
            sortSub.add(0, 203, 2, "Sort by Location").apply { isCheckable = true; isChecked = (currentSorting == ProblemSorting.BY_LOCATION) }
            sortSub.add(0, 204, 3, "Sort by Message").apply { isCheckable = true; isChecked = (currentSorting == ProblemSorting.BY_MESSAGE) }

            add(0, 301, 3, "Expand All Groups")
            add(0, 302, 4, "Collapse All Groups")
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                101 -> { currentGrouping = ProblemGrouping.BY_FILE; reloadDiagnostics(); true }
                102 -> { currentGrouping = ProblemGrouping.BY_SEVERITY; reloadDiagnostics(); true }
                103 -> { currentGrouping = ProblemGrouping.BY_SOURCE; reloadDiagnostics(); true }
                201 -> { currentSorting = ProblemSorting.BY_SEVERITY; reloadDiagnostics(); true }
                202 -> { currentSorting = ProblemSorting.BY_FILE_NAME; reloadDiagnostics(); true }
                203 -> { currentSorting = ProblemSorting.BY_LOCATION; reloadDiagnostics(); true }
                204 -> { currentSorting = ProblemSorting.BY_MESSAGE; reloadDiagnostics(); true }
                301 -> { adapter.expandAll(); binding.toggleExpandAllBtn.text = "Collapse All"; true }
                302 -> { adapter.collapseAll(); binding.toggleExpandAllBtn.text = "Expand All"; true }
                else -> false
            }
        }
        popup.show()
    }

    fun reloadDiagnostics() {
        val registry = ProjectProblemRegistry.getInstance()
        allGroups = registry.getProblemGroups(currentGrouping, currentSorting)
        updateOverviewHeader()
        updateFilterCounts()
        applyFilter()
    }

    private fun updateOverviewHeader() {
        var totalErrors = 0
        var totalWarnings = 0
        var totalInfos = 0

        for (group in allGroups) {
            totalErrors += group.errorCount
            totalWarnings += group.warningCount
            totalInfos += group.infoCount
        }

        val totalAll = totalErrors + totalWarnings + totalInfos
        val affectedFiles = allGroups.count { it.diagnostics.isNotEmpty() }

        val context = requireContext()
        if (totalErrors > 0) {
            binding.problemsStatusIcon.setImageResource(R.drawable.ic_error)
            binding.problemsStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_error))
            binding.problemsOverviewTitle.text = "$totalErrors Error${if (totalErrors > 1) "s" else ""}${if (totalWarnings > 0) ", $totalWarnings Warning(s)" else ""}"
            binding.problemsOverviewSubtitle.text = "$affectedFiles file${if (affectedFiles > 1) "s" else ""} affected · Total $totalAll problems"
        } else if (totalWarnings > 0) {
            binding.problemsStatusIcon.setImageResource(R.drawable.ic_warning)
            binding.problemsStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_warning))
            binding.problemsOverviewTitle.text = "$totalWarnings Warning${if (totalWarnings > 1) "s" else ""}"
            binding.problemsOverviewSubtitle.text = "$affectedFiles file${if (affectedFiles > 1) "s" else ""} affected · No blocking errors"
        } else {
            binding.problemsStatusIcon.setImageResource(R.drawable.ic_check_circle)
            binding.problemsStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_success))
            binding.problemsOverviewTitle.text = "0 Problems Detected"
            binding.problemsOverviewSubtitle.text = "Project workspace clean and up to date"
        }

        val buildState = ProjectProblemRegistry.getInstance().getBuildState()
        binding.buildStateBadge.text = buildState.displayName
        val typedVal = android.util.TypedValue()
        context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedVal, true)
        val defaultColor = typedVal.data
        binding.buildStateBadge.setTextColor(
            when (buildState) {
                BuildState.BUILD_FAILED -> ContextCompat.getColor(context, R.color.status_error)
                BuildState.BUILDING, BuildState.ANALYZING -> ContextCompat.getColor(context, R.color.status_warning)
                BuildState.BUILD_SUCCEEDED -> ContextCompat.getColor(context, R.color.status_success)
                else -> defaultColor
            }
        )

        val groupingName = when (currentGrouping) {
            ProblemGrouping.BY_FILE -> "File"
            ProblemGrouping.BY_SEVERITY -> "Severity"
            ProblemGrouping.BY_SOURCE -> "Source"
        }
        val sortingName = when (currentSorting) {
            ProblemSorting.BY_SEVERITY -> "Severity"
            ProblemSorting.BY_FILE_NAME -> "Name"
            ProblemSorting.BY_LOCATION -> "Location"
            ProblemSorting.BY_MESSAGE -> "Message"
        }
        binding.problemsGroupingStatusText.text = "Grouped by $groupingName · Sorted by $sortingName"
    }

    private fun updateFilterCounts() {
        var totalErrors = 0
        var totalWarnings = 0
        var totalInfos = 0

        for (group in allGroups) {
            totalErrors += group.errorCount
            totalWarnings += group.warningCount
            totalInfos += group.infoCount
        }

        val totalAll = totalErrors + totalWarnings + totalInfos

        binding.chipFilterAll.text = "All ($totalAll)"
        binding.chipFilterErrors.text = "Errors ($totalErrors)"
        binding.chipFilterWarnings.text = "Warnings ($totalWarnings)"
    }

    private fun applyFilter() {
        val activeFile = ProjectProblemRegistry.getInstance().getActiveEditorFile()
        adapter.submitData(allGroups, currentFilter, activeFile, currentSearchQuery)

        val hasVisibleItems = allGroups.any { group ->
            group.filteredDiagnostics(currentFilter, activeFile, currentSearchQuery).isNotEmpty()
        }

        if (!hasVisibleItems) {
            binding.problemsRecyclerView.visibility = View.GONE
            binding.problemsEmptyState.visibility = View.VISIBLE

            val hasBeenBuilt = ProjectProblemRegistry.getInstance().hasProjectBeenBuilt()
            if (!hasBeenBuilt && allGroups.isEmpty()) {
                binding.problemsEmptyTitle.text = getString(R.string.problems_empty_title)
                binding.problemsEmptySummary.text = "Project not yet built. Run a build to detect all project errors."
            } else if (currentSearchQuery.isNotBlank()) {
                binding.problemsEmptyTitle.text = "No Matching Problems"
                binding.problemsEmptySummary.text = "No diagnostics match '$currentSearchQuery'."
            } else {
                binding.problemsEmptyTitle.text = getString(R.string.problems_empty_title)
                binding.problemsEmptySummary.text = getString(R.string.problems_empty_summary)
            }
        } else {
            binding.problemsRecyclerView.visibility = View.VISIBLE
            binding.problemsEmptyState.visibility = View.GONE
        }
    }

    private fun showDiagnosticDetailsDialog(file: File, diagnostic: DiagnosticItem) {
        val dialogBinding = DialogDiagnosticDetailsBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()

        val isError = diagnostic.severity == DiagnosticSeverity.ERROR
        val isWarning = diagnostic.severity == DiagnosticSeverity.WARNING

        dialogBinding.dialogSeverityIcon.setImageResource(
            when {
                isError -> R.drawable.ic_error
                isWarning -> R.drawable.ic_warning
                else -> R.drawable.ic_check_circle
            }
        )
        dialogBinding.dialogSeverityIcon.setColorFilter(
            ContextCompat.getColor(
                requireContext(),
                when {
                    isError -> R.color.status_error
                    isWarning -> R.color.status_warning
                    else -> R.color.status_success
                }
            )
        )

        dialogBinding.dialogSeverityTitle.text = when {
            isError -> "Error: ${file.name}"
            isWarning -> "Warning: ${file.name}"
            else -> "Diagnostic: ${file.name}"
        }

        val line = (diagnostic.range.start.line + 1).coerceAtLeast(1)
        val col = (diagnostic.range.start.column + 1).coerceAtLeast(1)
        val displayPath = if (file.name == "[Project Build]") "[Project Build]" else "${file.name}:$line:$col"
        dialogBinding.dialogLocationChip.text = displayPath
        dialogBinding.dialogSourceChip.text = diagnostic.source.ifBlank { "COMPILER" }

        dialogBinding.dialogFullMessageText.text = diagnostic.message

        // Show source snippet if file exists
        if (file.exists() && file.isFile) {
            try {
                val lines = file.readLines()
                if (line <= lines.size) {
                    val start = maxOf(1, line - 2)
                    val end = minOf(lines.size, line + 2)
                    val snippet = buildString {
                        for (i in start..end) {
                            val mark = if (i == line) ">" else " "
                            appendLine("%s %4d | %s".format(mark, i, lines[i - 1]))
                        }
                    }.trimEnd()
                    dialogBinding.dialogSourceSnippet.text = snippet
                    dialogBinding.dialogSourceSnippet.visibility = View.VISIBLE
                }
            } catch (_: Throwable) {}
        }

        dialogBinding.dialogCloseBtn.setOnClickListener {
            dialog.dismiss()
        }

        // Ask AI to Fix
        dialogBinding.dialogBtnAiFix.setOnClickListener {
            dialog.dismiss()
            val prompt = ProjectProblemRegistry.getInstance().buildAiFixPrompt(diagnostic, file)
            ChatFragment.requestAiFix(prompt)
        }

        // Open in Editor
        dialogBinding.dialogBtnJumpToSource.setOnClickListener {
            dialog.dismiss()
            if (file.exists()) {
                (activity as? IEditorHandler)?.openFileAndSelect(file, diagnostic.range)
            } else {
                Toast.makeText(requireContext(), "File not available on disk.", Toast.LENGTH_SHORT).show()
            }
        }

        // Copy Context
        dialogBinding.dialogBtnCopyContext.setOnClickListener {
            val prompt = ProjectProblemRegistry.getInstance().buildAiFixPrompt(diagnostic, file)
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Diagnostic Context", prompt))
            Toast.makeText(requireContext(), "Diagnostic context copied to clipboard.", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }
}
