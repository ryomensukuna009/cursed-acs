/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.updatePaddingRelative
import com.blankj.utilcode.util.ClipboardUtils
import com.blankj.utilcode.util.SizeUtils
import com.tom.rv2ide.BuildConfig
import com.tom.rv2ide.R
import com.tom.rv2ide.adapters.SimpleIconTitleDescriptionAdapter
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.app.EdgeToEdgeIDEActivity
import com.tom.rv2ide.app.configuration.IDEBuildConfigProvider
import com.tom.rv2ide.buildinfo.BuildInfo
import com.tom.rv2ide.databinding.ActivityAboutBinding
import com.tom.rv2ide.models.IconTitleDescriptionItem
import com.tom.rv2ide.models.SimpleIconTitleDescriptionItem
import com.tom.rv2ide.utils.BuildInfoUtils
import com.tom.rv2ide.utils.flashSuccess
import com.tom.rv2ide.utils.resolveAttr

class AboutActivity : EdgeToEdgeIDEActivity() {

  private var _binding: ActivityAboutBinding? = null
  private val binding: ActivityAboutBinding
    get() = checkNotNull(_binding) { "Activity has been destroyed" }

  override fun bindLayout(): View {
    _binding = ActivityAboutBinding.inflate(layoutInflater)
    return _binding!!.root
  }

  companion object {

    private var id = 0
    private val ACTION_WEBSITE = id++
    private val ACTION_EMAIL = id++
    private val ACTION_TG_CHANNEL = id++
    private val ACTION_TG_GROUP = id++
    private val ACTION_CONTRIBUTE = id++
    private val ACTION_CONTRIBUTORS = id++
    private val ACTION_UPSTREAM_INFO = id++
    private val ACTION_LICENSES = id++
    private val ACTION_PRIVACY_SECURITY = id++
    private val ACTION_CHECK_UPDATES = id++
    // private val ACTION_QQ_GROUP = id++
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding.aboutAppLogo?.let { com.tom.rv2ide.utils.AppLogoManager.bindLogo(it) }

    binding.apply {
      setSupportActionBar(toolbar)
      supportActionBar!!.setDisplayHomeAsUpEnabled(true)
      supportActionBar!!.setTitle(R.string.about)
      toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

      aboutHeader.apply {
        ideVersion.text = createVersionText()
        ideVersion.isClickable = true
        ideVersion.isFocusable = true
        ideVersion.setBackgroundResource(R.drawable.bg_ripple)
        ideVersion.setOnClickListener {
          ClipboardUtils.copyText(BuildInfoUtils.getBuildInfoHeader())
          flashSuccess(R.string.copied)
        }
      }

      findViewById<View>(R.id.btnCreatorTelegram)?.setOnClickListener {
        app.openUrl("https://t.me/Ryomen_Sukuna_009")
      }
      findViewById<View>(R.id.btnCreatorGithub)?.setOnClickListener {
        app.openUrl("https://github.com/ryomensukuna009/cursed-acs")
      }

      socials.apply {
        sectionTitle.setText(R.string.title_socials)
        sectionItems.adapter = AboutSocialItemsAdapter(createSocialItems(), ::handleActionClick)
      }

      misc.apply {
        sectionTitle.setText(R.string.title_misc)
        sectionItems.adapter = AboutSocialItemsAdapter(createMiscItems(), ::handleActionClick)
      }
    }
  }

  override fun onApplySystemBarInsets(insets: Insets) {
    binding.toolbar.apply {
      setPaddingRelative(
          paddingStart + insets.left,
          paddingTop,
          paddingEnd + insets.right,
          paddingBottom,
      )
    }
  }

  private fun handleActionClick(action: SimpleIconTitleDescriptionItem) {
    when (action.id) {
      ACTION_WEBSITE -> app.openWebsite()
      ACTION_EMAIL -> app.emailUs()
      ACTION_TG_GROUP -> app.openTelegramGroup()
      ACTION_TG_CHANNEL -> app.openTelegramChannel()
      ACTION_CONTRIBUTE -> app.openUrl(BaseApplication.CONTRIBUTOR_GUIDE_URL)
      ACTION_CONTRIBUTORS -> startActivity(Intent(this, ContributorsActivity::class.java))
      ACTION_UPSTREAM_INFO -> showUpstreamAttributionDialog()
      ACTION_LICENSES -> showLicensesDialog()
      ACTION_PRIVACY_SECURITY -> showPrivacySecurityDialog()
      ACTION_CHECK_UPDATES -> TomIDEUpdater(this).checkForUpdates(manual = true)
    }
  }

