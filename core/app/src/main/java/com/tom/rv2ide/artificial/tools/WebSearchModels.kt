package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.transport.CitationItem
import org.json.JSONObject

enum class SearchProviderType(val id: String, val displayName: String) {
    DUCKDUCKGO("duckduckgo", "DuckDuckGo (Free / No Key)"),
    JINA("jina", "Jina AI Search (Free / Low Cost)"),
    TAVILY("tavily", "Tavily Search API"),
    BRAVE_SEARCH("brave_search", "Brave Search API"),
    CUSTOM_JSON("custom_json", "Custom JSON Search Endpoint");

    companion object {
        fun fromId(id: String?): SearchProviderType {
            return entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: DUCKDUCKGO
        }
    }
}

enum class WebSearchStatus(val label: String) {
    DISABLED("Web Search Disabled"),
    NEEDS_CONFIG("Web Search Enabled (Needs Search API Key / URL)"),
    READY("Web Search Enabled & Ready")
}

data class WebSearchConfig(
    val enabled: Boolean = false,
    val providerType: SearchProviderType = SearchProviderType.DUCKDUCKGO,
    val customEndpointUrl: String = "",
    val allowCleartextCustomEndpoint: Boolean = false,
    val resultLimit: Int = 5,
    val timeoutSec: Int = 15
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("providerType", providerType.id)
        put("customEndpointUrl", customEndpointUrl)
        put("allowCleartextCustomEndpoint", allowCleartextCustomEndpoint)
        put("resultLimit", resultLimit)
        put("timeoutSec", timeoutSec)
    }

    companion object {
        fun fromJson(obj: JSONObject): WebSearchConfig = WebSearchConfig(
            enabled = obj.optBoolean("enabled", false),
            providerType = SearchProviderType.fromId(obj.optString("providerType", SearchProviderType.DUCKDUCKGO.id)),
            customEndpointUrl = obj.optString("customEndpointUrl", ""),
            allowCleartextCustomEndpoint = obj.optBoolean("allowCleartextCustomEndpoint", false),
            resultLimit = obj.optInt("resultLimit", 5).coerceIn(1, 10),
            timeoutSec = obj.optInt("timeoutSec", 15).coerceIn(5, 60)
        )
    }
}

data class WebSearchResultItem(
    val title: String,
    val url: String,
    val snippet: String,
    val publishedDate: String? = null,
    val source: String = "web_search"
) {
    fun toCitationItem(): CitationItem = CitationItem(
        title = title.ifBlank { url },
        url = url,
        snippet = snippet,
        sourceType = source
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("url", url)
        put("snippet", snippet)
        if (!publishedDate.isNullOrBlank()) {
            put("publishedDate", publishedDate)
        }
        put("source", source)
    }
}

data class WebSearchResponse(
    val query: String,
    val results: List<WebSearchResultItem>,
    val providerUsed: SearchProviderType
) {
    fun toToolResultContent(): String {
        if (results.isEmpty()) {
            return "<untrusted_web_content source=\"web_search\" query=\"${escapeXml(query)}\">\nNo web search results found.\n</untrusted_web_content>"
        }
        val sb = StringBuilder()
        sb.append("<untrusted_web_content source=\"web_search\" query=\"")
            .append(escapeXml(query))
            .append("\">\n")
        results.forEachIndexed { idx, item ->
            sb.append("[Result ").append(idx + 1).append("]\n")
            sb.append("Title: ").append(item.title).append('\n')
            sb.append("URL: ").append(item.url).append('\n')
            if (!item.publishedDate.isNullOrBlank()) {
                sb.append("Published: ").append(item.publishedDate).append('\n')
            }
            sb.append("Snippet: ").append(item.snippet).append("\n\n")
        }
        sb.append("</untrusted_web_content>")
        return sb.toString()
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
