package com.lanbrowserrelay
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
class CleanRoomTests {
 @Test fun boundedStreamAllowsExactLimit(){val data="12345".toByteArray();var eof=false;assertArrayEquals(data,BoundedInputStream(ByteArrayInputStream(data),5,onEof={eof=true}).readBytes());assertTrue(eof)}
 @Test fun boundedStreamRejectsOverflow(){assertThrows(IOException::class.java){BoundedInputStream(ByteArrayInputStream("123456".toByteArray()),5).readBytes()}}
 @Test fun rejectsBadSchemesAndLocalHosts(){assertTrue(UrlPolicy.parse("file:///etc/passwd").isFailure);assertTrue(UrlPolicy.parse("http://localhost").isFailure);assertTrue(UrlPolicy.parse("https://u:p@example.com").isFailure)}
 @Test fun blocksPrivateAddresses(){assertTrue(UrlPolicy.privateAddress(InetAddress.getByName("192.168.1.1")));assertTrue(UrlPolicy.privateAddress(InetAddress.getByName("127.0.0.1")));assertFalse(UrlPolicy.privateAddress(InetAddress.getByName("8.8.8.8")))}
}
