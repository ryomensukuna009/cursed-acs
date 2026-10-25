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

import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit

class JavaSourceCompiler {

  fun compile(
      contract: OfflineProjectContract,
      workspace: OfflineWorkspace,
      listener: OfflineBuildProgressListener? = null
  ): OfflineStageResult {
    val startTime = System.currentTimeMillis()

    // 1. Locate javac binary or embedded in-process compiler
    val javacBin = findJavac()
    val hasInProcessJavac = try {
      Class.forName("openjdk.tools.javac.Main") != null
    } catch (_: Throwable) {
      false
    }

    if (javacBin == null && !hasInProcessJavac) {
      return OfflineStageResult(
          stage = OfflineBuildStage.JAVA_COMPILE,
          success = false,
          message = "Java compiler (javac) not found.",
          durationMs = System.currentTimeMillis() - startTime,
          details = "Checked: ${Environment.JAVA_HOME}/bin/javac and PATH.\n" +
              "Please ensure OpenJDK 17 is installed in IDE Settings > System > JDK."
      )
    }

    // 2. Locate android.jar
    val androidJar = Environment.ANDROID_JAR
    if (androidJar == null || !androidJar.exists() || androidJar.length() == 0L) {
      return OfflineStageResult(
          stage = OfflineBuildStage.JAVA_COMPILE,
          success = false,
          message = "Android platform framework JAR not found at ${androidJar?.absolutePath}",
          durationMs = System.currentTimeMillis() - startTime
      )
    }

    // 3. Collect all source files (project java + generated R.java)
    val projectSources = contract.javaFiles
    val generatedSources = workspace.genDir.walkTopDown()
        .filter { it.isFile && it.extension.equals("java", ignoreCase = true) }
        .toList()

    val allSources = (projectSources + generatedSources).distinctBy { it.canonicalPath }
    if (allSources.isEmpty()) {
      if (contract.hasNativeCode) {
        listener?.onStageLog(
            OfflineBuildStage.JAVA_COMPILE,
            "No Java sources or generated R.java to compile for native-only project. Skipping javac."
        )
        return OfflineStageResult(
            stage = OfflineBuildStage.JAVA_COMPILE,
            success = true,
            message = "Skipped Java compilation (native project without Java sources).",
            durationMs = System.currentTimeMillis() - startTime
        )
      }
      return OfflineStageResult(
          stage = OfflineBuildStage.JAVA_COMPILE,
          success = false,
          message = "No Java source files to compile.",
          durationMs = System.currentTimeMillis() - startTime
      )
    }

    listener?.onStageLog(
        OfflineBuildStage.JAVA_COMPILE,
        "Compiling ${allSources.size} Java source file(s) (${projectSources.size} project + ${generatedSources.size} generated)..."
    )

    // 4. Write source file paths to argument file (@sources.txt) to prevent command line length limits
    val sourcesListFile = File(workspace.rootDir, "sources.txt")
    sourcesListFile.writeText(
        allSources.joinToString(System.lineSeparator()) { src ->
            val path = src.absolutePath
            if (path.contains(' ')) "\"${path.replace("\\", "/")}\"" else path
        }
    )

    // 5. Execute javac via binary or embedded in-process compiler
    if (javacBin != null && javacBin.exists()) {
      if (!javacBin.canExecute()) {
        javacBin.setExecutable(true)
      }
      val cmd = listOf(
          javacBin.absolutePath,
          "-source", "1.8",
          "-target", "1.8",
          "-encoding", "UTF-8",
          "-bootclasspath", androidJar.absolutePath,
          "-cp", androidJar.absolutePath,
          "-d", workspace.classesDir.absolutePath,
          "@" + sourcesListFile.absolutePath
      )

      val processBuilder = ProcessBuilder(cmd)
          .directory(workspace.rootDir)
          .redirectErrorStream(false)

      val env = processBuilder.environment()
      Environment.putEnvironment(env, false)

      val process = processBuilder.start()
      val stdout = process.inputStream.bufferedReader().use { it.readText() }
      val stderr = process.errorStream.bufferedReader().use { it.readText() }

      val completed = process.waitFor(120, TimeUnit.SECONDS)
      if (!completed) {
        process.destroyForcibly()
        return OfflineStageResult(
            stage = OfflineBuildStage.JAVA_COMPILE,
            success = false,
            message = "javac process timed out after 120 seconds.",
            durationMs = System.currentTimeMillis() - startTime,
            details = "Stderr:\n$stderr\n\nStdout:\n$stdout"
        )
      }

      if (process.exitValue() != 0) {
        return OfflineStageResult(
            stage = OfflineBuildStage.JAVA_COMPILE,
            success = false,
            message = "Java compilation failed with exit code ${process.exitValue()}",
            durationMs = System.currentTimeMillis() - startTime,
            details = "Command: ${cmd.joinToString(" ")}\n\nErrors:\n$stderr\n\nOutput:\n$stdout"
        )
      }
    } else {
      // In-Process Embedded OpenJDK Compiler
      listener?.onStageLog(
          OfflineBuildStage.JAVA_COMPILE,
          "Using embedded in-process OpenJDK compiler..."
      )
      val javacArgs = mutableListOf(
          "-source", "1.8",
          "-target", "1.8",
          "-encoding", "UTF-8",
          "-bootclasspath", androidJar.absolutePath,
          "-cp", androidJar.absolutePath,
          "-d", workspace.classesDir.absolutePath,
          "@" + sourcesListFile.absolutePath
      )
      val outWriter = StringWriter()
      val printWriter = PrintWriter(outWriter)
      val exitCode = try {
        val mainClass = Class.forName("openjdk.tools.javac.Main")
        val compileMethod = mainClass.getMethod("compile", Array<String>::class.java, PrintWriter::class.java)
        compileMethod.invoke(null, javacArgs.toTypedArray(), printWriter) as Int
      } catch (e: Throwable) {
        return OfflineStageResult(
            stage = OfflineBuildStage.JAVA_COMPILE,
            success = false,
            message = "In-process javac compilation error: ${e.message}",
            durationMs = System.currentTimeMillis() - startTime,
            details = e.stackTraceToString()
        )
      }
      printWriter.flush()
      val output = outWriter.toString()

      if (exitCode != 0) {
        return OfflineStageResult(
            stage = OfflineBuildStage.JAVA_COMPILE,
            success = false,
            message = "Java compilation failed with exit code $exitCode",
            durationMs = System.currentTimeMillis() - startTime,
            details = output
        )
      }
    }

    // 6. Verify compiled classes
    val classFiles = workspace.classesDir.walkTopDown()
        .filter { it.isFile && it.extension.equals("class", ignoreCase = true) }
        .toList()

    if (classFiles.isEmpty()) {
      return OfflineStageResult(
          stage = OfflineBuildStage.JAVA_COMPILE,
          success = false,
          message = "javac succeeded with exit 0, but no .class files were produced in ${workspace.classesDir.absolutePath}",
          durationMs = System.currentTimeMillis() - startTime
      )
    }

    listener?.onStageLog(
        OfflineBuildStage.JAVA_COMPILE,
        "Java compilation successful. Produced ${classFiles.size} .class file(s)."
    )

    return OfflineStageResult(
        stage = OfflineBuildStage.JAVA_COMPILE,
        success = true,
        message = "Compiled ${classFiles.size} class file(s) successfully.",
        durationMs = System.currentTimeMillis() - startTime
    )
  }

  private fun findJavac(): File? {
    val fromJavaHome = File(Environment.JAVA_HOME, "bin/javac")
    if (fromJavaHome.exists()) return fromJavaHome

    val fromPrefix = File(Environment.PREFIX, "bin/javac")
    if (fromPrefix.exists()) return fromPrefix

    // Search common openjdk paths
    val candidateHomes = listOf(
        File(Environment.PREFIX, "lib/jvm/java-17-openjdk"),
        File(Environment.PREFIX, "lib/jvm/java-21-openjdk"),
        File(Environment.PREFIX, "lib/jvm/openjdk-17")
    )

    for (home in candidateHomes) {
      val j = File(home, "bin/javac")
      if (j.exists()) return j
    }

    return null
  }
}
