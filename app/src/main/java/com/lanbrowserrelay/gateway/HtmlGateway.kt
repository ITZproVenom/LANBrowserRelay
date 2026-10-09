package com.lanbrowserrelay.gateway

import com.lanbrowserrelay.security.UrlValidator
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Restricted HTML gateway — no auth required.
 * Fetches pages and rewrites navigable links to stay inside the hosted browser.
 */
class HtmlGateway(private val baseProxyPath: String = "/browse") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class GatewayResult(
        val contentType: String,
        val body: ByteArray,
        val statusCode: Int,
        val finalUrl: String,
        val error: String? = null
    )

    fun fetchAndRewrite(targetUrl: String, unusedToken: String = ""): GatewayResult {
        val validation = UrlValidator.isAllowedUrl(targetUrl)
        if (validation.isFailure) {
            return GatewayResult(
                "text/html; charset=utf-8",
                errorPage("Blocked URL", validation.exceptionOrNull()?.message ?: "Invalid").toByteArray(),
                403,
                targetUrl,
                validation.exceptionOrNull()?.message
            )
        }

        return try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 LANBrowserRelay/1.0")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                val finalCheck = UrlValidator.isAllowedUrl(finalUrl)
                if (finalCheck.isFailure) {
                    return GatewayResult(
                        "text/html; charset=utf-8",
                        errorPage("Redirect blocked", finalCheck.exceptionOrNull()?.message ?: "").toByteArray(),
                        403,
                        finalUrl
                    )
                }

                val bodyBytes = response.body?.bytes() ?: ByteArray(0)
                val contentType = response.header("Content-Type") ?: "application/octet-stream"

                if (contentType.contains("text/html", ignoreCase = true) ||
                    contentType.contains("application/xhtml", ignoreCase = true)
                ) {
                    val html = String(bodyBytes, Charsets.UTF_8)
                    val rewritten = rewriteHtml(html, finalUrl)
                    GatewayResult(
                        "text/html; charset=utf-8",
                        rewritten.toByteArray(Charsets.UTF_8),
                        response.code,
                        finalUrl
                    )
                } else {
                    if (bodyBytes.size > 5_000_000) {
                        return GatewayResult(
                            "text/html; charset=utf-8",
                            errorPage("Resource too large", "Non-HTML resources limited to 5 MB in gateway").toByteArray(),
                            413,
                            finalUrl
                        )
                    }
                    GatewayResult(contentType, bodyBytes, response.code, finalUrl)
                }
            }
        } catch (e: Exception) {
            GatewayResult(
                "text/html; charset=utf-8",
                errorPage("Fetch failed", e.message ?: "Unknown error").toByteArray(),
                502,
                targetUrl,
                e.message
            )
        }
    }

    private fun rewriteHtml(html: String, pageUrl: String): String {
        val base = try { URI(pageUrl) } catch (_: Exception) { return html }

        var result = html
        val attrPattern = Pattern.compile(
            "(?i)(\\b(?:href|src|action)\\s*=\\s*)([\"'])([^\"']+)\\2",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = attrPattern.matcher(result)
        val sb = StringBuffer()
        while (matcher.find()) {
            val prefix = matcher.group(1)
            val quote = matcher.group(2)
            val value = matcher.group(3)
            val rewritten = rewriteUrl(value, base)
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("$prefix$quote$rewritten$quote"))
        }
        matcher.appendTail(sb)
        result = sb.toString()

        if (!result.contains("<base ", ignoreCase = true)) {
            val baseTag = """<base href=\"${escapeHtml(pageUrl)}\">"""
            result = result.replaceFirst(Regex("(?i)<head[^>]*>"), "$0\n$baseTag")
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
            val check = UrlValidator.isAllowedUrl(resolved)
            if (check.isFailure) trimmed
            else "$baseProxyPath?url=${java.net.URLEncoder.encode(resolved, "UTF-8")}"
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun errorPage(title: String, message: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">
            <title>$title</title>
            <style>
                body{font-family:system-ui,sans-serif;background:#121212;color:#eee;padding:2rem;text-align:center}
                h1{color:#F44336} a{color:#00BCD4}
            </style>
            </head>
            <body>
                <h1>$title</h1>
                <p>${escapeHtml(message)}</p>
                <p><a href=\"/\">Return to browser home</a></p>
                <p style=\"color:#888;font-size:0.9rem\">This site could not be loaded through the LAN gateway.</p>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&").replace("<", "<").replace(">", ">").replace("\"", """)
}
