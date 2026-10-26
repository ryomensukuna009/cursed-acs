package com.tom.rv2ide.artificial.memory

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MemoryType(val id: String, val displayName: String) {
    IDENTITY("identity", "Developer Identity"),
    WORKFLOW("workflow", "Workflow & Tool Preferences"),
    VOICE("voice", "Tone & Communication Style"),
    INSTRUCTION("instruction", "Persistent Rules & Directives");

    companion object {
        fun fromId(id: String?): MemoryType {
            return entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: WORKFLOW
        }
    }
}

enum class MemoryScope(val id: String) {
    GLOBAL("global"),
    PROJECT("project"),
    ASSISTANT("assistant");

    companion object {
        fun fromId(id: String?): MemoryScope {
            return entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: GLOBAL
        }
    }
}

data class MemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val type: MemoryType,
    val scope: MemoryScope = MemoryScope.GLOBAL,
    val content: String,
    val projectKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.id)
        put("scope", scope.id)
        put("content", content)
        put("projectKey", projectKey)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): MemoryEntry = MemoryEntry(
            id = obj.optString("id", UUID.randomUUID().toString()),
            type = MemoryType.fromId(obj.optString("type")),
            scope = MemoryScope.fromId(obj.optString("scope")),
            content = obj.optString("content", ""),
            projectKey = obj.optString("projectKey", ""),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
        )
    }
}

data class UserProfileField(
    val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("value", value)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): UserProfileField = UserProfileField(
            key = obj.optString("key", ""),
            value = obj.optString("value", ""),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
        )
    }
}
