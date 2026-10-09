package com.lanbrowserrelay.download

import com.lanbrowserrelay.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class Transfer(
    val id: String,
    val url: String,
    val filename: String,
    val bytes: Long,
    val length: Long?,
    val speedBps: Long,
    val status: Status,
    val error: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    enum class Status { STARTING, STREAMING, COMPLETED, FAILED, CANCELLED, LIMIT_EXCEEDED }
}

class DownloadManager {
    private val transfers = ConcurrentHashMap<String, Transfer>()
    private val totalBytes = AtomicLong(0)

    @Synchronized
    fun begin(id: String, url: String): Boolean {
        if (transfers.containsKey(id)) return false
        val count = transfers.values.count { it.status == Transfer.Status.STARTING || it.status == Transfer.Status.STREAMING }
        if (count >= Config.MAX_CONCURRENT_DOWNLOADS) return false
        transfers[id] = Transfer(id, url, "Preparing download", 0, null, 0, Transfer.Status.STARTING)
        trimHistory()
        return true
    }

    fun update(id: String, filename: String, length: Long?, bytes: Long, speed: Long) {
        transfers.computeIfPresent(id) { _, old ->
            if (old.status != Transfer.Status.STARTING && old.status != Transfer.Status.STREAMING) old
            else {
                val safeBytes = maxOf(old.bytes, bytes)
                totalBytes.addAndGet((safeBytes - old.bytes).coerceAtLeast(0))
                old.copy(filename=filename, length=length, bytes=safeBytes, speedBps=speed.coerceAtLeast(0),
                    status=Transfer.Status.STREAMING, error=null, updatedAt=System.currentTimeMillis())
            }
        }
    }

    fun finish(id: String, status: Transfer.Status, bytes: Long, error: String? = null) {
        transfers.computeIfPresent(id) { _, old ->
            val safeBytes = maxOf(old.bytes, bytes)
            totalBytes.addAndGet((safeBytes - old.bytes).coerceAtLeast(0))
            val finalStatus = if (old.status == Transfer.Status.CANCELLED) Transfer.Status.CANCELLED else status
            old.copy(bytes=safeBytes, speedBps=0, status=finalStatus,
                error=if (finalStatus == Transfer.Status.CANCELLED) null else error, updatedAt=System.currentTimeMillis())
        }
        trimHistory()
    }

    fun cancel(id: String) {
        transfers.computeIfPresent(id) { _, old ->
            if (old.status == Transfer.Status.STARTING || old.status == Transfer.Status.STREAMING)
                old.copy(status=Transfer.Status.CANCELLED, speedBps=0, updatedAt=System.currentTimeMillis())
            else old
        }
    }

    fun active(): List<Transfer> = recent().filter {
        it.status == Transfer.Status.STARTING || it.status == Transfer.Status.STREAMING
    }
    fun recent(): List<Transfer> = transfers.values.sortedByDescending { it.updatedAt }.take(MAX_HISTORY)
    fun totalBytesServed(): Long = totalBytes.get()

    @Synchronized
    private fun trimHistory() {
        if (transfers.size <= MAX_HISTORY) return
        transfers.values.filter { it.status !in listOf(Transfer.Status.STARTING, Transfer.Status.STREAMING) }
            .sortedBy { it.updatedAt }.take(transfers.size - MAX_HISTORY).forEach { transfers.remove(it.id, it) }
    }

    companion object { private const val MAX_HISTORY = 50 }
}
