package com.lanbrowserrelay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import fi.iki.elonen.NanoHTTPD
import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as Upstream
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val MAX_DOWNLOAD_BYTES = 100_000_000L

class MainActivity : Activity() {
    private lateinit var state: TextView
    private lateinit var address: TextView
    private lateinit var transfer: TextView
    private lateinit var logs: TextView
    private lateinit var preview: WebView
    private var previewUrl = ""
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val service = LanServerService.current
            if (service != null) {
                state.text = service.message
                address.text = service.lanUrl()
                transfer.text = service.server?.summary() ?: "Downloads idle"
                logs.text = service.logs.takeLast(10).joinToString("\n")
                val local = "http://127.0.0.1:" + service.port() + "/"
                if (previewUrl != local) { previewUrl = local; preview.loadUrl(local) }
            }
            handler.postDelayed(this, 1200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 13, 22)
        window.navigationBarColor = Color.rgb(10, 13, 22)
        val intent = Intent(this, LanServerService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setBackgroundColor(Color.rgb(10, 13, 22))
        }
        root.addView(label("LAN BROWSER RELAY", 25, Color.WHITE, true))
        root.addView(label("Browse on your phone. TV relays through Ethernet.", 14, 0xffaeb8cb.toInt(), false))
        state = label("Starting service…", 16, 0xfff0bf5a.toInt(), true)
        address = label("Finding LAN address…", 21, 0xff77e4c0.toInt(), true)
        transfer = label("Downloads idle", 14, Color.WHITE, false)
        root.addView(state); root.addView(address); root.addView(transfer)
        root.addView(label("LIVE LOG", 12, 0xff77e4c0.toInt(), true))
        val scroll = ScrollView(this)
        logs = label("Waiting for server…", 12, 0xffd5dbea.toInt(), false)
        scroll.addView(logs)
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(90)))
        root.addView(label("LOCAL WEB PREVIEW", 12, 0xff77e4c0.toInt(), true))
        preview = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            clearCache(true); clearHistory()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host != "127.0.0.1" || request.url.port !in 8080..8090
            }
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
    private fun label(s: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = s; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}

class LanServerService : Service() {
    @Volatile var server: RelayServer? = null
        private set
    @Volatile var address = "Finding LAN address…"
        private set
    @Volatile var message = "Starting service…"
        private set
    val logs = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun onCreate() {
        super.onCreate()
        current = this
        try {
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
            addLog("App caches cleared")
        } catch (e: Exception) { addLog("Cache warning: " + e.message) }
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("lanrelay", "LAN Relay", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "lanrelay") else Notification.Builder(this)
        val n = builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("LAN Browser Relay").setContentText("LAN server running").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        if (server == null) startServer()
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun startServer() {
        address = findAddress() ?: "LAN address unavailable"
        var error: Exception? = null
        for (p in 8080..8090) {
            val candidate = RelayServer(this, p, ::addLog)
            try {
                candidate.start(5000, false); server = candidate; message = "Running"
                addLog("Listening at " + lanUrl()); return
            } catch (e: Exception) { error = e; try { candidate.stop() } catch (_: Exception) {} }
        }
        message = "Server failed: " + (error?.message ?: "no free port"); addLog(message)
    }
    private fun findAddress(): String? = try {
        val nets = Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.lowercase().startsWith("eth")) 0 else 1 }
        nets.forEach { net -> Collections.list(net.inetAddresses).forEach { ip ->
            if (ip is Inet4Address && !ip.isLoopbackAddress && !ip.isLinkLocalAddress) return ip.hostAddress
        } }
        null
    } catch (_: Exception) { null }
    fun addLog(s: String) {
        logs.add((System.currentTimeMillis() % 100000).toString() + ": " + s)
        while (logs.size > 80) logs.removeAt(0)
        android.util.Log.i("LANBrowserRelay", s)
    }
    fun lanUrl() = "http://" + address + ":" + port()
    fun port() = server?.listeningPort ?: 8080
    override fun onDestroy() { server?.stop(); server = null; if (current === this) current = null; super.onDestroy() }
    companion object { @Volatile var current: LanServerService? = null; private set }
}

