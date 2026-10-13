package com.lanbrowserrelay.download

import com.lanbrowserrelay.DownloadPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TransferDeadlineTest {
    @Test fun slowDripIsCancelledAtAbsoluteDeadlineAndFreesSlot() {
        val server = ServerSocket(0)
        val stopped = AtomicBoolean(false)
        val firstChunkSent = CountDownLatch(1)
        val upstream = Thread {
            try {
                server.accept().use { socket ->
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    while (!stopped.get()) {
                        out.write("1\r\nx\r\n".toByteArray())
                        out.flush()
                        firstChunkSent.countDown()
                        Thread.sleep(20)
                    }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }

        val manager = DownloadManager()
        assertTrue(manager.begin("slow", "http://127.0.0.1:${server.localPort}/", "browser"))
        val call = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
        val deadline = TransferDeadline(180) {
            manager.finish("slow", "FAILED", 0, "absolute deadline")
            call.cancel()
        }
        try {
            val response = call.execute()
            assertTrue(response.isSuccessful)
            try { response.body!!.byteStream().readBytes() } finally { response.close() }
        } catch (_: IOException) {
            // Deadline cancellation must interrupt the slow-drip response.
        } finally {
            stopped.set(true)
            deadline.close()
            server.close()
            upstream.join(1_000)
        }
        assertTrue(firstChunkSent.await(1, TimeUnit.SECONDS))
        assertTrue(deadline.hasExpired())
        assertEquals(0, manager.activeCount("browser"))
        assertTrue(manager.begin("next", "https://example.com/next", "browser"))
        manager.finish("next", "COMPLETED", 0)
    }
}
