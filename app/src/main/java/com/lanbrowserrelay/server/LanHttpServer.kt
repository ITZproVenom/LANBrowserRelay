package com.lanbrowserrelay.server

import android.content.Context
import android.util.Log
import com.lanbrowserrelay.Config
import com.lanbrowserrelay.download.DownloadManager
import com.lanbrowserrelay.download.DownloadProgress
import com.lanbrowserrelay.gateway.HtmlGateway
import com.lanbrowserrelay.security.UrlValidator
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Open LAN HTTP server — no authentication. */
class LanHttpServer(
    private val context: Context,
    port: Int,
    private val downloadManager: DownloadManager,
    private val logSink: (String) -> Unit = {},
    private val onStatusUpdate: (() -> Unit)? = null
) : NanoHTTPD(port) {

    private val gateway = HtmlGateway()
    private val clientCount = AtomicInteger(0)
    private val logs = CopyOnWriteArrayList<String>()
    private val activeCalls = ConcurrentHashMap<String, okhttp3.Call>()
    private val tag = "LanHttpServer"
    private val maxLogLines = 200

    fun connectedClients(): Int = clientCount.get()
    fun recentLogs(): List<String> = logs.takeLast(50)

    private fun log(msg: String) {
        val line = "${System.currentTimeMillis() % 100000}: $msg"
        logs.add(line)
        while (logs.size > maxLogLines) logs.removeAt(0)
        logSink(line)
        Log.d(tag, msg)
    }

    override fun serve(session: IHTTPSession): Response {
        clientCount.incrementAndGet()
        try {
            val uri = session.uri
            val method = session.method
            log("$method $uri")

            val cors = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                "Access-Control-Allow-Headers" to "Content-Type"
            )

            if (method == Method.OPTIONS) {
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").apply {
                    cors.forEach { (k, v) -> addHeader(k, v) }
                }
            }

            val response = when {
                uri == "/" || uri == "/index.html" -> serveAsset("web/index.html", "text/html")
                uri.startsWith("/css/") -> serveAsset("web$uri", "text/css")
                uri.startsWith("/js/") -> serveAsset("web$uri", "application/javascript")
                uri == "/api/status" -> jsonResponse(statusJson())
                uri == "/api/logs" -> jsonResponse(logsJson())
                uri == "/api/cancel" && method == Method.GET -> handleCancel(session)
                uri == "/api/download" && method == Method.GET -> handleDownload(session)
                uri == "/browse" -> handleBrowse(session)
                uri == "/api/search" -> handleSearch(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
            cors.forEach { (k, v) -> response.addHeader(k, v) }
            return response
        } finally {
            clientCount.decrementAndGet()
            onStatusUpdate?.invoke()
        }
    }

    private fun handleBrowse(session: IHTTPSession): Response {
        val url = session.parms["url"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url")
        log("Browse: $url")
        val result = gateway.fetchAndRewrite(url, "")
        return newFixedLengthResponse(
            Response.Status.lookup(result.statusCode) ?: Response.Status.OK,
            result.contentType,
            ByteArrayInputStream(result.body),
            result.body.size.toLong()
        )
    }

    private fun handleSearch(session: IHTTPSession): Response {
        val q = session.parms["q"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing q")
        val googleUrl = "https://www.google.com/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}&hl=en"
        val browseUrl = "/browse?url=${java.net.URLEncoder.encode(googleUrl, "UTF-8")}"
        val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
        response.addHeader("Location", browseUrl)
        return response
    }

    private fun handleDownload(session: IHTTPSession): Response {
        val url = session.parms["url"]
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url")

        val validation = UrlValidator.isAllowedUrl(url)
        if (validation.isFailure) {
            log("Download blocked: ${validation.exceptionOrNull()?.message}")
            return jsonResponse(
                JSONObject().put("error", validation.exceptionOrNull()?.message).toString(),
                Response.Status.FORBIDDEN
            )
        }

        val requestedId = session.parms["id"]
        val id = requestedId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) }
            ?: UUID.randomUUID().toString()
        val filenameHint = session.parms["filename"]
        log("Download start: $url")
        return createStreamingDownloadResponse(id, url, filenameHint)
    }

    private fun handleCancel(session: IHTTPSession): Response {
        val id = session.parms["id"]
            ?: return jsonResponse(JSONObject().put("error", "Missing download id").toString(), Response.Status.BAD_REQUEST)
        val call = activeCalls[id]
            ?: return jsonResponse(
                JSONObject().put("ok", false).put("error", "Transfer is no longer active").toString(),
                Response.Status.NOT_FOUND
            )
        downloadManager.cancel(id)
        call.cancel()
        log("Download cancellation requested (id=${id})")
        return jsonResponse(JSONObject().put("ok", true).put("id", id).toString())
    }

    private fun createStreamingDownloadResponse(id: String, url: String, filenameHint: String?): Response {
        val maxBytes = minOf(Config.getMaxDownloadBytes(context), Config.DEFAULT_MAX_DOWNLOAD_BYTES)
        if (!downloadManager.beginRelayedStream(id, url)) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                "Too many concurrent downloads or duplicate transfer id"
            )
        }

        var call: okhttp3.Call? = null
        var upstream: okhttp3.Response? = null
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val opened = openCheckedDownload(id, url, client)
            val downloadCall = opened.first
            val downloadResponse = opened.second
            call = downloadCall
            upstream = downloadResponse

            if (!downloadResponse.isSuccessful) {
                val statusCode = downloadResponse.code
                downloadResponse.close()
                activeCalls.remove(id, downloadCall)
                downloadManager.finishRelayedStream(
                    id, DownloadProgress.Status.FAILED, 0, "Upstream HTTP ${statusCode}"
                )
                return newFixedLengthResponse(
                    Response.Status.lookup(statusCode) ?: Response.Status.BAD_GATEWAY,
                    MIME_PLAINTEXT,
                    "Upstream error: ${statusCode}"
                )
            }

            val body = downloadResponse.body
            if (body == null) {
                downloadResponse.close()
                activeCalls.remove(id, downloadCall)
                downloadManager.finishRelayedStream(id, DownloadProgress.Status.FAILED, 0, "Empty response body")
                return newFixedLengthResponse(Response.Status.BAD_GATEWAY, MIME_PLAINTEXT, "Empty response body")
            }

            val contentLength = body.contentLength().takeIf { it >= 0L }
            if (contentLength != null && contentLength > maxBytes) {
                downloadResponse.close()
                activeCalls.remove(id, downloadCall)
                val message = "File exceeds the ${maxBytes / 1_000_000} MB download limit"
                downloadManager.finishRelayedStream(id, DownloadProgress.Status.LIMIT_EXCEEDED, 0, message)
                log("Rejected oversized Content-Length=${contentLength} (id=${id})")
                return newFixedLengthResponse(
                    Response.Status.PAYLOAD_TOO_LARGE,
                    "application/json",
                    JSONObject().put("error", message).toString()
                )
            }

            val finalUrl = downloadResponse.request.url.toString()
            val filename = filenameHint?.takeIf { it.isNotBlank() }
                ?.let { UrlValidator.safeFilename(it) }
                ?: UrlValidator.extractFilename(downloadResponse.header("Content-Disposition"), finalUrl)
            val safeName = UrlValidator.safeFilename(filename)
            val contentType = downloadResponse.header("Content-Type") ?: "application/octet-stream"
            val upstreamStream = body.byteStream()
            downloadManager.updateRelayedStream(id, safeName, contentLength, 0, 0)

            val transferStream = object : InputStream() {
                private var transferred = 0L
                private var closed = false
                private var finished = false
                private var lastSpeedAt = System.currentTimeMillis()
                private var lastSpeedBytes = 0L
                private var currentSpeed = 0L
                private var lastLogAt = 0L

                private fun finish(status: DownloadProgress.Status, error: String? = null) {
                    if (finished) return
                    finished = true
                    downloadManager.finishRelayedStream(id, status, transferred, error)
                    activeCalls.remove(id, downloadCall)
                }

                private fun closeUpstream() {
                    try { upstreamStream.close() } catch (_: Exception) {}
                    try { downloadResponse.close() } catch (_: Exception) {}
                    activeCalls.remove(id, downloadCall)
                }

                override fun read(): Int {
                    val one = ByteArray(1)
                    val count = read(one, 0, 1)
                    return if (count == -1) -1 else one[0].toInt() and 0xFF
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (offset < 0 || length < 0 || offset > buffer.size - length) {
                        throw IndexOutOfBoundsException()
                    }
                    if (length == 0) return 0
                    if (closed) return -1

                    try {
                        if (transferred >= maxBytes) {
                            val extra = upstreamStream.read()
                            closed = true
                            if (extra == -1) {
                                finish(DownloadProgress.Status.COMPLETED)
                                closeUpstream()
                                log("Download complete: ${transferred} bytes -> ${safeName} (id=${id})")
                                return -1
                            }
                            val message = "File exceeded the ${maxBytes / 1_000_000} MB download limit"
                            finish(DownloadProgress.Status.LIMIT_EXCEEDED, message)
                            closeUpstream()
                            log("Download size limit exceeded (id=${id})")
                            throw IOException(message)
                        }

                        val allowed = minOf(length.toLong(), maxBytes - transferred).toInt()
                        val count = upstreamStream.read(buffer, offset, allowed)
                        if (count < 0) {
                            if (contentLength != null && transferred < contentLength) {
                                val message = "Upstream ended before Content-Length bytes arrived"
                                finish(DownloadProgress.Status.FAILED, message)
                                closed = true
                                closeUpstream()
                                throw IOException(message)
                            }
                            finish(DownloadProgress.Status.COMPLETED)
                            closed = true
                            closeUpstream()
                            log("Download complete: ${transferred} bytes -> ${safeName} (id=${id})")
                            return -1
                        }

                        transferred += count
                        val now = System.currentTimeMillis()
                        if (now - lastSpeedAt >= 500) {
                            currentSpeed = ((transferred - lastSpeedBytes) * 1000L) /
                                (now - lastSpeedAt).coerceAtLeast(1L)
                            lastSpeedAt = now
                            lastSpeedBytes = transferred
                        }
                        downloadManager.updateRelayedStream(
                            id, safeName, contentLength, transferred, currentSpeed
                        )
                        if (now - lastLogAt >= 1000) {
                            log("Streaming ${transferred / 1000} KB -> ${safeName} (id=${id})")
                            lastLogAt = now
                        }
                        return count
                    } catch (e: IOException) {
                        if (!finished) {
                            val cancelled = downloadCall.isCanceled == true
                            finish(
                                if (cancelled) DownloadProgress.Status.CANCELLED else DownloadProgress.Status.FAILED,
                                if (cancelled) null else (e.message ?: "Streaming failed")
                            )
                        }
                        closed = true
                        closeUpstream()
                        throw e
                    }
                }

                override fun close() {
                    if (!finished) {
                        val cancelled = downloadCall.isCanceled == true
                        val completeKnownLength = contentLength != null &&
                            transferred == contentLength && !cancelled
                        finish(
                            when {
                                cancelled -> DownloadProgress.Status.CANCELLED
                                completeKnownLength -> DownloadProgress.Status.COMPLETED
                                else -> DownloadProgress.Status.CANCELLED
                            },
                            if (cancelled || !completeKnownLength) "Transfer cancelled or client disconnected" else null
                        )
                    }
                    closed = true
                    closeUpstream()
                }
            }

            val response = if (contentLength != null) {
                newFixedLengthResponse(Response.Status.OK, contentType, transferStream, contentLength)
            } else {
                newChunkedResponse(Response.Status.OK, contentType, transferStream)
            }
            response.addHeader(
                "Content-Disposition",
                "attachment; filename=\"${safeName}\"; filename*=UTF-8''${java.net.URLEncoder.encode(safeName, "UTF-8").replace("+", "%20")}"
            )
            response.addHeader("X-Download-Id", id)
            response.addHeader("Cache-Control", "no-store")
            response.addHeader("X-Content-Type-Options", "nosniff")
            return response
        } catch (e: Exception) {
            val cancelled = call?.isCanceled == true
            activeCalls.remove(id)
            try { upstream?.close() } catch (_: Exception) {}
            downloadManager.finishRelayedStream(
                id,
                if (cancelled) DownloadProgress.Status.CANCELLED else DownloadProgress.Status.FAILED,
                0,
                if (cancelled) null else (e.message ?: "Download failed")
            )
            val message = if (cancelled) "Download cancelled" else (e.message ?: "Download failed")
            log("${message} (id=${id})")
            return newFixedLengthResponse(
                if (cancelled) Response.Status.BAD_REQUEST else Response.Status.BAD_GATEWAY,
                MIME_PLAINTEXT,
                message
            )
        }
    }

    private fun openCheckedDownload(
        id: String,
        initialUrl: String,
        client: okhttp3.OkHttpClient
    ): Pair<okhttp3.Call, okhttp3.Response> {
        var currentUrl = UrlValidator.isAllowedUrl(initialUrl).getOrElse {
            throw IOException("Blocked URL: ${it.message}")
        }.toString()

        for (redirectCount in 0..MAX_REDIRECTS) {
            val request = okhttp3.Request.Builder()
                .url(currentUrl)
                .header("User-Agent", "LANBrowserRelay/1.0")
                .build()
            val call = client.newCall(request)
            activeCalls[id] = call
            val response = call.execute()

            if (response.code in 300..399) {
                val location = response.header("Location")
                if (location.isNullOrBlank()) return call to response
                if (redirectCount == MAX_REDIRECTS) {
                    response.close()
                    activeCalls.remove(id, call)
                    throw IOException("Too many redirects")
                }

                val nextUrl = try {
                    URI(currentUrl).resolve(location).toURL().toString()
                } catch (e: Exception) {
                    response.close()
                    activeCalls.remove(id, call)
                    throw IOException("Invalid redirect URL", e)
                }
                response.close()
                activeCalls.remove(id, call)
                currentUrl = UrlValidator.isAllowedUrl(nextUrl).getOrElse {
                    throw IOException("Redirect blocked: ${it.message}")
                }.toString()
            } else {
                if (UrlValidator.isAllowedUrl(response.request.url.toString()).isFailure) {
                    response.close()
                    activeCalls.remove(id, call)
                    throw IOException("Redirect to prohibited destination")
                }
                return call to response
            }
        }
        throw IOException("Too many redirects")
    }

    private fun statusJson(): String {
        val downloads = downloadManager.getRecentDownloads()
        return JSONObject()
            .put("running", true)
            .put("clients", clientCount.get())
            .put("downloads", downloadManager.getActiveDownloads().size)
            .put("maxDownloadBytes", minOf(Config.getMaxDownloadBytes(context), Config.DEFAULT_MAX_DOWNLOAD_BYTES))
            .put("totalBytesServed", downloadManager.getTotalBytesServed())
            .put("active", JSONArray().apply {
                downloads.forEach { d ->
                    put(JSONObject()
                        .put("id", d.id)
                        .put("url", d.url)
                        .put("filename", d.filename)
                        .put("bytes", d.bytesTransferred)
                        .put("contentLength", d.contentLength ?: JSONObject.NULL)
                        .put("status", d.status.name)
                        .put("speed", d.speedBps)
                        .put("error", d.error ?: JSONObject.NULL))
                }
            })
            .toString()
    }

    private fun logsJson(): String {
        return JSONObject().put("logs", JSONArray(recentLogs())).toString()
    }

    private fun serveAsset(path: String, mime: String): Response {
        return try {
            val stream = context.assets.open(path)
            newChunkedResponse(Response.Status.OK, mime, stream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Asset not found: $path")
        }
    }

    private fun jsonResponse(json: String, status: Response.Status = Response.Status.OK): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json)
    }

    companion object {
        private const val MAX_REDIRECTS = 5
    }
}
