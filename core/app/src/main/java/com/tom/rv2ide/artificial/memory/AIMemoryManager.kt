package com.tom.rv2ide.artificial.memory

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AIMemoryManager private constructor(context: Context) {

    private val appContext = context.applicationContext ?: context
    private val memoryDir = File(appContext.filesDir, "ai_memory")
    private val memoryFile = File(memoryDir, "memories.json")
    private val profileFile = File(memoryDir, "profile.json")

    private val memories = mutableListOf<MemoryEntry>()
    private val profileFields = mutableMapOf<String, UserProfileField>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    init {
        loadFromDisk()
    }

    @Synchronized
    fun getAllMemories(): List<MemoryEntry> = memories.toList()

    @Synchronized
    fun getProfile(): Map<String, UserProfileField> = profileFields.toMap()

    @Synchronized
    fun addOrUpdateMemory(
        type: MemoryType,
        content: String,
        scope: MemoryScope = MemoryScope.GLOBAL,
        projectKey: String = ""
    ): MemoryEntry {
        val clean = content.trim()
        val existing = memories.firstOrNull { it.type == type && it.content.equals(clean, ignoreCase = true) }
        val entry = if (existing != null) {
            existing.copy(updatedAt = System.currentTimeMillis())
        } else {
            MemoryEntry(
                type = type,
                scope = scope,
                content = clean,
                projectKey = projectKey,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        }

        if (existing != null) {
            val idx = memories.indexOf(existing)
            memories[idx] = entry
        } else {
            memories.add(entry)
        }

        saveToDisk()
        return entry
    }

    @Synchronized
    fun deleteMemory(id: String): Boolean {
        val removed = memories.removeAll { it.id == id }
        if (removed) {
            saveToDisk()
        }
        return removed
    }

    @Synchronized
    fun setProfileField(key: String, value: String) {
        val cleanKey = key.trim().lowercase()
        val cleanValue = value.trim()
        if (cleanValue.isEmpty()) {
            profileFields.remove(cleanKey)
        } else {
            profileFields[cleanKey] = UserProfileField(cleanKey, cleanValue, System.currentTimeMillis())
        }
        saveToDisk()
    }

    /**
     * Constructs the structured long-term memory prompt block injected into the system prompt.
     */
    @Synchronized
    fun buildMemoryPromptBlock(projectKey: String = ""): String {
        val sb = StringBuilder()

        // 1. User Profile block
        if (profileFields.isNotEmpty()) {
            sb.append("<user_profile>\n")
            profileFields.values.sortedBy { it.key }.forEach { field ->
                sb.append("- ").append(field.key).append(": ").append(field.value).append('\n')
            }
            sb.append("</user_profile>\n\n")
        }

        // 2. Memory blocks by category
        val visibleMemories = memories.filter { m ->
            m.scope == MemoryScope.GLOBAL || m.projectKey.isBlank() || m.projectKey == projectKey
        }

        if (visibleMemories.isNotEmpty()) {
            for (type in MemoryType.entries) {
                val items = visibleMemories.filter { it.type == type }
                if (items.isEmpty()) continue

                sb.append("<user_memory type=\"").append(type.id).append("\">\n")
                items.sortedByDescending { it.updatedAt }.forEach { entry ->
                    val dateStr = dateFormat.format(Date(entry.updatedAt))
                    sb.append("- [").append(dateStr).append("] ")
                    if (entry.scope == MemoryScope.PROJECT) {
                        sb.append("(project) ")
                    }
                    sb.append(entry.content).append('\n')
                }
                sb.append("</user_memory>\n\n")
            }
        }

        return sb.toString().trim()
    }

    private fun loadFromDisk() {
        try {
            if (!memoryDir.exists()) memoryDir.mkdirs()

            if (memoryFile.exists()) {
                val jsonStr = memoryFile.readText()
                val arr = JSONArray(jsonStr)
                memories.clear()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { obj ->
                        memories.add(MemoryEntry.fromJson(obj))
                    }
                }
            }

            if (profileFile.exists()) {
                val jsonStr = profileFile.readText()
                val obj = JSONObject(jsonStr)
                profileFields.clear()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    obj.optJSONObject(k)?.let { fObj ->
                        profileFields[k] = UserProfileField.fromJson(fObj)
                    }
                }
            }

            // Seed helpful initial developer defaults if empty
            if (memories.isEmpty()) {
                memories.add(
                    MemoryEntry(
                        type = MemoryType.INSTRUCTION,
                        scope = MemoryScope.GLOBAL,
                        content = "Always write clean, indentation-accurate Kotlin/Java/C++ code. Do not use truncation placeholders."
                    )
                )
                memories.add(
                    MemoryEntry(
                        type = MemoryType.WORKFLOW,
                        scope = MemoryScope.GLOBAL,
                        content = "CURSED ACS mobile IDE runs on physical Android devices. Prefer lightweight atomic patches and verify imports."
                    )
                )
                saveToDisk()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load memories from disk", e)
        }
    }

    private fun saveToDisk() {
        try {
            if (!memoryDir.exists()) memoryDir.mkdirs()

            val memArr = JSONArray()
            memories.forEach { memArr.put(it.toJson()) }
            memoryFile.writeText(memArr.toString(2))

            val profObj = JSONObject()
            profileFields.forEach { (k, v) -> profObj.put(k, v.toJson()) }
            profileFile.writeText(profObj.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save memories to disk", e)
        }
    }

    companion object {
        private const val TAG = "AIMemoryManager"

        @Volatile
        private var instance: AIMemoryManager? = null

        fun getInstance(context: Context): AIMemoryManager {
            return instance ?: synchronized(this) {
                instance ?: AIMemoryManager(context).also { instance = it }
            }
        }
    }
}
