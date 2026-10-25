package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.discovery.ModelDiscoveryService
import com.tom.rv2ide.artificial.providers.ApiProtocol
import com.tom.rv2ide.artificial.providers.AuthStyle
import com.tom.rv2ide.artificial.providers.DiscoveryStatus
import com.tom.rv2ide.artificial.providers.ProviderProfile
import com.tom.rv2ide.artificial.providers.ProviderRegistryManager
import com.tom.rv2ide.artificial.providers.ProviderTemplates
import com.tom.rv2ide.artificial.providers.TriStateCapability
import com.tom.rv2ide.artificial.secrets.JvmAesGcmSecretBackend
import com.tom.rv2ide.artificial.secrets.SecureKeyStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Phase6DiscoveryAndRegistryTest {

    private val recordedRequests = mutableListOf<Request>()

    @Before
    fun setUp() {
        recordedRequests.clear()
        SecureKeyStore.setBackendForTesting(JvmAesGcmSecretBackend())
    }

    @After
    fun tearDown() {
        SecureKeyStore.setBackendForTesting(null)
    }

    private fun buildMockClient(handler: (Request) -> Pair<Int, String>): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                recordedRequests.add(req)
                val (code, body) = handler(req)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code in 200..299) "OK" else "Error")
                    .header("Content-Type", "application/json")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }

    @Test
    fun testOpenAIDiscoveryDoesNotInferCapabilitiesFromModelName() = runBlocking {
        val json = """
            {
              "object": "list",
              "data": [
                {"id": "gpt-4o-vision-tools", "object": "model", "owned_by": "openai"},
                {"id": "o3-mini", "object": "model", "owned_by": "openai"}
              ]
            }
        """.trimIndent()

        val client = buildMockClient { req ->
            assertEquals("Bearer sk-test-openai-key", req.header("Authorization"))
            assertEquals("https://api.openai.com/v1/models", req.url.toString())
            200 to json
        }

        val service = ModelDiscoveryService(client)
        val profile = ProviderTemplates.openAiPreset()
        val result = service.discoverModels(profile, "sk-test-openai-key")

        assertEquals(DiscoveryStatus.SUCCESS, result.status)
        assertEquals(2, result.models.size)

        val gpt4o = result.models.first { it.id == "gpt-4o-vision-tools" }
        // Because OpenAI /v1/models only returned id & owned_by, capabilities must remain UNKNOWN / null!
        assertNull(gpt4o.capabilities.contextLength)
        assertEquals(TriStateCapability.UNKNOWN, gpt4o.capabilities.supportsTools)
        assertEquals(TriStateCapability.UNKNOWN, gpt4o.capabilities.supportsVision)
    }

    @Test
    fun testOpenRouterDiscoveryExtractsRichMetadata() = runBlocking {
        val json = """
            {
              "data": [
                {
                  "id": "anthropic/claude-3.7-sonnet",
                  "name": "Anthropic: Claude 3.7 Sonnet",
                  "description": "High-capability coding model",
                  "context_length": 200000,
                  "top_provider": {"max_completion_tokens": 64000},
                  "architecture": {
                    "input_modalities": ["text", "image"],
                    "output_modalities": ["text"]
                  },
                  "supported_parameters": ["temperature", "tools", "tool_choice"],
                  "pricing": {"prompt": "0.000003", "completion": "0.000015"}
                },
                {
                  "id": "deepseek/deepseek-r1-distill",
                  "name": "DeepSeek R1 Distill",
                  "context_length": 64000,
                  "architecture": {
                    "input_modalities": ["text"]
                  },
                  "supported_parameters": ["temperature", "max_tokens"]
                }
              ]
            }
        """.trimIndent()

        val client = buildMockClient { 200 to json }
        val service = ModelDiscoveryService(client)
        val result = service.discoverModels(ProviderTemplates.openRouterPreset(), "sk-or-test")

        assertEquals(DiscoveryStatus.SUCCESS, result.status)
        assertEquals(2, result.models.size)

        val claude = result.models.first { it.id == "anthropic/claude-3.7-sonnet" }
        assertEquals(200000, claude.capabilities.contextLength)
        assertEquals(64000, claude.capabilities.maxOutputTokens)
        assertEquals(TriStateCapability.SUPPORTED, claude.capabilities.supportsTools)
        assertEquals(TriStateCapability.SUPPORTED, claude.capabilities.supportsVision)
        assertEquals("0.000003", claude.pricingPrompt)

        val deepseek = result.models.first { it.id == "deepseek/deepseek-r1-distill" }
        assertEquals(64000, deepseek.capabilities.contextLength)
        assertEquals(TriStateCapability.UNSUPPORTED, deepseek.capabilities.supportsTools)
        assertEquals(TriStateCapability.UNSUPPORTED, deepseek.capabilities.supportsVision)
    }

    @Test
    fun testGeminiNativeDiscoveryAndPagination() = runBlocking {
        val page1 = """
            {
              "models": [
                {
                  "name": "models/gemini-2.5-pro",
                  "displayName": "Gemini 2.5 Pro",
                  "inputTokenLimit": 1048576,
                  "outputTokenLimit": 65536,
                  "supportedGenerationMethods": ["generateContent", "countTokens"]
                },
                {
                  "name": "models/text-embedding-004",
                  "displayName": "Text Embedding 004",
                  "supportedGenerationMethods": ["embedContent"]
                }
              ],
              "nextPageToken": "page_2_token"
            }
        """.trimIndent()

        val page2 = """
            {
              "models": [
                {
                  "name": "models/gemini-2.5-flash",
                  "displayName": "Gemini 2.5 Flash",
                  "inputTokenLimit": 1048576,
                  "outputTokenLimit": 8192,
                  "supportedGenerationMethods": ["generateContent"]
                }
              ]
            }
        """.trimIndent()

        val client = buildMockClient { req ->
            assertEquals("AIzaTestKey123", req.header("x-goog-api-key"))
            if (req.url.queryParameter("pageToken") == "page_2_token") {
                200 to page2
            } else {
                200 to page1
            }
        }

        val service = ModelDiscoveryService(client)
        val result = service.discoverModels(ProviderTemplates.geminiNativePreset(), "AIzaTestKey123")

        assertEquals(DiscoveryStatus.SUCCESS, result.status)
        // Embedding-only model is filtered out; both pages of generateContent models are included
        assertEquals(2, result.models.size)
        assertTrue(result.models.any { it.id == "gemini-2.5-pro" && it.capabilities.contextLength == 1048576 })
        assertTrue(result.models.any { it.id == "gemini-2.5-flash" && it.capabilities.maxOutputTokens == 8192 })
    }

    @Test
    fun testOllamaNativeAndCustomGenericDiscovery() = runBlocking {
        val ollamaTagsJson = """
            {
              "models": [
                {"name": "qwen2.5-coder:7b", "model": "qwen2.5-coder:7b"},
                {"name": "llama3.2:latest", "model": "llama3.2:latest"}
              ]
            }
        """.trimIndent()

        val client = buildMockClient { req ->
            when {
                req.url.encodedPath.endsWith("/api/tags") -> 200 to ollamaTagsJson
                else -> 200 to """["custom-alpha", "custom-beta"]"""
            }
        }

        val service = ModelDiscoveryService(client)
        val ollamaRes = service.discoverModels(ProviderTemplates.ollamaPreset(), null)
        assertEquals(DiscoveryStatus.SUCCESS, ollamaRes.status)
        assertEquals(listOf("llama3.2:latest", "qwen2.5-coder:7b"), ollamaRes.models.map { it.id })

        val customProfile = ProviderProfile(
            id = "custom_1",
            displayName = "Custom Server",
            protocol = ApiProtocol.CUSTOM_GENERIC,
            baseUrl = "https://llm.example.com",
            authStyle = AuthStyle.NONE
        )
        val customRes = service.discoverModels(customProfile, null)
        assertEquals(DiscoveryStatus.SUCCESS, customRes.status)
        assertEquals(listOf("custom-alpha", "custom-beta"), customRes.models.map { it.id })
    }

    @Test
    fun testDiscoveryErrorHandlingAndRedaction() = runBlocking {
        val secretKey = "sk-secret-key-do-not-leak-9999"
        val client401 = buildMockClient {
            401 to """{"error": {"message": "Incorrect API key provided: $secretKey"}}"""
        }
        val res401 = ModelDiscoveryService(client401).discoverModels(ProviderTemplates.openAiPreset(), secretKey)
        assertEquals(DiscoveryStatus.AUTH_FAILED, res401.status)
        assertFalse(res401.errorMessage.orEmpty().contains(secretKey))

        val client429 = buildMockClient {
            429 to """{"error": {"message": "Rate limit reached"}}"""
        }
        val res429 = ModelDiscoveryService(client429).discoverModels(ProviderTemplates.openAiPreset(), secretKey)
        assertEquals(DiscoveryStatus.RATE_LIMITED, res429.status)

        val client404 = buildMockClient {
            404 to """{"error": "Models endpoint not implemented"}"""
        }
        val res404 = ModelDiscoveryService(client404).discoverModels(ProviderTemplates.openAiPreset(), secretKey)
        assertEquals(DiscoveryStatus.ENDPOINT_NOT_FOUND, res404.status)
    }

    @Test
    fun testProviderRegistryCrudSearchAndSecretFreeExport() = runBlocking {
        val mockClient = buildMockClient {
            200 to """{"data": [{"id": "deepseek-coder-v2", "name": "DeepSeek Coder V2"}, {"id": "qwen-max", "name": "Qwen Max"}]}"""
        }
        val registry = ProviderRegistryManager(
            context = null,
            discoveryService = ModelDiscoveryService(mockClient)
        )

        val custom = ProviderTemplates.createCustomProfileFromTemplate(
            ProviderTemplates.openRouterPreset(),
            "My OpenRouter"
        ).copy(manualModelIdOverride = "manual/override-model")
        registry.saveProfile(custom)
        registry.setProviderApiKey(custom.id, "sk-or-secret-export-test")

        val fetched = registry.fetchAndCacheModels(custom.id)
        assertEquals(2, fetched.models.size)

        val filtered = registry.searchModels(custom.id, "coder")
        assertEquals(1, filtered.size)
        assertEquals("deepseek-coder-v2", filtered[0].id)

        // Manual override takes precedence when set
        assertEquals("manual/override-model", registry.getProfile(custom.id)?.effectiveModelId())

        // Exporting profiles must NEVER include API keys
        val exported = registry.exportProfilesJson(includeSecrets = false)
        assertFalse(exported.contains("sk-or-secret-export-test"))
        assertTrue(exported.contains("My OpenRouter"))
    }
}
