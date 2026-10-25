package com.tom.rv2ide.artificial.coding

import com.tom.rv2ide.preferences.internal.BuildPreferences
import java.io.File

/**
 * Inspects project architecture, build mode (Standard Gradle vs Offline NDK Builder),
 * cross-file symbol usages, imports, XML resource references, and JNI declarations.
 */
object ProjectDependencyInspector {

    data class ProjectInspectionResult(
        val projectRoot: File,
        val buildModeDescription: String,
        val hasNdkSources: Boolean,
        val manifestFiles: List<String>,
        val buildConfigFiles: List<String>,
        val inspectedFiles: List<String>,
        val contextPromptBlock: String,
        val repoMap: String = ""
    )

    private val IGNORED_DIRS = setOf(
        ".git", ".gradle", ".idea", "build", "captures", ".cxx", ".externalNativeBuild"
    )

    private val SOURCE_EXTENSIONS = setOf(
        "kt", "java", "xml", "c", "cpp", "cc", "h", "hpp", "mk", "gradle", "kts", "json", "properties"
    )

    fun inspectProjectForPrompt(
        projectRoot: File,
        userPrompt: String,
        maxBudgetChars: Int = 24_000
    ): ProjectInspectionResult {
        val isOfflineMode = try {
            BuildPreferences.isOfflineNdkBuild
        } catch (_: Throwable) {
            false
        }

        val allFiles = collectProjectSourceFiles(projectRoot)
        val relativeMap = allFiles.associateBy { it.relativeTo(projectRoot).path.replace('\\', '/') }

        val manifestFiles = relativeMap.keys.filter { it.endsWith("AndroidManifest.xml") }
        val buildConfigFiles = relativeMap.keys.filter {
            it.endsWith("build.gradle") ||
                it.endsWith("build.gradle.kts") ||
                it.endsWith("Android.mk") ||
                it.endsWith("Application.mk") ||
                it.endsWith("CMakeLists.txt")
        }
        val hasNdk = relativeMap.keys.any {
            it.endsWith(".cpp") || it.endsWith(".c") || it.endsWith("Android.mk") || it.contains("/jni/")
        }

        val buildModeDesc = if (isOfflineMode) {
            "Offline NDK Builder (Standalone AAPT2 + Javac + D8 + ndk-build without Gradle)"
        } else {
            "Standard Gradle Build"
        }

        // Rank files by relevance:
        // Priority order: active task -> declarations/scopes -> callers/callees/usages -> diagnostics -> fallback
        val promptLower = userPrompt.lowercase()
        val promptTokens = Regex("""[A-Za-z0-9_./-]{3,}""")
            .findAll(userPrompt)
            .map { it.value }
            .toSet()

        // 1. Identify symbol declarations and call-sites for prompt tokens
        val symbolDefFiles = mutableSetOf<String>()
        val symbolUsageFiles = mutableSetOf<String>()
        for (tok in promptTokens.take(10)) {
            val defs = AcsRepoMap.findSymbolDefinitions(projectRoot, tok, exactMatch = true)
            defs.forEach { symbolDefFiles.add(it.first) }
            val refs = AcsRepoMap.findSymbolReferences(projectRoot, tok, maxReferences = 8)
            refs.forEach { symbolUsageFiles.add(it.relativePath) }
        }

        val directlyMatched = mutableListOf<File>()
        val otherCandidates = mutableListOf<Pair<File, Int>>()

        for ((relPath, file) in relativeMap) {
            val fileNameLower = file.name.lowercase()
            val stemLower = file.nameWithoutExtension.lowercase()
            var score = 0

            // Priority 1: Directly matched in prompt
            if (promptLower.contains(fileNameLower) || (stemLower.length >= 4 && promptLower.contains(stemLower))) {
                score += 200
                directlyMatched.add(file)
            }
            // Priority 2: Symbol declarations / definitions
            if (relPath in symbolDefFiles) {
                score += 120
            }
            // Priority 3: Usages / Callers / Callees
            if (relPath in symbolUsageFiles) {
                score += 60
            }

            for (tok in promptTokens) {
                if (relPath.lowercase().contains(tok.lowercase())) {
                    score += 20
                }
            }
            if (relPath.endsWith("AndroidManifest.xml")) score += 25
            if (relPath.endsWith("Android.mk") || relPath.endsWith("build.gradle") || relPath.endsWith("build.gradle.kts")) {
                score += 20
            }
            if (relPath.contains("MainActivity") || relPath.contains("Main")) score += 15
            if (file.extension in setOf("kt", "java", "cpp", "c", "xml")) score += 5

            otherCandidates.add(file to score)
        }

        // Discover cross-file dependents of directly matched files
        val dependentFiles = findDependentFiles(projectRoot, directlyMatched, allFiles)
        val rankedFiles = LinkedHashSet<File>()
        rankedFiles.addAll(directlyMatched)
        rankedFiles.addAll(dependentFiles)
        otherCandidates.sortedByDescending { it.second }.forEach { rankedFiles.add(it.first) }

        val inspectedRelPaths = mutableListOf<String>()
        val sb = StringBuilder()
        sb.appendLine("=== PROJECT ARCHITECTURE & CONTEXT ===")
        sb.appendLine("Project Root: ${projectRoot.name}")
        sb.appendLine("Active Build Mode: $buildModeDesc")
        sb.appendLine("Native C/C++ (JNI) Present: $hasNdk")
        sb.appendLine("Total Source/Config Files: ${allFiles.size}")
        sb.appendLine("Project File Tree:")
        relativeMap.keys.sorted().take(80).forEach { rel ->
            sb.appendLine("  - $rel")
        }
        sb.appendLine()

        val repoMap = try {
            AcsRepoMap.rankAndFormatMap(
                projectRoot = projectRoot,
                userQuery = userPrompt,
                maxTokens = 1000
            )
        } catch (_: Exception) {
            ""
        }

        if (repoMap.isNotBlank()) {
            sb.appendLine(repoMap)
            sb.appendLine()
        }

        for (file in rankedFiles) {
            if (sb.length >= maxBudgetChars) break
            if (!file.exists() || !file.isFile || file.length() > 120_000) continue
            val relPath = file.relativeTo(projectRoot).path.replace('\\', '/')
            val text = try {
                file.readText()
            } catch (_: Exception) {
                continue
            }
            val remaining = maxBudgetChars - sb.length
            if (remaining < 400) break

            val snippet = if (text.length + 120 <= remaining) {
                text
            } else {
                text.take((remaining - 160).coerceAtLeast(200)) + "\n// ... [truncated for context budget]"
            }
            inspectedRelPaths.add(relPath)
            sb.appendLine("--- FILE: $relPath ---")
            sb.appendLine(snippet)
            sb.appendLine("--- END FILE: $relPath ---")
            sb.appendLine()
        }

        return ProjectInspectionResult(
            projectRoot = projectRoot,
            buildModeDescription = buildModeDesc,
            hasNdkSources = hasNdk,
            manifestFiles = manifestFiles,
            buildConfigFiles = buildConfigFiles,
            inspectedFiles = inspectedRelPaths,
            contextPromptBlock = sb.toString(),
            repoMap = repoMap
        )
    }

