package com.tom.rv2ide.artificial.coding

import com.tom.rv2ide.artificial.parser.SnippetParser
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Result of attempting to resolve an import for an unresolved symbol.
 */
data class ImportResolutionResult(
    val symbolName: String,
    val status: ResolutionStatus,
    val importStatement: String? = null,
    val headerInclude: String? = null,
    val resolvedQualifiedName: String? = null,
    val candidateDeclarations: List<String> = emptyList(),
    val message: String
)

enum class ResolutionStatus {
    RESOLVED,
    ALREADY_IMPORTED,
    SAME_PACKAGE,
    AMBIGUOUS,
    NOT_FOUND
}

/**
 * Coordinates dependency-ordered multi-file patch applications and
 * conservative, automated import/include resolution using AcsRepoMap.
 */
object DependencyAwarePatchCoordinator {

    private val log = LoggerFactory.getLogger(DependencyAwarePatchCoordinator::class.java)

    private val WELL_KNOWN_SDK_IMPORTS = mapOf(
        "Activity" to "android.app.Activity",
        "Context" to "android.content.Context",
        "Intent" to "android.content.Intent",
        "Bundle" to "android.os.Bundle",
        "View" to "android.view.View",
        "ViewGroup" to "android.view.ViewGroup",
        "Toast" to "android.widget.Toast",
        "TextView" to "android.widget.TextView",
        "Button" to "android.widget.Button",
        "ImageView" to "android.widget.ImageView",
        "LinearLayout" to "android.widget.LinearLayout",
        "FrameLayout" to "android.widget.FrameLayout",
        "RelativeLayout" to "android.widget.RelativeLayout",
        "File" to "java.io.File",
        "IOException" to "java.io.IOException",
        "InputStream" to "java.io.InputStream",
        "OutputStream" to "java.io.OutputStream",
        "List" to "java.util.List",
        "ArrayList" to "java.util.ArrayList",
        "Map" to "java.util.Map",
        "HashMap" to "java.util.HashMap",
        "Set" to "java.util.Set",
        "HashSet" to "java.util.HashSet"
    )

    private val WELL_KNOWN_CPP_INCLUDES = mapOf(
        "string" to "<string>",
        "vector" to "<vector>",
        "map" to "<map>",
        "unordered_map" to "<unordered_map>",
        "memory" to "<memory>",
        "iostream" to "<iostream>",
        "jni.h" to "<jni.h>",
        "android/log.h" to "<android/log.h>",
        "__android_log_print" to "<android/log.h>"
    )

