package com.lanbrowserrelay.download

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Absolute wall-clock transfer deadline; its callback must cancel I/O and release the slot. */
class TransferDeadline(timeoutMillis: Long, private val onTimeout: () -> Unit) : Closeable {
    private val finished = AtomicBoolean(false)
    private val expired = AtomicBoolean(false)
    private val task: ScheduledFuture<*>

    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        task = scheduler.schedule({
            if (finished.compareAndSet(false, true)) {
                expired.set(true)
                onTimeout()
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS)
    }

    fun hasExpired() = expired.get()

    override fun close() {
        if (finished.compareAndSet(false, true)) task.cancel(false)
    }

    companion object {
        private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "LBR-transfer-deadline").apply { isDaemon = true }
        }
    }
}