    /**
     * Finds files that reference symbols, class names, or XML layout/id resources defined in [targetFiles].
     */
    fun findDependentFiles(
        projectRoot: File,
        targetFiles: List<File>,
        allProjectFiles: List<File> = collectProjectSourceFiles(projectRoot)
    ): List<File> {
        if (targetFiles.isEmpty()) return emptyList()
        val targetSymbols = mutableSetOf<String>()
        for (target in targetFiles) {
            val stem = target.nameWithoutExtension
            if (stem.length >= 3) {
                targetSymbols.add(stem)
            }
            if (target.extension == "xml" && target.parentFile?.name?.startsWith("layout") == true) {
                targetSymbols.add("R.layout.$stem")
                targetSymbols.add("@layout/$stem")
            }
        }
        if (targetSymbols.isEmpty()) return emptyList()

        val dependents = mutableListOf<File>()
        for (candidate in allProjectFiles) {
            if (candidate in targetFiles || candidate.length() > 100_000) continue
            val content = try {
                candidate.readText()
            } catch (_: Exception) {
                continue
            }
            if (targetSymbols.any { sym -> content.contains(sym) }) {
                dependents.add(candidate)
            }
        }
        return dependents
    }

    fun collectProjectSourceFiles(projectRoot: File): List<File> {
        if (!projectRoot.exists() || !projectRoot.isDirectory) return emptyList()
        val result = mutableListOf<File>()
        projectRoot.walkTopDown()
            .onEnter { dir -> dir.name !in IGNORED_DIRS }
            .filter { file ->
                file.isFile &&
                    (file.extension.lowercase() in SOURCE_EXTENSIONS ||
                        file.name == "AndroidManifest.xml" ||
                        file.name == "Android.mk" ||
                        file.name == "Application.mk" ||
                        file.name == "CMakeLists.txt")
            }
            .forEach { result.add(it) }
        return result
    }

