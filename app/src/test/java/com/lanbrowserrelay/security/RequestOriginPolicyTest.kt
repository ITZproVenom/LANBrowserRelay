package com.lanbrowserrelay.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestOriginPolicyTest {
    @Test fun validatesHostPortOriginAndFetchMetadata() {
        assertTrue(RequestOriginPolicy.isExpectedHost("192.168.1.7:8080", "192.168.1.7", 8080))
        assertFalse(RequestOriginPolicy.isExpectedHost("attacker.example:8080", "192.168.1.7", 8080))
        assertFalse(RequestOriginPolicy.isExpectedHost("192.168.1.7:8081", "192.168.1.7", 8080))
        assertFalse(RequestOriginPolicy.isExpectedHost("192.168.1.7.evil:8080", "192.168.1.7", 8080))

        assertTrue(RequestOriginPolicy.isExpectedOrigin("http://192.168.1.7:8080", "192.168.1.7", 8080))
        assertFalse(RequestOriginPolicy.isExpectedOrigin("null", "192.168.1.7", 8080))
        assertFalse(RequestOriginPolicy.isExpectedOrigin("http://attacker.example:8080", "192.168.1.7", 8080))
        assertFalse(RequestOriginPolicy.isExpectedOrigin("https://192.168.1.7:8080", "192.168.1.7", 8080))
        assertTrue(RequestOriginPolicy.isSameOriginFetch("same-origin"))
        assertTrue(RequestOriginPolicy.isSameOriginFetch(null)) // synchronizer token remains mandatory
        assertFalse(RequestOriginPolicy.isSameOriginFetch("cross-site"))
        assertFalse(RequestOriginPolicy.isSameOriginFetch("same-site"))
    }
}
