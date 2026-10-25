/*
 *  This file is part of AndroidCodeStudio (CURSED ACS).
 *  Centralized Problem & Diagnostic Aggregator for project-wide compiler, LSP, and build diagnostics.
 */

package com.tom.rv2ide.problems

import android.os.Handler
import android.os.Looper
import com.tom.rv2ide.artificial.coding.BuildDiagnostic
import com.tom.rv2ide.artificial.coding.StructuredDiagnosticCollector
import com.tom.rv2ide.fragments.sidebar.BuildState
import com.tom.rv2ide.fragments.sidebar.ProblemFileGroup
import com.tom.rv2ide.fragments.sidebar.ProblemGrouping
import com.tom.rv2ide.fragments.sidebar.ProblemSorting
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ProjectProblemRegistry private constructor() {

    private val mainHandler: Handler? by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Throwable) {
            null
        }
    }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val lspDiagnostics = ConcurrentHashMap<File, List<DiagnosticItem>>()
    private val buildDiagnostics = CopyOnWriteArrayList<BuildDiagnostic>()

    @Volatile
    private var hasBuildRun: Boolean = false

    @Volatile
    private var activeProjectRoot: File? = null

    @Volatile
    private var activeEditorFile: File? = null

    @Volatile
    private var currentBuildState: BuildState = BuildState.IDLE

    @Volatile
    private var lastBuildSummary: String? = null

    companion object {
        @Volatile
        private var instance: ProjectProblemRegistry? = null

        @JvmStatic
        fun getInstance(): ProjectProblemRegistry {
            return instance ?: synchronized(this) {
                instance ?: ProjectProblemRegistry().also { instance = it }
            }
        }
    }

    fun setProjectRoot(root: File?) {
        if (activeProjectRoot != null && root != null && activeProjectRoot?.absolutePath != root.absolutePath) {
            // Project switched -> clear previous diagnostics
            onProjectSwitched(root)
        } else {
            activeProjectRoot = root
        }
    }

    fun getProjectRoot(): File? = activeProjectRoot

    fun setActiveEditorFile(file: File?) {
        activeEditorFile = file
        notifyListeners()
    }

    fun getActiveEditorFile(): File? = activeEditorFile

    fun getBuildState(): BuildState = currentBuildState

    fun getLastBuildSummary(): String? = lastBuildSummary

    fun hasProjectBeenBuilt(): Boolean = hasBuildRun

    fun onBuildStarted() {
        hasBuildRun = true
        currentBuildState = BuildState.BUILDING
        lastBuildSummary = "Build in progress…"
        buildDiagnostics.clear()
        notifyListeners()
    }

    fun setBuildState(state: BuildState, summary: String? = null) {
        currentBuildState = state
        if (summary != null) {
            lastBuildSummary = summary
        }
        notifyListeners()
    }

    fun updateLspDiagnostics(file: File, items: List<DiagnosticItem>) {
        if (items.isEmpty()) {
            lspDiagnostics.remove(file)
        } else {
            lspDiagnostics[file] = items
        }
        notifyListeners()
    }

    fun clearLspDiagnostics(file: File) {
        lspDiagnostics.remove(file)
        notifyListeners()
    }

    fun clearAllLspDiagnostics() {
        lspDiagnostics.clear()
        notifyListeners()
    }

    fun onFileDeleted(file: File) {
        lspDiagnostics.remove(file)
        val toRemove = buildDiagnostics.filter { diag ->
            val resolved = resolveFile(diag.filePath, activeProjectRoot)
            resolved.absolutePath == file.absolutePath
        }
        buildDiagnostics.removeAll(toRemove.toSet())
        notifyListeners()
    }

    fun onFileRenamed(oldFile: File, newFile: File) {
        val existing = lspDiagnostics.remove(oldFile)
        if (existing != null) {
            lspDiagnostics[newFile] = existing
        }
        notifyListeners()
    }

    fun onProjectSwitched(newProjectRoot: File) {
        activeProjectRoot = newProjectRoot
        activeEditorFile = null
        hasBuildRun = false
        currentBuildState = BuildState.IDLE
        lastBuildSummary = null
        lspDiagnostics.clear()
        buildDiagnostics.clear()
        notifyListeners()
    }

    fun recordBuildDiagnostics(diagnostics: List<BuildDiagnostic>, projectRoot: File? = null) {
        hasBuildRun = true
        if (projectRoot != null) {
            activeProjectRoot = projectRoot
        }
        val hasErrors = diagnostics.any { it.severity == BuildDiagnostic.Severity.ERROR }
        currentBuildState = if (hasErrors) BuildState.BUILD_FAILED else BuildState.BUILD_SUCCEEDED
        lastBuildSummary = if (hasErrors) {
            val errCount = diagnostics.count { it.severity == BuildDiagnostic.Severity.ERROR }
            "Build failed ($errCount error${if (errCount > 1) "s" else ""})"
        } else {
            "Build succeeded"
        }
        buildDiagnostics.clear()
        buildDiagnostics.addAll(diagnostics)
        notifyListeners()
    }

    fun recordBuildOutput(rawOutput: String, projectRoot: File? = null, stage: String = "BUILD") {
        hasBuildRun = true
        if (projectRoot != null) {
            activeProjectRoot = projectRoot
        }
        val parsed = StructuredDiagnosticCollector.parseBuildOutput(
            rawOutput = rawOutput,
            projectRoot = projectRoot ?: activeProjectRoot,
            defaultStage = stage
        )
        recordBuildDiagnostics(parsed, projectRoot)
    }

    fun clearBuildDiagnostics() {
        buildDiagnostics.clear()
        currentBuildState = BuildState.IDLE
        lastBuildSummary = null
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val handler = mainHandler
        if (handler != null) {
            handler.post {
                for (listener in listeners) {
                    try {
                        listener()
                    } catch (_: Throwable) {}
                }
            }
        } else {
            for (listener in listeners) {
                try {
                    listener()
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Backward-compatible default accessor returning file groups.
     */
    fun getAllProblemFileGroups(): List<ProblemFileGroup> {
        return getProblemGroups(ProblemGrouping.BY_FILE, ProblemSorting.BY_SEVERITY)
    }

    /**
     * Returns grouped problem items based on the selected grouping strategy and sorting preference.
     */
    fun getProblemGroups(
        grouping: ProblemGrouping = ProblemGrouping.BY_FILE,
        sorting: ProblemSorting = ProblemSorting.BY_SEVERITY
    ): List<ProblemFileGroup> {
        val root = activeProjectRoot

        // 1. Collect all diagnostics paired with their target file
        val allItems = mutableListOf<Pair<File, DiagnosticItem>>()

        for ((file, items) in lspDiagnostics) {
            for (item in items) {
                allItems.add(file to item)
            }
        }

        for (diag in buildDiagnostics) {
            val targetFile = resolveFile(diag.filePath, root)
            val diagItem = convertBuildDiagnosticToItem(diag)

            // Deduplicate: check if identical item exists at same file and line
            val alreadyReported = allItems.any { (f, existing) ->
                f.absolutePath == targetFile.absolutePath &&
                    existing.range.start.line == diagItem.range.start.line &&
                    (existing.message.equals(diagItem.message, ignoreCase = true) ||
                        existing.severity == diagItem.severity &&
                        (existing.message.contains(diagItem.message.take(20), ignoreCase = true) ||
                            diagItem.message.contains(existing.message.take(20), ignoreCase = true)))
            }

            if (!alreadyReported) {
                allItems.add(targetFile to diagItem)
            }
        }

        // 2. Group according to chosen grouping strategy
        val groupList: List<ProblemFileGroup> = when (grouping) {
            ProblemGrouping.BY_FILE -> {
                val byFile = LinkedHashMap<File, MutableList<DiagnosticItem>>()
                for ((file, item) in allItems) {
                    byFile.getOrPut(file) { mutableListOf() }.add(item)
                }

                byFile.map { (file, list) ->
                    sortDiagnosticsList(list, sorting)
                    val relPath = if (root != null && file.absolutePath.startsWith(root.absolutePath)) {
                        file.parentFile?.relativeToOrNull(root)?.path ?: file.parent ?: ""
                    } else {
                        file.parent ?: ""
                    }

                    ProblemFileGroup(
                        file = file,
                        fileName = file.name,
                        filePath = relPath,
                        isExpanded = true,
                        diagnostics = list,
                        groupKey = file.absolutePath
                    )
                }
            }

            ProblemGrouping.BY_SEVERITY -> {
                val errorList = mutableListOf<DiagnosticItem>()
                val warningList = mutableListOf<DiagnosticItem>()
                val infoList = mutableListOf<DiagnosticItem>()

                val dummyFile = File("[Project Scope]")
                for ((_, item) in allItems) {
                    when (item.severity) {
                        DiagnosticSeverity.ERROR -> errorList.add(item)
                        DiagnosticSeverity.WARNING -> warningList.add(item)
                        DiagnosticSeverity.INFO, DiagnosticSeverity.HINT -> infoList.add(item)
                    }
                }

                val result = mutableListOf<ProblemFileGroup>()
                if (errorList.isNotEmpty()) {
                    sortDiagnosticsList(errorList, sorting)
                    result.add(
                        ProblemFileGroup(
                            file = dummyFile,
                            fileName = "Errors (${errorList.size})",
                            filePath = "Project Errors",
                            isExpanded = true,
                            diagnostics = errorList,
                            groupKey = "SEVERITY_ERRORS"
                        )
                    )
                }
                if (warningList.isNotEmpty()) {
                    sortDiagnosticsList(warningList, sorting)
                    result.add(
                        ProblemFileGroup(
                            file = dummyFile,
                            fileName = "Warnings (${warningList.size})",
                            filePath = "Project Warnings",
                            isExpanded = true,
                            diagnostics = warningList,
                            groupKey = "SEVERITY_WARNINGS"
                        )
                    )
                }
                if (infoList.isNotEmpty()) {
                    sortDiagnosticsList(infoList, sorting)
                    result.add(
                        ProblemFileGroup(
                            file = dummyFile,
                            fileName = "Information & Hints (${infoList.size})",
                            filePath = "Informational messages",
                            isExpanded = false,
                            diagnostics = infoList,
                            groupKey = "SEVERITY_INFOS"
                        )
                    )
                }
                result
            }

            ProblemGrouping.BY_SOURCE -> {
                val bySource = LinkedHashMap<String, MutableList<DiagnosticItem>>()
                for ((_, item) in allItems) {
                    val src = item.source.ifBlank { "LSP" }
                    bySource.getOrPut(src) { mutableListOf() }.add(item)
                }

                bySource.map { (sourceName, list) ->
                    sortDiagnosticsList(list, sorting)
                    ProblemFileGroup(
                        file = File("[Source: $sourceName]"),
                        fileName = sourceName,
                        filePath = "Diagnostics from $sourceName",
                        isExpanded = true,
                        diagnostics = list,
                        groupKey = "SOURCE_$sourceName"
                    )
                }
            }
        }

        // 3. Sort outer groups
        return when (sorting) {
            ProblemSorting.BY_SEVERITY -> {
                groupList.sortedWith(
                    compareByDescending<ProblemFileGroup> { it.errorCount }
                        .thenByDescending { it.warningCount }
                        .thenBy { it.fileName }
                )
            }
            ProblemSorting.BY_FILE_NAME -> {
                groupList.sortedBy { it.fileName.lowercase() }
            }
            ProblemSorting.BY_LOCATION -> {
                groupList.sortedBy { it.filePath.lowercase() }
            }
            ProblemSorting.BY_MESSAGE -> {
                groupList.sortedBy { it.diagnostics.firstOrNull()?.message.orEmpty().lowercase() }
            }
        }
    }

    private fun sortDiagnosticsList(list: MutableList<DiagnosticItem>, sorting: ProblemSorting) {
        when (sorting) {
            ProblemSorting.BY_SEVERITY -> {
                list.sortWith(
                    compareBy<DiagnosticItem> { it.severity != DiagnosticSeverity.ERROR }
                        .thenBy { it.severity != DiagnosticSeverity.WARNING }
                        .thenBy { it.range.start.line }
                        .thenBy { it.range.start.column }
                )
            }
            ProblemSorting.BY_LOCATION -> {
                list.sortWith(
                    compareBy<DiagnosticItem> { it.range.start.line }
                        .thenBy { it.range.start.column }
                )
            }
            ProblemSorting.BY_MESSAGE -> {
                list.sortBy { it.message.lowercase() }
            }
            ProblemSorting.BY_FILE_NAME -> {
                list.sortWith(
                    compareBy<DiagnosticItem> { it.severity != DiagnosticSeverity.ERROR }
                        .thenBy { it.range.start.line }
                )
            }
        }
    }

    private fun resolveFile(filePath: String?, root: File?): File {
        if (filePath.isNullOrBlank()) {
            return root?.let { File(it, "[Project Build]") } ?: File("[Project Build]")
        }
        val f = File(filePath)
        if (f.isAbsolute && f.exists()) {
            return f
        }
        if (root != null) {
            val relative = File(root, filePath)
            if (relative.exists()) return relative
        }
        return f
    }

    private fun convertBuildDiagnosticToItem(diag: BuildDiagnostic): DiagnosticItem {
        val startLine = (diag.line ?: 1) - 1
        val startCol = (diag.column ?: 1) - 1
        val range = Range(
            Position(startLine.coerceAtLeast(0), startCol.coerceAtLeast(0)),
            Position(startLine.coerceAtLeast(0), (startCol + 1).coerceAtLeast(1))
        )

        val severity = when (diag.severity) {
            BuildDiagnostic.Severity.ERROR -> DiagnosticSeverity.ERROR
            BuildDiagnostic.Severity.WARNING -> DiagnosticSeverity.WARNING
            BuildDiagnostic.Severity.INFO -> DiagnosticSeverity.INFO
        }

        val source = diag.stage.ifBlank { "BUILD" }
        return DiagnosticItem(
            message = diag.message,
            code = diag.stage,
            range = range,
            source = source,
            severity = severity
        )
    }

    /**
     * Builds a structured, focused context prompt for autonomous AI repair.
     */
    fun buildAiFixPrompt(diagnostic: DiagnosticItem, file: File): String {
        val line = (diagnostic.range.start.line + 1).coerceAtLeast(1)
        val col = (diagnostic.range.start.column + 1).coerceAtLeast(1)
        val sev = diagnostic.severity.name
        val src = diagnostic.source.ifBlank { "COMPILER" }
        val filePath = file.path

        val snippet = if (file.exists() && file.isFile) {
            try {
                val lines = file.readLines()
                val start = maxOf(1, line - 4)
                val end = minOf(lines.size, line + 4)
                buildString {
                    appendLine("```${file.extension}")
                    for (i in start..end) {
                        val mark = if (i == line) ">" else " "
                        appendLine("%s %4d | %s".format(mark, i, lines[i - 1]))
                    }
                    appendLine("```")
                }
            } catch (_: Throwable) {
                ""
            }
        } else ""

        return buildString {
            appendLine("Please fix the following $sev in the project:")
            appendLine("- **File**: `$filePath`")
            appendLine("- **Location**: Line $line, Column $col")
            appendLine("- **Severity**: $sev")
            appendLine("- **Source**: $src")
            appendLine("- **Message**: ${diagnostic.message}")
            if (snippet.isNotBlank()) {
                appendLine()
                appendLine("Source Context:")
                appendLine(snippet)
            }
            appendLine("Please inspect the code, modify the file safely to resolve this error, and verify that the fix compiles properly.")
        }
    }

    /**
     * Formats all real-time project problems and build diagnostics into a structured text report
     * so autonomous AI agents have full direct visibility into active project errors and warnings.
     */
    fun formatDiagnosticsForAgent(): String {
        val groups = getAllProblemFileGroups()
        if (groups.isEmpty()) {
            return if (hasBuildRun) {
                "No errors or warnings currently detected in the active project."
            } else {
                "No project diagnostics available yet (build has not been executed and no active LSP diagnostics)."
            }
        }

        val totalErrors = groups.sumOf { it.errorCount }
        val totalWarnings = groups.sumOf { it.warningCount }

        return buildString {
            appendLine("Project Problems & Diagnostics Report:")
            appendLine("Found $totalErrors error(s) and $totalWarnings warning(s) across ${groups.size} file(s):")
            appendLine()
            for (group in groups) {
                val relPath = if (group.file.name == "[Project Build]") "[Project Build]" else group.file.path
                appendLine("File: $relPath (${group.errorCount} error(s), ${group.warningCount} warning(s)):")
                for (item in group.diagnostics) {
                    val sevStr = when (item.severity) {
                        DiagnosticSeverity.ERROR -> "ERROR"
                        DiagnosticSeverity.WARNING -> "WARNING"
                        DiagnosticSeverity.INFO -> "INFO"
                        DiagnosticSeverity.HINT -> "HINT"
                    }
                    val loc = if (item.range.start.line > 0 || item.range.start.column > 0) {
                        "[Line ${item.range.start.line + 1}, Col ${item.range.start.column + 1}] "
                    } else ""
                    val source = if (!item.source.isNullOrBlank()) "[${item.source}] " else ""
                    appendLine("  • $loc$source$sevStr: ${item.message}")
                }
                appendLine()
            }
        }
    }
}
