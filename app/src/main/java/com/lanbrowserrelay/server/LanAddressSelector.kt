package com.lanbrowserrelay.server

/** Pure deterministic choice of a usable private IPv4 address for the relay UI. */
object LanAddressSelector {
    data class Candidate(
        val interfaceName: String,
        val address: String,
        val isUp: Boolean,
        val isLoopback: Boolean = false,
        val isLinkLocal: Boolean = false,
        val isActive: Boolean = false,
        val isEthernet: Boolean = false
    )

    fun select(candidates: Iterable<Candidate>): String? = candidates.asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isLinkLocal && isPrivateIpv4(it.address) }
        .sortedWith(
            compareBy<Candidate> {
                when {
                    it.isEthernet && it.isActive -> 0
                    it.isEthernet -> 1
                    it.isActive -> 2
                    else -> 3
                }
            }.thenBy { it.interfaceName.lowercase() }
                .thenBy { ipv4Number(it.address) }
        )
        .firstOrNull()
        ?.address

    private fun isPrivateIpv4(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    private fun ipv4Number(address: String): Long = address.split('.')
        .fold(0L) { result, octet -> result * 256 + octet.toInt() }
}
