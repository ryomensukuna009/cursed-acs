package com.tom.rv2ide.artificial.coding

import com.tom.rv2ide.artificial.chat.AgentCompletionReport
import com.tom.rv2ide.artificial.chat.VerificationLevel
import com.tom.rv2ide.artificial.transport.TransportCancellationToken
import com.tom.rv2ide.builder.BuildExecutionResult
import com.tom.rv2ide.builder.SharedBuildOrchestrator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

enum class AutonomousExecutionState(val displayName: String) {
    IDLE("Idle"),
    INSPECTING_PROJECT("Inspecting project"),
    ANALYZING_TASK("Analyzing task"),
    PLANNING_CHANGES("Planning changes"),
    PREPARING_REPAIR("Preparing repair"),
    VALIDATING_PATCHES("Validating patches"),
    APPLYING_CHANGES("Applying changes"),
    RUNNING_BUILD("Running build"),
    COLLECTING_DIAGNOSTICS("Collecting diagnostics"),
    ANALYZING_FAILURES("Analyzing failures"),
    REPAIRING_ERRORS("Repairing errors"),
    VERIFYING_FIXES("Verifying fixes"),
    BUILD_SUCCEEDED("Build succeeded"),
    BLOCKED("Blocked"),
    CANCELLED("Cancelled")
}

interface AutonomousStateListener {
    fun onStateChanged(state: AutonomousExecutionState, description: String)
}

data class RepairWaveRecord(
    val waveIndex: Int,
    val modifiedFiles: List<String>,
    val errorFingerprint: String,
    val buildSucceeded: Boolean,
    val remainingErrorsCount: Int,
    val primaryRootCauses: List<String>,
    val newErrorsCount: Int = 0,
    val resolvedErrorsCount: Int = 0
)

data class AutonomousExecutionSummary(
    val finalState: AutonomousExecutionState,
    val isSuccess: Boolean,
    val wavesExecuted: Int,
    val totalFilesModified: List<String>,
    val lastBuildResult: BuildExecutionResult?,
    val stagnationDetected: Boolean,
    val failureBlockerMessage: String?,
    val waveRecords: List<RepairWaveRecord>,
    val report: AgentCompletionReport,
    val sessionId: String = ""
)

/**
 * Coordinates the autonomous Build–Diagnose–Repair loop across a formal state machine:
 *
 * IDLE -> INSPECTING_PROJECT -> ANALYZING_TASK -> PLANNING_CHANGES ->
 * VALIDATING_PATCHES -> APPLYING_CHANGES -> RUNNING_BUILD ->
 * COLLECTING_DIAGNOSTICS -> ANALYZING_FAILURES -> REPAIRING_ERRORS ->
 * VERIFYING_FIXES -> BUILD_SUCCEEDED / BLOCKED / CANCELLED.
 *
 * Implements:
 * 1. Zero simulated builds (dispatches via SharedBuildOrchestrator).
 * 2. Unlimited multi-file updates (3, 5, 10, 20+ files supported atomically).
 * 3. Continuous repair loop with NO arbitrary 2- or 3-attempt cap.
 * 4. Rigorous error-fingerprint stagnation detection.
 */
object AutonomousExecutionCoordinator {

    private val log = LoggerFactory.getLogger(AutonomousExecutionCoordinator::class.java)

    private val currentStateRef = AtomicReference(AutonomousExecutionState.IDLE)
    private val listeners = CopyOnWriteArrayList<AutonomousStateListener>()

    fun getCurrentState(): AutonomousExecutionState = currentStateRef.get()

    fun addListener(listener: AutonomousStateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: AutonomousStateListener) {
        listeners.remove(listener)
    }

    fun transitionTo(newState: AutonomousExecutionState, description: String = "") {
        currentStateRef.set(newState)
        log.info("[AutonomousState] -> {} : {}", newState.name, description)
        for (l in listeners) {
            try {
                l.onStateChanged(newState, description)
            } catch (e: Exception) {
                log.warn("Error in AutonomousStateListener: {}", e.message)
            }
        }
    }

