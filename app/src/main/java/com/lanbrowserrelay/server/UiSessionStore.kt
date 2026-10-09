package com.lanbrowserrelay.server

import com.lanbrowserrelay.security.ApiAccessPolicy
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.LinkedHashMap

/** In-memory browser sessions. The HttpOnly cookie and synchronizer token rotate on restart. */
class UiSessionStore(
    private val maxSessions: Int = 64,
    private val idleTimeoutMillis: Long = 30 * 60 * 1000L,
    private val requestLimitPerMinute: Int = 120,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Session internal constructor(
        val cookie: String,
        val csrf: String,
        internal var lastSeen: Long,
        internal val requests: ArrayDeque<Long> = ArrayDeque()
    )
    data class Resolved(val session: Session, val created: Boolean)

    private val random = SecureRandom()
    private val sessions = LinkedHashMap<String, Session>(16, 0.75f, true)

    @Synchronized
    fun resolveOrCreate(cookie: String?): Resolved {
        val now = clock()
        prune(now)
        val current = cookie?.let(sessions::get)
        if (current != null) {
            current.lastSeen = now
            return Resolved(current, false)
        }
        while (sessions.size >= maxSessions) {
            val oldest = sessions.entries.iterator()
            if (oldest.hasNext()) { oldest.next(); oldest.remove() } else break
        }
        val session = Session(randomToken(), randomToken(), now)
        sessions[session.cookie] = session
        return Resolved(session, true)
    }

    @Synchronized
    fun authorize(cookie: String?, csrf: String?, now: Long = clock()): Session? {
        prune(now)
        val session = cookie?.let(sessions::get) ?: return null
        if (!ApiAccessPolicy.isAuthorized(session.csrf, csrf)) return null
        session.lastSeen = now
        while (session.requests.isNotEmpty() && now - session.requests.first() >= 60_000L) {
            session.requests.removeFirst()
        }
        if (session.requests.size >= requestLimitPerMinute) return null
        session.requests.addLast(now)
        return session
    }

    private fun prune(now: Long) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.lastSeen >= idleTimeoutMillis) iterator.remove()
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val alphabet = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
    }
}
