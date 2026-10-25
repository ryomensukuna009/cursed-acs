package com.tom.rv2ide.templates.preferences

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Unit tests validating TASK 2:
 * - "Remove from IDE" removes project registration without touching filesystem directory or files
 * - SHA-256 hashes of test files remain identical before and after removal
 * - Project can be unhidden / re-added to IDE
 */
class ProjectRemovalAndRecentPreferencesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        WizardPreferences.prefsOverrideForTesting = fakePrefs
    }

    @After
    fun tearDown() {
        WizardPreferences.prefsOverrideForTesting = null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testRemoveFromIdePreservesFilesAndHashes() {
        // 1. Create a mock project directory with source files
        val projectDir = tempFolder.newFolder("MyAndroidApp")
        val srcDir = File(projectDir, "src/main/java/com/example")
        srcDir.mkdirs()
        val codeFile = File(srcDir, "MainActivity.kt")
        codeFile.writeText("package com.example\nclass MainActivity {}")
        val buildGradle = File(projectDir, "build.gradle.kts")
        buildGradle.writeText("plugins { id(\"com.android.application\") }")

        val initialCodeHash = sha256(codeFile)
        val initialBuildHash = sha256(buildGradle)

        // 2. Add to recent projects
        WizardPreferences.addRecentProject(null, projectDir.absolutePath)
        assertTrue(WizardPreferences.isRecentProject(null, projectDir.absolutePath))
        assertFalse(WizardPreferences.isProjectHidden(null, projectDir.absolutePath))

        // 3. Perform "Remove from IDE"
        val removed = WizardPreferences.removeRecentProject(null, projectDir.absolutePath)
        WizardPreferences.hideProject(null, projectDir.canonicalPath)

        assertTrue("removeRecentProject should report true", removed)
        assertFalse("Should no longer be in recent list", WizardPreferences.isRecentProject(null, projectDir.absolutePath))
        assertTrue("Should be marked hidden in IDE", WizardPreferences.isProjectHidden(null, projectDir.canonicalPath))

        // 4. CRITICAL VERIFICATION: Files and directory MUST NOT be touched
        assertTrue("Project directory must still exist", projectDir.exists())
        assertTrue("MainActivity.kt must still exist", codeFile.exists())
        assertTrue("build.gradle.kts must still exist", buildGradle.exists())
        assertEquals("Code file hash must match perfectly", initialCodeHash, sha256(codeFile))
        assertEquals("Build file hash must match perfectly", initialBuildHash, sha256(buildGradle))

        // 5. Verify the project can later be imported / reopened
        WizardPreferences.addRecentProject(null, projectDir.absolutePath)
        assertTrue("Project re-added to recent projects", WizardPreferences.isRecentProject(null, projectDir.absolutePath))
        assertFalse("Project automatically unhidden upon re-adding", WizardPreferences.isProjectHidden(null, projectDir.canonicalPath))
    }

    @Test
    fun testHideAndUnhideProjectLifecycle() {
        val path = "C:/test/sample_project"

        assertFalse(WizardPreferences.isProjectHidden(null, path))

        WizardPreferences.hideProject(null, path)
        assertTrue(WizardPreferences.isProjectHidden(null, path))

        WizardPreferences.unhideProject(null, path)
        assertFalse(WizardPreferences.isProjectHidden(null, path))
    }

    class FakeSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            (map[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                temp[key ?: ""] = value
                return this
            }
            override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
                temp[key ?: ""] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                temp[key ?: ""] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                temp[key ?: ""] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                temp[key ?: ""] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                temp[key ?: ""] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                temp[key ?: ""] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clear = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clear) prefs.map.clear()
                for ((k, v) in temp) {
                    if (v == null) prefs.map.remove(k) else prefs.map[k] = v
                }
            }
        }
    }
}