object UrlPolicy {
    fun parse(raw: String): Result<HttpUrl> {
        val u = raw.trim().toHttpUrlOrNull() ?: return Result.failure(IllegalArgumentException("Invalid URL"))
        if (u.scheme != "http" && u.scheme != "https") return Result.failure(IllegalArgumentException("Only HTTP(S) is supported"))
        if (u.username.isNotEmpty() || u.password.isNotEmpty()) return Result.failure(SecurityException("URL credentials blocked"))
        val h = u.host.lowercase(Locale.ROOT)
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal") || h == "metadata.google.internal")
            return Result.failure(SecurityException("Local host blocked"))
        if (h.contains(':') || h.matches(Regex("[0-9.]+"))) {
            val ip = try { InetAddress.getByName(h) } catch (_: Exception) { return Result.failure(IllegalArgumentException("Invalid IP")) }
            if (privateAddress(ip)) return Result.failure(SecurityException("Private IP blocked"))
        }
        return Result.success(u)
    }
    fun validate(raw: String): Result<HttpUrl> {
        val parsed = parse(raw); if (parsed.isFailure) return parsed
        return try { val u = parsed.getOrThrow(); resolvePublic(u.host); Result.success(u) }
        catch (e: Exception) { Result.failure(SecurityException("Blocked destination: " + (e.message ?: "DNS failed"))) }
    }
    fun resolvePublic(host: String): List<InetAddress> {
        val h = host.lowercase(Locale.ROOT)
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".internal")) throw UnknownHostException("Local hostname blocked")
        val ips = InetAddress.getAllByName(h).toList()
        if (ips.isEmpty() || ips.any(::privateAddress)) throw UnknownHostException("Private DNS result blocked")
        return ips
    }
    fun privateAddress(ip: InetAddress): Boolean {
        if (ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress || ip.isMulticastAddress) return true
        val b = ip.address
        if (ip is Inet4Address) {
            val a=b[0].toInt() and 255; val s=b[1].toInt() and 255; val t=b[2].toInt() and 255
            return a==0 || a==10 || a==127 || (a==100 && s in 64..127) || (a==169 && s==254) ||
                (a==172 && s in 16..31) || (a==192 && s==168) || (a==192 && s==0 && t in listOf(0,2)) ||
                (a==198 && s in 18..19) || (a==198 && s==51 && t==100) || (a==203 && s==0 && t==113) || a>=224
        }
        if (ip is Inet6Address) { val a=b[0].toInt() and 255; val s=b[1].toInt() and 255; return (a and 254)==252 || (a==254 && (s and 192)==128) || a==255 }
        return true
    }
    fun filename(raw: String?): String {
        val decoded = raw?.let { try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it } }.orEmpty()
        return decoded.replace(Regex("[\\\\\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').take(180).ifBlank { "download.bin" }
    }
}

class BoundedInputStream(
    input: InputStream, private val limit: Long, private val onBytes: (Long)->Unit = {},
    private val onEof: (Long)->Unit = {}, private val onLimit: (Long)->Unit = {},
    private val onEarlyClose: (Long)->Unit = {}
) : FilterInputStream(input) {
    var count = 0L; private set
    private var terminal = false; private var closed = false
    init { require(limit > 0) }
    override fun read(): Int { val b=ByteArray(1); val n=read(b,0,1); return if(n<0)-1 else b[0].toInt() and 255 }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if(off<0||len<0||off>b.size-len) throw IndexOutOfBoundsException()
        if(len==0)return 0
        if(closed||terminal)return -1
        if(count>=limit){val extra=super.read();terminal=true;if(extra<0){onEof(count);return -1};onLimit(count);throw IOException("Stream exceeds "+limit+" bytes")}
        val n=super.read(b,off,minOf(len.toLong(),limit-count).toInt())
        if(n<0){terminal=true;onEof(count);return -1}
        if(n>0){count+=n;onBytes(count)}
        return n
    }
    override fun close(){if(closed)return;closed=true;if(!terminal){terminal=true;onEarlyClose(count)};super.close()}
}