    /**
     * Topologically sorts proposed edits so that dependency declarations
     * (headers, base classes, interfaces, models) are processed before callers/implementations.
     */
    fun sortEditsByDependency(
        projectRoot: File,
        edits: List<CoordinatedFileEdit>
    ): List<CoordinatedFileEdit> {
        if (edits.size <= 1) return edits

        // Assign base tier by file type
        fun getTier(edit: CoordinatedFileEdit): Int {
            val path = edit.relativePath.lowercase().replace('\\', '/')
            val ext = File(path).extension.lowercase()
            return when {
                ext in setOf("h", "hpp", "hh", "hxx") -> 10 // C/C++ headers first
                path.endsWith("androidmanifest.xml") -> 15 // Manifest before code referencing components
                path.endsWith(".mk") || path.endsWith(".gradle") || path.endsWith(".kts") -> 20 // Build configs
                // Interfaces, base definitions
                edit.finalContent.contains("interface ") -> 30
                edit.finalContent.contains("abstract class ") -> 35
                ext in setOf("java", "kt") && (edit.finalContent.contains("enum class ") || edit.finalContent.contains("data class ")) -> 40
                ext in setOf("cpp", "cc", "c", "cxx") -> 60 // C/C++ implementations
                ext in setOf("java", "kt") -> 70 // Java/Kotlin implementations
                ext in setOf("xml") -> 80 // Layouts / resources
                else -> 90
            }
        }

        // Check explicit inter-file dependencies
        // If file B includes or imports file A, A has an edge to B (A must come before B)
        val editMap = edits.associateBy { it.relativePath.replace('\\', '/') }
        val dependencies = mutableMapOf<String, MutableSet<String>>()
        for (edit in edits) {
            val normPath = edit.relativePath.replace('\\', '/')
            dependencies[normPath] = mutableSetOf()
            val content = edit.finalContent

            for (other in edits) {
                if (other === edit) continue
                val otherNorm = other.relativePath.replace('\\', '/')
                val otherName = File(otherNorm).name
                val otherNameWithoutExt = File(otherNorm).nameWithoutExtension

                // C/C++ include check
                if (content.contains("#include \"$otherName\"") ||
                    content.contains("#include <$otherName>") ||
                    content.contains("#include \"$otherNorm\"")
                ) {
                    dependencies[normPath]?.add(otherNorm)
                    dependencies[normPath]?.add(otherName)
                }

                // Java/Kotlin import or reference check
                val isJavaOrKt = otherNorm.endsWith(".java") || otherNorm.endsWith(".kt")
                if (isJavaOrKt && otherNameWithoutExt.length >= 2) {
                    val wordRegex = Regex("""\b${Regex.escape(otherNameWithoutExt)}\b""")
                    if (wordRegex.containsMatchIn(content)) {
                        dependencies[normPath]?.add(otherNorm)
                        dependencies[normPath]?.add(otherName)
                    }
                }
            }
        }

        // Stable sort based on Tier + Dependency graph
        return edits.sortedWith { a, b ->
            val pathA = a.relativePath.replace('\\', '/')
            val pathB = b.relativePath.replace('\\', '/')
            val nameA = File(pathA).name
            val nameB = File(pathB).name

            val bDependsOnA = dependencies[pathB]?.contains(pathA) == true || dependencies[pathB]?.contains(nameA) == true
            val aDependsOnB = dependencies[pathA]?.contains(pathB) == true || dependencies[pathA]?.contains(nameB) == true

            when {
                bDependsOnA && !aDependsOnB -> -1 // A must come before B
                aDependsOnB && !bDependsOnA -> 1  // B must come before A
                else -> getTier(a).compareTo(getTier(b))
            }
        }
    }

