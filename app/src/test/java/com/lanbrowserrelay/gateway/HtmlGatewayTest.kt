package com.lanbrowserrelay.gateway

import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlGatewayTest {
    @Test fun injectsOnlyTheConstrainedLinkBridgeAndRewritesSafeLinks() {
        val html = "<html><head><title>remote</title></head><body>" +
            "<a href=\"/files/update.apk\" download>Get it</a>" +
            "<a href=\"javascript:alert(1)\">unsafe scheme</a></body></html>"
        val rewritten = HtmlGateway().rewrite(html, "https://8.8.8.8/docs/page.html")

        assertTrue(rewritten.contains("<head><script src=\"/link-bridge.js\"></script><title>remote</title>"))
        assertTrue(rewritten.contains("href=\"/browse?url=https%3A%2F%2F8.8.8.8%2Ffiles%2Fupdate.apk\""))
        assertTrue(rewritten.contains("href=\"javascript:alert(1)\""))
        assertTrue(rewritten.contains("download"))
    }
}
