package com.lanbrowserrelay.download

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Streams an upstream payload to the client while enforcing a hard byte budget.
 * Memory use is bounded by the read buffer the caller passes in.
 *
 * Contract with the caller:
 *  - The budget is enforced against bytes actually read, not just the declared
 *    Content-Length. Once the budget is reached the stream peeks for one extra
 *    byte: no extra byte means a clean completion at exactly the cap, an extra
 *    byte means the transfer is aborted (a [LimitExceededException] is thrown)
 *    so the client never sees a silently-truncated "success".
 *  - Early close() (client disconnect before EOF) and mid-stream read errors
 *    are reported through [onDisconnected] / [onError].
 */
class BoundedRelayInputStream(
    input: InputStream,
    private val limitBytes: Long,
    private val expectedLength: Long? = null,
    private val onBytes: (Long) -> Unit = {},
    private val onComplete: (Long, Boolean) -> Unit = { _, _ -> },
    private val onLimitExceeded: (Long) -> Unit = {},
    private val onDisconnected: (Long) -> Unit = {},
    private val onError: (Long, String) -> Unit = { _, _ -> },
    private val abort: () -> Unit = {}
) : FilterInputStream(input) {

    var bytesRead: Long = 0L
        private set

    private var terminal = false
    private var closed = false

    init {
        require(limitBytes > 0) { "limitBytes must be positive" }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException()
        }
        if (length == 0) return 0
        if (closed || terminal) return -1

        if (bytesRead >= limitBytes) {
            val extra = readOneForLimitProbe()
            terminal = true
            if (extra < 0) {
                onComplete(bytesRead, false)
                return -1
            }
            onLimitExceeded(bytesRead)
            abort()
            throw LimitExceededException(bytesRead, limitBytes)
        }

        val allowed = minOf(length.toLong(), limitBytes - bytesRead).toInt()
        val n = try {
            super.read(buffer, offset, allowed)
        } catch (e: IOException) {
            terminal = true
            onError(bytesRead, e.message ?: "Upstream read failed")
            throw e
        }

        if (n < 0) {
            terminal = true
            val truncated = expectedLength != null && bytesRead < expectedLength
            onComplete(bytesRead, truncated)
            return -1
        }
        if (n > 0) {
            bytesRead += n
            onBytes(bytesRead)
        }
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!terminal) {
            terminal = true
            if (expectedLength != null && bytesRead >= expectedLength) {
                onComplete(bytesRead, false)
            } else {
                onDisconnected(bytesRead)
            }
        }
        super.close()
    }

    private fun readOneForLimitProbe(): Int {
        return try {
            super.read()
        } catch (e: IOException) {
            terminal = true
            onError(bytesRead, e.message ?: "Upstream read failed")
            throw e
        }
    }
}

/** Thrown when the stream would exceed its byte budget. Not a silent truncation. */
class LimitExceededException(
    val bytesRead: Long,
    val limitBytes: Long
) : IOException("Download exceeded $limitBytes byte limit after $bytesRead bytes")