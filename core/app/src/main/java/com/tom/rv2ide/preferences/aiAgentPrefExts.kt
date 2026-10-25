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

package com.tom.rv2ide.preferences

import android.content.Context
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.dialogs.ProviderProfileDialogs
import com.tom.rv2ide.artificial.providers.ProviderRegistryManager
import com.tom.rv2ide.artificial.secrets.SecretRedactor
import com.tom.rv2ide.artificial.secrets.SecureKeyStore
import com.tom.rv2ide.artificial.tools.WebSearchManager
import com.tom.rv2ide.preferences.internal.prefManager
import com.tom.rv2ide.resources.R.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import android.widget.Toast
import com.tom.rv2ide.app.IDEApplication
import com.tom.rv2ide.artificial.policy.ActionPolicyGate

/** * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null */
@Parcelize
class AIAgentPreferencesScreen(
    override val key: String = "idepref_agent",
    override val title: Int = string.agent_tab_title,
    override val summary: Int? = string.agent_tab_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(AgentApprovalsGroup())
  }
}

@Parcelize
private class AIAgentConfig(
    override val key: String = "idepref_ai_agent_config",
    override val title: Int = string.ai_agent_title,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  @IgnoredOnParcel private var geminiApiKeyPref: GeminiApiKey? = null
  @IgnoredOnParcel private var deepseekApiKeyPref: DeepseekApiKey? = null
  @IgnoredOnParcel private var openAIApiKeyPref: OpenAIApiKey? = null
  @IgnoredOnParcel private var anthropicApiKeyPref: AnthropicApiKey? = null
  @IgnoredOnParcel private var grokApiKeyPref: GrokApiKey? = null
  @IgnoredOnParcel private var openRouterApiKeyPref: OpenRouterApiKey? = null
  @IgnoredOnParcel private var manageProfilesPref: ManageProviderProfilesPref? = null
  @IgnoredOnParcel private var selectModelPref: SelectActiveModelPref? = null
  @IgnoredOnParcel private var webSearchPref: ConfigureWebSearchPref? = null

  init {
    val aiAgentEnabled = AIAgentEnabled { isEnabled -> updateApiKeyPreferencesState(isEnabled) }

    manageProfilesPref = ManageProviderProfilesPref()
    selectModelPref = SelectActiveModelPref()
    webSearchPref = ConfigureWebSearchPref()
    geminiApiKeyPref = GeminiApiKey()
    deepseekApiKeyPref = DeepseekApiKey()
    openAIApiKeyPref = OpenAIApiKey()
    openRouterApiKeyPref = OpenRouterApiKey()
    anthropicApiKeyPref = AnthropicApiKey()
    grokApiKeyPref = GrokApiKey()

    addPreference(aiAgentEnabled)
    addPreference(manageProfilesPref!!)
    addPreference(selectModelPref!!)
    addPreference(webSearchPref!!)
    addPreference(geminiApiKeyPref!!)
    addPreference(openAIApiKeyPref!!)
    addPreference(openRouterApiKeyPref!!)
    addPreference(anthropicApiKeyPref!!)
    addPreference(deepseekApiKeyPref!!)
    addPreference(grokApiKeyPref!!)
  }

  private fun updateApiKeyPreferencesState(isEnabled: Boolean) {
    manageProfilesPref?.setEnabled(isEnabled)
    selectModelPref?.setEnabled(isEnabled)
    webSearchPref?.setEnabled(isEnabled)
    geminiApiKeyPref?.setEnabled(isEnabled)
    deepseekApiKeyPref?.setEnabled(isEnabled)
    openAIApiKeyPref?.setEnabled(isEnabled)
    openRouterApiKeyPref?.setEnabled(isEnabled)
    anthropicApiKeyPref?.setEnabled(isEnabled)
    grokApiKeyPref?.setEnabled(isEnabled)
  }
}

