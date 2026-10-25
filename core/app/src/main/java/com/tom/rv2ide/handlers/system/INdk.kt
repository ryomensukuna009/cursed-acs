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
package com.tom.rv2ide.handlers.system

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.customizablecardview.CustomizableCardView
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.IDEConfigurations
import com.tom.rv2ide.activities.IdeConfigurations.*
import com.tom.rv2ide.fragments.sidebar.utils.showErrorDialog
import com.tom.rv2ide.handlers.IConfigHandler
import com.tom.rv2ide.handlers.system.installer.IPackageInstaller
import com.tom.rv2ide.ideconfigurations.utils.IDEUtils
import com.tom.rv2ide.managers.PreferenceManager
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.GeneralFileUtils
import com.tom.rv2ide.utils.IBuildSystemUtils
import com.tom.rv2ide.utils.flashError
import com.tom.rv2ide.utils.flashProgress
import com.tom.rv2ide.utils.flashSuccess
import android.net.Uri
import com.tom.rv2ide.ndk.NdkArchiveImporter
import com.tom.rv2ide.ndk.NdkSmokeTester
import com.tom.rv2ide.ndk.NdkToolchainClassifier
import com.tom.rv2ide.ndk.SafeNdkDeleteResult
import com.tom.rv2ide.ndk.SafeNdkDeleter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null */
class INdk(
    private val context: Context,
    private val prefManager: PreferenceManager,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val isDarkTheme: Boolean,
) : IConfigHandler {

  override val handlerName: String = "NDK"
  override val cardHolderId: Int = R.id.ndkCardHolder
  override val removeButtonId: Int = R.id.remove_btn
  override val reinstallButtonId: Int = R.id.reinstall_btn
  override val importButtonId: Int = R.id.import_btn
  override val testButtonId: Int = R.id.test_ndk_btn

  private val NDK_DIR = File(Environment.HOME, "android-sdk/ndk")
  private val ACS_DIR = File(Environment.HOME, "acs")

  private external fun processNdkInstall(version: String, arch: String, filename: String)

  companion object {
    private const val PREF_CACHED_NDK_SIZE = "cached_ndk_total_size"
  }

  private var sizeCalculationJob: kotlinx.coroutines.Job? = null

  override fun updateStatus() {
    val activity = context as? IDEConfigurations
    val deleter = SafeNdkDeleter(baseNdkDir = NDK_DIR, prefManager = prefManager)
    val availableVersions = deleter.getInstalledVersions()
    val activeVersion = deleter.getActiveNdkVersion()

    // 1. Get cached size first - instant, NO disk scan on main thread!
    val cachedSizeBytes = prefManager.getLong(PREF_CACHED_NDK_SIZE, -1L)

    // 2. Render UI immediately with cached size (or no size badge if not yet calculated)
    renderStatusSummary(availableVersions, activeVersion, if (cachedSizeBytes >= 0) cachedSizeBytes else null)

    // 3. Update active NDK dropdown in UI
    updateActiveNdkDropdown(activity, availableVersions, activeVersion)

    // 4. Trigger asynchronous background size calculation if needed
    triggerAsyncSizeCalculation(deleter, availableVersions, activeVersion)
  }

  private fun renderStatusSummary(
      availableVersions: List<String>,
      activeVersion: String?,
      totalSizeBytes: Long?
  ) {
    val activity = context as? IDEConfigurations
    val cardHolder = activity?.findViewById<CustomizableCardView>(R.id.ndkCardHolder)
    val removeBtn = (context as? android.app.Activity)?.findViewById<View>(removeButtonId)
    val testBtn = (context as? android.app.Activity)?.findViewById<View>(testButtonId)

    val errorColor = try {
      androidx.core.content.ContextCompat.getColor(context, R.color.status_error)
    } catch (_: Throwable) {
      Color.RED
    }
    val successColor = try {
      androidx.core.content.ContextCompat.getColor(context, R.color.status_success)
    } catch (_: Throwable) {
      if (isDarkTheme) Color.GREEN else Color.rgb(0, 75, 0)
    }

    val sizeBadge = if (totalSizeBytes != null && totalSizeBytes > 0) {
      " (${SafeNdkDeleter.formatBytes(totalSizeBytes)})"
    } else ""

    val statusText: String
    val color: Int

    when {
      availableVersions.isEmpty() -> {
        statusText = "Not installed"
        color = errorColor
        removeBtn?.visibility = View.GONE
        testBtn?.visibility = View.GONE
      }
      availableVersions.size == 1 -> {
        statusText = "${availableVersions.first()}$sizeBadge"
        color = successColor
        removeBtn?.visibility = View.VISIBLE
        testBtn?.visibility = View.VISIBLE
      }
      else -> {
        val activePrefix = if (!activeVersion.isNullOrBlank()) "Active: $activeVersion" else availableVersions.first()
        statusText = "$activePrefix (${availableVersions.size} installed)$sizeBadge"
        color = successColor
        removeBtn?.visibility = View.VISIBLE
        testBtn?.visibility = View.VISIBLE
      }
    }

    val fullText = context.getString(R.string.cfg_installed_ndk_version, statusText)
    val spannableString = SpannableString(fullText)

    val startIndex = fullText.indexOf(statusText)
    if (startIndex != -1) {
      val endIndex = startIndex + statusText.length
      spannableString.setSpan(
          ForegroundColorSpan(color),
          startIndex,
          endIndex,
          SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
      )
    }

    cardHolder?.setCardSummary(spannableString)

    // Restore the green-colored status line directly above active NDK dropdown selector
    val tvActiveNdkInfo = (context as? android.app.Activity)?.findViewById<TextView>(R.id.tvActiveNdkInfo)
    tvActiveNdkInfo?.text = spannableString
  }

  fun updateActiveNdkDropdown(activity: IDEConfigurations?, availableVersions: List<String>, activeVersion: String?) {
    if (activity == null) return
    val activeDropdown = activity.findViewById<android.widget.AutoCompleteTextView>(R.id.active_ndk_dropdown) ?: return

    if (availableVersions.isEmpty()) {
      activeDropdown.setText("No NDK installed", false)
      activeDropdown.setAdapter(null)
      activeDropdown.isEnabled = false
      return
    }

    activeDropdown.isEnabled = true
    val items = availableVersions.map { ver ->
      if (ver.equals(activeVersion, ignoreCase = true)) "$ver [Active]" else ver
    }

    val adapter = android.widget.ArrayAdapter(context, R.layout.dropdown_item, items)
    activeDropdown.setAdapter(adapter)

    val activeDisplay = if (!activeVersion.isNullOrBlank() && availableVersions.contains(activeVersion)) {
      "$activeVersion [Active]"
    } else {
      "${availableVersions.last()} [Active]"
    }
    activeDropdown.setText(activeDisplay, false)

    activeDropdown.setOnItemClickListener { _, _, position, _ ->
      val selectedRaw = availableVersions.getOrNull(position) ?: return@setOnItemClickListener
      setActiveNdk(selectedRaw)
    }
  }

  private fun triggerAsyncSizeCalculation(
      deleter: SafeNdkDeleter,
      availableVersions: List<String>,
      activeVersion: String?,
      forceRecalculate: Boolean = false
  ) {
    if (availableVersions.isEmpty()) {
      prefManager.putLong(PREF_CACHED_NDK_SIZE, 0L)
      return
    }

    val cachedSize = prefManager.getLong(PREF_CACHED_NDK_SIZE, -1L)
    if (cachedSize >= 0L && !forceRecalculate) {
      return
    }

    if (sizeCalculationJob?.isActive == true) {
      return
    }

    sizeCalculationJob = lifecycleScope.launch(Dispatchers.IO) {
      try {
        val totalBytes = deleter.calculateTotalNdkSize()
        prefManager.putLong(PREF_CACHED_NDK_SIZE, totalBytes)
        withContext(Dispatchers.Main) {
          renderStatusSummary(availableVersions, activeVersion, totalBytes)
        }
      } catch (t: Throwable) {
        android.util.Log.w("INdk", "Failed to calculate total NDK size: ${t.message}")
      }
    }
  }

  fun setActiveNdk(version: String) {
    val targetDir = File(NDK_DIR, version)
    if (!SafeNdkDeleter.isValidNdkDir(targetDir)) {
      flashError("Cannot select '$version': Missing valid NDK markers.")
      return
    }

    try {
      IBuildSystemUtils.setNdkVersion(version)
      prefManager.putString("bs_native_kit_version", version)
      prefManager.putBoolean("ndk_installed", true)
      flashSuccess("Active NDK set to: $version")
      updateStatus()
    } catch (e: Exception) {
      flashError("Failed to set active NDK: ${e.message}")
    }
  }

  fun showActiveNdkSelectionDialog() {
    val deleter = SafeNdkDeleter(baseNdkDir = NDK_DIR, prefManager = prefManager)
    val installed = deleter.getInstalledVersions()
    if (installed.isEmpty()) {
      flashError("No installed NDK versions available.")
      return
    }

    val currentActive = deleter.getActiveNdkVersion()
    val displayItems = installed.map { ver ->
      if (ver.equals(currentActive, ignoreCase = true)) "$ver [Active]" else ver
    }.toTypedArray()

    val checkedItem = installed.indexOfFirst { it.equals(currentActive, ignoreCase = true) }.coerceAtLeast(0)

    MaterialAlertDialogBuilder(context)
        .setTitle("Select Active NDK for Build System")
        .setSingleChoiceItems(displayItems, checkedItem) { dialog, which ->
          val selected = installed[which]
          setActiveNdk(selected)
          dialog.dismiss()
        }
        .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
        .show()
  }

  override fun onTestClick() {
    testCurrentNdk()
  }

  private fun testCurrentNdk() {
    val deleter = SafeNdkDeleter(baseNdkDir = NDK_DIR, prefManager = prefManager)
    val availableVersions = deleter.getInstalledVersions()
    val activeVersion = deleter.getActiveNdkVersion()

    when {
      availableVersions.isEmpty() -> {
        flashError("No NDK installed. Please import or download an NDK first.")
      }
      availableVersions.size == 1 -> {
        runSmokeTestForVersion(availableVersions.first())
      }
      else -> {
        showVersionSelectionDialog(
            title = "Select NDK Version to Test",
            versions = availableVersions,
            activeVersion = activeVersion,
            onVersionSelected = { selectedVersion -> runSmokeTestForVersion(selectedVersion) },
        )
      }
    }
  }

  private fun runSmokeTestForVersion(version: String) {
    val targetNdk = File(NDK_DIR, version)
    flashProgress(configure = { message("Testing NDK compiler ($version)...") }) { progressFlashbar ->
      lifecycleScope.launch(Dispatchers.Main) {
        try {
          val tester = NdkSmokeTester(context)
          val result = withContext(Dispatchers.IO) { tester.runSmokeTest(targetNdk) }
          progressFlashbar.dismiss()

          val dialogTitle = if (result.success) "NDK Test Passed" else "NDK Test Failed"
          val messageContent = buildString {
            appendLine("NDK Version: $version")
            appendLine("Status: ${if (result.success) "SUCCESS" else "FAILED"}")
            appendLine("Exit Code: ${result.exitCode}")
            appendLine("Execution Time: ${result.durationMs} ms")
            val libName = result.libraryName ?: result.outputLibrary?.name ?: "libndk_smoke.so"
            appendLine("Library: $libName (${result.librarySizeBytes} bytes)")
            if (result.elfArchitecture != null) {
              appendLine("Architecture: ${result.elfArchitecture}")
            }
            appendLine("ELF Valid: ${result.isElfValid}")
            if (result.preservedLibraryFile != null) {
              appendLine("Preserved File: ${result.preservedLibraryFile.absolutePath}")
            }
            if (!result.errorMessage.isNullOrEmpty()) {
              appendLine()
              appendLine("Error Message:")
              appendLine(result.errorMessage)
            }
            if (result.stdout.isNotBlank()) {
              appendLine()
              appendLine("--- ndk-build stdout ---")
              appendLine(result.stdout.take(1500))
            }
            if (result.stderr.isNotBlank()) {
              appendLine()
              appendLine("--- ndk-build stderr ---")
              appendLine(result.stderr.take(1500))
            }
          }

          MaterialAlertDialogBuilder(context)
              .setTitle(dialogTitle)
              .setMessage(messageContent)
              .setPositiveButton("OK") { d, _ -> d.dismiss() }
              .show()
        } catch (e: Exception) {
          progressFlashbar.dismiss()
          flashError("Test execution error: ${e.message}")
        }
      }
    }
  }

  override fun onRemoveClick() {
    val deleter = SafeNdkDeleter(baseNdkDir = NDK_DIR, prefManager = prefManager)
    val availableVersions = deleter.getInstalledVersions()
    val activeVersion = deleter.getActiveNdkVersion()

    when {
      availableVersions.isEmpty() -> {
        Toast.makeText(context, "No NDK versions found to remove", Toast.LENGTH_SHORT).show()
      }
      availableVersions.size == 1 -> {
        removeNdkVersion(availableVersions.first())
      }
      else -> {
        showVersionSelectionDialog(
            title = "Select NDK Version to Remove",
            versions = availableVersions,
            activeVersion = activeVersion,
            onVersionSelected = { selectedVersion -> removeNdkVersion(selectedVersion) },
        )
      }
    }
  }

  override fun onReinstallClick() {
    if (!isACSProgramExists()) {
      showErrorDialog(
          ctx = context,
          title = context.getString(R.string.no_acs_title),
          message = context.getString(R.string.no_acs_summary),
          negativeBtnTitle = "OK",
      )
      return
    }

    // Get the version from the dropdown in IDEConfigurations
    val activity = context as? IDEConfigurations
    val ndkDropdown =
        activity?.findViewById<android.widget.AutoCompleteTextView>(R.id.ndks_dropdown)
    val selectedVersion = ndkDropdown?.text?.toString()?.trim()

    if (
        selectedVersion.isNullOrEmpty() ||
            selectedVersion == "Loading versions..." ||
            selectedVersion == "No versions available"
    ) {
      flashError("Please select ndk version from the dropdown first")
      return
    }

    initializeAcs("android-native-kit", selectedVersion)
  }

  override fun onImportClick() {
    val activity = context as? IDEConfigurations
    if (activity != null) {
      val mimeTypes =
          arrayOf(
              "application/zip",
              "application/x-zip-compressed",
              "application/gzip",
              "application/x-gzip",
              "application/x-tar",
              "application/x-xz",
              "application/octet-stream",
              "*/*",
          )
      activity.importNdkLauncher.launch(mimeTypes)
    } else {
      flashError("Cannot launch file picker: activity not available")
    }
  }

  fun importNdkArchive(uri: Uri) {
    flashProgress(configure = { message("Importing NDK archive...") }) { progressFlashbar ->
      lifecycleScope.launch(Dispatchers.Main) {
        try {
          val result =
              withContext(Dispatchers.IO) {
                NdkArchiveImporter().importFromUri(
                    context = context,
                    uri = uri,
                    targetBaseDir = NDK_DIR,
                )
              }

          progressFlashbar.dismiss()

          if (result.success && result.installedDir != null) {
            prefManager.putBoolean("ndk_installed", true)
            IBuildSystemUtils.setNdkVersion(result.installedDir.name)
            prefManager.putString("bs_native_kit_version", result.installedDir.name)
            prefManager.remove(PREF_CACHED_NDK_SIZE)
            flashSuccess("Successfully imported NDK: ${result.installedDir.name}")
            updateStatus()

            val classification = result.classification
            val details = buildString {
              appendLine("Installation Directory: ${result.installedDir.name}")
              if (classification != null) {
                appendLine("Classification: ${classification.description}")
                appendLine(
                    "Host Architecture: ${classification.hostArchitecture ?: "Unknown"} (Bionic Native: ${classification.isBionicNative})",
                )
                if (classification.targetAbis.isNotEmpty()) {
                  appendLine("Target ABIs: ${classification.targetAbis.joinToString(", ")}")
                }
                if (classification.apiLevels.isNotEmpty()) {
                  appendLine(
                      "Supported API Levels: android-${classification.apiLevels.first()} to android-${classification.apiLevels.last()}",
                  )
                }
                if (classification.warnings.isNotEmpty()) {
                  appendLine()
                  appendLine("Warnings:")
                  classification.warnings.forEach { appendLine("• $it") }
                }
              }
            }

            MaterialAlertDialogBuilder(context)
                .setTitle("NDK Imported Successfully")
                .setMessage(details)
                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                .show()
          } else {
            flashError("Import failed: ${result.errorMessage}")
            showErrorDialog(
                ctx = context,
                title = "NDK Import Failed",
                message = result.errorMessage ?: "Unknown error during NDK import.",
                negativeBtnTitle = "OK",
            )
          }
        } catch (e: Exception) {
          progressFlashbar.dismiss()
          flashError("Import error: ${e.message}")
          e.printStackTrace()
        }
      }
    }
  }

  override fun initialize() {
    // Initialize NDK if needed
  }

  override fun cleanup() {
    sizeCalculationJob?.cancel()
    sizeCalculationJob = null
  }

  private fun removeNdkVersion(version: String) {
    val deleter = SafeNdkDeleter(NDK_DIR, prefManager)
    val inspection = deleter.inspectNdkForDeletion(version)

    when (inspection) {
      is SafeNdkDeleteResult.Rejected -> {
        MaterialAlertDialogBuilder(context)
            .setTitle("Cannot Remove NDK")
            .setMessage(inspection.reason + (inspection.details?.let { "\n\n$it" } ?: ""))
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
      }
      is SafeNdkDeleteResult.Error -> {
        flashError("Error inspecting NDK: ${inspection.message}")
      }
      is SafeNdkDeleteResult.ValidationSummary -> {
        val message = SpannableStringBuilder().apply {
          val warnHeader = "⚠️ WARNING: PERMANENT NDK DELETION\n\n"
          val sWarn = length
          append(warnHeader)
          setSpan(ForegroundColorSpan(Color.parseColor("#E53935")), sWarn, length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
          setSpan(StyleSpan(Typeface.BOLD), sWarn, length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)

          append("NDK Version: ${inspection.version}\n")
          append("Disk Space Freed: ${inspection.formattedSize}\n")
          append("Location: ${inspection.targetPath}\n\n")

          val damageHeader = "DAMAGES & CONSEQUENCES:\n"
          val sDam = length
          append(damageHeader)
          setSpan(ForegroundColorSpan(Color.parseColor("#E53935")), sDam, length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
          setSpan(StyleSpan(Typeface.BOLD), sDam, length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)

          if (inspection.isLastNdk) {
            append("❌ This is your ONLY installed NDK version!\n")
            append("❌ Deleting it will completely disable offline C/C++ compilation in CURSED ACS.\n")
            append("❌ Native builds and ndk-build will fail until an NDK archive is re-imported.\n")
          } else if (inspection.isActive) {
            val fallback = inspection.remainingVersionsAfterDelete.lastOrNull()
            append("⚠️ This is currently your active NDK compiler.\n")
            append("• Active NDK will automatically switch to '$fallback'.\n")
            append("• Projects configured explicitly for ${inspection.version} may fail until reconfigured.\n")
          } else {
            append("• All Clang compilers, sysroots, and libraries for ${inspection.version} will be permanently erased.\n")
          }
          append("\nDo you want to proceed with permanent deletion?")
        }

        val positiveBtnText = when {
          inspection.isLastNdk -> "Permanently Delete (Disable NDK)"
          inspection.isActive -> "Switch & Delete"
          else -> "Permanently Delete"
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Confirm NDK Deletion")
            .setMessage(message)
            .setPositiveButton(positiveBtnText) { d, _ ->
              d.dismiss()
              executeNdkDeletion(deleter, inspection)
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#E53935"))
      }
      else -> Unit
    }
  }

  private fun executeNdkDeletion(deleter: SafeNdkDeleter, summary: SafeNdkDeleteResult.ValidationSummary) {
    flashProgress(configure = { message("Deleting NDK ${summary.version}...") }) { progressFlashbar ->
      lifecycleScope.launch(Dispatchers.Main) {
        try {
          val result = withContext(Dispatchers.IO) {
            deleter.deleteNdkSafely(
                version = summary.version,
                forceDeleteActive = summary.isActive,
                allowDeleteLast = summary.isLastNdk
            )
          }
          progressFlashbar.dismiss()

          when (result) {
            is SafeNdkDeleteResult.Success -> {
              val msg = "Successfully removed NDK ${result.version} (Freed ${SafeNdkDeleter.formatBytes(result.freedBytes)})"
              flashSuccess(msg)
              if (result.newActiveVersion != null) {
                Toast.makeText(context, "Active NDK switched to ${result.newActiveVersion}", Toast.LENGTH_LONG).show()
              }
              prefManager.remove(PREF_CACHED_NDK_SIZE)
              updateStatus()
            }
            is SafeNdkDeleteResult.Rejected -> {
              flashError("Deletion rejected: ${result.reason}")
            }
            is SafeNdkDeleteResult.Error -> {
              flashError("Failed to remove NDK: ${result.message}")
            }
            else -> Unit
          }
        } catch (e: Exception) {
          progressFlashbar.dismiss()
          flashError("Deletion failed: ${e.message}")
        }
      }
    }
  }

  private fun showVersionSelectionDialog(
      title: String,
      versions: List<String>,
      activeVersion: String? = null,
      onVersionSelected: (String) -> Unit,
  ) {
    val displayItems = versions.map { ver ->
      if (ver.equals(activeVersion, ignoreCase = true)) "$ver [Active]" else ver
    }.toTypedArray()

    MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setItems(displayItems) { dialog, which ->
          val selectedVersion = versions[which]
          onVersionSelected(selectedVersion)
          dialog.dismiss()
        }
        .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
        .create()
        .show()
  }

  private fun initializeAcs(packageId: String, version: String) {
    showErrorDialog(
        ctx = context,
        title = context.getString(R.string.acs_install_title),
        message = context.getString(R.string.acs_ask_install_message, version),
        negativeBtnTitle = context.getString(R.string.btn_proceed_str),
        onNegativeClick = {
          val cpuArch = getCpuArchitecture()
          if (!listOf("armeabi-v7a", "arm64-v8a", "x86_64").contains(cpuArch)) {
            flashError("Unsupported architecture: $cpuArch")
            return@showErrorDialog
          }

          val architecture: AcsCommandInterface.Architecture =
              when (cpuArch) {
                "armeabi-v7a" -> AcsCommandInterface.Architecture.ARM_V7A
                "arm64-v8a" -> AcsCommandInterface.Architecture.ARM64_V8A
                "x86_64" -> AcsCommandInterface.Architecture.X86_64
                else -> throw IllegalStateException("This should never happen")
              }

          flashProgress(configure = { message("Getting package information...") }) { infoFlashbar ->
            lifecycleScope.launch(Dispatchers.Main) {
              try {
                val filenameResult =
                    withContext(Dispatchers.IO) {
                      AcsCommandInterface.getPackageField(
                          manifestUrl = AcsProvider.getManifestUrl,
                          architecture = architecture,
                          packageId = packageId,
                          field = AcsCommandInterface.ManifestField.FILENAME,
                          version = version,
                      )
                    }

                infoFlashbar.dismiss()

                if (!filenameResult.success) {
                  flashError("Failed to get package filename: ${filenameResult.errorOutput}")
                  return@launch
                }

                val filename = filenameResult.output.trim()
                if (filename.isEmpty()) {
                  flashError("Empty filename received from manifest")
                  return@launch
                }

                flashProgress(configure = { message("Downloading $version...") }) { downloadFlashbar
                  ->
                  lifecycleScope.launch(Dispatchers.IO) {
                    try {
                      val downloadResult =
                          AcsProvider.acsRunner(
                              packageId = packageId,
                              artifactVersion = version,
                              arch = architecture,
                          )

                      withContext(Dispatchers.Main) {
                        downloadFlashbar.dismiss()

                        if (downloadResult.output.contains("successfully", ignoreCase = true)) {
                          val successFlash = flashSuccess("Successfully downloaded")

                          lifecycleScope.launch(Dispatchers.IO) {
                            delay(1000)
                            withContext(Dispatchers.Main) { successFlash?.dismiss() }

                            withContext(Dispatchers.Main) {
                              flashProgress(configure = { message("Extracting $version...") }) {
                                  extractFlashbar ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                  try {
                                    val installer = IPackageInstaller()
                                    val archiveFile = File(ACS_DIR, filename)

                                    installer.extractXzArchive(
                                        archiveFile.absolutePath,
                                        NDK_DIR.absolutePath,
                                    )

                                    withContext(Dispatchers.Main) {
                                      extractFlashbar.dismiss()
                                      prefManager.putBoolean("ndk_installed", true)
                                      IBuildSystemUtils.setNdkVersion(version)
                                      prefManager.putString("bs_native_kit_version", version)
                                      prefManager.remove(PREF_CACHED_NDK_SIZE)
                                      flashSuccess("Installation completed successfully!")
                                      renameDir(NDK_DIR.absolutePath, "$version")
                                      updateNdkBuild(version)
                                      updateStatus()
                                    }
                                  } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                      extractFlashbar.dismiss()
                                      flashError("Installation failed: ${e.message}")
                                      e.printStackTrace()
                                    }
                                  }
                                }
                              }
                            }
                          }
                        } else {
                          showErrorDialog(
                              ctx = context,
                              title = "Download Failed",
                              message =
                                  "Package verification failed:\n${downloadResult.output}\n${downloadResult.errorOutput}",
                              negativeBtnTitle = "OK",
                          )
                        }
                      }
                    } catch (e: Exception) {
                      withContext(Dispatchers.Main) {
                        downloadFlashbar.dismiss()
                        flashError("Download failed: ${e.message}")
                        e.printStackTrace()
                      }
                    }
                  }
                }
              } catch (e: Exception) {
                infoFlashbar.dismiss()
                flashError("Failed to get package info: ${e.message}")
                e.printStackTrace()
              }
            }
          }
        },
    )
  }

  private fun updateNdkBuild(version: String) {
    val build_script = File(NDK_DIR, "$version/ndk-build")
    if (build_script.exists()) {
      build_script.writeText(
          """
    #!/bin/sh
    DIR=${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)
    ${Environment.BIN_DIR}/bash "${'$'}DIR/build/ndk-build" "${'$'}@"
            """
              .trimIndent()
      )
      build_script.setExecutable(true)
    }
  }

  private fun renameDir(parentDir: String, newName: String = "ndk") {
    val parent = File(parentDir)

    if (!parent.exists() || !parent.isDirectory) {
      return
    }

    parent.listFiles()?.forEach { file ->
      if (file.isDirectory && file.name.startsWith("android-ndk")) {
        val newFile = File(parent, newName)

        // Handle name conflicts by adding a number suffix
        var finalNewFile = newFile
        var counter = 1
        while (finalNewFile.exists()) {
          finalNewFile = File(parent, "$newName-$counter")
          counter++
        }

        if (file.renameTo(finalNewFile)) {
          println("Renamed: ${file.name} -> ${finalNewFile.name}")
        } else {
          println("Failed to rename: ${file.name}")
        }
      }
    }
  }

  private fun isACSProgramExists(): Boolean {
    // DEPRECATED
    // val program: File = File(context.filesDir.parent, "usr/bin/acs")
    // return GeneralFileUtils.isFileExists(program.absoluteFile)
    return true // using the acs library which is always going to be available
  }

  private fun getVersion(): String {
    return (IBuildSystemUtils::getNdkVersion)() ?: "Unknown"
  }
}
