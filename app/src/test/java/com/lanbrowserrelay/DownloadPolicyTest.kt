package com.lanbrowserrelay
import org.junit.Assert.*
import org.junit.Test
class DownloadPolicyTest {
 @Test fun exactDecimalCap() = assertEquals(100_000_000L, DownloadPolicy.MAX_BYTES)
 @Test fun bufferCannotCrossCap() {
  assertEquals(32768, DownloadPolicy.bytesAllowed(0,32768))
  assertEquals(1, DownloadPolicy.bytesAllowed(99_999_999,32768))
  assertEquals(0, DownloadPolicy.bytesAllowed(100_000_000,1))
 }
 @Test fun overflowBoundary() {
  assertFalse(DownloadPolicy.wouldExceed(99_999_999,1))
  assertTrue(DownloadPolicy.wouldExceed(99_999_999,2))
  assertTrue(DownloadPolicy.wouldExceed(100_000_000,1))
 }
}
