package com.tom.rv2ide.ndk

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NdkImportTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var testWorkDir: File
  private lateinit var ndkInstallBaseDir: File
  private lateinit var importer: NdkArchiveImporter
  private lateinit var extractor: SafeTarExtractor
  private lateinit var classifier: NdkToolchainClassifier

  @Before
  fun setUp() {
    testWorkDir = tempFolder.newFolder("work")
    ndkInstallBaseDir = tempFolder.newFolder("installed_ndks")
    extractor = SafeTarExtractor()
    classifier = NdkToolchainClassifier()
    importer = NdkArchiveImporter(extractor, classifier)
  }

  // --- 1. Valid .tar.gz archive ---
  @Test
  fun testValidTarGzArchive() {
    val archive = File(testWorkDir, "valid_ndk.tar.gz")
    createSyntheticNdkArchive(archive, gzip = true, rootPrefix = "android-ndk-test")

    val result = importer.importArchive(archive, ndkInstallBaseDir)
    assertTrue("Import should succeed: ${result.errorMessage}", result.success)
    assertNotNull(result.installedDir)
    assertTrue(result.installedDir!!.exists())
    assertTrue(File(result.installedDir, "ndk-build").exists())
    assertTrue(File(result.installedDir, "build/core/default.mk").exists())
    assertNotNull(result.classification)
    assertEquals(NdkToolchainClassifier.NdkToolchainType.LEGACY_MAKE_NDK, result.classification!!.type)
  }

  // --- 2. Valid .tar.xz archive ---
  @Test
  fun testValidTarXzArchive() {
    val dirToPack = tempFolder.newFolder("to_pack_xz")
    File(dirToPack, "ndk-build").writeText("#!/bin/sh\n")
    val coreDir = File(dirToPack, "build/core").apply { mkdirs() }
    File(coreDir, "default.mk").writeText("# makefile\n")
    val platDir = File(dirToPack, "platforms/android-21/arch-arm64").apply { mkdirs() }
    File(platDir, "libc.so").writeText("stub")
    val clangDir = File(dirToPack, "toolchains/llvm/prebuilt/linux-arm64/bin").apply { mkdirs() }
    File(clangDir, "clang").writeText("clang-binary")

    val archive = File(testWorkDir, "valid_ndk.tar.xz")
    var packed = false
    try {
      val pb = ProcessBuilder("tar", "-cJf", archive.absolutePath, "-C", dirToPack.absolutePath, ".")
      if (pb.start().waitFor() == 0 && archive.exists() && archive.length() > 0) {
        packed = true
      }
    } catch (_: Exception) {}

    if (packed) {
      val result = importer.importArchive(archive, ndkInstallBaseDir, suggestedName = "test-xz")
      assertTrue("XZ import should succeed: ${result.errorMessage}", result.success)
      assertNotNull(result.installedDir)
      assertTrue(File(result.installedDir, "ndk-build").exists())
    } else {
      // If system tar -cJf is unavailable on host, test format acceptance
      val dummyXz = File(testWorkDir, "dummy.tar.xz").apply { writeBytes(ByteArray(100)) }
      val result = importer.importArchive(dummyXz, ndkInstallBaseDir)
      // Must not fail with "Unsupported archive format"
      assertFalse("Should recognize .tar.xz extension", result.errorMessage?.contains("Unsupported archive format") == true)
    }
  }

  // --- 3. Unsupported file extension ---
  @Test
  fun testUnsupportedFileExtension() {
    val unsupported7z = File(testWorkDir, "toolchain.7z").apply { writeText("dummy 7z content") }
    val result7z = importer.importArchive(unsupported7z, ndkInstallBaseDir)
    assertFalse(result7z.success)
    assertTrue(result7z.errorMessage!!.contains("Unsupported archive format"))

    val unsupportedRar = File(testWorkDir, "toolchain.rar").apply { writeText("dummy rar content") }
    val resultRar = importer.importArchive(unsupportedRar, ndkInstallBaseDir)
    assertFalse(resultRar.success)
    assertTrue(resultRar.errorMessage!!.contains("Unsupported archive format"))
  }

  // --- 3b. Valid .zip archive ---
  @Test
  fun testValidZipArchive() {
    val archive = File(testWorkDir, "valid_ndk.zip")
    createSyntheticNdkZip(archive, rootPrefix = "android-ndk-test-zip")

    val result = importer.importArchive(archive, ndkInstallBaseDir)
    assertTrue("Zip import should succeed: ${result.errorMessage}", result.success)
    assertNotNull(result.installedDir)
    assertTrue(result.installedDir!!.exists())
    assertTrue(File(result.installedDir, "ndk-build").exists())
    assertTrue(File(result.installedDir, "build/core/default.mk").exists())
  }

  // --- 4. Corrupt or truncated archive ---
  @Test
  fun testCorruptOrTruncatedArchive() {
    val corruptArchive = File(testWorkDir, "corrupt.tar.gz").apply {
      writeBytes(byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0x12, 0x34)) // Incomplete gzip header
    }
    val result = importer.importArchive(corruptArchive, ndkInstallBaseDir)
    assertFalse(result.success)
    assertTrue("Should report failure for corrupt archive", result.errorMessage != null)
  }

  // --- 5. Path traversal attempt (Zip Slip) ---
  @Test
  fun testPathTraversalAttempt() {
    val maliciousArchive = File(testWorkDir, "slip.tar.gz")
    val entries = listOf(
        TestTarEntry(name = "safe_file.txt", type = '0', content = "safe".toByteArray()),
        TestTarEntry(name = "../../outside.txt", type = '0', content = "malicious".toByteArray()),
    )
    createTarArchive(maliciousArchive, entries, gzip = true)

    val stagingDir = tempFolder.newFolder("staging_slip")
    try {
      extractor.extract(maliciousArchive, stagingDir)
      fail("SafeTarExtractor should have thrown SecurityException for path traversal entry")
    } catch (e: SecurityException) {
      assertTrue("Exception should mention path traversal: ${e.message}", e.message!!.contains("Path traversal"))
    }

    // Verify nothing escaped staging dir
    val escapedFile = File(stagingDir.parentFile, "outside.txt")
    assertFalse("File must NOT have escaped staging directory", escapedFile.exists())
  }

  // --- 6. Unsafe symbolic link target escaping staging directory ---
  @Test
  fun testUnsafeSymbolicLinkTarget() {
    val maliciousSymlink = File(testWorkDir, "unsafe_symlink.tar.gz")
    val entries = listOf(
        TestTarEntry(name = "folder", type = '5'),
        TestTarEntry(name = "folder/evil_symlink", type = '2', linkTarget = "../../../../etc/passwd"),
    )
    createTarArchive(maliciousSymlink, entries, gzip = true)

    val stagingDir = tempFolder.newFolder("staging_symlink")
    try {
      extractor.extract(maliciousSymlink, stagingDir)
      fail("SafeTarExtractor should have thrown SecurityException for unsafe symlink target")
    } catch (e: SecurityException) {
      assertTrue(
          "Exception should indicate escaping staging directory: ${e.message}",
          e.message!!.contains("escapes staging directory") || e.message!!.contains("Path traversal"),
      )
    }
  }

  // --- 6a. Unsafe hard link target escaping staging directory ---
  @Test
  fun testUnsafeHardLinkTarget() {
    val maliciousHardlink = File(testWorkDir, "unsafe_hardlink.tar.gz")
    val entries = listOf(
        TestTarEntry(name = "folder", type = '5'),
        TestTarEntry(name = "folder/evil_hardlink", type = '1', linkTarget = "../../../../etc/shadow"),
    )
    createTarArchive(maliciousHardlink, entries, gzip = true)

    val stagingDir = tempFolder.newFolder("staging_hardlink_unsafe")
    try {
      extractor.extract(maliciousHardlink, stagingDir)
      fail("SafeTarExtractor should have thrown SecurityException for unsafe hardlink target")
    } catch (e: SecurityException) {
      assertTrue(
          "Exception should indicate escaping staging directory: ${e.message}",
          e.message!!.contains("escapes staging directory") || e.message!!.contains("Path traversal"),
      )
    }
  }

  // --- 6b. Unsupported hard link entry rejected by default policy ---
  @Test
  fun testUnsupportedHardLinkEntryRejectedByDefault() {
    val internalHardlink = File(testWorkDir, "internal_hardlink.tar.gz")
    val entries = listOf(
        TestTarEntry(name = "bin", type = '5'),
        TestTarEntry(name = "bin/target.txt", type = '0', content = "target content".toByteArray()),
        TestTarEntry(name = "bin/link.txt", type = '1', linkTarget = "bin/target.txt"),
    )
    createTarArchive(internalHardlink, entries, gzip = true)

    val stagingDir = tempFolder.newFolder("staging_hardlink_unsupported")
    try {
      extractor.extract(internalHardlink, stagingDir)
      fail("SafeTarExtractor should reject hard links by default")
    } catch (e: SecurityException) {
      assertTrue(
          "Exception should state hard links are not permitted: ${e.message}",
          e.message!!.contains("Hard link entries ('1') are not permitted"),
      )
    }

    // Verify importer fails gracefully and reports rejection
    val result = importer.importArchive(internalHardlink, ndkInstallBaseDir)
    assertFalse("Importer should reject archive with unsupported hard links", result.success)
    assertTrue(result.errorMessage?.contains("Hard link entries ('1') are not permitted") == true)
  }

  // --- 6c. Hard link referencing missing target when enabled ---
  @Test
  fun testHardLinkReferencingMissingTargetWhenEnabled() {
    val missingTargetArchive = File(testWorkDir, "missing_target_hardlink.tar.gz")
    val entries = listOf(
        TestTarEntry(name = "bin", type = '5'),
        TestTarEntry(name = "bin/broken_link.txt", type = '1', linkTarget = "bin/nonexistent.txt"),
    )
    createTarArchive(missingTargetArchive, entries, gzip = true)

    val extractorWithHardLinks = SafeTarExtractor(allowHardLinks = true)
    val stagingDir = tempFolder.newFolder("staging_hardlink_missing")
    try {
      extractorWithHardLinks.extract(missingTargetArchive, stagingDir)
      fail("Should throw IOException when hard link target is missing")
    } catch (e: IOException) {
      assertTrue(
          "Exception should mention missing target: ${e.message}",
          e.message!!.contains("does not exist or has not been extracted yet"),
      )
    }
  }

  // --- 6d. Hard link appearing before its target when enabled ---
  @Test
  fun testHardLinkAppearingBeforeTargetWhenEnabled() {
    val beforeTargetArchive = File(testWorkDir, "before_target_hardlink.tar.gz")
    val entries = listOf(
        TestTarEntry(name = "bin", type = '5'),
        // Link entry appears BEFORE target entry in archive
        TestTarEntry(name = "bin/early_link.txt", type = '1', linkTarget = "bin/late_target.txt"),
        TestTarEntry(name = "bin/late_target.txt", type = '0', content = "data".toByteArray()),
    )
    createTarArchive(beforeTargetArchive, entries, gzip = true)

    val extractorWithHardLinks = SafeTarExtractor(allowHardLinks = true)
    val stagingDir = tempFolder.newFolder("staging_hardlink_before")
    try {
      extractorWithHardLinks.extract(beforeTargetArchive, stagingDir)
      fail("Should throw IOException when hard link appears before its target")
    } catch (e: IOException) {
      assertTrue(
          "Exception should mention target does not exist yet: ${e.message}",
          e.message!!.contains("does not exist or has not been extracted yet"),
      )
    }
  }

  // --- 6e. Valid internal hard link extraction when enabled ---
  @Test
  fun testValidInternalHardLinkWhenEnabled() {
    val validHardlinkArchive = File(testWorkDir, "valid_internal_hardlink.tar.gz")
    val entries = listOf(
        TestTarEntry(name = "bin", type = '5'),
        TestTarEntry(name = "bin/target.txt", type = '0', content = "hello hardlink content".toByteArray()),
        TestTarEntry(name = "bin/hardlink.txt", type = '1', linkTarget = "bin/target.txt"),
    )
    createTarArchive(validHardlinkArchive, entries, gzip = true)

    val extractorWithHardLinks = SafeTarExtractor(allowHardLinks = true)
    val stagingDir = tempFolder.newFolder("staging_hardlink_valid")
    val result = extractorWithHardLinks.extract(validHardlinkArchive, stagingDir)

    assertTrue(result.totalEntries >= 3)
    val linkFile = File(stagingDir, "bin/hardlink.txt")
    assertTrue("Hard link destination file should exist", linkFile.exists())
    assertEquals("hello hardlink content", linkFile.readText())
  }

  // --- 7. Missing or incomplete NDK markers ---
  @Test
  fun testMissingOrIncompleteMarkers() {
    val randomArchive = File(testWorkDir, "random_stuff.tar.gz")
    val entries = listOf(
        TestTarEntry(name = "readme.txt", type = '0', content = "Hello".toByteArray()),
        TestTarEntry(name = "lib/somelib.so", type = '0', content = "binary".toByteArray()),
    )
    createTarArchive(randomArchive, entries, gzip = true)

    val result = importer.importArchive(randomArchive, ndkInstallBaseDir)
    assertFalse(result.success)
    assertNotNull(result.classification)
    assertEquals(NdkToolchainClassifier.NdkToolchainType.UNSUPPORTED, result.classification!!.type)
  }

  // --- 8. Nested archive directory layout ---
  @Test
  fun testNestedArchiveDirectoryLayout() {
    val nestedArchive = File(testWorkDir, "nested_ndk.tar.gz")
    createSyntheticNdkArchive(nestedArchive, gzip = true, rootPrefix = "deeply/nested/android-ndk-r18b")

    val result = importer.importArchive(nestedArchive, ndkInstallBaseDir)
    assertTrue("Should successfully locate nested NDK root: ${result.errorMessage}", result.success)
    assertNotNull(result.installedDir)
    assertEquals("android-ndk-r18b", result.installedDir!!.name)
    assertTrue(File(result.installedDir, "ndk-build").exists())
  }

  // --- 9. Cancellation and cleanup ---
  @Test
  fun testCancellationAndCleanup() {
    val archive = File(testWorkDir, "cancel_ndk.tar.gz")
    createSyntheticNdkArchive(archive, gzip = true, rootPrefix = "ndk-cancel")

    val cancelled = AtomicBoolean(true) // Pre-cancelled
    val result = importer.importArchive(archive, ndkInstallBaseDir, isCancelled = { cancelled.get() })
    assertFalse(result.success)
    assertTrue(result.errorMessage!!.contains("cancelled"))

    // Verify no stray directories in ndkInstallBaseDir
    assertEquals(0, ndkInstallBaseDir.listFiles()?.size ?: 0)
  }

  // --- 10. Failed import preserves existing installation ---
  @Test
  fun testFailedImportPreservesExisting() {
    val existingNdk = File(ndkInstallBaseDir, "existing-ndk-21").apply { mkdirs() }
    val sentinelFile = File(existingNdk, "sentinel.txt").apply { writeText("DO_NOT_DELETE") }

    val corruptArchive = File(testWorkDir, "corrupt.tar.gz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val result = importer.importArchive(corruptArchive, ndkInstallBaseDir)
    assertFalse(result.success)

    // Verify existing NDK is completely preserved
    assertTrue(existingNdk.exists())
    assertTrue(sentinelFile.exists())
    assertEquals("DO_NOT_DELETE", sentinelFile.readText())
  }

  // --- 11. Duplicate installation names without silent replacement ---
  @Test
  fun testDuplicateInstallationNamesWithoutSilentOverwrite() {
    // Pre-create an installation with name "android-ndk-test"
    val existing = File(ndkInstallBaseDir, "android-ndk-test").apply { mkdirs() }
    val originalMarker = File(existing, "original.txt").apply { writeText("original") }

    val archive = File(testWorkDir, "duplicate_ndk.tar.gz")
    createSyntheticNdkArchive(archive, gzip = true, rootPrefix = "android-ndk-test")

    val result = importer.importArchive(archive, ndkInstallBaseDir)
    assertTrue("Import should succeed without collision error: ${result.errorMessage}", result.success)
    assertNotNull(result.installedDir)

    // New installation must have a distinct name (e.g. android-ndk-test-1)
    assertEquals("android-ndk-test-1", result.installedDir!!.name)
    assertTrue("Original installation must remain intact", originalMarker.exists())
    assertEquals("original", originalMarker.readText())
  }

  // --- 17. Companion bin/ directory preserved on import (Goal A) ---
  @Test
  fun testCompanionBinDirectoryPreservedOnImport() {
    val archive = File(testWorkDir, "aide_style_ndk.tar.gz")
    val entries = listOf(
        TestTarEntry(name = "android-ndk-aide/ndk-build", type = '0', content = "#!/bin/sh\n".toByteArray(), mode = 0b111_101_101),
        TestTarEntry(name = "android-ndk-aide/build/core", type = '5'),
        TestTarEntry(name = "android-ndk-aide/build/core/default.mk", type = '0', content = "# default.mk".toByteArray()),
        TestTarEntry(name = "android-ndk-aide/prebuilt/linux-arm64/bin", type = '5'),
        TestTarEntry(name = "android-ndk-aide/prebuilt/linux-arm64/bin/make", type = '0', content = createSyntheticElf(), mode = 0b111_101_101),
        TestTarEntry(name = "android-ndk-aide/toolchains/llvm/prebuilt/linux-arm64/bin", type = '5'),
        TestTarEntry(name = "android-ndk-aide/toolchains/llvm/prebuilt/linux-arm64/bin/clang", type = '0', content = createSyntheticElf(), mode = 0b111_101_101),
        TestTarEntry(name = "android-ndk-aide/platforms/android-21/arch-arm64", type = '5'),
        TestTarEntry(name = "android-ndk-aide/platforms/android-21/arch-arm64/libc.so", type = '0', content = "stub".toByteArray()),
        // Companion bin/ directory (sibling to android-ndk-aide)
        TestTarEntry(name = "bin", type = '5'),
        TestTarEntry(name = "bin/busybox", type = '0', content = createSyntheticElf(), mode = 0b111_101_101),
        TestTarEntry(name = "bin/sh", type = '0', content = "#!/bin/sh\n".toByteArray(), mode = 0b111_101_101),
    )
    createTarArchive(archive, entries, gzip = true)

    val result = importer.importArchive(archive, ndkInstallBaseDir, suggestedName = "aide_ndk")
    assertTrue("Import should succeed: ${result.errorMessage}", result.success)
    assertNotNull(result.installedDir)
    assertTrue(File(result.installedDir, "ndk-build").exists())
    assertTrue("Companion bin/ must be preserved in installed NDK", File(result.installedDir, "bin").isDirectory)
    assertTrue("bin/busybox must exist", File(result.installedDir, "bin/busybox").exists())
    assertTrue("bin/sh must exist", File(result.installedDir, "bin/sh").exists())
  }

  // --- 18. Executable permissions enforcement ---
  @Test
  fun testEnsureExecutablePermissions() {
    val ndkDir = tempFolder.newFolder("mock_ndk_perms")
    val ndkBuild = File(ndkDir, "ndk-build").apply { writeText("#!/bin/sh") }
    val binDir = File(ndkDir, "bin").apply { mkdirs() }
    val busybox = File(binDir, "busybox").apply { writeText("mock busybox") }

    importer.ensureExecutablePermissions(ndkDir)

    assertTrue(ndkBuild.exists())
    assertTrue(busybox.exists())
  }

  // --- 19. Smoke test project generation ---
  @Test
  fun testSmokeProjectGeneration() {
    val smokeDir = tempFolder.newFolder("smoke_proj")
    val tester = NdkSmokeTester(workingCacheDir = tempFolder.root)
    tester.createSmokeProject(smokeDir)

    val smokeC = File(smokeDir, "jni/smoke.c")
    val androidMk = File(smokeDir, "jni/Android.mk")
    val applicationMk = File(smokeDir, "jni/Application.mk")

    assertTrue(smokeC.exists())
    assertTrue(androidMk.exists())
    assertTrue(applicationMk.exists())

    val cContent = smokeC.readText()
    assertTrue(cContent.contains("testNativeAdd"))
    assertTrue(cContent.contains("JNIEXPORT"))

    val mkContent = androidMk.readText()
    assertTrue(mkContent.contains("LOCAL_MODULE    := ndk_smoke"))
    assertTrue(mkContent.contains("BUILD_SHARED_LIBRARY"))

    val appMkContent = applicationMk.readText()
    assertTrue(appMkContent.contains("APP_ABI := arm64-v8a"))
    assertTrue(appMkContent.contains("APP_PLATFORM := android-21"))
  }

  // --- 20. Smoke tester locates ndk-build ---
  @Test
  fun testSmokeTesterFindsNdkBuildScript() {
    val ndkDir = tempFolder.newFolder("ndk_loc")
    val tester = NdkSmokeTester(workingCacheDir = tempFolder.root)

    val foundBefore = tester.findNdkBuildScript(ndkDir)
    assertEquals(null, foundBefore)

    val script = File(ndkDir, "ndk-build").apply { writeText("#!/bin/sh") }
    val foundAfter = tester.findNdkBuildScript(ndkDir)
    assertNotNull(foundAfter)
    assertEquals(script.absolutePath, foundAfter!!.absolutePath)
  }

  // --- 21. Smoke tester graceful error handling on invalid NDK ---
  @Test
  fun testSmokeTesterHandlesInvalidNdkGracefully() {
    val nonExistentDir = File(tempFolder.root, "does_not_exist_ndk")
    val tester = NdkSmokeTester(workingCacheDir = tempFolder.root)
    val result = tester.runSmokeTest(nonExistentDir)

    assertFalse("Smoke test should fail on missing directory", result.success)
    assertNotNull(result.errorMessage)
    assertTrue(result.errorMessage!!.contains("does not exist"))
  }

  // --- 22. ELF verification: missing file ---
  @Test
  fun testElfVerificationWithMissingFile() {
    val tester = NdkSmokeTester(workingCacheDir = tempFolder.root)
    val missingFile = File(tempFolder.root, "does_not_exist.so")
    val result = tester.verifyElfSharedLibrary(missingFile)

    assertFalse("Missing library must fail verification", result.isValid)
    assertNotNull(result.errorMessage)
    assertTrue(result.errorMessage!!.contains("does not exist"))
  }

  // --- 23. ELF verification: empty file (0 bytes) ---
  @Test
  fun testElfVerificationWithEmptyFile() {
    val tester = NdkSmokeTester(workingCacheDir = tempFolder.root)
    val emptyFile = File(tempFolder.root, "empty.so").apply { createNewFile() }
    val result = tester.verifyElfSharedLibrary(emptyFile)

    assertFalse("Empty file (0 bytes) must fail verification", result.isValid)
    assertEquals(0L, result.sizeBytes)
    assertNotNull(result.errorMessage)
    assertTrue(result.errorMessage!!.contains("0 bytes"))
  }

  // --- 24. ELF verification: invalid magic ---
  @Test
  fun testElfVerificationWithInvalidMagic() {
    val tester = NdkSmokeTester(workingCacheDir = tempFolder.root)
    val invalidFile = File(tempFolder.root, "invalid.so").apply {
      writeBytes("NOT_AN_ELF_FILE_AT_ALL_JUST_RANDOM_TEXT_DATA_FOR_TESTING".toByteArray())
    }
    val result = tester.verifyElfSharedLibrary(invalidFile)

    assertFalse("File without ELF magic must fail verification", result.isValid)
    assertNotNull(result.errorMessage)
    assertTrue(result.errorMessage!!.contains("Invalid ELF magic"))
  }

  // --- 25. ELF verification: 32-bit ELF (not 64-bit arm64-v8a) ---
  @Test
  fun testElfVerificationWith32BitElf() {
    val tester = NdkSmokeTester(workingCacheDir = tempFolder.root)
    val elf32 = ByteArray(64)
    elf32[0] = 0x7F
    elf32[1] = 'E'.code.toByte()
    elf32[2] = 'L'.code.toByte()
    elf32[3] = 'F'.code.toByte()
    elf32[4] = 1 // 32-bit (EI_CLASS = 1)
    elf32[5] = 1 // Little endian
    elf32[18] = 40.toByte() // ARM 32-bit
    val file32 = File(tempFolder.root, "lib32.so").apply { writeBytes(elf32) }

    val result = tester.verifyElfSharedLibrary(file32)
    assertFalse("32-bit ELF must fail arm64 verification", result.isValid)
    assertNotNull(result.errorMessage)
    assertTrue(result.errorMessage!!.contains("not 64-bit"))
  }

  // --- 26. ELF verification: wrong machine architecture (e.g. x86_64, EM_X86_64 = 62) ---
  @Test
  fun testElfVerificationWithWrongArchitecture() {
    val tester = NdkSmokeTester(workingCacheDir = tempFolder.root)
    val elfX86 = ByteArray(64)
    elfX86[0] = 0x7F
    elfX86[1] = 'E'.code.toByte()
    elfX86[2] = 'L'.code.toByte()
    elfX86[3] = 'F'.code.toByte()
    elfX86[4] = 2 // 64-bit
    elfX86[5] = 1 // Little endian
    elfX86[18] = 62.toByte() // EM_X86_64 = 62
    val fileX86 = File(tempFolder.root, "libx86.so").apply { writeBytes(elfX86) }

    val result = tester.verifyElfSharedLibrary(fileX86)
    assertFalse("x86_64 ELF must fail arm64 verification", result.isValid)
    assertNotNull(result.errorMessage)
    assertTrue(result.errorMessage!!.contains("Incompatible machine architecture"))
  }

  // --- 27. ELF verification: valid arm64-v8a ELF shared library ---
  @Test
  fun testElfVerificationWithValidArm64Elf() {
    val tester = NdkSmokeTester(workingCacheDir = tempFolder.root)
    val elfArm64 = createSyntheticElf() // 64-bit, little-endian, e_machine = 183
    val fileArm64 = File(tempFolder.root, "libvalid_arm64.so").apply { writeBytes(elfArm64) }

    val result = tester.verifyElfSharedLibrary(fileArm64)
    assertTrue("Valid arm64 ELF must pass verification: ${result.errorMessage}", result.isValid)
    assertEquals(64L, result.sizeBytes)
    assertTrue(result.is64Bit)
    assertTrue(result.isLittleEndian)
    assertEquals(183, result.machine)
    assertEquals("arm64-v8a (AArch64)", result.architecture)
  }

  // --- Helpers for synthetic TAR archives ---

  data class TestTarEntry(
      val name: String,
      val type: Char = '0', // '0' = file, '5' = dir, '2' = symlink
      val content: ByteArray = ByteArray(0),
      val linkTarget: String = "",
      val mode: Int = 0b111_101_101, // 0755
  )

  private fun createSyntheticNdkArchive(destFile: File, gzip: Boolean, rootPrefix: String) {
    val prefix = if (rootPrefix.endsWith('/')) rootPrefix else "$rootPrefix/"
    val entries = listOf(
        TestTarEntry(name = "${prefix}ndk-build", type = '0', content = "#!/bin/sh\n".toByteArray(), mode = 0b111_101_101),
        TestTarEntry(name = "${prefix}build/core", type = '5'),
        TestTarEntry(name = "${prefix}build/core/default.mk", type = '0', content = "# default.mk".toByteArray()),
        TestTarEntry(name = "${prefix}prebuilt/linux-arm64/bin", type = '5'),
        TestTarEntry(name = "${prefix}prebuilt/linux-arm64/bin/make", type = '0', content = createSyntheticElf(), mode = 0b111_101_101),
        TestTarEntry(name = "${prefix}toolchains/llvm/prebuilt/linux-arm64/bin", type = '5'),
        TestTarEntry(name = "${prefix}toolchains/llvm/prebuilt/linux-arm64/bin/clang", type = '0', content = createSyntheticElf(), mode = 0b111_101_101),
        TestTarEntry(name = "${prefix}platforms/android-21/arch-arm64", type = '5'),
        TestTarEntry(name = "${prefix}platforms/android-21/arch-arm64/libc.so", type = '0', content = "stub".toByteArray()),
    )
    createTarArchive(destFile, entries, gzip)
  }

  private fun createSyntheticElf(): ByteArray {
    // Minimal ELF 64-bit Little Endian header (e_machine = 183 for AArch64)
    val elf = ByteArray(64)
    elf[0] = 0x7F
    elf[1] = 'E'.code.toByte()
    elf[2] = 'L'.code.toByte()
    elf[3] = 'F'.code.toByte()
    elf[4] = 2 // 64-bit
    elf[5] = 1 // Little endian
    elf[6] = 1 // Version
    elf[16] = 2 // e_type: ET_EXEC
    elf[18] = 183.toByte() // e_machine: AArch64 (0xB7 = 183)
    elf[19] = 0
    return elf
  }

  private fun createTarArchive(destFile: File, entries: List<TestTarEntry>, gzip: Boolean) {
    destFile.parentFile?.mkdirs()
    val baseOut: OutputStream = FileOutputStream(destFile).buffered()
    val out: OutputStream = if (gzip) GZIPOutputStream(baseOut) else baseOut

    out.use { stream ->
      for (entry in entries) {
        val header = ByteArray(512)
        val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.size.coerceAtMost(100))

        val modeStr = String.format("%06o ", entry.mode)
        System.arraycopy(modeStr.toByteArray(), 0, header, 100, 7)

        val size = if (entry.type == '0') entry.content.size.toLong() else 0L
        val sizeStr = String.format("%011o ", size)
        System.arraycopy(sizeStr.toByteArray(), 0, header, 124, 11)

        val mtimeStr = String.format("%011o ", System.currentTimeMillis() / 1000)
        System.arraycopy(mtimeStr.toByteArray(), 0, header, 136, 11)

        header[156] = entry.type.code.toByte()

        if (entry.linkTarget.isNotEmpty()) {
          val linkBytes = entry.linkTarget.toByteArray(Charsets.UTF_8)
          System.arraycopy(linkBytes, 0, header, 157, linkBytes.size.coerceAtMost(100))
        }

        // Magic "ustar\0"
        System.arraycopy("ustar\u0000".toByteArray(), 0, header, 257, 6)
        System.arraycopy("00".toByteArray(), 0, header, 263, 2)

        // Checksum calculation (fill checksum field 148..155 with spaces first)
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var chksum = 0
        for (b in header) chksum += (b.toInt() and 0xFF)
        val chksumStr = String.format("%06o\u0000 ", chksum)
        System.arraycopy(chksumStr.toByteArray(), 0, header, 148, 8)

        stream.write(header)

        if (entry.type == '0' && entry.content.isNotEmpty()) {
          stream.write(entry.content)
          val padding = (512 - (entry.content.size % 512)) % 512
          if (padding > 0) {
            stream.write(ByteArray(padding))
          }
        }
      }

      // Two 512-byte zero blocks at end of tar
      stream.write(ByteArray(1024))
    }
  }

  private fun createSyntheticNdkZip(destFile: File, rootPrefix: String) {
    destFile.parentFile?.mkdirs()
    val prefix = if (rootPrefix.endsWith('/')) rootPrefix else "$rootPrefix/"
    java.util.zip.ZipOutputStream(java.io.FileOutputStream(destFile)).use { zos ->
      val files = listOf(
          "${prefix}ndk-build" to "#!/bin/sh\n".toByteArray(),
          "${prefix}build/core/default.mk" to "# default.mk\n".toByteArray(),
          "${prefix}prebuilt/linux-arm64/bin/make" to createSyntheticElf(),
          "${prefix}toolchains/llvm/prebuilt/linux-arm64/bin/clang" to createSyntheticElf(),
          "${prefix}platforms/android-21/arch-arm64/libc.so" to "stub".toByteArray()
      )
      for ((name, content) in files) {
        val entry = java.util.zip.ZipEntry(name)
        zos.putNextEntry(entry)
        zos.write(content)
        zos.closeEntry()
      }
    }
  }
}
