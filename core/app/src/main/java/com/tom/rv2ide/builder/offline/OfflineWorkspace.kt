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

import java.io.File

/**
 * Manages an isolated workspace for a standalone build run.
 * Ensures the original source project is never modified in-place.
 */
class OfflineWorkspace(
    val rootDir: File
) {

  val genDir = File(rootDir, "gen")
  val resCompiledDir = File(rootDir, "res_compiled")
  val classesDir = File(rootDir, "classes")
  val dexDir = File(rootDir, "dex")
  val nativeOutDir = File(rootDir, "native_obj")
  val libsOutDir = File(rootDir, "libs")
  val binDir = File(rootDir, "bin")
  val outputDir = File(rootDir, "output")

  val resourcesApFile = File(binDir, "resources.ap_")
  val unalignedApkFile = File(binDir, "app-unaligned.apk")
  val alignedApkFile = File(binDir, "app-aligned.apk")
  val signedApkFile = File(outputDir, "app-signed.apk")
  val projectBridgeDir = File(rootDir, "project_src")

  fun prepare(): Boolean {
    clean()
    return listOf(
        rootDir,
        genDir,
        resCompiledDir,
        classesDir,
        dexDir,
        nativeOutDir,
        libsOutDir,
        binDir,
        outputDir
    ).all { it.mkdirs() || it.isDirectory }
  }

  fun clean() {
    if (projectBridgeDir.exists()) {
      try {
        java.nio.file.Files.deleteIfExists(projectBridgeDir.toPath())
      } catch (_: Throwable) {
        try {
          projectBridgeDir.delete()
        } catch (_: Throwable) {}
      }
    }
    if (rootDir.exists()) {
      rootDir.deleteRecursively()
    }
  }

  companion object {
    fun create(baseDir: File, projectName: String): OfflineWorkspace {
      val timestamp = System.currentTimeMillis()
      val safeName = projectName.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
      val workspaceDir = File(baseDir, "offline_build_${safeName}_$timestamp")
      return OfflineWorkspace(workspaceDir)
    }
  }
}