@Parcelize
private class AIAgentEnabled(
    override val key: String = "ai_agent_enabled",
    override val title: Int = R.string.ai_agent_enable,
    @IgnoredOnParcel private val onStateChanged: ((Boolean) -> Unit)? = null,
) :
    SwitchPreference(
        setValue = { isEnabled ->
          prefManager.putBoolean("ai_agent_enabled", isEnabled)
          onStateChanged?.invoke(isEnabled)
        },
        getValue = { prefManager.getBoolean("ai_agent_enabled", false) },
    ) {

  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).apply {
      key = "ai_agent_enabled"
      title = context.getString(R.string.ai_agent_enable)
      summary = context.getString(R.string.ai_agent_enable_summary)
    }
  }
}

@Parcelize
private class ManageProviderProfilesPref(
    override val key: String = "ai_agent_manage_profiles",
    override val title: Int = R.string.ai_agent_enable,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null
  @IgnoredOnParcel private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  override fun onCreatePreference(context: Context): Preference {
    preference = Preference(context).apply {
      key = "ai_agent_manage_profiles"
      title = "Manage AI Providers & Custom Endpoints"
      summary = buildSummary(context)
      isEnabled = true
    }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val registry = ProviderRegistryManager.getInstance(context)
    val profiles = registry.getAllProfiles()
    val items = profiles.map { p ->
      val hasKey = registry.hasProviderApiKey(p.id) || p.authStyle.name == "NONE"
      val status = if (hasKey) "✓ Ready" else "Key needed"
      "${p.displayName} [${p.protocol.name}] — $status"
    } + listOf("+ Add New Custom Provider Profile")

    MaterialAlertDialogBuilder(context)
      .setTitle("AI Provider Profiles")
      .setItems(items.toTypedArray()) { _, which ->
        if (which < profiles.size) {
          val selected = profiles[which]
          ProviderProfileDialogs.showEditProfileDialog(
            context = context,
            scope = uiScope,
            profile = selected,
            isNew = false,
            onProfileSaved = { updated ->
              Agents(context).setProvider(updated.id)
              preference.summary = buildSummary(context)
            },
            onProfileDeleted = {
              preference.summary = buildSummary(context)
            }
          )
        } else {
          ProviderProfileDialogs.showAddFromTemplateDialog(
            context = context,
            scope = uiScope
          ) { created ->
            Agents(context).setProvider(created.id)
            preference.summary = buildSummary(context)
          }
        }
      }
      .setNegativeButton("Close", null)
      .show()
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = true
  }

  private fun buildSummary(context: Context): String {
    val registry = ProviderRegistryManager.getInstance(context)
    val active = registry.getActiveProfile()
    val total = registry.getAllProfiles().size
    return "Active: ${active.displayName} (${active.protocol.name}) • $total profiles configured"
  }
}

@Parcelize
private class SelectActiveModelPref(
    override val key: String = "ai_agent_select_model_live",
    override val title: Int = R.string.ai_agent_enable,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null
  @IgnoredOnParcel private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  override fun onCreatePreference(context: Context): Preference {
    preference = Preference(context).apply {
      key = "ai_agent_select_model_live"
      title = "Live Model Discovery & Selection"
      summary = buildSummary(context)
      isEnabled = true
    }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val agents = Agents(context)
    val providerId = agents.getProvider()
    ProviderProfileDialogs.showSearchableModelPickerDialog(
      context = context,
      scope = uiScope,
      providerId = providerId
    ) { selectedModel ->
      agents.setAgent(selectedModel)
      preference.summary = buildSummary(context)
    }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = true
  }

  private fun buildSummary(context: Context): String {
    val agents = Agents(context)
    val registry = ProviderRegistryManager.getInstance(context)
    val provId = agents.getProvider()
    val cachedCount = registry.getCachedModels(provId).size
    return "Selected: ${agents.getAgent()} ($cachedCount live models cached)"
  }
}

