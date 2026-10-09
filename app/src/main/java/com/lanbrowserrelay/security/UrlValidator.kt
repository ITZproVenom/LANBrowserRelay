package com.lanbrowserrelay.security

import java.net.InetAddress
import java.net.URI
import java.net.URL

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
            if (host == "localhost" || host.endsWith(".localhost") ||
                host == "metadata.google.internal"
            ) {
                return Result.failure(SecurityException("Local hosts are blocked"))
            }

            val addresses = InetAddress.getAllByName(host)
            if (addresses.isEmpty() || addresses.any(::blocked)) {
                return Result.failure(SecurityException("Local/reserved destination blocked"))
            }
            Result.success(uri.toURL())
        } catch (_: Exception) {
            Result.failure(SecurityException("Invalid URL"))
        }
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
            if ((first and 0xfe) == 0xfc) return true // Unique-local fc00::/7.
            if (first == 0xfe && (second and 0xc0) == 0xc0) return true // Site-local fec0::/10.
            if (first == 0x20 && second == 0x01 &&
                bytes[2] == 0x0d && bytes[3] == 0xb8
            ) return true // Documentation 2001:db8::/32.

            val firstTenZero = bytes.take(10).all { it == 0 }
            val mapped = firstTenZero && bytes[10] == 0xff && bytes[11] == 0xff
            val compatible = bytes.take(12).all { it == 0 }
            if (mapped || compatible) {
                val embedded = InetAddress.getByAddress(
                    bytes.takeLast(4).map { it.toByte() }.toByteArray()
                )
                if (blocked(embedded)) return true
            }
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
