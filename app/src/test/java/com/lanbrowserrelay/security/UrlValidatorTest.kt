package com.lanbrowserrelay.security

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {
    @Test fun blocksPrivateAddresses() {
        listOf("127.0.0.1", "10.0.0.2", "192.168.1.5", "169.254.169.254", "100.64.0.1")
            .forEach { assertTrue("$it should be blocked", UrlValidator.isBlockedAddress(InetAddress.getByName(it))) }
    }
    @Test fun permitsPublicAddress() {
        assertFalse(UrlValidator.isBlockedAddress(InetAddress.getByName("1.1.1.1")))
    }
    @Test fun rejectsNonHttpSchemes() {
        assertTrue(UrlValidator.validate("file:///etc/passwd").isFailure)
        assertTrue(UrlValidator.validate("javascript:alert(1)").isFailure)
    }
    @Test fun sanitizesFilename() {
        assertFalse(UrlValidator.safeFilename("../../evil.txt").contains('/'))
        assertEquals("download.bin", UrlValidator.safeFilename(""))
    }
}