@Parcelize
private class ConfigureWebSearchPref(
    override val key: String = "ai_agent_web_search_config",
    override val title: Int = R.string.ai_agent_enable,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null
  @IgnoredOnParcel private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  override fun onCreatePreference(context: Context): Preference {
    preference = Preference(context).apply {
      key = "ai_agent_web_search_config"
      title = "Web Search & URL Grounding Tools"
      summary = buildSummary(context)
      isEnabled = true
    }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    ProviderProfileDialogs.showWebSearchConfigDialog(
      context = context,
      scope = uiScope
    ) {
      preference.summary = buildSummary(context)
    }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = true
  }

  private fun buildSummary(context: Context): String {
    val mgr = WebSearchManager(context)
    val cfg = mgr.getConfig()
    val status = mgr.getSearchStatus(cfg)
    return if (cfg.enabled) {
      "${status.label} • ${cfg.providerType.displayName}"
    } else {
      status.label
    }
  }
}

private fun promptEncryptedKeyDialog(
    context: Context,
    providerId: String,
    dialogTitle: String,
    hintText: String,
    onSaved: () -> Unit
) {
  val editText = android.widget.EditText(context).apply {
    val existing = SecureKeyStore.getProviderApiKey(context, providerId).orEmpty()
    hint = if (existing.isNotBlank()) "Saved: ${SecretRedactor.maskKey(existing)}" else hintText
    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
  }

  MaterialAlertDialogBuilder(context)
      .setTitle(dialogTitle)
      .setMessage("$hintText (encrypted via Android Keystore)")
      .setView(editText)
      .setPositiveButton("Save") { _, _ ->
        val apiKey = editText.text.toString().trim()
        SecureKeyStore.putProviderApiKey(context, providerId, apiKey)
        onSaved()
      }
      .setNegativeButton("Cancel", null)
      .show()
}

private fun maskedKeySummary(context: Context, providerId: String): String {
  val key = SecureKeyStore.getProviderApiKey(context, providerId)
  return if (key.isNullOrBlank()) "Click to set API key" else "API Key: ${SecretRedactor.maskKey(key)}"
}

