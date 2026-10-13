package com.lanbrowserrelay.security

import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.UnknownHostException

/**
 * Validates HTTP destinations before any outbound request and after every redirect.
 * Blocks private, loopback, link-local, unique-local IPv6, and special-use ranges.
 */
object UrlValidator {
    fun validate(raw: String): Result<URL> {
        return try {
            val uri = URI(raw.trim())
            val scheme = uri.scheme?.lowercase()
                ?: return Result.failure(SecurityException("Missing scheme"))
            if (scheme != "http" && scheme != "https") {
                return Result.failure(SecurityException("Only HTTP/HTTPS is allowed"))
            }

            val host = uri.host?.removeSurrounding("[", "]")?.lowercase()
                ?: return Result.failure(SecurityException("Missing host"))
            if (uri.rawUserInfo != null) {
                return Result.failure(SecurityException("Credentials in URLs are blocked"))
            }
            if (host == "localhost" || host.endsWith(".localhost") ||
                host == "metadata.google.internal"
            ) {
                return Result.failure(SecurityException("Local hosts are blocked"))
            }

            resolvePublicAddresses(host)
            Result.success(uri.toURL())
        } catch (_: Exception) {
            Result.failure(SecurityException("Invalid URL"))
        }
    }

    /**
     * Call this from OkHttp's DNS hook as well as during URL validation. That second
     * check prevents a hostname that changes from public to private from reaching
     * a local service between validation and the actual connection.
     */
    fun resolvePublicAddresses(hostname: String): List<InetAddress> {
        val addresses = InetAddress.getAllByName(hostname).toList()
        if (addresses.isEmpty() || addresses.any(::blocked)) {
            throw UnknownHostException("Local/reserved destination blocked")
        }
        return addresses
    }

    private fun blocked(ip: InetAddress): Boolean {
        if (ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress ||
            ip.isSiteLocalAddress || ip.isMulticastAddress
        ) return true

        val bytes = ip.address.map { it.toInt() and 0xff }
        if (bytes.size == 4) {
            val a = bytes[0]
            val b = bytes[1]
            val c = bytes[2]

            if (a == 0 || a >= 224) return true
            if (a == 10 || (a == 172 && b in 16..31) ||
                (a == 192 && b == 168)
            ) return true
            if (a == 100 && b in 64..127) return true
            if (a == 192 && b == 0 && c in 0..2) return true
            if (a == 192 && b == 88 && c == 99) return true
            if (a == 198 && b in 18..19) return true
            if (a == 198 && b == 51 && c == 100) return true
            if (a == 203 && b == 0 && c == 113) return true
            return false
        }

        if (bytes.size == 16) {
            val first = bytes[0]
            val second = bytes[1]
            val firstTenZero = bytes.take(10).all { it == 0 }
            val mapped = firstTenZero && bytes[10] == 0xff && bytes[11] == 0xff
            val compatible = bytes.take(12).all { it == 0 }
            if (mapped || compatible) return true

            // Only global-unicast IPv6 is eligible; this excludes unspecified,
            // loopback, ULA, link/site-local, multicast and other special ranges.
            if ((first and 0xe0) != 0x20) return true
            if (first == 0x20 && second == 0x01 && bytes[2] in 0..1) return true // IETF protocol assignments, incl. Teredo.
            if (first == 0x20 && second == 0x02) return true // 6to4 embeds an arbitrary IPv4 destination.
            if (first == 0x20 && second == 0x01 &&
                bytes[2] == 0x0d && bytes[3] == 0xb8
            ) return true // Documentation 2001:db8::/32.
            if (first == 0x3f && second == 0xff && (bytes[2] and 0xf0) == 0) {
                return true // Documentation 3fff::/20.
            }
            val nat64WellKnown = first == 0 && second == 0x64 && bytes[2] == 0xff && bytes[3] == 0x9b &&
                bytes.slice(4..11).all { it == 0 }
            val nat64Local = first == 0 && second == 0x64 && bytes[2] == 0xff && bytes[3] == 0x9b &&
                bytes[4] == 0 && bytes[5] == 1
            if (nat64WellKnown || nat64Local) return true
            return false
        }

        return true
    }

    fun safeFilename(raw: String?): String {
        val cleaned = raw.orEmpty()
            .filter { it >= ' ' && it != '\u007f' }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .trim('.')
            .take(180)
        return cleaned.ifBlank { "download.bin" }
    }

    fun filenameFrom(disposition: String?, url: String): String {
        val encoded = disposition?.let {
            Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.get(1)
        }
        if (!encoded.isNullOrBlank()) {
            val decoded = try {
                java.net.URLDecoder.decode(encoded, "UTF-8")
            } catch (_: Exception) {
                encoded
            }
            return safeFilename(decoded)
        }

        val basic = disposition?.let {
            Regex("filename=\"?([^\";]+)", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.get(1)
        }
        if (!basic.isNullOrBlank()) return safeFilename(basic)

        return try {
            safeFilename(URI(url).path.substringAfterLast('/'))
        } catch (_: Exception) {
            "download.bin"
        }
    }
}
