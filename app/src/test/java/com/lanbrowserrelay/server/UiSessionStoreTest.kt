package com.lanbrowserrelay.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSessionStoreTest {
    @Test fun sessionsAreIndependentExpireAndRequireTheirCsrfToken() {
        var now = 1_000L
        val store = UiSessionStore(maxSessions = 4, idleTimeoutMillis = 100, requestLimitPerMinute = 3) { now }
        val first = store.resolveOrCreate(null)
        val second = store.resolveOrCreate(null)
        assertNotEquals(first.session.cookie, second.session.cookie)
        assertNotEquals(first.session.csrf, second.session.csrf)
        assertNull(store.authorize(first.session.cookie, "wrong"))
        assertNull(store.authorize(second.session.cookie, first.session.csrf))
        assertEquals(first.session.cookie, store.authorize(first.session.cookie, first.session.csrf)?.cookie)
        assertEquals(second.session.cookie, store.authorize(second.session.cookie, second.session.csrf)?.cookie)

        assertNotNull(store.authorize(first.session.cookie, first.session.csrf))
        assertNotNull(store.authorize(first.session.cookie, first.session.csrf))
        assertNull(store.authorize(first.session.cookie, first.session.csrf)) // per-session rate cap
        now += 101
        assertTrue(store.resolveOrCreate(first.session.cookie).created)
    }

    @Test fun reusesValidCookieAndBoundsSessionCount() {
        val store = UiSessionStore(maxSessions = 1)
        val first = store.resolveOrCreate(null)
        assertEquals(first.session.csrf, store.resolveOrCreate(first.session.cookie).session.csrf)
        val replacement = store.resolveOrCreate(null)
        assertNotEquals(first.session.cookie, replacement.session.cookie)
        assertNull(store.authorize(first.session.cookie, first.session.csrf))
    }
}
