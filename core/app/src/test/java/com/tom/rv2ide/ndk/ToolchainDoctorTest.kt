package com.tom.rv2ide.ndk

import java.io.File
import java.io.FileInputStream
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolchainDoctorTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun testDiagnosticsOfflineOnlyMode() {
    val projectRoot = tempFolder.newFolder("test_project_offline")
    val report = ToolchainDoctor.runDiagnostics(
        context = null,
        projectRoot = projectRoot,
        workflowOverride = "offline_only"
    )

    assertEquals("offline_only", report.workflowChoice)
    assertNotNull(report.components)
    assertEquals(10, report.components.size)

    val offlineNdk = report.components.first { it.id == "ndk_offline" }
    val clang = report.components.first { it.id == "clang_llvm" }
    val gnuMake = report.components.first { it.id == "gnu_make" }
    val aapt2 = report.components.first { it.id == "aapt2_offline" }
    val androidJar = report.components.first { it.id == "android_jar_offline" }
    val bytecode = report.components.first { it.id == "d8_apksigner" }

    val jdk = report.components.first { it.id == "jdk_env" }
    val sdk = report.components.first { it.id == "android_sdk_tools" }
    val gradle = report.components.first { it.id == "gradle_runtime" }
    val cmake = report.components.first { it.id == "cmake_native" }

    // Offline components should NOT be NOT_REQUIRED
    assertTrue(offlineNdk.status != ToolchainStatus.NOT_REQUIRED)
    assertTrue(clang.status != ToolchainStatus.NOT_REQUIRED)
    assertTrue(gnuMake.status != ToolchainStatus.NOT_REQUIRED)
    assertTrue(aapt2.status != ToolchainStatus.NOT_REQUIRED)
    assertTrue(androidJar.status != ToolchainStatus.NOT_REQUIRED)
    assertTrue(bytecode.status != ToolchainStatus.NOT_REQUIRED)

    // Online/Gradle components MUST be NOT_REQUIRED in offline_only mode
    assertEquals(ToolchainStatus.NOT_REQUIRED, jdk.status)
    assertEquals(ToolchainStatus.NOT_REQUIRED, sdk.status)
    assertEquals(ToolchainStatus.NOT_REQUIRED, gradle.status)
    assertEquals(ToolchainStatus.NOT_REQUIRED, cmake.status)
  }

  @Test
  fun testDiagnosticsGradleOnlyMode() {
    val projectRoot = tempFolder.newFolder("test_project_gradle")
    val report = ToolchainDoctor.runDiagnostics(
        context = null,
        projectRoot = projectRoot,
        workflowOverride = "gradle_only"
    )

    assertEquals("gradle_only", report.workflowChoice)
    val offlineNdk = report.components.first { it.id == "ndk_offline" }
    val clang = report.components.first { it.id == "clang_llvm" }
    val gnuMake = report.components.first { it.id == "gnu_make" }
    val aapt2 = report.components.first { it.id == "aapt2_offline" }
    val androidJar = report.components.first { it.id == "android_jar_offline" }
    val bytecode = report.components.first { it.id == "d8_apksigner" }

    // Offline-specific components must be NOT_REQUIRED in gradle_only mode
    assertEquals(ToolchainStatus.NOT_REQUIRED, offlineNdk.status)
    assertEquals(ToolchainStatus.NOT_REQUIRED, clang.status)
    assertEquals(ToolchainStatus.NOT_REQUIRED, gnuMake.status)
    assertEquals(ToolchainStatus.NOT_REQUIRED, aapt2.status)
    assertEquals(ToolchainStatus.NOT_REQUIRED, androidJar.status)
    assertEquals(ToolchainStatus.NOT_REQUIRED, bytecode.status)

    // Gradle and JDK must be evaluated
    val jdk = report.components.first { it.id == "jdk_env" }
    val sdk = report.components.first { it.id == "android_sdk_tools" }
    val gradle = report.components.first { it.id == "gradle_runtime" }
    val cmake = report.components.first { it.id == "cmake_native" }

    assertTrue(jdk.status != ToolchainStatus.NOT_REQUIRED)
    assertTrue(sdk.status != ToolchainStatus.NOT_REQUIRED)
    assertTrue(gradle.status != ToolchainStatus.NOT_REQUIRED)
    assertTrue(cmake.status != ToolchainStatus.NOT_REQUIRED)
  }

  @Test
  fun testDiagnosticsBothModeEvaluatesAll() {
    val projectRoot = tempFolder.newFolder("test_project_both")
    val report = ToolchainDoctor.runDiagnostics(
        context = null,
        projectRoot = projectRoot,
        workflowOverride = "both"
    )

    assertEquals("both", report.workflowChoice)
    assertEquals(10, report.components.size)
    // In "both" mode, none of the components should be NOT_REQUIRED
    val notRequired = report.components.filter { it.status == ToolchainStatus.NOT_REQUIRED }
    assertEquals(0, notRequired.size)
  }

  @Test
  fun testSafeRepairPreservesActiveNdkAndUserFiles() {
    val projectRoot = tempFolder.newFolder("test_project_repair")
    val userCodeDir = File(projectRoot, "app/src/main/cpp").apply { mkdirs() }
    val userSource = File(userCodeDir, "native-lib.cpp").apply {
      writeText("#include <jni.h>\n// User native code")
    }

    val localProps = File(projectRoot, "local.properties").apply {
      writeText("custom.property=user_value\n")
    }

    val repairResult = ToolchainDoctor.performSafeRepair(context = null, projectRoot = projectRoot)
    assertTrue(repairResult.success)

    // Verify user files remain intact
    assertTrue(userSource.exists())
    assertEquals("#include <jni.h>\n// User native code", userSource.readText())

    // Verify local.properties preserves custom properties
    assertTrue(localProps.exists())
    val props = Properties()
    FileInputStream(localProps).use { props.load(it) }
    assertEquals("user_value", props.getProperty("custom.property"))
  }
}
