package com.lanbrowserrelay

object Config {
    const val DEFAULT_PORT = 8080
    const val MAX_DOWNLOAD_BYTES = 100_000_000L
    const val MAX_CONCURRENT_DOWNLOADS = 3
    const val IO_BUFFER_BYTES = 32 * 1024
    const val GATEWAY_MAX_BYTES = 5_000_000
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 30L
}
