package com.lanbrowserrelay.server

import android.content.Context
import android.util.Log
import com.lanbrowserrelay.Config
import com.lanbrowserrelay.download.DownloadManager
import com.lanbrowserrelay.gateway.HtmlGateway
import com.lanbrowserrelay.security.UrlValidator
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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

        val id = UUID.randomUUID().toString()
        val filenameHint = session.parms["filename"]
        log("Download start: $url")
        return createStreamingDownloadResponse(id, url, filenameHint)
    }

    private fun createStreamingDownloadResponse(id: String, url: String, filenameHint: String?): Response {
        val maxBytes = Config.getMaxDownloadBytes(context)
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .followRedirects(true)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder().url(url).build()
            val call = client.newCall(request)
            val upstream = call.execute()

            if (!upstream.isSuccessful) {
                upstream.close()
                log("Upstream error ${upstream.code}")
                return newFixedLengthResponse(
                    Response.Status.lookup(upstream.code) ?: Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Upstream error: ${upstream.code}"
                )
            }

            val finalUrl = upstream.request.url.toString()
            if (UrlValidator.isAllowedUrl(finalUrl).isFailure) {
                upstream.close()
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Redirect blocked")
            }

            val body = upstream.body!!
            val contentLength = body.contentLength()
            if (contentLength > 0 && contentLength > maxBytes) {
                upstream.close()
                log("Rejected oversized Content-Length: $contentLength")
                return newFixedLengthResponse(
                    Response.Status.PAYLOAD_TOO_LARGE,
                    "application/json",
                    JSONObject().put("error", "File exceeds limit of ${maxBytes / 1_000_000} MB").toString()
                )
            }

            val filename = filenameHint?.takeIf { it.isNotBlank() }
                ?: UrlValidator.extractFilename(upstream.header("Content-Disposition"), finalUrl)
            val contentType = upstream.header("Content-Type") ?: "application/octet-stream"

            val limitedStream = object : InputStream() {
                private val upstreamStream = body.byteStream()
                private var transferred = 0L
                private var closed = false
                private var lastLog = 0L

                override fun read(): Int {
                    val b = ByteArray(1)
                    val n = read(b, 0, 1)
                    return if (n == -1) -1 else b[0].toInt() and 0xFF
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (closed) return -1
                    if (transferred >= maxBytes) {
                        call.cancel()
                        closed = true
                        log("Limit hit at $transferred bytes")
                        return -1
                    }
                    val toRead = minOf(len.toLong(), maxBytes - transferred).toInt()
                    val n = upstreamStream.read(b, off, toRead)
                    if (n > 0) {
                        transferred += n
                        val now = System.currentTimeMillis()
                        if (now - lastLog > 1000) {
                            log("Streaming ${transferred / 1000} KB")
                            lastLog = now
                        }
                    }
                    if (n == -1) {
                        closed = true
                        log("Download complete: $transferred bytes -> $filename")
                    }
                    return n
                }

                override fun close() {
                    closed = true
                    try { upstreamStream.close() } catch (_: Exception) {}
                    try { upstream.close() } catch (_: Exception) {}
                }
            }

            val response = newChunkedResponse(Response.Status.OK, contentType, limitedStream)
            val safeName = UrlValidator.safeFilename(filename)
            response.addHeader(
                "Content-Disposition",
                "attachment; filename=\"$safeName\"; filename*=UTF-8''${java.net.URLEncoder.encode(safeName, "UTF-8").replace("+", "%20")}"
            )
            response.addHeader("X-Download-Id", id)
            response.addHeader("Cache-Control", "no-store")
            response
        } catch (e: Exception) {
            log("Download failed: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Download failed: ${e.message}")
        }
    }

    private fun statusJson(): String {
        val downloads = downloadManager.getActiveDownloads()
        return JSONObject()
            .put("running", true)
            .put("clients", clientCount.get())
            .put("downloads", downloads.size)
            .put("maxDownloadBytes", Config.getMaxDownloadBytes(context))
            .put("totalBytesServed", downloadManager.getTotalBytesServed())
            .put("active", JSONArray().apply {
                downloads.take(5).forEach { d ->
                    put(JSONObject()
                        .put("filename", d.filename)
                        .put("bytes", d.bytesTransferred)
                        .put("status", d.status.name)
                        .put("speed", d.speedBps))
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
        return newFixedLengthResponse(status, "application/json", json)
    }
}