    data class SearchMatch(
        val relativePath: String,
        val lineNumber: Int,
        val lineContent: String
    )

    fun searchProject(
        projectRoot: File,
        query: String,
        fileExtension: String? = null,
        maxMatches: Int = 40
    ): List<SearchMatch> {
        val matches = mutableListOf<SearchMatch>()
        val files = collectProjectSourceFiles(projectRoot)
        val queryLower = query.lowercase()

        for (file in files) {
            if (matches.size >= maxMatches) break
            if (fileExtension != null && !file.extension.equals(fileExtension.trimStart('.'), ignoreCase = true)) {
                continue
            }
            if (file.length() > 250_000) continue
            val relPath = file.relativeTo(projectRoot).path.replace('\\', '/')
            try {
                file.forEachLine { line, lineNum ->
                    if (matches.size >= maxMatches) return@forEachLine
                    if (line.lowercase().contains(queryLower)) {
                        matches.add(
                            SearchMatch(
                                relativePath = relPath,
                                lineNumber = lineNum,
                                lineContent = line.trim()
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // Ignore unreadable binary/corrupted files
            }
        }
        return matches
    }

    private fun File.forEachLine(action: (String, Int) -> Unit) {
        this.bufferedReader().useLines { lines ->
            var num = 1
            for (l in lines) {
                action(l, num++)
            }
        }
    }

    fun listProjectFiles(
        projectRoot: File,
        subDir: String? = null,
        limit: Int = 80
    ): List<String> {
        val targetDir = if (subDir.isNullOrBlank()) {
            projectRoot
        } else {
            File(projectRoot, subDir)
        }
        if (!targetDir.exists() || !targetDir.isDirectory) return emptyList()

        return collectProjectSourceFiles(targetDir)
            .take(limit)
            .map { it.relativeTo(projectRoot).path.replace('\\', '/') }
            .sorted()
    }

    fun readFileRange(
        file: File,
        startLine: Int = 1,
        endLine: Int = 100
    ): String {
        if (!file.exists() || !file.isFile) return "Error: File not found: ${file.path}"
        val lines = file.readLines()
        val s = (startLine.coerceAtLeast(1) - 1).coerceAtMost(lines.size)
        val e = endLine.coerceAtLeast(startLine).coerceAtMost(lines.size)
        return lines.subList(s, e).mapIndexed { idx, line ->
            "${s + idx + 1}: $line"
        }.joinToString("\n")
    }

    fun inspectSymbolUsages(
        projectRoot: File,
        symbol: String
    ): List<SearchMatch> {
        return searchProject(projectRoot, symbol, maxMatches = 50)
    }
}
