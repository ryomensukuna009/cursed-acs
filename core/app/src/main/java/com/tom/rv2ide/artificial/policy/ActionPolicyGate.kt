package com.tom.rv2ide.artificial.policy



import android.app.Activity

import android.content.Context

import android.graphics.Color

import android.view.LayoutInflater

import android.view.View

import android.widget.ScrollView

import android.widget.TextView

import androidx.preference.PreferenceManager

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import com.tom.rv2ide.R

import com.tom.rv2ide.app.BaseApplication

import com.tom.rv2ide.artificial.rules.WritingRules

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.suspendCancellableCoroutine

import kotlinx.coroutines.withContext

import org.slf4j.LoggerFactory

import java.io.File

import java.util.concurrent.ConcurrentHashMap

import kotlin.coroutines.resume

import android.graphics.drawable.GradientDrawable

import android.util.TypedValue

import android.view.ViewGroup

import com.tom.rv2ide.artificial.coding.CoordinatedFileEdit

import com.tom.rv2ide.artificial.diff.DiffEngine

import com.tom.rv2ide.artificial.diff.DiffVisualFormatter



enum class ActionType(val displayName: String) {

    MODIFY_FILE("Modify File"),

    CREATE_FILE("Create File"),

    DELETE_FILE("Delete File"),

    EXECUTE_COMMAND("Run Terminal Command"),

    EXECUTE_BUILD("Execute Project Build"),

    RESOLVE_IMPORT("Resolve & Insert Import"),

    GIT_OPERATION("Git Operation"),

    ROLLBACK_BATCH("Rollback Changes")

}



enum class ActionRiskLevel(val displayName: String) {

    READ_ONLY("READ ONLY"),

    SAFE_PROJECT_MUTATION("PROJECT MUTATION"),

    BUILD_OR_TEST("BUILD / TEST"),

    COMMAND_NON_DESTRUCTIVE("COMMAND"),

    COMMAND_DESTRUCTIVE("DESTRUCTIVE COMMAND"),

    DESTRUCTIVE_FILE_OP("DESTRUCTIVE FILE OP"),

    SENSITIVE_OR_OUT_OF_WORKSPACE("OUT OF WORKSPACE")

}



enum class UserApprovalResult {

    ALLOW_ONCE,

    ALLOW_ALWAYS,

    REFUSE,

    DENIED_BY_POLICY

}



data class PolicyEvaluation(

    val allowed: Boolean,

    val requiresPrompt: Boolean,

    val riskLevel: ActionRiskLevel,

    val reason: String

)



data class ScopedApprovalRule(

    val projectPath: String,

    val actionType: String,

    val resourcePattern: String

) {

    fun matches(targetProjectPath: String, targetActionType: ActionType, targetResource: String): Boolean {

        if (!projectPath.equals(targetProjectPath, ignoreCase = true) && projectPath != "*") {

            return false

        }

        if (!actionType.equals(targetActionType.name, ignoreCase = true) && actionType != "*") {

            return false

        }

        if (resourcePattern == "*" || resourcePattern.isBlank()) {

            return true

        }

        return try {

            val regex = Regex(resourcePattern)

            regex.containsMatchIn(targetResource)

        } catch (_: Exception) {

            targetResource.contains(resourcePattern, ignoreCase = true)

        }

    }



    fun serialize(): String = "$projectPath|$actionType|$resourcePattern"



    companion object {

        fun deserialize(serialized: String): ScopedApprovalRule? {

            val parts = serialized.split("|")

            if (parts.size < 3) return null

            return ScopedApprovalRule(parts[0], parts[1], parts[2])

        }

    }

}



object ActionPolicyGate {



    const val PREF_PERM_FILES = "pref_agent_permission_files"

    const val PREF_PERM_TERMINAL = "pref_agent_permission_terminal"

    const val PREF_PERM_BUILD = "pref_agent_permission_build"



    const val PERM_ASK_EVERY_TIME = "ASK_EVERY_TIME"

    const val PERM_ALWAYS_APPROVE = "ALWAYS_APPROVE"



    fun getFilePermission(context: Context): String {

        val sp = context.getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)

