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

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import com.tom.rv2ide.utils.Environment
import java.io.File

class DexConverter {

  fun convertToDex(
      contract: OfflineProjectContract,
      workspace: OfflineWorkspace,
      listener: OfflineBuildProgressListener? = null
  ): OfflineStageResult {
    val startTime = System.currentTimeMillis()

    // 1. Collect all .class files
    val classFiles = workspace.classesDir.walkTopDown()
        .filter { it.isFile && it.extension.equals("class", ignoreCase = true) }
        .toList()

    if (classFiles.isEmpty()) {
      if (contract.hasNativeCode) {
        listener?.onStageLog(
            OfflineBuildStage.DEX,
            "No class files found for native-only project. Skipping DEX conversion."
        )
        return OfflineStageResult(
            stage = OfflineBuildStage.DEX,
            success = true,
            message = "Skipped DEX conversion (native-only project without class files).",
            durationMs = System.currentTimeMillis() - startTime
        )
      }
      return OfflineStageResult(
          stage = OfflineBuildStage.DEX,
          success = false,
          message = "No class files found in ${workspace.classesDir.absolutePath} to dex.",
          durationMs = System.currentTimeMillis() - startTime
      )
    }

    listener?.onStageLog(
        OfflineBuildStage.DEX,
        "Converting ${classFiles.size} class file(s) to DEX bytecode using in-process D8 (minSdkVersion ${contract.minSdkVersion})..."
    )

    // 2. Prepare D8Command
    val androidJar = Environment.ANDROID_JAR
    val builder = D8Command.builder()
        .setMinApiLevel(contract.minSdkVersion)
        .setMode(CompilationMode.DEBUG)
        .setOutput(workspace.dexDir.toPath(), OutputMode.DexIndexed)

    if (androidJar != null && androidJar.exists() && androidJar.isFile) {
      builder.addLibraryFiles(androidJar.toPath())
    }

    for (classFile in classFiles) {
      builder.addProgramFiles(classFile.toPath())
    }

    // 3. Run D8 in-process
    try {
      val d8Command = builder.build()
      D8.run(d8Command)
    } catch (e: Exception) {
      return OfflineStageResult(
          stage = OfflineBuildStage.DEX,
          success = false,
          message = "D8 compilation error: ${e.message}",
          durationMs = System.currentTimeMillis() - startTime,
          details = e.stackTraceToString()
      )
    }

    // 4. Verify generated DEX files
    val dexFiles = workspace.dexDir.listFiles()?.filter {
      it.isFile && it.name.startsWith("classes") && it.name.endsWith(".dex")
    } ?: emptyList()

    if (dexFiles.isEmpty()) {
      return OfflineStageResult(
          stage = OfflineBuildStage.DEX,
          success = false,
          message = "D8 completed without exception, but no classes*.dex files were found in ${workspace.dexDir.absolutePath}",
          durationMs = System.currentTimeMillis() - startTime
      )
    }

    val totalDexBytes = dexFiles.sumOf { it.length() }
    val detailsMsg = dexFiles.joinToString(", ") { "${it.name} (${it.length()} bytes)" }
    listener?.onStageLog(
        OfflineBuildStage.DEX,
        "D8 conversion completed: generated ${dexFiles.size} DEX file(s) ($detailsMsg)."
    )

    return OfflineStageResult(
        stage = OfflineBuildStage.DEX,
        success = true,
        message = "DEX conversion successful: $detailsMsg (total $totalDexBytes bytes).",
        durationMs = System.currentTimeMillis() - startTime
    )
  }
}
