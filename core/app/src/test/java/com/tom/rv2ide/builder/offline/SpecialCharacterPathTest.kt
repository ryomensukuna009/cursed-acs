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

import com.tom.rv2ide.artificial.coding.BuildDiagnostic
import com.tom.rv2ide.artificial.coding.StructuredDiagnosticCollector
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Acceptance test suite for TASK 7 (Special-Character Build Paths Support):
 * Verifies robust handling of project paths containing spaces, parentheses, '@',
 * non-ASCII characters, and shell metacharacters without triggering shell syntax errors.
 */
class SpecialCharacterPathTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun test01_shQuote_escapesSpecialCharactersCorrectly() {
    assertEquals("''", NdkNativeBuilder.shQuote(""))
    assertEquals("hello", NdkNativeBuilder.shQuote("hello"))
    assertEquals("lib_ndk-v1.0.so", NdkNativeBuilder.shQuote("lib_ndk-v1.0.so"))
    assertEquals("/data/user/0/com.tom.rv2ide/ndk", NdkNativeBuilder.shQuote("/data/user/0/com.tom.rv2ide/ndk"))

    // Paths with parentheses and '@' (exact user scenario)
    val userPath = "/storage/emulated/0/ACS_SRC/ZYGISK_SRC_RAMA_MODZ(TG@RAMA_MODZ)"
    val quotedUserPath = NdkNativeBuilder.shQuote(userPath)
    assertEquals("'$userPath'", quotedUserPath)

    // Paths with spaces
    val spacePath = "/storage/emulated/0/My Android Projects/App One"
    assertEquals("'$spacePath'", NdkNativeBuilder.shQuote(spacePath))

    // Paths with single quotes
    val singleQuotePath = "app's_module"
    assertEquals("'app'\\''s_module'", NdkNativeBuilder.shQuote(singleQuotePath))
  }

  @Test
  fun test02_safePathBridge_preservesFilesAndProtectsAgainstDeletion() {
    val srcDir = tempFolder.newFolder("ZYGISK_SRC_RAMA_MODZ(TG@RAMA_MODZ)")
    val jniDir = File(srcDir, "app/src/main/jni")
    jniDir.mkdirs()
    val testSource = File(jniDir, "imgui.cpp")
    testSource.writeText("int imgui_render() { return 100; }")

    val baseDir = tempFolder.newFolder("workspace_base")
    val workspace = OfflineWorkspace.create(baseDir, srcDir.name)
    assertTrue("Workspace must prepare cleanly", workspace.prepare())

    val builder = NdkNativeBuilder()
    val bridgeDir = workspace.projectBridgeDir

    val bridged = builder.createSafePathBridge(srcDir, bridgeDir, null)
    assertTrue("Safe path bridge creation should succeed", bridged)
    assertTrue("Bridge directory must exist", bridgeDir.exists())

    // Verify files can be read through the bridge
    val bridgedSource = File(bridgeDir, "app/src/main/jni/imgui.cpp")
    assertTrue("Source file must be accessible via bridge", bridgedSource.exists())
    assertEquals("int imgui_render() { return 100; }", bridgedSource.readText())

    // Now clean the workspace: this MUST delete the bridge without deleting the original source!
    workspace.clean()
    assertFalse("Bridge directory should be removed", bridgeDir.exists())
    assertFalse("Workspace root should be removed", workspace.rootDir.exists())

    // CRITICAL: The user's original directory and file must be 100% intact!
    assertTrue("Original project directory MUST still exist", srcDir.exists())
    assertTrue("Original test source file MUST still exist", testSource.exists())
    assertEquals("int imgui_render() { return 100; }", testSource.readText())
  }

  @Test
  fun test03_offlineWorkspace_sanitizesProjectNamesWithParenthesesAndSpecialChars() {
    val baseDir = tempFolder.newFolder("workspaces")
    val dirtyName = "ZYGISK_SRC_RAMA_MODZ(TG@RAMA_MODZ)"
    val workspace = OfflineWorkspace.create(baseDir, dirtyName)

    assertFalse("Workspace root path MUST NOT contain '('", workspace.rootDir.name.contains("("))
    assertFalse("Workspace root path MUST NOT contain ')'", workspace.rootDir.name.contains(")"))
    assertFalse("Workspace root path MUST NOT contain '@'", workspace.rootDir.name.contains("@"))
    assertTrue("Workspace root name must contain sanitized identifier", workspace.rootDir.name.contains("ZYGISK_SRC_RAMA_MODZ_TG_RAMA_MODZ_"))

    assertTrue(workspace.prepare())
    assertTrue(workspace.nativeOutDir.exists())
    assertTrue(workspace.libsOutDir.exists())

    workspace.clean()
    assertFalse(workspace.rootDir.exists())
  }

  @Test
  fun test04_offlineProjectInspector_acceptsProjectsWithParenthesesAndAtSymbol() {
    val projectDir = tempFolder.newFolder("ZYGISK_SRC_RAMA_MODZ(TG@RAMA_MODZ)")
    val appDir = File(projectDir, "app/src/main")
    appDir.mkdirs()

    val manifest = File(appDir, "AndroidManifest.xml")
    manifest.writeText(
        """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.rama.modz">
    <application android:label="RamaModz"/>
</manifest>"""
    )

    val javaDir = File(appDir, "java/com/rama/modz")
    javaDir.mkdirs()
    File(javaDir, "Main.java").writeText("package com.rama.modz; public class Main {}")

    val jniDir = File(appDir, "jni")
    jniDir.mkdirs()
    val androidMk = File(jniDir, "Android.mk")
    androidMk.writeText(
        """LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := RAMA_MODZ
LOCAL_SRC_FILES := imgui.cpp
include $(BUILD_SHARED_LIBRARY)"""
    )

    val inspector = OfflineProjectInspector()
    val result = inspector.inspect(projectDir)

    assertTrue("Inspector must accept project with parentheses in path", result is ProjectContractResult.Valid)
    val contract = (result as ProjectContractResult.Valid).contract
    assertEquals("com.rama.modz", contract.packageName)
    assertTrue("Native code must be recognized", contract.hasNativeCode)
    assertNotNull(contract.androidMkFile)
    assertEquals(androidMk.canonicalPath, contract.androidMkFile!!.canonicalPath)
  }

  @Test
  fun test05_structuredDiagnosticCollector_parsesShellSyntaxErrorsAccurately() {
    val shellErrorRaw = """
      Invoking ndk-build for target ABI(s): arm64-v8a...
      /system/bin/sh: syntax error: unexpected '('
      make: *** [/data/user/0/com.tom.rv2ide/files/home/android-sdk/ndk/aide_ndk_arm64/build/core/build-binary.mk:530: error] Error 1
      BUILD FAILED in 3447ms
    """.trimIndent()

    val diags = StructuredDiagnosticCollector.parseBuildOutput(shellErrorRaw)
    assertTrue("At least one diagnostic should be parsed", diags.isNotEmpty())

    val shellDiag = diags.find { it.stage == "SHELL_EXEC" }
    assertNotNull("A SHELL_EXEC diagnostic must be produced for /system/bin/sh syntax errors", shellDiag)
    assertEquals(BuildDiagnostic.Severity.ERROR, shellDiag!!.severity)
    assertTrue("Message must indicate shell syntax error", shellDiag.message.contains("syntax error"))
  }
}
