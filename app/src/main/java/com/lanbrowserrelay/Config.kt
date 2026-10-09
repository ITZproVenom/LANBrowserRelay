package com.lanbrowserrelay

import android.content.Context
import android.content.SharedPreferences

object Config {
    private const val PREFS = "lan_browser_prefs"
    private const val KEY_PORT = "port"
    private const val KEY_MAX_DOWNLOAD = "max_download_bytes"

    const val DEFAULT_PORT = 8080
    const val DEFAULT_MAX_DOWNLOAD_BYTES = 100_000_000L
    const val MAX_CONCURRENT_DOWNLOADS = 3
    const val BUFFER_SIZE = 64 * 1024
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getPort(context: Context): Int =
        prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_PORT, port.coerceIn(1024, 65535)).apply()
    }

    fun getMaxDownloadBytes(context: Context): Long =
        prefs(context).getLong(KEY_MAX_DOWNLOAD, DEFAULT_MAX_DOWNLOAD_BYTES)

    fun setMaxDownloadBytes(context: Context, bytes: Long) {
        val safe = bytes.coerceIn(1_000_000L, 500_000_000L)
        prefs(context).edit().putLong(KEY_MAX_DOWNLOAD, safe).apply()
    }
}