class RelayServer(private val app: Context, port: Int, private val log: (String)->Unit) : NanoHTTPD(port) {
    data class Transfer(val id:String,val url:String,@Volatile var filename:String="Preparing",@Volatile var bytes:Long=0,@Volatile var length:Long?=null,@Volatile var speed:Long=0,@Volatile var status:String="STARTING",@Volatile var error:String?=null) {
        fun json()=JSONObject().put("id",id).put("url",url).put("filename",filename).put("bytes",bytes).put("length",length?:JSONObject.NULL).put("speed",speed).put("status",status).put("error",error?:JSONObject.NULL)
    }
    private val transfers=ConcurrentHashMap<String,Transfer>();private val calls=ConcurrentHashMap<String,Call>();private val slots=Semaphore(3);private val total=AtomicLong()
    private val client=OkHttpClient.Builder().dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = UrlPolicy.resolvePublic(hostname) }).connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    override fun serve(s:IHTTPSession):Response {
        if(s.method==Method.OPTIONS)return cors(txt(Response.Status.OK,"text/plain",""))
        if(s.method!=Method.GET&&s.method!=Method.HEAD)return cors(txt(Response.Status.METHOD_NOT_ALLOWED,"text/plain","GET only"))
        val out=try{when(s.uri){"/","/index.html"->txt(Response.Status.OK,"text/html; charset=utf-8",HOME_HTML);"/api/status"->txt(Response.Status.OK,"application/json",statusJson());"/api/cancel"->cancel(s);"/browse"->browse(s);"/resource"->resource(s);"/download"->download(s);else->txt(Response.Status.NOT_FOUND,"text/plain","Not found")}}
        catch(e:Exception){log("Request failed: "+e.message);txt(Response.Status.INTERNAL_ERROR,"text/plain","Request failed: "+(e.message?:"unknown"))}
        log(s.method.toString()+" "+s.uri);return cors(out)
    }
    private fun browse(s:IHTTPSession):Response {
        val raw=s.parms["url"]?:return txt(Response.Status.BAD_REQUEST,"text/plain","Missing URL");val (_,r)=open(raw)
        r.use { if(!it.isSuccessful)return txt(Response.Status.lookup(it.code)?:Response.Status.INTERNAL_ERROR,"text/plain","Upstream HTTP "+it.code)
            val b=it.body?:return txt(Response.Status.INTERNAL_ERROR,"text/plain","Empty response");val data=readBounded(b.byteStream(),5_000_000);val mime=it.header("Content-Type")?:"application/octet-stream"
            return if(mime.contains("text/html",true))txt(Response.Status.OK,"text/html; charset=utf-8",rewrite(String(data,Charsets.UTF_8),it.request.url))else bytes(Response.Status.OK,mime,data) }
    }
    private fun resource(s:IHTTPSession):Response {
        val raw=s.parms["url"]?:return txt(Response.Status.BAD_REQUEST,"text/plain","Missing URL");val (_,r)=open(raw)
        r.use { if(!it.isSuccessful)return txt(Response.Status.lookup(it.code)?:Response.Status.INTERNAL_ERROR,"text/plain","Resource HTTP "+it.code)
            val b=it.body?:return txt(Response.Status.INTERNAL_ERROR,"text/plain","Empty resource");val data=readBounded(b.byteStream(),5_000_000);return bytes(Response.Status.OK,it.header("Content-Type")?:"application/octet-stream",data) }
    }
    private fun download(s:IHTTPSession):Response {
        val raw=s.parms["url"]?:return txt(Response.Status.BAD_REQUEST,"text/plain","Missing URL")
        if(!slots.tryAcquire())return txt(Response.Status.SERVICE_UNAVAILABLE,"text/plain","Three downloads already active")
        val id=s.parms["id"]?.takeIf{it.matches(Regex("[A-Za-z0-9_-]{1,80}"))}?:UUID.randomUUID().toString();val t=Transfer(id,raw)
        if(transfers.putIfAbsent(id,t)!=null){slots.release();return txt(Response.Status.CONFLICT,"text/plain","Duplicate ID")}
        var upstream:Upstream?=null;var released=false;fun release(){if(!released){released=true;slots.release()}}
        try {
            val pair=open(raw);val call=pair.first;val r=pair.second;upstream=r;calls[id]=call
            if(!r.isSuccessful){t.status="FAILED";t.error="Upstream HTTP "+r.code;r.close();calls.remove(id);release();return txt(Response.Status.lookup(r.code)?:Response.Status.INTERNAL_ERROR,"text/plain",t.error!!)}
            val body=r.body?:throw IOException("Empty upstream body");val length=body.contentLength().takeIf{it>=0};t.length=length
            if(length!=null&&length>MAX_DOWNLOAD_BYTES){t.status="LIMIT_EXCEEDED";t.error="File exceeds 100,000,000 bytes";r.close();calls.remove(id);release();return txt(Response.Status.lookup(413)?:Response.Status.BAD_REQUEST,"application/json",JSONObject().put("error",t.error).toString())}
            t.filename=UrlPolicy.filename(r.header("Content-Disposition")?.substringAfter("filename=")?.trim('"')?:r.request.url.encodedPath.substringAfterLast('/'));t.status="STREAMING"
            var at=System.currentTimeMillis();var last=0L
            val stream=BoundedInputStream(body.byteStream(),MAX_DOWNLOAD_BYTES,onBytes={n->val old=t.bytes;t.bytes=n;total.addAndGet((n-old).coerceAtLeast(0));val now=System.currentTimeMillis();if(now-at>=500){t.speed=((n-last)*1000)/(now-at).coerceAtLeast(1);at=now;last=n}},
                onEof={n->t.bytes=n;t.status=if(length!=null&&n<length)"FAILED" else "COMPLETED";if(t.status=="FAILED")t.error="Truncated upstream";t.speed=0;calls.remove(id);release();log("Download "+t.status+" "+t.filename)},
                onLimit={n->t.bytes=n;t.status="LIMIT_EXCEEDED";t.error="100,000,000 byte limit exceeded";call.cancel();calls.remove(id);release()},
                onEarlyClose={n->t.bytes=n;if(t.status=="STREAMING"){t.status="CANCELLED";t.error="Client disconnected"};call.cancel();calls.remove(id);release()})
            val out=newChunkedResponse(Response.Status.OK,r.header("Content-Type")?:"application/octet-stream",stream)
            out.addHeader("Content-Disposition","attachment; filename=\""+t.filename+"\"; filename*=UTF-8''"+URLEncoder.encode(t.filename,"UTF-8").replace("+","%20"));out.addHeader("X-Download-Id",id);out.addHeader("X-Content-Type-Options","nosniff");return out
        } catch(e:Exception){try{upstream?.close()}catch(_:Exception){};calls.remove(id);t.status="FAILED";t.error=e.message?:"Download failed";release();return txt(Response.Status.INTERNAL_ERROR,"text/plain",t.error!!)}
    }
    private fun cancel(s:IHTTPSession):Response {val id=s.parms["id"]?:return txt(Response.Status.BAD_REQUEST,"application/json","""{"error":"Missing id"}""");val c=calls[id]?:return txt(Response.Status.NOT_FOUND,"application/json","""{"ok":false}""");transfers[id]?.let{it.status="CANCELLED";it.error="Cancelled by user"};c.cancel();return txt(Response.Status.OK,"application/json","""{"ok":true}""")}
    private fun open(raw:String):Pair<Call,Upstream>{var u=UrlPolicy.validate(raw).getOrElse{throw IOException(it.message?:"Destination blocked")};repeat(6){i->val c=client.newCall(Request.Builder().url(u).header("User-Agent","LANBrowserRelay/2.0").build());val r=c.execute();if(r.code in 300..399){val loc=r.header("Location")?:return c to r;if(i==5){r.close();throw IOException("Too many redirects")};val next=u.resolve(loc)?:run{r.close();throw IOException("Bad redirect")};r.close();u=UrlPolicy.validate(next.toString()).getOrElse{throw IOException("Redirect blocked: "+it.message)}}else return c to r};throw IOException("Too many redirects")}
    private fun rewrite(html:String,page:HttpUrl):String {var out=Regex("(?is)(<a\\b[^>]*?\\bhref\\s*=\\s*)(['\"])(.*?)\\2").replace(html){m->val u=resolve(page,m.groupValues[3]);if(u==null)m.value else m.groupValues[1]+m.groupValues[2]+local("/browse",u)+m.groupValues[2]};out=Regex("(?is)(<(?:img|script|link|source|video|audio)\\b[^>]*?\\b(?:src|href)\\s*=\\s*)(['\"])(.*?)\\2").replace(out){m->val u=resolve(page,m.groupValues[3]);if(u==null)m.value else m.groupValues[1]+m.groupValues[2]+local("/resource",u)+m.groupValues[2]};val meta="<meta name=\"lbr-target-url\" content=\""+page.toString().replace("&","&amp;").replace("\"","&quot;")+"\">";val head=Regex("(?i)<head[^>]*>").find(out);return if(head!=null)out.replaceRange(head.range,head.value+meta)else "<head>"+meta+"</head>"+out}
    private fun resolve(base:HttpUrl,raw:String):HttpUrl?{val v=raw.trim();if(v.isEmpty()||v.startsWith("#")||v.startsWith("data:",true)||v.startsWith("javascript:",true))return null;return base.resolve(v)?.takeIf{it.scheme=="http"||it.scheme=="https"}}
    private fun local(p:String,u:HttpUrl)=p+"?url="+URLEncoder.encode(u.toString(),"UTF-8")
    private fun readBounded(input:InputStream,limit:Int):ByteArray{input.use{src->val out=ByteArrayOutputStream();val b=ByteArray(8192);var total=0;while(true){val n=src.read(b);if(n<0)break;if(total+n>limit)throw IOException("Resource exceeds gateway limit");out.write(b,0,n);total+=n};return out.toByteArray()}}
    fun summary():String{val a=transfers.values.filter{it.status=="STARTING"||it.status=="STREAMING"};return if(a.isEmpty())"Downloads idle · "+total.get()/1_000_000+" MB relayed" else a.size.toString()+" active · "+a[0].filename+" · "+a[0].speed/1000+" KB/s"}
    private fun statusJson()=JSONObject().put("running",true).put("maxDownloadBytes",MAX_DOWNLOAD_BYTES).put("totalBytesServed",total.get()).put("transfers",JSONArray().apply{transfers.values.take(40).forEach{put(it.json())}}).toString()
    private fun txt(s:Response.Status,m:String,b:String)=newFixedLengthResponse(s,m,b)
    private fun bytes(s:Response.Status,m:String,b:ByteArray)=newFixedLengthResponse(s,m,ByteArrayInputStream(b),b.size.toLong())
    private fun cors(r:Response)=r.apply{addHeader("Access-Control-Allow-Origin","*");addHeader("Access-Control-Allow-Methods","GET, HEAD, OPTIONS");addHeader("Access-Control-Allow-Headers","Content-Type");addHeader("Cache-Control","no-store")}
    companion object { private const val MAX_REDIRECTS=5 }
}