        return sp.getString(PREF_PERM_FILES, PERM_ASK_EVERY_TIME) ?: PERM_ASK_EVERY_TIME

    }



    fun setFilePermission(context: Context, value: String) {

        context.getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)

            .edit().putString(PREF_PERM_FILES, value).apply()

        if (value == PERM_ASK_EVERY_TIME) {

            resetAlwaysApprovedRules(context, ActionType.MODIFY_FILE)

        }

    }



    fun getTerminalPermission(context: Context): String {

        val sp = context.getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)

        return sp.getString(PREF_PERM_TERMINAL, PERM_ASK_EVERY_TIME) ?: PERM_ASK_EVERY_TIME

    }



    fun setTerminalPermission(context: Context, value: String) {

        context.getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)

            .edit().putString(PREF_PERM_TERMINAL, value).apply()

        if (value == PERM_ASK_EVERY_TIME) {

            resetAlwaysApprovedRules(context, ActionType.EXECUTE_COMMAND)

        }

    }



    fun getBuildPermission(context: Context): String {

        val sp = context.getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)

        return sp.getString(PREF_PERM_BUILD, PERM_ASK_EVERY_TIME) ?: PERM_ASK_EVERY_TIME

    }



    fun setBuildPermission(context: Context, value: String) {

        context.getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)

            .edit().putString(PREF_PERM_BUILD, value).apply()

        if (value == PERM_ASK_EVERY_TIME) {

            resetAlwaysApprovedRules(context, ActionType.EXECUTE_BUILD)

        }

    }





    private val log = LoggerFactory.getLogger(ActionPolicyGate::class.java)



    private const val PREF_KEY_SCOPED_APPROVAL_RULES = "pref_ai_scoped_approval_rules"



    // In-memory cache of one-shot approvals for the active IDE session

    private val sessionApprovedOnce = ConcurrentHashMap.newKeySet<String>()



    fun clearSessionApprovals() {

        sessionApprovedOnce.clear()

    }



    fun grantSessionApproval(actionType: ActionType, targetDetail: String) {

        sessionApprovedOnce.add("${actionType.name}:$targetDetail")

    }



    var onHoldStateChanged: ((isHeld: Boolean, resumeAction: (() -> Unit)?) -> Unit)? = null

    private var activeHoldResume: (() -> Unit)? = null

    private var activeHoldCancel: (() -> Unit)? = null



    fun resumeHeldRequest() {

        activeHoldResume?.invoke()

    }



    fun cancelHold() {

        activeHoldCancel?.invoke()

        activeHoldCancel = null

        activeHoldResume = null

        onHoldStateChanged?.invoke(false, null)

    }



    /**

     * Checks if an action has been explicitly authorized via session approval or scoped rules.

     */

        /**
     * Synchronously checks whether an action is permitted without waiting for an interactive prompt.
     */
    fun isActionPermittedSync(
        context: Context,
        actionType: ActionType,
        targetDetail: String,
        projectRoot: File? = null
    ): Boolean {
        val evaluation = evaluatePolicy(
            context = context,
            actionType = actionType,
            targetDetail = targetDetail,
            projectRoot = projectRoot
        )
        return evaluation.allowed
    }

    fun isActionExplicitlyAuthorized(actionType: ActionType, targetDetail: String, projectRoot: File? = null): Boolean {

        val actionKey = "${actionType.name}:$targetDetail"

        if (sessionApprovedOnce.contains(actionKey)) return true

        val app = BaseApplication.getBaseInstance() ?: return false

        return isAlwaysApproved(app, projectRoot, actionType, targetDetail, ActionRiskLevel.SAFE_PROJECT_MUTATION)

    }



    fun evaluatePolicy(

        context: Context,

        actionType: ActionType,

        targetDetail: String,

        projectRoot: File? = null,

        proposedDiff: String? = null,

        isExplicitlyDestructive: Boolean = false

    ): PolicyEvaluation {

        val riskLevel = determineRiskLevel(actionType, targetDetail, projectRoot, isExplicitlyDestructive)



        // 1. Check Agent Execution Mode (Side Agent vs Autonomous)

        val executionMode = AgentExecutionMode.getSavedMode(context)

        if (executionMode == AgentExecutionMode.SIDE_AGENT) {

            val isMutating = when (actionType) {

                ActionType.MODIFY_FILE,

                ActionType.CREATE_FILE,

                ActionType.DELETE_FILE,

                ActionType.RESOLVE_IMPORT -> true

                ActionType.EXECUTE_COMMAND -> riskLevel == ActionRiskLevel.COMMAND_DESTRUCTIVE

                else -> false

            }



            if (isMutating && !WritingRules.isEditCommandActive) {

                return PolicyEvaluation(

                    allowed = false,

                    requiresPrompt = false,

                    riskLevel = riskLevel,

                    reason = "Side Agent mode is advisory only. Include '/edit' in your message to authorize applying changes to your project."

                )

            }

        }



        // 2. Check Read-Only actions

        if (riskLevel == ActionRiskLevel.READ_ONLY) {

            return PolicyEvaluation(

                allowed = true,

                requiresPrompt = false,

                riskLevel = riskLevel,

                reason = "Read-only inspection action is inherently safe."

            )

        }



        // 2.1 Enforce Three Permission Categories (Files, Terminal, Build)

        val isAutonomousOrTurbo = ApprovalManager.isTurbo(context) || executionMode == AgentExecutionMode.AUTONOMOUS



        if (actionType == ActionType.EXECUTE_BUILD) {

            val buildPerm = getBuildPermission(context)

            if (buildPerm == PERM_ALWAYS_APPROVE || (isAutonomousOrTurbo && riskLevel == ActionRiskLevel.BUILD_OR_TEST)) {

                return PolicyEvaluation(

                    allowed = true,

                    requiresPrompt = false,

                    riskLevel = riskLevel,

                    reason = "Build operations approved."

                )

            } else if (buildPerm == PERM_ASK_EVERY_TIME && !isAutonomousOrTurbo) {

                return PolicyEvaluation(

                    allowed = false,

                    requiresPrompt = true,

                    riskLevel = riskLevel,

                    reason = "Project build requires user approval before starting."

                )

            }

        }



        if (actionType == ActionType.EXECUTE_COMMAND) {

            val termPerm = getTerminalPermission(context)

            if (termPerm == PERM_ALWAYS_APPROVE && riskLevel != ActionRiskLevel.COMMAND_DESTRUCTIVE) {

                return PolicyEvaluation(

                    allowed = true,

                    requiresPrompt = false,

                    riskLevel = riskLevel,

                    reason = "Terminal commands always approved in settings."

                )

            } else if (termPerm == PERM_ASK_EVERY_TIME && !isAutonomousOrTurbo) {

                return PolicyEvaluation(

                    allowed = false,

                    requiresPrompt = true,

                    riskLevel = riskLevel,

                    reason = "Terminal command requires user confirmation."

                )

            }

        }



        if (actionType == ActionType.MODIFY_FILE || actionType == ActionType.CREATE_FILE ||

            actionType == ActionType.DELETE_FILE || actionType == ActionType.RESOLVE_IMPORT) {

            val filePerm = getFilePermission(context)

            if (filePerm == PERM_ALWAYS_APPROVE && riskLevel == ActionRiskLevel.SAFE_PROJECT_MUTATION) {

                return PolicyEvaluation(

                    allowed = true,

                    requiresPrompt = false,

                    riskLevel = riskLevel,

                    reason = "File modifications always approved in settings."

                )

            } else if (filePerm == PERM_ASK_EVERY_TIME && !isAutonomousOrTurbo) {

                return PolicyEvaluation(

                    allowed = false,

                    requiresPrompt = true,

                    riskLevel = riskLevel,

                    reason = "File modifications require explicit user confirmation."

                )

            }

        }



        // 3. Check Session & Scoped Approvals

        val actionKey = "${actionType.name}:$targetDetail"

        if (sessionApprovedOnce.contains(actionKey)) {

            return PolicyEvaluation(

                allowed = true,

                requiresPrompt = false,

                riskLevel = riskLevel,

                reason = "Approved for this session."

            )

        }



        if (isAlwaysApproved(context, projectRoot, actionType, targetDetail, riskLevel)) {

            return PolicyEvaluation(

                allowed = true,

                requiresPrompt = false,

                riskLevel = riskLevel,

                reason = "Matches user-configured scoped approval rule."

            )

        }



        // 4. Check Approval Mode (Turbo vs Approve)

        if (isAutonomousOrTurbo) {

            val requiresMandatoryGate = when (riskLevel) {

                ActionRiskLevel.COMMAND_DESTRUCTIVE,

                ActionRiskLevel.DESTRUCTIVE_FILE_OP,

                ActionRiskLevel.SENSITIVE_OR_OUT_OF_WORKSPACE -> true

                else -> false

            }



            if (requiresMandatoryGate) {

                return PolicyEvaluation(

                    allowed = false,

                    requiresPrompt = true,

                    riskLevel = riskLevel,

                    reason = "Mandatory safety gate: High-risk or destructive operation requires explicit user confirmation even in Auto/Turbo mode."

                )

            } else {

                return PolicyEvaluation(

                    allowed = true,

                    requiresPrompt = false,

                    riskLevel = riskLevel,

                    reason = "Auto/Turbo mode active: Non-destructive project operation executed autonomously."

                )

            }

        }



        // In Approve Mode ("Ask Before Changes"), every mutating, build, and command action requires user confirmation

        return PolicyEvaluation(

            allowed = false,

            requiresPrompt = true,

            riskLevel = riskLevel,

            reason = "Approve mode active: Explicit confirmation required before executing action."

        )

    }



    suspend fun checkAndRequestChangeSetApproval(

        edits: List<CoordinatedFileEdit>,

        projectRoot: File? = null

    ): UserApprovalResult {

        if (edits.isEmpty()) return UserApprovalResult.ALLOW_ONCE



        val currentActivity = BaseApplication.getCurrentActivity()

        val context = currentActivity ?: BaseApplication.getBaseInstance()

        if (context == null) {

            val allSafe = edits.all { edit ->

                val action = if (edit.isNewFile) ActionType.CREATE_FILE else ActionType.MODIFY_FILE

                val risk = determineRiskLevel(action, edit.targetFile.absolutePath, projectRoot, false)

                risk == ActionRiskLevel.SAFE_PROJECT_MUTATION || risk == ActionRiskLevel.READ_ONLY

            }

            if (allSafe) return UserApprovalResult.ALLOW_ONCE

            log.error("ActionPolicyGate: Application context is unavailable. Failing closed.")

            return UserApprovalResult.REFUSE

        }



        // 1. Check side agent mode

        val executionMode = AgentExecutionMode.getSavedMode(context)

        if (executionMode == AgentExecutionMode.SIDE_AGENT && !WritingRules.isEditCommandActive) {

            return UserApprovalResult.DENIED_BY_POLICY

        }



        // 2. Check Auto / Turbo mode bypass for safe operations

        val isAutonomousOrTurbo = ApprovalManager.isTurbo(context) || executionMode == AgentExecutionMode.AUTONOMOUS

        if (isAutonomousOrTurbo) {

            val hasSensitive = edits.any { edit ->

                val actionType = if (edit.isNewFile) ActionType.CREATE_FILE else ActionType.MODIFY_FILE

                val risk = determineRiskLevel(actionType, edit.targetFile.absolutePath, projectRoot, false)

                risk == ActionRiskLevel.COMMAND_DESTRUCTIVE ||

                    risk == ActionRiskLevel.DESTRUCTIVE_FILE_OP ||

                    risk == ActionRiskLevel.SENSITIVE_OR_OUT_OF_WORKSPACE

            }

            if (!hasSensitive) {

                return UserApprovalResult.ALLOW_ONCE

            }

        }



        // 3. Prompt user for each file edit separately (Issue 5(i))

        for (edit in edits) {

            val actionType = if (edit.isNewFile) ActionType.CREATE_FILE else ActionType.MODIFY_FILE

            val actionKey = "${actionType.name}:${edit.targetFile.absolutePath}"

            if (sessionApprovedOnce.contains(actionKey) || isAlwaysApproved(context, projectRoot, actionType, edit.targetFile.absolutePath, ActionRiskLevel.SAFE_PROJECT_MUTATION)) {

                continue

            }

            val result = requestSingleEditApproval(edit, projectRoot)

            if (result == UserApprovalResult.REFUSE || result == UserApprovalResult.DENIED_BY_POLICY) {

                return result

            }

        }

        return UserApprovalResult.ALLOW_ONCE

    }



    private suspend fun requestSingleEditApproval(

        edit: CoordinatedFileEdit,

        projectRoot: File?

    ): UserApprovalResult = withContext(Dispatchers.Main) {

        suspendCancellableCoroutine { continuation ->

            try {

                val actionType = if (edit.isNewFile) ActionType.CREATE_FILE else ActionType.MODIFY_FILE

                val actionKey = "${actionType.name}:${edit.targetFile.absolutePath}"



                val diff = DiffEngine.computeDiff(

                    relativePath = edit.relativePath,

                    originalContent = edit.originalContent,

                    newContent = edit.finalContent

                )

                var activeDialog: androidx.appcompat.app.AlertDialog? = null

                continuation.invokeOnCancellation {
                    try {
                        activeDialog?.dismiss()
                    } catch (_: Exception) {}
                }

                fun showDialog() {

                    val activity = BaseApplication.getCurrentActivity()

                    if (activity == null || activity.isFinishing || activity.isDestroyed) {

                        log.error("ActionPolicyGate: No active Activity available to present approval dialog.")

                        if (continuation.isActive) continuation.resume(UserApprovalResult.REFUSE)

                        return

                    }



                    val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_approval_request, null)

                    val tvAction = dialogView.findViewById<TextView>(R.id.tvApprovalAction)

                    val tvRisk = dialogView.findViewById<TextView>(R.id.tvApprovalRisk)

                    val tvTarget = dialogView.findViewById<TextView>(R.id.tvApprovalTarget)

                    val tvScope = dialogView.findViewById<TextView>(R.id.tvApprovalScope)

                    val tvLineStats = dialogView.findViewById<TextView>(R.id.tvApprovalLineStats)

                    val scrollDiff = dialogView.findViewById<View>(R.id.scrollViewDiff)

                    val tvDiff = dialogView.findViewById<TextView>(R.id.tvApprovalDiff)

                    val scrollFiles = dialogView.findViewById<View>(R.id.scrollApprovalFiles)



                    scrollFiles?.visibility = View.GONE



                    tvAction?.text = if (edit.isNewFile) "Create File" else "Modify File"

                    tvRisk?.text = "PROJECT MUTATION"

                    tvRisk?.setBackgroundResource(com.tom.rv2ide.resources.R.drawable.bg_count_pill_accent)

                    tvRisk?.setTextColor(Color.BLACK)



                    val scopeName = projectRoot?.name ?: "Active Workspace"

                    tvScope?.text = "Scope: $scopeName"

                    tvTarget?.text = edit.relativePath



                    tvLineStats?.visibility = View.VISIBLE

                    tvLineStats?.text = "+${diff.linesAdded}  -${diff.linesRemoved}"

                    scrollDiff?.visibility = View.VISIBLE
                    val dm = activity.resources.displayMetrics
                    val maxDiffPx = minOf((180 * dm.density).toInt(), (dm.heightPixels * 0.28).toInt())
                    (scrollDiff as? com.tom.rv2ide.ui.MaxHeightNestedScrollView)?.maxHeightPx = maxDiffPx



                    val spannable = DiffVisualFormatter.formatDiffSpannable(diff, activity, onlyHunks = true)

                    tvDiff?.text = spannable



                    val dialog = MaterialAlertDialogBuilder(activity)

                        .setTitle("🛡️ Agent Action Approval")

                        .setIcon(com.tom.rv2ide.resources.R.drawable.ic_warning)

                        .setView(dialogView)

                        .setCancelable(true)

                        .create()
                    activeDialog = dialog



                    dialog.setOnCancelListener {

                        activeHoldCancel = null

                        activeHoldResume = null

                        onHoldStateChanged?.invoke(false, null)

                        if (continuation.isActive) continuation.resume(UserApprovalResult.REFUSE)

                    }



                    val btnHold = dialogView.findViewById<View>(R.id.btnApprovalHold)

                    val btnApprove = dialogView.findViewById<View>(R.id.btnApprovalApproveOnce)

                    val btnRefuse = dialogView.findViewById<View>(R.id.btnApprovalRefuse)

                    val btnAlways = dialogView.findViewById<View>(R.id.btnApprovalApproveAlways)



                    btnApprove?.setOnClickListener {

                        activeHoldCancel = null

                        activeHoldResume = null

                        onHoldStateChanged?.invoke(false, null)

                        dialog.dismiss()

                        sessionApprovedOnce.add(actionKey)

                        if (continuation.isActive) continuation.resume(UserApprovalResult.ALLOW_ONCE)

                    }



                    btnAlways?.setOnClickListener {

                        activeHoldCancel = null

                        activeHoldResume = null

                        onHoldStateChanged?.invoke(false, null)

                        dialog.dismiss()

                        setFilePermission(activity, PERM_ALWAYS_APPROVE)

                        sessionApprovedOnce.add(actionKey)

                        recordAlwaysApprovedRule(activity, projectRoot, actionType, edit.targetFile.absolutePath, ActionRiskLevel.SAFE_PROJECT_MUTATION)

                        if (continuation.isActive) continuation.resume(UserApprovalResult.ALLOW_ALWAYS)

                    }



                    btnRefuse?.setOnClickListener {

                        activeHoldCancel = null

                        activeHoldResume = null

                        onHoldStateChanged?.invoke(false, null)

                        dialog.dismiss()

                        if (continuation.isActive) continuation.resume(UserApprovalResult.REFUSE)

                    }



                    btnHold?.setOnClickListener {

                        dialog.dismiss()

                        activeHoldCancel = {

                            if (continuation.isActive) continuation.resume(UserApprovalResult.REFUSE)

                        }

                        activeHoldResume = {

                            if (continuation.isActive) {
                                showDialog()
                            }

                        }

                        onHoldStateChanged?.invoke(true, activeHoldResume)

                    }



                    dialog.show()

                }



                showDialog()

            } catch (e: Exception) {

                log.error("ActionPolicyGate: Exception showing single edit approval dialog.", e)

                if (continuation.isActive) continuation.resume(UserApprovalResult.REFUSE)

            }

        }

    }



    suspend fun checkAndRequestApproval(

        actionType: ActionType,

        targetDetail: String,

        projectRoot: File? = null,

        proposedDiff: String? = null,

        isExplicitlyDestructive: Boolean = false

    ): UserApprovalResult {

        val currentActivity = BaseApplication.getCurrentActivity()

        val context = currentActivity ?: BaseApplication.getBaseInstance()

        if (context == null) {

            val actionKey = "${actionType.name}:$targetDetail"

            if (sessionApprovedOnce.contains(actionKey)) {

                return UserApprovalResult.ALLOW_ONCE

            }

            val riskLevel = determineRiskLevel(actionType, targetDetail, projectRoot, isExplicitlyDestructive)

            if (riskLevel == ActionRiskLevel.SAFE_PROJECT_MUTATION ||

                riskLevel == ActionRiskLevel.READ_ONLY ||

                riskLevel == ActionRiskLevel.BUILD_OR_TEST ||

                riskLevel == ActionRiskLevel.COMMAND_NON_DESTRUCTIVE) {

                return UserApprovalResult.ALLOW_ONCE

            }

            log.error("ActionPolicyGate: Application context is unavailable. Failing closed.")

            return UserApprovalResult.REFUSE

        }



        val eval = evaluatePolicy(

            context = context,

            actionType = actionType,

            targetDetail = targetDetail,

            projectRoot = projectRoot,

            proposedDiff = proposedDiff,

            isExplicitlyDestructive = isExplicitlyDestructive

        )



        if (!eval.allowed && !eval.requiresPrompt) {

            log.warn("ActionPolicyGate: Action {} denied by policy: {}", actionType, eval.reason)

            return UserApprovalResult.DENIED_BY_POLICY

        }



        if (eval.allowed && !eval.requiresPrompt) {

            return UserApprovalResult.ALLOW_ONCE

        }



        // User confirmation prompt required

        return withContext(Dispatchers.Main) {

            suspendCancellableCoroutine { continuation ->

                try {

                    val actionKey = "${actionType.name}:$targetDetail"

                    var activeDialog: androidx.appcompat.app.AlertDialog? = null

                    continuation.invokeOnCancellation {
                        try {
                            activeDialog?.dismiss()
                        } catch (_: Exception) {}
                    }

                    fun showDialog() {

                        val activity = BaseApplication.getCurrentActivity()

                        if (activity == null || activity.isFinishing || activity.isDestroyed) {

                            log.error("ActionPolicyGate: No active Activity available to present approval dialog. Failing closed.")

                            if (continuation.isActive) continuation.resume(UserApprovalResult.REFUSE)

                            return

                        }



                        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_approval_request, null)

                        val tvAction = dialogView.findViewById<TextView>(R.id.tvApprovalAction)

                        val tvRisk = dialogView.findViewById<TextView>(R.id.tvApprovalRisk)

                        val tvTarget = dialogView.findViewById<TextView>(R.id.tvApprovalTarget)

                        val tvScope = dialogView.findViewById<TextView>(R.id.tvApprovalScope)

                        val scrollDiff = dialogView.findViewById<View>(R.id.scrollViewDiff)

                        val tvDiff = dialogView.findViewById<TextView>(R.id.tvApprovalDiff)

                        val scrollFiles = dialogView.findViewById<View>(R.id.scrollApprovalFiles)



                        scrollFiles?.visibility = View.GONE



                        tvAction?.text = actionType.displayName

                        tvRisk?.text = eval.riskLevel.displayName



                        when (eval.riskLevel) {

                            ActionRiskLevel.READ_ONLY,

                            ActionRiskLevel.SAFE_PROJECT_MUTATION -> {

                                tvRisk?.setBackgroundResource(com.tom.rv2ide.resources.R.drawable.bg_count_pill_accent)

                                tvRisk?.setTextColor(Color.BLACK)

                            }

                            ActionRiskLevel.BUILD_OR_TEST,

                            ActionRiskLevel.COMMAND_NON_DESTRUCTIVE -> {

                                tvRisk?.setBackgroundResource(com.tom.rv2ide.resources.R.drawable.bg_count_pill_warning)

                                tvRisk?.setTextColor(Color.BLACK)

                            }

                            ActionRiskLevel.COMMAND_DESTRUCTIVE,

                            ActionRiskLevel.DESTRUCTIVE_FILE_OP,

                            ActionRiskLevel.SENSITIVE_OR_OUT_OF_WORKSPACE -> {

                                tvRisk?.setBackgroundResource(com.tom.rv2ide.resources.R.drawable.bg_count_pill_error)

                                tvRisk?.setTextColor(Color.WHITE)

                            }

                        }



                        tvTarget?.text = targetDetail

                        val scopeName = projectRoot?.name ?: "Active Workspace"

                        tvScope?.text = "Scope: $scopeName"



                        if (!proposedDiff.isNullOrBlank()) {

                            scrollDiff?.visibility = View.VISIBLE
                            val dm2 = activity.resources.displayMetrics
                            val maxDiffPx2 = minOf((180 * dm2.density).toInt(), (dm2.heightPixels * 0.28).toInt())
                            (scrollDiff as? com.tom.rv2ide.ui.MaxHeightNestedScrollView)?.maxHeightPx = maxDiffPx2

                            val targetFile = File(targetDetail)

                            val origContent = if (targetFile.exists() && targetFile.isFile) targetFile.readText() else null

                            val isDiffFormat = proposedDiff.contains("@@") || proposedDiff.startsWith("---") || proposedDiff.startsWith("+++")



                            if (!isDiffFormat && (actionType == ActionType.MODIFY_FILE || actionType == ActionType.CREATE_FILE)) {

                                val computed = DiffEngine.computeDiff(targetFile.name, origContent, proposedDiff)

                                tvDiff?.text = DiffVisualFormatter.formatDiffSpannable(computed, activity, onlyHunks = true)

                            } else {

                                val boundedDiff = if (proposedDiff.length > 5000) {

                                    proposedDiff.take(5000) + "\n... [diff truncated for display]"

                                } else {

                                    proposedDiff

                                }

                                tvDiff?.text = boundedDiff

                            }

                        } else {

                            scrollDiff?.visibility = View.GONE

                        }



                        val dialog = MaterialAlertDialogBuilder(activity)

                            .setTitle("🛡️ Agent Action Approval")

                            .setIcon(com.tom.rv2ide.resources.R.drawable.ic_warning)

                            .setView(dialogView)

                            .setCancelable(true)

                            .create()
                        activeDialog = dialog



                        dialog.setOnCancelListener {

                            activeHoldCancel = null

                            activeHoldResume = null

                            onHoldStateChanged?.invoke(false, null)

                            if (continuation.isActive) continuation.resume(UserApprovalResult.REFUSE)

                        }



                        val btnHold = dialogView.findViewById<View>(R.id.btnApprovalHold)

                        val btnApprove = dialogView.findViewById<View>(R.id.btnApprovalApproveOnce)

                        val btnRefuse = dialogView.findViewById<View>(R.id.btnApprovalRefuse)

                        val btnAlways = dialogView.findViewById<View>(R.id.btnApprovalApproveAlways)



                        btnApprove?.setOnClickListener {

                            activeHoldCancel = null

                            activeHoldResume = null

                            onHoldStateChanged?.invoke(false, null)

                            dialog.dismiss()

                            sessionApprovedOnce.add(actionKey)

                            if (continuation.isActive) continuation.resume(UserApprovalResult.ALLOW_ONCE)

                        }



                        btnAlways?.setOnClickListener {

                            activeHoldCancel = null

                            activeHoldResume = null

                            onHoldStateChanged?.invoke(false, null)

                            dialog.dismiss()

                            when (actionType) {

                                ActionType.EXECUTE_BUILD -> setBuildPermission(activity, PERM_ALWAYS_APPROVE)

                                ActionType.EXECUTE_COMMAND -> setTerminalPermission(activity, PERM_ALWAYS_APPROVE)

                                else -> setFilePermission(activity, PERM_ALWAYS_APPROVE)

                            }

                            recordAlwaysApprovedRule(activity, projectRoot, actionType, targetDetail, eval.riskLevel)

                            sessionApprovedOnce.add(actionKey)

                            if (continuation.isActive) continuation.resume(UserApprovalResult.ALLOW_ALWAYS)

                        }



                        btnRefuse?.setOnClickListener {

                            activeHoldCancel = null

                            activeHoldResume = null

                            onHoldStateChanged?.invoke(false, null)

                            dialog.dismiss()

                            if (continuation.isActive) continuation.resume(UserApprovalResult.REFUSE)

                        }



                        btnHold?.setOnClickListener {

                            dialog.dismiss()

                            activeHoldCancel = {

                                if (continuation.isActive) continuation.resume(UserApprovalResult.REFUSE)

                            }

                            activeHoldResume = {

                                showDialog()

                            }

                            onHoldStateChanged?.invoke(true, activeHoldResume)

                        }



                        dialog.show()

                    }



                    showDialog()

                } catch (e: Exception) {

                    log.error("ActionPolicyGate: Exception showing approval dialog.", e)

                    if (continuation.isActive) continuation.resume(UserApprovalResult.REFUSE)

                }

            }

        }

    }

    fun determineRiskLevel(
        actionType: ActionType,
        targetDetail: String,
        projectRoot: File? = null,
        isExplicitlyDestructive: Boolean = false
    ): ActionRiskLevel {
        if (isExplicitlyDestructive || actionType == ActionType.DELETE_FILE) {
            return ActionRiskLevel.DESTRUCTIVE_FILE_OP
        }

        if (actionType == ActionType.EXECUTE_COMMAND) {
            val cmd = targetDetail.lowercase()
            val destructivePatterns = listOf(
                "rm -rf /", "rm -r /", "mkfs", "dd ",
                "kill -9", "chmod 777", "> /dev/", "shutdown", "reboot"
            )
            val isDestructive = destructivePatterns.any { cmd.contains(it) }
            return if (isDestructive) ActionRiskLevel.COMMAND_DESTRUCTIVE else ActionRiskLevel.COMMAND_NON_DESTRUCTIVE
        }

        if (actionType == ActionType.EXECUTE_BUILD) {
            return ActionRiskLevel.BUILD_OR_TEST
        }

        if (actionType == ActionType.MODIFY_FILE || actionType == ActionType.CREATE_FILE || actionType == ActionType.RESOLVE_IMPORT) {
            val lower = targetDetail.lowercase()
            val isSensitive = lower.contains(".git/") || lower.contains(".gradle/") || lower.contains(".id/") ||
                lower.endsWith(".jks") || lower.endsWith(".keystore") || lower.contains("id_rsa") || lower.endsWith(".env")

            if (isSensitive) {
                return ActionRiskLevel.SENSITIVE_OR_OUT_OF_WORKSPACE
            }

            if (projectRoot != null) {
                try {
                    val rootCanon = projectRoot.canonicalPath
                    val targetFile = File(targetDetail)
                    val targetCanon = targetFile.canonicalPath
                    if (!targetCanon.startsWith(rootCanon)) {
                        return ActionRiskLevel.SENSITIVE_OR_OUT_OF_WORKSPACE
                    }
                } catch (_: Exception) {}
            }
            return ActionRiskLevel.SAFE_PROJECT_MUTATION
        }

        return ActionRiskLevel.SAFE_PROJECT_MUTATION
    }

    private fun isAlwaysApproved(
        context: Context,
        projectRoot: File?,
        actionType: ActionType,
        targetDetail: String,
        riskLevel: ActionRiskLevel
    ): Boolean {
        if (riskLevel == ActionRiskLevel.COMMAND_DESTRUCTIVE ||
            riskLevel == ActionRiskLevel.DESTRUCTIVE_FILE_OP ||
            riskLevel == ActionRiskLevel.SENSITIVE_OR_OUT_OF_WORKSPACE) {
            return false
        }

        val projectPath = projectRoot?.canonicalPath ?: ""
        val rules = loadScopedRules(context)
        return rules.any { it.matches(projectPath, actionType, targetDetail) }
    }

    private fun recordAlwaysApprovedRule(

        context: Context,

        projectRoot: File?,

        actionType: ActionType,

        targetDetail: String,

        riskLevel: ActionRiskLevel

    ) {

        if (riskLevel == ActionRiskLevel.COMMAND_DESTRUCTIVE ||

            riskLevel == ActionRiskLevel.DESTRUCTIVE_FILE_OP ||

            riskLevel == ActionRiskLevel.SENSITIVE_OR_OUT_OF_WORKSPACE) {

            log.warn("ActionPolicyGate: Refusing to persist scoped approval for destructive action: {}", targetDetail)

            return

        }



        val projectPath = projectRoot?.canonicalPath ?: "*"

        val pattern = if (actionType == ActionType.MODIFY_FILE || actionType == ActionType.CREATE_FILE) {

            File(targetDetail).name

        } else {

            targetDetail.take(120)

        }



        val newRule = ScopedApprovalRule(

            projectPath = projectPath,

            actionType = actionType.name,

            resourcePattern = pattern

        )



        val currentRules = loadScopedRules(context).toMutableList()

        currentRules.add(newRule)

        saveScopedRules(context, currentRules)

    }



    private fun loadScopedRules(context: Context): List<ScopedApprovalRule> {

        return try {

            val sp = PreferenceManager.getDefaultSharedPreferences(context)

            val set = sp.getStringSet(PREF_KEY_SCOPED_APPROVAL_RULES, emptySet()) ?: emptySet()

            set.mapNotNull { ScopedApprovalRule.deserialize(it) }

        } catch (_: Exception) {

            emptyList()

        }

    }



    private fun saveScopedRules(context: Context, rules: List<ScopedApprovalRule>) {

        try {

            val sp = PreferenceManager.getDefaultSharedPreferences(context)

            val set = rules.map { it.serialize() }.toSet()

            sp.edit().putStringSet(PREF_KEY_SCOPED_APPROVAL_RULES, set).apply()

        } catch (_: Exception) {}

    }



    /**

     * Resets persisted scoped rules and clears session approvals for the specified action type,

     * or for all action types if null. Used by settings when toggling permissions back to Ask Every Time.

     */

    fun resetAlwaysApprovedRules(context: Context, actionType: ActionType? = null) {

        try {

            clearSessionApprovals()

            val current = loadScopedRules(context)

            val filtered = if (actionType != null) {

                current.filter { rule ->

                    if (actionType == ActionType.MODIFY_FILE) {

                        rule.actionType != ActionType.MODIFY_FILE.name &&

                        rule.actionType != ActionType.CREATE_FILE.name &&

                        rule.actionType != ActionType.DELETE_FILE.name

                    } else {

                        rule.actionType != actionType.name

                    }

                }

            } else {

                emptyList()

            }

            saveScopedRules(context, filtered)

        } catch (_: Exception) {}

    }



    /**

     * Resets all agent permissions (Files, Terminal, Build) to PERM_ASK_EVERY_TIME

     * and purges all persisted scoped rules and in-memory session approvals.

     */

    fun resetAllApprovalsToAskEveryTime(context: Context) {

        setFilePermission(context, PERM_ASK_EVERY_TIME)

        setTerminalPermission(context, PERM_ASK_EVERY_TIME)

        setBuildPermission(context, PERM_ASK_EVERY_TIME)

        resetAlwaysApprovedRules(context, null)

    }

}

