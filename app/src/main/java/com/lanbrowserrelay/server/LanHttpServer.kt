package com.lanbrowserrelay.server
import android.content.Context
import android.util.Log
import com.lanbrowserrelay.DownloadPolicy
import com.lanbrowserrelay.download.BoundedRelayInputStream
import com.lanbrowserrelay.download.DownloadManager
import com.lanbrowserrelay.download.TransferDeadline
import com.lanbrowserrelay.security.RequestOriginPolicy
import com.lanbrowserrelay.security.UrlValidator
import fi.iki.elonen.NanoHTTPD
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as Upstream
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
class LanHttpServer(
    private val context: Context,
    private val bindAddress: String,
    private val port: Int,
    private val downloads: DownloadManager,
    private val logSink: (String) -> Unit
) : NanoHTTPD(bindAddress, port) {
    private val logs = CopyOnWriteArrayList<String>()
    private val calls = ConcurrentHashMap<String, Call>()
    private val clients = AtomicInteger()
    private val sessions = UiSessionStore(
        requestLimitPerMinute = DownloadPolicy.MAX_SESSION_REQUESTS_PER_MINUTE
    )
    private val contentPort = port + 1
    private val contentServer = RelayContentServer(context, bindAddress, contentPort, port)
    private val deadlines = ConcurrentHashMap<String, TransferDeadline>()

    fun startContent() = contentServer.start(10_000, false)
    fun isAliveWithContent() = isAlive && contentServer.isAlive
    fun contentPort() = contentPort
    private fun log(message: String) {
        val line = "${System.currentTimeMillis() % 100000}: $message"
        logs.add(line)
        while (logs.size > 100) logs.removeAt(0)
        logSink(line)
        Log.d("LanBrowserRelay", message)
    }
    fun recentLogs(): List<String> = logs.takeLast(40)
    fun connectedClients() = clients.get()
    override fun serve(session: IHTTPSession): Response {
        clients.incrementAndGet()
        try {
            val route = session.uri
            if (!RequestOriginPolicy.isExpectedHost(header(session, "host"), bindAddress, port)) {
                return responseError(Response.Status.BAD_REQUEST, "Invalid Host")
            }
            log("${session.method} $route")
            if (route in setOf("/api/download", "/api/cancel") && session.method == Method.POST) {
                val length = header(session, "content-length")?.toLongOrNull()
                if (length == null || length !in 1..20_000) {
                    return responseError(Response.Status.PAYLOAD_TOO_LARGE, "Invalid request body size")
                }
                try { session.parseBody(HashMap()) }
                catch (_: Exception) { return responseError(Response.Status.BAD_REQUEST, "Invalid request body") }
            }

            val result = when (route) {
                "/", "/index.html" -> if (session.method == Method.GET) index(session) else methodNotAllowed()
                "/styles.css" -> staticGet(session, "web/styles.css", "text/css; charset=utf-8")
                "/app.js" -> staticGet(session, "web/app.js", "application/javascript; charset=utf-8")
                "/api/status" -> if (session.method == Method.GET) {
                    val owner = authorize(session, header(session, "x-relay-csrf"), requireOrigin = false)
                    if (owner == null) unauthorized() else json(status(owner))
                } else methodNotAllowed()
                "/api/logs" -> if (session.method == Method.GET) {
                    val owner = authorize(session, header(session, "x-relay-csrf"), requireOrigin = false)
                    if (owner == null) unauthorized() else json(JSONObject().put("logs", JSONArray(recentLogs())).toString())
                } else methodNotAllowed()
                "/api/cancel" -> if (session.method == Method.POST) {
                    val owner = authorize(session, header(session, "x-relay-csrf"), requireOrigin = true)
                    if (owner == null) unauthorized() else cancel(session.parms["id"], owner.cookie)
                } else methodNotAllowed()
                "/api/download" -> if (session.method == Method.POST) {
                    val owner = authorize(session, session.parms["_csrf"], requireOrigin = true)
                    if (owner == null) unauthorized() else download(session, owner.cookie)
                } else methodNotAllowed()
                else -> responseError(Response.Status.NOT_FOUND, "Not found")
            }
            result.addHeader("Cache-Control", "no-store")
            result.addHeader("X-Content-Type-Options", "nosniff")
            result.addHeader("Referrer-Policy", "no-referrer")
            if (route == "/" || route == "/index.html") {
                result.addHeader("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; " +
                        "img-src 'self' data:; frame-src 'self' http://$bindAddress:$contentPort; " +
                        "form-action 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'self'")
            }
            return result
        } finally { clients.decrementAndGet() }
    }

    private fun index(session: IHTTPSession): Response {
        val current = sessions.resolveOrCreate(cookie(session))
        val response = asset("web/index.html", "text/html; charset=utf-8", current.session.csrf)
        if (current.created) {
            response.addHeader("Set-Cookie", "lbr_session=${current.session.cookie}; Path=/; HttpOnly; SameSite=Strict")
        }
        return response
    }

    private fun staticGet(session: IHTTPSession, path: String, type: String): Response =
        if (session.method == Method.GET) asset(path, type) else methodNotAllowed()

    private fun authorize(session: IHTTPSession, csrf: String?, requireOrigin: Boolean): UiSessionStore.Session? {
        val origin = header(session, "origin")
        if (!RequestOriginPolicy.isAllowedOrigin(origin, bindAddress, port, requireOrigin)) return null
        if (!RequestOriginPolicy.isSameOriginFetch(header(session, "sec-fetch-site"))) return null
        if (!RequestOriginPolicy.isExpectedHost(header(session, "host"), bindAddress, port)) return null
        return sessions.authorize(cookie(session), csrf)
    }

    private fun cookie(session: IHTTPSession): String? = header(session, "cookie")
        ?.split(';')
        ?.asSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("lbr_session=") }
        ?.substringAfter('=')
        ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }

    private fun header(session: IHTTPSession, name: String): String? =
        session.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun methodNotAllowed() = responseError(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed")
    private fun unauthorized() = json(JSONObject().put("error", "Forbidden").toString(), Response.Status.FORBIDDEN)
    private fun responseError(status: Response.Status, message: String) = newFixedLengthResponse(status, MIME_PLAINTEXT, message)
    private fun download(session: IHTTPSession, ownerSession: String): Response {
        val raw = session.parms["url"] ?: return responseError(Response.Status.BAD_REQUEST, "Missing URL")
        if (raw.length > 8192) return responseError(Response.Status.BAD_REQUEST, "URL too long")
        val safe = UrlValidator.validate(raw)
        if (safe.isFailure) return json(JSONObject().put("error", safe.exceptionOrNull()?.message).toString(), Response.Status.FORBIDDEN)
        val supplied = session.parms["id"]
        val id = supplied?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) } ?: UUID.randomUUID().toString()
        val name = session.parms["filename"]?.take(512)?.let(UrlValidator::safeFilename)
        return stream(id, safe.getOrThrow().toString(), name, ownerSession)
    }

    private fun cancel(id: String?, ownerSession: String): Response {
        if (id.isNullOrBlank() || id.length > 80) return json(JSONObject().put("error", "Missing id").toString(), Response.Status.BAD_REQUEST)
        if (!downloads.cancel(id, ownerSession)) {
            return json(JSONObject().put("ok", false).put("error", "No active transfer").toString(), Response.Status.NOT_FOUND)
        }
        calls[id]?.cancel()
        log("Cancel requested for an owned transfer")
        return json(JSONObject().put("ok", true).toString())
    }
    private fun stream(id: String, start: String, hint: String?, ownerSession: String): Response {
        if (!downloads.begin(id, start, ownerSession)) return responseError(Response.Status.SERVICE_UNAVAILABLE, "Concurrent limit reached or duplicate id")
        val deadline = TransferDeadline(DownloadPolicy.MAX_TRANSFER_DURATION_MS) {
            downloads.finish(id, "FAILED", 0, "Transfer exceeded its absolute time limit")
            calls[id]?.cancel()
            deadlines.remove(id)
        }
        deadlines[id] = deadline
        fun stopDeadline() { deadline.close(); deadlines.remove(id, deadline) }
  var selectedCall:Call?=null;var upstream:Upstream?=null
  try{
   val client=OkHttpClient.Builder().dns(object : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
     UrlValidator.resolvePublicAddresses(hostname)
   }).followRedirects(false).followSslRedirects(false).connectTimeout(15,TimeUnit.SECONDS).readTimeout(DownloadPolicy.MAX_IDLE_READ_MS,TimeUnit.MILLISECONDS).callTimeout(DownloadPolicy.MAX_TRANSFER_DURATION_MS,TimeUnit.MILLISECONDS).build()
   var url=start;var selected:Upstream?=null;var callSelected:Call?=null
   for(i in 0..5){
                if (!downloads.isActive(id)) throw IOException("Transfer no longer active")
                val valid = UrlValidator.validate(url).getOrElse { throw IOException("Redirect blocked: ${it.message}") }
                val c = client.newCall(Request.Builder().url(valid).header("User-Agent", "LANBrowserRelay/1.0").build())
                calls[id] = c
                selectedCall = c
                if (!downloads.isActive(id)) { c.cancel(); throw IOException("Transfer no longer active") }
                val r = c.execute()
    if(r.code in 300..399&&!r.header("Location").isNullOrBlank()){
     if(i==5){r.close();throw IOException("Too many redirects")}
     val next=URI(url).resolve(r.header("Location")!!).toString();r.close();calls.remove(id,c)
     url=UrlValidator.validate(next).getOrElse{throw IOException("Redirect blocked: ${it.message}")}.toString()
    }else{selected=r;callSelected=c;break}
   }
   val r=selected?:throw IOException("No upstream response");val call=callSelected?:throw IOException("No upstream request")
   upstream=r;selectedCall=call
   if(!r.isSuccessful){r.close();calls.remove(id,call);downloads.finish(id,"FAILED",0,"HTTP ${r.code}");deadline.close();deadlines.remove(id,deadline);return newFixedLengthResponse(Response.Status.lookup(r.code)?:Response.Status.INTERNAL_ERROR,MIME_PLAINTEXT,"Upstream error: ${r.code}")}
   val body=r.body?:throw IOException("Empty upstream body");val length=body.contentLength().takeIf{it>=0}
   if(length!=null&&length>DownloadPolicy.MAX_BYTES){r.close();calls.remove(id,call);downloads.finish(id,"LIMIT_EXCEEDED",0,"File exceeds 100 MB limit");deadline.close();deadlines.remove(id,deadline);return json(JSONObject().put("error","File exceeds 100 MB limit").toString(),Response.Status.PAYLOAD_TOO_LARGE)}
   val filename=UrlValidator.safeFilename(hint?:UrlValidator.filenameFrom(r.header("Content-Disposition"),r.request.url.toString()))
   val type=safeMediaType(r.header("Content-Type"));val input=body.byteStream()
   var transferred=0L;var until=System.currentTimeMillis();var previous=0L;var speed=0L
   fun cleanup(){deadline.close();deadlines.remove(id,deadline);try{input.close()}catch(_:Exception){};try{r.close()}catch(_:Exception){};calls.remove(id,call)}
   val transfer=BoundedRelayInputStream(
    input=input,
    limitBytes=DownloadPolicy.MAX_BYTES,
    expectedLength=length,
    onBytes={n->
     transferred=n;val now=System.currentTimeMillis()
     if(now-until>=500){speed=((transferred-previous)*1000L)/(now-until).coerceAtLeast(1);until=now;previous=transferred}
     downloads.progress(id,filename,length,transferred,speed)
    },
    onComplete={n,truncated->
     transferred=n
     downloads.finish(id,if(truncated)"FAILED" else "COMPLETED",n,if(truncated)"Upstream ended early" else null)
calls.remove(id,call);cleanup()
       val outcome=if(truncated)"failed (truncated)" else "complete"
       log("Download $outcome: $filename ($n bytes)")
    },
    onLimitExceeded={n->
     transferred=n
     downloads.finish(id,"LIMIT_EXCEEDED",n,"File exceeds 100 MB limit")
     calls.remove(id,call);call.cancel();cleanup()
     log("Limit exceeded: $filename ($n bytes)")
    },
    onDisconnected={n->
     transferred=n
     downloads.finish(id,if(call.isCanceled())"CANCELLED" else "FAILED",n,if(call.isCanceled())"Cancelled by user" else "Client disconnected")
     calls.remove(id,call);call.cancel();cleanup()
     log("Transfer cancelled or disconnected: $filename ($n bytes)")
    },
    onError={n,message->
     transferred=n
     downloads.finish(id,if(call.isCanceled())"CANCELLED" else "FAILED",n,message)
     calls.remove(id,call);call.cancel();cleanup()
    },
    abort={call.cancel()}
   )
   val output=if(length!=null&&length<DownloadPolicy.MAX_BYTES)newFixedLengthResponse(Response.Status.OK,type,transfer,length)else newChunkedResponse(Response.Status.OK,type,transfer)
   val enc=java.net.URLEncoder.encode(filename,"UTF-8").replace("+","%20")
   output.addHeader("Content-Disposition","attachment; filename=\"$filename\"; filename*=UTF-8''$enc");output.addHeader("X-Download-Id",id);return output
  }catch(e:Exception){
   deadline.close();deadlines.remove(id,deadline);selectedCall?.let{calls.remove(id,it)};try{upstream?.close()}catch(_:Exception){}
   downloads.finish(id,if(selectedCall?.isCanceled() == true) "CANCELLED" else "FAILED",0,e.message?:"Download failed")
   log("Download failed: ${e.message?.take(160) ?: "I/O error"}");return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,MIME_PLAINTEXT,"Download failed")
  }
 }
    private fun status(ownerSession: String): String = JSONObject()
        .put("running", isAliveWithContent())
        .put("clients", clients.get())
        .put("activeCount", downloads.activeCount(ownerSession))
        .put("maxDownloadBytes", DownloadPolicy.MAX_BYTES)
        .put("totalBytesServed", downloads.totalBytesServed())
        .put("downloads", JSONArray().apply {
            downloads.recent(ownerSession).forEach { transfer ->
                put(JSONObject()
                    .put("id", transfer.id)
                    .put("filename", transfer.filename)
                    .put("bytes", transfer.bytes)
                    .put("total", transfer.total ?: JSONObject.NULL)
                    .put("speed", transfer.speed)
                    .put("status", transfer.status)
                    .put("error", transfer.error ?: JSONObject.NULL))
            }
        }).toString()

    private fun asset(path: String, type: String, csrf: String? = null): Response = try {
        val source = context.assets.open(path).use { it.readBytes() }
        val bytes = if (path == "web/index.html") {
            String(source, Charsets.UTF_8)
                .replace("__RELAY_CSRF_TOKEN__", csrf.orEmpty())
                .replace("__RELAY_CONTENT_PORT__", contentPort.toString())
                .toByteArray(Charsets.UTF_8)
        } else source
        newFixedLengthResponse(Response.Status.OK, type, ByteArrayInputStream(bytes), bytes.size.toLong())
    } catch (_: Exception) {
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Asset not found")
    }
    private fun safeMediaType(raw: String?): String {
        val media = raw?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return if (media.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))) media else "application/octet-stream"
    }

    fun shutdown() {
        deadlines.values.forEach(TransferDeadline::close)
        deadlines.clear()
        calls.forEach { (id, call) -> downloads.cancel(id); call.cancel() }
        try { contentServer.shutdown() } catch (_: Exception) { }
        stop()
    }

    private fun json(s: String, status: Response.Status = Response.Status.OK) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", s)
}
