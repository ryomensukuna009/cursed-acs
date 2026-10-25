package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.chat.VerificationLevel
import com.tom.rv2ide.artificial.coding.BuildDiagnostic
import com.tom.rv2ide.artificial.coding.StructuredDiagnosticCollector
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Categorization of verification activities.
 */
enum class VerificationType(val displayName: String) {
    BUILD("Project Build"),
    TEST("Test Execution"),
    LINT("Linter / Static Analysis"),
    CUSTOM("Custom Verification")
}

/**
 * Immutable historical record of a single verification execution pass.
 */
data class VerificationRunEntry(
    val runId: String,
    val type: VerificationType,
    val commandOrTool: String,
    val generation: Long,
    val timestampMs: Long = System.currentTimeMillis(),
    val isSuccess: Boolean,
    val exitCode: Int? = null,
    val diagnostics: List<BuildDiagnostic> = emptyList(),
    val summary: String = "",
    @Volatile var resolvedByRunId: String? = null,
    @Volatile var isStale: Boolean = false
) {
    val isUnresolvedFailure: Boolean
        get() = !isSuccess && resolvedByRunId == null

    val isResolvedFailure: Boolean
        get() = !isSuccess && resolvedByRunId != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("runId", runId)
        put("type", type.name)
        put("commandOrTool", commandOrTool)
        put("generation", generation)
        put("timestampMs", timestampMs)
        put("isSuccess", isSuccess)
        put("exitCode", exitCode ?: JSONObject.NULL)
        put("summary", summary)
        put("resolvedByRunId", resolvedByRunId ?: JSONObject.NULL)
        put("isStale", isStale)

        val diagArray = JSONArray()
        for (d in diagnostics) {
            val dObj = JSONObject().apply {
                put("filePath", d.filePath ?: JSONObject.NULL)
                put("line", d.line ?: JSONObject.NULL)
                put("column", d.column ?: JSONObject.NULL)
                put("severity", d.severity.name)
                put("stage", d.stage)
                put("message", d.message)
                put("rawLine", d.rawLine)
            }
            diagArray.put(dObj)
        }
        put("diagnostics", diagArray)
    }

    companion object {
        fun parseTestOutput(rawOutput: String): List<BuildDiagnostic> {
            if (rawOutput.isBlank()) return emptyList()
            val diags = mutableListOf<BuildDiagnostic>()

            val gradleTestFailedRegex = Regex("""^([\w.]+)\s*>\s*(\w+)\s+FAILED""", RegexOption.IGNORE_CASE)
            val assertionRegex = Regex("""^(?:java\.lang\.)?AssertionError:\s*(.+)$""", RegexOption.IGNORE_CASE)
            val comparisonRegex = Regex("""^org\.junit\.(?:ComparisonFailure|ComparisonError):\s*(.+)$""", RegexOption.IGNORE_CASE)
            val stackTraceRegex = Regex("""^\s*at\s+([\w.$]+)\(([\w-]+\.(?:kt|java)):(\d+)\)""")
            val pytestRegex = Regex("""^FAILED\s+([^:]+)::(\w+)(?:\s*-\s*(.+))?""", RegexOption.IGNORE_CASE)

            var currentTestName: String? = null
            var currentFile: String? = null
            var currentLine: Int? = null

            for (rawLine in rawOutput.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue

                gradleTestFailedRegex.find(line)?.let { m ->
                    currentTestName = "${m.groupValues[1]}#${m.groupValues[2]}"
                    diags.add(
                        BuildDiagnostic(
                            filePath = currentFile,
                            line = currentLine,
                            column = null,
                            severity = BuildDiagnostic.Severity.ERROR,
                            stage = "TEST",
                            message = "Test failure in $currentTestName",
                            rawLine = line
                        )
                    )
                }

                pytestRegex.find(line)?.let { m ->
                    val pyFile = m.groupValues[1]
                    val pyTest = m.groupValues[2]
                    val msg = m.groupValues[3].ifBlank { "Test failed" }
                    diags.add(
                        BuildDiagnostic(
                            filePath = pyFile,
                            line = null,
                            column = null,
                            severity = BuildDiagnostic.Severity.ERROR,
                            stage = "TEST",
                            message = "$pyTest: $msg",
                            rawLine = line
                        )
                    )
                }

                stackTraceRegex.find(line)?.let { m ->
                    if (currentFile == null) {
                        currentFile = m.groupValues[2]
                        currentLine = m.groupValues[3].toIntOrNull()
                    }
                }

                assertionRegex.find(line)?.let { m ->
                    diags.add(
                        BuildDiagnostic(
                            filePath = currentFile,
                            line = currentLine,
                            column = null,
                            severity = BuildDiagnostic.Severity.ERROR,
                            stage = "TEST",
                            message = "AssertionError: ${m.groupValues[1]}",
                            rawLine = line
                        )
                    )
                }

                comparisonRegex.find(line)?.let { m ->
                    diags.add(
                        BuildDiagnostic(
                            filePath = currentFile,
                            line = currentLine,
                            column = null,
                            severity = BuildDiagnostic.Severity.ERROR,
                            stage = "TEST",
                            message = "ComparisonFailure: ${m.groupValues[1]}",
                            rawLine = line
                        )
                    )
                }
            }

            if (diags.isEmpty() && (rawOutput.contains("FAILURE", ignoreCase = true) || rawOutput.contains("FAILED", ignoreCase = true))) {
                diags.add(
                    BuildDiagnostic(
                        filePath = null,
                        line = null,
                        column = null,
                        severity = BuildDiagnostic.Severity.ERROR,
                        stage = "TEST",
                        message = "Test suite execution failed",
                        rawLine = rawOutput.lines().firstOrNull { it.contains("FAILED", ignoreCase = true) || it.contains("FAILURE", ignoreCase = true) } ?: rawOutput.take(120)
                    )
                )
            }

            return diags
        }

        fun fromJson(obj: JSONObject): VerificationRunEntry {
            val diagList = mutableListOf<BuildDiagnostic>()
            val diagArray = obj.optJSONArray("diagnostics")
            if (diagArray != null) {
                for (i in 0 until diagArray.length()) {
                    val dObj = diagArray.optJSONObject(i) ?: continue
                    val sevStr = dObj.optString("severity", "ERROR")
                    val sev = try {
                        BuildDiagnostic.Severity.valueOf(sevStr.uppercase())
                    } catch (_: Exception) {
                        BuildDiagnostic.Severity.ERROR
                    }
                    diagList.add(
                        BuildDiagnostic(
                            filePath = if (dObj.isNull("filePath")) null else dObj.optString("filePath"),
                            line = if (dObj.isNull("line")) null else dObj.optInt("line"),
                            column = if (dObj.isNull("column")) null else dObj.optInt("column"),
                            severity = sev,
                            stage = dObj.optString("stage", "UNKNOWN"),
                            message = dObj.optString("message", ""),
                            rawLine = dObj.optString("rawLine", "")
                        )
                    )
                }
            }

            val typeStr = obj.optString("type", "BUILD")
            val type = try {
                VerificationType.valueOf(typeStr.uppercase())
            } catch (_: Exception) {
                VerificationType.BUILD
            }

            return VerificationRunEntry(
                runId = obj.optString("runId", "run_unknown"),
                type = type,
                commandOrTool = obj.optString("commandOrTool", ""),
                generation = obj.optLong("generation", 0L),
                timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
                isSuccess = obj.optBoolean("isSuccess", false),
                exitCode = if (obj.isNull("exitCode")) null else obj.optInt("exitCode"),
                diagnostics = diagList,
                summary = obj.optString("summary", ""),
                resolvedByRunId = if (obj.isNull("resolvedByRunId")) null else obj.optString("resolvedByRunId"),
                isStale = obj.optBoolean("isStale", false)
            )
        }
    }
}

