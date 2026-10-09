package com.lanbrowserrelay.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadManagerTest {

    @Test
    fun concurrencyLimitIsEnforced() {
        val manager = DownloadManager()
        assertTrue(manager.begin("a", "https://example.com/a"))
        assertTrue(manager.begin("b", "https://example.com/b"))
        assertTrue(manager.begin("c", "https://example.com/c"))
        // Fourth concurrent request must be rejected.
        assertFalse(manager.begin("d", "https://example.com/d"))
        assertEquals(3, manager.activeCount())

        manager.finish("a", "COMPLETED", 10)
        assertTrue(manager.begin("e", "https://example.com/e"))
        assertEquals(3, manager.activeCount())
    }

    @Test
    fun duplicateIdIsRejected() {
        val manager = DownloadManager()
        assertTrue(manager.begin("a", "https://example.com/a"))
        assertFalse(manager.begin("a", "https://example.com/a-again"))
    }

    @Test
    fun cancelKeepsTransferCancelStateThroughFinish() {
        val manager = DownloadManager()
        val id = "dl-cancel"
        assertTrue(manager.begin(id, "https://example.com/file"))
        manager.cancel(id)
        // finish() may race the cancellation; the transfer must stay CANCELLED.
        manager.finish(id, "COMPLETED", 5)
        val transfer = manager.recent().first { it.id == id }
        assertEquals("CANCELLED", transfer.status)
        assertEquals(0, manager.activeCount())
    }

    @Test
    fun bytesTrackedExactlyOnce() {
        val manager = DownloadManager()
        val id = "dl-bytes"
        assertTrue(manager.begin(id, "https://example.com/file"))
        manager.progress(id, "file.bin", 100L, 40L, 500L)
        manager.progress(id, "file.bin", 100L, 40L, 500L) // duplicate report
        manager.progress(id, "file.bin", 100L, 80L, 500L)
        manager.finish(id, "COMPLETED", 80L)
        assertEquals(80L, manager.totalBytesServed())
        val transfer = manager.recent().first { it.id == id }
        assertEquals(80L, transfer.bytes)
        assertEquals("COMPLETED", transfer.status)
    }

    @Test
    fun limitExceededStateIsPreserved() {
        val manager = DownloadManager()
        val id = "dl-limit"
        assertTrue(manager.begin(id, "https://example.com/file"))
        manager.finish(id, "LIMIT_EXCEEDED", 100_000_000L, "File exceeds 100 MB limit")
        val transfer = manager.recent().first { it.id == id }
        assertEquals("LIMIT_EXCEEDED", transfer.status)
        assertEquals(0, manager.activeCount())
    }
}