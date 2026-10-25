package com.tom.rv2ide.artificial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.BuildConfig
import com.tom.rv2ide.R
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.artificial.coding.AutonomousRollbackManager
import com.tom.rv2ide.artificial.coding.CoordinatedFileEdit
import com.tom.rv2ide.artificial.coding.RollbackConflictReason
import com.tom.rv2ide.artificial.coding.TaskCheckpoint
import com.tom.rv2ide.artificial.coding.TaskCheckpointManager
import com.tom.rv2ide.artificial.diff.DiffEngine
import com.tom.rv2ide.artificial.diff.DiffVisualFormatter
import com.tom.rv2ide.artificial.policy.ActionPolicyGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Diagnostic BroadcastReceiver executing on-device AI Trust Engine hardware verification.
 *
 * Hardened with:
 * 1. Packaged exclusively via src/debug/AndroidManifest.xml (omitted from release builds).
 * 2. Runtime guard restricting execution strictly to BuildConfig.DEBUG.
 * 3. Caller UID guard permitting only ADB shell (UID 2000), root (UID 0), or app process.
 * 4. Synthetic sandboxing in app internal storage.
 */
class TrustEngineVerificationReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "TrustEngineVerification"
        const val ACTION_VERIFY = "com.tom.rv2ide.artificial.ACTION_VERIFY_TRUST_ENGINE"
        const val ACTION_SHOW_SAMPLE_DIFF = "com.tom.rv2ide.artificial.ACTION_SHOW_SAMPLE_DIFF"
        const val ACTION_SHOW_SAMPLE_REVIEW = "com.tom.rv2ide.artificial.ACTION_SHOW_SAMPLE_REVIEW"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "Rejected verification request: Disabled in production builds.")
            setResultCode(2)
            setResultData("DISABLED_IN_PRODUCTION")
            return
        }

        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.myUid() && callingUid != 2000 && callingUid != 0) {
            Log.w(TAG, "Rejected verification request: Calling UID $callingUid is not authorized.")
            setResultCode(3)
            setResultData("UNAUTHORIZED_CALLER")
            return
        }

        if (action == ACTION_SHOW_SAMPLE_DIFF) {
            val act = BaseApplication.getCurrentActivity()
            if (act != null) {
                act.runOnUiThread {
                    CoroutineScope(Dispatchers.Main).launch {
                        com.tom.rv2ide.artificial.policy.ApprovalManager.setApprovalMode(act, com.tom.rv2ide.artificial.policy.ApprovalManager.MODE_APPROVE)
                        ActionPolicyGate.clearSessionApprovals()
                        com.tom.rv2ide.artificial.rules.WritingRules.isEditCommandActive = true
                        val edit1 = CoordinatedFileEdit(
                            relativePath = "app/src/main/java/MainActivity.kt",
                            targetFile = File(act.filesDir, "MainActivity.kt"),
                            isNewFile = false,
                            finalContent = "class MainActivity : AppCompatActivity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        setContentView(R.layout.activity_main)\n        setupTrustEngineVisualDiff()\n    }\n\n    private fun setupTrustEngineVisualDiff() {\n        Log.i(\"TrustEngine\", \"Visual Diff Active\")\n    }\n}",
                            originalContent = "class MainActivity : AppCompatActivity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        setContentView(R.layout.activity_main)\n    }\n}"
                        )
                        val edit2 = CoordinatedFileEdit(
                            relativePath = "app/src/main/AndroidManifest.xml",
                            targetFile = File(act.filesDir, "AndroidManifest.xml"),
                            isNewFile = false,
                            finalContent = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <application>\n        <service android:name=\".TrustEngineService\" />\n    </application>\n</manifest>",
                            originalContent = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <application>\n    </application>\n</manifest>"
                        )
                        ActionPolicyGate.checkAndRequestChangeSetApproval(
                            edits = listOf(edit1, edit2),
                            projectRoot = act.filesDir
                        )
                    }
                }
            }
            return
        }

        if (action == ACTION_SHOW_SAMPLE_REVIEW) {
            val act = BaseApplication.getCurrentActivity()
            if (act != null) {
                act.runOnUiThread {
                    val sessionId = "turn_chk_sample_101"
                    val file1 = File(act.filesDir, "Service.kt").apply { writeText("original service v0") }
                    val file2 = File(act.filesDir, "Utils.kt").apply { writeText("original utils v0") }
                    AutonomousRollbackManager.startSession(sessionId, act.filesDir)
                    AutonomousRollbackManager.recordFilePreState(sessionId, act.filesDir, file1, "Service.kt")
                    AutonomousRollbackManager.recordFilePreState(sessionId, act.filesDir, file2, "Utils.kt")
                    file1.writeText("agent modified service v1\nadded line 2")
                    file2.writeText("agent modified utils v1")
                    AutonomousRollbackManager.recordFilePostWrite(sessionId, act.filesDir, file1, "Service.kt", "agent modified service v1\nadded line 2")
                    AutonomousRollbackManager.recordFilePostWrite(sessionId, act.filesDir, file2, "Utils.kt", "agent modified utils v1")

                    file2.writeText("user manual edit on utils - DO NOT OVERWRITE")

                    val journal = AutonomousRollbackManager.getJournal(sessionId, act.filesDir)
                    val conflicts = AutonomousRollbackManager.checkConflicts(sessionId, act.filesDir)
                    val conflictMap = conflicts.associateBy { it.relativePath }

                    val dialogView = act.layoutInflater.inflate(R.layout.dialog_review_changes, null)
                    val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvReviewSubtitle)
                    val cardConflict = dialogView.findViewById<View>(R.id.cardReviewConflictWarning)
                    val tvConflictDetail = dialogView.findViewById<TextView>(R.id.tvReviewConflictDetail)
                    val layoutChips = dialogView.findViewById<LinearLayout>(R.id.layoutReviewFileChips)
                    val tvFilePath = dialogView.findViewById<TextView>(R.id.tvReviewFilePath)
                    val tvOpBadge = dialogView.findViewById<TextView>(R.id.tvReviewOperationBadge)
                    val tvLineStats = dialogView.findViewById<TextView>(R.id.tvReviewLineStats)
                    val tvConflictStatus = dialogView.findViewById<TextView>(R.id.tvReviewConflictStatus)
                    val tvDiff = dialogView.findViewById<TextView>(R.id.tvReviewDiffContent)
                    val scrollReviewDiff = dialogView.findViewById<View>(R.id.scrollReviewDiff)
                    val dm = act.resources.displayMetrics
                    val maxReviewDiffPx = minOf((220 * dm.density).toInt(), (dm.heightPixels * 0.35).toInt())
                    (scrollReviewDiff as? com.tom.rv2ide.ui.MaxHeightNestedScrollView)?.maxHeightPx = maxReviewDiffPx

                    tvSubtitle?.text = "Session: $sessionId • 2 file(s) recorded"
                    if (conflicts.isNotEmpty()) {
                        cardConflict?.visibility = View.VISIBLE
                        tvConflictDetail?.text = "${conflicts.size} file(s) have external user edits and will be protected from undo."
                    }

                    val entriesList = journal!!.entries.values.toList()
                    fun render(idx: Int) {
                        val entry = entriesList[idx]
                        val conflict = conflictMap[entry.relativePath]
                        tvFilePath?.text = entry.relativePath
                        tvOpBadge?.text = entry.operation.name
                        if (conflict != null) {
                            tvConflictStatus?.text = "⚠️ Conflict: ${conflict.reason.description}"
                            tvConflictStatus?.setTextColor(Color.parseColor("#E57373"))
                        } else {
                            tvConflictStatus?.text = "✓ In sync with AI output (Safe to revert)"
                            tvConflictStatus?.setTextColor(Color.parseColor("#81C784"))
                        }
                        val diff = DiffEngine.computeDiff(entry.relativePath, entry.originalContent, entry.latestWrittenContent)
                        tvLineStats?.text = "+${diff.linesAdded}  -${diff.linesRemoved}"
                        tvDiff?.text = DiffVisualFormatter.formatDiffSpannable(diff, act, onlyHunks = false)

                        for (i in 0 until layoutChips.childCount) {
                            val chip = layoutChips.getChildAt(i) as? TextView ?: continue
                            val isConflicted = conflictMap.containsKey(entriesList[i].relativePath)
                            if (i == idx) {
                                chip.setTextColor(Color.WHITE)
                                chip.setBackgroundColor(Color.parseColor("#00838F"))
                            } else if (isConflicted) {
                                chip.setTextColor(Color.parseColor("#FF8A80"))
                                chip.setBackgroundColor(Color.argb(40, 255, 138, 128))
                            } else {
                                chip.setTextColor(Color.parseColor("#90A4AE"))
                                chip.setBackgroundColor(Color.argb(30, 144, 164, 174))
                            }
                        }
                    }

                    layoutChips.removeAllViews()
                    entriesList.forEachIndexed { idx, entry ->
                        val isConflicted = conflictMap.containsKey(entry.relativePath)
                        val chip = TextView(act).apply {
                            text = (if (isConflicted) "⚠️ " else "") + File(entry.relativePath).name
                            textSize = 11f
                            setPadding(20, 8, 20, 8)
                            val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                rightMargin = 12
                            }
                            layoutParams = p
                            setOnClickListener { render(idx) }
                        }
                        layoutChips.addView(chip)
                    }
                    render(0)

                    MaterialAlertDialogBuilder(act)
                        .setView(dialogView)
                        .setCancelable(true)
                        .setPositiveButton("↩ Revert AI Changes") { d, _ -> d.dismiss() }
                        .setNegativeButton("Keep Changes / Close", null)
                        .show()
                }
            }
            return
        }

        Log.i(TAG, "Starting authorized on-device AI Trust Engine verification on physical hardware (caller UID: $callingUid)...")

        val tests = mutableListOf<JSONObject>()
        var overallSuccess = true

        val sandboxRoot = File(context.filesDir, "trust_engine_sandbox_${System.currentTimeMillis()}")
        sandboxRoot.mkdirs()

        try {
            // 1. DiffEngine verification
            try {
                val orig = "fun calculate(a: Int, b: Int): Int {\n    return a + b\n}"
                val modified = "fun calculate(a: Int, b: Int): Int {\n    val result = a + b\n    return result\n}"
                val diff = DiffEngine.computeDiff("Calculator.kt", orig, modified)
                val ok = diff.linesAdded == 2 && diff.linesRemoved == 1 && diff.hunks.isNotEmpty()
                tests.add(JSONObject().apply {
                    put("name", "diff_engine_computation")
                    put("passed", ok)
                    put("lines_added", diff.linesAdded)
                    put("lines_removed", diff.linesRemoved)
                    put("hunks_count", diff.hunks.size)
                })
                if (!ok) overallSuccess = false
            } catch (e: Exception) {
                overallSuccess = false
                tests.add(JSONObject().apply {
                    put("name", "diff_engine_computation")
                    put("passed", false)
                    put("error", e.message)
                })
            }

            // 2. DiffVisualFormatter Spannable verification
            try {
                val orig = "val v = 1"
                val modified = "val v = 2"
                val diff = DiffEngine.computeDiff("Value.kt", orig, modified)
                val spannable = DiffVisualFormatter.formatDiffSpannable(diff, context, onlyHunks = true)
                val ok = spannable.isNotEmpty()
                tests.add(JSONObject().apply {
                    put("name", "diff_visual_formatter_spannable")
                    put("passed", ok)
                    put("rendered_length", spannable.length)
                })
                if (!ok) overallSuccess = false
            } catch (e: Exception) {
                overallSuccess = false
                tests.add(JSONObject().apply {
                    put("name", "diff_visual_formatter_spannable")
                    put("passed", false)
                    put("error", e.message)
                })
            }

            // 3. AutonomousRollbackManager safe restoration
            try {
                val sessionId = UUID.randomUUID().toString()
                val target = File(sandboxRoot, "Main.kt").apply { writeText("original v0") }
                AutonomousRollbackManager.startSession(sessionId, sandboxRoot)
                AutonomousRollbackManager.recordFilePreState(sessionId, sandboxRoot, target, "Main.kt")

                target.writeText("ai written v1")
                AutonomousRollbackManager.recordFilePostWrite(sessionId, sandboxRoot, target, "Main.kt", "ai written v1")

                val rollbackRes = AutonomousRollbackManager.rollbackSession(sessionId, sandboxRoot)
                val restoredText = target.readText()
                val ok = rollbackRes.success && rollbackRes.revertedCount == 1 && restoredText == "original v0"
                tests.add(JSONObject().apply {
                    put("name", "rollback_manager_safe_restoration")
                    put("passed", ok)
                    put("reverted_count", rollbackRes.revertedCount)
                    put("restored_content", restoredText)
                })
                if (!ok) overallSuccess = false
            } catch (e: Exception) {
                overallSuccess = false
                tests.add(JSONObject().apply {
                    put("name", "rollback_manager_safe_restoration")
                    put("passed", false)
                    put("error", e.message)
                })
            }

            // 4. Conflict detection preserves external user modifications
            try {
                val sessionId = UUID.randomUUID().toString()
                val target = File(sandboxRoot, "UserFile.kt").apply { writeText("baseline") }
                AutonomousRollbackManager.startSession(sessionId, sandboxRoot)
                AutonomousRollbackManager.recordFilePreState(sessionId, sandboxRoot, target, "UserFile.kt")

                target.writeText("agent change")
                AutonomousRollbackManager.recordFilePostWrite(sessionId, sandboxRoot, target, "UserFile.kt", "agent change")

                // User manually edits
                target.writeText("user manual edit - KEEP")

                val preConflicts = AutonomousRollbackManager.checkConflicts(sessionId, sandboxRoot)
                val conflictDetected = preConflicts.size == 1 && preConflicts[0].reason == RollbackConflictReason.MODIFIED_EXTERNALLY

                val rollbackRes = AutonomousRollbackManager.rollbackSession(sessionId, sandboxRoot)
                val currentText = target.readText()
                val ok = conflictDetected && !rollbackRes.success && rollbackRes.conflictCount == 1 && currentText == "user manual edit - KEEP"
                tests.add(JSONObject().apply {
                    put("name", "conflict_detection_and_user_edit_preservation")
                    put("passed", ok)
                    put("conflict_detected", conflictDetected)
                    put("preserved_text", currentText)
                })
                if (!ok) overallSuccess = false
            } catch (e: Exception) {
                overallSuccess = false
                tests.add(JSONObject().apply {
                    put("name", "conflict_detection_and_user_edit_preservation")
                    put("passed", false)
                    put("error", e.message)
                })
            }

            // 5. Newly created file deletion on rollback
            try {
                val sessionId = UUID.randomUUID().toString()
                val newFile = File(sandboxRoot, "CreatedByAi.kt")
                AutonomousRollbackManager.startSession(sessionId, sandboxRoot)
                AutonomousRollbackManager.recordFilePreState(sessionId, sandboxRoot, newFile, "CreatedByAi.kt")

                newFile.writeText("class CreatedByAi")
                AutonomousRollbackManager.recordFilePostWrite(sessionId, sandboxRoot, newFile, "CreatedByAi.kt", "class CreatedByAi")

                val rollbackRes = AutonomousRollbackManager.rollbackSession(sessionId, sandboxRoot)
                val fileDeleted = !newFile.exists()
                val ok = rollbackRes.success && rollbackRes.revertedCount == 1 && fileDeleted
                tests.add(JSONObject().apply {
                    put("name", "new_file_deletion_on_rollback")
                    put("passed", ok)
                    put("file_deleted", fileDeleted)
                })
                if (!ok) overallSuccess = false
            } catch (e: Exception) {
                overallSuccess = false
                tests.add(JSONObject().apply {
                    put("name", "new_file_deletion_on_rollback")
                    put("passed", false)
                    put("error", e.message)
                })
            }

            // 6. TaskCheckpointManager persistence and recovery
            try {
                val chkDir = File(sandboxRoot, ".roxum/checkpoints")
                TaskCheckpointManager.setStorageDirectory(chkDir)
                val chkSession = "test_chk_session"
                val checkpoint = TaskCheckpoint(
                    taskId = chkSession,
                    objective = "Verify Task 23 Trust Engine on device",
                    acceptanceCriteria = listOf("Visual diff", "One-tap rollback"),
                    modifiedFiles = listOf("Calculator.kt", "Main.kt"),
                    projectPath = sandboxRoot.absolutePath
                )
                TaskCheckpointManager.recordCheckpoint(chkSession, checkpoint)
                val reloaded = TaskCheckpointManager.getCheckpoint(chkSession)
                val ok = reloaded != null && reloaded.objective == checkpoint.objective && reloaded.modifiedFiles.size == 2
                tests.add(JSONObject().apply {
                    put("name", "task_checkpoint_disk_persistence")
                    put("passed", ok)
                    put("reloaded_objective", reloaded?.objective)
                    put("files_count", reloaded?.modifiedFiles?.size)
                })
                if (!ok) overallSuccess = false
            } catch (e: Exception) {
                overallSuccess = false
                tests.add(JSONObject().apply {
                    put("name", "task_checkpoint_disk_persistence")
                    put("passed", false)
                    put("error", e.message)
                })
            }
        } finally {
            sandboxRoot.deleteRecursively()
        }

        val resultJson = JSONObject().apply {
            put("success", overallSuccess)
            put("timestamp", System.currentTimeMillis())
            put("device_metadata", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("android_release", Build.VERSION.RELEASE)
                put("sdk_int", Build.VERSION.SDK_INT)
                put("fingerprint", Build.FINGERPRINT)
            })
            val testsArr = JSONArray()
            tests.forEach { testsArr.put(it) }
            put("tests", testsArr)
        }

        val outFile = File(context.filesDir, "task23_trust_engine_verification_result.json")
        outFile.writeText(resultJson.toString(2), Charsets.UTF_8)
        Log.i(TAG, "On-device AI Trust Engine verification complete: success=$overallSuccess, tests=${tests.size}")

        setResultCode(if (overallSuccess) 0 else 1)
        setResultData(resultJson.toString())
    }
}
