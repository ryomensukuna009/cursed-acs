/*
 *  This file is part of AndroidCodeStudio (ROXUM-ACS).
 *  Data models for AIDE-style Problems Panel.
 */

package com.tom.rv2ide.fragments.sidebar

import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import java.io.File

enum class ProblemFilter {
    ALL,
    ERRORS_ONLY,
    WARNINGS_ONLY,
    INFO_HINTS,
    CURRENT_FILE,
    BUILD_ONLY
}

enum class ProblemGrouping {
    BY_FILE,
    BY_SEVERITY,
    BY_SOURCE
}

enum class ProblemSorting {
    BY_SEVERITY,
    BY_FILE_NAME,
    BY_LOCATION,
    BY_MESSAGE
}

enum class BuildState(val displayName: String) {
    IDLE("Ready"),
    ANALYZING("Analyzing…"),
    BUILDING("Building…"),
    UP_TO_DATE("Up to date"),
    BUILD_FAILED("Build Failed"),
    BUILD_SUCCEEDED("Build Succeeded")
}

data class ProblemFileGroup(
    val file: File,
    val fileName: String = file.name,
    val filePath: String = file.parent ?: "",
    var isExpanded: Boolean = true,
    val diagnostics: MutableList<DiagnosticItem> = mutableListOf(),
    val groupKey: String = file.absolutePath
) {
    val errorCount: Int
        get() = diagnostics.count { it.severity == DiagnosticSeverity.ERROR }

    val warningCount: Int
        get() = diagnostics.count { it.severity == DiagnosticSeverity.WARNING }

    val infoCount: Int
        get() = diagnostics.count { it.severity == DiagnosticSeverity.INFO || it.severity == DiagnosticSeverity.HINT }

    fun filteredDiagnostics(filter: ProblemFilter): List<DiagnosticItem> {
        return filteredDiagnostics(filter, null, "")
    }

    fun filteredDiagnostics(
        filter: ProblemFilter = ProblemFilter.ALL,
        activeFile: File? = null,
        searchQuery: String = ""
    ): List<DiagnosticItem> {
        return diagnostics.filter { diag ->
            val matchesSearch = searchQuery.isBlank() ||
                diag.message.contains(searchQuery, ignoreCase = true) ||
                fileName.contains(searchQuery, ignoreCase = true) ||
                filePath.contains(searchQuery, ignoreCase = true) ||
                diag.source.contains(searchQuery, ignoreCase = true)

            if (!matchesSearch) return@filter false

            when (filter) {
                ProblemFilter.ALL -> true
                ProblemFilter.ERRORS_ONLY -> diag.severity == DiagnosticSeverity.ERROR
                ProblemFilter.WARNINGS_ONLY -> diag.severity == DiagnosticSeverity.WARNING
                ProblemFilter.INFO_HINTS -> diag.severity == DiagnosticSeverity.INFO || diag.severity == DiagnosticSeverity.HINT
                ProblemFilter.CURRENT_FILE -> activeFile != null && (file.absolutePath == activeFile.absolutePath || file.name == activeFile.name)
                ProblemFilter.BUILD_ONLY -> file.name == "[Project Build]" || diag.source != "LSP"
            }
        }
    }
}

