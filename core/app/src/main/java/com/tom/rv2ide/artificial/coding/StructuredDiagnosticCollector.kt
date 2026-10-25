package com.tom.rv2ide.artificial.coding

import java.io.File

/**
 * Structured representation of a compiler, resource, linker, or build diagnostic.
 */
data class BuildDiagnostic(
    val filePath: String?,
    val line: Int?,
    val column: Int?,
    val severity: Severity,
    val stage: String,
    val message: String,
    val rawLine: String
) {
    enum class Severity {
        ERROR,
        WARNING,
        INFO
    }

    val isRootCauseCandidate: Boolean
        get() {
            val lower = message.lowercase()
            return lower.contains("unresolved reference") ||
                lower.contains("cannot find symbol") ||
                lower.contains("package does not exist") ||
                lower.contains("syntax error") ||
                lower.contains("expecting") ||
                lower.contains("resource") && lower.contains("not found") ||
                lower.contains("duplicate class") ||
                lower.contains("undefined reference") ||
                lower.contains("file not found") ||
                lower.contains("unclosed")
        }

    fun formatCompact(): String {
        val loc = buildString {
            if (!filePath.isNullOrBlank()) {
                append(filePath)
                if (line != null) append(":$line")
                if (column != null) append(":$column")
                append(" - ")
            }
        }
        return "[${stage}] ${severity.name}: ${loc}${message}"
    }
}

/**
 * Root-cause grouped diagnostic report so the coding agent fixes primary errors before cascading errors.
 */
data class CascadingDiagnosticReport(
    val rootCauseDiagnostics: List<BuildDiagnostic>,
    val secondaryDiagnostics: List<BuildDiagnostic>,
    val affectedFiles: List<String>,
    val summaryPromptBlock: String
)

/**
 * Represents a persistent diagnostic whose line number shifted between builds.
 */
data class ShiftedDiagnostic(
    val diagnostic: BuildDiagnostic,
    val previousLine: Int?,
    val currentLine: Int?
)

/**
 * Result of comparing baseline diagnostics against current build diagnostics.
 */
data class DiagnosticDelta(
    val newDiagnostics: List<BuildDiagnostic>,
    val persistentDiagnostics: List<BuildDiagnostic>,
    val resolvedDiagnostics: List<BuildDiagnostic>,
    val shiftedDiagnostics: List<ShiftedDiagnostic>,
    val baselineCount: Int,
    val currentCount: Int,
    val summaryPromptBlock: String
) {
    val totalActiveErrors: Int
        get() = (newDiagnostics + persistentDiagnostics + shiftedDiagnostics.map { it.diagnostic })
            .count { it.severity == BuildDiagnostic.Severity.ERROR }
}

/**
 * Parses and groups diagnostics from OfflineBuildEngine, javac, kotlinc, aapt2, ndk-build/clang, and Gradle outputs.
 */
object StructuredDiagnosticCollector {

