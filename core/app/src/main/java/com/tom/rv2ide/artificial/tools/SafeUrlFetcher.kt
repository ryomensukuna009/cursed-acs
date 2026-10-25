package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.exceptions.NetworkErrorException
import com.tom.rv2ide.artificial.exceptions.UnsafeUrlException
import com.tom.rv2ide.artificial.transport.CitationItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class FetchedWebPage(
    val url: String,
    val finalUrl: String,
    val title: String,
    val readableText: String,
    val contentType: String,
    val truncated: Boolean
) {
    fun toCitationItem(): CitationItem = CitationItem(
        title = title.ifBlank { finalUrl },
        url = finalUrl,
        snippet = readableText.take(240),
        sourceType = "open_url"
    )

    fun toUntrustedToolContent(): String {
        val safeUrl = escapeAttr(finalUrl)
        val safeTitle = escapeAttr(title.ifBlank { finalUrl })
        return buildString {
            append("<untrusted_web_content source=\"open_url\" url=\"")
            append(safeUrl)
            append("\" title=\"")
            append(safeTitle)
            append("\">\n")
            if (truncated) {
                append("[Note: Page content truncated to fit context limit]\n")
            }
            append(readableText)
            append("\n</untrusted_web_content>")
        }
    }

    private fun escapeAttr(s: String): String {
        return s.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}

class SafeUrlFetcher(
    private val baseHttpClient: OkHttpClient = defaultHttpClient(),
    private val dnsResolver: (String) -> List<InetAddress> = { host ->
        InetAddress.getAllByName(host).toList()
    },
    private val maxResponseBytes: Int = 512 * 1024,
    private val maxExtractedChars: Int = 12_000
) {

    /**
     * Validates that a target URL is safe to fetch via `open_url`:
     * - Must use `http` or `https` scheme.
     * - Must not target localhost, loopback, link-local, or private network IPs.
     */
    fun validateSafePublicUrl(urlString: String) {
        val trimmed = urlString.trim()
        if (trimmed.isEmpty()) {
            throw UnsafeUrlException("URL cannot be empty")
        }

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw UnsafeUrlException("Malformed URL: $trimmed", e)
        }

        val scheme = uri.scheme?.lowercase()
            ?: throw UnsafeUrlException("URL is missing scheme (must be http or https): $trimmed")

        if (scheme != "http" && scheme != "https") {
            throw UnsafeUrlException("Disallowed URL scheme '$scheme://'. Only HTTP(S) URLs are permitted.")
        }

        val host = uri.host?.lowercase()?.trim('[', ']')
            ?: throw UnsafeUrlException("URL is missing a valid host: $trimmed")

        if (host.isEmpty() ||
            host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            host == "0.0.0.0" ||
            host == "::1" ||
            host == "0:0:0:0:0:0:0:1"
        ) {
            throw UnsafeUrlException("Access to localhost or local network host '$host' is blocked.")
        }

        val resolvedAddresses = try {
            dnsResolver(host)
        } catch (e: Exception) {
            throw UnsafeUrlException("Unable to resolve host '$host': ${e.message}", e)
        }

        if (resolvedAddresses.isEmpty()) {
            throw UnsafeUrlException("Host '$host' did not resolve to any IP address.")
        }

        for (addr in resolvedAddresses) {
            if (isDisallowedAddress(addr)) {
                throw UnsafeUrlException(
                    "Access to private, loopback, or reserved IP address (${addr.hostAddress}) for host '$host' is blocked."
                )
            }
        }
    }

    private fun isDisallowedAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress ||
            addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return true
        }

        when (addr) {
            is Inet4Address -> {
                val b = addr.address
                val b0 = b[0].toInt() and 0xFF
                val b1 = b[1].toInt() and 0xFF
                // 0.0.0.0/8, 10.0.0.0/8, 100.64.0.0/10 (CGNAT), 127.0.0.0/8, 169.254.0.0/16, 172.16.0.0/12, 192.168.0.0/16
                if (b0 == 0 || b0 == 10 || b0 == 127) return true
                if (b0 == 100 && (b1 in 64..127)) return true
                if (b0 == 169 && b1 == 254) return true
                if (b0 == 172 && (b1 in 16..31)) return true
                if (b0 == 192 && b1 == 168) return true
            }
            is Inet6Address -> {
                val b = addr.address
                val b0 = b[0].toInt() and 0xFF
                val b1 = b[1].toInt() and 0xFF
                // Unique local fc00::/7 or link-local fe80::/10
                if ((b0 and 0xFE) == 0xFC) return true
                if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true
            }
        }

        return false
    }

    suspend fun fetchUrl(
        urlString: String,
        timeoutSec: Int = 15
    ): FetchedWebPage = withContext(Dispatchers.IO) {
        validateSafePublicUrl(urlString)

        val timeout = timeoutSec.coerceIn(5, 60).toLong()
        val client = baseHttpClient.newBuilder()
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .writeTimeout(timeout, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val initialRequest = Request.Builder()
            .url(urlString.trim())
            .header("User-Agent", "Mozilla/5.0 (compatible; ROXUM-ACS-Agent/1.0)")
            .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9")
            .get()
            .build()

        val response = executeWithSafeRedirects(client, initialRequest)
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw NetworkErrorException("Failed to fetch URL (HTTP ${resp.code})")
            }

            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            if (isBinaryContentType(contentType)) {
                throw UnsafeUrlException("Unsupported binary content type '$contentType' for open_url.")
            }

            val body = resp.body ?: throw NetworkErrorException("Empty response body from URL")
            val (rawBytes, byteTruncated) = readCappedBytes(body.byteStream(), maxResponseBytes)
            val rawText = String(rawBytes, StandardCharsets.UTF_8)
            val finalUrl = resp.request.url.toString()

            val (title, extractedText) = if (contentType.contains("html") || rawText.trimStart().startsWith("<")) {
                extractReadableHtml(rawText, finalUrl)
            } else {
                Pair(finalUrl, rawText.trim())
            }

            val charTruncated = extractedText.length > maxExtractedChars
            val finalText = if (charTruncated) {
                extractedText.take(maxExtractedChars)
            } else {
                extractedText
            }

            FetchedWebPage(
                url = urlString.trim(),
                finalUrl = finalUrl,
                title = title.ifBlank { finalUrl },
                readableText = finalText,
                contentType = contentType,
                truncated = byteTruncated || charTruncated
            )
        }
    }

    private fun executeWithSafeRedirects(
        client: OkHttpClient,
        initialRequest: Request
    ): Response {
        var currentRequest = initialRequest
        var redirects = 0
        val maxRedirects = 4

        while (true) {
            val response = try {
                client.newCall(currentRequest).execute()
            } catch (e: IOException) {
                throw NetworkErrorException("Network error fetching URL: ${e.message}", e)
            }

            if (response.code !in listOf(301, 302, 303, 307, 308)) {
                return response
            }

            val location = response.header("Location")
            if (location.isNullOrBlank() || redirects >= maxRedirects) {
                return response
            }

            val nextUrl = currentRequest.url.resolve(location) ?: return response
            response.close()

            // Validate every redirect target against private IP / file scheme rules!
            validateSafePublicUrl(nextUrl.toString())

            currentRequest = currentRequest.newBuilder()
                .url(nextUrl)
                .removeHeader("Authorization")
                .removeHeader("Cookie")
                .get()
                .build()
            redirects++
        }
    }

    private fun readCappedBytes(
        inputStream: java.io.InputStream,
        maxBytes: Int
    ): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        var truncated = false

        inputStream.use { stream ->
            while (true) {
                val remaining = maxBytes - total
                if (remaining <= 0) {
                    // Check if more data exists
                    if (stream.read() != -1) {
                        truncated = true
                    }
                    break
                }
                val readCount = stream.read(buffer, 0, minOf(buffer.size, remaining))
                if (readCount == -1) break
                out.write(buffer, 0, readCount)
                total += readCount
            }
        }
        return Pair(out.toByteArray(), truncated)
    }

    fun extractReadableHtml(html: String, baseUri: String): Pair<String, String> {
        val doc = Jsoup.parse(html, baseUri)
        val title = doc.title().trim()

        doc.select("script, style, noscript, iframe, svg, canvas, form, nav, footer, aside").remove()

        val mainElement = doc.selectFirst("main, article, [role=main]") ?: doc.body()
        val text = mainElement?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return Pair(title, text)
    }

    private fun isBinaryContentType(contentType: String): Boolean {
        if (contentType.isBlank()) return false
        val binaryPrefixes = listOf(
            "image/",
            "video/",
            "audio/",
            "application/octet-stream",
            "application/zip",
            "application/gzip",
            "application/pdf",
            "application/vnd.android.package-archive"
        )
        return binaryPrefixes.any { contentType.startsWith(it) }
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