    /**
     * Executes the autonomous engineering loop.
     */
    suspend fun runAutonomousEngineeringLoop(
        projectRoot: File,
        userGoal: String,
        initialCodeCandidate: String?,
        codeGenerator: suspend (prompt: String) -> String?,
        onFileModifying: ((path: String, name: String) -> Unit)? = null,
        onFileModified: ((path: String, name: String, success: Boolean) -> Unit)? = null,
        maxWaves: Int = 12,
        cancellationToken: TransportCancellationToken = TransportCancellationToken(),
        preModifiedFiles: List<String> = emptyList(),
        existingSessionId: String? = null
    ): AutonomousExecutionSummary = withContext(Dispatchers.IO) {
        val sessionId = existingSessionId ?: java.util.UUID.randomUUID().toString()
        if (existingSessionId == null) {
            AutonomousRollbackManager.startSession(sessionId, projectRoot)
        }

        val waveRecords = mutableListOf<RepairWaveRecord>()
        val allModifiedFiles = LinkedHashSet<String>()
        allModifiedFiles.addAll(preModifiedFiles)
        val seenFingerprints = mutableMapOf<String, Int>()
        var stagnationDetected = false
        var currentCandidate = initialCodeCandidate
        var waveIndex = 0
        var lastBuildResult: BuildExecutionResult? = null
        var lastVerification: IterativeRepairCoordinator.PostEditVerificationResult? = null
        var blockerMessage: String? = null
        var baselineDiagnostics: List<BuildDiagnostic> = emptyList()
        var previousWaveDiagnostics: List<BuildDiagnostic> = emptyList()

        try {
            transitionTo(AutonomousExecutionState.INSPECTING_PROJECT, "Inspecting project structure for: $userGoal")
            val inspection = ProjectDependencyInspector.inspectProjectForPrompt(projectRoot, userGoal)

            // Step 1: If no initial code was provided, analyze task & generate initial patch with real compiler errors
            if (currentCandidate.isNullOrBlank()) {
                if (cancellationToken.isCancelled()) throw CancellationException("Cancelled by user")

                // If preModifiedFiles is not empty, files were just modified on disk, so previous build result is stale.
                val isStale = preModifiedFiles.isNotEmpty()
                var initialDiagnostics = if (isStale) null else SharedBuildOrchestrator.getLastResult()
                if (initialDiagnostics == null || initialDiagnostics.isSuccess) {
                    val buildMsg = if (preModifiedFiles.isNotEmpty()) {
                        "Verifying build after file modifications across ${preModifiedFiles.size} file(s)..."
                    } else {
                        "Running initial build to check for compiler errors..."
                    }
                    transitionTo(AutonomousExecutionState.RUNNING_BUILD, buildMsg)
                    val probeBuild = SharedBuildOrchestrator.executeBuild(projectDir = projectRoot)
                    lastBuildResult = probeBuild
                    if (probeBuild.isSuccess) {
                        val successDesc = if (preModifiedFiles.isNotEmpty()) {
                            "Application build SUCCEEDED after applying modifications! Artifact: ${probeBuild.apkFile?.name}"
                        } else {
                            "Project already builds successfully! Artifact: ${probeBuild.apkFile?.name}"
                        }
                        transitionTo(AutonomousExecutionState.BUILD_SUCCEEDED, successDesc)
                        val checks = mutableListOf<String>()
                        if (preModifiedFiles.isNotEmpty()) {
                            checks.add("File change-set transaction applied")
                        }
                        checks.add("Actual build execution (${probeBuild.buildMode})")
                        val report = AgentCompletionReport(
                            goalOrRootCause = userGoal.take(140),
                            filesModified = allModifiedFiles.toList(),
                            mainChangesSummary = if (preModifiedFiles.isNotEmpty()) {
                                "Build succeeded after applying changes across ${allModifiedFiles.size} file(s). Artifact: ${probeBuild.apkFile?.name ?: "APK"}"
                            } else {
                                "Initial build succeeded without modifications. Artifact: ${probeBuild.apkFile?.name ?: "APK"}"
                            },
                            validationChecksRun = checks,
                            validationPassed = true,
                            verificationLevel = VerificationLevel.BUILT_AND_VERIFIED,
                            repairAttemptsUsed = 0,
                            remainingErrors = emptyList(),
                            nextActionableStep = null,
                            rollbackSessionId = sessionId
                        )
                        return@withContext AutonomousExecutionSummary(
                            finalState = AutonomousExecutionState.BUILD_SUCCEEDED,
                            isSuccess = true,
                            wavesExecuted = 0,
                            totalFilesModified = allModifiedFiles.toList(),
                            lastBuildResult = probeBuild,
                            stagnationDetected = false,
                            failureBlockerMessage = null,
                            waveRecords = emptyList(),
                            report = report,
                            sessionId = sessionId
                        )
                    }
                    initialDiagnostics = probeBuild
                } else {
                    lastBuildResult = initialDiagnostics
                }

                transitionTo(AutonomousExecutionState.ANALYZING_TASK, "Analyzing compiler failure and planning repairs...")
                val rawDiag = initialDiagnostics.diagnostics.ifEmpty {
                    StructuredDiagnosticCollector.parseBuildOutput(initialDiagnostics.rawLog, projectRoot)
                }
                baselineDiagnostics = rawDiag
                previousWaveDiagnostics = rawDiag
                val diagSnippet = if (rawDiag.isNotEmpty()) {
                    rawDiag.take(15).joinToString("\n") { it.formatCompact() }
                } else {
                    initialDiagnostics.rawLog.lines().takeLast(30).joinToString("\n")
                }

                val initialSourceSnippets = StructuredDiagnosticCollector.buildAugmentedErrorContext(
                    diagnostics = rawDiag.take(4),
                    projectRoot = projectRoot
                )
                val missingSymbolHints = AcsRepoMap.resolveDiagnosticMissingSymbols(
                    projectRoot = projectRoot,
                    diagnostics = rawDiag
                )

                val taskPrompt = buildString {
                    appendLine(inspection.contextPromptBlock)
                    appendLine()
                    appendLine("User Goal: $userGoal")
                    appendLine()
                    appendLine("=== Current Build Diagnostics (${initialDiagnostics.buildMode}) ===")
                    appendLine(diagSnippet)
                    appendLine()
                    if (initialSourceSnippets.isNotBlank()) {
                        appendLine("=== RELEVANT SOURCE CODE SURROUNDING ERRORS ===")
                        appendLine(initialSourceSnippets)
                        appendLine()
                    }
                    if (missingSymbolHints.isNotEmpty()) {
                        appendLine("=== RELATED SYMBOL DEFINITIONS DISCOVERED IN PROJECT ===")
                        missingSymbolHints.forEach { appendLine(it) }
                        appendLine("Tip: Verify if the defining header needs to be included, imported, or if parameter types match.")
                        appendLine()
                    }
                    appendLine("Instructions:")
                    appendLine("Diagnose and repair the build errors shown above. Provide full modified files using either format:")
                    appendLine("[FILE: relative/path/to/file]")
                    appendLine("// complete file contents here")
                    appendLine("[/FILE]")
                    appendLine()
                    appendLine("OR:")
                    appendLine("FILE_TO_MODIFY: relative/path/to/file")
                    appendLine("CODE_START")
                    appendLine("// complete file contents here")
                    appendLine("CODE_END")
                }
                TransactionalFileCoordinator.clearLastAppliedEdits()
                currentCandidate = codeGenerator(taskPrompt)
            }

            var parseRetries = 0

            // Outer loop: Continues as long as build fails, errors are changing, and no stagnation occurs
            while (waveIndex < maxWaves) {
                if (cancellationToken.isCancelled()) {
                    transitionTo(AutonomousExecutionState.CANCELLED, "Operation cancelled by user.")
                    break
                }

                val candidateText = currentCandidate ?: break
                transitionTo(AutonomousExecutionState.VALIDATING_PATCHES, "Validating structural and syntax safety...")

                val changeValidation = TransactionalFileCoordinator.parseProposedChangeSet(candidateText, projectRoot)
                val appliedEditsForWave: List<CoordinatedFileEdit>

                if (changeValidation.isValid && changeValidation.edits.isNotEmpty()) {
                    parseRetries = 0
                    waveIndex++

                    transitionTo(
                        if (waveIndex == 1) AutonomousExecutionState.PLANNING_CHANGES else AutonomousExecutionState.PREPARING_REPAIR,
                        "Executing repair wave $waveIndex (applying changes & building)..."
                    )

                    // Policy Gate Check before applying changes
                    var allEditsApproved = true
                    for (edit in changeValidation.edits) {
                        val approval = com.tom.rv2ide.artificial.policy.ActionPolicyGate.checkAndRequestApproval(
                            actionType = if (edit.isNewFile) com.tom.rv2ide.artificial.policy.ActionType.CREATE_FILE else com.tom.rv2ide.artificial.policy.ActionType.MODIFY_FILE,
                            targetDetail = edit.targetFile.absolutePath,
                            projectRoot = projectRoot,
                            proposedDiff = edit.finalContent
                        )
                        if (approval == com.tom.rv2ide.artificial.policy.UserApprovalResult.REFUSE ||
                            approval == com.tom.rv2ide.artificial.policy.UserApprovalResult.DENIED_BY_POLICY) {
                            allEditsApproved = false
                            transitionTo(AutonomousExecutionState.BLOCKED, "Action refused by user: modification to ${edit.targetFile.name} was denied.")
                            blockerMessage = "User denied file modification for ${edit.targetFile.name}."
                            break
                        }
                    }
                    if (!allEditsApproved) {
                        break
                    }

                    // Step 2: Apply coordinated changes atomically across any number of files (no 2-file limit)
                    transitionTo(AutonomousExecutionState.APPLYING_CHANGES, "Applying ${changeValidation.edits.size} file modification(s)...")
                    val commitResult = TransactionalFileCoordinator.applyChangeSetTransactionally(
                        edits = changeValidation.edits,
                        sessionId = sessionId,
                        projectRoot = projectRoot
                    ) { edit ->
                        val fileName = edit.targetFile.name
                        onFileModifying?.invoke(edit.targetFile.absolutePath, fileName)
                        onFileModified?.invoke(edit.targetFile.absolutePath, fileName, true)
                    }

                    if (!commitResult.success) {
                        transitionTo(AutonomousExecutionState.BLOCKED, "Failed to apply atomic file batch: ${commitResult.errorMessages.joinToString("; ")}")
                        blockerMessage = "Atomic write transaction failed: ${commitResult.errorMessages.joinToString("; ")}"
                        break
                    }
                    appliedEditsForWave = commitResult.appliedEdits
                } else {
                    val recentEdits = TransactionalFileCoordinator.getLastAppliedEdits()
                    if (recentEdits.isNotEmpty()) {
                        parseRetries = 0
                        waveIndex++

                        transitionTo(
                            if (waveIndex == 1) AutonomousExecutionState.PLANNING_CHANGES else AutonomousExecutionState.PREPARING_REPAIR,
                            "Executing repair wave $waveIndex (tool-applied changes & building)..."
                        )
                        transitionTo(AutonomousExecutionState.APPLYING_CHANGES, "Changes committed via tools across ${recentEdits.size} file(s)...")
                        recentEdits.forEach { edit ->
                            val fileName = edit.targetFile.name
                            onFileModifying?.invoke(edit.targetFile.absolutePath, fileName)
                            onFileModified?.invoke(edit.targetFile.absolutePath, fileName, true)
                        }
                        appliedEditsForWave = recentEdits
                    } else {
                        log.warn("Validation failed for wave candidate: {}", changeValidation.errors)
                        if (parseRetries < 2) {
                            parseRetries++
                            transitionTo(AutonomousExecutionState.PLANNING_CHANGES, "Prompting model to reformat response into valid file modifications (attempt $parseRetries/2)...")
                            val repairPrompt = buildString {
                                appendLine("Your previous response did not contain any valid file modifications.")
                                appendLine("Validation errors: ${changeValidation.errors.joinToString("; ").ifBlank { "No file modification blocks found." }}")
                                appendLine("Please output the required file modifications using either format:")
                                appendLine("[FILE: relative/path/to/file]")
                                appendLine("// complete updated file content")
                                appendLine("[/FILE]")
                                appendLine("OR:")
                                appendLine("FILE_TO_MODIFY: relative/path/to/file")
                                appendLine("CODE_START")
                                appendLine("// complete updated file content")
                                appendLine("CODE_END")
                            }
                            TransactionalFileCoordinator.clearLastAppliedEdits()
                            currentCandidate = codeGenerator(repairPrompt)
                            continue
                        } else {
                            transitionTo(AutonomousExecutionState.BLOCKED, "AI model did not return file modifications in valid format after retries.")
                            blockerMessage = "AI model did not provide valid file modifications."
                            break
                        }
                    }
                }

                appliedEditsForWave.forEach { allModifiedFiles.add(it.relativePath) }

                // Policy Gate Check before running build
                val buildApproval = com.tom.rv2ide.artificial.policy.ActionPolicyGate.checkAndRequestApproval(
                    actionType = com.tom.rv2ide.artificial.policy.ActionType.EXECUTE_BUILD,
                    targetDetail = "Run build on ${projectRoot.name} (${SharedBuildOrchestrator.getCurrentBuildMode()})",
                    projectRoot = projectRoot
                )
                if (buildApproval == com.tom.rv2ide.artificial.policy.UserApprovalResult.REFUSE ||
                    buildApproval == com.tom.rv2ide.artificial.policy.UserApprovalResult.DENIED_BY_POLICY) {
                    transitionTo(AutonomousExecutionState.IDLE, "All file modifications applied. Ready for manual build and testing.")
                    blockerMessage = "All file changes have been applied successfully. Automatic build was skipped per your request. Please build and test your project."
                    break
                }

                // Step 3: Run real build through SharedBuildOrchestrator
                transitionTo(AutonomousExecutionState.RUNNING_BUILD, "Running actual build (${SharedBuildOrchestrator.getCurrentBuildMode()})...")
                val buildResult = SharedBuildOrchestrator.executeBuild(projectDir = projectRoot)
                lastBuildResult = buildResult

                if (buildResult.isSuccess) {
                    transitionTo(AutonomousExecutionState.VERIFYING_FIXES, "Verifying build artifact and final consistency...")
                    lastVerification = IterativeRepairCoordinator.verifyAppliedEdits(
                        projectRoot = projectRoot,
                        appliedEdits = appliedEditsForWave,
                        compilerOutput = buildResult.rawLog
                    )
                    transitionTo(AutonomousExecutionState.BUILD_SUCCEEDED, "Application build SUCCEEDED! Artifact: ${buildResult.apkFile?.name}")
                    val resolvedCount = previousWaveDiagnostics.count { it.severity == BuildDiagnostic.Severity.ERROR }
                    waveRecords.add(
                        RepairWaveRecord(
                            waveIndex = waveIndex,
                            modifiedFiles = appliedEditsForWave.map { it.relativePath },
                            errorFingerprint = "SUCCESS",
                            buildSucceeded = true,
                            remainingErrorsCount = 0,
                            primaryRootCauses = emptyList(),
                            newErrorsCount = 0,
                            resolvedErrorsCount = resolvedCount
                        )
                    )
                    break
                }

                // Step 4: Build failed - collect & group diagnostics
                transitionTo(AutonomousExecutionState.COLLECTING_DIAGNOSTICS, "Collecting compiler diagnostics...")
                val diagnostics = buildResult.diagnostics.ifEmpty {
                    StructuredDiagnosticCollector.parseBuildOutput(buildResult.rawLog, projectRoot)
                }

                if (baselineDiagnostics.isEmpty()) {
                    baselineDiagnostics = diagnostics
                }

                transitionTo(AutonomousExecutionState.ANALYZING_FAILURES, "Analyzing root causes vs cascading errors...")
                val grouped = StructuredDiagnosticCollector.groupCascadingDiagnostics(diagnostics)
                val fingerprint = StructuredDiagnosticCollector.computeErrorFingerprint(diagnostics)

                val delta = StructuredDiagnosticCollector.computeDiagnosticDelta(
                    baselineDiagnostics = previousWaveDiagnostics.ifEmpty { baselineDiagnostics },
                    currentDiagnostics = diagnostics,
                    projectRoot = projectRoot
                )

                val count = seenFingerprints.getOrDefault(fingerprint, 0)
                seenFingerprints[fingerprint] = count + 1

                val newErrorsCount = delta.newDiagnostics.count { it.severity == BuildDiagnostic.Severity.ERROR }
                val resolvedErrorsCount = delta.resolvedDiagnostics.count { it.severity == BuildDiagnostic.Severity.ERROR }

                waveRecords.add(
                    RepairWaveRecord(
                        waveIndex = waveIndex,
                        modifiedFiles = appliedEditsForWave.map { it.relativePath },
                        errorFingerprint = fingerprint,
                        buildSucceeded = false,
                        remainingErrorsCount = diagnostics.size,
                        primaryRootCauses = grouped.rootCauseDiagnostics.map { it.formatCompact() },
                        newErrorsCount = newErrorsCount,
                        resolvedErrorsCount = resolvedErrorsCount
                    )
                )

                // Update previous diagnostics for next wave delta comparison
                previousWaveDiagnostics = diagnostics

                // Stagnation Detection: Identical error fingerprint repeated across different repair waves
                if (count >= 1) {
                    stagnationDetected = true
                    val msg = "Stagnation detected: Build produced identical compiler errors after wave $waveIndex."
                    log.warn(msg)
                    transitionTo(AutonomousExecutionState.BLOCKED, msg)
                    blockerMessage = "Persistent compiler root cause could not be resolved automatically: " +
                        (grouped.rootCauseDiagnostics.firstOrNull()?.formatCompact() ?: buildResult.errorMessage ?: "Unknown build error")
                    break
                }

                // Step 5: Construct next targeted repair prompt for all primary root causes
                transitionTo(AutonomousExecutionState.REPAIRING_ERRORS, "Preparing coordinated repair for ${grouped.rootCauseDiagnostics.size} root-cause error(s)...")
                val waveSourceWindows = StructuredDiagnosticCollector.buildAugmentedErrorContext(
                    diagnostics = grouped.rootCauseDiagnostics.take(4),
                    projectRoot = projectRoot
                )
                val waveMissingSymbolHints = AcsRepoMap.resolveDiagnosticMissingSymbols(
                    projectRoot = projectRoot,
                    diagnostics = grouped.rootCauseDiagnostics
                )
                val repairPrompt = buildString {
                    appendLine("=== AUTONOMOUS BUILD–DIAGNOSE–REPAIR (Wave $waveIndex) ===")
                    appendLine("Failed Build Stage: ${buildResult.failedStage ?: "COMPILER"}")
                    appendLine("Project Mode: ${buildResult.buildMode}")
                    appendLine()
                    appendLine(delta.summaryPromptBlock)
                    appendLine()
                    if (grouped.rootCauseDiagnostics.isNotEmpty()) {
                        appendLine("=== ROOT-CAUSE PRIORITY FOCUS ===")
                        appendLine(grouped.summaryPromptBlock)
                        appendLine()
                    }
                    if (waveSourceWindows.isNotBlank()) {
                        appendLine("=== RELEVANT SOURCE CODE SURROUNDING ERRORS ===")
                        appendLine(waveSourceWindows)
                        appendLine()
                    }
                    if (waveMissingSymbolHints.isNotEmpty()) {
                        appendLine("=== RELATED SYMBOL DEFINITIONS DISCOVERED IN PROJECT ===")
                        waveMissingSymbolHints.forEach { appendLine(it) }
                        appendLine("Tip: Verify if the defining header needs to be included, imported, or if parameter types match.")
                        appendLine()
                    }
                    appendLine("INSTRUCTIONS FOR NEXT REPAIR WAVE:")
                    if (newErrorsCount > 0) {
                        appendLine("1. CRITICAL: Revert or fix new compiler errors introduced by your last edit.")
                        appendLine("2. Address remaining persistent root-cause errors.")
                    } else {
                        appendLine("1. Fix the primary root-cause errors listed above.")
                    }
                    appendLine("3. You can modify as many files as necessary (e.g. 3, 5, 10+ files).")
                    appendLine("4. Use the FILE_TO_MODIFY / FILE_TO_CREATE / FILE_TO_PATCH format.")
                    appendLine("5. Output complete, working code without truncation comments.")
                }

                TransactionalFileCoordinator.clearLastAppliedEdits()
                currentCandidate = codeGenerator(repairPrompt)
                if (currentCandidate.isNullOrBlank()) {
                    transitionTo(AutonomousExecutionState.BLOCKED, "AI agent stopped without generating a repair candidate.")
                    blockerMessage = "AI model did not return a valid repair candidate."
                    break
                }
            }

            if (currentStateRef.get() == AutonomousExecutionState.RUNNING_BUILD ||
                currentStateRef.get() == AutonomousExecutionState.REPAIRING_ERRORS
            ) {
                if (lastBuildResult?.isSuccess != true) {
                    transitionTo(AutonomousExecutionState.BLOCKED, "Exceeded maximum allowed repair waves ($maxWaves) without build success.")
                    blockerMessage = "Exceeded $maxWaves repair waves without complete build success."
                }
            }

            val finalState = currentStateRef.get()
            val isSuccess = finalState == AutonomousExecutionState.BUILD_SUCCEEDED

            val checksRun = mutableListOf<String>()
            if (allModifiedFiles.isNotEmpty()) {
                checksRun.add("Structural syntax validation")
                checksRun.add("Atomic transactional write")
            }
            if (lastBuildResult != null) {
                checksRun.add("Actual build execution (${lastBuildResult.buildMode})")
                checksRun.add("Root-cause diagnostic grouping")
            }

            val report = AgentCompletionReport(
                goalOrRootCause = userGoal.take(140),
                filesModified = allModifiedFiles.toList(),
                mainChangesSummary = if (isSuccess) {
                    "Build succeeded after $waveIndex repair wave(s). Artifact: ${lastBuildResult?.apkFile?.name ?: "APK"}"
                } else {
                    "Autonomous loop stopped with state: ${finalState.displayName} (${waveIndex} repair wave(s) executed)"
                },
                validationChecksRun = checksRun,
                validationPassed = isSuccess,
                verificationLevel = if (isSuccess) VerificationLevel.BUILT_AND_VERIFIED else VerificationLevel.FAILED_WITH_ERRORS,
                repairAttemptsUsed = waveIndex,
                remainingErrors = if (isSuccess) emptyList() else lastBuildResult?.diagnostics?.map { it.formatCompact() } ?: emptyList(),
                nextActionableStep = if (!isSuccess) blockerMessage ?: "Review compiler diagnostics." else null,
                rollbackSessionId = sessionId
            )

            AutonomousExecutionSummary(
                finalState = finalState,
                isSuccess = isSuccess,
                wavesExecuted = waveIndex,
                totalFilesModified = allModifiedFiles.toList(),
                lastBuildResult = lastBuildResult,
                stagnationDetected = stagnationDetected,
                failureBlockerMessage = blockerMessage,
                waveRecords = waveRecords,
                report = report,
                sessionId = sessionId
            )
        } catch (e: CancellationException) {
            transitionTo(AutonomousExecutionState.CANCELLED, "Autonomous repair cancelled.")
            val report = AgentCompletionReport(
                goalOrRootCause = userGoal.take(140),
                filesModified = allModifiedFiles.toList(),
                mainChangesSummary = "Autonomous repair cancelled by user.",
                validationChecksRun = emptyList(),
                validationPassed = false,
                verificationLevel = VerificationLevel.ROLLED_BACK,
                repairAttemptsUsed = waveIndex,
                remainingErrors = emptyList(),
                nextActionableStep = "Operation cancelled.",
                rollbackSessionId = sessionId
            )
            AutonomousExecutionSummary(
                finalState = AutonomousExecutionState.CANCELLED,
                isSuccess = false,
                wavesExecuted = waveIndex,
                totalFilesModified = allModifiedFiles.toList(),
                lastBuildResult = lastBuildResult,
                stagnationDetected = false,
                failureBlockerMessage = "Operation cancelled by user",
                waveRecords = waveRecords,
                report = report,
                sessionId = sessionId
            )
        }
    }
}
