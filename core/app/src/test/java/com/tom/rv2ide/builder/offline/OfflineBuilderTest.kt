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

import com.android.apksig.ApkVerifier
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineBuilderTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun test01_validAideEclipseProjectContract() {
    val projectDir = tempFolder.newFolder("aide_project")
    val manifest = File(projectDir, "AndroidManifest.xml")
    manifest.writeText(
        """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.aide.sample"
    android:versionCode="42"
    android:versionName="2.1">
    <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="33"/>
    <application android:label="SampleApp"/>
</manifest>"""
    )

    val srcDir = File(projectDir, "src/com/aide/sample")
    srcDir.mkdirs()
    File(srcDir, "MainActivity.java").writeText("package com.aide.sample; public class MainActivity {}")

    val resDir = File(projectDir, "res/values")
    resDir.mkdirs()
    File(resDir, "strings.xml").writeText("<resources><string name=\"app_name\">SampleApp</string></resources>")

    val inspector = OfflineProjectInspector()
    val result = inspector.inspect(projectDir)

    assertTrue("Expected valid contract for AIDE/Eclipse project", result is ProjectContractResult.Valid)
    val contract = (result as ProjectContractResult.Valid).contract
    assertEquals("com.aide.sample", contract.packageName)
    assertEquals(42, contract.versionCode)
    assertEquals("2.1", contract.versionName)
    assertEquals(21, contract.minSdkVersion)
    assertEquals(33, contract.targetSdkVersion)
    assertEquals(1, contract.javaFiles.size)
    assertFalse(contract.hasNativeCode)
  }

  @Test
  fun test02_validAndroidStudioProjectContractWithJni() {
    val projectDir = tempFolder.newFolder("as_project")
    val appDir = File(projectDir, "app")
    val manifest = File(appDir, "src/main/AndroidManifest.xml")
    manifest.parentFile?.mkdirs()
    manifest.writeText(
        """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.nativeapp">
    <application android:label="NativeApp"/>
</manifest>"""
    )

    val javaDir = File(appDir, "src/main/java/com/example/nativeapp")
    javaDir.mkdirs()
    File(javaDir, "NativeApp.java").writeText("package com.example.nativeapp; public class NativeApp {}")

    val jniDir = File(appDir, "src/main/jni")
    jniDir.mkdirs()
    val androidMk = File(jniDir, "Android.mk")
    androidMk.writeText(
        """LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := testnative
LOCAL_SRC_FILES := native.c
include $(BUILD_SHARED_LIBRARY)"""
    )

    val inspector = OfflineProjectInspector()
    val result = inspector.inspect(projectDir)

    assertTrue("Expected valid contract for AS project with JNI", result is ProjectContractResult.Valid)
    val contract = (result as ProjectContractResult.Valid).contract
    assertEquals("com.example.nativeapp", contract.packageName)
    assertTrue("Native code should be detected", contract.hasNativeCode)
    assertNotNull(contract.androidMkFile)
    assertEquals(androidMk.canonicalPath, contract.androidMkFile!!.canonicalPath)
  }

  @Test
  fun test03_rejectKotlinProject() {
    val projectDir = tempFolder.newFolder("kotlin_project")
    val manifest = File(projectDir, "AndroidManifest.xml")
    manifest.writeText("<manifest package=\"com.kt.app\"/>")

    val srcDir = File(projectDir, "src/com/kt/app")
    srcDir.mkdirs()
    File(srcDir, "MainActivity.kt").writeText("package com.kt.app\nclass MainActivity")
    File(srcDir, "JavaHelper.java").writeText("package com.kt.app; class JavaHelper {}")

    val inspector = OfflineProjectInspector()
    val result = inspector.inspect(projectDir)

    assertTrue("Expected rejection of Kotlin project", result is ProjectContractResult.Invalid)
    val invalid = result as ProjectContractResult.Invalid
    assertTrue(invalid.reason.contains("Kotlin", ignoreCase = true))
  }

  @Test
  fun test04_rejectComposeProject() {
    val projectDir = tempFolder.newFolder("compose_project")
    val manifest = File(projectDir, "AndroidManifest.xml")
    manifest.writeText("<manifest package=\"com.compose.app\"/>")

    val buildGradle = File(projectDir, "build.gradle")
    buildGradle.writeText("dependencies { implementation 'androidx.compose.ui:ui:1.0.0' }")

    val srcDir = File(projectDir, "src/com/compose/app")
    srcDir.mkdirs()
    File(srcDir, "MainActivity.java").writeText("package com.compose.app; class MainActivity {}")

    val inspector = OfflineProjectInspector()
    val result = inspector.inspect(projectDir)

    assertTrue("Expected rejection of Compose project", result is ProjectContractResult.Invalid)
    val invalid = result as ProjectContractResult.Invalid
    assertTrue(invalid.reason.contains("Compose", ignoreCase = true))
  }

  @Test
  fun test05_rejectProjectWithoutJavaSources() {
    val projectDir = tempFolder.newFolder("empty_source_project")
    val manifest = File(projectDir, "AndroidManifest.xml")
    manifest.writeText("<manifest package=\"com.empty.app\"/>")

    val srcDir = File(projectDir, "src")
    srcDir.mkdirs()

    val inspector = OfflineProjectInspector()
    val result = inspector.inspect(projectDir)

    assertTrue("Expected rejection for lack of Java sources", result is ProjectContractResult.Invalid)
  }

  @Test
  fun test06_workspacePreparationAndClean() {
    val baseDir = tempFolder.newFolder("workspace_test")
    val workspace = OfflineWorkspace.create(baseDir, "TestApp")

    assertTrue(workspace.prepare())
    assertTrue(workspace.genDir.exists())
    assertTrue(workspace.classesDir.exists())
    assertTrue(workspace.dexDir.exists())
    assertTrue(workspace.libsOutDir.exists())
    assertTrue(workspace.binDir.exists())
    assertTrue(workspace.outputDir.exists())

    workspace.clean()
    assertFalse(workspace.rootDir.exists())
  }

  @Test
  fun test07_apkZipAligner_4ByteAnd16KbPageAlignment() {
    val workDir = tempFolder.newFolder("aligner_test")

    // Create a mock resources.ap_
    val resourcesAp = File(workDir, "resources.ap_")
    ZipOutputStream(resourcesAp.outputStream()).use { zos ->
      // STORED resources.arsc
      val arscData = "Mock resources.arsc content for testing".toByteArray(StandardCharsets.UTF_8)
      val entryArsc = ZipEntry("resources.arsc").apply {
        method = ZipEntry.STORED
        size = arscData.size.toLong()
        compressedSize = arscData.size.toLong()
        val crc = java.util.zip.CRC32()
        crc.update(arscData)
        setCrc(crc.value)
      }
      zos.putNextEntry(entryArsc)
      zos.write(arscData)
      zos.closeEntry()

      // DEFLATED AndroidManifest.xml
      val manifestData = "<manifest></manifest>".toByteArray(StandardCharsets.UTF_8)
      val entryManifest = ZipEntry("AndroidManifest.xml")
      zos.putNextEntry(entryManifest)
      zos.write(manifestData)
      zos.closeEntry()
    }

    // Create mock classes.dex
    val dexFile = File(workDir, "classes.dex")
    dexFile.writeBytes(byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00) + "Mock Dex Content Byte Sequence".toByteArray(StandardCharsets.UTF_8))

    // Create mock libnative.so
    val soFile = File(workDir, "libnative.so")
    val elfHeader = ByteArray(1024)
    elfHeader[0] = 0x7F
    elfHeader[1] = 'E'.code.toByte()
    elfHeader[2] = 'L'.code.toByte()
    elfHeader[3] = 'F'.code.toByte()
    soFile.writeBytes(elfHeader)

    // Assemble and Align
    val alignedApk = File(workDir, "app-aligned.apk")
    val aligner = ApkZipAligner()
    val stageResult = aligner.packageAndAlign(
        resourcesApFile = resourcesAp,
        dexFiles = listOf(dexFile),
        nativeLibraries = listOf(soFile),
        outputApkFile = alignedApk
    )

    assertTrue("Package & align should succeed", stageResult.success)
    assertTrue("Aligned APK must exist", alignedApk.exists())
    assertTrue("Aligned APK must have size > 0", alignedApk.length() > 0L)

    // Verify ZIP can be opened by standard ZipFile
    ZipFile(alignedApk).use { zip ->
      assertNotNull(zip.getEntry("resources.arsc"))
      assertNotNull(zip.getEntry("AndroidManifest.xml"))
      assertNotNull(zip.getEntry("classes.dex"))
      assertNotNull(zip.getEntry("lib/arm64-v8a/libnative.so"))
    }

    // Inspect byte offsets in file to verify 4-byte and 16KB alignment
    RandomAccessFile(alignedApk, "r").use { raf ->
      fun findEntryDataOffset(entryName: String): Long {
        raf.seek(0)
        var offset = 0L
        while (offset < raf.length() - 30) {
          raf.seek(offset)
          val sig = Integer.reverseBytes(raf.readInt())
          if (sig != 0x04034b50) break

          raf.seek(offset + 26)
          val nameLen = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF
          val extraLen = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xFFFF

          val nameBytes = ByteArray(nameLen)
          raf.readFully(nameBytes)
          val name = String(nameBytes, StandardCharsets.UTF_8)

          val dataOffset = offset + 30 + nameLen + extraLen
          if (name == entryName) {
            return dataOffset
          }

          raf.seek(offset + 18)
          val compSize = Integer.reverseBytes(raf.readInt()).toLong() and 0xFFFFFFFFL
          offset = dataOffset + compSize
        }
        return -1L
      }

      val arscOffset = findEntryDataOffset("resources.arsc")
      assertTrue("resources.arsc offset found", arscOffset >= 0)
      assertEquals("resources.arsc must be 4-byte aligned", 0L, arscOffset % 4)

      val soOffset = findEntryDataOffset("lib/arm64-v8a/libnative.so")
      assertTrue("libnative.so offset found", soOffset >= 0)
      assertEquals("Shared library must be 16KB (16384 bytes) page-aligned", 0L, soOffset % 16384)
    }
  }

  @Test
  fun test08_apkSigningAndVerification() {
    val workDir = tempFolder.newFolder("signing_test")

    // Create an aligned test APK with a valid binary AndroidManifest.xml and classes.dex
    val testApk = File(workDir, "test-aligned.apk")
    val manifestBytes = javaClass.classLoader?.getResourceAsStream("minimal_binary_manifest.bin")?.use { it.readBytes() }
        ?: File("src/test/resources/minimal_binary_manifest.bin").readBytes()

    ZipOutputStream(testApk.outputStream()).use { zos ->
      val manifestEntry = ZipEntry("AndroidManifest.xml")
      zos.putNextEntry(manifestEntry)
      zos.write(manifestBytes)
      zos.closeEntry()

      val dexEntry = ZipEntry("classes.dex")
      zos.putNextEntry(dexEntry)
      zos.write("Mock Dex Data for signing test".toByteArray(StandardCharsets.UTF_8))
      zos.closeEntry()
    }

    // Locate bundled debug keystore from core/app/src/main/assets/offline_debug.keystore
    val keystoreAsset = File("src/main/assets/offline_debug.keystore")
    assertTrue("offline_debug.keystore asset must exist", keystoreAsset.exists())

    // Copy to temp user home so ApkSignerService finds it
    val targetDir = File(workDir, ".androidide")
    targetDir.mkdirs()
    val localKeystore = File(targetDir, ApkSignerService.KEYSTORE_FILENAME)
    keystoreAsset.copyTo(localKeystore, overwrite = true)

    val oldUserHome = System.getProperty("user.home")
    System.setProperty("user.home", workDir.absolutePath)
    val signedApk = File(workDir, "test-signed.apk")
    try {
      val signerConfig = ApkSignerService()
      val stageResult = signerConfig.signAndVerify(
          inputApk = testApk,
          outputApk = signedApk
      )
      assertTrue("Sign and verify stage must succeed: ${stageResult.message}\nDetails: ${stageResult.details}", stageResult.success)
    } finally {
      if (oldUserHome != null) {
        System.setProperty("user.home", oldUserHome)
      }
    }

    // Check with ApkVerifier
    if (signedApk.exists()) {
      val verifier = ApkVerifier.Builder(signedApk)
          .setMinCheckedPlatformVersion(21)
          .build()
      val verifyResult = verifier.verify()
      assertTrue("APK must be verified", verifyResult.isVerified)
      assertTrue("v1 signature must be present", verifyResult.isVerifiedUsingV1Scheme)
      assertTrue("v2 signature must be present", verifyResult.isVerifiedUsingV2Scheme)
      assertTrue("v3 signature must be present", verifyResult.isVerifiedUsingV3Scheme)
    }
  }
}
