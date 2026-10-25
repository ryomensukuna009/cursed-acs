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

package com.tom.rv2ide.artificial.secrets

import com.tom.rv2ide.artificial.exceptions.InsecureEndpointException
import com.tom.rv2ide.artificial.exceptions.UnsafeUrlException
import java.net.URI
import java.net.URL

/**
 * Validates provider and tool URLs, enforcing HTTPS by default, requiring explicit opt-in
 * for cleartext HTTP endpoints, and preventing credential leakage across cross-origin redirects.
 */
object EndpointSecurityValidator {

    /**
     * Validates a provider endpoint URL.
     * - Only http and https schemes are accepted.
     * - Remote/local http:// requires [allowCleartextHttp] to be explicitly true.
     */
    fun validateEndpointUrl(urlString: String, allowCleartextHttp: Boolean): URL {
        val trimmed = urlString.trim()
        if (trimmed.isEmpty()) {
            throw UnsafeUrlException("Endpoint URL cannot be empty.")
        }

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw UnsafeUrlException("Malformed URL: ${SecretRedactor.redact(trimmed)}")
        }

        val scheme = uri.scheme?.lowercase()
            ?: throw UnsafeUrlException("URL is missing scheme (expected https://): ${SecretRedactor.redact(trimmed)}")

        if (scheme != "https" && scheme != "http") {
            throw UnsafeUrlException("Unsupported URL scheme '$scheme'. Only HTTPS (or explicitly permitted HTTP) is allowed.")
        }

        val host = uri.host
        if (host.isNullOrBlank()) {
            throw UnsafeUrlException("URL is missing a valid host: ${SecretRedactor.redact(trimmed)}")
        }

        if (scheme == "http" && !allowCleartextHttp) {
            throw InsecureEndpointException(
                "Cleartext HTTP endpoint '$host' is blocked by default. " +
                    "Enable 'Allow Cleartext HTTP (Insecure)' in the provider settings if you trust this network."
            )
        }

        return try {
            uri.toURL()
        } catch (e: Exception) {
            throw UnsafeUrlException("Invalid endpoint URL: ${SecretRedactor.redact(trimmed)}")
        }
    }

    /**
     * Determines whether credentials (Authorization, x-api-key, custom auth headers)
     * must be stripped when following an HTTP redirect.
     *
     * Credentials are NEVER forwarded if the redirect changes host, scheme, or effective port.
     */
    fun shouldStripCredentialsOnRedirect(originalUrl: URL, redirectUrl: URL): Boolean {
        val sameProtocol = originalUrl.protocol.equals(redirectUrl.protocol, ignoreCase = true)
        val sameHost = originalUrl.host.equals(redirectUrl.host, ignoreCase = true)
        val origPort = if (originalUrl.port != -1) originalUrl.port else originalUrl.defaultPort
        val redirPort = if (redirectUrl.port != -1) redirectUrl.port else redirectUrl.defaultPort
        val samePort = origPort == redirPort
        return !(sameProtocol && sameHost && samePort)
    }

    fun shouldStripCredentialsOnRedirect(fromUrl: String, toUrl: String): Boolean {
        return try {
            shouldStripCredentialsOnRedirect(URL(fromUrl), URL(toUrl))
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Checks if a redirect URL is acceptable under the current cleartext policy.
     */
    fun validateRedirectTarget(originalUrl: URL, redirectUrl: URL, allowCleartextHttp: Boolean) {
        val scheme = redirectUrl.protocol.lowercase()
        if (scheme != "https" && scheme != "http") {
            throw UnsafeUrlException("Redirect to unsupported scheme '$scheme' blocked.")
        }
        if (scheme == "http" && !allowCleartextHttp) {
            throw InsecureEndpointException(
                "Redirect from ${originalUrl.protocol}://${originalUrl.host} to cleartext HTTP (${redirectUrl.host}) blocked."
            )
        }
    }
}
