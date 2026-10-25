package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.exceptions.InsecureEndpointException
import com.tom.rv2ide.artificial.providers.ApiProtocol
import com.tom.rv2ide.artificial.providers.UrlNormalizer
import com.tom.rv2ide.artificial.secrets.EndpointSecurityValidator
import com.tom.rv2ide.artificial.secrets.JvmAesGcmSecretBackend
import com.tom.rv2ide.artificial.secrets.SecretRedactor
import com.tom.rv2ide.artificial.secrets.SecureKeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Phase6SecurityAndUrlTest {

    private lateinit var testSecretBackend: JvmAesGcmSecretBackend

    @Before
    fun setUp() {
        testSecretBackend = JvmAesGcmSecretBackend()
        SecureKeyStore.setBackendForTesting(testSecretBackend)
    }

    @After
    fun tearDown() {
        SecureKeyStore.setBackendForTesting(null)
    }

    @Test
    fun testUrlNormalizationAvoidsDuplicateV1Segments() {
        assertEquals(
            "https://api.openai.com/v1/models",
            UrlNormalizer.resolveModelsEndpoint("https://api.openai.com/v1/", "/v1/models", ApiProtocol.OPENAI_COMPATIBLE)
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            UrlNormalizer.resolveChatEndpoint(
                baseUrl = "https://api.openai.com/v1",
                chatPathOverride = "/v1/chat/completions",
                protocol = ApiProtocol.OPENAI_COMPATIBLE,
                modelId = "gpt-4o",
                stream = true
            )
        )
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            UrlNormalizer.resolveChatEndpoint(
                baseUrl = "https://openrouter.ai/api/v1/",
                chatPathOverride = "/api/v1/chat/completions",
                protocol = ApiProtocol.OPENROUTER,
                modelId = "openai/gpt-4o",
                stream = false
            )
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/models",
            UrlNormalizer.resolveModelsEndpoint(
                "https://generativelanguage.googleapis.com/v1beta/openai/",
                "/models",
                ApiProtocol.GEMINI_OPENAI
            )
        )
    }

    @Test
    fun testProtocolSpecificEndpointResolution() {
        val geminiNonStream = UrlNormalizer.resolveChatEndpoint(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            chatPathOverride = "",
            protocol = ApiProtocol.GEMINI_NATIVE,
            modelId = "models/gemini-2.5-pro",
            stream = false
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent",
            geminiNonStream
        )

        val geminiStream = UrlNormalizer.resolveChatEndpoint(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
            chatPathOverride = "",
            protocol = ApiProtocol.GEMINI_NATIVE,
            modelId = "gemini-2.5-flash",
            stream = true
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            geminiStream
        )

        val anthropicUrl = UrlNormalizer.resolveChatEndpoint(
            baseUrl = "https://api.anthropic.com",
            chatPathOverride = "",
            protocol = ApiProtocol.ANTHROPIC_MESSAGES,
            modelId = "claude-3-7-sonnet-20250219",
            stream = true
        )
        assertEquals("https://api.anthropic.com/v1/messages", anthropicUrl)

        val ollamaChat = UrlNormalizer.resolveChatEndpoint(
            baseUrl = "http://192.168.1.50:11434/",
            chatPathOverride = "",
            protocol = ApiProtocol.OLLAMA_NATIVE,
            modelId = "llama3.2",
            stream = true
        )
        assertEquals("http://192.168.1.50:11434/api/chat", ollamaChat)
    }

    @Test(expected = InsecureEndpointException::class)
    fun testCleartextHttpRejectedWhenNotAllowed() {
        EndpointSecurityValidator.validateEndpointUrl(
            urlString = "http://api.example.com/v1/chat/completions",
            allowCleartextHttp = false
        )
    }

    @Test
    fun testCleartextHttpPermittedWhenExplicitlyAllowed() {
        EndpointSecurityValidator.validateEndpointUrl(
            urlString = "http://192.168.1.25:11434/api/tags",
            allowCleartextHttp = true
        )
    }

    @Test
    fun testRedirectCredentialStrippingPolicy() {
        // Same host & scheme -> keep credentials
        assertFalse(
            EndpointSecurityValidator.shouldStripCredentialsOnRedirect(
                "https://api.openai.com/v1/models",
                "https://api.openai.com/v2/models"
            )
        )
        // Cross host -> strip credentials
        assertTrue(
            EndpointSecurityValidator.shouldStripCredentialsOnRedirect(
                "https://api.openai.com/v1/models",
                "https://evil.example.com/collect"
            )
        )
        // Downgrade HTTPS to HTTP -> strip credentials
        assertTrue(
            EndpointSecurityValidator.shouldStripCredentialsOnRedirect(
                "https://api.openai.com/v1/models",
                "http://api.openai.com/v1/models"
            )
        )
    }

    @Test
    fun testSecretRedactorMasksAndRedactsAllSecretPatterns() {
        val rawKey = "sk-proj-1234567890abcdefghijklmnop"
        val masked = SecretRedactor.maskKey(rawKey)
        assertFalse(masked.contains("1234567890abcdefghijklmnop"))
        assertTrue(masked.startsWith("sk-p"))

        val logLine = """
            Failed request to https://generativelanguage.googleapis.com/v1beta/models?key=AIzaSySecretKey99999999999999
            Authorization: Bearer $rawKey
            x-api-key: anthropic_secret_value_abc123
            X-Subscription-Token: BSA_brave_secret_token_987654
        """.trimIndent()

        val redacted = SecretRedactor.redact(logLine, listOf(rawKey, "anthropic_secret_value_abc123"))
        assertFalse(redacted.contains(rawKey))
        assertFalse(redacted.contains("AIzaSySecretKey99999999999999"))
        assertFalse(redacted.contains("anthropic_secret_value_abc123"))
        assertFalse(redacted.contains("BSA_brave_secret_token_987654"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun testSecureKeyStoreEncryptsAndSeparatesProviderAndSearchKeys() {
        SecureKeyStore.putProviderApiKey(null, "openai", "sk-openai-live-secret-1111")
        SecureKeyStore.putProviderApiKey(null, "openrouter", "sk-or-v1-live-secret-2222")
        SecureKeyStore.putSearchApiKey(null, "tavily", "tvly-search-secret-3333")

        assertEquals("sk-openai-live-secret-1111", SecureKeyStore.getProviderApiKey(null, "openai"))
        assertEquals("sk-or-v1-live-secret-2222", SecureKeyStore.getProviderApiKey(null, "openrouter"))
        assertEquals("tvly-search-secret-3333", SecureKeyStore.getSearchApiKey(null, "tavily"))

        // Verify provider key and search key namespaces do not collide
        assertNull(SecureKeyStore.getProviderApiKey(null, "tavily"))
        assertNull(SecureKeyStore.getSearchApiKey(null, "openai"))

        SecureKeyStore.removeProviderApiKey(null, "openai")
        assertFalse(SecureKeyStore.hasProviderApiKey(null, "openai"))
        assertNotNull(SecureKeyStore.getSearchApiKey(null, "tavily"))
    }
}
