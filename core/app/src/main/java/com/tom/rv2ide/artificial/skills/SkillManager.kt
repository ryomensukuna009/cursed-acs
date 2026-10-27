package com.tom.rv2ide.artificial.skills

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class SkillManager private constructor(context: Context) {

    private val appContext = context.applicationContext ?: context
    private val globalSkillsDir = File(appContext.filesDir, "skills")
    private val prefs by lazy {
        appContext.getSharedPreferences("agent_skills_prefs", Context.MODE_PRIVATE)
    }

    fun isSkillEnabled(skillId: String): Boolean {
        return prefs.getBoolean("skill_enabled_$skillId", true)
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean) {
        prefs.edit().putBoolean("skill_enabled_$skillId", enabled).apply()
    }

    init {
        if (!globalSkillsDir.exists()) globalSkillsDir.mkdirs()
        seedDefaultSkillIfEmpty()
    }

    /**
     * Scans both global `filesDir/skills/` and optional project-level `.agents/skills/`
     * returning all discovered valid skills.
     */
    @Synchronized
    fun discoverSkills(projectRoot: File? = null): List<SkillRecord> {
        val results = mutableListOf<SkillRecord>()

        // 1. Global skills
        results.addAll(scanDirectoryForSkills(globalSkillsDir))

        // 2. Project local skills (.agents/skills or .gemini/skills)
        if (projectRoot != null && projectRoot.isDirectory) {
            val projectAgentsSkills = File(projectRoot, ".agents/skills")
            if (projectAgentsSkills.exists()) {
                results.addAll(scanDirectoryForSkills(projectAgentsSkills))
            }
            val projectGeminiSkills = File(projectRoot, ".gemini/skills")
            if (projectGeminiSkills.exists()) {
                results.addAll(scanDirectoryForSkills(projectGeminiSkills))
            }
        }

        // Distinct by id
        return results.distinctBy { it.id }
    }

    private fun scanDirectoryForSkills(parentDir: File): List<SkillRecord> {
        val list = mutableListOf<SkillRecord>()
        if (!parentDir.exists() || !parentDir.isDirectory) return list

        val children = parentDir.listFiles() ?: return list
        for (child in children) {
            if (!child.isDirectory) continue
            val skillMd = File(child, "SKILL.md")
            if (skillMd.exists() && skillMd.isFile) {
                try {
                    val content = skillMd.readText()
                    val fm = SkillFrontmatter.parse(content)
                    val skillId = child.name.lowercase().replace(" ", "-")
                    val name = fm.name.ifBlank { child.name }
                    val desc = fm.description.ifBlank { "Skill in ${child.name}" }

                    list.add(
                        SkillRecord(
                            id = skillId,
                            name = name,
                            description = desc,
                            dirPath = child.absolutePath,
                            skillMdPath = skillMd.absolutePath,
                            enabled = isSkillEnabled(skillId)
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse skill at ${child.absolutePath}", e)
                }
            }
        }
        return list
    }

    /**
     * Imports a skill from an input stream containing a `.zip` archive.
     * Prevents Zip Slip vulnerabilities by verifying canonical output paths.
     */
    @Synchronized
    fun importSkillFromZip(zipInputStream: InputStream, targetSkillName: String? = null): SkillRecord? {
        val destDir = if (targetSkillName != null) {
            File(globalSkillsDir, targetSkillName.trim().lowercase().replace(" ", "-"))
        } else {
            File(globalSkillsDir, "imported-skill-${System.currentTimeMillis()}")
        }
        if (!destDir.exists()) destDir.mkdirs()

        val destCanonical = destDir.canonicalPath

        ZipInputStream(zipInputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val newFile = File(destDir, entry.name)
                // Security traversal check against Zip Slip
                if (!newFile.canonicalPath.startsWith(destCanonical)) {
                    Log.e(TAG, "Zip Slip rejection for entry: ${entry.name}")
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Look for SKILL.md in root of destDir or one level down
        var skillMd = File(destDir, "SKILL.md")
        var finalDir = destDir
        if (!skillMd.exists()) {
            val nested = destDir.listFiles()?.firstOrNull { it.isDirectory && File(it, "SKILL.md").exists() }
            if (nested != null) {
                skillMd = File(nested, "SKILL.md")
                finalDir = nested
            }
        }

        if (skillMd.exists()) {
            val fm = SkillFrontmatter.parse(skillMd.readText())
            return SkillRecord(
                id = finalDir.name.lowercase(),
                name = fm.name.ifBlank { finalDir.name },
                description = fm.description,
                dirPath = finalDir.absolutePath,
                skillMdPath = skillMd.absolutePath,
                enabled = true
            )
        }
        return null
    }

    /**
     * Builds the standard Antigravity/Claude `<available_skills>` prompt fragment.
     */
    fun buildAvailableSkillsPromptBlock(projectRoot: File? = null): String {
        val skills = discoverSkills(projectRoot).filter { it.enabled }
        if (skills.isEmpty()) return ""

        return buildString {
            append("<available_skills>\n")
            append("Use a skill when the task matches its description: call read_file on its SKILL.md first and follow it. Files referenced by a skill live in the same directory.\n")
            for (skill in skills.take(20)) {
                val desc = skill.description.take(240).replace("\n", " ").trim()
                append("- ${skill.id}: $desc (${skill.skillMdPath})\n")
            }
            append("</available_skills>\n\n")
        }
    }

    /**
     * Installs or registers a preset recommended skill directly into `globalSkillsDir`.
     */
    @Synchronized
    fun installPresetSkill(id: String, name: String, description: String, instructions: String): Boolean {
        return try {
            val skillDir = File(globalSkillsDir, id.trim().lowercase().replace(" ", "-"))
            if (!skillDir.exists()) skillDir.mkdirs()
            val skillMd = File(skillDir, "SKILL.md")
            val content = """---
name: $name
description: $description
---
# $name

$instructions
"""
            skillMd.writeText(content)
            setSkillEnabled(id.trim().lowercase().replace(" ", "-"), true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install preset skill $id", e)
            false
        }
    }

    private fun seedDefaultSkillIfEmpty() {
        val defaultDir = File(globalSkillsDir, "android-development")
        if (!defaultDir.exists()) {
            defaultDir.mkdirs()
            val skillMd = File(defaultDir, "SKILL.md")
            skillMd.writeText(
                """---
name: android-development
description: Best practices for Android native apps, Gradle builds, Compose UI, and NDK C++ integration in CURSED ACS.
---
# Android Development Skill

When editing Android projects:
1. Preserve Gradle namespace, package declarations, and applicationId.
2. In Kotlin/Java, import proper AndroidX and Material3 packages.
3. For NDK projects, check CMakeLists.txt target links (log, android, EGL, GLESv3).
4. Run incremental diagnostic checks before large architectural changes.
"""
            )
        }
    }

    companion object {
        private const val TAG = "SkillManager"

        @Volatile
        private var instance: SkillManager? = null

        fun getInstance(context: Context): SkillManager {
            return instance ?: synchronized(this) {
                instance ?: SkillManager(context).also { instance = it }
            }
        }
    }
}
