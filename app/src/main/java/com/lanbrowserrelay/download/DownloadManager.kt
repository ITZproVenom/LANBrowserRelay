package com.lanbrowserrelay.download

import com.lanbrowserrelay.Config
import com.lanbrowserrelay.security.UrlValidator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class DownloadProgress(
    val id: String,
    val url: String,
    val filename: String,
    val bytesTransferred: Long,
    val contentLength: Long?,
    val status: Status,
    val error: String? = null,
    val speedBps: Long = 0
) {
    enum class Status { STARTING, STREAMING, COMPLETED, FAILED, CANCELLED, LIMIT_EXCEEDED }
}

/**
 * Manages streaming downloads with hard size limits and bounded buffers.
 * Never writes file contents to disk.
 */
class DownloadManager(
    maxBytes: Long = Config.DEFAULT_MAX_DOWNLOAD_BYTES,
    maxConcurrent: Int = Config.MAX_CONCURRENT_DOWNLOADS
) {
    private val maxBytes = minOf(maxBytes.coerceAtLeast(1L), Config.DEFAULT_MAX_DOWNLOAD_BYTES)
    private val maxConcurrent = maxConcurrent.coerceAtLeast(1)
    private val client = OkHttpClient.Builder()
        .connectTimeout(Config.CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(Config.READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val active = ConcurrentHashMap<String, DownloadProgress>()
    private val relayedIds = ConcurrentHashMap.newKeySet<String>()
    private val concurrentCount = AtomicInteger(0)
    private val totalBytesServed = AtomicLong(0)

    fun getActiveDownloads(): List<DownloadProgress> = active.values.filter {
        it.status == DownloadProgress.Status.STARTING || it.status == DownloadProgress.Status.STREAMING
    }

    fun getRecentDownloads(): List<DownloadProgress> =
        active.values.sortedByDescending { it.id }.take(MAX_HISTORY)

    fun getTotalBytesServed(): Long = totalBytesServed.get()

    /** Reserve a stream handled by LanHttpServer without staging its bytes to disk. */
    fun beginRelayedStream(id: String, url: String): Boolean {
        if (!relayedIds.add(id)) return false
        if (concurrentCount.incrementAndGet() > maxConcurrent) {
            concurrentCount.decrementAndGet()
            relayedIds.remove(id)
            active[id] = DownloadProgress(
                id, url, "unknown", 0, null,
                DownloadProgress.Status.FAILED, "Too many concurrent downloads"
            )
            trimHistory()
            return false
        }
        active[id] = DownloadProgress(
            id, url, "Preparing download", 0, null, DownloadProgress.Status.STARTING
        )
        trimHistory()
        return true
    }

    fun updateRelayedStream(
        id: String,
        filename: String,
        contentLength: Long?,
        bytesTransferred: Long,
        speedBps: Long
    ) {
        if (id !in relayedIds) return
        active.computeIfPresent(id) { _, old ->
            if (old.status == DownloadProgress.Status.CANCELLED) {
                old
            } else {
                val updatedBytes = maxOf(old.bytesTransferred, bytesTransferred)
                totalBytesServed.addAndGet((updatedBytes - old.bytesTransferred).coerceAtLeast(0L))
                old.copy(
                    filename = filename,
                    contentLength = contentLength,
                    bytesTransferred = updatedBytes,
                    status = DownloadProgress.Status.STREAMING,
                    speedBps = speedBps,
                    error = null
                )
            }
        }
    }

    fun finishRelayedStream(
        id: String,
        status: DownloadProgress.Status,
        bytesTransferred: Long,
        error: String? = null
    ) {
        val reserved = relayedIds.remove(id)
        active.computeIfPresent(id) { _, old ->
            val updatedBytes = maxOf(old.bytesTransferred, bytesTransferred)
            totalBytesServed.addAndGet((updatedBytes - old.bytesTransferred).coerceAtLeast(0L))
            val finalStatus = if (
                old.status == DownloadProgress.Status.CANCELLED ||
                status == DownloadProgress.Status.CANCELLED
            ) DownloadProgress.Status.CANCELLED else status
            old.copy(
                bytesTransferred = updatedBytes,
                status = finalStatus,
                speedBps = 0,
                error = if (finalStatus == DownloadProgress.Status.CANCELLED) null else error
            )
        }
        if (reserved) concurrentCount.decrementAndGet()
        trimHistory()
    }

    private fun trimHistory() {
        if (active.size <= MAX_HISTORY) return
        val terminal = active.entries.filter {
            it.key !in relayedIds && it.value.status !in listOf(
                DownloadProgress.Status.STARTING,
                DownloadProgress.Status.STREAMING
            )
        }
        val excess = (active.size - MAX_HISTORY).coerceAtLeast(0)
        terminal.take(excess).forEach { active.remove(it.key, it.value) }
    }

    companion object {
        private const val MAX_HISTORY = 50
    }

    fun streamTo(
        id: String,
        urlString: String,
        output: OutputStream,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): DownloadProgress {
        val validation = UrlValidator.isAllowedUrl(urlString)
        if (validation.isFailure) {
            val p = DownloadProgress(id, urlString, "unknown", 0, null, DownloadProgress.Status.FAILED, validation.exceptionOrNull()?.message)
            active[id] = p
            return p
        }
        val url = validation.getOrThrow()

        if (concurrentCount.incrementAndGet() > maxConcurrent) {
            concurrentCount.decrementAndGet()
            val p = DownloadProgress(id, urlString, "unknown", 0, null, DownloadProgress.Status.FAILED, "Too many concurrent downloads")
            active[id] = p
            trimHistory()
            return p
        }
        var progress = DownloadProgress(id, urlString, "download.bin", 0, null, DownloadProgress.Status.STARTING)
        active[id] = progress
        onProgress?.invoke(progress)

        var response: Response? = null
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "LANBrowserRelay/1.0")
                .build()

            response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                progress = progress.copy(status = DownloadProgress.Status.FAILED, error = "HTTP ${response.code}")
                active[id] = progress
                onProgress?.invoke(progress)
                return progress
            }

            val finalUrl = response.request.url.toString()
            val finalCheck = UrlValidator.isAllowedUrl(finalUrl)
            if (finalCheck.isFailure) {
                progress = progress.copy(status = DownloadProgress.Status.FAILED, error = "Redirect to prohibited destination")
                active[id] = progress
                onProgress?.invoke(progress)
                return progress
            }

            val body = response.body ?: throw IOException("Empty body")
            val contentLength = body.contentLength().takeIf { it >= 0 }
            if (contentLength != null && contentLength > maxBytes) {
                progress = progress.copy(
                    status = DownloadProgress.Status.LIMIT_EXCEEDED,
                    contentLength = contentLength,
                    error = "File exceeds ${maxBytes / 1_000_000} MB limit"
                )
                active[id] = progress
                onProgress?.invoke(progress)
                return progress
            }

            val filename = UrlValidator.extractFilename(response.header("Content-Disposition"), finalUrl)
            progress = progress.copy(filename = filename, contentLength = contentLength, status = DownloadProgress.Status.STREAMING)
            active[id] = progress
            onProgress?.invoke(progress)

            val input: InputStream = body.byteStream()
            val buffer = ByteArray(Config.BUFFER_SIZE)
            var transferred = 0L
            var lastReport = System.currentTimeMillis()
            var lastBytes = 0L

            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (transferred + read > maxBytes) {
                    progress = progress.copy(
                        bytesTransferred = transferred,
                        status = DownloadProgress.Status.LIMIT_EXCEEDED,
                        error = "Exceeded ${maxBytes / 1_000_000} MB limit while streaming"
                    )
                    active[id] = progress
                    onProgress?.invoke(progress)
                    return progress
                }
                output.write(buffer, 0, read)
                transferred += read
                totalBytesServed.addAndGet(read.toLong())

                val now = System.currentTimeMillis()
                if (now - lastReport >= 500) {
                    val speed = ((transferred - lastBytes) * 1000) / (now - lastReport).coerceAtLeast(1)
                    progress = progress.copy(bytesTransferred = transferred, speedBps = speed)
                    active[id] = progress
                    onProgress?.invoke(progress)
                    lastReport = now
                    lastBytes = transferred
                }
            }
            output.flush()

            progress = progress.copy(bytesTransferred = transferred, status = DownloadProgress.Status.COMPLETED, speedBps = 0)
            active[id] = progress
            onProgress?.invoke(progress)
            return progress
        } catch (e: Exception) {
            progress = progress.copy(status = DownloadProgress.Status.FAILED, error = e.message ?: "Unknown error")
            active[id] = progress
            onProgress?.invoke(progress)
            return progress
        } finally {
            response?.close()
            concurrentCount.decrementAndGet()
        }
    }

    fun cancel(id: String) {
        active[id]?.let { active[id] = it.copy(status = DownloadProgress.Status.CANCELLED) }
    }

    fun clearFinished() {
        active.entries.removeIf {
            it.value.status in listOf(
                DownloadProgress.Status.COMPLETED,
                DownloadProgress.Status.FAILED,
                DownloadProgress.Status.CANCELLED,
                DownloadProgress.Status.LIMIT_EXCEEDED
            )
        }
    }
}
