package com.lanbrowserrelay.security
import java.net.InetAddress
import java.net.URI
import java.net.URL
object UrlValidator {
 fun validate(raw: String): Result<URL> {
  return try {
   val u = URI(raw.trim())
   val scheme = u.scheme?.lowercase() ?: return Result.failure(SecurityException("Missing scheme"))
   if (scheme != "http" && scheme != "https") return Result.failure(SecurityException("Only HTTP/HTTPS is allowed"))
   val host = u.host?.lowercase() ?: return Result.failure(SecurityException("Missing host"))
   if (host == "localhost" || host.endsWith(".localhost") || host == "metadata.google.internal")
    return Result.failure(SecurityException("Local hosts are blocked"))
   val addresses = InetAddress.getAllByName(host)
   if (addresses.isEmpty() || addresses.any(::blocked)) return Result.failure(SecurityException("Local/reserved destination blocked"))
   Result.success(u.toURL())
  } catch (e: Exception) { Result.failure(SecurityException("Invalid URL: ${e.message ?: "parse error"}")) }
 }
 private fun blocked(ip: InetAddress): Boolean {
  if (ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress || ip.isMulticastAddress) return true
  val b = ip.address.map { it.toInt() and 255 }
  if (b.size == 4) {
   if (b[0] == 0 || b[0] >= 240) return true
   if (b[0] == 100 && b[1] in 64..127) return true
   if (b[0] == 192 && b[1] == 0 && b[2] == 0) return true
   if (b[0] == 198 && b[1] in 18..19) return true
  }
  return false
 }
 fun safeFilename(raw: String?): String {
  val s = raw.orEmpty().filter { it >= ' ' && it != '\u007f' }
   .replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.').take(180)
  return s.ifBlank { "download.bin" }
 }
 fun filenameFrom(disposition: String?, url: String): String {
  val star = disposition?.let { Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
  if (!star.isNullOrBlank()) {
   val decoded = try { java.net.URLDecoder.decode(star, "UTF-8") } catch (_: Exception) { star }
   return safeFilename(decoded)
  }
  val basic = disposition?.let { Regex("filename=\"?([^\";]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
  if (!basic.isNullOrBlank()) return safeFilename(basic)
  return try { safeFilename(URI(url).path.substringAfterLast('/')) } catch (_: Exception) { "download.bin" }
 }
}
