package com.lanbrowserrelay.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanAddressSelectorTest {
    private fun candidate(
        name: String,
        ip: String,
        active: Boolean = false,
        ethernet: Boolean = false,
        up: Boolean = true,
        loopback: Boolean = false,
        linkLocal: Boolean = false
    ) = LanAddressSelector.Candidate(name, ip, up, loopback, linkLocal, active, ethernet)

    @Test fun activeEthernetWinsRegardlessOfEnumerationOrder() {
        val wifi = candidate("wlan0", "192.168.1.4", active = true)
        val ethernet = candidate("eth0", "10.0.0.8", active = true, ethernet = true)
        assertEquals("10.0.0.8", LanAddressSelector.select(listOf(wifi, ethernet)))
        assertEquals("10.0.0.8", LanAddressSelector.select(listOf(ethernet, wifi)))
    }

    @Test fun interfaceAndAddressTiesAreDeterministic() {
        val values = listOf(
            candidate("wlan1", "192.168.1.9"),
            candidate("wlan0", "192.168.1.20"),
            candidate("wlan0", "192.168.1.3")
        )
        assertEquals("192.168.1.3", LanAddressSelector.select(values))
        assertEquals("192.168.1.3", LanAddressSelector.select(values.reversed()))
    }

    @Test fun ignoresUnusableAndNonLanAddressesAndHandlesNoAddresses() {
        assertNull(LanAddressSelector.select(emptyList()))
        assertNull(LanAddressSelector.select(listOf(
            candidate("lo", "127.0.0.1", loopback = true),
            candidate("eth0", "169.254.4.2", linkLocal = true),
            candidate("wlan0", "8.8.8.8"),
            candidate("down0", "192.168.1.8", up = false)
        )))
    }
}
