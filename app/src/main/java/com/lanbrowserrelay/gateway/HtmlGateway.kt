package com.lanbrowserrelay.gateway

import com.lanbrowserrelay.security.UrlValidator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * No-login HTML gateway. Rewrites navigable links into the local hosted browser.
 * Response bodies are bounded so large pages/resources cannot exhaust TV memory.
 */
class HtmlGateway(private val baseProxyPath: String = "/browse") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    data class GatewayResult(
        val contentType: String,
        val body: ByteArray,
        val statusCode: Int,
        val finalUrl: String,
        val error: String? = null
    )

    fun fetchAndRewrite(targetUrl: String, unusedToken: String = ""): GatewayResult {
        val initialCheck = UrlValidator.isAllowedUrl(targetUrl)
        if (initialCheck.isFailure) {
            val message = initialCheck.exceptionOrNull()?.message ?: "Invalid URL"
            return GatewayResult("text/html; charset=utf-8", errorPage("Blocked URL", message).toByteArray(), 403, targetUrl, message)
        }

        return try {
            executeCheckedRedirects(initialCheck.getOrThrow().toString()).use { response ->
                val finalUrl = response.request.url.toString()
                val contentType = response.header("Content-Type") ?: "application/octet-stream"
                val body = response.body
                val declaredLength = body?.contentLength() ?: 0L

                if (declaredLength > MAX_RESPONSE_BYTES) {
                    return GatewayResult(
                        "text/html; charset=utf-8",
                        errorPage("Resource too large", "Gateway resources are limited to 5 MB. Use the download action for files.").toByteArray(),
                        413,
                        finalUrl
                    )
                }

                val bodyBytes = body?.byteStream()?.use { readBounded(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
                if (bodyBytes == null) {
                    return GatewayResult(
                        "text/html; charset=utf-8",
                        errorPage("Resource too large", "Gateway resources are limited to 5 MB. Use the download action for files.").toByteArray(),
                        413,
                        finalUrl
                    )
                }

                if (contentType.contains("text/html", ignoreCase = true) ||
                    contentType.contains("application/xhtml", ignoreCase = true)
                ) {
                    val rewritten = rewriteHtml(String(bodyBytes, Charsets.UTF_8), finalUrl)
                    GatewayResult("text/html; charset=utf-8", rewritten.toByteArray(Charsets.UTF_8), response.code, finalUrl)
                } else {
                    GatewayResult(contentType, bodyBytes, response.code, finalUrl)
                }
            }
        } catch (e: Exception) {
            val message = e.message ?: "Unknown error"
            GatewayResult("text/html; charset=utf-8", errorPage("Fetch failed", message).toByteArray(), 502, targetUrl, message)
        }
    }

    /** Redirects are followed manually so each destination is validated before it is requested. */
    private fun executeCheckedRedirects(initialUrl: String): Response {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val request = Request.Builder()
                .url(currentUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 LANBrowserRelay/1.0"
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val response = client.newCall(request).execute()
            if (response.code in 300..399) {
                val location = response.header("Location")
                if (location.isNullOrBlank()) return response
                if (redirectCount == MAX_REDIRECTS) {
                    response.close()
                    throw IOException("Too many redirects")
                }

                val nextUrl = try {
                    URI(currentUrl).resolve(location).toURL().toString()
                } catch (e: Exception) {
                    response.close()
                    throw IOException("Invalid redirect URL", e)
                }
                response.close()
                currentUrl = UrlValidator.isAllowedUrl(nextUrl).getOrElse {
                    throw IOException("Redirect to prohibited destination: \${it.message}")
                }.toString()
            } else {
                val validation = UrlValidator.isAllowedUrl(response.request.url.toString())
                if (validation.isFailure) {
                    response.close()
                    throw IOException("Redirect to prohibited destination")
                }
                return response
            }
        }
        throw IOException("Too many redirects")
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(limit, 32 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (total + count > limit) return null
            out.write(buffer, 0, count)
            total += count
        }
        return out.toByteArray()
    }

    private fun rewriteHtml(html: String, pageUrl: String): String {
        val base = try {
            URI(pageUrl)
        } catch (_: Exception) {
            return html
        }

        val attrPattern = Pattern.compile(
            "(?i)(\\b(?:href|src|action)\\s*=\\s*)([\\\"'])([^\\\"']+)\\2",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = attrPattern.matcher(html)
        val sb = StringBuffer()
        while (matcher.find()) {
            val prefix = matcher.group(1)
            val quote = matcher.group(2)
            val value = matcher.group(3)
            val rewritten = rewriteUrl(value, base)
            matcher.appendReplacement(
                sb,
                java.util.regex.Matcher.quoteReplacement("\$prefix\$quote\$rewritten\$quote")
            )
        }
        matcher.appendTail(sb)
        var result = sb.toString()

        if (!result.contains("<base ", ignoreCase = true)) {
            val baseTag = "<base href=\"\${escapeHtml(pageUrl)}\">"
            result = result.replaceFirst(Regex("(?i)<head[^>]*>"), "\$0\n\$baseTag")
        }
        return result
    }

    private fun rewriteUrl(raw: String, base: URI): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() ||
            trimmed.startsWith("#") ||
            trimmed.startsWith("javascript:", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true) ||
            trimmed.startsWith("mailto:", ignoreCase = true) ||
            trimmed.startsWith("tel:", ignoreCase = true)
        ) return trimmed

        return try {
            val resolved = base.resolve(trimmed).toURL().toString()
            if (UrlValidator.isAllowedUrl(resolved).isFailure) trimmed
            else "\$baseProxyPath?url=\${java.net.URLEncoder.encode(resolved, "UTF-8")}"
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun errorPage(title: String, message: String): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <title>\${escapeHtml(title)}</title>
          <style>
            body{font-family:system-ui,sans-serif;background:#121212;color:#eee;padding:2rem;text-align:center}
            h1{color:#F44336} a{color:#00BCD4}
          </style>
        </head>
        <body>
          <h1>\${escapeHtml(title)}</h1>
          <p>\${escapeHtml(message)}</p>
          <p><a href="/">Return to browser home</a></p>
          <p style="color:#888;font-size:.9rem">This site could not be loaded through the LAN gateway.</p>
        </body>
        </html>
    """.trimIndent()

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    companion object {
        private const val MAX_RESPONSE_BYTES = 5_000_000
        private const val MAX_REDIRECTS = 5
    }
}
