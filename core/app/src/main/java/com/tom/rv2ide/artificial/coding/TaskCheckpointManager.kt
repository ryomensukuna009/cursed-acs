package com.tom.rv2ide.artificial.coding

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class TaskCheckpoint(
    val taskId: String,
    val objective: String,
    val acceptanceCriteria: List<String> = emptyList(),
    val currentPlan: List<String> = emptyList(),
    val completedSteps: List<String> = emptyList(),
    val modifiedFiles: List<String> = emptyList(),
    val verificationEvidence: String = "",
    val knownBlockers: List<String> = emptyList(),
    val nextAction: String = "",
    val projectPath: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toMarkdownSummary(): String = buildString {
        appendLine("### 📌 Task State & Context Checkpoint")
        appendLine("**Objective:** $objective")
        if (projectPath.isNotBlank()) {
            appendLine("**Project:** `$projectPath`")
        }
        if (acceptanceCriteria.isNotEmpty()) {
            appendLine("\n**Acceptance Criteria:**")
            acceptanceCriteria.forEach { appendLine("- $it") }
        }
        if (completedSteps.isNotEmpty()) {
            appendLine("\n**Completed Milestones:**")
            completedSteps.forEach { appendLine("- [x] $it") }
        }
        if (modifiedFiles.isNotEmpty()) {
            appendLine("\n**Active Modified Files:**")
            modifiedFiles.forEach { appendLine("- `$it`") }
        }
        if (verificationEvidence.isNotBlank()) {
            appendLine("\n**Verification Evidence:** $verificationEvidence")
        }
        if (knownBlockers.isNotEmpty()) {
            appendLine("\n**Blockers / Issues:**")
            knownBlockers.forEach { appendLine("- ⚠️ $it") }
        }
        if (nextAction.isNotBlank()) {
            appendLine("\n**Next Concrete Step:** $nextAction")
        }
    }.trim()

    fun toJson(): JSONObject = JSONObject().apply {
        put("taskId", taskId)
        put("objective", objective)
        put("acceptanceCriteria", JSONArray(acceptanceCriteria))
        put("currentPlan", JSONArray(currentPlan))
        put("completedSteps", JSONArray(completedSteps))
        put("modifiedFiles", JSONArray(modifiedFiles))
        put("verificationEvidence", verificationEvidence)
        put("knownBlockers", JSONArray(knownBlockers))
        put("nextAction", nextAction)
        put("projectPath", projectPath)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(json: JSONObject): TaskCheckpoint {
            val criteria = mutableListOf<String>()
            val cArr = json.optJSONArray("acceptanceCriteria")
            if (cArr != null) {
                for (i in 0 until cArr.length()) criteria.add(cArr.optString(i))
            }

            val plan = mutableListOf<String>()
            val pArr = json.optJSONArray("currentPlan")
            if (pArr != null) {
                for (i in 0 until pArr.length()) plan.add(pArr.optString(i))
            }

            val steps = mutableListOf<String>()
            val sArr = json.optJSONArray("completedSteps")
            if (sArr != null) {
                for (i in 0 until sArr.length()) steps.add(sArr.optString(i))
            }

            val files = mutableListOf<String>()
            val fArr = json.optJSONArray("modifiedFiles")
            if (fArr != null) {
                for (i in 0 until fArr.length()) files.add(fArr.optString(i))
            }

            val blockers = mutableListOf<String>()
            val bArr = json.optJSONArray("knownBlockers")
            if (bArr != null) {
                for (i in 0 until bArr.length()) blockers.add(bArr.optString(i))
            }

            return TaskCheckpoint(
                taskId = json.optString("taskId", "task_0"),
                objective = json.optString("objective", "Ongoing Task"),
                acceptanceCriteria = criteria,
                currentPlan = plan,
                completedSteps = steps,
                modifiedFiles = files,
                verificationEvidence = json.optString("verificationEvidence", ""),
                knownBlockers = blockers,
                nextAction = json.optString("nextAction", ""),
                projectPath = json.optString("projectPath", ""),
                timestamp = json.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}

object TaskCheckpointManager {

    private val checkpointsBySession = ConcurrentHashMap<String, TaskCheckpoint>()

    @Volatile
    private var storageDir: File? = null

    fun setStorageDirectory(dir: File) {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        storageDir = dir
    }

    fun getStorageDirectory(): File? = storageDir

    fun recordCheckpoint(sessionId: String, checkpoint: TaskCheckpoint, projectPath: String = "") {
        val effectiveProject = if (projectPath.isNotBlank()) projectPath else checkpoint.projectPath
        val scopedCheckpoint = if (checkpoint.projectPath.isBlank() && effectiveProject.isNotBlank()) {
            checkpoint.copy(projectPath = effectiveProject)
        } else {
            checkpoint
        }
        checkpointsBySession[sessionId] = scopedCheckpoint
        persistCheckpointToDisk(sessionId, scopedCheckpoint)
    }

    fun getCheckpoint(sessionId: String): TaskCheckpoint? {
        val inMemory = checkpointsBySession[sessionId]
        if (inMemory != null) return inMemory
        return loadCheckpointFromDisk(sessionId)?.also {
            checkpointsBySession[sessionId] = it
        }
    }

    fun clearCheckpoint(sessionId: String) {
        checkpointsBySession.remove(sessionId)
        storageDir?.let { dir ->
            try {
                val f = File(dir, sanitizeFileName(sessionId) + ".json")
                if (f.exists()) f.delete()
            } catch (_: Exception) {
            }
        }
    }

    fun clearCheckpointsForProject(projectPath: String) {
        val norm = sanitizeFileName(projectPath)
        val toRemove = checkpointsBySession.filter { sanitizeFileName(it.value.projectPath) == norm }.keys
        toRemove.forEach { clearCheckpoint(it) }
    }

    fun getLatestCheckpoint(projectPath: String = ""): TaskCheckpoint? {
        val inMemoryLatest = if (projectPath.isBlank()) {
            checkpointsBySession.values.maxByOrNull { it.timestamp }
        } else {
            val norm = sanitizeFileName(projectPath)
            checkpointsBySession.values.filter { sanitizeFileName(it.projectPath) == norm }.maxByOrNull { it.timestamp }
        }
        val diskLatest = loadLatestFromDisk(projectPath)
        return when {
            inMemoryLatest == null -> diskLatest
            diskLatest == null -> inMemoryLatest
            diskLatest.timestamp > inMemoryLatest.timestamp -> {
                checkpointsBySession[diskLatest.taskId] = diskLatest
                diskLatest
            }
            else -> inMemoryLatest
        }
    }

    fun clearAll() {
        checkpointsBySession.clear()
    }

    fun getAllCheckpoints(projectPath: String = ""): List<TaskCheckpoint> {
        val resultByTaskId = LinkedHashMap<String, TaskCheckpoint>()
        for (cp in checkpointsBySession.values) {
            resultByTaskId[cp.taskId] = cp
        }
        storageDir?.listFiles { _, name -> name.endsWith(".json") }?.forEach { f ->
            try {
                val obj = JSONObject(f.readText())
                val cp = TaskCheckpoint.fromJson(obj)
                if (!resultByTaskId.containsKey(cp.taskId)) {
                    resultByTaskId[cp.taskId] = cp
                }
            } catch (_: Exception) {
            }
        }
        val filtered = if (projectPath.isBlank()) {
            resultByTaskId.values.toList()
        } else {
            val norm = sanitizeFileName(projectPath)
            resultByTaskId.values.filter { sanitizeFileName(it.projectPath) == norm }
        }
        return filtered.sortedByDescending { it.timestamp }
    }

    private fun persistCheckpointToDisk(sessionId: String, checkpoint: TaskCheckpoint) {
        val dir = storageDir ?: return
        try {
            if (!dir.exists()) dir.mkdirs()
            val targetFile = File(dir, sanitizeFileName(sessionId) + ".json")
            val tmpFile = File(dir, sanitizeFileName(sessionId) + ".json.tmp")
            tmpFile.writeText(checkpoint.toJson().toString())
            if (!tmpFile.renameTo(targetFile)) {
                targetFile.writeText(checkpoint.toJson().toString())
                tmpFile.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun loadCheckpointFromDisk(sessionId: String): TaskCheckpoint? {
        val dir = storageDir ?: return null
        return try {
            val f = File(dir, sanitizeFileName(sessionId) + ".json")
            if (!f.exists()) return null
            val obj = JSONObject(f.readText())
            TaskCheckpoint.fromJson(obj)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadLatestFromDisk(projectPath: String = ""): TaskCheckpoint? {
        val dir = storageDir ?: return null
        return try {
            val files = dir.listFiles { _, name -> name.endsWith(".json") } ?: return null
            val all = files.mapNotNull { f ->
                try {
                    TaskCheckpoint.fromJson(JSONObject(f.readText()))
                } catch (_: Exception) {
                    null
                }
            }
            if (projectPath.isBlank()) {
                all.maxByOrNull { it.timestamp }
            } else {
                val norm = sanitizeFileName(projectPath)
                all.filter { sanitizeFileName(it.projectPath) == norm }.maxByOrNull { it.timestamp }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
