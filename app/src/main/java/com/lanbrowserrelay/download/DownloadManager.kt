package com.lanbrowserrelay.download

import com.lanbrowserrelay.DownloadPolicy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class Transfer(
    val id: String,
    val url: String,
    val filename: String = "Preparing…",
    val bytes: Long = 0,
    val total: Long? = null,
    val speed: Long = 0,
    val status: String = "STARTING",
    val error: String? = null,
    val updated: Long = System.currentTimeMillis(),
    val ownerSession: String = ""
)

class DownloadManager {
    private val items = ConcurrentHashMap<String, Transfer>()
    private val reservations = ConcurrentHashMap.newKeySet<String>()
    private val count = AtomicInteger()
    private val served = AtomicLong()

    fun totalBytesServed() = served.get()
    fun activeCount(ownerSession: String? = null) = items.values.count {
        (ownerSession == null || it.ownerSession == ownerSession) && (it.status == "STARTING" || it.status == "STREAMING")
    }
    fun recent(ownerSession: String? = null) = items.values
        .filter { ownerSession == null || it.ownerSession == ownerSession }
        .sortedByDescending { it.updated }.take(30)

    @Synchronized
    fun begin(id: String, url: String, ownerSession: String = ""): Boolean {
        if (id.isBlank() || id.length > 80 || items.containsKey(id) || !reservations.add(id)) return false
        if (count.incrementAndGet() > DownloadPolicy.MAX_CONCURRENT ||
            activeCount(ownerSession) >= DownloadPolicy.MAX_CONCURRENT_PER_SESSION) {
            count.decrementAndGet()
            reservations.remove(id)
            items[id] = Transfer(id, url, status = "FAILED", error = "Concurrent limit reached", ownerSession = ownerSession)
            trim()
            return false
        }
        items[id] = Transfer(id, url, ownerSession = ownerSession)
        trim()
        return true
    }

    fun progress(id: String, name: String, total: Long?, bytes: Long, speed: Long) {
        if (id !in reservations) return
        items.computeIfPresent(id) { _, old ->
            if (old.status != "STARTING" && old.status != "STREAMING") old
            else {
                val next = maxOf(old.bytes, bytes)
                served.addAndGet((next - old.bytes).coerceAtLeast(0))
                old.copy(filename = name, total = total, bytes = next, speed = speed,
                    status = "STREAMING", error = null, updated = System.currentTimeMillis())
            }
        }
    }

    fun finish(id: String, status: String, bytes: Long, error: String? = null) {
        if (reservations.remove(id)) count.decrementAndGet()
        items.computeIfPresent(id) { _, old ->
            if (old.status != "STARTING" && old.status != "STREAMING" && old.status != "CANCELLED") old
            else {
                val next = maxOf(old.bytes, bytes)
                served.addAndGet((next - old.bytes).coerceAtLeast(0))
                val finalStatus = if (old.status == "CANCELLED" || status == "CANCELLED") "CANCELLED" else status
                old.copy(bytes = next, status = finalStatus, speed = 0,
                    error = if (finalStatus == "CANCELLED") null else error,
                    updated = System.currentTimeMillis())
            }
        }
        trim()
    }

    /** Marks a transfer cancelled even if its OkHttp Call is not installed yet. */
    fun cancel(id: String, ownerSession: String? = null): Boolean {
        var changed = false
        items.computeIfPresent(id) { _, old ->
            if ((ownerSession == null || old.ownerSession == ownerSession) &&
                (old.status == "STARTING" || old.status == "STREAMING")) {
                changed = true
                old.copy(status = "CANCELLED", speed = 0, updated = System.currentTimeMillis())
            } else old
        }
        return changed
    }

    fun isCancelled(id: String) = items[id]?.status == "CANCELLED"
    fun isActive(id: String) = id in reservations
    fun isOwnedBy(id: String, ownerSession: String) = items[id]?.ownerSession == ownerSession

    private fun trim() {
        if (items.size > 30) {
            items.values.filter { it.id !in reservations && it.status !in setOf("STARTING", "STREAMING") }
                .sortedBy { it.updated }.take(items.size - 30).forEach { items.remove(it.id, it) }
        }
    }
}
