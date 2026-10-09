package com.lanbrowserrelay

object DownloadPolicy {
    const val MAX_BYTES = 100_000_000L
    const val BUFFER_BYTES = 32 * 1024
    const val MAX_CONCURRENT = 3
    const val MAX_CONCURRENT_PER_SESSION = 2
    const val MAX_TRANSFER_DURATION_MS = 15 * 60 * 1000L
    const val MAX_PAGE_REQUEST_DURATION_MS = 30_000L
    const val MAX_IDLE_READ_MS = 30_000L
    const val MAX_SESSION_REQUESTS_PER_MINUTE = 120

    fun bytesAllowed(transferred: Long, requested: Int): Int {
        if (transferred < 0L || requested <= 0 || transferred >= MAX_BYTES) return 0
        val remaining = MAX_BYTES - transferred
        return minOf(requested.toLong(), remaining).toInt()
    }

    fun wouldExceed(transferred: Long, incoming: Int): Boolean {
        if (transferred < 0L || incoming < 0) return true
        if (transferred > MAX_BYTES) return true
        return incoming.toLong() > MAX_BYTES - transferred
    }
}
