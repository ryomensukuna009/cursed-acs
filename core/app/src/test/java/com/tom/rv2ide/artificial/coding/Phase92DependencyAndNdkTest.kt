package com.tom.rv2ide.artificial.coding

import com.tom.rv2ide.builder.SharedBuildOrchestrator
import com.tom.rv2ide.builder.offline.OfflineProjectInspector
import com.tom.rv2ide.builder.offline.ProjectContractResult
import com.tom.rv2ide.ndk.SafeNdkDeleteResult
import com.tom.rv2ide.ndk.SafeNdkDeleter
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Phase92DependencyAndNdkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testProjectDir: File
    private lateinit var testNdkBaseDir: File

    @Before
    fun setUp() {
        testProjectDir = tempFolder.newFolder("TestProject")
        testNdkBaseDir = tempFolder.newFolder("android-sdk-ndk")
        SharedBuildOrchestrator.resetStateForTesting()
    }

    @After
    fun tearDown() {
        SharedBuildOrchestrator.resetStateForTesting()
    }

    // =========================================================================
    // 1. DependencyAwarePatchCoordinator: Topological Sorting
    // =========================================================================

    @Test
    fun testTopologicalSort_CppHeaderBeforeSource() {
        val mainCpp = File(testProjectDir, "jni/Main.cpp")
        val setupH = File(testProjectDir, "jni/Setup.h")

        val filesToEdit = listOf(mainCpp, setupH)

        // Main.cpp includes Setup.h
        val fileContents = mapOf(
            mainCpp to "#include \"Setup.h\"\nvoid init() { setDialog(); }",
            setupH to "#pragma once\nvoid setDialog();"
        )

        val sorted = DependencyAwarePatchCoordinator.sortEditsByDependency(filesToEdit, fileContents)

        assertEquals("Should sort 2 files", 2, sorted.size)
        assertEquals("Setup.h must precede Main.cpp", setupH, sorted[0])
        assertEquals("Main.cpp must follow Setup.h", mainCpp, sorted[1])
    }

    @Test
    fun testTopologicalSort_JavaModelBeforeCaller() {
        val serviceJava = File(testProjectDir, "src/main/java/com/example/UserService.java")
        val modelJava = File(testProjectDir, "src/main/java/com/example/User.java")

        val filesToEdit = listOf(serviceJava, modelJava)

        val fileContents = mapOf(
            serviceJava to "package com.example;\npublic class UserService { private User user; }",
            modelJava to "package com.example;\npublic class User { public String name; }"
        )

        val sorted = DependencyAwarePatchCoordinator.sortEditsByDependency(filesToEdit, fileContents)

        assertEquals("Should sort 2 files", 2, sorted.size)
        assertEquals("User.java must precede UserService.java", modelJava, sorted[0])
        assertEquals("UserService.java must follow User.java", serviceJava, sorted[1])
    }

    @Test
    fun testTopologicalSort_HandlesUnrelatedFilesGracefully() {
        val fileA = File(testProjectDir, "src/main/java/com/example/A.java")
        val fileB = File(testProjectDir, "src/main/java/com/example/B.java")

        val filesToEdit = listOf(fileA, fileB)
        val fileContents = mapOf(
            fileA to "package com.example;\npublic class A {}",
            fileB to "package com.example;\npublic class B {}"
        )

        val sorted = DependencyAwarePatchCoordinator.sortEditsByDependency(filesToEdit, fileContents)
        assertEquals("Should retain all files", 2, sorted.size)
        assertTrue(sorted.contains(fileA))
        assertTrue(sorted.contains(fileB))
    }

    // =========================================================================
    // 2. DependencyAwarePatchCoordinator: Safe Import & Include Insertion
    // =========================================================================

    @Test
    fun testInsertImportSafely_JavaPackageAfterPackageDecl() {
        val originalSource = """
            package com.example.service;

            public class MyService {
                private Activity activity;
            }
        """.trimIndent()

        val updated = DependencyAwarePatchCoordinator.insertImportSafely(
            originalContent = originalSource,
            importStatement = "import android.app.Activity;",
            isCpp = false
        )

        assertTrue(updated.contains("import android.app.Activity;"))
        val lines = updated.lines().map { it.trim() }
        val packageIdx = lines.indexOfFirst { it.startsWith("package com.example.service;") }
        val importIdx = lines.indexOfFirst { it == "import android.app.Activity;" }
        assertTrue("Import must appear after package statement", importIdx > packageIdx)
    }

    @Test
    fun testInsertImportSafely_JavaDuplicateIgnored() {
        val originalSource = """
            package com.example;
            import java.io.File;

            public class Foo {}
        """.trimIndent()

        val updated = DependencyAwarePatchCoordinator.insertImportSafely(
            originalContent = originalSource,
            importStatement = "import java.io.File;",
            isCpp = false
        )

        assertEquals("Source should remain identical if import already present", originalSource.trim(), updated.trim())
    }

    @Test
    fun testInsertIncludeSafely_CppHeaderInsertion() {
        val originalSource = """
            #pragma once
            #include <android/log.h>

            void doStuff() {}
        """.trimIndent()

        val updated = DependencyAwarePatchCoordinator.insertImportSafely(
            originalContent = originalSource,
            importStatement = "#include \"Setup.h\"",
            isCpp = true
        )

        assertTrue(updated.contains("#include \"Setup.h\""))
        val lines = updated.lines().map { it.trim() }
        val pragmaIdx = lines.indexOfFirst { it == "#pragma once" }
        val includeIdx = lines.indexOfFirst { it == "#include \"Setup.h\"" }
        assertTrue("Include must be placed after #pragma once", includeIdx > pragmaIdx)
    }

    @Test
    fun testAutoRepairMissingImports_JavaSymbol() {
        val sourceFile = File(testProjectDir, "src/main/java/com/example/TestClass.java")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText("""
            package com.example;

            public class TestClass {
                private Activity activity;
            }
        """.trimIndent())

        val diagnostics = listOf(
            BuildDiagnostic(
                filePath = sourceFile.absolutePath,
                line = 4,
                column = 13,
                severity = BuildDiagnostic.Severity.ERROR,
                stage = "JAVA_COMPILE",
                message = "cannot find symbol: class Activity",
                rawLine = "TestClass.java:4: error: cannot find symbol: class Activity"
            )
        )

        val repairResult = DependencyAwarePatchCoordinator.autoRepairMissingImportsForFile(sourceFile, diagnostics)
        assertTrue("Auto-repair should report applied imports", repairResult.success)
        assertEquals(1, repairResult.appliedImports.size)
        assertTrue(repairResult.appliedImports.first().contains("Activity"))

        val content = sourceFile.readText()
        assertTrue("Source file should have Activity imported", content.contains("import android.app.Activity;"))
    }

    // =========================================================================
    // 3. OfflineProjectInspector: Native-first & Directory Resolution
    // =========================================================================

    @Test
    fun testOfflineProjectInspector_AcceptsPureNativeProject() {
        // Project with AndroidManifest.xml and src/main/jni/Android.mk, but no Java files
        val manifest = File(testProjectDir, "AndroidManifest.xml")
        manifest.writeText("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.nativeapp">
                <application android:hasCode="false" android:label="NativeApp" />
            </manifest>
        """.trimIndent())

        val jniDir = File(testProjectDir, "jni")
        jniDir.mkdirs()
        File(jniDir, "Android.mk").writeText("LOCAL_PATH := $(call my-dir)\ninclude $(CLEAR_VARS)\nLOCAL_MODULE := mynative\ninclude $(BUILD_SHARED_LIBRARY)")

        val inspector = OfflineProjectInspector()
        val result = inspector.inspect(testProjectDir)

        assertTrue("Project with native C++ code should be accepted even without Java source files", result is ProjectContractResult.Valid)
        val contract = (result as ProjectContractResult.Valid).contract
        assertEquals("com.example.nativeapp", contract.packageName)
        assertTrue(contract.hasNativeCode)
        assertTrue(contract.javaFiles.isEmpty())
    }

    @Test
    fun testOfflineProjectInspector_DoesNotTreatSrcAsJavaWhenSrcMainExists() {
        // Project with src/main/jni and src/main/res, but no src/main/java
        val manifest = File(testProjectDir, "AndroidManifest.xml")
        manifest.writeText("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.imgui">
                <application android:label="ImGui" />
            </manifest>
        """.trimIndent())

        val srcMainJni = File(testProjectDir, "src/main/jni")
        srcMainJni.mkdirs()
        File(srcMainJni, "Android.mk").writeText("LOCAL_MODULE := imgui")

        val inspector = OfflineProjectInspector()
        val result = inspector.inspect(testProjectDir)

        assertTrue(result is ProjectContractResult.Valid)
        val contract = (result as ProjectContractResult.Valid).contract
        // javaSourceDirs must NOT contain the top-level src folder
        val topSrc = File(testProjectDir, "src")
        assertFalse("src/ must not be treated as Java root when src/main exists", contract.javaSourceDirs.contains(topSrc))
    }

    // =========================================================================
    // 4. SafeNdkDeleter: Boundary, Traversal, Markers, and Active NDK Guards
    // =========================================================================

    @Test
    fun testSafeNdkDeleter_RejectsPathTraversal() {
        val deleter = SafeNdkDeleter(baseNdkDir = testNdkBaseDir)

        val result1 = deleter.inspectNdkForDeletion("../etc/passwd")
        assertTrue(result1 is SafeNdkDeleteResult.Rejected)
        assertTrue((result1 as SafeNdkDeleteResult.Rejected).reason.contains("illegal path characters"))

        val result2 = deleter.inspectNdkForDeletion("r25c/../../sensitive")
        assertTrue(result2 is SafeNdkDeleteResult.Rejected)
    }

    @Test
    fun testSafeNdkDeleter_RejectsMissingNdkMarkers() {
        val nonNdkDir = File(testNdkBaseDir, "bogus-folder")
        nonNdkDir.mkdirs()
        File(nonNdkDir, "random.txt").writeText("not an ndk")

        val deleter = SafeNdkDeleter(baseNdkDir = testNdkBaseDir)
        val result = deleter.inspectNdkForDeletion("bogus-folder")

        assertTrue(result is SafeNdkDeleteResult.Rejected)
        assertTrue((result as SafeNdkDeleteResult.Rejected).reason.contains("missing source.properties, ndk-build, or toolchains"))
    }

    @Test
    fun testSafeNdkDeleter_InspectsValidNdkAndComputesSize() {
        val validNdk = File(testNdkBaseDir, "r25c")
        validNdk.mkdirs()
        File(validNdk, "source.properties").writeText("Pkg.Desc = Android NDK\nPkg.Revision = 25.2.9519653")
        File(validNdk, "ndk-build").writeText("#!/bin/sh\necho building")
        File(validNdk, "dummy.bin").writeBytes(ByteArray(1024 * 1024)) // 1 MB

        val deleter = SafeNdkDeleter(baseNdkDir = testNdkBaseDir)
        val result = deleter.inspectNdkForDeletion("r25c")

        assertTrue(result is SafeNdkDeleteResult.ValidationSummary)
        val summary = result as SafeNdkDeleteResult.ValidationSummary
        assertEquals("r25c", summary.version)
        assertTrue("Size should be >= 1MB", summary.sizeBytes >= 1024 * 1024)
        assertTrue("Formatted size should contain MB", summary.formattedSize.contains("MB"))
    }

    @Test
    fun testSafeNdkDeleter_ProtectsLastRemainingNdkByDefault() {
        val validNdk = File(testNdkBaseDir, "r25c")
        validNdk.mkdirs()
        File(validNdk, "source.properties").writeText("Pkg.Revision = 25.2.9519653")

        val deleter = SafeNdkDeleter(baseNdkDir = testNdkBaseDir)
        val deleteResult = deleter.deleteNdkSafely("r25c", allowDeleteLast = false)

        assertTrue(deleteResult is SafeNdkDeleteResult.Rejected)
        assertTrue((deleteResult as SafeNdkDeleteResult.Rejected).reason.contains("only installed NDK"))
        assertTrue("Directory must still exist", validNdk.exists())
    }

    @Test
    fun testSafeNdkDeleter_SuccessfulDeletionAndAutoMigrateActive() {
        // Setup two NDK versions
        val ndk1 = File(testNdkBaseDir, "21.4.7075529")
        ndk1.mkdirs()
        File(ndk1, "source.properties").writeText("Pkg.Revision = 21.4.7075529")
        File(ndk1, "ndk-build").writeText("echo ndk")

        val ndk2 = File(testNdkBaseDir, "25.2.9519653")
        ndk2.mkdirs()
        File(ndk2, "source.properties").writeText("Pkg.Revision = 25.2.9519653")
        File(ndk2, "ndk-build").writeText("echo ndk")

        val deleter = SafeNdkDeleter(baseNdkDir = testNdkBaseDir)

        // Delete version 21.4.7075529 (with multiple NDKs installed)
        val result = deleter.deleteNdkSafely("21.4.7075529")

        assertTrue("Deletion must succeed", result is SafeNdkDeleteResult.Success)
        val success = result as SafeNdkDeleteResult.Success
        assertEquals("21.4.7075529", success.version)
        assertFalse("Deleted NDK must no longer exist", ndk1.exists())
        assertTrue("Other NDK must remain intact", ndk2.exists())
        assertEquals(listOf("25.2.9519653"), success.remainingVersions)
    }

    // =========================================================================
    // 5. OfflineProjectInspector: Contract Guard 3 Manifest Activity Validation
    // =========================================================================

    @Test
    fun testOfflineProjectInspector_RejectsMissingJavaActivityInNativeProject() {
        val manifest = File(testProjectDir, "AndroidManifest.xml")
        manifest.writeText("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.nativemissing">
                <application android:label="App">
                    <activity android:name="com.example.MainActivity" />
                </application>
            </manifest>
        """.trimIndent())

        val jniDir = File(testProjectDir, "jni")
        jniDir.mkdirs()
        File(jniDir, "Android.mk").writeText("LOCAL_MODULE := mynative")

        val inspector = OfflineProjectInspector()
        val result = inspector.inspect(testProjectDir)

        assertTrue("Should reject project missing declared Activity source", result is ProjectContractResult.Invalid)
        val invalid = result as ProjectContractResult.Invalid
        assertTrue(invalid.reason.contains("com.example.MainActivity"))
        assertTrue(invalid.details?.contains("MainActivity.java") == true)
    }

    @Test
    fun testOfflineProjectInspector_AcceptsNativeActivityInNativeProject() {
        val manifest = File(testProjectDir, "AndroidManifest.xml")
        manifest.writeText("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.nativeok">
                <application android:hasCode="false" android:label="App">
                    <activity android:name="android.app.NativeActivity" />
                </application>
            </manifest>
        """.trimIndent())

        val jniDir = File(testProjectDir, "jni")
        jniDir.mkdirs()
        File(jniDir, "Android.mk").writeText("LOCAL_MODULE := mynative")

        val inspector = OfflineProjectInspector()
        val result = inspector.inspect(testProjectDir)

        assertTrue("NativeActivity without Java code should be valid", result is ProjectContractResult.Valid)
    }

    @Test
    fun testOfflineProjectInspector_AcceptsMatchingJavaActivityInHybridProject() {
        val manifest = File(testProjectDir, "AndroidManifest.xml")
        manifest.writeText("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.hybrid">
                <application android:label="App">
                    <activity android:name=".MainActivity" />
                </application>
            </manifest>
        """.trimIndent())

        val javaDir = File(testProjectDir, "src/main/java/com/example")
        javaDir.mkdirs()
        File(javaDir, "MainActivity.java").writeText("package com.example; public class MainActivity {}")

        val jniDir = File(testProjectDir, "src/main/jni")
        jniDir.mkdirs()
        File(jniDir, "Android.mk").writeText("LOCAL_MODULE := mynative")

        val inspector = OfflineProjectInspector()
        val result = inspector.inspect(testProjectDir)

        assertTrue("Hybrid project with matching MainActivity.java should be valid", result is ProjectContractResult.Valid)
    }
}
