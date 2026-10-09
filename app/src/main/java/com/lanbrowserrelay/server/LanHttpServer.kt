package com.lanbrowserrelay.server
import android.content.Context
import android.util.Log
import com.lanbrowserrelay.DownloadPolicy
import com.lanbrowserrelay.download.BoundedRelayInputStream
import com.lanbrowserrelay.download.DownloadManager
import com.lanbrowserrelay.gateway.HtmlGateway
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
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
class LanHttpServer(private val context:Context,port:Int,private val downloads:DownloadManager,private val logSink:(String)->Unit):NanoHTTPD(port){
 private val gateway=HtmlGateway();private val logs=CopyOnWriteArrayList<String>();private val calls=ConcurrentHashMap<String,Call>();private val clients=AtomicInteger()
 private fun log(s:String){val line="${System.currentTimeMillis()%100000}: $s";logs.add(line);while(logs.size>100)logs.removeAt(0);logSink(line);Log.d("LanBrowserRelay",s)}
 fun recentLogs():List<String> = logs.takeLast(40)
 fun connectedClients()=clients.get()
 override fun serve(session:IHTTPSession):Response{
  clients.incrementAndGet()
  try{
   log("${session.method} ${session.uri}")
   return when(session.uri){
    "/", "/index.html"->asset("web/index.html","text/html; charset=utf-8")
    "/styles.css"->asset("web/styles.css","text/css; charset=utf-8")
    "/app.js"->asset("web/app.js","application/javascript; charset=utf-8")
    "/api/status"->json(status())
    "/api/logs"->json(JSONObject().put("logs",JSONArray(recentLogs())).toString())
    "/api/cancel"->cancel(session.parms["id"])
    "/browse"->browse(session.parms["url"])
    "/api/download"->download(session)
    else->newFixedLengthResponse(Response.Status.NOT_FOUND,MIME_PLAINTEXT,"Not found")
   }.apply{addHeader("Cache-Control","no-store");addHeader("X-Content-Type-Options","nosniff")}
  }finally{clients.decrementAndGet()}
 }
 private fun browse(url:String?):Response{
  if(url.isNullOrBlank())return newFixedLengthResponse(Response.Status.BAD_REQUEST,MIME_PLAINTEXT,"Missing URL")
  val p=gateway.fetch(url)
  return newFixedLengthResponse(Response.Status.lookup(p.status)?:Response.Status.OK,p.type,ByteArrayInputStream(p.bytes),p.bytes.size.toLong())
 }
 private fun download(session:IHTTPSession):Response{
  val raw=session.parms["url"]?:return newFixedLengthResponse(Response.Status.BAD_REQUEST,MIME_PLAINTEXT,"Missing URL")
  val safe=UrlValidator.validate(raw)
  if(safe.isFailure)return json(JSONObject().put("error",safe.exceptionOrNull()?.message).toString(),Response.Status.FORBIDDEN)
  val supplied=session.parms["id"];val id=supplied?.takeIf{it.matches(Regex("[A-Za-z0-9_-]{1,80}"))}?:UUID.randomUUID().toString()
  val name=session.parms["filename"]?.let{UrlValidator.safeFilename(it)}
  return stream(id,safe.getOrThrow().toString(),name)
 }
 private fun cancel(id:String?):Response{
  if(id.isNullOrBlank())return json(JSONObject().put("error","Missing id").toString(),Response.Status.BAD_REQUEST)
  val c=calls[id]?:return json(JSONObject().put("ok",false).put("error","No active transfer").toString(),Response.Status.NOT_FOUND)
  downloads.cancel(id);c.cancel();log("Cancel requested: $id");return json(JSONObject().put("ok",true).toString())
 }
 private fun stream(id:String,start:String,hint:String?):Response{
  if(!downloads.begin(id,start))return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE,MIME_PLAINTEXT,"Concurrent limit reached or duplicate id")
  var selectedCall:Call?=null;var upstream:Upstream?=null
  try{
   val client=OkHttpClient.Builder().dns(object : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
     UrlValidator.resolvePublicAddresses(hostname)
   }).followRedirects(false).followSslRedirects(false).connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()
   var url=start;var selected:Upstream?=null;var callSelected:Call?=null
   for(i in 0..5){
    val valid=UrlValidator.validate(url).getOrElse{throw IOException("Redirect blocked: ${it.message}")}
    val c=client.newCall(Request.Builder().url(valid).header("User-Agent","LANBrowserRelay/1.0").build());calls[id]=c;selectedCall=c
    val r=c.execute()
    if(r.code in 300..399&&!r.header("Location").isNullOrBlank()){
     if(i==5){r.close();throw IOException("Too many redirects")}
     val next=URI(url).resolve(r.header("Location")!!).toString();r.close();calls.remove(id,c)
     url=UrlValidator.validate(next).getOrElse{throw IOException("Redirect blocked: ${it.message}")}.toString()
    }else{selected=r;callSelected=c;break}
   }
   val r=selected?:throw IOException("No upstream response");val call=callSelected?:throw IOException("No upstream request")
   upstream=r;selectedCall=call
   if(!r.isSuccessful){r.close();calls.remove(id,call);downloads.finish(id,"FAILED",0,"HTTP ${r.code}");return newFixedLengthResponse(Response.Status.lookup(r.code)?:Response.Status.INTERNAL_ERROR,MIME_PLAINTEXT,"Upstream error: ${r.code}")}
   val body=r.body?:throw IOException("Empty upstream body");val length=body.contentLength().takeIf{it>=0}
   if(length!=null&&length>DownloadPolicy.MAX_BYTES){r.close();calls.remove(id,call);downloads.finish(id,"LIMIT_EXCEEDED",0,"File exceeds 100 MB limit");return json(JSONObject().put("error","File exceeds 100 MB limit").toString(),Response.Status.PAYLOAD_TOO_LARGE)}
   val filename=UrlValidator.safeFilename(hint?:UrlValidator.filenameFrom(r.header("Content-Disposition"),r.request.url.toString()))
   val type=r.header("Content-Type")?:"application/octet-stream";val input=body.byteStream()
   var transferred=0L;var until=System.currentTimeMillis();var previous=0L;var speed=0L
   fun cleanup(){try{input.close()}catch(_:Exception){};try{r.close()}catch(_:Exception){};calls.remove(id,call)}
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
   calls.remove(id);try{upstream?.close()}catch(_:Exception){}
   downloads.finish(id,if(selectedCall?.isCanceled() == true) "CANCELLED" else "FAILED",0,e.message ?: "Download failed")
   log("Download failed: ${e.message}");return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,MIME_PLAINTEXT,e.message?: "Download failed")
  }
 }
 private fun status():String=JSONObject().put("running",true).put("clients",clients.get()).put("activeCount",downloads.activeCount()).put("maxDownloadBytes",DownloadPolicy.MAX_BYTES).put("totalBytesServed",downloads.totalBytesServed()).put("downloads",JSONArray().apply{downloads.recent().forEach{d->put(JSONObject().put("id",d.id).put("url",d.url).put("filename",d.filename).put("bytes",d.bytes).put("total",d.total?:JSONObject.NULL).put("speed",d.speed).put("status",d.status).put("error",d.error?:JSONObject.NULL))}}).toString()
 private fun asset(path:String,type:String):Response=try{context.assets.open(path).use{val b=it.readBytes();newFixedLengthResponse(Response.Status.OK,type,ByteArrayInputStream(b),b.size.toLong())}}catch(_:Exception){newFixedLengthResponse(Response.Status.NOT_FOUND,MIME_PLAINTEXT,"Asset not found")}
 private fun json(s:String,status:Response.Status=Response.Status.OK)=newFixedLengthResponse(status,"application/json; charset=utf-8",s)
}
