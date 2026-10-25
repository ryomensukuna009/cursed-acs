package com.tom.rv2ide.preferences.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildPreferencesTest {

  @Test
  fun testBuildModeConstants() {
    assertEquals("idepref_build_mode", BuildPreferences.BUILD_MODE)
    assertEquals("gradle", BuildPreferences.BUILD_MODE_GRADLE)
    assertEquals("offline_ndk", BuildPreferences.BUILD_MODE_OFFLINE_NDK)
  }

  @Test
  fun testResolveBuildModeDefaultAndFallback() {
    // Missing / null must default to gradle
    assertEquals(BuildPreferences.BUILD_MODE_GRADLE, BuildPreferences.resolveBuildMode(null))

    // Empty string must default to gradle
    assertEquals(BuildPreferences.BUILD_MODE_GRADLE, BuildPreferences.resolveBuildMode(""))

    // Invalid / unknown values must fall back to gradle
    assertEquals(BuildPreferences.BUILD_MODE_GRADLE, BuildPreferences.resolveBuildMode("invalid_system"))
    assertEquals(BuildPreferences.BUILD_MODE_GRADLE, BuildPreferences.resolveBuildMode("cmake"))
    assertEquals(BuildPreferences.BUILD_MODE_GRADLE, BuildPreferences.resolveBuildMode("UNKNOWN"))

    // Valid values must be preserved
    assertEquals(BuildPreferences.BUILD_MODE_GRADLE, BuildPreferences.resolveBuildMode(BuildPreferences.BUILD_MODE_GRADLE))
    assertEquals(BuildPreferences.BUILD_MODE_OFFLINE_NDK, BuildPreferences.resolveBuildMode(BuildPreferences.BUILD_MODE_OFFLINE_NDK))
  }
}