/**
 * Result bundle computed from the verification ledger for truthful completion reporting.
 */
data class LedgerCompletionStatus(
    val verificationLevel: VerificationLevel,
    val validationPassed: Boolean,
    val remainingErrors: List<String>,
    val checksRun: List<String>,
    val nextActionableStep: String?,
    val hasUnresolvedFailures: Boolean,
    val isStale: Boolean
)

/**
 * Structured, append-only ledger tracking all verification passes and establishing exact causality
 * between failures, repairs, source mutations, and resolution.
 */
class VerificationEvidenceLedger {

    companion object {
        fun parseTestOutput(rawOutput: String): List<BuildDiagnostic> = VerificationRunEntry.parseTestOutput(rawOutput)
    }

    private val entries = CopyOnWriteArrayList<VerificationRunEntry>()
    private val runCounter = AtomicInteger(0)

    /**
     * Records a verification run into the ledger.
     * When [isSuccess] is true, automatically resolves previous unresolved failures of the SAME verification type.
     * Note: A passing BUILD does NOT resolve failing TEST runs, and vice-versa.
     */
    @Synchronized
    fun recordRun(
        type: VerificationType,
        commandOrTool: String,
        generation: Long,
        isSuccess: Boolean,
        exitCode: Int? = null,
        diagnostics: List<BuildDiagnostic> = emptyList(),
        summary: String = "",
        runId: String? = null
    ): VerificationRunEntry {
        val assignedId = runId ?: "verif_${type.name.lowercase()}_${System.currentTimeMillis()}_${runCounter.incrementAndGet()}"

        // Filter out ERROR-level diagnostics if the run is marked successful to prevent contradictory state
        val effectiveDiags = if (isSuccess) {
            diagnostics.filter { it.severity != BuildDiagnostic.Severity.ERROR }
        } else {
            diagnostics
        }

        val entry = VerificationRunEntry(
            runId = assignedId,
            type = type,
            commandOrTool = commandOrTool,
            generation = generation,
            timestampMs = System.currentTimeMillis(),
            isSuccess = isSuccess,
            exitCode = exitCode,
            diagnostics = effectiveDiags,
            summary = summary,
            isStale = false
        )

        // If this run succeeded, resolve previous unresolved failures of the SAME type
        if (isSuccess) {
            for (prev in entries) {
                if (prev.type == type && !prev.isSuccess && prev.resolvedByRunId == null) {
                    prev.resolvedByRunId = assignedId
                }
            }
        }

        entries.add(entry)
        return entry
    }

