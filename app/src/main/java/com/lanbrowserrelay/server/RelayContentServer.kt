package com.lanbrowserrelay.server

import android.content.Context
import android.util.Log
import com.lanbrowserrelay.gateway.HtmlGateway
import com.lanbrowserrelay.security.RequestOriginPolicy
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.util.concurrent.Semaphore

/** Serves proxied pages from a separate TCP port/origin with no relay API routes. */
class RelayContentServer(
    private val context: Context,
    private val bindAddress: String,
    port: Int = 8081,
    private val uiPort: Int = 8080,
    private val sessions: UiSessionStore
) : NanoHTTPD(bindAddress, port) {
    private val gateway = HtmlGateway()
    private val contentPort = port
    private val pagePermits = Semaphore(2)

    override fun serve(session: IHTTPSession): Response {
        if (!RequestOriginPolicy.isExpectedHost(session.headers.header("host"), bindAddress, contentPort)) {
            return error(Response.Status.BAD_REQUEST, "Invalid Host")
        }
        if (session.method != Method.GET) return error(Response.Status.METHOD_NOT_ALLOWED, "GET required")
        return when (session.uri) {
            "/link-bridge.js" -> bridgeScript(session)
            "/browse" -> browse(session.parms["url"])
            else -> error(Response.Status.NOT_FOUND, "Not found")
        }.apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Referrer-Policy", "no-referrer")
        }
    }

    private fun browse(url: String?): Response {
        if (url.isNullOrBlank() || url.length > 8192) return error(Response.Status.BAD_REQUEST, "Invalid URL")
        if (!pagePermits.tryAcquire()) return error(Response.Status.SERVICE_UNAVAILABLE, "Page load capacity reached")
        return try {
            val page = gateway.fetch(url)
            newFixedLengthResponse(
                Response.Status.lookup(page.status) ?: Response.Status.OK,
                page.type,
                ByteArrayInputStream(page.bytes),
                page.bytes.size.toLong()
            ).also { response ->
                if (page.type.startsWith("text/html", ignoreCase = true)) {
                    response.addHeader(
                        "Content-Security-Policy",
                        "default-src 'self' https: data: blob:; script-src 'self' 'unsafe-inline' https:; " +
                            "style-src 'self' 'unsafe-inline' https:; img-src 'self' https: data: blob:; " +
                            "font-src 'self' https: data:; media-src 'self' https: blob:; connect-src 'self'; " +
                            "frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; " +
                            "frame-ancestors http://$bindAddress:$uiPort"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("LanBrowserRelay", "Proxied page failed", e)
            error(Response.Status.INTERNAL_ERROR, "Page load failed")
        } finally {
            pagePermits.release()
        }
    }

    private fun bridgeScript(session: IHTTPSession): Response {
        val cookie = session.headers.header("cookie")
            ?.split(';')
            ?.asSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("lbr_session=") }
            ?.substringAfter('=')
            ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        val token = sessions.navigationTokenForCookie(cookie)
            ?: return error(Response.Status.FORBIDDEN, "Session required")
        return try {
            val source = context.assets.open("web/link-bridge.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val bytes = source.replace("__RELAY_NAV_TOKEN__", token).toByteArray(Charsets.UTF_8)
            newFixedLengthResponse(Response.Status.OK, "application/javascript; charset=utf-8",
                ByteArrayInputStream(bytes), bytes.size.toLong())
        } catch (_: Exception) {
            error(Response.Status.NOT_FOUND, "Asset not found")
        }
    }

    private fun asset(path: String, type: String): Response = try {
        val bytes = context.assets.open(path).use { it.readBytes() }
        newFixedLengthResponse(Response.Status.OK, type, ByteArrayInputStream(bytes), bytes.size.toLong())
    } catch (_: Exception) {
        error(Response.Status.NOT_FOUND, "Asset not found")
    }

    private fun error(status: Response.Status, message: String) =
        newFixedLengthResponse(status, MIME_PLAINTEXT, message)

    fun shutdown() = stop()

    private fun Map<String, String>.header(name: String) =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
