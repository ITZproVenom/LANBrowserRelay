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
    private val maxBytes: Long = Config.DEFAULT_MAX_DOWNLOAD_BYTES,
    private val maxConcurrent: Int = Config.MAX_CONCURRENT_DOWNLOADS
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(Config.CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(Config.READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val active = ConcurrentHashMap<String, DownloadProgress>()
    private val concurrentCount = AtomicInteger(0)
    private val totalBytesServed = AtomicLong(0)

    fun getActiveDownloads(): List<DownloadProgress> = active.values.toList()
    fun getTotalBytesServed(): Long = totalBytesServed.get()

    fun streamTo(
        id: String,
        urlString: String,
        output: OutputStream,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): DownloadProgress {
        if (concurrentCount.get() >= maxConcurrent) {
            val p = DownloadProgress(id, urlString, "unknown", 0, null, DownloadProgress.Status.FAILED, "Too many concurrent downloads")
            active[id] = p
            return p
        }

        val validation = UrlValidator.isAllowedUrl(urlString)
        if (validation.isFailure) {
            val p = DownloadProgress(id, urlString, "unknown", 0, null, DownloadProgress.Status.FAILED, validation.exceptionOrNull()?.message)
            active[id] = p
            return p
        }
        val url = validation.getOrThrow()

        concurrentCount.incrementAndGet()
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
