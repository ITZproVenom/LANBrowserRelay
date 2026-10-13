package com.lanbrowserrelay.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerPortPairsTest {
    @Test fun prefersTheDocumentedDefaultPair() {
        assertEquals(8080, ListenerPortPairs.candidates().first().toInt())
    }

    @Test fun fallbackPairsAreAdjacentAndDoNotOverlap() {
        val uiPorts = ListenerPortPairs.candidates()
        assertEquals(ListenerPortPairs.MAX_ATTEMPTS, uiPorts.size)
        assertEquals((8080..8110 step 2).toList(), uiPorts)
        assertTrue(uiPorts.all { it % 2 == 0 && it + 1 <= 65535 })
        assertTrue(uiPorts.zipWithNext().all { (left, right) -> right == left + 2 })
    }
}
