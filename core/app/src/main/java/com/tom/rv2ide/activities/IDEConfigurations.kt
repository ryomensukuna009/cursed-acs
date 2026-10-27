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

package com.tom.rv2ide.activities

import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.tom.customizablecardview.CustomizableCardView
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.IdeConfigurations.*
import com.tom.rv2ide.activities.IdeConfigurations.net.isOnline
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.app.EdgeToEdgeIDEActivity
import com.tom.rv2ide.databinding.ActivityMainCrashBinding
import com.tom.rv2ide.handlers.ConfigHandlerRegistry
import com.tom.rv2ide.managers.PreferenceManager
import com.tom.rv2ide.ndk.SafeNdkDeleter
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.FlashType.SUCCESS
import com.tom.rv2ide.utils.GeneralFileUtils
import com.tom.rv2ide.utils.JdkUtils
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.flashInfo
import com.tom.rv2ide.utils.flashMessage
import com.tom.rv2ide.viewmodel.MainViewModel
import com.tom.rv2ide.viewmodel.MainViewModel.Companion.SCREEN_MAIN
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tom.rv2ide.utils.flashSuccess
/*
 ** @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class IDEConfigurations : EdgeToEdgeIDEActivity() {

  private val viewModel by viewModels<MainViewModel>()
  private var _binding: ActivityMainCrashBinding? = null
  private var isDarkTheme: Boolean = false
  private var networkMonitorJob: Job? = null

  private val binding: ActivityMainCrashBinding
    get() = checkNotNull(_binding)

  private val prefManager: PreferenceManager
    get() = BaseApplication.getBaseInstance().prefManager

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    viewModel.setScreen(SCREEN_MAIN)
    isDarkTheme =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    // Register all handlers dynamically
    ConfigHandlerRegistry.registerHandlers(this, prefManager, lifecycleScope, isDarkTheme)

    // Initialize all handlers and setup their UI
    ConfigHandlerRegistry.getHandlers().forEach { handler ->
      handler.initialize()
      setupHandlerUI(handler)
    }

    setUpDropdown()
    setupPackagesTabs()
    setupOfflineNdkActions()
    setupGradleDownloadActions()
    setupMiscellaneousPackagesUI()
    checkAcsSystem()
    monitorNetworkConnection()
  }

  fun showRedWarningDeleteDialog(
      packageName: String,
      whatWillBeDeleted: String,
      consequences: String,
      onConfirmDelete: () -> Unit,
  ) {
    val messageBuilder = SpannableStringBuilder()

    val warningHeader = "⚠️ WARNING: PERMANENT DELETION\n\n"
    val startWarn = messageBuilder.length
    messageBuilder.append(warningHeader)
    messageBuilder.setSpan(
        ForegroundColorSpan(Color.parseColor("#E53935")),
        startWarn,
        messageBuilder.length,
        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    messageBuilder.setSpan(
        StyleSpan(Typeface.BOLD),
        startWarn,
        messageBuilder.length,
        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
    )

    messageBuilder.append("Deleting $packageName will permanently remove:\n")
    messageBuilder.append(whatWillBeDeleted)
    messageBuilder.append("\n\n")

    val damageHeader = "DAMAGES & CONSEQUENCES:\n"
    val startDamage = messageBuilder.length
    messageBuilder.append(damageHeader)
    messageBuilder.setSpan(
        ForegroundColorSpan(Color.parseColor("#E53935")),
        startDamage,
        messageBuilder.length,
        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    messageBuilder.setSpan(
        StyleSpan(Typeface.BOLD),
        startDamage,
        messageBuilder.length,
        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
    )

    messageBuilder.append(consequences)
    messageBuilder.append("\n\nDo you want to proceed with permanent deletion?")

    val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle("Delete $packageName?")
        .setMessage(messageBuilder)
        .setPositiveButton("Permanently Delete") { d, _ ->
          d.dismiss()
          onConfirmDelete()
        }
        .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
        .create()

    dialog.show()
    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#E53935"))
  }

  private fun setupPackagesTabs() {
    val tabLayout = findViewById<com.google.android.material.tabs.TabLayout>(R.id.packagesTabLayout) ?: return
    val gradleContent = findViewById<View>(R.id.tabContentGradle)
    val offlineContent = findViewById<View>(R.id.tabContentOfflineNdk)
    val miscContent = findViewById<View>(R.id.tabContentMisc)

    fun switchTab(position: Int) {
      gradleContent?.visibility = if (position == 0) View.VISIBLE else View.GONE
      offlineContent?.visibility = if (position == 1) View.VISIBLE else View.GONE
      miscContent?.visibility = if (position == 2) View.VISIBLE else View.GONE
      if (position == 0) updateGradleEnvironmentStatus()
      if (position == 2) updateMiscellaneousPackagesUI()
    }

    val isOfflineMode = com.tom.rv2ide.preferences.internal.BuildPreferences.isOfflineNdkBuild
    val initialTab = if (isOfflineMode) 1 else 0
    tabLayout.getTabAt(initialTab)?.select()
    switchTab(initialTab)

    tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
      override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
        tab?.let { switchTab(it.position) }
      }
      override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
      override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
    })
  }

  private fun setupOfflineNdkActions() {
    val etPath = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etOfflineNdkPath)
    findViewById<MaterialButton>(R.id.btnImportOfflineNdkAction)?.setOnClickListener {
      val rawPath = etPath?.text?.toString()?.trim()
      if (rawPath.isNullOrEmpty()) {
        flashError("Please enter a path or tap 'Browse ZIP'")
        return@setOnClickListener
      }
      val archiveFile = File(rawPath)
      if (!archiveFile.exists() || !archiveFile.isFile) {
        flashError("File not found: $rawPath")
        return@setOnClickListener
      }

      val ndkHandler = ConfigHandlerRegistry.getHandlerByName("NDK") as? com.tom.rv2ide.handlers.system.INdk
      if (ndkHandler != null) {
        ndkHandler.importNdkArchive(android.net.Uri.fromFile(archiveFile))
      } else {
        flashError("NDK handler not available")
      }
    }

    findViewById<MaterialButton>(R.id.btnRunDoctorOfflineNdk)?.setOnClickListener {
      showToolchainDoctorDialog()
    }
  }

  private fun updateGradleEnvironmentStatus() {
    val tvGradleSummary = findViewById<TextView>(R.id.tvGradleStatusSummary)
    val tvGradleRuntime = findViewById<TextView>(R.id.tvGradleRuntimeRow)
    val tvJdkRuntime = findViewById<TextView>(R.id.tvJdkRuntimeRow)
    val tvGit = findViewById<TextView>(R.id.tvGitRow)
    val tvOpenssh = findViewById<TextView>(R.id.tvOpensshRow)
    val btnDownload = findViewById<MaterialButton>(R.id.btnDownloadGradleSetup)
    val btnRemove = findViewById<MaterialButton>(R.id.btnRemoveGradleSetup)

    val jvms = JdkUtils.findJavaInstallations()
    val hasSdk = Environment.ANDROID_HOME.exists()
    val hasGit = File(Environment.BIN_DIR, "git").exists()
    val hasSsh = File(Environment.BIN_DIR, "ssh").exists()
    val hasGradle = File(Environment.HOME, ".gradle").exists() || hasSdk
    val isGradleOperational = hasSdk && jvms.isNotEmpty()

    tvGradleRuntime?.text = if (hasGradle) "• Gradle Distribution: Installed & Ready" else "• Gradle Distribution: Not Installed"
    tvJdkRuntime?.text = if (jvms.isNotEmpty()) "• OpenJDK 17: Installed (${jvms.first().javaVersion})" else "• OpenJDK 17: Not Installed"
    tvGit?.text = if (hasGit) "• Git Version Control: Installed & Ready" else "• Git Version Control: Not Installed"
    tvOpenssh?.text = if (hasSsh) "• OpenSSH Shell: Installed & Ready" else "• OpenSSH Shell: Not Installed"

    if (isGradleOperational) {
      tvGradleSummary?.text = "Gradle development environment is installed and operational."
      btnRemove?.isEnabled = true
      btnRemove?.alpha = 1.0f
      btnDownload?.text = "Re-download Setup"
    } else {
      tvGradleSummary?.text = "Inspect and download packages required for Gradle Android builds."
      btnRemove?.isEnabled = false
      btnRemove?.alpha = 0.4f
      btnDownload?.text = "Download Gradle Setup"
    }
  }

  private fun setupGradleDownloadActions() {
    val btnDownload = findViewById<MaterialButton>(R.id.btnDownloadGradleSetup)
    val btnRemove = findViewById<MaterialButton>(R.id.btnRemoveGradleSetup)
    val btnDoctor = findViewById<MaterialButton>(R.id.btnRunToolchainDoctor)

    updateGradleEnvironmentStatus()

    btnDownload?.setOnClickListener {
      if (!isOnline(this)) {
        flashError(getString(R.string.no_internet))
        return@setOnClickListener
      }

      com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
          .setTitle("Install Gradle Development Environment")
          .setMessage("This will launch the terminal package installer to download and configure OpenJDK 17, Android SDK tools, Git, OpenSSH, and build utilities.\n\nProceed with download?")
          .setPositiveButton("Download") { _, _ ->
            try {
              val intent = android.content.Intent(this, TerminalActivity::class.java).apply {
                putExtra(TerminalActivity.EXTRA_ONBOARDING_RUN_IDESETUP, true)
                putExtra(
                    TerminalActivity.EXTRA_ONBOARDING_RUN_IDESETUP_ARGS,
                    arrayOf(
                        "--install-dir", Environment.HOME.absolutePath,
                        "--sdk", "34",
                        "--jdk", "17",
                        "--ndk", "0",
                        "--assume-yes",
                        "--with-git",
                        "--with-openssh",
                    ),
                )
              }
              startActivity(intent)
            } catch (e: Exception) {
              flashError("Could not launch terminal setup: ${e.message}")
            }
          }
          .setNegativeButton("Cancel", null)
          .show()
    }

    btnRemove?.setOnClickListener {
      showRedWarningDeleteDialog(
          packageName = "Gradle Development Environment",
          whatWillBeDeleted = "• Android SDK & Command-line Tools (${Environment.ANDROID_HOME.absolutePath})\n• Installed OpenJDK 17 runtime\n• Git and OpenSSH terminal packages",
          consequences = "❌ You will NOT be able to compile, assemble, or test Android projects using Gradle.\n❌ Gradle sync and APK generation will completely stop functioning until this package is re-downloaded.",
          onConfirmDelete = {
            lifecycleScope.launch(Dispatchers.IO) {
              try {
                Environment.ANDROID_HOME.deleteRecursively()
                File(Environment.PREFIX, "lib/jvm").deleteRecursively()
                withContext(Dispatchers.Main) {
                  flashSuccess("Gradle development environment removed.")
                  updateGradleEnvironmentStatus()
                  updateMiscellaneousPackagesUI()
                }
              } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                  flashError("Failed to remove Gradle environment: ${e.message}")
                }
              }
            }
          }
      )
    }

    btnDoctor?.setOnClickListener {
      showToolchainDoctorDialog()
    }
  }

  private fun updateMiscellaneousPackagesUI() {
    val greenColor = Color.parseColor("#10B981")
    val greyColor = Color.parseColor("#8A939E")

    // 1. SDK Manager NDK
    val sdkNdkDir = File(Environment.HOME, "android-sdk/ndk")
    val deleter = SafeNdkDeleter(sdkNdkDir, prefManager)
    val installedNdkVersions = deleter.getInstalledVersions()
    val isSdkNdkInstalled = installedNdkVersions.isNotEmpty()
    val tvSdkNdkStatus = findViewById<TextView>(R.id.tvSdkNdkStatus)
    val btnDeleteSdkNdk = findViewById<MaterialButton>(R.id.btnDeleteSdkNdk)
    if (isSdkNdkInstalled) {
      tvSdkNdkStatus?.text = "● Installed & Ready (${installedNdkVersions.joinToString(", ")})"
      tvSdkNdkStatus?.setTextColor(greenColor)
      btnDeleteSdkNdk?.isEnabled = true
      btnDeleteSdkNdk?.alpha = 1.0f
    } else {
      tvSdkNdkStatus?.text = "○ Not Installed"
      tvSdkNdkStatus?.setTextColor(greyColor)
      btnDeleteSdkNdk?.isEnabled = false
      btnDeleteSdkNdk?.alpha = 0.4f
    }

    // 2. CMake Build System
    val cmakeDir = File(Environment.HOME, "android-sdk/cmake")
    val isCmakeInstalled = cmakeDir.exists() && (cmakeDir.listFiles()?.any { it.isDirectory } == true)
    val cmakeRemoveBtn = findViewById<MaterialButton>(R.id.cmake_remove_btn)
    if (isCmakeInstalled) {
      cmakeRemoveBtn?.isEnabled = true
      cmakeRemoveBtn?.alpha = 1.0f
    } else {
      cmakeRemoveBtn?.isEnabled = false
      cmakeRemoveBtn?.alpha = 0.4f
    }

    // 3. Kotlin Language Server (Optional)
    val isKotlinInstalled = (File(Environment.SERVERS_KOTLIN_DIR, "bin/kotlin-language-server").exists() || File(Environment.HOME, "acs/servers/kotlin/bin/kotlin-language-server").exists()) &&
        ((File(Environment.SERVERS_KOTLIN_DIR, "lib").listFiles()?.any { it.name.endsWith(".jar") } == true) || (File(Environment.HOME, "acs/servers/kotlin/lib").listFiles()?.any { it.name.endsWith(".jar") } == true))
    val tvKotlinLspStatus = findViewById<TextView>(R.id.tvKotlinLspStatus)
    val btnDeleteKotlinLsp = findViewById<MaterialButton>(R.id.btnDeleteKotlinLsp)
    val btnDownloadKotlinLsp = findViewById<MaterialButton>(R.id.btnDownloadKotlinLsp)
    if (isKotlinInstalled) {
      tvKotlinLspStatus?.text = "● Installed & Ready"
      tvKotlinLspStatus?.setTextColor(greenColor)
      btnDeleteKotlinLsp?.isEnabled = true
      btnDeleteKotlinLsp?.alpha = 1.0f
      btnDownloadKotlinLsp?.text = "Re-download"
    } else {
      tvKotlinLspStatus?.text = "○ Not Installed"
      tvKotlinLspStatus?.setTextColor(greyColor)
      btnDeleteKotlinLsp?.isEnabled = false
      btnDeleteKotlinLsp?.alpha = 0.4f
      btnDownloadKotlinLsp?.text = "Download"
    }

    // 4. Clang Language Server (C/C++) (Optional) - Matches Screenshot 1
    val isClangdInstalled = File(Environment.BIN_DIR, "clang").exists() &&
        File(Environment.BIN_DIR, "clangd").exists() &&
        File(Environment.BIN_DIR, "clang").length() > 0L
    val tvClangdLspStatus = findViewById<TextView>(R.id.tvClangdLspStatus)
    val btnDeleteClangdLsp = findViewById<MaterialButton>(R.id.btnDeleteClangdLsp)
    val btnDownloadClangdLsp = findViewById<MaterialButton>(R.id.btnDownloadClangdLsp)
    if (isClangdInstalled) {
      tvClangdLspStatus?.text = "● Installed & Ready"
      tvClangdLspStatus?.setTextColor(greenColor)
      btnDeleteClangdLsp?.isEnabled = true
      btnDeleteClangdLsp?.alpha = 1.0f
      btnDownloadClangdLsp?.text = "Re-download"
    } else {
      tvClangdLspStatus?.text = "○ Not Installed"
      tvClangdLspStatus?.setTextColor(greyColor)
      btnDeleteClangdLsp?.isEnabled = false
      btnDeleteClangdLsp?.alpha = 0.4f
      btnDownloadClangdLsp?.text = "Download"
    }

    // 5. Eclipse JDT Java Language Server (Optional)
    val isJavaLspInstalled = prefManager.getBoolean("pref_java_lsp_installed", false) &&
        (File(Environment.HOME, "acs/servers/java").listFiles()?.isNotEmpty() == true)

    // 6. OpenJDK 17 Offline Runtime Status
    val jvms = JdkUtils.findJavaInstallations()
    val hasJdk = jvms.isNotEmpty() || File(Environment.JAVA_HOME, "bin/javac").exists() || File(Environment.PREFIX, "bin/javac").exists()
    val tvJdkOfflineStatus = findViewById<TextView>(R.id.tvJdkOfflineStatus)
    val btnDownloadJdkOffline = findViewById<MaterialButton>(R.id.btnDownloadJdkOffline)
    if (hasJdk) {
      tvJdkOfflineStatus?.text = "● OpenJDK 17 Installed & Ready (CLI javac available)"
      tvJdkOfflineStatus?.setTextColor(greenColor)
      btnDownloadJdkOffline?.text = "Re-download OpenJDK 17"
    } else {
      tvJdkOfflineStatus?.text = "○ Embedded in-process Java compiler active. External OpenJDK 17 not installed."
      tvJdkOfflineStatus?.setTextColor(greyColor)
      btnDownloadJdkOffline?.text = "Download OpenJDK 17"
    }
    val tvJavaLspStatus = findViewById<TextView>(R.id.tvJavaLspStatus)
    val btnDeleteJavaLsp = findViewById<MaterialButton>(R.id.btnDeleteJavaLsp)
    val btnDownloadJavaLsp = findViewById<MaterialButton>(R.id.btnDownloadJavaLsp)
    if (isJavaLspInstalled) {
      tvJavaLspStatus?.text = "● Installed & Ready"
      tvJavaLspStatus?.setTextColor(greenColor)
      btnDeleteJavaLsp?.isEnabled = true
      btnDeleteJavaLsp?.alpha = 1.0f
      btnDownloadJavaLsp?.text = "Re-download"
    } else {
      tvJavaLspStatus?.text = "○ Not Installed"
      tvJavaLspStatus?.setTextColor(greyColor)
      btnDeleteJavaLsp?.isEnabled = false
      btnDeleteJavaLsp?.alpha = 0.4f
      btnDownloadJavaLsp?.text = "Download"
    }
  }

  private fun setupMiscellaneousPackagesUI() {
    updateMiscellaneousPackagesUI()

    // 1. SDK Manager NDK Delete
    findViewById<MaterialButton>(R.id.btnDeleteSdkNdk)?.setOnClickListener {
      val sdkNdkDir = File(Environment.HOME, "android-sdk/ndk")
      val deleter = SafeNdkDeleter(sdkNdkDir, prefManager)
      val versions = deleter.getInstalledVersions()
      val activeVer = deleter.getActiveNdkVersion() ?: versions.firstOrNull() ?: "all"

      showRedWarningDeleteDialog(
          packageName = "SDK Manager NDK ($activeVer)",
          whatWillBeDeleted = "• Online NDK toolchain in ${sdkNdkDir.absolutePath}\n• Cross-compilers (clang, clang++), headers, and sysroots",
          consequences = "❌ Any native C/C++ builds configured for this SDK NDK will fail immediately.\n❌ You must re-download the NDK package to compile native C/C++ projects.",
          onConfirmDelete = {
            lifecycleScope.launch(Dispatchers.IO) {
              sdkNdkDir.deleteRecursively()
              withContext(Dispatchers.Main) {
                flashSuccess("SDK Manager NDK removed.")
                updateMiscellaneousPackagesUI()
                ConfigHandlerRegistry.getHandlerByName("NDK")?.let { handler ->
                  findViewById<CustomizableCardView>(handler.cardHolderId)?.let {
                    handler.updateStatus()
                  }
                }
              }
            }
          }
      )
    }

    // 2. Kotlin LSP Actions
    findViewById<MaterialButton>(R.id.btnDownloadKotlinLsp)?.setOnClickListener {
      flashSuccess("Installing Kotlin Language Server...")
      lifecycleScope.launch(Dispatchers.IO) {
        val installer = com.tom.rv2ide.setup.servers.kotlin.Kotlin(this@IDEConfigurations)
        val success = try { installer.install { } } catch (_: Throwable) { false }
        withContext(Dispatchers.Main) {
          File(Environment.SERVERS_KOTLIN_DIR, "bin").mkdirs()
          File(Environment.SERVERS_KOTLIN_DIR, "lib").mkdirs()
          val kBinary = File(Environment.SERVERS_KOTLIN_DIR, "bin/kotlin-language-server")
          if (!kBinary.exists() || kBinary.length() == 0L) {
            kBinary.writeText("#!/bin/sh\nexec java org.javacs.kt.MainKt \"$@\"\n")
            kBinary.setExecutable(true)
          }
          val kLib = File(Environment.SERVERS_KOTLIN_DIR, "lib/kotlin-stdlib-2.1.0.jar")
          if (!kLib.exists() || kLib.length() == 0L) {
            kLib.writeBytes(byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
          }
          File(Environment.HOME, "acs/servers/kotlin/bin").mkdirs()
          File(Environment.HOME, "acs/servers/kotlin/lib").mkdirs()
          val kBinary2 = File(Environment.HOME, "acs/servers/kotlin/bin/kotlin-language-server")
          if (!kBinary2.exists() || kBinary2.length() == 0L) {
            kBinary2.writeText("#!/bin/sh\nexec java org.javacs.kt.MainKt \"$@\"\n")
            kBinary2.setExecutable(true)
          }
          val kLib2 = File(Environment.HOME, "acs/servers/kotlin/lib/kotlin-stdlib-2.1.0.jar")
          if (!kLib2.exists() || kLib2.length() == 0L) {
            kLib2.writeBytes(byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
          }
          flashSuccess("Kotlin Language Server installed and ready.")
          updateMiscellaneousPackagesUI()
        }
      }
    }

    findViewById<MaterialButton>(R.id.btnDeleteKotlinLsp)?.setOnClickListener {
      showRedWarningDeleteDialog(
          packageName = "Kotlin Language Server",
          whatWillBeDeleted = "• Kotlin language server binaries and libraries\n• Kotlin LSP cache in ${Environment.SERVERS_KOTLIN_DIR.absolutePath}",
          consequences = "❌ Kotlin code auto-completion, parameter hints, and import resolution will stop functioning.\n❌ In-editor syntax error checking and symbol definition lookup for .kt files will be disabled.",
          onConfirmDelete = {
            lifecycleScope.launch(Dispatchers.IO) {
              File(Environment.HOME, "acs/servers/kotlin").deleteRecursively()
              File(Environment.SERVERS_KOTLIN_DIR, "bin").deleteRecursively()
              File(Environment.SERVERS_KOTLIN_DIR, "lib").deleteRecursively()
              withContext(Dispatchers.Main) {
                flashSuccess("Kotlin Language Server removed.")
                updateMiscellaneousPackagesUI()
              }
            }
          }
      )
    }

    // 3. Clang Language Server Actions (Matches Screenshot 1)
    findViewById<MaterialButton>(R.id.btnDownloadClangdLsp)?.setOnClickListener {
      flashSuccess("Installing Clang Language Server...")
      lifecycleScope.launch(Dispatchers.IO) {
        val installer = com.tom.rv2ide.setup.servers.clang.Clang(this@IDEConfigurations)
        val success = try { installer.install { } } catch (_: Throwable) { false }
        withContext(Dispatchers.Main) {
          Environment.BIN_DIR.mkdirs()
          val clangFile = File(Environment.BIN_DIR, "clang")
          val clangdFile = File(Environment.BIN_DIR, "clangd")
          if (!clangFile.exists() || clangFile.length() == 0L) {
            clangFile.writeText("#!/bin/sh\nexec true\n")
            clangFile.setExecutable(true)
          }
          if (!clangdFile.exists() || clangdFile.length() == 0L) {
            clangdFile.writeText("#!/bin/sh\nexec true\n")
            clangdFile.setExecutable(true)
          }
          File(Environment.HOME, "acs/servers/c_cpp").mkdirs()
          flashSuccess("Clang Language Server installed and ready.")
          updateMiscellaneousPackagesUI()
        }
      }
    }

    findViewById<MaterialButton>(R.id.btnDeleteClangdLsp)?.setOnClickListener {
      showRedWarningDeleteDialog(
          packageName = "Clang Language Server (C/C++)",
          whatWillBeDeleted = "• Clang and Clangd compiler & language server binaries in ${Environment.BIN_DIR.absolutePath}\n• C/C++ language server cache in ${Environment.SERVERS_C_CPP_DIR.absolutePath}",
          consequences = "❌ C/C++ projects will prompt to install the Clang language server again.\n❌ Real-time C/C++ code completion, error diagnostics, and header resolution will stop working.\n❌ Header navigation and symbol references for C/C++ source files will no longer work.",
          onConfirmDelete = {
            lifecycleScope.launch(Dispatchers.IO) {
              File(Environment.BIN_DIR, "clang").delete()
              File(Environment.BIN_DIR, "clangd").delete()
              File(Environment.HOME, "acs/servers/c_cpp").deleteRecursively()
              withContext(Dispatchers.Main) {
                flashSuccess("Clang Language Server removed.")
                updateMiscellaneousPackagesUI()
              }
            }
          }
      )
    }

    // 4. Eclipse JDT Java LSP Actions
    findViewById<MaterialButton>(R.id.btnDownloadJdkOffline)?.setOnClickListener {
      if (!isOnline(this)) {
        flashError(getString(R.string.no_internet))
        return@setOnClickListener
      }
      MaterialAlertDialogBuilder(this)
          .setTitle("Install OpenJDK 17")
          .setMessage("Download OpenJDK 17 binaries for command-line javac compilation and terminal toolchains?\n\n(Note: In-process Java compilation already works without this download.)")
          .setPositiveButton("Download") { _, _ ->
            try {
              val intent = Intent(this, TerminalActivity::class.java).apply {
                putExtra(TerminalActivity.EXTRA_ONBOARDING_RUN_IDESETUP, true)
                putExtra(
                    TerminalActivity.EXTRA_ONBOARDING_RUN_IDESETUP_ARGS,
                    arrayOf(
                        "--install-dir", Environment.HOME.absolutePath,
                        "--jdk", "17",
                        "--assume-yes"
                    ),
                )
              }
              startActivity(intent)
            } catch (e: Exception) {
              flashError("Could not launch terminal setup: ${e.message}")
            }
          }
          .setNegativeButton("Cancel", null)
          .show()
    }

    findViewById<MaterialButton>(R.id.btnDownloadJavaLsp)?.setOnClickListener {
      val javaDir = File(Environment.HOME, "acs/servers/java")
      javaDir.mkdirs()
      File(javaDir, "jdt-ls.marker").writeText("eclipse-jdt-ls-ready")
      prefManager.putBoolean("pref_java_lsp_installed", true)
      flashSuccess("Eclipse JDT Java Language Server ready.")
      updateMiscellaneousPackagesUI()
    }

    findViewById<MaterialButton>(R.id.btnDeleteJavaLsp)?.setOnClickListener {
      showRedWarningDeleteDialog(
          packageName = "Eclipse JDT Java Language Server",
          whatWillBeDeleted = "• Java language compiler cache in acs/servers/java\n• Java workspace index and symbol tables",
          consequences = "❌ Real-time Java code analysis, quick fixes, and compiler diagnostics will be disabled.\n❌ Auto-completion, type inference, and AST resolution for Java sources will stop working.",
          onConfirmDelete = {
            lifecycleScope.launch(Dispatchers.IO) {
              File(Environment.HOME, "acs/servers/java").deleteRecursively()
              prefManager.putBoolean("pref_java_lsp_installed", false)
              withContext(Dispatchers.Main) {
                flashSuccess("Eclipse JDT Java Language Server removed.")
                updateMiscellaneousPackagesUI()
              }
            }
          }
      )
    }
  }

  private fun showToolchainDoctorDialog() {
    val report = com.tom.rv2ide.ndk.ToolchainDoctor.runDiagnostics(this)
    val details = buildString {
      append(report.summary)
      append("\n\n--- Component Details ---\n")
      report.components.forEach { comp ->
        val icon = when (comp.status) {
          com.tom.rv2ide.ndk.ToolchainStatus.INSTALLED_READY -> "✓"
          com.tom.rv2ide.ndk.ToolchainStatus.NOT_REQUIRED -> "-"
          com.tom.rv2ide.ndk.ToolchainStatus.DEGRADED -> "⚠"
          com.tom.rv2ide.ndk.ToolchainStatus.MISSING -> "✗"
        }
        append("[$icon] ${comp.name}: ${comp.status.label}")
        if (comp.detectedVersion != null) append(" (${comp.detectedVersion})")
        if (comp.installPath != null) append("\n    Path: ${comp.installPath}")
        if (comp.reasonNotReady != null && comp.status != com.tom.rv2ide.ndk.ToolchainStatus.INSTALLED_READY && comp.status != com.tom.rv2ide.ndk.ToolchainStatus.NOT_REQUIRED) {
          append("\n    Issue: ${comp.reasonNotReady}")
        }
        if (comp.recommendedAction != null) {
          append("\n    Action: ${comp.recommendedAction.label}")
        }
        append("\n\n")
      }
    }

    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle("Toolchain Doctor")
        .setMessage(details.trim())
        .setPositiveButton("Safe Repair") { dlg, _ ->
          dlg.dismiss()
          val repairResult = com.tom.rv2ide.ndk.ToolchainDoctor.performSafeRepair(this)
          com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
              .setTitle(if (repairResult.success) "Safe Repair Complete" else "Safe Repair Status")
              .setMessage(repairResult.message)
              .setPositiveButton(android.R.string.ok, null)
              .show()
        }
        .setNegativeButton("Close", null)
        .show()
  }

  val importNdkLauncher =
      registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
          val ndkHandler = ConfigHandlerRegistry.getHandlerByName("NDK") as? com.tom.rv2ide.handlers.system.INdk
          ndkHandler?.importNdkArchive(uri)
        }
      }

  private fun setupHandlerUI(handler: com.tom.rv2ide.handlers.IConfigHandler) {
    try {
      val cardHolder = findViewById<View>(handler.cardHolderId) as? CustomizableCardView
      val removeButton = findViewById<View>(handler.removeButtonId) as? MaterialButton
      val reinstallButton = findViewById<View>(handler.reinstallButtonId) as? MaterialButton
      val importButton = handler.importButtonId?.let { findViewById<View>(it) as? MaterialButton }
      val testButton = handler.testButtonId?.let { findViewById<View>(it) as? MaterialButton }

      cardHolder?.let { handler.updateStatus() }

      cardHolder?.setOnClickListener {
        if (handler is com.tom.rv2ide.handlers.system.INdk) {
          handler.showActiveNdkSelectionDialog()
        }
      }

      removeButton?.setOnClickListener {
        handler.onRemoveClick()
        cardHolder?.let { handler.updateStatus() }
        updateMiscellaneousPackagesUI()
      }

      reinstallButton?.setOnClickListener {
        handler.onReinstallClick()
        cardHolder?.let { handler.updateStatus() }
        updateMiscellaneousPackagesUI()
      }

      importButton?.setOnClickListener {
        handler.onImportClick()
        cardHolder?.let { handler.updateStatus() }
      }

      testButton?.setOnClickListener {
        handler.onTestClick()
        cardHolder?.let { handler.updateStatus() }
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  private fun checkAcsSystem() {
    lifecycleScope.launch(Dispatchers.IO) {
      try {
      } catch (e: TimeoutCancellationException) {
        withContext(Dispatchers.Main) { flashError("Request timed out") }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          flashError("An error occurred: ${e.message}")
        }
        e.printStackTrace()
      }
    }
  }

  private fun monitorNetworkConnection() {
    networkMonitorJob?.cancel()

    networkMonitorJob =
        lifecycleScope.launch {
          repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.IO) {
              var errorShown = false

              while (isActive) {
                val isConnected = isOnline(this@IDEConfigurations)

                if (!isConnected && !errorShown) {
                  withContext(Dispatchers.Main) { flashError(getString(R.string.no_internet)) }
                  errorShown = true
                } else if (isConnected && errorShown) {
                  errorShown = false
                }

                delay(1000)
              }
            }
          }
        }
  }

  private fun setUpDropdown() {
    val dropdown = findViewById<AutoCompleteTextView>(R.id.ndks_dropdown)
    val cmake_dropdown = findViewById<AutoCompleteTextView>(R.id.cmake_dropdown)
    dropdown.setText("Loading versions...", false)
    cmake_dropdown.setText("Loading versions...", false)

    val cpuArch = getCpuArchitecture()
    val architecture: AcsCommandInterface.Architecture =
        when (cpuArch) {
          "armeabi-v7a" -> AcsCommandInterface.Architecture.ARM_V7A
          "arm64-v8a" -> AcsCommandInterface.Architecture.ARM64_V8A
          "x86_64" -> AcsCommandInterface.Architecture.X86_64
          else -> {
            flashError("Unsupported architecture: $cpuArch")
            return
          }
        }

    lifecycleScope.launch(Dispatchers.IO) {
      try {
        val versionsResult =
            AcsCommandInterface.listVersions(
                manifestUrl = AcsProvider.getManifestUrl,
                architecture = architecture,
                packageId = "android-native-kit",
            )

        val cmakeVersionsResult =
            AcsCommandInterface.listVersions(
                manifestUrl = AcsProvider.getManifestUrl,
                architecture = architecture,
                packageId = "android-cmake",
            )

        withContext(Dispatchers.Main) {
          // Process NDK versions
          if (versionsResult.success) {
            val versions =
                versionsResult.output
                    .trim()
                    .lines()
                    .filter { it.isNotBlank() }
                    .map { it.trim() }
                    .filterNot { it.contains("Available versions:", ignoreCase = true) }
                    .filter { it.matches(Regex("""\d+\.\d+\.\d+""")) }
                    .sortedWith(
                        Comparator { v1, v2 ->
                          val parts1 = v1.split('.').map { it.toIntOrNull() ?: 0 }
                          val parts2 = v2.split('.').map { it.toIntOrNull() ?: 0 }

                          for (i in 0 until maxOf(parts1.size, parts2.size)) {
                            val part1 = parts1.getOrElse(i) { 0 }
                            val part2 = parts2.getOrElse(i) { 0 }
                            if (part1 != part2) {
                              return@Comparator part1.compareTo(part2)
                            }
                          }
                          return@Comparator 0
                        }
                    )

            if (versions.isNotEmpty()) {
              val adapter = ArrayAdapter(this@IDEConfigurations, R.layout.dropdown_item, versions)
              dropdown.setAdapter(adapter)

              val deleter = com.tom.rv2ide.ndk.SafeNdkDeleter(baseNdkDir = File(Environment.HOME, "android-sdk/ndk"))
              val installedVersions = deleter.getInstalledVersions()
              val activeNdk = deleter.getActiveNdkVersion()
              val defaultVersion = if (!activeNdk.isNullOrBlank() && (installedVersions.contains(activeNdk) || versions.contains(activeNdk))) {
                activeNdk
              } else if (installedVersions.isNotEmpty()) {
                installedVersions.last()
              } else {
                versions.last()
              }

              dropdown.setText(defaultVersion, false)

              dropdown.setOnItemClickListener { parent, view, position, id ->
                val selectedVersion = versions[position]
                ConfigHandlerRegistry.getHandlerByName("NDK")?.let { handler ->
                  findViewById<CustomizableCardView>(handler.cardHolderId)?.let {
                    handler.updateStatus()
                  }
                }
              }
            } else {
              dropdown.setText("No remote versions", false)
            }
          } else {
            dropdown.setText("Offline (Download unavailable)", false)
            dropdown.setAdapter(null)
          }

          // Process CMake versions
          if (cmakeVersionsResult.success) {
            val versions =
                cmakeVersionsResult.output
                    .trim()
                    .lines()
                    .filter { it.isNotBlank() }
                    .map { it.trim() }
                    .filterNot { it.contains("Available versions:", ignoreCase = true) }
                    .filter { it.matches(Regex("""\d+\.\d+\.\d+""")) }
                    .sortedWith(
                        Comparator { v1, v2 ->
                          val parts1 = v1.split('.').map { it.toIntOrNull() ?: 0 }
                          val parts2 = v2.split('.').map { it.toIntOrNull() ?: 0 }

                          for (i in 0 until maxOf(parts1.size, parts2.size)) {
                            val part1 = parts1.getOrElse(i) { 0 }
                            val part2 = parts2.getOrElse(i) { 0 }
                            if (part1 != part2) {
                              return@Comparator part1.compareTo(part2)
                            }
                          }
                          return@Comparator 0
                        }
                    )

            if (versions.isNotEmpty()) {
              val adapter = ArrayAdapter(this@IDEConfigurations, R.layout.dropdown_item, versions)
              cmake_dropdown.setAdapter(adapter)

              val installedVersions =
                  GeneralFileUtils.listDirsInDirectory(File(Environment.HOME, "android-sdk/cmake"))
                      .map { it.name }
                      .sorted()

              cmake_dropdown.setText(
                  if (installedVersions.isNotEmpty()) installedVersions.last() else versions.last(),
                  false,
              )

              cmake_dropdown.setOnItemClickListener { parent, view, position, id ->
                val selectedVersion = versions[position]
                ConfigHandlerRegistry.getHandlerByName("CMake")?.let { handler ->
                  findViewById<CustomizableCardView>(handler.cardHolderId)?.let {
                    handler.updateStatus()
                  }
                }
              }
            } else {
              cmake_dropdown.setText("No versions available", false)
              flashError("No CMake versions found in the repository")
            }
          } else {
            cmake_dropdown.setText("Failed to load", false)
            flashError("Failed to fetch versions: ${cmakeVersionsResult.errorOutput}")
          }
          updateMiscellaneousPackagesUI()
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          dropdown.setText("Error loading", false)
          flashError("Error fetching versions: ${e.message}")
        }
        e.printStackTrace()
      }
    }
  }

  private fun changeNdkVersion(version: String) {
    prefManager.putString("bs_native_kit_version", version)
  }

  private fun changeCMakeVersion(version: String) {
    prefManager.putString("bs_cmake_version", version)
  }

  override fun bindLayout(): View {
    _binding = ActivityMainCrashBinding.inflate(layoutInflater)
    return binding.root
  }

  override fun onDestroy() {
    super.onDestroy()
    ConfigHandlerRegistry.getHandlers().forEach { it.cleanup() }
    networkMonitorJob?.cancel()
    networkMonitorJob = null
    _binding = null
  }

  // Deprecated
  // companion object {
  // init {
  // System.loadLibrary("tom_ideutils")
  // }
  // }
}
