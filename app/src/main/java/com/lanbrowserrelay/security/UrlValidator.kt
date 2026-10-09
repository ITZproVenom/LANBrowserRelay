package com.lanbrowserrelay.security

import java.net.InetAddress
import java.net.URI
import java.net.URL

/**
 * Validates destinations for SSRF protection.
 * Blocks private, loopback, link-local, multicast, and reserved ranges.
 */
object UrlValidator {

    private val BLOCKED_HOSTS = setOf(
        "localhost", "127.0.0.1", "0.0.0.0", "::1",
        "metadata.google.internal", "169.254.169.254"
    )

    fun isAllowedUrl(urlString: String): Result<URL> {
        return try {
            val url = URL(urlString)
            val protocol = url.protocol.lowercase()
            if (protocol != "http" && protocol != "https") {
                return Result.failure(SecurityException("Only http/https schemes allowed"))
            }
            val host = url.host?.lowercase() ?: return Result.failure(SecurityException("Missing host"))
            if (host in BLOCKED_HOSTS) {
                return Result.failure(SecurityException("Blocked host: $host"))
            }
            val addresses = InetAddress.getAllByName(host)
            for (addr in addresses) {
                if (isProhibitedAddress(addr)) {
                    return Result.failure(SecurityException("Prohibited address for host $host: ${addr.hostAddress}"))
                }
            }
            Result.success(url)
        } catch (e: Exception) {
            Result.failure(SecurityException("Invalid URL: ${e.message}"))
        }
    }

    fun isProhibitedAddress(addr: InetAddress): Boolean {
        return addr.isAnyLocalAddress ||
                addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isMulticastAddress ||
                addr.isSiteLocalAddress ||
                isCarrierGradeNat(addr) ||
                isReserved(addr)
    }

    private fun isCarrierGradeNat(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            return b0 == 100 && (b1 and 0xC0) == 64
        }
        return false
    }

    private fun isReserved(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            if (b0 == 0) return true
            if (b0 >= 240) return true
        }
        return false
    }

    fun safeFilename(name: String?): String {
        if (name.isNullOrBlank()) return "download.bin"
        var cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.length > 200) cleaned = cleaned.take(200)
        if (cleaned.isBlank()) return "download.bin"
        return cleaned
    }

    fun extractFilename(contentDisposition: String?, url: String): String {
        contentDisposition?.let { cd ->
            val filenameStar = Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE)
                .find(cd)?.groupValues?.getOrNull(1)
            if (!filenameStar.isNullOrBlank()) {
                return safeFilename(java.net.URLDecoder.decode(filenameStar.trim().trim('"'), "UTF-8"))
            }
            val filename = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(cd)?.groupValues?.getOrNull(1)
            if (!filename.isNullOrBlank()) {
                return safeFilename(filename.trim())
            }
        }
        return try {
            val path = URI(url).path
            val last = path.substringAfterLast('/').takeIf { it.isNotBlank() }
            safeFilename(last ?: "download.bin")
        } catch (_: Exception) {
            "download.bin"
        }
    }
}
