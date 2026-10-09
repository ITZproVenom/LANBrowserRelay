package com.lanbrowserrelay.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL

object UrlValidator {
    fun validate(raw: String): Result<URL> = runCatching {
        val uri = URI(raw.trim())
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "Only HTTP and HTTPS URLs are supported"
        }
        require(uri.rawUserInfo == null) { "URLs containing user credentials are not allowed" }
        val host = uri.host?.trim('[', ']')?.lowercase()
        require(!host.isNullOrBlank()) { "URL has no valid hostname" }
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty() && addresses.none(::isBlockedAddress)) {
            "Local or reserved destinations are blocked"
        }
        uri.toURL()
    }

    fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return true
        val b = address.address.map { it.toInt() and 255 }
        if (address is Inet4Address) {
            val a0 = b[0]
            val a1 = b[1]
            if (a0 == 0 || a0 == 10 || a0 == 127 || a0 >= 224) return true
            if (a0 == 100 && a1 in 64..127) return true
            if (a0 == 169 && a1 == 254) return true
            if (a0 == 172 && a1 in 16..31) return true
            if (a0 == 192 && (a1 == 0 || a1 == 168)) return true
            if (a0 == 198 && a1 in 18..19) return true
            if (a0 == 198 && a1 == 51 && b[2] == 100) return true
            if (a0 == 203 && a1 == 0 && b[2] == 113) return true
        }
        if (address is Inet6Address && (b[0] and 0xfe) == 0xfc) return true
        return false
    }

    fun safeFilename(input: String?): String {
        val value = input.orEmpty()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim().trim('.').take(180)
        return value.ifBlank { "download.bin" }
    }

    fun filenameFrom(disposition: String?, url: String): String {
        if (!disposition.isNullOrBlank()) {
            Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
                .find(disposition)?.groupValues?.getOrNull(1)?.let {
                    return safeFilename(runCatching { java.net.URLDecoder.decode(it.trim(), "UTF-8") }.getOrDefault(it))
                }
            Regex("filename=\"?([^\";]+)", RegexOption.IGNORE_CASE)
                .find(disposition)?.groupValues?.getOrNull(1)?.let { return safeFilename(it) }
        }
        return safeFilename(runCatching { URI(url).path.substringAfterLast('/').takeIf { it.isNotBlank() } }.getOrNull())
    }
}
