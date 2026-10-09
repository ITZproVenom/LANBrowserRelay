package com.lanbrowserrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {
    @Test
    fun exactDecimalCap() {
        assertEquals(100_000_000L, DownloadPolicy.MAX_BYTES)
    }

    @Test
    fun bufferCannotCrossCap() {
        assertEquals(32_768, DownloadPolicy.bytesAllowed(0, 32_768))
        assertEquals(1, DownloadPolicy.bytesAllowed(99_999_999, 32_768))
        assertEquals(0, DownloadPolicy.bytesAllowed(100_000_000, 1))
        assertEquals(0, DownloadPolicy.bytesAllowed(-1, 32_768))
        assertEquals(0, DownloadPolicy.bytesAllowed(0, -1))
    }

    @Test
    fun exactBoundaryIsAllowedButNextByteIsNot() {
        assertFalse(DownloadPolicy.wouldExceed(99_999_999, 1))
        assertTrue(DownloadPolicy.wouldExceed(99_999_999, 2))
        assertFalse(DownloadPolicy.wouldExceed(100_000_000, 0))
        assertTrue(DownloadPolicy.wouldExceed(100_000_000, 1))
    }

    @Test
    fun malformedAndOverflowInputsAreRejected() {
        assertTrue(DownloadPolicy.wouldExceed(-1, 0))
        assertTrue(DownloadPolicy.wouldExceed(0, -1))
        assertTrue(DownloadPolicy.wouldExceed(Long.MAX_VALUE, Int.MAX_VALUE))
        assertTrue(DownloadPolicy.wouldExceed(100_000_001, 0))
    }
}