    /**
     * Resolves an import statement or C/C++ #include for an unresolved symbol using the project RepoMap.
     */
    fun resolveImportForSymbol(
        projectRoot: File,
        targetFile: File,
        symbolName: String
    ): ImportResolutionResult {
        val cleanSymbol = symbolName.trim().removeSurrounding("'", "'").removeSurrounding("`", "`").removeSurrounding("\"", "\"")
        if (cleanSymbol.isBlank() || !cleanSymbol.all { it.isJavaIdentifierPart() || it == '.' }) {
            return ImportResolutionResult(
                symbolName = cleanSymbol,
                status = ResolutionStatus.NOT_FOUND,
                message = "Invalid symbol identifier: '$cleanSymbol'"
            )
        }

        val ext = targetFile.extension.lowercase()
        val isCpp = ext in setOf("cpp", "cc", "c", "cxx", "h", "hpp")
        val isJava = ext == "java"
        val isKotlin = ext == "kt"

        val targetContent = if (targetFile.exists()) targetFile.readText() else ""
        val targetLines = targetContent.lines()

        // 1. Check if already imported
        if (isJava || isKotlin) {
            val importRegex = Regex("""import\s+(?:static\s+)?([A-Za-z0-9_.]+\.$cleanSymbol);?""")
            if (importRegex.containsMatchIn(targetContent)) {
                return ImportResolutionResult(
                    symbolName = cleanSymbol,
                    status = ResolutionStatus.ALREADY_IMPORTED,
                    message = "Symbol '$cleanSymbol' is already imported in ${targetFile.name}."
                )
            }

            // Check if in same package
            val packageRegex = Regex("""package\s+([A-Za-z0-9_.]+);?""")
            val currentPackage = packageRegex.find(targetContent)?.groupValues?.get(1)

            // Search symbol definitions in AcsRepoMap
            val defs = AcsRepoMap.findSymbolDefinitions(projectRoot, cleanSymbol, exactMatch = true)
            val candidateTypes = defs.filter { it.second.kind in setOf(SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.ENUM, SymbolKind.STRUCT) }

            // Map candidates to qualified names or fall back to well-known standard SDK classes
            val qualifiedCandidates = if (candidateTypes.isNotEmpty()) {
                candidateTypes.mapNotNull { it.second.qualifiedName }.distinct()
            } else {
                val sdkMatch = WELL_KNOWN_SDK_IMPORTS[cleanSymbol]
                if (sdkMatch != null) listOf(sdkMatch) else emptyList()
            }

            if (qualifiedCandidates.isEmpty()) {
                return ImportResolutionResult(
                    symbolName = cleanSymbol,
                    status = ResolutionStatus.NOT_FOUND,
                    message = "No type definition for '$cleanSymbol' found in the project RepoMap or standard libraries."
                )
            }

            // If a candidate is in the same package, no import is needed
            if (currentPackage != null) {
                val samePackageMatch = qualifiedCandidates.firstOrNull {
                    it.substringBeforeLast('.', "") == currentPackage
                }
                if (samePackageMatch != null) {
                    return ImportResolutionResult(
                        symbolName = cleanSymbol,
                        status = ResolutionStatus.SAME_PACKAGE,
                        resolvedQualifiedName = samePackageMatch,
                        message = "Symbol '$cleanSymbol' resides in the same package ($currentPackage). No import required."
                    )
                }
            }

            if (qualifiedCandidates.size == 1) {
                val qName = qualifiedCandidates.first()
                val importStmt = if (isKotlin) "import $qName" else "import $qName;"
                return ImportResolutionResult(
                    symbolName = cleanSymbol,
                    status = ResolutionStatus.RESOLVED,
                    importStatement = importStmt,
                    resolvedQualifiedName = qName,
                    message = "Resolved unambiguous import '$importStmt' for '$cleanSymbol'."
                )
            } else {
                return ImportResolutionResult(
                    symbolName = cleanSymbol,
                    status = ResolutionStatus.AMBIGUOUS,
                    candidateDeclarations = qualifiedCandidates,
                    message = "Ambiguous symbol '$cleanSymbol'. Multiple candidate packages found: ${qualifiedCandidates.joinToString(", ")}"
                )
            }
        } else if (isCpp) {
            // C/C++ #include resolution
            val defs = AcsRepoMap.findSymbolDefinitions(projectRoot, cleanSymbol, exactMatch = true)
            val headerDefs = defs.filter {
                val p = it.first.lowercase()
                p.endsWith(".h") || p.endsWith(".hpp") || p.endsWith(".hh")
            }

            if (headerDefs.isEmpty()) {
                return ImportResolutionResult(
                    symbolName = cleanSymbol,
                    status = ResolutionStatus.NOT_FOUND,
                    message = "No header declaring '$cleanSymbol' found in project."
                )
            }

            val targetRelPath = try {
                targetFile.relativeTo(projectRoot).path.replace('\\', '/')
            } catch (_: Exception) {
                targetFile.name
            }

            val distinctHeaders = headerDefs.map { it.first.replace('\\', '/') }.distinct()
            val headerToInclude = distinctHeaders.first()
            val includeStmt = "#include \"$headerToInclude\""

            if (targetContent.contains("#include \"$headerToInclude\"") || targetContent.contains("#include \"${File(headerToInclude).name}\"")) {
                return ImportResolutionResult(
                    symbolName = cleanSymbol,
                    status = ResolutionStatus.ALREADY_IMPORTED,
                    headerInclude = includeStmt,
                    message = "Header for '$cleanSymbol' is already included."
                )
            }

            if (distinctHeaders.size == 1) {
                return ImportResolutionResult(
                    symbolName = cleanSymbol,
                    status = ResolutionStatus.RESOLVED,
                    headerInclude = includeStmt,
                    message = "Resolved include '$includeStmt' for symbol '$cleanSymbol'."
                )
            } else {
                return ImportResolutionResult(
                    symbolName = cleanSymbol,
                    status = ResolutionStatus.AMBIGUOUS,
                    candidateDeclarations = distinctHeaders,
                    message = "Multiple headers define '$cleanSymbol': ${distinctHeaders.joinToString(", ")}"
                )
            }
        }

        return ImportResolutionResult(
            symbolName = cleanSymbol,
            status = ResolutionStatus.NOT_FOUND,
            message = "Unsupported file type for import resolution: ${targetFile.name}"
        )
    }

