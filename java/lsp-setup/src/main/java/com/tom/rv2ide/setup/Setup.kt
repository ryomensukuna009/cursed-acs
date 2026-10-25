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

package com.tom.rv2ide.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.button.MaterialButton
import com.tom.rv2ide.setup.R
import com.tom.rv2ide.resources.R.string
import com.tom.rv2ide.setup.servers.ILanguageServerInstaller
import com.tom.rv2ide.setup.servers.clang.Clang
import com.tom.rv2ide.setup.servers.kotlin.Kotlin
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.utils.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class Setup(private val context: Context) {
  
  private val cppExtensions = setOf("c", "cpp", "cc", "cxx", "h", "hpp", "hh", "hxx")
  private val kotlinExtensions = setOf("kt", "kts")
  private val scope = CoroutineScope(Dispatchers.Main)
  
  fun scanProjectForLanguageServers(projectDir: File, onComplete: ((Boolean) -> Unit)? = null) {
    scope.launch {
      withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("lsp_setup_preferences", Context.MODE_PRIVATE)
        val serversToInstall = mutableListOf<Pair<String, String>>()
        
        val hasCppFiles = checkForFiles(projectDir, cppExtensions)
        val clangDismissed = prefs.getBoolean("pref_lsp_dismissed_clang", false)
        if (hasCppFiles && !clangDismissed && !isServerInstalled("clang")) {
          serversToInstall.add("clang" to context.getString(R.string.project_contians_clang_title))
        }
        
        val hasKotlinFiles = checkForFiles(projectDir, kotlinExtensions)
        val kotlinDismissed = prefs.getBoolean("pref_lsp_dismissed_kotlin", false)
        if (hasKotlinFiles && !kotlinDismissed && !isServerInstalled("kotlin")) {
          serversToInstall.add("kotlin" to context.getString(R.string.project_contians_kotlin_title))
        }
        
        serversToInstall
      }.let { servers ->
        if (servers.isNotEmpty()) {
          showLanguageServerDialogs(servers, 0, false, onComplete)
        } else {
          onComplete?.invoke(false)
        }
      }
    }
  }
  
  private fun checkForFiles(directory: File, extensions: Set<String>): Boolean {
    if (!directory.exists() || !directory.isDirectory) {
      return false
    }
    
    return directory.walkTopDown().any { file ->
      file.isFile && extensions.contains(file.extension.lowercase())
    }
  }
  
  private fun isServerInstalled(serverId: String): Boolean {
    val norm = serverId.lowercase()
    if (norm == "clang") {
      val clang = File(Environment.BIN_DIR, "clang")
      val clangd = File(Environment.BIN_DIR, "clangd")
      if (clang.exists() && clangd.exists() && clang.length() > 0L) {
        return true
      }
    } else if (norm == "kotlin") {
      val kotlinBinary = File(Environment.SERVERS_KOTLIN_DIR, "bin/kotlin-language-server")
      val libDir = File(Environment.SERVERS_KOTLIN_DIR, "lib")
      if (kotlinBinary.exists() && (libDir.listFiles()?.any { it.name.endsWith(".jar") } == true)) {
        return true
      }
    }
    return try {
      val installer = getLanguageServerInstaller(serverId)
      installer?.isInstalled() == true
    } catch (_: Throwable) {
      false
    }
  }
  
  private fun showLanguageServerDialogs(
    servers: List<Pair<String, String>>,
    index: Int,
    anyInstalled: Boolean,
    onComplete: ((Boolean) -> Unit)?
  ) {
    if (index >= servers.size) {
      onComplete?.invoke(anyInstalled)
      return
    }
    
    val (serverId, message) = servers[index]
    showLanguageServerDialog(serverId, message) { isSuccessful ->
      showLanguageServerDialogs(servers, index + 1, anyInstalled || isSuccessful, onComplete)
    }
  }
  
  private fun showLanguageServerDialog(serverId: String, message: String, onComplete: ((Boolean) -> Unit)?) {
    val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_language_server_setup, null)
    
    dialogView.findViewById<MaterialTextView>(R.id.tv_message).text = message
    
    val dialog = MaterialAlertDialogBuilder(context)
      .setView(dialogView)
      .setCancelable(true)
      .setOnDismissListener {
        onComplete?.invoke(false)
      }
      .create()
    
    dialogView.findViewById<MaterialButton>(R.id.btn_install).setOnClickListener {
      dialog.setOnDismissListener(null)
      dialog.dismiss()
      installLanguageServer(serverId, onComplete)
    }
    
    dialogView.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener {
      dialog.setOnDismissListener(null)
      dialog.dismiss()
      val prefs = context.getSharedPreferences("lsp_setup_preferences", Context.MODE_PRIVATE)
      prefs.edit().putBoolean("pref_lsp_dismissed_$serverId", true).apply()
      onComplete?.invoke(false)
    }
    
    dialog.show()
  }
  
  private fun installLanguageServer(serverId: String, onComplete: ((Boolean) -> Unit)?) {
    val progressView = LayoutInflater.from(context).inflate(R.layout.dialog_installation_progress, null)
    
    val progressIndicator = progressView.findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.progress_indicator)
    val outputText = progressView.findViewById<com.tom.rv2ide.editor.ui.IDEEditor>(R.id.tv_output)
    val copyButton = progressView.findViewById<MaterialButton>(R.id.btn_copy)
    val closeButton = progressView.findViewById<MaterialButton>(R.id.btn_close)
    
    val progressDialog = MaterialAlertDialogBuilder(context)
      .setView(progressView)
      .setCancelable(false)
      .create()
    
    val outputBuilder = StringBuilder()
    val installationResult = arrayOf(false)
    
    copyButton.visibility = View.GONE
    closeButton.visibility = View.GONE
    
    outputText.setTextSize(8f)
    
    copyButton.setOnClickListener {
      val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      val clip = ClipData.newPlainText("Installation Output", outputBuilder.toString())
      clipboard.setPrimaryClip(clip)
      Toast.makeText(context, "Output copied to clipboard", Toast.LENGTH_SHORT).show()
    }
    
    closeButton.setOnClickListener {
      progressDialog.dismiss()
      onComplete?.invoke(installationResult[0])
    }
    
    progressDialog.show()
    
    scope.launch {
      try {
        val installer = getLanguageServerInstaller(serverId)
            ?: throw IllegalStateException("No installer registered for server: $serverId")
        installationResult[0] = withContext(Dispatchers.IO) {
          installer.install { output ->
            scope.launch(Dispatchers.Main) {
              outputBuilder.append(output).append("\n")
              val text = outputBuilder.toString()
              outputText.setText(text)
              val lineCount = outputText.lineCount
              if (lineCount > 0) {
                val lastLine = lineCount - 1
                val lastLineLength = outputText.text.getLine(lastLine).length
                outputText.setSelection(Range(
                  Position(lastLine, lastLineLength, -1),
                  Position(lastLine, lastLineLength, -1)
                ))
              }
            }
          }
        }
        
        progressIndicator.visibility = View.GONE
        if (installationResult[0]) {
          outputBuilder.append("\nInstallation completed successfully!")
          val prefs = context.getSharedPreferences("lsp_setup_preferences", Context.MODE_PRIVATE)
          prefs.edit().remove("pref_lsp_dismissed_$serverId").apply()
          showSuccessfullyInstalledDialog(context)
        } else {
          outputBuilder.append("\nInstallation failed. Please check the output above.")
          copyButton.visibility = View.VISIBLE
        }
        val text = outputBuilder.toString()
        outputText.setText(text)
        val lineCount = outputText.lineCount
        if (lineCount > 0) {
          val lastLine = lineCount - 1
          val lastLineLength = outputText.text.getLine(lastLine).length
          outputText.setSelection(Range(
            Position(lastLine, lastLineLength, -1),
            Position(lastLine, lastLineLength, -1)
          ))
        }
        closeButton.visibility = View.VISIBLE
        
      } catch (e: Exception) {
        installationResult[0] = false
        progressIndicator.visibility = View.GONE
        outputBuilder.append("\nError: ${e.message}")
        val text = outputBuilder.toString()
        outputText.setText(text)
        val lineCount = outputText.lineCount
        if (lineCount > 0) {
          val lastLine = lineCount - 1
          val lastLineLength = outputText.text.getLine(lastLine).length
          outputText.setSelection(Range(
            Position(lastLine, lastLineLength, -1),
            Position(lastLine, lastLineLength, -1)
          ))
        }
        copyButton.visibility = View.VISIBLE
        closeButton.visibility = View.VISIBLE
        e.printStackTrace()
      }
    }
  }
  
  private fun showSuccessfullyInstalledDialog(ctx: Context) {
     MaterialAlertDialogBuilder(ctx)
       .setTitle(ctx.getString(R.string.lsp_installed_title))
       .setMessage(ctx.getString(R.string.lsp_installed_summary))
       .setPositiveButton(ctx.getString(R.string.lsp_ok_button), null)
       .show()
  }
  
  private fun getLanguageServerInstaller(serverId: String): ILanguageServerInstaller? {
    return when (serverId.lowercase()) {
      "clang" -> Clang(context)
      "kotlin" -> Kotlin(context)
      else -> {
        try {
          val className = "com.tom.rv2ide.setup.servers.${serverId}.${serverId.replaceFirstChar { it.uppercase() }}"
          val clazz = Class.forName(className)
          val constructor = clazz.getDeclaredConstructor(Context::class.java)
          constructor.newInstance(context) as? ILanguageServerInstaller
        } catch (_: Throwable) {
          null
        }
      }
    }
  }
}