@Parcelize
private class OpenRouterApiKey(
    override val key: String = "ai_agent_openrouter_api_key",
    override val title: Int = R.string.ai_agent_openai_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference = Preference(context).apply {
      key = "ai_agent_openrouter_api_key"
      title = "OpenRouter API Key"
      summary = maskedKeySummary(context, "openrouter")
      isEnabled = prefManager.getBoolean("ai_agent_enabled", true)
    }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    promptEncryptedKeyDialog(context, "openrouter", "OpenRouter API Key", "Enter your OpenRouter API key") {
      preference.summary = maskedKeySummary(context, "openrouter")
    }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class GrokApiKey(
    override val key: String = "ai_agent_grok_api_key",
    override val title: Int = R.string.ai_agent_grok_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference = Preference(context).apply {
      key = "ai_agent_grok_api_key"
      title = context.getString(R.string.ai_agent_grok_api_key)
      summary = maskedKeySummary(context, "grok")
      isEnabled = prefManager.getBoolean("ai_agent_enabled", true)
    }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    promptEncryptedKeyDialog(context, "grok", "Grok API Key", "Enter your xAI Grok API key") {
      preference.summary = maskedKeySummary(context, "grok")
    }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class GeminiApiKey(
    override val key: String = "ai_agent_gemini_api_key",
    override val title: Int = R.string.ai_agent_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference = Preference(context).apply {
      key = "ai_agent_gemini_api_key"
      title = context.getString(R.string.ai_agent_api_key)
      summary = maskedKeySummary(context, "gemini")
      isEnabled = prefManager.getBoolean("ai_agent_enabled", true)
    }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    promptEncryptedKeyDialog(context, "gemini", "Gemini API Key", "Enter your Google Gemini API key") {
      preference.summary = maskedKeySummary(context, "gemini")
    }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class DeepseekApiKey(
    override val key: String = "ai_agent_deepseek_api_key",
    override val title: Int = R.string.ai_agent_deepseek_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference = Preference(context).apply {
      key = "ai_agent_deepseek_api_key"
      title = context.getString(R.string.ai_agent_deepseek_api_key)
      summary = maskedKeySummary(context, "deepseek")
      isEnabled = prefManager.getBoolean("ai_agent_enabled", true)
    }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    promptEncryptedKeyDialog(context, "deepseek", "DeepSeek API Key", "Enter your DeepSeek API key") {
      preference.summary = maskedKeySummary(context, "deepseek")
    }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class OpenAIApiKey(
    override val key: String = "ai_agent_openai_api_key",
    override val title: Int = R.string.ai_agent_openai_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference = Preference(context).apply {
      key = "ai_agent_openai_api_key"
      title = context.getString(R.string.ai_agent_openai_api_key)
      summary = maskedKeySummary(context, "openai")
      isEnabled = prefManager.getBoolean("ai_agent_enabled", true)
    }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    promptEncryptedKeyDialog(context, "openai", "OpenAI API Key", "Enter your OpenAI API key") {
      preference.summary = maskedKeySummary(context, "openai")
    }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
private class AnthropicApiKey(
    override val key: String = "ai_agent_anthropic_api_key",
    override val title: Int = R.string.ai_agent_anthropic_api_key,
) : BasePreference() {

  @IgnoredOnParcel private var preference: Preference? = null

  override fun onCreatePreference(context: Context): Preference {
    preference = Preference(context).apply {
      key = "ai_agent_anthropic_api_key"
      title = context.getString(R.string.ai_agent_anthropic_api_key)
      summary = maskedKeySummary(context, "claude")
      isEnabled = prefManager.getBoolean("ai_agent_enabled", true)
    }
    return preference!!
  }

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    promptEncryptedKeyDialog(context, "claude", "Anthropic API Key", "Enter your Anthropic API key") {
      preference.summary = maskedKeySummary(context, "claude")
    }
    return true
  }

  fun setEnabled(enabled: Boolean) {
    preference?.isEnabled = enabled
  }
}

@Parcelize
class AgentApprovalsGroup(
    override val key: String = "idepref_agent_approvals_group",
    override val title: Int = string.agent_approvals_title,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  init {
    addPreference(AgentFileModificationsPref())
    addPreference(AgentTerminalCommandsPref())
    addPreference(AgentProjectBuildPref())
    addPreference(ResetAllAgentApprovalsPref())
  }
}

@Parcelize
class AgentFileModificationsPref(
    override val key: String = "idepref_agent_file_perm",
    override val title: Int = string.pref_agent_file_perm_title,
) : SwitchPreference(
    setValue = { isChecked ->
      val context = IDEApplication.instance
      if (isChecked) {
        ActionPolicyGate.setFilePermission(context, ActionPolicyGate.PERM_ALWAYS_APPROVE)
      } else {
        ActionPolicyGate.setFilePermission(context, ActionPolicyGate.PERM_ASK_EVERY_TIME)
      }
    },
    getValue = {
      val context = IDEApplication.instance
      ActionPolicyGate.getFilePermission(context) == ActionPolicyGate.PERM_ALWAYS_APPROVE
    }
) {
  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).apply {
      key = "idepref_agent_file_perm"
      title = context.getString(string.pref_agent_file_perm_title)
      val isAlways = ActionPolicyGate.getFilePermission(context) == ActionPolicyGate.PERM_ALWAYS_APPROVE
      summary = context.getString(if (isAlways) string.pref_agent_file_perm_always else string.pref_agent_file_perm_ask)
      setOnPreferenceChangeListener { pref, newValue ->
        val checked = newValue as? Boolean ?: false
        if (checked) {
          ActionPolicyGate.setFilePermission(context, ActionPolicyGate.PERM_ALWAYS_APPROVE)
        } else {
          ActionPolicyGate.setFilePermission(context, ActionPolicyGate.PERM_ASK_EVERY_TIME)
        }
        pref.summary = context.getString(if (checked) string.pref_agent_file_perm_always else string.pref_agent_file_perm_ask)
        true
      }
    }
  }
}

@Parcelize
class AgentTerminalCommandsPref(
    override val key: String = "idepref_agent_term_perm",
    override val title: Int = string.pref_agent_term_perm_title,
) : SwitchPreference(
    setValue = { isChecked ->
      val context = IDEApplication.instance
      if (isChecked) {
        ActionPolicyGate.setTerminalPermission(context, ActionPolicyGate.PERM_ALWAYS_APPROVE)
      } else {
        ActionPolicyGate.setTerminalPermission(context, ActionPolicyGate.PERM_ASK_EVERY_TIME)
      }
    },
    getValue = {
      val context = IDEApplication.instance
      ActionPolicyGate.getTerminalPermission(context) == ActionPolicyGate.PERM_ALWAYS_APPROVE
    }
) {
  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).apply {
      key = "idepref_agent_term_perm"
      title = context.getString(string.pref_agent_term_perm_title)
      val isAlways = ActionPolicyGate.getTerminalPermission(context) == ActionPolicyGate.PERM_ALWAYS_APPROVE
      summary = context.getString(if (isAlways) string.pref_agent_term_perm_always else string.pref_agent_term_perm_ask)
      setOnPreferenceChangeListener { pref, newValue ->
        val checked = newValue as? Boolean ?: false
        if (checked) {
          ActionPolicyGate.setTerminalPermission(context, ActionPolicyGate.PERM_ALWAYS_APPROVE)
        } else {
          ActionPolicyGate.setTerminalPermission(context, ActionPolicyGate.PERM_ASK_EVERY_TIME)
        }
        pref.summary = context.getString(if (checked) string.pref_agent_term_perm_always else string.pref_agent_term_perm_ask)
        true
      }
    }
  }
}