    /**
     * Safely inserts an import or include statement into source code preserving
     * license headers, package declarations, and existing comments.
     */
    fun insertImportSafely(
        originalContent: String,
        importStatement: String,
        isCpp: Boolean = false
    ): String {
        val trimmedStmt = importStatement.trim()
        if (originalContent.contains(trimmedStmt)) {
            return originalContent // Avoid duplicate
        }

        val lines = originalContent.lines().toMutableList()

        if (isCpp) {
            // Find last #include line
            var lastIncludeIdx = -1
            for (i in lines.indices) {
                val l = lines[i].trim()
                if (l.startsWith("#include")) {
                    lastIncludeIdx = i
                }
            }

            if (lastIncludeIdx >= 0) {
                lines.add(lastIncludeIdx + 1, trimmedStmt)
            } else {
                // Insert after comments/pragmas or at top
                var insertIdx = 0
                while (insertIdx < lines.size && (lines[insertIdx].trim().startsWith("//") || lines[insertIdx].trim().startsWith("/*") || lines[insertIdx].trim().startsWith("*") || lines[insertIdx].trim().isEmpty() || lines[insertIdx].trim().startsWith("#pragma"))) {
                    insertIdx++
                }
                lines.add(insertIdx, trimmedStmt)
            }
            return lines.joinToString("\n")
        }

        // Java / Kotlin
        val packageIdx = lines.indexOfFirst { it.trim().startsWith("package ") }
        val lastImportIdx = lines.indexOfLast { it.trim().startsWith("import ") }

        if (lastImportIdx >= 0) {
            lines.add(lastImportIdx + 1, trimmedStmt)
        } else if (packageIdx >= 0) {
            // Insert after package with a blank line
            lines.add(packageIdx + 1, "")
            lines.add(packageIdx + 2, trimmedStmt)
        } else {
            // Insert at top after any initial comments
            var insertIdx = 0
            while (insertIdx < lines.size && (lines[insertIdx].trim().startsWith("//") || lines[insertIdx].trim().startsWith("/*") || lines[insertIdx].trim().startsWith("*") || lines[insertIdx].trim().isEmpty())) {
                insertIdx++
            }
            lines.add(insertIdx, trimmedStmt)
        }

        return lines.joinToString("\n")
    }

    /**
     * Topologically sorts a list of files based on their content dependencies.
     */
    fun sortEditsByDependency(
        files: List<File>,
        fileContents: Map<File, String>
    ): List<File> {
        val coordinated = files.map { file ->
            CoordinatedFileEdit(
                relativePath = file.name,
                targetFile = file,
                isNewFile = false,
                finalContent = fileContents[file] ?: (if (file.exists()) file.readText() else ""),
                originalContent = null
            )
        }
        val sorted = sortEditsByDependency(File("."), coordinated)
        return sorted.map { it.targetFile }
    }

    data class AutoRepairResult(
        val success: Boolean,
        val appliedImports: List<String>,
        val updatedContent: String?
    )

