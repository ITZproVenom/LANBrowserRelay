package com.lanbrowserrelay
object DownloadPolicy {
 const val MAX_BYTES = 100_000_000L
 const val BUFFER_BYTES = 32 * 1024
 const val MAX_CONCURRENT = 3
 fun bytesAllowed(transferred: Long, requested: Int): Int {
  if (requested <= 0 || transferred >= MAX_BYTES) return 0
  return minOf(requested.toLong(), MAX_BYTES - transferred).toInt()
 }
 fun wouldExceed(transferred: Long, incoming: Int) =
  incoming < 0 || transferred < 0 || transferred + incoming > MAX_BYTES
}
