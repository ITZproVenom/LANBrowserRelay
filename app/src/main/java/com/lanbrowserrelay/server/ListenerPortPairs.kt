package com.lanbrowserrelay.server

/**
 * Ordered UI/content listener pairs. Each UI listener uses the next port for isolated proxied
 * content, so pairs advance by two and never overlap one another.
 */
internal object ListenerPortPairs {
    const val DEFAULT_UI_PORT = 8080
    const val MAX_ATTEMPTS = 16

    fun candidates(): List<Int> =
        (0 until MAX_ATTEMPTS).map { DEFAULT_UI_PORT + it * 2 }
}