    /**
     * Marks all runs prior to [newGeneration] as stale.
     * Historical records and diagnostics are preserved; they are not deleted.
     */
    fun onMutationOccurred(newGeneration: Long) {
        for (entry in entries) {
            if (entry.generation < newGeneration) {
                entry.isStale = true
            }
        }
    }

    /**
     * Checks if a run failed when it was originally executed.
     */
    fun didRunFailHistorically(runId: String): Boolean {
        return entries.find { it.runId == runId }?.let { !it.isSuccess } ?: false
    }

    /**
     * Checks if a historical failure was later resolved by a subsequent passing run of the same type.
     */
    fun wasFailureResolved(runId: String): Boolean {
        return entries.find { it.runId == runId }?.isResolvedFailure == true
    }

    /**
     * Checks if a run's verification evidence is still non-stale and applicable to [currentGeneration].
     */
    fun isEvidenceApplicableToCurrentState(runId: String, currentGeneration: Long): Boolean {
        val entry = entries.find { it.runId == runId } ?: return false
        return !entry.isStale && entry.generation == currentGeneration
    }

    /**
     * Retrieves the subsequent run that resolved a historical failure.
     */
    fun getResolvingRun(runId: String): VerificationRunEntry? {
        val entry = entries.find { it.runId == runId } ?: return null
        val resolvingId = entry.resolvedByRunId ?: return null
        return entries.find { it.runId == resolvingId }
    }

    /**
     * Retrieves the most recent active (non-stale) successful run for [type] at [currentGeneration].
     */
    fun getSupportingRun(type: VerificationType, currentGeneration: Long): VerificationRunEntry? {
        return entries.findLast { it.type == type && it.isSuccess && !it.isStale && it.generation == currentGeneration }
    }

    /**
     * Looks up an entry by its run ID.
     */
    fun getEntry(runId: String): VerificationRunEntry? {
        return entries.find { it.runId == runId }
    }

    /**
     * Returns all entries that failed and have not been resolved by a subsequent passing run of the same type.
     */
    fun getUnresolvedFailures(): List<VerificationRunEntry> {
        return entries.filter { it.isUnresolvedFailure }
    }

    /**
     * Returns all diagnostics from unresolved failures, formatted as concise error strings.
     */
    fun getUnresolvedDiagnostics(): List<String> {
        val unresolved = getUnresolvedFailures()
        if (unresolved.isEmpty()) return emptyList()

        val results = mutableListOf<String>()
        for (run in unresolved) {
            if (run.diagnostics.isNotEmpty()) {
                for (d in run.diagnostics) {
                    if (d.severity == BuildDiagnostic.Severity.ERROR) {
                        results.add(d.formatCompact())
                    }
                }
            } else if (run.summary.isNotBlank()) {
                val prefix = when (run.type) {
                    VerificationType.BUILD -> "[Build Error]: "
                    VerificationType.TEST -> "[Test Error]: "
                    VerificationType.LINT -> "[Lint Error]: "
                    VerificationType.CUSTOM -> "[Verification Error]: "
                }
                results.add(prefix + run.summary.take(200))
            } else {
                results.add("[${run.type.displayName} Error]: Exit code ${run.exitCode ?: -1}")
            }
        }
        return results
    }