@Parcelize
class AgentProjectBuildPref(
    override val key: String = "idepref_agent_build_perm",
    override val title: Int = string.pref_agent_build_perm_title,
) : SwitchPreference(
    setValue = { isChecked ->
      val context = IDEApplication.instance
      if (isChecked) {
        ActionPolicyGate.setBuildPermission(context, ActionPolicyGate.PERM_ALWAYS_APPROVE)
      } else {
        ActionPolicyGate.setBuildPermission(context, ActionPolicyGate.PERM_ASK_EVERY_TIME)
      }
    },
    getValue = {
      val context = IDEApplication.instance
      ActionPolicyGate.getBuildPermission(context) == ActionPolicyGate.PERM_ALWAYS_APPROVE
    }
) {
  override fun onCreatePreference(context: Context): Preference {
    return super.onCreatePreference(context).apply {
      key = "idepref_agent_build_perm"
      title = context.getString(string.pref_agent_build_perm_title)
      val isAlways = ActionPolicyGate.getBuildPermission(context) == ActionPolicyGate.PERM_ALWAYS_APPROVE
      summary = context.getString(if (isAlways) string.pref_agent_build_perm_always else string.pref_agent_build_perm_ask)
      setOnPreferenceChangeListener { pref, newValue ->
        val checked = newValue as? Boolean ?: false
        if (checked) {
          ActionPolicyGate.setBuildPermission(context, ActionPolicyGate.PERM_ALWAYS_APPROVE)
        } else {
          ActionPolicyGate.setBuildPermission(context, ActionPolicyGate.PERM_ASK_EVERY_TIME)
        }
        pref.summary = context.getString(if (checked) string.pref_agent_build_perm_always else string.pref_agent_build_perm_ask)
        true
      }
    }
  }
}

@Parcelize
class ResetAllAgentApprovalsPref(
    override val key: String = "idepref_agent_reset_all_approvals",
    override val title: Int = string.pref_agent_reset_all_title,
) : BasePreference() {

  override fun onCreatePreference(context: Context): Preference {
    return Preference(context).apply {
      key = "idepref_agent_reset_all_approvals"
      title = context.getString(string.pref_agent_reset_all_title)
      summary = context.getString(string.pref_agent_reset_all_summary)
      setOnPreferenceClickListener {
        ActionPolicyGate.resetAllApprovalsToAskEveryTime(context)
        Toast.makeText(
          context,
          context.getString(string.pref_agent_reset_all_done),
          Toast.LENGTH_SHORT
        ).show()
        true
      }
    }
  }
}
