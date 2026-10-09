package com.lanbrowserrelay.server

import android.content.Context
import android.util.Log
import com.lanbrowserrelay.Config
import com.lanbrowserrelay.download.DownloadManager
import com.lanbrowserrelay.download.Transfer
import com.lanbrowserrelay.gateway.HtmlGateway
import com.lanbrowserrelay.security.UrlValidator
import fi.iki.elonen.NanoHTTPD
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as UpstreamResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LanHttpServer(
    private val context: Context,
    port: Int,
    private val downloads: DownloadManager,
    private val logSink: (String) -> Unit
) : NanoHTTPD(port) {
    private val gateway = HtmlGateway()
    private val clients = AtomicInteger(0)
    private val logs = CopyOnWriteArrayList<String>()
    private val calls = ConcurrentHashMap<String, Call>()

    fun connectedClients() = clients.get()
    fun recentLogs() = logs.takeLast(50)

    private fun log(message: String) {
        logs.add("${System.currentTimeMillis() % 100000}: $message")
        while (logs.size > MAX_LOGS) logs.removeAt(0)
        logSink(message)
        Log.i(TAG, message)
    }

    override fun serve(session: IHTTPSession): Response {
        clients.incrementAndGet()
        try {
            log("${session.method} ${session.uri}")
            return when {
                session.method == Method.OPTIONS -> newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                session.uri == "/" || session.uri == "/index.html" -> asset("web/index.html", "text/html; charset=utf-8")
                session.uri == "/css/styles.css" -> asset("web/css/styles.css", "text/css; charset=utf-8")
                session.uri == "/js/app.js" -> asset("web/js/app.js", "application/javascript; charset=utf-8")
                session.uri == "/api/status" -> json(statusJson())
                session.uri == "/api/logs" -> json(JSONObject().put("logs", JSONArray(recentLogs())).toString())
                session.uri == "/api/cancel" && session.method == Method.GET -> cancel(session)
                session.uri == "/api/download" && session.method == Method.GET -> download(session)
                session.uri == "/browse" && session.method == Method.GET -> browse(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }.apply {
                addHeader("Cache-Control", "no-store")
                addHeader("X-Content-Type-Options", "nosniff")
            }
        } catch (e: Exception) {
            log("Request failed: ${e.message}")
            return fixed(Response.Status.INTERNAL_ERROR, e.message ?: "Request failed")
        } finally {
            clients.decrementAndGet()
        }
    }

    private fun browse(session: IHTTPSession): Response {
        val url = session.parms["url"] ?: return fixed(Response.Status.BAD_REQUEST, "Missing url")
        val result = gateway.fetch(url)
        return newFixedLengthResponse(
            Response.Status.lookup(result.statusCode) ?: Response.Status.OK,
            result.contentType, result.body.inputStream(), result.body.size.toLong()
        )
    }

    private fun download(session: IHTTPSession): Response {
        val raw = session.parms["url"] ?: return fixed(Response.Status.BAD_REQUEST, "Missing url")
        val url = UrlValidator.validate(raw).getOrElse {
            log("Download blocked: ${it.message}")
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json",
                JSONObject().put("error", it.message ?: "Blocked URL").toString())
        }.toString()
        val id = session.parms["id"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) } ?: UUID.randomUUID().toString()
        if (!downloads.begin(id, url)) return fixed(Response.Status.SERVICE_UNAVAILABLE, "Too many active downloads or duplicate ID")
        val hint = session.parms["filename"]
        log("Download started: $id")
        var call: Call? = null
        var upstream: UpstreamResponse? = null
        try {
            val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(Config.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(Config.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
            val opened = openCheckedRedirects(client, url, id)
            call = opened.first
            upstream = opened.second
            if (!upstream.isSuccessful) {
                val code = upstream.code
                upstream.close(); calls.remove(id, call)
                downloads.finish(id, Transfer.Status.FAILED, 0, "Upstream HTTP $code")
                return newFixedLengthResponse(Response.Status.lookup(code) ?: Response.Status.BAD_GATEWAY, MIME_PLAINTEXT, "Upstream HTTP $code")
            }
            val body = upstream.body ?: throw IOException("Upstream returned no body")
            val length = body.contentLength().takeIf { it >= 0 }
            if (length != null && length > Config.MAX_DOWNLOAD_BYTES) {
                upstream.close(); calls.remove(id, call)
                downloads.finish(id, Transfer.Status.LIMIT_EXCEEDED, 0, "File exceeds 100 MB")
                return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, "application/json",
                    JSONObject().put("error", "File exceeds 100,000,000 bytes (100 MB decimal)").toString())
            }
            val finalUrl = upstream.request.url.toString()
            val filename = UrlValidator.safeFilename(hint?.takeIf { it.isNotBlank() }
                ?: UrlValidator.filenameFrom(upstream.header("Content-Disposition"), finalUrl))
            val type = upstream.header("Content-Type") ?: "application/octet-stream"
            val source = body.byteStream()
            downloads.update(id, filename, length, 0, 0)

            val relay = object : java.io.InputStream() {
                private var sent = 0L
                private var closed = false
                private var done = false
                private var speedAt = System.currentTimeMillis()
                private var speedBytes = 0L
                private var speed = 0L
                private fun finish(status: Transfer.Status, error: String? = null) {
                    if (done) return
                    done = true
                    downloads.finish(id, status, sent, error)
                    calls.remove(id, call)
                }
                private fun closeUpstream() {
                    runCatching { source.close() }
                    runCatching { upstream?.close() }
                    calls.remove(id, call)
                }
                override fun read(): Int {
                    val one = ByteArray(1)
                    val n = read(one, 0, 1)
                    return if (n == -1) -1 else one[0].toInt() and 255
                }
                override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                    if (closed) return -1
                    if (len == 0) return 0
                    try {
                        if (sent >= Config.MAX_DOWNLOAD_BYTES) {
                            val extra = source.read()
                            closed = true
                            if (extra == -1) {
                                finish(Transfer.Status.COMPLETED); closeUpstream()
                                log("Download completed: $id ($sent bytes)")
                                return -1
                            }
                            call?.cancel()
                            finish(Transfer.Status.LIMIT_EXCEEDED, "File exceeded 100,000,000 bytes")
                            closeUpstream()
                            throw IOException("Download exceeds the 100 MB limit")
                        }
                        val n = source.read(buffer, off, minOf(len.toLong(), Config.MAX_DOWNLOAD_BYTES - sent).toInt())
                        if (n == -1) {
                            val truncated = length != null && sent < length
                            finish(if (truncated) Transfer.Status.FAILED else Transfer.Status.COMPLETED,
                                if (truncated) "Upstream ended before the advertised size" else null)
                            closed = true; closeUpstream()
                            if (truncated) log("Download truncated: $id") else log("Download completed: $id ($sent bytes)")
                            return -1
                        }
                        if (n > 0) {
                            sent += n
                            val now = System.currentTimeMillis()
                            if (now - speedAt >= 500) {
                                speed = ((sent - speedBytes) * 1000L) / (now - speedAt).coerceAtLeast(1)
                                speedAt = now; speedBytes = sent
                            }
                            downloads.update(id, filename, length, sent, speed)
                        }
                        return n
                    } catch (e: IOException) {
                        if (!done) finish(if (call?.isCanceled == true) Transfer.Status.CANCELLED else Transfer.Status.FAILED,
                            if (call?.isCanceled == true) null else e.message)
                        closed = true; closeUpstream()
                        throw e
                    }
                }
                override fun close() {
                    if (!done) finish(Transfer.Status.CANCELLED, "Client disconnected or cancelled")
                    closed = true; closeUpstream()
                }
            }
            val response = newChunkedResponse(Response.Status.OK, type, relay)
            val encoded = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
            response.addHeader("Content-Disposition", "attachment; filename=\"$filename\"; filename*=UTF-8''$encoded")
            response.addHeader("X-Download-Id", id)
            return response
        } catch (e: Exception) {
            runCatching { upstream?.close() }
            calls.remove(id)
            downloads.finish(id, if (call?.isCanceled == true) Transfer.Status.CANCELLED else Transfer.Status.FAILED, 0, e.message)
            log("Download failed: ${e.message}")
            return fixed(Response.Status.BAD_GATEWAY, e.message ?: "Download failed")
        }
    }

    private fun openCheckedRedirects(client: OkHttpClient, start: String, id: String): Pair<Call, UpstreamResponse> {
        var current = start
        repeat(MAX_REDIRECTS + 1) { index ->
            UrlValidator.validate(current).getOrElse { throw IOException("Destination blocked") }
            val request = Request.Builder().url(current).header("User-Agent", "LANBrowserRelay/1.0").build()
            val call = client.newCall(request)
            calls[id] = call
            val response = call.execute()
            val location = response.header("Location")
            if (response.code !in 300..399 || location.isNullOrBlank()) {
                if (UrlValidator.validate(response.request.url.toString()).isFailure) {
                    response.close(); calls.remove(id, call); throw IOException("Redirect target blocked")
                }
                return call to response
            }
            if (index == MAX_REDIRECTS) {
                response.close(); calls.remove(id, call); throw IOException("Too many redirects")
            }
            val next = runCatching { URI(current).resolve(location).toString() }.getOrElse {
                response.close(); calls.remove(id, call); throw IOException("Invalid redirect target")
            }
            response.close(); calls.remove(id, call)
            current = UrlValidator.validate(next).getOrElse { throw IOException("Redirect target blocked") }.toString()
        }
        throw IOException("Too many redirects")
    }

    private fun cancel(session: IHTTPSession): Response {
        val id = session.parms["id"] ?: return fixed(Response.Status.BAD_REQUEST, "Missing id")
        val call = calls[id] ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
            JSONObject().put("error", "Transfer is no longer active").toString())
        downloads.cancel(id); call.cancel(); log("Cancel requested: $id")
        return json(JSONObject().put("ok", true).toString())
    }

    private fun statusJson(): String = JSONObject()
        .put("running", true)
        .put("clients", clients.get())
        .put("maxDownloadBytes", Config.MAX_DOWNLOAD_BYTES)
        .put("totalBytesServed", downloads.totalBytesServed())
        .put("downloads", JSONArray().apply {
            downloads.recent().forEach { t ->
                put(JSONObject().put("id", t.id).put("url", t.url).put("filename", t.filename)
                    .put("bytes", t.bytes).put("length", t.length ?: JSONObject.NULL)
                    .put("speed", t.speedBps).put("status", t.status.name)
                    .put("error", t.error ?: JSONObject.NULL))
            }
        }).toString()

    private fun asset(path: String, mime: String): Response = try {
        context.assets.open(path).use { input ->
            val bytes = input.readBytes()
            newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
        }
    } catch (_: Exception) { fixed(Response.Status.NOT_FOUND, "Missing app asset: $path") }

    private fun json(value: String): Response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", value)
    private fun fixed(status: Response.Status, message: String): Response = newFixedLengthResponse(status, MIME_PLAINTEXT, message)

    companion object { private const val TAG = "LanHttpServer"; private const val MAX_LOGS = 160; private const val MAX_REDIRECTS = 5 }
}
