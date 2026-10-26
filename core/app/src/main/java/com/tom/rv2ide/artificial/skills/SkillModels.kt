package com.tom.rv2ide.artificial.skills

import org.json.JSONObject
import java.io.File

data class SkillFrontmatter(
    val name: String,
    val description: String,
    val body: String
) {
    companion object {
        fun parse(markdown: String): SkillFrontmatter {
            var text = markdown.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
            val lines = text.lines()

            var name = ""
            var description = ""
            var body = text

            if (lines.isNotEmpty() && lines.first().trim() == "---") {
                val endIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
                if (endIdx != -1) {
                    val yamlLines = lines.subList(1, endIdx + 1)
                    body = lines.drop(endIdx + 2).joinToString("\n")

                    for (yLine in yamlLines) {
                        val trimmed = yLine.trim()
                        if (trimmed.startsWith("name:", ignoreCase = true)) {
                            name = trimmed.substringAfter(":").trim(' ', '"', '\'')
                        } else if (trimmed.startsWith("description:", ignoreCase = true)) {
                            description = trimmed.substringAfter(":").trim(' ', '"', '\'')
                        }
                    }
                }
            }

            if (name.isBlank()) {
                val firstHeader = lines.firstOrNull { it.trimStart().startsWith("#") }
                name = firstHeader?.trimStart('#', ' ') ?: "unnamed-skill"
            }

            return SkillFrontmatter(name = name, description = description, body = body)
        }
    }
}

data class SkillRecord(
    val id: String,
    val name: String,
    val description: String,
    val dirPath: String,
    val skillMdPath: String,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("dirPath", dirPath)
        put("skillMdPath", skillMdPath)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(obj: JSONObject): SkillRecord = SkillRecord(
            id = obj.optString("id", ""),
            name = obj.optString("name", ""),
            description = obj.optString("description", ""),
            dirPath = obj.optString("dirPath", ""),
            skillMdPath = obj.optString("skillMdPath", ""),
            enabled = obj.optBoolean("enabled", true)
        )
    }
}