  private fun showUpstreamAttributionDialog() {
    val message = SpannableStringBuilder().apply {
      append("LEAD CREATOR & PRODUCT ARCHITECT:\n", android.text.style.StyleSpan(android.graphics.Typeface.BOLD), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
      append("• Ryomen Sukuna (Solo Vibe Coder)\n")
      append("• Telegram: https://t.me/Ryomen_Sukuna_009 (@Ryomen_Sukuna_009)\n")
      append("• Official Repository: https://github.com/ryomensukuna009/cursed-acs\n\n")

      append("ORIGINAL UPSTREAM PROJECT:\n", android.text.style.StyleSpan(android.graphics.Typeface.BOLD), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
      append("• Name: AndroidIDE\n")
      append("• Upstream Author: itsaky (https://github.com/itsaky)\n")
      append("• Upstream Repository: https://github.com/AndroidIDE-Official/AndroidIDE\n")
      append("• License: GNU General Public License v3.0 or later (GPL-3.0-or-later)\n\n")

      append("CURSED ACS MODIFICATION / FORK DETAILS:\n", android.text.style.StyleSpan(android.graphics.Typeface.BOLD), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
      append("CURSED ACS is an advanced downstream distribution engineered for autonomous, resilient Android development directly on physical mobile devices.\n\n")
      append("Key Customizations & Enhancements:\n")
      append("1. Zero-delay Sukuna Domain Expansion launch engine with transparent WebGL/Canvas bloom and concurrent background IDE loading.\n")
      append("2. Jujutsu Cursed AI Agent with multi-provider integration (Gemini, Claude, GPT, DeepSeek, Grok) and collapsible thought reasoning (> ⌄).\n")
      append("3. Offline standalone NDK toolchain management with .zip/.tar.gz import, Zip Slip security traversal checks, and active version switching.\n")
      append("4. Modular Packages Configurations with individual package lifecycles (SDK Manager NDK, CMake, Clang Language Server, Kotlin LSP, Java LSP).\n")
      append("5. Build Toolchain Doctor: multi-component compiler, SDK, and JDK diagnostic engine with actionable repair suggestions.\n")
      append("6. Non-destructive project removal preserving on-device source trees.\n")
      append("7. Unified terminal build streaming and isolated problems center diagnostics.\n\n")
      append("All source code changes strictly adhere to the terms and obligations of the GNU General Public License v3.0.")
    }

    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
      .setTitle("Credits, Attribution & Fork Info")
      .setIcon(R.drawable.ic_github)
      .setMessage(message)
      .setPositiveButton("Creator Telegram") { _, _ ->
        app.openUrl("https://t.me/Ryomen_Sukuna_009")
      }
      .setNeutralButton("GitHub Repo") { _, _ ->
        app.openUrl("https://github.com/ryomensukuna009/cursed-acs")
      }
      .setNegativeButton("Close", null)
      .show()
  }

  private fun showLicensesDialog() {
    val message = SpannableStringBuilder().apply {
      append("OPEN-SOURCE NOTICES & THIRD-PARTY LICENSES\n\n", android.text.style.StyleSpan(android.graphics.Typeface.BOLD), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
      append("CURSED ACS incorporates open-source libraries and utilities:\n\n")
      append("• AndroidIDE Core: GNU General Public License v3.0\n  Copyright (C) 2021-2024 itsaky\n\n")
      append("• Sora Editor: Apache License 2.0\n  Copyright (C) 2020-2024 Rosemoe\n\n")
      append("• Tree-Sitter: MIT License\n  Copyright (C) 2018 Max Brunsfeld\n\n")
      append("• Termux Runtime & Packages: GNU General Public License v3.0\n  Copyright (C) 2018-2024 Fredrik Fornwall & Termux Contributors\n\n")
      append("• Google Material Components & AndroidX: Apache License 2.0\n  Copyright (C) Google LLC\n\n")
      append("• OkHttp & Retrofit: Apache License 2.0\n  Copyright (C) 2020 Square, Inc.\n\n")
      append("• Eclipse JDT Core: Eclipse Public License 2.0\n  Copyright (C) Eclipse Foundation\n\n")
      append("• KaTeX Math Typesetting: MIT License\n  Copyright (C) Khan Academy & KaTeX contributors\n\n")
      append("Complete license texts and source files are available in the project repository.")
    }

    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
      .setTitle("Open-Source Licenses")
      .setIcon(R.drawable.ic_licenses)
      .setMessage(message)
      .setPositiveButton("Copy Notices") { _, _ ->
        ClipboardUtils.copyText(message.toString())
        flashSuccess(R.string.copied)
      }
      .setNegativeButton("Close", null)
      .show()
  }

  private fun showPrivacySecurityDialog() {
    val message = SpannableStringBuilder().apply {
      append("PRIVACY, LOCAL STORAGE & CREDENTIAL SECURITY\n\n", android.text.style.StyleSpan(android.graphics.Typeface.BOLD), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
      append("• Local-First Privacy: 100% of your source code, projects, Git repositories, and build artifacts remain strictly local on your device.\n\n")
      append("• Zero Analytics Telemetry: CURSED ACS does not embed advertising SDKs, tracking pixels, or diagnostic beacons. No user telemetry is collected.\n\n")
      append("• Hardware-Backed Secret Storage: AI provider API credentials and tokens are encrypted at rest using Android Keystore AES-256-GCM. Master keys never leave hardware security modules.\n\n")
      append("• Direct Encrypted AI Channels: All AI requests are transmitted directly over TLS 1.3 encrypted connections straight to your chosen model provider (Google, Anthropic, OpenAI, etc.). No intermediary proxy servers are involved.")
    }

    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
      .setTitle("Privacy & Security Policy")
      .setIcon(R.drawable.ic_info)
      .setMessage(message)
      .setPositiveButton("OK", null)
      .show()
  }

  private fun createSocialItems(): List<IconTitleDescriptionItem> {
    return mutableListOf<IconTitleDescriptionItem>().apply {
      add(
          createSimpleIconTextItem(
              this@AboutActivity,
              ACTION_WEBSITE,
              R.drawable.ic_website,
              R.string.about_option_website,
              BuildInfo.PROJECT_SITE,
          )
      )
      add(
          createSimpleIconTextItem(
              this@AboutActivity,
              ACTION_EMAIL,
              R.drawable.ic_email,
              R.string.about_option_email,
              BaseApplication.EMAIL,
          )
      )
      add(
          createSimpleIconTextItem(
              this@AboutActivity,
              ACTION_TG_GROUP,
              R.drawable.ic_telegram,
              R.string.discussions_on_telegram,
              BaseApplication.TELEGRAM_GROUP_URL,
          )
      )
      add(
          createSimpleIconTextItem(
              this@AboutActivity,
              ACTION_TG_CHANNEL,
              R.drawable.ic_telegram,
              R.string.official_tg_channel,
              BaseApplication.TELEGRAM_CHANNEL_URL,
          )
      )
    }
  }

  private fun createMiscItems(): List<IconTitleDescriptionItem> {
    return mutableListOf<IconTitleDescriptionItem>().apply {
      add(
          SimpleIconTitleDescriptionItem(
              ACTION_CHECK_UPDATES,
              ContextCompat.getDrawable(this@AboutActivity, R.drawable.ic_github),
              "Check for Updates",
              "Check GitHub releases for the latest CURSED ACS build",
          )
      )
      add(
          SimpleIconTitleDescriptionItem(
              ACTION_UPSTREAM_INFO,
              ContextCompat.getDrawable(this@AboutActivity, R.drawable.ic_github),
              "Upstream Attribution & Fork Info",
              "AndroidIDE (GPL-3.0-or-later) by itsaky. Read CURSED ACS customizations.",
          )
      )
      add(
          SimpleIconTitleDescriptionItem(
              ACTION_LICENSES,
              ContextCompat.getDrawable(this@AboutActivity, R.drawable.ic_licenses),
              "Open-Source Licenses & Notices",
              "GPL-3.0, Apache 2.0, MIT, and third-party library legal notices.",
          )
      )
      add(
          SimpleIconTitleDescriptionItem(
              ACTION_PRIVACY_SECURITY,
              ContextCompat.getDrawable(this@AboutActivity, R.drawable.ic_info),
              "Privacy, Local Data & Security",
              "100% on-device code execution. Android Keystore AES-256-GCM encryption.",
          )
      )
      add(
          SimpleIconTitleDescriptionItem.create(
              this@AboutActivity,
              ACTION_CONTRIBUTE,
              R.drawable.ic_code,
              R.string.title_contribute,
              R.string.summary_contribute,
          )
      )
      add(
          SimpleIconTitleDescriptionItem.create(
              this@AboutActivity,
              ACTION_CONTRIBUTORS,
              R.drawable.ic_heart_outline,
              R.string.title_contributors,
              R.string.summary_contributors,
          )
      )
    }
  }

  private fun createSimpleIconTextItem(
      context: Context,
      id: Int,
      @DrawableRes icon: Int,
      @StringRes title: Int,
      description: CharSequence,
  ): SimpleIconTitleDescriptionItem {
    return SimpleIconTitleDescriptionItem(
        id,
        ContextCompat.getDrawable(context, icon),
        ContextCompat.getString(context, title),
        description,
    )
  }

  /**
   * Create the version name string that should be displayed to the user.
   *
   * Format of the version name string is :
   *
   * `v[version-name]-[variant] ([build-type]/[[UN]OFFICIAL])`
   */
  @Suppress("KDocUnresolvedReference")
  private fun createVersionText(): CharSequence {
    val builder = SpannableStringBuilder()
    builder.append("v")
    builder.append(BuildInfo.VERSION_NAME_SIMPLE)
    builder.append("-")
    builder.append(IDEBuildConfigProvider.getInstance().cpuAbiName)
    builder.append(" ")

    val colorPositive = ContextCompat.getColor(this, R.color.color_success)
    val colorNegative = ContextCompat.getColor(this, R.color.color_error)

    appendBuildType(builder, colorPositive, colorNegative)

    return builder
  }

  private fun appendBuildType(
      builder: SpannableStringBuilder,
      @ColorInt colorPositive: Int,
      @ColorInt colorNegative: Int,
  ) {
    @Suppress("KotlinConstantConditions")
    var color =
        if (BuildConfig.BUILD_TYPE != "release") {
          colorNegative
        } else {
          colorPositive
        }

    builder.append("(")
    appendForegroundSpan(builder, BuildConfig.BUILD_TYPE, color)

    val isOfficialBuild = BuildInfoUtils.isOfficialBuild(this)

    color =
        if (isOfficialBuild) {
          colorPositive
        } else {
          colorNegative
        }

    builder.append("/")
    appendForegroundSpan(builder, BuildInfoUtils.getBuildType(this).lowercase(), color)

    builder.append(")")
  }

  private fun appendForegroundSpan(
      builder: SpannableStringBuilder,
      text: CharSequence,
      color: Int,
  ) {
    builder.append(
        text,
        ForegroundColorSpan(color),
        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
  }

  override fun onDestroy() {
    super.onDestroy()
    _binding = null
  }

  class AboutSocialItemsAdapter(
      items: List<IconTitleDescriptionItem>,
      private val onClickListener: (SimpleIconTitleDescriptionItem) -> Unit,
  ) : SimpleIconTitleDescriptionAdapter(items) {

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
      super.onBindViewHolder(holder, position)
      val binding = holder.binding
      val item = getItem(position) as SimpleIconTitleDescriptionItem
      val dp8 = SizeUtils.dp2px(8f)
      binding.icon.updatePaddingRelative(dp8, dp8, dp8, dp8)
      binding.title.setTextAppearance(R.style.TextAppearance_Material3_TitleSmall)

      binding.description.maxLines = 3
      binding.description.setTextAppearance(R.style.TextAppearance_Material3_BodySmall)
      binding.description.setTextColor(binding.description.context.resolveAttr(R.attr.colorPrimary))

      binding.root.isClickable = true
      binding.root.isFocusable = true
      binding.root.setBackgroundResource(R.drawable.bg_ripple)
      binding.root.setOnClickListener { onClickListener(item) }
    }
  }
}
