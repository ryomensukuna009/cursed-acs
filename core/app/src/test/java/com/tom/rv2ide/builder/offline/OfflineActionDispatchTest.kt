/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.builder.offline

import com.tom.rv2ide.preferences.internal.BuildPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineActionDispatchTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun test01_buildPreferencesModeConstants() {
    assertEquals("gradle", BuildPreferences.BUILD_MODE_GRADLE)
    assertEquals("offline_ndk", BuildPreferences.BUILD_MODE_OFFLINE_NDK)
    assertEquals("idepref_build_mode", BuildPreferences.BUILD_MODE)
  }

  @Test
  fun test02_engineFallbackTmpDirHandling() {
    val projectDir = tempFolder.newFolder("sample_proj")
    val manifest = File(projectDir, "AndroidManifest.xml")
    manifest.writeText(
        """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.test">
    <application android:label="Test"/>
</manifest>"""
    )
    val srcDir = File(projectDir, "src/com/example/test")
    srcDir.mkdirs()
    File(srcDir, "MainActivity.java").writeText("package com.example.test; public class MainActivity {}")

    val invalidTmpDir = File(tempFolder.root, "does_not_exist/deep_sub/invalid_dir")

    val engine = OfflineBuildEngine()
    val contract = engine.inspectProject(projectDir)
    assertNotNull("Contract should be valid", contract)
    assertEquals("com.example.test", contract!!.packageName)

    val nonExistentProject = File(tempFolder.root, "missing_proj")
    val invalidContract = engine.inspectProject(nonExistentProject)
    assertNull("Non-existent project should return null contract", invalidContract)
  }

  @Test
  fun test03_invalidProjectInspectionDoesNotProceedToBuild() {
    val projectDir = tempFolder.newFolder("empty_project")
    val engine = OfflineBuildEngine()

    val result = engine.build(projectDir)
    assertFalse("Empty project build must fail at INSPECT stage", result.success)
    assertEquals(OfflineBuildStage.INSPECT, result.failedStage)
    assertNotNull("Error message must explain failure", result.errorMessage)
  }
}