    // Matches standard javac / clang / ndk-build / aapt2: /path/to/File.ext:42:10: error: message
    // Supports Windows drive letters (C:\), spaces in paths, and AAPT prefixes
    private val COLON_DIAG_REGEX = Regex(
        """^(?:\./)?((?:[a-zA-Z]:[\\/])?[^:\r\n]+\.(?:kt|java|xml|c|cpp|cc|cxx|h|hpp|mk|gradle|kts)):(\d+)(?::(\d+))?:\s*(?:(?:AAPT|clang|gcc):\s*)?(error|warning|fatal error):\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

    // Matches kotlinc paren format: e: /path/to/File.kt: (42, 10): message
    private val KOTLINC_PAREN_REGEX = Regex(
        """^\s*([ew]):\s*(?:file://)?((?:[a-zA-Z]:[\\/])?[^:\r\n]+\.(?:kt|kts)):\s*\((\d+),\s*(\d+)\)\s*:?\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

    // Matches kotlinc colon format: e: /path/to/File.kt:42:10: message
    private val KOTLINC_COLON_REGEX = Regex(
        """^\s*([ew]):\s*(?:file://)?((?:[a-zA-Z]:[\\/])?[^:\r\n]+\.(?:kt|kts)):(\d+)(?::(\d+))?\s*:?\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

    // Matches AAPT2 resource file errors with line number: /path/res.xml:42: error: ...
    // Supports character ranges like :14:1-35: and AAPT prefixes
    private val AAPT2_FILE_REGEX = Regex(
        """^((?:[a-zA-Z]:[\\/])?[^:\r\n]+\.xml):(\d+)(?::(\d+)(?:-\d+)?)?:\s*(?:AAPT:\s*)?(error|warn(?:ing)?):\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

    // Matches AAPT2 / Android resource errors without specific line numbers
    private val AAPT2_ERROR_REGEX = Regex(
        """^(?:.*aapt2.*|.*AndroidManifest\.xml.*|.*res/.*\.xml.*)?:\s*(error|warn(?:ing)?):\s*(.+)$""",
        RegexOption.IGNORE_CASE
    )

    // Matches NDK / linker undefined reference
    private val LINKER_ERROR_REGEX = Regex(
        """^(.*(?:\.o|\.cpp|\.c|\.so|\.a)):\s*(?:in function\s+.+:)?\s*(undefined reference to\s+.+|ld(?:\.lld)?:\s*error:\s*.+)$""",
        RegexOption.IGNORE_CASE
    )

    // Matches D8 dex converter failures
    private val D8_ERROR_REGEX = Regex(
        """^(?:.*D8:\s*|ERROR:\s*|Compilation failed to complete\s*)(.+)$""",
        RegexOption.IGNORE_CASE
    )

    // Matches Gradle top-level configuration / task failures
    private val GRADLE_FAILURE_REGEX = Regex(
        """^(?:FAILURE:\s*Build failed with an exception\.|>\s*What went wrong:\s*|>\s*(?:Task|Could not resolve)\s*|\*\s*What went wrong:\s*)(.+)$""",
        RegexOption.IGNORE_CASE
    )

    // Matches POSIX shell / sh / bash syntax and command errors (Task 7):
    // e.g. /system/bin/sh: syntax error: unexpected '(' or /bin/sh: line 1: foo: command not found
    private val SHELL_ERROR_REGEX = Regex(
        """^(?:(?:/[^:]+/)?(?:sh|bash|mksh|toybox)):\s*(?:line\s+\d+:\s*)?(syntax error.*|.*unexpected.*|.*command not found.*|.*permission denied.*)$""",
        RegexOption.IGNORE_CASE
    )

    fun computeErrorFingerprint(diagnostics: List<BuildDiagnostic>): String {
        val errorItems = diagnostics
            .filter { it.severity == BuildDiagnostic.Severity.ERROR }
            .map { "${it.stage}:${it.filePath.orEmpty()}:${it.line ?: -1}:${it.message.take(80).lowercase().trim()}" }
            .sorted()
        if (errorItems.isEmpty()) return "NO_ERRORS"
        val joined = errorItems.joinToString("\n")
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            md.digest(joined.toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            joined.hashCode().toString()
        }
    }

    fun parseBuildOutput(
        rawOutput: String,
        projectRoot: File? = null,
        defaultStage: String = "BUILD"
    ): List<BuildDiagnostic> {
        if (rawOutput.isBlank()) return emptyList()
        val diagnostics = mutableListOf<BuildDiagnostic>()
        var currentStage = defaultStage

        for (rawLine in rawOutput.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // Detect and strip stage prefix: e.g. "[NDK_BUILD] path/to/file:1:2: error: ..."
            val effectiveLine = if (line.startsWith("[") && line.contains("] ")) {
                val bracketTag = line.substring(1, line.indexOf("] "))
                if (bracketTag.isNotBlank() && !bracketTag.contains(" ")) {
                    currentStage = when {
                        bracketTag.contains("AAPT2", ignoreCase = true) || bracketTag.contains("RESOURCE", ignoreCase = true) -> "AAPT2"
                        bracketTag.contains("JAVAC", ignoreCase = true) || bracketTag.contains("Java", ignoreCase = true) -> "JAVAC"
                        bracketTag.contains("NDK", ignoreCase = true) || bracketTag.contains("NATIVE", ignoreCase = true) -> "NDK_BUILD"
                        bracketTag.contains("D8", ignoreCase = true) || bracketTag.contains("DEX", ignoreCase = true) -> "D8_DEX"
                        bracketTag.contains("SIGN", ignoreCase = true) -> "APK_SIGN"
                        bracketTag.contains("GRADLE", ignoreCase = true) -> "GRADLE"
                        else -> bracketTag
                    }
                }
                line.substring(line.indexOf("] ") + 2).trim()
            } else {
                line
            }

            // Detect OfflineBuildEngine stage headers if present
            if (effectiveLine.startsWith("=== [Stage") || effectiveLine.startsWith("[OfflineBuildEngine]")) {
                currentStage = when {
                    effectiveLine.contains("AAPT2", ignoreCase = true) || effectiveLine.contains("Resource", ignoreCase = true) -> "AAPT2"
                    effectiveLine.contains("JAVAC", ignoreCase = true) || effectiveLine.contains("Java", ignoreCase = true) -> "JAVAC"
                    effectiveLine.contains("NDK", ignoreCase = true) || effectiveLine.contains("Native", ignoreCase = true) -> "NDK_BUILD"
                    effectiveLine.contains("D8", ignoreCase = true) || effectiveLine.contains("DEX", ignoreCase = true) -> "D8_DEX"
                    effectiveLine.contains("SIGN", ignoreCase = true) -> "APK_SIGN"
                    else -> currentStage
                }
            }

            KOTLINC_PAREN_REGEX.matchEntire(effectiveLine)?.let { m ->
                val sev = if (m.groupValues[1].lowercase() == "e") {
                    BuildDiagnostic.Severity.ERROR
                } else {
                    BuildDiagnostic.Severity.WARNING
                }
                val normPath = normalizePath(m.groupValues[2], projectRoot)
                val lineNum = m.groupValues[3].toIntOrNull()
                val colNum = m.groupValues[4].toIntOrNull()
                val msg = m.groupValues[5].trim()
                diagnostics.add(
                    BuildDiagnostic(
                        filePath = normPath,
                        line = lineNum,
                        column = colNum,
                        severity = sev,
                        stage = "KOTLINC",
                        message = msg,
                        rawLine = line
                    )
                )
                return@let
            } ?: KOTLINC_COLON_REGEX.matchEntire(effectiveLine)?.let { m ->
                val sev = if (m.groupValues[1].lowercase() == "e") {
                    BuildDiagnostic.Severity.ERROR
                } else {
                    BuildDiagnostic.Severity.WARNING
                }
                val normPath = normalizePath(m.groupValues[2], projectRoot)
                val lineNum = m.groupValues[3].toIntOrNull()
                val colNum = m.groupValues[4].toIntOrNull()
                val msg = m.groupValues[5].trim()
                diagnostics.add(
                    BuildDiagnostic(
                        filePath = normPath,
                        line = lineNum,
                        column = colNum,
                        severity = sev,
                        stage = "KOTLINC",
                        message = msg,
                        rawLine = line
                    )
                )
                return@let
            } ?: AAPT2_FILE_REGEX.matchEntire(effectiveLine)?.let { m ->
                val normPath = normalizePath(m.groupValues[1], projectRoot)
                val lineNum = m.groupValues[2].toIntOrNull()
                val colNum = m.groupValues[3].toIntOrNull()
                val sevToken = m.groupValues[4].lowercase()
                val sev = if (sevToken.startsWith("err")) {
                    BuildDiagnostic.Severity.ERROR
                } else {
                    BuildDiagnostic.Severity.WARNING
                }
                val msg = m.groupValues[5].trim()
                diagnostics.add(
                    BuildDiagnostic(
                        filePath = normPath,
                        line = lineNum,
                        column = colNum,
                        severity = sev,
                        stage = "AAPT2",
                        message = msg,
                        rawLine = line
                    )
                )
                return@let
            } ?: COLON_DIAG_REGEX.matchEntire(effectiveLine)?.let { m ->
                val normPath = normalizePath(m.groupValues[1], projectRoot)
                val lineNum = m.groupValues[2].toIntOrNull()
                val colNum = m.groupValues[3].toIntOrNull()
                val sevToken = m.groupValues[4].lowercase()
                val sev = if (sevToken.contains("error")) {
                    BuildDiagnostic.Severity.ERROR
                } else {
                    BuildDiagnostic.Severity.WARNING
                }
                val msg = m.groupValues[5].trim()
                val stage = inferStageFromPath(normPath, currentStage)
                diagnostics.add(
                    BuildDiagnostic(
                        filePath = normPath,
                        line = lineNum,
                        column = colNum,
                        severity = sev,
                        stage = stage,
                        message = msg,
                        rawLine = line
                    )
                )
                return@let
            } ?: LINKER_ERROR_REGEX.matchEntire(effectiveLine)?.let { m ->
                val normPath = normalizePath(m.groupValues[1], projectRoot)
                val msg = m.groupValues[2].trim()
                diagnostics.add(
                    BuildDiagnostic(
                        filePath = normPath,
                        line = null,
                        column = null,
                        severity = BuildDiagnostic.Severity.ERROR,
                        stage = "NDK_LINKER",
                        message = msg,
                        rawLine = line
                    )
                )
                return@let
            } ?: AAPT2_ERROR_REGEX.matchEntire(effectiveLine)?.let { m ->
                val sev = if (m.groupValues[1].lowercase().startsWith("err")) {
                    BuildDiagnostic.Severity.ERROR
                } else {
                    BuildDiagnostic.Severity.WARNING
                }
                diagnostics.add(
                    BuildDiagnostic(
                        filePath = null,
                        line = null,
                        column = null,
                        severity = sev,
                        stage = "AAPT2",
                        message = m.groupValues[2].trim(),
                        rawLine = line
                    )
                )
                return@let
            } ?: D8_ERROR_REGEX.matchEntire(effectiveLine)?.let { m ->
                if (currentStage == "D8_DEX" || effectiveLine.contains("dex", ignoreCase = true)) {
                    diagnostics.add(
                        BuildDiagnostic(
                            filePath = null,
                            line = null,
                            column = null,
                            severity = BuildDiagnostic.Severity.ERROR,
                            stage = "D8_DEX",
                            message = m.groupValues[1].trim(),
                            rawLine = line
                        )
                    )
                }
                return@let
            } ?: GRADLE_FAILURE_REGEX.matchEntire(effectiveLine)?.let { m ->
                diagnostics.add(
                    BuildDiagnostic(
                        filePath = null,
                        line = null,
                        column = null,
                        severity = BuildDiagnostic.Severity.ERROR,
                        stage = "GRADLE",
                        message = m.groupValues[1].trim(),
                        rawLine = line
                    )
                )
                return@let
            } ?: SHELL_ERROR_REGEX.matchEntire(effectiveLine)?.let { m ->
                diagnostics.add(
                    BuildDiagnostic(
                        filePath = null,
                        line = null,
                        column = null,
                        severity = BuildDiagnostic.Severity.ERROR,
                        stage = "SHELL_EXEC",
                        message = "Shell execution/syntax error: ${m.groupValues[1].trim()}. Verify command arguments and unquoted shell metacharacters.",
                        rawLine = line
                    )
                )
            }
        }

        // Robust Fallback: If no structured errors were matched, but the build output reflects failure,
        // extract the actual failure lines and report them under [Project Build] so errors never get lost!
        if (diagnostics.none { it.severity == BuildDiagnostic.Severity.ERROR } &&
            (rawOutput.contains("BUILD FAILED", ignoreCase = true) ||
             rawOutput.contains("FAILURE:", ignoreCase = true) ||
             rawOutput.contains("FAILED", ignoreCase = true) ||
             rawOutput.contains("error:", ignoreCase = true) ||
             rawOutput.contains("Exception:", ignoreCase = true))) {

            val failureLines = mutableListOf<String>()
            var capturingWhatWentWrong = false

            for (rawLine in rawOutput.lineSequence()) {
                val t = rawLine.trim()
                if (t.isEmpty()) continue

                if (t.startsWith("* What went wrong:", ignoreCase = true) || t.startsWith("What went wrong:", ignoreCase = true)) {
                    capturingWhatWentWrong = true
                    continue
                }
                if (capturingWhatWentWrong) {
                    if (t.startsWith("*") || t.startsWith("> Run with")) {
                        capturingWhatWentWrong = false
                    } else {
                        failureLines.add(t.removePrefix(">").trim())
                    }
                } else if (t.startsWith("Execution failed for task", ignoreCase = true) ||
                    (t.contains("FAILED", ignoreCase = true) && t.startsWith("> Task")) ||
                    t.startsWith("Error:", ignoreCase = true) ||
                    t.startsWith("error:", ignoreCase = true) ||
                    t.startsWith("fatal error:", ignoreCase = true) ||
                    t.contains("Compilation failed", ignoreCase = true) ||
                    t.contains("Cannot find", ignoreCase = true) ||
                    t.contains("Unresolved reference", ignoreCase = true) ||
                    t.contains("undefined reference", ignoreCase = true) ||
                    t.contains("resource linking failed", ignoreCase = true) ||
                    t.contains("resource compilation failed", ignoreCase = true) ||
                    t.contains("Build failed", ignoreCase = true) ||
                    t.contains("Exception:", ignoreCase = true)) {
                    failureLines.add(t.removePrefix(">").trim())
                }
            }

            if (failureLines.isEmpty()) {
                val candidate = rawOutput.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && (it.contains("failed", ignoreCase = true) || it.contains("error", ignoreCase = true) || it.contains("exception", ignoreCase = true)) }
                    .lastOrNull()
                if (!candidate.isNullOrBlank()) {
                    failureLines.add(candidate.take(200))
                } else {
                    failureLines.add("Build failed in stage '$defaultStage'. Review build output.")
                }
            }

            for (failMsg in failureLines.take(15).distinct()) {
                diagnostics.add(
                    BuildDiagnostic(
                        filePath = null,
                        line = null,
                        column = null,
                        severity = BuildDiagnostic.Severity.ERROR,
                        stage = defaultStage,
                        message = failMsg,
                        rawLine = failMsg
                    )
                )
            }
        }

        return diagnostics.distinctBy { "${it.filePath}:${it.line}:${it.message}" }
    }

    /**
     * Groups diagnostics into primary root causes vs secondary cascading errors.
     * Example: If an XML resource fails to compile in AAPT2, `R.id.*` / `R.layout.*` errors in Java/Kotlin
     * are secondary cascading errors caused by the missing R class generation.
     */
    fun groupCascadingDiagnostics(diagnostics: List<BuildDiagnostic>): CascadingDiagnosticReport {
        val errors = diagnostics.filter { it.severity == BuildDiagnostic.Severity.ERROR }
        if (errors.isEmpty()) {
            return CascadingDiagnosticReport(
                rootCauseDiagnostics = emptyList(),
                secondaryDiagnostics = diagnostics,
                affectedFiles = emptyList(),
                summaryPromptBlock = "No compiler or build errors detected."
            )
        }

        val hasResourceError = errors.any { it.stage == "AAPT2" || it.filePath?.endsWith(".xml") == true }
        val rootCauses = mutableListOf<BuildDiagnostic>()
        val secondary = mutableListOf<BuildDiagnostic>()

        for (err in errors) {
            val isCascadingRClassError = hasResourceError &&
                (err.message.contains("package R does not exist") ||
                    err.message.contains("cannot find symbol") && err.message.contains("variable R") ||
                    err.message.contains("Unresolved reference: R"))

            if (isCascadingRClassError) {
                secondary.add(err)
            } else if (err.isRootCauseCandidate || rootCauses.size < 8) {
                rootCauses.add(err)
            } else {
                secondary.add(err)
            }
        }

        if (rootCauses.isEmpty() && errors.isNotEmpty()) {
            rootCauses.addAll(errors.take(5))
        }

        val affectedFiles = (rootCauses + secondary)
            .mapNotNull { it.filePath }
            .distinct()

        val summary = buildString {
            appendLine("=== STRUCTURED DIAGNOSTIC ANALYSIS ===")
            appendLine("Primary Root-Cause Errors (${rootCauses.size}):")
            rootCauses.forEachIndexed { idx, d ->
                appendLine("  ${idx + 1}. ${d.formatCompact()}")
            }
            if (secondary.isNotEmpty()) {
                appendLine("Cascading / Secondary Errors (${secondary.size} — likely resolved once root causes are fixed):")
                secondary.take(5).forEach { d ->
                    appendLine("  - ${d.formatCompact()}")
                }
            }
        }.trim()

        return CascadingDiagnosticReport(
            rootCauseDiagnostics = rootCauses,
            secondaryDiagnostics = secondary,
            affectedFiles = affectedFiles,
            summaryPromptBlock = summary
        )
    }

    private fun inferStageFromPath(filePath: String?, fallback: String): String {
        if (filePath == null) return fallback
        return when {
            filePath.endsWith(".kt") || filePath.endsWith(".kts") -> "KOTLINC"
            filePath.endsWith(".java") -> "JAVAC"
            filePath.endsWith(".xml") -> "AAPT2"
            filePath.endsWith(".c") || filePath.endsWith(".cpp") || filePath.endsWith(".h") || filePath.endsWith(".mk") -> "NDK_BUILD"
            else -> fallback
        }
    }

    private fun normalizePath(rawPath: String, projectRoot: File?): String {
        var cleaned = rawPath.trim().replace('\\', '/')
        if (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2)
        }
        if (projectRoot != null) {
            val rootNormalized = projectRoot.absolutePath.replace('\\', '/').trimEnd('/') + "/"
            if (cleaned.startsWith(rootNormalized)) {
                return cleaned.removePrefix(rootNormalized)
            }
            val direct = File(projectRoot, cleaned)
            if (direct.exists()) {
                return cleaned
            }
            if (!cleaned.contains("/")) {
                val found = projectRoot.walkTopDown().maxDepth(6).firstOrNull { it.isFile && it.name == cleaned }
                if (found != null) {
                    val foundNorm = found.absolutePath.replace('\\', '/')
                    if (foundNorm.startsWith(rootNormalized)) {
                        return foundNorm.removePrefix(rootNormalized)
                    }
                }
            }
        }
        return cleaned
    }

    /**
     * Compares [baselineDiagnostics] against [currentDiagnostics], categorizing into:
     * 1. Newly introduced diagnostics (present in current, absent from baseline)
     * 2. Persistent diagnostics (present at same line in both)
     * 3. Shifted diagnostics (same file and error message, but line number shifted)
     * 4. Resolved diagnostics (present in baseline, absent in current)
     */
    fun computeDiagnosticDelta(
        baselineDiagnostics: List<BuildDiagnostic>,
        currentDiagnostics: List<BuildDiagnostic>,
        projectRoot: File? = null
    ): DiagnosticDelta {
        val remainingBaseline = baselineDiagnostics.toMutableList()
        val remainingCurrent = currentDiagnostics.toMutableList()

        val persistent = mutableListOf<BuildDiagnostic>()
        val shifted = mutableListOf<ShiftedDiagnostic>()

        fun normPath(p: String?): String {
            if (p.isNullOrBlank()) return ""
            var cleaned = p.trim().replace('\\', '/')
            if (cleaned.startsWith("./")) cleaned = cleaned.substring(2)
            if (projectRoot != null) {
                val rootNorm = projectRoot.absolutePath.replace('\\', '/').trimEnd('/') + "/"
                if (cleaned.startsWith(rootNorm)) {
                    cleaned = cleaned.removePrefix(rootNorm)
                }
            }
            return cleaned
        }

        fun normMsg(m: String): String {
            return m.trim()
                .replace('\\', '/')
                .replace(Regex("""\s+"""), " ")
                .lowercase()
        }

        // Pass 1: Exact matches (same path, stage, severity, line, and normalized message)
        val currentIterator = remainingCurrent.iterator()
        while (currentIterator.hasNext()) {
            val curr = currentIterator.next()
            val matchIdx = remainingBaseline.indexOfFirst { base ->
                normPath(base.filePath) == normPath(curr.filePath) &&
                    base.stage.equals(curr.stage, ignoreCase = true) &&
                    base.severity == curr.severity &&
                    base.line == curr.line &&
                    normMsg(base.message) == normMsg(curr.message)
            }
            if (matchIdx >= 0) {
                persistent.add(curr)
                remainingBaseline.removeAt(matchIdx)
                currentIterator.remove()
            }
        }

        // Pass 2: Shifted line matches (same path, stage, severity, normalized message, but different line)
        val shiftedIterator = remainingCurrent.iterator()
        while (shiftedIterator.hasNext()) {
            val curr = shiftedIterator.next()
            val candidates = remainingBaseline.filter { base ->
                normPath(base.filePath) == normPath(curr.filePath) &&
                    base.stage.equals(curr.stage, ignoreCase = true) &&
                    base.severity == curr.severity &&
                    normMsg(base.message) == normMsg(curr.message)
            }
            if (candidates.isNotEmpty()) {
                val bestMatch = candidates.minByOrNull { base ->
                    Math.abs((base.line ?: 0) - (curr.line ?: 0))
                }!!
                shifted.add(ShiftedDiagnostic(curr, bestMatch.line, curr.line))
                remainingBaseline.remove(bestMatch)
                shiftedIterator.remove()
            }
        }

        // Pass 3: Remaining items
        val newDiags = remainingCurrent.toList()
        val resolvedDiags = remainingBaseline.toList()

        val summary = buildString {
            appendLine("=== DELTA COMPILER DIAGNOSTICS ===")
            appendLine("Active Diagnostics: ${currentDiagnostics.size} (Baseline had ${baselineDiagnostics.size})")

            val newErrors = newDiags.filter { it.severity == BuildDiagnostic.Severity.ERROR }
            val persistentErrors = persistent.filter { it.severity == BuildDiagnostic.Severity.ERROR }
            val shiftedErrors = shifted.filter { it.diagnostic.severity == BuildDiagnostic.Severity.ERROR }
            val resolvedErrors = resolvedDiags.filter { it.severity == BuildDiagnostic.Severity.ERROR }

            if (newErrors.isNotEmpty()) {
                appendLine()
                appendLine("[!] NEW ERRORS INTRODUCED IN THIS WAVE (${newErrors.size}):")
                newErrors.forEachIndexed { i, d ->
                    appendLine("  ${i + 1}. ${d.formatCompact()}")
                }
            }

            if (persistentErrors.isNotEmpty()) {
                appendLine()
                appendLine("[*] PERSISTENT ROOT-CAUSE ERRORS (${persistentErrors.size}):")
                persistentErrors.forEachIndexed { i, d ->
                    appendLine("  ${i + 1}. ${d.formatCompact()}")
                }
            }

            if (shiftedErrors.isNotEmpty()) {
                appendLine()
                appendLine("[~] PERSISTENT ERRORS (Shifted Line Numbers) (${shiftedErrors.size}):")
                shiftedErrors.forEachIndexed { i, s ->
                    val prev = s.previousLine?.let { "previously line $it" } ?: "previous line unknown"
                    appendLine("  ${i + 1}. ${s.diagnostic.formatCompact()} ($prev)")
                }
            }

            if (resolvedErrors.isNotEmpty()) {
                appendLine()
                appendLine("[✓] RESOLVED COMPILER ERRORS (${resolvedErrors.size} fixed):")
                resolvedErrors.take(5).forEach { d ->
                    appendLine("  - ${d.formatCompact()}")
                }
            }

            val remainingWarnings = (newDiags + persistent + shifted.map { it.diagnostic })
                .filter { it.severity == BuildDiagnostic.Severity.WARNING }
            if (remainingWarnings.isNotEmpty()) {
                appendLine()
                appendLine("[-] Compiler Warnings (${remainingWarnings.size} — non-blocking):")
                remainingWarnings.take(3).forEach { d ->
                    appendLine("  - ${d.formatCompact()}")
                }
            }
        }.trim()

        return DiagnosticDelta(
            newDiagnostics = newDiags,
            persistentDiagnostics = persistent,
            resolvedDiagnostics = resolvedDiags,
            shiftedDiagnostics = shifted,
            baselineCount = baselineDiagnostics.size,
            currentCount = currentDiagnostics.size,
            summaryPromptBlock = summary
        )
    }

    /**
     * Extracts a surrounding source code snippet for a diagnostic if it points to a readable file and line number.
     * Returns formatted snippet with line numbers and a pointer '>' on the error line.
     */
    fun extractSourceContext(
        diagnostic: BuildDiagnostic,
        projectRoot: File?,
        windowRadius: Int = 5
    ): String? {
        val filePath = diagnostic.filePath?.takeIf { it.isNotBlank() } ?: return null
        val targetLine = diagnostic.line?.takeIf { it > 0 } ?: return null

        val file = when {
            File(filePath).isAbsolute -> File(filePath)
            projectRoot != null -> File(projectRoot, filePath)
            else -> File(filePath)
        }

        if (!file.exists() || !file.isFile) return null

        return try {
            val lines = file.readLines()
            if (lines.isEmpty()) return null

            val startLine = maxOf(1, targetLine - windowRadius)
            val endLine = minOf(lines.size, targetLine + windowRadius)

            val sb = StringBuilder()
            sb.appendLine("  [Source Context: $filePath around line $targetLine]")
            for (lineIdx in startLine..endLine) {
                val isTarget = (lineIdx == targetLine)
                val prefix = if (isTarget) ">" else " "
                val lineContent = lines[lineIdx - 1]
                sb.appendLine("%s %4d | %s".format(prefix, lineIdx, lineContent))
            }
            sb.toString().trimEnd()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Augments a list of diagnostics with their surrounding source code context windows.
     */
    fun buildAugmentedErrorContext(
        diagnostics: List<BuildDiagnostic>,
        projectRoot: File?,
        maxSnippets: Int = 4,
        windowRadius: Int = 5
    ): String {
        if (diagnostics.isEmpty() || projectRoot == null) return ""

        val snippets = mutableListOf<String>()
        val seenLocations = mutableSetOf<String>()

        for (d in diagnostics) {
            if (snippets.size >= maxSnippets) break
            val path = d.filePath ?: continue
            val line = d.line ?: continue
            val key = "$path:$line"
            if (seenLocations.contains(key)) continue
            seenLocations.add(key)

            val snippet = extractSourceContext(d, projectRoot, windowRadius)
            if (!snippet.isNullOrBlank()) {
                snippets.add("• ${d.formatCompact()}\n$snippet")
            }
        }

        return snippets.joinToString("\n\n")
    }
}

