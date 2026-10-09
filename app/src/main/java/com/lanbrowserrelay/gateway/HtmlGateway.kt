package com.lanbrowserrelay.gateway
import com.lanbrowserrelay.security.UrlValidator
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
class HtmlGateway(private val maxBytes:Int=5_000_000){
 private val client=OkHttpClient.Builder().dns(object : Dns {
  override fun lookup(hostname: String): List<InetAddress> =
   UrlValidator.resolvePublicAddresses(hostname)
 }).followRedirects(false).followSslRedirects(false).connectTimeout(15,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS).build()
 data class Page(val type:String,val bytes:ByteArray,val status:Int,val url:String)
 fun fetch(raw:String):Page{
  val safe=UrlValidator.validate(raw).getOrElse{return error(403,"Blocked URL",it.message?: "Invalid URL",raw)}
  return try{openRedirects(safe.toString()).use{r->
   val url=r.request.url.toString();val type=safeMediaType(r.header("Content-Type"));val body=r.body?.byteStream()
   val bytes=if(body==null)ByteArray(0)else body.use{readBounded(it)}?:return error(413,"Page too large","Gateway view limit is 5 MB. Use Download for files.",url)
   if(type.contains("text/html",true)||type.contains("application/xhtml",true)){
    val h=rewrite(String(bytes,Charsets.UTF_8),url);Page("text/html; charset=utf-8",h.toByteArray(Charsets.UTF_8),r.code,url)
   }else Page(type,bytes,r.code,url)
  }}catch(e:Exception){error(502,"Could not load page",e.message?: "Network error",raw)}
 }
 private fun openRedirects(initial:String):Response{
  var url=initial
  repeat(6){i->
   val req=Request.Builder().url(url).header("User-Agent","Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36").header("Accept","text/html,application/xhtml+xml,*/*;q=0.8").build()
   val r=client.newCall(req).execute()
   if(r.code in 300..399&&!r.header("Location").isNullOrBlank()){
    if(i==5){r.close();throw IOException("Too many redirects")}
    val next=URI(url).resolve(r.header("Location")!!).toString();r.close()
    url=UrlValidator.validate(next).getOrElse{throw IOException("Redirect blocked: ${it.message}")}.toString()
   }else{if(UrlValidator.validate(r.request.url.toString()).isFailure){r.close();throw IOException("Redirect blocked")};return r}
  };throw IOException("Too many redirects")
 }
 private fun readBounded(input:InputStream):ByteArray?{
  val out=ByteArrayOutputStream(minOf(maxBytes,32768));val buf=ByteArray(16384);var total=0
  while(true){val n=input.read(buf);if(n<0)return out.toByteArray();if(total+n>maxBytes)return null;out.write(buf,0,n);total+=n}
 }
 internal fun rewrite(html:String,pageUrl:String):String{
  val base=URI(pageUrl);val p=Pattern.compile("(?i)(\\b(?:href|src|action)\\s*=\\s*)([\"'])([^\"']+)\\2")
  val m=p.matcher(html);val out=StringBuffer()
  while(m.find()){
   val raw=m.group(3).trim()
   val target=if(raw.startsWith("#")||raw.startsWith("javascript:",true)||raw.startsWith("data:",true)||raw.startsWith("mailto:",true)||raw.startsWith("tel:",true))raw else try{
    val resolved=base.resolve(raw).toString()
    if(UrlValidator.validate(resolved).isFailure)raw else "/browse?url="+java.net.URLEncoder.encode(resolved,"UTF-8")
   }catch(_:Exception){raw}
   m.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(m.group(1)+m.group(2)+target+m.group(2)))
  };m.appendTail(out)
  val rewritten=out.toString()
  val bridge="<script src=\"/link-bridge.js\"></script>"
  val head=Pattern.compile("(?i)<head(?:\\s[^>]*)?>").matcher(rewritten)
  return if(head.find())rewritten.substring(0,head.end())+bridge+rewritten.substring(head.end()) else bridge+rewritten
 }
 private fun safeMediaType(raw:String?):String{
  val media=raw?.substringBefore(';')?.trim()?.lowercase().orEmpty()
  return if(media.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")))media else "application/octet-stream"
 }
 private fun error(code:Int,title:String,message:String,url:String):Page{
  val h="""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${esc(title)}</title><style>body{font:16px system-ui;background:#101318;color:#f4f6fa;padding:2rem}h1{color:#ff858b}</style></head><body><h1>${esc(title)}</h1><p>${esc(message)}</p><p><a href="/">Return home</a></p></body></html>"""
  return Page("text/html; charset=utf-8",h.toByteArray(),code,url)
 }
 private fun esc(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
}
