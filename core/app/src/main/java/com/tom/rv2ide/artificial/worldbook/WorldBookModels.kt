package com.tom.rv2ide.artificial.worldbook

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class WorldBookPosition(val id: String) {
    BEFORE_SYSTEM_PROMPT("before_system_prompt"),
    AFTER_SYSTEM_PROMPT("after_system_prompt");

    companion object {
        fun fromId(id: String?): WorldBookPosition {
            return entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: AFTER_SYSTEM_PROMPT
        }
    }
}

data class WorldBookEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 100,
    val content: String,
    val keywords: List<String> = emptyList(),
    val useRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val constantActive: Boolean = false,
    val position: WorldBookPosition = WorldBookPosition.AFTER_SYSTEM_PROMPT
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("priority", priority)
        put("content", content)
        put("keywords", JSONArray(keywords))
        put("useRegex", useRegex)
        put("caseSensitive", caseSensitive)
        put("constantActive", constantActive)
        put("position", position.id)
    }

    companion object {
        fun fromJson(obj: JSONObject): WorldBookEntry {
            val kwArr = obj.optJSONArray("keywords") ?: JSONArray()
            val kws = (0 until kwArr.length()).mapNotNull { kwArr.optString(it).takeIf { s -> s.isNotBlank() } }
            return WorldBookEntry(
                id = obj.optString("id", UUID.randomUUID().toString()),
                name = obj.optString("name", "Unnamed Entry"),
                enabled = obj.optBoolean("enabled", true),
                priority = obj.optInt("priority", 100),
                content = obj.optString("content", ""),
                keywords = kws,
                useRegex = obj.optBoolean("useRegex", false),
                caseSensitive = obj.optBoolean("caseSensitive", false),
                constantActive = obj.optBoolean("constantActive", false),
                position = WorldBookPosition.fromId(obj.optString("position"))
            )
        }
    }
}

data class WorldBook(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<WorldBookEntry> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("enabled", enabled)
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        put("entries", arr)
    }

    companion object {
        fun fromJson(obj: JSONObject): WorldBook {
            val entriesArr = obj.optJSONArray("entries") ?: JSONArray()
            val list = (0 until entriesArr.length()).mapNotNull {
                entriesArr.optJSONObject(it)?.let { eObj -> WorldBookEntry.fromJson(eObj) }
            }
            return WorldBook(
                id = obj.optString("id", UUID.randomUUID().toString()),
                name = obj.optString("name", "New World Book"),
                description = obj.optString("description", ""),
                enabled = obj.optBoolean("enabled", true),
                entries = list
            )
        }
    }
}