    /**
     * Attempts to auto-repair missing imports for a single source file given compiler diagnostics.
     */
    fun autoRepairMissingImportsForFile(
        sourceFile: File,
        diagnostics: List<BuildDiagnostic>,
        explicitProjectRoot: File? = null
    ): AutoRepairResult {
        if (!sourceFile.exists() || !sourceFile.isFile) {
            return AutoRepairResult(false, emptyList(), null)
        }
        val projectRoot = explicitProjectRoot ?: findProjectRoot(sourceFile)
        var currentText = sourceFile.readText()
        val isCpp = sourceFile.extension.lowercase() in setOf("cpp", "c", "cc", "h", "hpp")
        val applied = mutableListOf<String>()

        for (diag in diagnostics) {
            val symbol = extractMissingSymbolFromDiagnostic(diag.message) ?: continue
            val res = resolveImportForSymbol(projectRoot, sourceFile, symbol)
            if (res.status == ResolutionStatus.RESOLVED) {
                val statement = if (isCpp) res.headerInclude else res.importStatement
                if (statement != null) {
                    val updated = insertImportSafely(currentText, statement, isCpp)
                    if (updated != currentText) {
                        currentText = updated
                        applied.add(statement)
                    }
                }
            }
        }

        if (applied.isNotEmpty()) {
            sourceFile.writeText(currentText)
            return AutoRepairResult(true, applied, currentText)
        }
        return AutoRepairResult(false, emptyList(), currentText)
    }

    /**
     * Resolves missing compiler diagnostic symbols and injects corresponding imports
     * across affected source files in the project.
     */
    fun autoRepairMissingImports(
        projectRoot: File,
        diagnostics: List<BuildDiagnostic>
    ): List<Pair<File, String>> {
        val repairs = mutableListOf<Pair<File, String>>()

        for (diag in diagnostics) {
            val path = diag.filePath ?: continue
            val file = if (File(path).isAbsolute) File(path) else File(projectRoot, path)
            if (!file.exists() || !file.isFile) continue

            // Extract unresolved symbol name from compiler diagnostic
            val symbol = extractMissingSymbolFromDiagnostic(diag.message) ?: continue
            val resolution = resolveImportForSymbol(projectRoot, file, symbol)

            if (resolution.status == ResolutionStatus.RESOLVED) {
                val currentText = file.readText()
                val isCpp = file.extension.lowercase() in setOf("cpp", "c", "cc", "h", "hpp")
                val statement = if (isCpp) resolution.headerInclude else resolution.importStatement
                if (statement != null) {
                    val updated = insertImportSafely(currentText, statement, isCpp)
                    if (updated != currentText) {
                        repairs.add(file to updated)
                        log.info("Auto-repaired import '{}' for symbol '{}' in {}", statement, symbol, file.name)
                    }
                }
            }
        }

        return repairs
    }

    private fun extractMissingSymbolFromDiagnostic(message: String): String? {
        val patterns = listOf(
            Regex("""cannot find symbol\s*:\s*(?:class|variable|symbol)\s+([A-Za-z0-9_]+)"""),
            Regex("""unresolved reference\s*:\s*([A-Za-z0-9_]+)"""),
            Regex("""use of undeclared identifier\s+'([A-Za-z0-9_]+)'"""),
            Regex("""'([A-Za-z0-9_]+)'\s+was not declared in this scope"""),
            Regex("""no member named\s+'([A-Za-z0-9_]+)'"""),
            Regex("""unknown type name\s+'([A-Za-z0-9_]+)'""")
        )

        for (regex in patterns) {
            val match = regex.find(message)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    fun findProjectRoot(file: File): File {
        var curr: File? = file.parentFile
        while (curr != null) {
            if (File(curr, "AndroidManifest.xml").exists() ||
                File(curr, "src/main").exists() ||
                File(curr, "build.gradle").exists() ||
                File(curr, "build.gradle.kts").exists() ||
                File(curr, ".git").exists() ||
                File(curr, "settings.gradle").exists()
            ) {
                return curr
            }
            curr = curr.parentFile
        }
        return file.parentFile ?: File(".")
    }
}
