package com.lanbrowserrelay.gateway

import com.lanbrowserrelay.Config
import com.lanbrowserrelay.security.UrlValidator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class GatewayResult(val statusCode: Int, val contentType: String, val body: ByteArray, val finalUrl: String)

class HtmlGateway {
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(Config.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(Config.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    fun fetch(rawUrl: String): GatewayResult {
        val initial = UrlValidator.validate(rawUrl).getOrElse {
            return error(403, "Blocked URL", it.message ?: "Invalid URL", rawUrl)
        }
        return try {
            executeValidatedRedirects(initial.toString()).use { response ->
                val finalUrl = response.request.url.toString()
                val type = response.header("Content-Type") ?: "application/octet-stream"
                val body = response.body
                val read = body?.byteStream()?.use { readBounded(it) }
                if (body != null && read == null) {
                    return error(413, "Page too large", "Gateway responses are limited to 5 MB. Use Download for files.", finalUrl)
                }
                val bytes = read ?: ByteArray(0)
                val output = when {
                    type.contains("text/html", true) || type.contains("application/xhtml", true) ->
                        rewriteHtml(String(bytes, Charsets.UTF_8), finalUrl).toByteArray(Charsets.UTF_8)
                    type.contains("text/css", true) ->
                        rewriteCss(String(bytes, Charsets.UTF_8), finalUrl).toByteArray(Charsets.UTF_8)
                    else -> bytes
                }
                GatewayResult(response.code, type, output, finalUrl)
            }
        } catch (e: Exception) {
            error(502, "Fetch failed", e.message ?: "Unknown upstream error", rawUrl)
        }
    }

    private fun executeValidatedRedirects(start: String): Response {
        var current = start
        repeat(MAX_REDIRECTS + 1) { index ->
            UrlValidator.validate(current).getOrElse { throw IOException("Destination blocked") }
            val request = Request.Builder().url(current).header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8").build()
            val response = client.newCall(request).execute()
            val location = response.header("Location")
            if (response.code !in 300..399 || location.isNullOrBlank()) {
                if (UrlValidator.validate(response.request.url.toString()).isFailure) {
                    response.close()
                    throw IOException("Redirect target blocked")
                }
                return response
            }
            if (index == MAX_REDIRECTS) {
                response.close()
                throw IOException("Too many redirects")
            }
            val next = runCatching { URI(current).resolve(location).toString() }.getOrElse {
                response.close()
                throw IOException("Invalid redirect target")
            }
            response.close()
            current = UrlValidator.validate(next).getOrElse { throw IOException("Redirect target blocked") }.toString()
        }
        throw IOException("Too many redirects")
    }

    private fun readBounded(input: java.io.InputStream): ByteArray? {
        val out = ByteArrayOutputStream(32 * 1024)
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (total + n > Config.GATEWAY_MAX_BYTES) return null
            out.write(buffer, 0, n)
            total += n
        }
    }

    private fun rewriteHtml(input: String, pageUrl: String): String {
        val base = URI(pageUrl)
        val pattern = Pattern.compile("(?i)(\\b(?:href|src|action)\\s*=\\s*)([\"'])([^\"']+)\\2")
        val matcher = pattern.matcher(input)
        val out = StringBuffer()
        while (matcher.find()) {
            val prefix = matcher.group(1)
            val quote = matcher.group(2)
            val raw = matcher.group(3).trim()
            val resolved = runCatching { base.resolve(raw).toString() }.getOrDefault(raw)
            val allowed = (resolved.startsWith("http://", true) || resolved.startsWith("https://", true)) &&
                UrlValidator.validate(resolved).isSuccess
            val proxied = if (allowed) proxy(resolved) else raw
            val original = if (allowed && matcher.group().contains("href", true))
                " data-lanrelay-url=\"${escapeAttribute(resolved)}\"" else ""
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("$prefix$quote$proxied$quote$original"))
        }
        matcher.appendTail(out)
        var html = out.toString()
        if (!html.contains("<base ", true)) {
            val head = Regex("(?i)<head[^>]*>").find(html)
            if (head != null) html = html.replaceRange(head.range.last + 1, head.range.last + 1,
                "\n<base href=\"${escapeAttribute(pageUrl)}\">")
        }
        val interceptor = """
            <script>
            document.addEventListener('click',function(e){
              const a=e.target.closest&&e.target.closest('a[data-lanrelay-url]'); if(!a)return;
              const u=a.getAttribute('data-lanrelay-url')||'';
              if(a.hasAttribute('download')||/\\.(zip|apk|mp4|mkv|pdf|7z|rar|exe|dmg|iso|mp3|flac|bin|tar|gz)(?:$|[?#])/i.test(u)){
                e.preventDefault(); parent.postMessage({type:'lanrelay-download',url:u,filename:a.getAttribute('download')||''},'*');
              }
            },true);
            </script>
        """.trimIndent()
        return if (html.contains("</body>", true)) html.replace(Regex("(?i)</body>"), interceptor + "\n</body>") else html + interceptor
    }

    private fun rewriteCss(css: String, pageUrl: String): String {
        val base = URI(pageUrl)
        val pattern = Regex("(?i)url\\(\\s*(['\"]?)([^'\")]+)\\1\\s*\\)")
        return pattern.replace(css) { match ->
            val raw = match.groupValues[2].trim()
            if (raw.startsWith("data:", true) || raw.startsWith("#") || raw.startsWith("blob:", true)) match.value
            else {
                val url = runCatching { base.resolve(raw).toString() }.getOrDefault(raw)
                if (UrlValidator.validate(url).isSuccess) "url(\"${proxy(url)}\")" else match.value
            }
        }
    }

    private fun proxy(url: String) = "/browse?url=" + java.net.URLEncoder.encode(url, "UTF-8")
    private fun escapeAttribute(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;")

    private fun error(status: Int, title: String, message: String, url: String) = GatewayResult(
        status, "text/html; charset=utf-8",
        """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeAttribute(title)}</title><style>body{background:#101116;color:#f2f4fa;font:16px system-ui;padding:2rem}h1{color:#ff7d8c}</style></head><body><h1>${escapeAttribute(title)}</h1><p>${escapeAttribute(message)}</p><a href="/">Return to relay</a></body></html>""".toByteArray(Charsets.UTF_8),
        url
    )

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36 LANBrowserRelay/1.0"
    }
}
