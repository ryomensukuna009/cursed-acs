/*
 *  This file is part of AndroidCodeStudio (ROXUM-ACS).
 *  Manages dynamic brand logo variants and preferences.
 */

package com.tom.rv2ide.utils

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.DrawableRes
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.databinding.DialogSelectAppLogoBinding
import com.tom.rv2ide.resources.R

enum class AppLogoVariant(
    val id: String,
    val title: String,
    val subtitle: String,
    @DrawableRes val drawableRes: Int,
    val aliasSuffix: String
) {
    CYBER_COMPASS(
        "cyber_compass",
        "Cyber Compass",
        "Minimalist Cyan & Crisp White",
        R.drawable.logo_cyan_cyber,
        ".activities.SplashActivityCyberCompass"
    ),
    DUAL_AURA(
        "dual_aura",
        "Dual Aura (Sukuna Split)",
        "Twin Cyan & Cursed Crimson Flames",
        R.drawable.logo_dual_aura,
        ".activities.SplashActivityDualAura"
    ),
    CRIMSON_DOMAIN(
        "crimson_domain",
        "Crimson Domain",
        "Fiery Red Demonic Shrine Aperture",
        R.drawable.logo_crimson_domain,
        ".activities.SplashActivityCrimsonDomain"
    ),
    SUKUNA_KING(
        "sukuna_king",
        "Sukuna King",
        "King of Curses & Android Code",
        R.drawable.logo_sukuna_king,
        ".activities.SplashActivitySukunaKing"
    );

    companion object {
        const val PREF_KEY = "app_logo_variant"
        val DEFAULT = DUAL_AURA

        fun fromId(id: String?): AppLogoVariant {
            return entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}

object AppLogoManager {

    fun getActiveLogo(): AppLogoVariant {
        return try {
            val prefManager = BaseApplication.getBaseInstance().prefManager
            val id = prefManager.getString(AppLogoVariant.PREF_KEY, AppLogoVariant.DEFAULT.id)
            AppLogoVariant.fromId(id)
        } catch (_: Throwable) {
            AppLogoVariant.DEFAULT
        }
    }

    fun setActiveLogo(variant: AppLogoVariant) {
        try {
            val prefManager = BaseApplication.getBaseInstance().prefManager
            prefManager.putString(AppLogoVariant.PREF_KEY, variant.id)
        } catch (_: Throwable) {}
    }

    fun bindLogo(imageView: ImageView) {
        imageView.clearColorFilter()
        imageView.colorFilter = null
        imageView.imageTintList = null
        val resId = getActiveLogo().drawableRes
        val drawable = androidx.core.content.ContextCompat.getDrawable(imageView.context, resId)?.mutate()
        drawable?.clearColorFilter()
        if (drawable != null) {
            imageView.setImageDrawable(drawable)
        } else {
            imageView.setImageResource(resId)
        }
    }

    fun showLogoSelectionDialog(context: Context, onApplied: ((AppLogoVariant) -> Unit)? = null) {
        val binding = DialogSelectAppLogoBinding.inflate(LayoutInflater.from(context))
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .create()

        var selectedVariant = getActiveLogo()

        val cards = listOf(
            Triple(binding.cardLogoCyberCompass, binding.radioCyberCompass, AppLogoVariant.CYBER_COMPASS),
            Triple(binding.cardLogoDualAura, binding.radioDualAura, AppLogoVariant.DUAL_AURA),
            Triple(binding.cardLogoCrimsonDomain, binding.radioCrimsonDomain, AppLogoVariant.CRIMSON_DOMAIN),
            Triple(binding.cardLogoSukunaKing, binding.radioSukunaKing, AppLogoVariant.SUKUNA_KING)
        )

        fun updateSelection(variant: AppLogoVariant) {
            selectedVariant = variant
            val primaryColor = resolveColorAttr(context, android.R.attr.colorPrimary)
            val outlineColor = resolveColorAttr(context, com.google.android.material.R.attr.colorOutlineVariant)

            cards.forEach { (card, radio, itemVariant) ->
                val isSelected = itemVariant == variant
                radio.isChecked = isSelected
                card.strokeWidth = if (isSelected) dpToPx(context, 2) else dpToPx(context, 1)
                card.strokeColor = if (isSelected) primaryColor else outlineColor
            }
        }

        updateSelection(selectedVariant)

        cards.forEach { (card, _, variant) ->
            card.setOnClickListener { updateSelection(variant) }
        }

        binding.cancelLogoBtn.setOnClickListener {
            dialog.dismiss()
        }

        binding.applyLogoBtn.setOnClickListener {
            setActiveLogo(selectedVariant)
            applyLauncherIcon(context, selectedVariant)
            Toast.makeText(context, "Applied icon: ${selectedVariant.title}", Toast.LENGTH_SHORT).show()
            onApplied?.invoke(selectedVariant)
            dialog.dismiss()
        }

        dialog.show()
    }

    fun applyLauncherIcon(context: Context, variant: AppLogoVariant) {
        try {
            val pm = context.packageManager
            val packageName = context.packageName

            for (v in AppLogoVariant.entries) {
                val component = android.content.ComponentName(packageName, "$packageName${v.aliasSuffix}")
                val newState = if (v == variant) {
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                pm.setComponentEnabledSetting(
                    component,
                    newState,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun resolveColorAttr(context: Context, attr: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
