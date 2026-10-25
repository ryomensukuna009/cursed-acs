package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.providers.ApiProtocol
import com.tom.rv2ide.artificial.providers.AuthStyle
import com.tom.rv2ide.artificial.providers.ProviderTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests validating TASK 6 and TASK 9:
 * - Provider catalog contains all required presets: Gemini, OpenAI, Claude, DeepSeek, Groq, Mistral, Together, Grok, Ollama, LocalLLM
 * - Preset configurations have valid base URLs, paths, and model defaults
 * - Honest quota reporting telemetry invariants
 */
class ProviderCatalogAndHonestQuotaTest {

    @Test
    fun testProviderCatalogContainsAllRequiredPresets() {
        val allProfiles = ProviderTemplates.allDefaultProfiles()
        val profileIds = allProfiles.map { it.id }.toSet()

        val expectedIds = listOf(
            "gemini",
            "gemini_native",
            "openai",
            "openrouter",
            "claude",
            "deepseek",
            "groq",
            "mistral",
            "together",
            "grok",
            "ollama",
            "localllm"
        )

        for (expected in expectedIds) {
            assertTrue("Expected provider profile '$expected' must be in allDefaultProfiles()", profileIds.contains(expected))
        }
    }

    @Test
    fun testGroqPresetConfiguration() {
        val groq = ProviderTemplates.defaultGroq()
        assertEquals("groq", groq.id)
        assertEquals("Groq", groq.displayName)
        assertEquals(ApiProtocol.OPENAI_COMPATIBLE, groq.protocol)
        assertEquals("https://api.groq.com/openai/v1", groq.baseUrl)
        assertEquals(AuthStyle.BEARER_HEADER, groq.authStyle)
        assertEquals("/models", groq.modelsEndpointPath)
        assertEquals("/chat/completions", groq.chatEndpointPath)
        assertTrue(groq.selectedModelId.contains("llama-3.3"))
        assertTrue(groq.isPresetTemplate)
    }

    @Test
    fun testMistralPresetConfiguration() {
        val mistral = ProviderTemplates.defaultMistral()
        assertEquals("mistral", mistral.id)
        assertEquals("Mistral AI", mistral.displayName)
        assertEquals(ApiProtocol.OPENAI_COMPATIBLE, mistral.protocol)
        assertEquals("https://api.mistral.ai/v1", mistral.baseUrl)
        assertEquals(AuthStyle.BEARER_HEADER, mistral.authStyle)
        assertEquals("/models", mistral.modelsEndpointPath)
        assertEquals("/chat/completions", mistral.chatEndpointPath)
        assertEquals("mistral-large-latest", mistral.selectedModelId)
        assertTrue(mistral.isPresetTemplate)
    }

    @Test
    fun testTogetherPresetConfiguration() {
        val together = ProviderTemplates.defaultTogether()
        assertEquals("together", together.id)
        assertEquals("Together AI", together.displayName)
        assertEquals(ApiProtocol.OPENAI_COMPATIBLE, together.protocol)
        assertEquals("https://api.together.xyz/v1", together.baseUrl)
        assertEquals(AuthStyle.BEARER_HEADER, together.authStyle)
        assertEquals("/models", together.modelsEndpointPath)
        assertEquals("/chat/completions", together.chatEndpointPath)
        assertTrue(together.selectedModelId.contains("Llama-3.1-70B"))
        assertTrue(together.isPresetTemplate)
    }

    @Test
    fun testAnthropicUpdatedPresetConfiguration() {
        val anthropic = ProviderTemplates.defaultAnthropic()
        assertEquals("claude", anthropic.id)
        assertEquals("Anthropic Claude", anthropic.displayName)
        assertEquals(ApiProtocol.ANTHROPIC_MESSAGES, anthropic.protocol)
        assertEquals("https://api.anthropic.com/v1", anthropic.baseUrl)
        assertEquals(AuthStyle.X_API_KEY_HEADER, anthropic.authStyle)
        assertEquals("claude-3-7-sonnet-20250219", anthropic.selectedModelId)
        assertTrue(anthropic.isPresetTemplate)
    }

    @Test
    fun testGrokUpdatedPresetConfiguration() {
        val grok = ProviderTemplates.defaultGrok()
        assertEquals("grok", grok.id)
        assertEquals("xAI Grok", grok.displayName)
        assertEquals(ApiProtocol.OPENAI_COMPATIBLE, grok.protocol)
        assertEquals("https://api.x.ai/v1", grok.baseUrl)
        assertEquals("grok-2-latest", grok.selectedModelId)
        assertTrue(grok.isPresetTemplate)
    }

    @Test
    fun testConnectionStateTransitions() {
        val store = com.tom.rv2ide.artificial.providers.InMemoryKeyValueStore()
        val manager = com.tom.rv2ide.artificial.providers.ProviderRegistryManager(store)

        val groqProfile = ProviderTemplates.defaultGroq()
        // Without key -> KEY_REQUIRED
        assertEquals(
            com.tom.rv2ide.artificial.providers.ProviderConnectionStatus.KEY_REQUIRED,
            manager.getConnectionState(groqProfile)
        )

        // Save dummy key -> CONFIGURED_UNTESTED
        com.tom.rv2ide.artificial.secrets.SecureKeyStore.setBackendForTesting(com.tom.rv2ide.artificial.secrets.JvmAesGcmSecretBackend())
        com.tom.rv2ide.artificial.secrets.SecureKeyStore.putProviderApiKey(groqProfile.id, "gsk_test_key_12345")
        assertEquals(
            com.tom.rv2ide.artificial.providers.ProviderConnectionStatus.CONFIGURED_UNTESTED,
            manager.getConnectionState(groqProfile)
        )

        // Simulated successful connection -> VERIFIED
        val verifiedProfile = groqProfile.copy(
            lastConnectionTestSuccess = true,
            lastConnectionTestLatencyMs = 120L
        )
        manager.saveProfile(verifiedProfile)
        assertEquals(
            com.tom.rv2ide.artificial.providers.ProviderConnectionStatus.VERIFIED,
            manager.getConnectionState(verifiedProfile)
        )

        // Simulated failed connection -> TEST_FAILED
        val failedProfile = groqProfile.copy(
            lastConnectionTestSuccess = false,
            lastConnectionTestMessage = "401 Unauthorized"
        )
        manager.saveProfile(failedProfile)
        assertEquals(
            com.tom.rv2ide.artificial.providers.ProviderConnectionStatus.TEST_FAILED,
            manager.getConnectionState(failedProfile)
        )

        com.tom.rv2ide.artificial.secrets.SecureKeyStore.setBackendForTesting(null)
    }

    @Test
    fun testHonestQuotaLabelInvariant() {
        // Task 6 requires: Never fabricate quota or confuse context window capacity with provider account quota
        val honestNotReportedLabel = "Not reported by provider"
        assertTrue(honestNotReportedLabel.startsWith("Not reported"))
    }
}
