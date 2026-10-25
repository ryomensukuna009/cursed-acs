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

import com.tom.rv2ide.managers.ToolsManager
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

class Aapt2ResourceCompiler {

  fun compileAndLink(
      contract: OfflineProjectContract,
      workspace: OfflineWorkspace,
      listener: OfflineBuildProgressListener? = null
  ): OfflineStageResult {
    val startTime = System.currentTimeMillis()

    // 1. Verify AAPT2 binary
    var aapt2Bin = Environment.AAPT2
    if (aapt2Bin == null || !aapt2Bin.exists() || aapt2Bin.length() == 0L) {
      ToolsManager.extractAapt2()
      aapt2Bin = Environment.AAPT2
    }

    if (aapt2Bin == null || !aapt2Bin.exists()) {
      return OfflineStageResult(
          stage = OfflineBuildStage.RESOURCES,
          success = false,
          message = "AAPT2 binary not found at ${aapt2Bin?.absolutePath}",
          durationMs = System.currentTimeMillis() - startTime
      )
    }

    if (!aapt2Bin.canExecute()) {
      aapt2Bin.setExecutable(true)
    }

    // 2. Verify Android platform JAR
    var androidJar = Environment.ANDROID_JAR
    if (androidJar == null || !androidJar.exists() || androidJar.isDirectory || androidJar.length() == 0L) {
      ToolsManager.extractAndroidJar()
      androidJar = Environment.ANDROID_JAR
    }

    if (androidJar == null || !androidJar.exists() || androidJar.length() == 0L) {
      return OfflineStageResult(
          stage = OfflineBuildStage.RESOURCES,
          success = false,
          message = "Android platform framework JAR not found or empty at ${androidJar?.absolutePath}",
          durationMs = System.currentTimeMillis() - startTime
      )
    }

    // 3. Compile resources if present
    val resZip = File(workspace.resCompiledDir, "resources.zip")
    val hasRes = contract.resDir != null && contract.resDir.exists() &&
        (contract.resDir.listFiles()?.isNotEmpty() == true)

    if (hasRes) {
      listener?.onStageLog(OfflineBuildStage.RESOURCES, "Compiling resources from ${contract.resDir!!.name}...")
      val compileCmd = listOf(
          aapt2Bin.absolutePath,
          "compile",
          "--dir", contract.resDir.absolutePath,
          "-o", resZip.absolutePath
      )

      val compileResult = runProcess(compileCmd, workspace.rootDir)
      if (compileResult.exitCode != 0) {
        return OfflineStageResult(
            stage = OfflineBuildStage.RESOURCES,
            success = false,
            message = "AAPT2 resource compilation failed (exit code ${compileResult.exitCode})",
            durationMs = System.currentTimeMillis() - startTime,
            details = "Command: ${compileCmd.joinToString(" ")}\n\nStderr:\n${compileResult.stderr}\n\nStdout:\n${compileResult.stdout}"
        )
      }
    } else {
      listener?.onStageLog(OfflineBuildStage.RESOURCES, "No resource directory found. Skipping resource compilation step.")
    }

    // 4. Link resources
    listener?.onStageLog(OfflineBuildStage.RESOURCES, "Linking resources with AAPT2 against ${androidJar.name}...")
    val linkCmd = mutableListOf(
        aapt2Bin.absolutePath,
        "link",
        "-I", androidJar.absolutePath,
        "--manifest", contract.manifestFile.absolutePath,
        "--java", workspace.genDir.absolutePath,
        "-o", workspace.resourcesApFile.absolutePath,
        "--auto-add-overlay",
        "--min-sdk-version", contract.minSdkVersion.toString(),
        "--target-sdk-version", contract.targetSdkVersion.toString()
    )

    if (contract.assetsDir != null && contract.assetsDir.exists()) {
      linkCmd.add("-A")
      linkCmd.add(contract.assetsDir.absolutePath)
    }

    if (hasRes && resZip.exists()) {
      linkCmd.add(resZip.absolutePath)
    }

    val linkResult = runProcess(linkCmd, workspace.rootDir)
    if (linkResult.exitCode != 0) {
      return OfflineStageResult(
          stage = OfflineBuildStage.RESOURCES,
          success = false,
          message = "AAPT2 resource linking failed (exit code ${linkResult.exitCode})",
          durationMs = System.currentTimeMillis() - startTime,
          details = "Command: ${linkCmd.joinToString(" ")}\n\nStderr:\n${linkResult.stderr}\n\nStdout:\n${linkResult.stdout}"
      )
    }

    // 5. Verify outputs
    if (!workspace.resourcesApFile.exists() || workspace.resourcesApFile.length() == 0L) {
      return OfflineStageResult(
          stage = OfflineBuildStage.RESOURCES,
          success = false,
          message = "AAPT2 finished with exit 0 but output '${workspace.resourcesApFile.name}' is missing or empty.",
          durationMs = System.currentTimeMillis() - startTime
      )
    }

    val rJavaFiles = workspace.genDir.walkTopDown().filter { it.isFile && it.name == "R.java" }.toList()
    listener?.onStageLog(
        OfflineBuildStage.RESOURCES,
        "AAPT2 link succeeded. Generated ${rJavaFiles.size} R.java file(s) and resources.ap_ (${workspace.resourcesApFile.length()} bytes)."
    )

    return OfflineStageResult(
        stage = OfflineBuildStage.RESOURCES,
        success = true,
        message = "Resources compiled and linked successfully (${workspace.resourcesApFile.length()} bytes).",
        durationMs = System.currentTimeMillis() - startTime
    )
  }

  private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

  private fun runProcess(command: List<String>, workingDir: File): ProcessResult {
    val process = ProcessBuilder(command)
        .directory(workingDir)
        .redirectErrorStream(false)
        .start()

    val stdout = process.inputStream.bufferedReader().use { it.readText() }
    val stderr = process.errorStream.bufferedReader().use { it.readText() }

    val completed = process.waitFor(120, TimeUnit.SECONDS)
    if (!completed) {
      process.destroyForcibly()
      return ProcessResult(-1, stdout, "Process timed out after 120 seconds:\n$stderr")
    }

    return ProcessResult(process.exitValue(), stdout, stderr)
  }
}