private const val HOME_HTML = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>LAN Browser Relay</title><style>
*{box-sizing:border-box}body{margin:0;background:#0a0d16;color:#eef3ff;font:15px system-ui,sans-serif}header{padding:16px 22px;border-bottom:1px solid #27334a;display:flex;justify-content:space-between}header b{letter-spacing:.15em;color:#77e4c0}main{max-width:1400px;margin:auto;padding:14px}form{display:flex;gap:7px}button,input{height:42px;border:1px solid #27334a;border-radius:10px;background:#111827;color:#eef3ff;padding:0 12px;font:inherit}input{flex:1;min-width:0}button{cursor:pointer}section{height:55vh;min-height:280px;background:white;position:relative;border-radius:12px;overflow:hidden}iframe{width:100%;height:100%;border:0}#loading{position:absolute;right:10px;top:10px;background:#101827dd;padding:6px 10px;border-radius:18px;font-size:11px}nav{display:flex;gap:6px;overflow:auto;padding:10px 0}nav button{font-size:12px}aside{margin-top:12px;border:1px solid #27334a;background:#111827;border-radius:12px;padding:14px}small{display:block;color:#93a1ba;margin:4px 0 10px}.item{padding:8px;border-top:1px solid #27334a;font-size:12px;color:#b8c4d9}.cancel{float:right;color:#ff9aa4}footer{display:flex;justify-content:space-between;color:#93a1ba;font-size:11px;padding:12px 2px}@media(max-width:600px){main{padding:8px}form{gap:4px}button,input{padding:0 7px;height:39px}section{height:50vh}}
</style></head><body><header><b>↗ LAN RELAY</b><span id="connection">TV connected</span></header><main><form id="address-form"><button id="back" type="button">‹</button><button id="forward" type="button">›</button><input id="address" placeholder="Search Google or enter a URL" autocomplete="url"><button>Go</button><button id="download" type="button">↓</button><button id="new-tab" type="button">＋</button></form><nav id="tabs"></nav><section><div id="loading">Ready</div><iframe id="page" title="Web page" referrerpolicy="no-referrer"></iframe></section><aside><b>Transfers</b><small>100 MB maximum · streamed, never stored on TV</small><div id="transfers">No downloads yet</div></aside><footer><span>LAN Browser Relay</span><span id="served">0 MB relayed</span></footer></main><script>
const el=id=>document.getElementById(id),addr=el("address"),frame=el("page"),tabs=el("tabs"),list=el("transfers");let history=[],pos=-1;
function normalize(v){v=String(v||"").trim();if(!v)return"";if(/^https?:\\/\\//i.test(v))return v;if(/^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(\\/.*)?$/i.test(v))return"https://"+v;return"https://www.google.com/search?q="+encodeURIComponent(v)}
function local(u){return"/browse?url="+encodeURIComponent(u)}
function go(v,replace){const u=normalize(v);if(!u)return;if(replace&&pos>=0)history[pos]=u;else{history=history.slice(0,pos+1);history.push(u);pos++}addr.value=u;frame.src=local(u);el("loading").textContent="Loading through TV…";render()}
function render(){tabs.replaceChildren();history.forEach((u,i)=>{const b=document.createElement("button");b.textContent="Tab "+(i+1);b.style.borderColor=i===pos?"#77e4c0":"#27334a";b.onclick=()=>{pos=i;addr.value=history[i];frame.src=local(history[i]);render()};tabs.append(b)});el("back").disabled=pos<=0;el("forward").disabled=pos>=history.length-1}
el("address-form").onsubmit=e=>{e.preventDefault();go(addr.value,false)};el("back").onclick=()=>{if(pos>0){pos--;addr.value=history[pos];frame.src=local(history[pos]);render()}};el("forward").onclick=()=>{if(pos<history.length-1){pos++;addr.value=history[pos];frame.src=local(history[pos]);render()}};el("new-tab").onclick=()=>go("https://www.google.com",false);el("download").onclick=()=>{const u=prompt("Paste the direct file URL:",addr.value);if(u){const id="dl_"+Date.now();window.open("/download?url="+encodeURIComponent(normalize(u))+"&id="+id,"_blank","noopener");setTimeout(poll,250)}};
frame.onload=()=>{el("loading").textContent="Loaded";try{const m=frame.contentDocument.querySelector('meta[name="lbr-target-url"]');if(m)addr.value=m.content}catch(e){}};
async function poll(){try{const d=await(await fetch("/api/status",{cache:"no-store"})).json();el("connection").textContent="TV connected";el("served").textContent=(d.totalBytesServed/1e6).toFixed(1)+" MB relayed";list.replaceChildren();if(!d.transfers.length)list.textContent="No downloads yet";d.transfers.slice().reverse().forEach(t=>{const row=document.createElement("div");row.className="item";row.textContent=t.filename+" · "+t.status+" · "+(t.bytes/1e6).toFixed(2)+" MB"+(t.error?" · "+t.error:"");if(t.status==="STREAMING"){const c=document.createElement("button");c.className="cancel";c.textContent="Cancel";c.onclick=()=>fetch("/api/cancel?id="+encodeURIComponent(t.id)).then(poll);row.append(c)}list.append(row)})}catch(e){el("connection").textContent="Reconnecting…"}}
go("https://www.google.com",false);setInterval(poll,1000);
</script></body></html>"""
