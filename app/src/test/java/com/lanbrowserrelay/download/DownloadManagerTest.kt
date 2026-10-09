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
    fun cancellationCanWinBeforeCallIsInstalledAndRemainsTerminal() {
        val manager = DownloadManager()
        assertTrue(manager.begin("race", "https://example.com/file"))
        assertTrue(manager.cancel("race"))
        assertTrue(manager.isCancelled("race"))
        manager.finish("race", "COMPLETED", 17)
        manager.progress("race", "file", 100, 20, 1)
        val transfer = manager.recent().first { it.id == "race" }
        assertEquals("CANCELLED", transfer.status)
        assertEquals(17L, transfer.bytes)
        assertFalse(manager.cancel("race"))
    }

    @Test
    fun completedTransferCannotBeReusedOrOverwrittenByLateCallback() {
        val manager = DownloadManager()
        assertTrue(manager.begin("once", "https://example.com/file"))
        manager.finish("once", "COMPLETED", 9)
        assertFalse(manager.begin("once", "https://example.com/other"))
        manager.finish("once", "FAILED", 0, "late error")
        val transfer = manager.recent().first { it.id == "once" }
        assertEquals("COMPLETED", transfer.status)
        assertEquals(9L, transfer.bytes)
    }

    @Test
    fun transfersAreIsolatedAndLimitedPerBrowserSession() {
        val manager = DownloadManager()
        assertTrue(manager.begin("a1", "https://example.com/a1", "session-a"))
        assertTrue(manager.begin("a2", "https://example.com/a2", "session-a"))
        assertFalse(manager.begin("a3", "https://example.com/a3", "session-a"))
        assertTrue(manager.begin("b1", "https://example.com/b1", "session-b"))
        assertEquals(2, manager.activeCount("session-a"))
        assertEquals(1, manager.recent("session-b").size)
        assertFalse(manager.cancel("a1", "session-b"))
        assertTrue(manager.cancel("a1", "session-a"))
        assertEquals(0, manager.recent("session-b").count { it.status == "CANCELLED" })
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