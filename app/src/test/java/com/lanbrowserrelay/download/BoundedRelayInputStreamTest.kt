package com.lanbrowserrelay.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class BoundedRelayInputStreamTest {

    private val limit = 100_000L

    private class ExplodingStream(private val afterBytes: Int) : InputStream() {
        private var emitted = 0
        override fun read(): Int {
            if (emitted >= afterBytes) throw IOException("simulated upstream failure")
            emitted++
            return 1
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (emitted >= afterBytes) throw IOException("simulated upstream failure")
            val n = minOf(len, afterBytes - emitted)
            repeat(n) { b[off + it] = 1; emitted++ }
            return n
        }
    }

    private fun readAll(stream: BoundedRelayInputStream): Long {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buffer, 0, buffer.size)
            if (n < 0) return total
            total += n
        }
    }

    @Test
    fun exactLimitIsReportedAsCleanCompletion() {
        var complete = false
        var completeTruncated: Boolean? = null
        var limitReported = false
        var aborted = false
        val stream = BoundedRelayInputStream(
            ByteArrayInputStream(ByteArray(limit.toInt()) { 7 }),
            limit,
            expectedLength = limit,
            onComplete = { bytes, truncated -> complete = true; completeTruncated = truncated; assertEquals(limit, bytes) },
            onLimitExceeded = { limitReported = true },
            abort = { aborted = true }
        )
        assertEquals(limit, readAll(stream))
        assertTrue(complete)
        assertFalse(completeTruncated!!)
        assertFalse(limitReported)
        assertFalse(aborted)
    }

    @Test
    fun overflowAbortsInsteadOfSilentlyTruncating() {
        var complete = false
        var limitReportedAt = -1L
        var aborted = false
        val stream = BoundedRelayInputStream(
            ByteArrayInputStream(ByteArray(limit.toInt() + 1) { 5 }),
            limit,
            onComplete = { _, _ -> complete = true },
            onLimitExceeded = { bytes -> limitReportedAt = bytes },
            abort = { aborted = true }
        )
        try {
            readAll(stream)
            fail("Expected the transfer to abort, not to truncate and report success")
        } catch (_: LimitExceededException) {
            // expected: the client connection is cut, never a silent success
        }
        assertEquals(limit, limitReportedAt)
        assertTrue(aborted)
        assertFalse("A silently truncated success must never be reported", complete)
    }

    @Test
    fun hugeSingleReadStillHonoursTheBudget() {
        var limitReported = false
        val stream = BoundedRelayInputStream(
            ByteArrayInputStream(ByteArray(limit.toInt() + 50) { 3 }),
            limit,
            onLimitExceeded = { limitReported = true }
        )
        val buffer = ByteArray(limit.toInt() + 128 * 1024)
        try {
            val n = stream.read(buffer, 0, buffer.size)
            assertEquals(limit.toInt(), n)
            // A second read must not hand out bytes past the cap.
            stream.read(buffer, 0, 10)
            fail("Transfer past the cap must abort")
        } catch (_: LimitExceededException) {
            // expected
        }
        assertTrue(limitReported)
    }

    @Test
    fun upstreamEndingEarlyIsReportedAsTruncated() {
        var truncated: Boolean? = null
        var completedBytes = -1L
        val stream = BoundedRelayInputStream(
            ByteArrayInputStream(ByteArray(50) { 1 }),
            limit,
            expectedLength = 100,
            onComplete = { bytes, t -> completedBytes = bytes; truncated = t }
        )
        readAll(stream)
        assertEquals(50L, completedBytes)
        assertTrue("A shorter-than-declared upstream must be flagged as truncated", truncated!!)
    }

    @Test
    fun chunkedUnknownLengthCompletesCleanly() {
        var truncated: Boolean? = null
        var completed = false
        val stream = BoundedRelayInputStream(
            ByteArrayInputStream(ByteArray(1000) { 2 }),
            limit,
            expectedLength = null,
            onComplete = { bytes, t -> completed = true; truncated = t; assertEquals(1000L, bytes) }
        )
        assertEquals(1000L, readAll(stream))
        assertTrue(completed)
        assertFalse(truncated!!)
    }

    @Test
    fun midStreamFailureIsReportedAndPropagated() {
        var errorMessage: String? = null
        var errorBytes = -1L
        val stream = BoundedRelayInputStream(
            ExplodingStream(afterBytes = 100_000),
            limit,
            onError = { bytes, message -> errorBytes = bytes; errorMessage = message }
        )
        try {
            readAll(stream)
            fail("Expected the upstream read failure to surface")
        } catch (_: IOException) {
            // expected
        }
        assertEquals(100_000L, errorBytes)
        assertTrue(errorMessage!!.contains("simulated"))
    }

    @Test
    fun clientDisconnectTriggersDisconnectedCallback() {
        var disconnectedAt = -1L
        val stream = BoundedRelayInputStream(
            ByteArrayInputStream(ByteArray(limit.toInt()) { 9 }),
            limit,
            onDisconnected = { bytes -> disconnectedAt = bytes }
        )
        val buffer = ByteArray(8192)
        stream.read(buffer, 0, buffer.size)
        stream.close()
        assertEquals(8192L, disconnectedAt)
    }

    @Test
    fun drainingExactDeclaredLengthThenClosingIsCompletionNotDisconnect() {
        var completed = false
        var disconnected = false
        val stream = BoundedRelayInputStream(
            ByteArrayInputStream(ByteArray(1000) { 4 }),
            limit,
            expectedLength = 1000,
            onComplete = { _, _ -> completed = true },
            onDisconnected = { disconnected = true }
        )
        // Simulate a fixed-length sender: read exactly length bytes, then close.
        val buffer = ByteArray(1000)
        assertEquals(1000, stream.read(buffer, 0, 1000))
        stream.close()
        assertTrue(completed)
        assertFalse(disconnected)
    }
}