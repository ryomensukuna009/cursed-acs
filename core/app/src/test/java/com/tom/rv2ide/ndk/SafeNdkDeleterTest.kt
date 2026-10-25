package com.tom.rv2ide.ndk

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeNdkDeleterTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var baseNdkDir: File
  private lateinit var deleter: SafeNdkDeleter

  @Before
  fun setUp() {
    baseNdkDir = tempFolder.newFolder("android-sdk-ndk")
    deleter = SafeNdkDeleter(baseNdkDir = baseNdkDir)
  }

  private fun createSyntheticNdk(name: String): File {
    val dir = File(baseNdkDir, name).apply { mkdirs() }
    File(dir, "source.properties").writeText("Pkg.Desc = Android NDK\nPkg.Revision = 21.4.7075529\n")
    File(dir, "ndk-build").apply {
      writeText("#!/bin/sh\n")
      setExecutable(true)
    }
    val buildCore = File(dir, "build/core").apply { mkdirs() }
    File(buildCore, "default.mk").writeText("# mk\n")
    return dir
  }

  // --- 1. POSIX Permission Issue: Parent directory is read-only (e.g. 0555 in GCC toolchains) ---
  @Test
  fun testDeleteRecursivelySafeWithReadOnlyParentDirectory() {
    val ndkDir = createSyntheticNdk("android-ndk-aide")
    val gccDir = File(ndkDir, "toolchains/aarch64-linux-android-4.9/prebuilt/linux-arm64/libexec/gcc/aarch64-linux-android/4.9.x")
    gccDir.mkdirs()

    val libLtoPlugin = File(gccDir, "liblto_plugin.so")
    libLtoPlugin.writeBytes(ByteArray(1024) { 0x55.toByte() })

    // Simulate POSIX 0555 (read + execute, no write on parent directory)
    gccDir.setWritable(false, false)
    gccDir.setWritable(false)

    // Execute deletion via deleter
    deleter.deleteRecursivelySafe(ndkDir)

    assertFalse("Target NDK directory should be completely deleted", ndkDir.exists())
    assertFalse("liblto_plugin.so must be deleted", libLtoPlugin.exists())
    assertFalse("Parent directory 4.9.x must be deleted", gccDir.exists())
  }

  // --- 2. Read-only child files ---
  @Test
  fun testDeleteRecursivelySafeWithReadOnlyChildFile() {
    val ndkDir = createSyntheticNdk("ndk-ro-file")
    val roFile = File(ndkDir, "read_only_binary.bin").apply {
      writeText("CANNOT_MODIFY")
      setWritable(false, false)
      setWritable(false)
    }

    deleter.deleteRecursivelySafe(ndkDir)

    assertFalse("Directory should be deleted even with read-only files inside", ndkDir.exists())
    assertFalse("Read-only file should be deleted", roFile.exists())
  }

  // --- 3. Symbolic links pointing outside must NOT delete external targets ---
  @Test
  fun testDeleteRecursivelySafeWithExternalSymlink() {
    val externalDir = tempFolder.newFolder("external_data")
    val sensitiveExternalFile = File(externalDir, "important_database.db").apply {
      writeText("CRITICAL_EXTERNAL_DATA")
    }

    val ndkDir = createSyntheticNdk("ndk-symlink")
    val symlinkFile = File(ndkDir, "link_to_external.db")

    var symlinkCreated = false
    try {
      Files.createSymbolicLink(symlinkFile.toPath(), sensitiveExternalFile.toPath())
      symlinkCreated = true
    } catch (_: Exception) {
      // Host environment does not permit symlink creation without admin rights (Windows)
    }

    deleter.deleteRecursivelySafe(ndkDir)

    assertFalse("NDK directory should be deleted", ndkDir.exists())
    if (symlinkCreated) {
      assertFalse("Symlink file inside NDK should be gone", Files.isSymbolicLink(symlinkFile.toPath()))
      assertTrue("External target MUST NOT be deleted when symlink is unlinked", sensitiveExternalFile.exists())
      assertEquals("CRITICAL_EXTERNAL_DATA", sensitiveExternalFile.readText())
    }
  }

  // --- 4. Broken / dangling symbolic links ---
  @Test
  fun testDeleteRecursivelySafeWithBrokenSymlink() {
    val ndkDir = createSyntheticNdk("ndk-broken-link")
    val nonexistentTarget = File(tempFolder.root, "does_not_exist_file.txt")
    val brokenLink = File(ndkDir, "dangling_link.so")

    var symlinkCreated = false
    try {
      Files.createSymbolicLink(brokenLink.toPath(), nonexistentTarget.toPath())
      symlinkCreated = true
    } catch (_: Exception) {}

    deleter.deleteRecursivelySafe(ndkDir)

    assertFalse("NDK directory should be deleted", ndkDir.exists())
    if (symlinkCreated) {
      assertFalse("Broken symlink must be removed", Files.isSymbolicLink(brokenLink.toPath()))
    }
  }

  // --- 5. Security: Path traversal attempts rejected ---
  @Test
  fun testPathTraversalRejection() {
    val traversalAttempts = listOf("../", "../../etc", "..\\system", "folder/../..", "ndk/..", "\u0000bad")
    for (attempt in traversalAttempts) {
      val inspection = deleter.inspectNdkForDeletion(attempt)
      assertTrue("Path traversal attempt '$attempt' must be rejected", inspection is SafeNdkDeleteResult.Rejected)

      val deletion = deleter.deleteNdkSafely(attempt)
      assertTrue("Deletion of traversal attempt '$attempt' must be rejected", deletion is SafeNdkDeleteResult.Rejected)
    }
  }

  // --- 6. Security: Base NDK directory itself cannot be deleted ---
  @Test
  fun testBaseNdkDirCannotBeDeleted() {
    val blankInspection = deleter.inspectNdkForDeletion("")
    assertTrue(blankInspection is SafeNdkDeleteResult.Rejected)

    val dotInspection = deleter.inspectNdkForDeletion(".")
    assertTrue(dotInspection is SafeNdkDeleteResult.Rejected)
  }

  // --- 7. NDK Marker Verification: Directory without markers rejected ---
  @Test
  fun testInvalidDirectoryWithoutMarkersRejected() {
    val invalidFolder = File(baseNdkDir, "not_an_ndk").apply { mkdirs() }
    File(invalidFolder, "readme.txt").writeText("Hello world")

    val inspection = deleter.inspectNdkForDeletion("not_an_ndk")
    assertTrue("Directory without markers must be rejected", inspection is SafeNdkDeleteResult.Rejected)

    val deletion = deleter.deleteNdkSafely("not_an_ndk")
    assertTrue("Deletion without markers must be rejected", deletion is SafeNdkDeleteResult.Rejected)
    assertTrue("Invalid folder must not have been deleted", invalidFolder.exists())
  }

  // --- 8. Utility directories like 'bin' or 'tmp' rejected as NDKs ---
  @Test
  fun testUtilityDirectoriesRejectedFromInstalledVersions() {
    val binDir = File(baseNdkDir, "bin").apply { mkdirs() }
    File(binDir, "busybox").writeText("binary")
    File(binDir, "sh").writeText("binary")

    assertFalse("bin directory must not be considered a valid NDK", SafeNdkDeleter.isValidNdkDir(binDir))

    createSyntheticNdk("valid-ndk-21")
    val installed = deleter.getInstalledVersions()
    assertFalse("bin directory must not appear in installed versions", installed.contains("bin"))
    assertTrue("valid-ndk-21 must be in installed versions", installed.contains("valid-ndk-21"))
  }

  // --- 9. Full deletion flow with ValidationSummary ---
  @Test
  fun testInspectAndFullDeleteFlow() {
    val ndk = createSyntheticNdk("android-ndk-r21e")
    val inspection = deleter.inspectNdkForDeletion("android-ndk-r21e")

    assertTrue(inspection is SafeNdkDeleteResult.ValidationSummary)
    val summary = inspection as SafeNdkDeleteResult.ValidationSummary
    assertEquals("android-ndk-r21e", summary.version)
    assertTrue(summary.sizeBytes > 0)
    assertTrue(summary.isLastNdk)

    // Attempting delete on only NDK without allowDeleteLast fails
    val rejectResult = deleter.deleteNdkSafely("android-ndk-r21e", allowDeleteLast = false)
    assertTrue(rejectResult is SafeNdkDeleteResult.Rejected)
    assertTrue(ndk.exists())

    // Allow deleting last NDK succeeds
    val successResult = deleter.deleteNdkSafely("android-ndk-r21e", allowDeleteLast = true)
    assertTrue(successResult is SafeNdkDeleteResult.Success)
    assertFalse(ndk.exists())
  }

  // --- 10. Multi-version active NDK migration ---
  @Test
  fun testActiveNdkMigrationOnDelete() {
    createSyntheticNdk("ndk-v1")
    createSyntheticNdk("ndk-v2")

    val inspectionV2 = deleter.inspectNdkForDeletion("ndk-v2")
    assertTrue(inspectionV2 is SafeNdkDeleteResult.ValidationSummary)
    val summaryV2 = inspectionV2 as SafeNdkDeleteResult.ValidationSummary
    assertFalse(summaryV2.isLastNdk)

    val deleteResult = deleter.deleteNdkSafely("ndk-v2", forceDeleteActive = true)
    assertTrue(deleteResult is SafeNdkDeleteResult.Success)
    val success = deleteResult as SafeNdkDeleteResult.Success
    assertEquals(listOf("ndk-v1"), success.remainingVersions)
    assertEquals(listOf("ndk-v1"), deleter.getInstalledVersions())
  }

  // --- 11. Directory size calculation handles nested files accurately ---
  @Test
  fun testDirectorySizeCalculation() {
    val ndk = createSyntheticNdk("ndk-size-test")
    val sub = File(ndk, "deep/nested").apply { mkdirs() }
    val payload = ByteArray(2048) { 1 }
    File(sub, "payload.bin").writeBytes(payload)

    val calculated = deleter.calculateDirectorySize(ndk)
    assertTrue("Size should include nested payload", calculated >= 2048)
    assertEquals("0 B", SafeNdkDeleter.formatBytes(0))
    assertTrue(SafeNdkDeleter.formatBytes(calculated).contains("KB"))
  }
}
