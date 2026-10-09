package com.lanbrowserrelay.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {
    @Test
    fun acceptsOnlyHttpAndHttpsSchemes() {
        assertTrue(UrlValidator.validate("ftp://example.com/file").isFailure)
        assertTrue(UrlValidator.validate("file:///etc/passwd").isFailure)
        assertTrue(UrlValidator.validate("javascript:alert(1)").isFailure)
    }

    @Test
    fun blocksLocalAndPrivateDestinations() {
        listOf(
            "http://localhost/",
            "http://printer.localhost/",
            "http://127.0.0.1/",
            "http://10.0.0.8/",
            "http://172.20.0.1/",
            "http://192.168.1.1/",
            "http://100.64.0.1/",
            "http://169.254.169.254/",
            "http://[::1]/",
            "http://[fc00::1]/",
            "http://[fe80::1]/",
            "http://[2001:db8::1]/",
            "http://[::ffff:127.0.0.1]/",
            "http://[2002:7f00:1::1]/",
            "http://[2001:2::1]/",
            "http://[3fff::1]/",
            "http://[64:ff9b::808:808]/",
            "http://user:pass@example.com/"
        ).forEach { url ->
            assertTrue("Expected blocked URL: $url", UrlValidator.validate(url).isFailure)
        }
    }

    @Test
    fun resolverRejectsLoopbackAtConnectionTime() {
        assertTrue(runCatching {
            UrlValidator.resolvePublicAddresses("127.0.0.1")
        }.isFailure)
    }

    @Test
    fun filenameIsSafeForContentDisposition() {
        val name = UrlValidator.safeFilename("../../my\\\\file?.zip")
        assertFalse(name.contains('/'))
        assertFalse(name.contains(92.toChar()))
        assertFalse(name.contains('?'))
        assertTrue(name.isNotBlank())
    }

    @Test
    fun prefersRfc5987Filename() {
        assertEquals(
            "résumé.zip",
            UrlValidator.filenameFrom("attachment; filename=\"resume.zip\"; filename*=UTF-8''r%C3%A9sum%C3%A9.zip", "https://example.com/file")
        )
    }
}
