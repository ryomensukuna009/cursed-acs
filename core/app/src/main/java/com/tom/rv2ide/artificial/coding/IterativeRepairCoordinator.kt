package com.tom.rv2ide.artificial.coding

import com.tom.rv2ide.artificial.chat.AgentCompletionReport
import com.tom.rv2ide.artificial.chat.VerificationLevel
import java.io.File

/**
 * Coordinates post-edit verification, cross-file consistency checks, and bounded self-repair prompts.
 */
object IterativeRepairCoordinator {

    const val MAX_REPAIR_ATTEMPTS = 2

    data class PostEditVerificationResult(
        val passed: Boolean,
        val level: VerificationLevel,
        val checksRun: List<String>,
        val issues: List<String>,
        val details: String
    )

    /**
     * Verifies applied edits across the project for syntax balance, XML well-formedness,
     * cross-file layout resource references, and optional compiler/build output diagnostics.
     */
    fun verifyAppliedEdits(
        projectRoot: File,
        appliedEdits: List<CoordinatedFileEdit>,
        compilerOutput: String? = null
    ): PostEditVerificationResult {
        val issues = mutableListOf<String>()
        val checksRun = mutableListOf<String>()

        // 1. Re-verify structural and syntax validity of each written file on disk
        checksRun.add("Syntax & delimiter balance")
        checksRun.add("XML well-formedness")
        for (edit in appliedEdits) {
            if (!edit.targetFile.exists()) {
                issues.add("Modified file '${edit.relativePath}' does not exist on disk after write.")
                continue
            }
            val diskErrors = TransactionalFileCoordinator.validateSingleEdit(
                edit.copy(finalContent = edit.targetFile.readText())
            )
            issues.addAll(diskErrors)
        }

        // 2. Check cross-file Android layout resource references (R.layout.<name>)
        val layoutNamesOnDisk = projectRoot.walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.parentFile?.name?.startsWith("layout") == true }
            .map { it.nameWithoutExtension }
            .toSet()

        if (layoutNamesOnDisk.isNotEmpty()) {
            checksRun.add("Cross-file R.layout resource consistency")
            val rLayoutRegex = Regex("""\bR\.layout\.([A-Za-z0-9_]+)\b""")
            for (edit in appliedEdits) {
                if (edit.targetFile.extension in setOf("kt", "java")) {
                    for (match in rLayoutRegex.findAll(edit.finalContent)) {
                        val layoutName = match.groupValues[1]
                        if (layoutName !in layoutNamesOnDisk) {
                            issues.add(
                                "Unresolved layout reference 'R.layout.$layoutName' in '${edit.relativePath}' " +
                                    "(no matching res/layout/$layoutName.xml found)."
                            )
                        }
                    }
                }
            }
        }

        // 3. Parse compiler/build output diagnostics if provided
        var hasCompilerOutput = false
        if (!compilerOutput.isNullOrBlank()) {
            hasCompilerOutput = true
            checksRun.add("Compiler & build stage diagnostic parser")
            val parsedDiagnostics = StructuredDiagnosticCollector.parseBuildOutput(
                rawOutput = compilerOutput,
                projectRoot = projectRoot
            )
            val grouped = StructuredDiagnosticCollector.groupCascadingDiagnostics(parsedDiagnostics)
            for (rootDiag in grouped.rootCauseDiagnostics) {
                issues.add(rootDiag.formatCompact())
            }
        }

        val passed = issues.isEmpty()
        val level = when {
            !passed -> VerificationLevel.FAILED_WITH_ERRORS
            hasCompilerOutput -> VerificationLevel.BUILT_AND_VERIFIED
            appliedEdits.isNotEmpty() -> VerificationLevel.STATICALLY_VERIFIED
            else -> VerificationLevel.EDITED_UNVERIFIED
        }

        val details = if (passed) {
            "Validated ${appliedEdits.size} file(s): ${checksRun.joinToString(", ")} passed."
        } else {
            "Detected ${issues.size} verification issue(s) across ${appliedEdits.size} modified file(s)."
        }

        return PostEditVerificationResult(
            passed = passed,
            level = level,
            checksRun = checksRun,
            issues = issues,
            details = details
        )
    }

    /**
     * Constructs a targeted self-repair prompt when pre-apply validation or post-edit verification fails.
     */
    fun buildRepairPrompt(
        originalUserRequest: String,
        attemptNumber: Int,
        validationOrBuildIssues: List<String>,
        affectedEdits: List<CoordinatedFileEdit>
    ): String {
        return buildString {
            appendLine("=== AUTOMATED SELF-REPAIR REQUEST (Attempt $attemptNumber of $MAX_REPAIR_ATTEMPTS) ===")
            appendLine("Your previous code modification for the user's request failed validation/verification.")
            appendLine("Original User Request: $originalUserRequest")
            appendLine()
            appendLine("Exact Issues to Fix (Root Causes First):")
            validationOrBuildIssues.forEachIndexed { idx, issue ->
                appendLine("  ${idx + 1}. $issue")
            }
            appendLine()
            if (affectedEdits.isNotEmpty()) {
                appendLine("Affected Files from Previous Attempt:")
                for (edit in affectedEdits.take(4)) {
                    appendLine("--- FILE: ${edit.relativePath} ---")
                    appendLine(edit.finalContent.take(6_000))
                    appendLine("--- END FILE: ${edit.relativePath} ---")
                }
                appendLine()
            }
            appendLine("INSTRUCTIONS FOR REPAIR:")
            appendLine("1. Fix ALL listed root-cause errors without introducing truncation placeholders.")
            appendLine("2. Output the corrected file(s) using the exact FILE_TO_MODIFY: <path> or FILE_TO_CREATE: <path> followed by CODE_START and CODE_END.")
            appendLine("3. Ensure all braces, parentheses, imports, and XML tags are complete and balanced.")
        }
    }

    /**
     * Builds the final honest [AgentCompletionReport] for display in the chat message card.
     */
    fun buildCompletionReport(
        goalOrRootCause: String,
        filesInspected: List<String>,
        appliedEdits: List<CoordinatedFileEdit>,
        verification: PostEditVerificationResult?,
        repairAttempts: Int,
        rolledBack: Boolean = false
    ): AgentCompletionReport {
        val modifiedPaths = appliedEdits.map { it.relativePath }
        val effectiveLevel = when {
            rolledBack -> VerificationLevel.ROLLED_BACK
            verification != null -> verification.level
            modifiedPaths.isNotEmpty() -> VerificationLevel.IMPLEMENTED_UNVERIFIED
            else -> VerificationLevel.NOT_VERIFIED
        }
        val checks = buildList {
            if (filesInspected.isNotEmpty()) {
                add("Inspected ${filesInspected.size} project file(s)")
            }
            if (verification != null) {
                addAll(verification.checksRun)
            }
        }
        val mainChanges = if (modifiedPaths.isNotEmpty()) {
            "Coordinated atomic update across ${modifiedPaths.size} file(s)"
        } else {
            "Analyzed project context without modifying disk files"
        }

        return AgentCompletionReport(
            goalOrRootCause = goalOrRootCause.take(140),
            filesModified = modifiedPaths,
            mainChangesSummary = mainChanges,
            validationChecksRun = checks,
            validationPassed = !rolledBack && (verification?.passed ?: true),
            verificationLevel = effectiveLevel,
            repairAttemptsUsed = repairAttempts,
            remainingErrors = verification?.issues ?: emptyList(),
            nextActionableStep = if (verification != null && !verification.passed) {
                "Review remaining diagnostic(s) or tap Retry to run another repair pass."
            } else {
                null
            }
        )
    }
}
