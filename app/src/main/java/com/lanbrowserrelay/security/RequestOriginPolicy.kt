package com.lanbrowserrelay.security

import java.net.URI

/** Request-source checks for the cleartext, IP-address-based local relay origin. */
object RequestOriginPolicy {
    fun isExpectedHost(hostHeader: String?, boundIpv4: String, port: Int): Boolean {
        if (hostHeader.isNullOrBlank()) return false
        return try {
            val uri = URI("http://$hostHeader")
            uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null && uri.rawFragment == null &&
                uri.host.equals(boundIpv4, ignoreCase = true) && uri.port == port
        } catch (_: Exception) {
            false
        }
    }

    fun isExpectedOrigin(origin: String?, boundIpv4: String, port: Int): Boolean {
        if (origin.isNullOrBlank() || origin == "null") return false
        return try {
            val uri = URI(origin)
            uri.scheme.equals("http", ignoreCase = true) && uri.rawUserInfo == null &&
                uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
                uri.host.equals(boundIpv4, ignoreCase = true) && uri.port == port
        } catch (_: Exception) {
            false
        }
    }

    fun isSameOriginFetch(site: String?): Boolean =
        site == null || site.equals("same-origin", ignoreCase = true)
}