    /**
     * Checks if there is an active (non-stale) successful verification run of the specified [type] at [currentGeneration].
     */
    fun hasActiveSuccess(type: VerificationType, currentGeneration: Long): Boolean {
        return entries.any {
            it.type == type && it.isSuccess && it.generation == currentGeneration && !it.isStale
        }
    }

    /**
     * Returns true if any verification run of the specified [type] was executed at all.
     */
    fun hasExecuted(type: VerificationType): Boolean {
        return entries.any { it.type == type }
    }

    /**
     * Returns the list of all entries recorded in this session.
     */
    fun getAllEntries(): List<VerificationRunEntry> = entries.toList()

    /**
     * Computes the truthful completion report status from the ledger evidence.
     */
    fun computeCompletionStatus(
        currentGeneration: Long,
        hadMutations: Boolean,
        rolledBack: Boolean
    ): LedgerCompletionStatus {
        val unresolvedFailures = getUnresolvedFailures()
        val unresolvedDiagnostics = getUnresolvedDiagnostics()
        val hasFailedTests = unresolvedFailures.any { it.type == VerificationType.TEST }
        val hasFailedBuilds = unresolvedFailures.any { it.type == VerificationType.BUILD }

        val activeBuildSuccess = hasActiveSuccess(VerificationType.BUILD, currentGeneration)
        val activeTestSuccess = hasActiveSuccess(VerificationType.TEST, currentGeneration)

        val anyBuildRun = hasExecuted(VerificationType.BUILD)
        val anyTestRun = hasExecuted(VerificationType.TEST)

        val checks = mutableListOf<String>()
        for (e in entries) {
            val statusStr = if (e.isSuccess) "PASSED" else "FAILED"
            val staleStr = if (e.isStale) " (stale)" else ""
            checks.add("${e.type.displayName}: $statusStr [${e.runId}]$staleStr")
        }

        val verificationLevel = when {
            rolledBack -> VerificationLevel.ROLLED_BACK
            unresolvedDiagnostics.isNotEmpty() -> VerificationLevel.FAILED_WITH_ERRORS
            hasFailedTests || hasFailedBuilds -> VerificationLevel.FAILED_WITH_ERRORS
            activeBuildSuccess && activeTestSuccess -> VerificationLevel.BUILT_AND_VERIFIED
            activeBuildSuccess -> VerificationLevel.BUILT_AND_VERIFIED
            activeTestSuccess -> VerificationLevel.TESTS_PASSED
            hadMutations -> VerificationLevel.IMPLEMENTED_UNVERIFIED
            else -> VerificationLevel.NOT_VERIFIED
        }

        val validationPassed = verificationLevel in listOf(
            VerificationLevel.BUILT_AND_VERIFIED,
            VerificationLevel.TESTS_PASSED,
            VerificationLevel.STATICALLY_VERIFIED,
            VerificationLevel.NOT_VERIFIED,
            VerificationLevel.IMPLEMENTED_UNVERIFIED
        ) && unresolvedDiagnostics.isEmpty() && !rolledBack && !hasFailedTests && !hasFailedBuilds

        val nextActionableStep = if (!validationPassed) {
            when {
                hasFailedTests -> "Fix failing test assertions and re-run tests."
                hasFailedBuilds -> "Resolve compilation/build errors."
                hadMutations && !activeBuildSuccess -> "Build or run tests to verify recent modifications."
                else -> "Address remaining error(s) and retry."
            }
        } else if (hadMutations && !activeBuildSuccess && !activeTestSuccess) {
            "Build or run tests to verify recent modifications."
        } else null

        return LedgerCompletionStatus(
            verificationLevel = verificationLevel,
            validationPassed = validationPassed,
            remainingErrors = unresolvedDiagnostics,
            checksRun = checks,
            nextActionableStep = nextActionableStep,
            hasUnresolvedFailures = unresolvedFailures.isNotEmpty(),
            isStale = hadMutations && !activeBuildSuccess && !activeTestSuccess
        )
    }

    /**
     * Clears all entries from the ledger.
     */
    fun clear() {
        entries.clear()
        runCounter.set(0)
    }
}
