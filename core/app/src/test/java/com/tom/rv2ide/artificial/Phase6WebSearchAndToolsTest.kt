package com.tom.rv2ide.artificial

import com.tom.rv2ide.artificial.exceptions.UnsafeUrlException
import com.tom.rv2ide.artificial.providers.ModelCapabilities
import com.tom.rv2ide.artificial.providers.ProviderTemplates
import com.tom.rv2ide.artificial.providers.TriStateCapability
import com.tom.rv2ide.artificial.secrets.JvmAesGcmSecretBackend
import com.tom.rv2ide.artificial.secrets.SecureKeyStore
import com.tom.rv2ide.artificial.tools.SafeUrlFetcher
import com.tom.rv2ide.artificial.tools.SearchProviderType
import com.tom.rv2ide.artificial.tools.ToolOrchestrator
import com.tom.rv2ide.artificial.tools.WebSearchConfig
import com.tom.rv2ide.artificial.tools.WebSearchManager
import com.tom.rv2ide.artificial.tools.WebSearchStatus
import com.tom.rv2ide.artificial.transport.ChatRole
import com.tom.rv2ide.artificial.transport.StreamEvent
import com.tom.rv2ide.artificial.transport.UnifiedChatMessage
import com.tom.rv2ide.artificial.transport.UnifiedTransportClient
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class Phase6WebSearchAndToolsTest {

    @Before
    fun setUp() {
        SecureKeyStore.setBackendForTesting(JvmAesGcmSecretBackend())
    }

    @After
    fun tearDown() {
        SecureKeyStore.setBackendForTesting(null)
    }

    @Test
    fun testWebSearchManagerTavilyAndBraveAndStatus() = runBlocking {
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val body = when {
                    req.url.host == "api.tavily.com" -> {
                        assertEquals("Bearer tvly-test-key", req.header("Authorization"))
                        """
                        {
                          "results": [
                            {
                              "title": "Kotlin Coroutines Guide",
                              "url": "https://kotlinlang.org/docs/coroutines-guide.html",
                              "content": "Coroutines provide asynchronous non-blocking programming.",
                              "published_date": "2025-01-10"
                            }
                          ]
                        }
                        """.trimIndent()
                    }
                    req.url.host == "api.search.brave.com" -> {
                        assertEquals("BSA-test-key", req.header("X-Subscription-Token"))
                        """
                        {
                          "web": {
                            "results": [
                              {
                                "title": "Android NDK Guides",
                                "url": "https://developer.android.com/ndk/guides",
                                "description": "Get started with the Android NDK.",
                                "page_age": "2025-02-01"
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    }
                    else -> """{"results":[]}"""
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val manager = WebSearchManager(context = null, baseHttpClient = mockHttp)
        assertEquals(WebSearchStatus.DISABLED, manager.getSearchStatus())

        manager.saveConfig(WebSearchConfig(enabled = true, providerType = SearchProviderType.TAVILY))
        assertEquals(WebSearchStatus.NEEDS_CONFIG, manager.getSearchStatus())

        manager.setSearchApiKey(SearchProviderType.TAVILY, "tvly-test-key")
        assertEquals(WebSearchStatus.READY, manager.getSearchStatus())

        val tavilyRes = manager.search("kotlin coroutines")
        assertEquals(1, tavilyRes.results.size)
        assertEquals("Kotlin Coroutines Guide", tavilyRes.results[0].title)
        assertEquals("https://kotlinlang.org/docs/coroutines-guide.html", tavilyRes.results[0].url)

        manager.saveConfig(WebSearchConfig(enabled = true, providerType = SearchProviderType.BRAVE_SEARCH))
        manager.setSearchApiKey(SearchProviderType.BRAVE_SEARCH, "BSA-test-key")
        val braveRes = manager.search("android ndk")
        assertEquals(1, braveRes.results.size)
        assertEquals("https://developer.android.com/ndk/guides", braveRes.results[0].url)
    }

    @Test
    fun testSafeUrlFetcherBlocksDisallowedSchemesAndPrivateIps() {
        val fetcher = SafeUrlFetcher(
            dnsResolver = { host ->
                when (host) {
                    "internal.corp" -> listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 42)))
                    else -> listOf(InetAddress.getByName(host))
                }
            }
        )

        val unsafeUrls = listOf(
            "file:///etc/passwd",
            "content://com.android.providers.media/item/1",
            "javascript:alert(1)",
            "http://localhost:8080/api",
            "http://127.0.0.1:11434/api/tags",
            "http://10.0.0.1/admin",
            "http://172.16.5.4/config",
            "http://192.168.1.1/router",
            "http://169.254.169.254/latest/meta-data/",
            "http://[::1]:8080/",
            "https://internal.corp/secret"
        )

        for (unsafe in unsafeUrls) {
            try {
                fetcher.validateSafePublicUrl(unsafe)
                fail("Expected UnsafeUrlException for URL: $unsafe")
            } catch (_: UnsafeUrlException) {
                // Expected!
            }
        }
    }

    @Test
    fun testSafeUrlFetcherBlocksRedirectToPrivateIpAndExtractsCleanHtml() = runBlocking {
        val publicIp = InetAddress.getByAddress("docs.example.com", byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()))
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                if (req.url.encodedPath == "/redirect-to-local") {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "http://127.0.0.1:8080/private-admin")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    val html = """
                        <html>
                          <head>
                            <title>Android Studio NDK Guide</title>
                            <script>window.stealSecrets();</script>
                            <style>body { color: red; }</style>
                          </head>
                          <body>
                            <nav>Top Navigation Menu</nav>
                            <main>
                              <h1>NDK Build Overview</h1>
                              <p>Use ndk-build with Android.mk and Application.mk.</p>
                            </main>
                            <footer>Copyright Notice</footer>
                          </body>
                        </html>
                    """.trimIndent()
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/html; charset=utf-8")
                        .body(html.toResponseBody("text/html".toMediaType()))
                        .build()
                }
            })
            .build()

        val fetcher = SafeUrlFetcher(
            baseHttpClient = mockHttp,
            dnsResolver = { host ->
                if (host == "docs.example.com") listOf(publicIp) else listOf(InetAddress.getByName(host))
            }
        )

        // 1. Verify redirect to 127.0.0.1 is blocked
        try {
            fetcher.fetchUrl("https://docs.example.com/redirect-to-local")
            fail("Expected UnsafeUrlException when redirecting to 127.0.0.1")
        } catch (_: UnsafeUrlException) {
            // Expected
        }

        // 2. Verify clean HTML extraction without script/style/nav/footer
        val page = fetcher.fetchUrl("https://docs.example.com/ndk-guide")
        assertEquals("Android Studio NDK Guide", page.title)
        assertTrue(page.readableText.contains("NDK Build Overview"))
        assertTrue(page.readableText.contains("Use ndk-build with Android.mk"))
        assertFalse(page.readableText.contains("stealSecrets"))
        assertFalse(page.readableText.contains("Top Navigation Menu"))
        assertTrue(page.toUntrustedToolContent().startsWith("<untrusted_web_content"))
    }

    @Test
    fun testToolOrchestratorMultiTurnLoopAndNonToolFallback() = runBlocking {
        val publicIp = InetAddress.getByAddress("kotlinlang.org", byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()))
        var llmTurn = 0

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val bodyStr = when {
                    req.url.host == "api.tavily.com" -> {
                        """
                        {
                          "results": [
                            {
                              "title": "Kotlin 2.1 What's New",
                              "url": "https://kotlinlang.org/docs/whatsnew21.html",
                              "content": "Kotlin 2.1 introduces guard conditions in when with a subject."
                            }
                          ]
                        }
                        """.trimIndent()
                    }
                    req.url.host == "kotlinlang.org" -> {
                        """
                        <html>
                          <head><title>What's new in Kotlin 2.1</title></head>
                          <body><main>Guard conditions allow 'if' after patterns in 'when' expressions.</main></body>
                        </html>
                        """.trimIndent()
                    }
                    else -> {
                        llmTurn++
                        when (llmTurn) {
                            1 -> {
                                // Turn 1: Model calls web_search
                                """
                                {
                                  "choices": [{
                                    "message": {
                                      "role": "assistant",
                                      "content": "",
                                      "tool_calls": [{
                                        "id": "call_search_1",
                                        "type": "function",
                                        "function": {
                                          "name": "web_search",
                                          "arguments": "{\"query\":\"kotlin 2.1 whats new\"}"
                                        }
                                      }]
                                    },
                                    "finish_reason": "tool_calls"
                                  }]
                                }
                                """.trimIndent()
                            }
                            2 -> {
                                // Turn 2: Model calls open_url on the search result
                                """
                                {
                                  "choices": [{
                                    "message": {
                                      "role": "assistant",
                                      "content": "",
                                      "tool_calls": [{
                                        "id": "call_open_2",
                                        "type": "function",
                                        "function": {
                                          "name": "open_url",
                                          "arguments": "{\"url\":\"https://kotlinlang.org/docs/whatsnew21.html\"}"
                                        }
                                      }]
                                    },
                                    "finish_reason": "tool_calls"
                                  }]
                                }
                                """.trimIndent()
                            }
                            else -> {
                                // Turn 3: Model returns final answer
                                """
                                {
                                  "choices": [{
                                    "message": {
                                      "role": "assistant",
                                      "content": "Kotlin 2.1 adds guard conditions in when expressions."
                                    },
                                    "finish_reason": "stop"
                                  }]
                                }
                                """.trimIndent()
                            }
                        }
                    }
                }
                val contentType = if (req.url.host == "kotlinlang.org") "text/html" else "application/json"
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", contentType)
                    .body(bodyStr.toResponseBody(contentType.toMediaType()))
                    .build()
            })
            .build()

        val searchMgr = WebSearchManager(context = null, baseHttpClient = mockHttp)
        searchMgr.saveConfig(WebSearchConfig(enabled = true, providerType = SearchProviderType.TAVILY))
        searchMgr.setSearchApiKey(SearchProviderType.TAVILY, "tvly-key")

        val fetcher = SafeUrlFetcher(
            baseHttpClient = mockHttp,
            dnsResolver = { listOf(publicIp) }
        )
        val orchestrator = ToolOrchestrator(
            transportClient = UnifiedTransportClient(mockHttp),
            webSearchManager = searchMgr,
            safeUrlFetcher = fetcher
        )

        val statusEvents = mutableListOf<String>()
        val resp = orchestrator.executeOrchestratedChat(
            profile = ProviderTemplates.openAiPreset(),
            modelId = "gpt-4o",
            modelCapabilities = ModelCapabilities(supportsTools = TriStateCapability.SUPPORTED),
            apiKey = "sk-test",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "What is new in Kotlin 2.1?")),
            webSearchRequested = true,
            stream = false
        ) { evt ->
            if (evt is StreamEvent.StatusUpdate) {
                statusEvents.add(evt.status)
            }
        }

        assertEquals("Kotlin 2.1 adds guard conditions in when expressions.", resp.text)
        assertEquals(1, resp.citations.size)
        assertEquals("https://kotlinlang.org/docs/whatsnew21.html", resp.citations[0].url)
        assertTrue(statusEvents.any { it.contains("Searching the web") })
        assertTrue(statusEvents.any { it.contains("Opening source") })

        // Now test deterministic search-prefetch fallback when model does NOT support tools
        val fallbackResp = orchestrator.executeOrchestratedChat(
            profile = ProviderTemplates.openAiPreset(),
            modelId = "deepseek-r1-distill",
            modelCapabilities = ModelCapabilities(supportsTools = TriStateCapability.UNSUPPORTED),
            apiKey = "sk-test",
            messages = listOf(UnifiedChatMessage(role = ChatRole.USER, content = "What is new in Kotlin 2.1?")),
            webSearchRequested = true,
            stream = false
        )

        assertEquals("Kotlin 2.1 adds guard conditions in when expressions.", fallbackResp.text)
        assertEquals(1, fallbackResp.citations.size)
        assertEquals("https://kotlinlang.org/docs/whatsnew21.html", fallbackResp.citations[0].url)
    }
}
