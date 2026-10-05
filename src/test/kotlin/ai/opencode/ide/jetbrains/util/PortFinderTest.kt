package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the downward scan in [PortFinder.findLastRunningOpenCodeServer].
 * Uses an injected health check predicate, so no real sockets are involved.
 */
class PortFinderTest {

    @Test
    fun testReturnsHighestHealthyPort() {
        val healthy = setOf(4096, 4110, 4097)
        val result = PortFinder.findLastRunningOpenCodeServer { it in healthy }
        assertEquals(4110, result)
    }

    @Test
    fun testReturnsNullWhenNothingHealthy() {
        val result = PortFinder.findLastRunningOpenCodeServer { false }
        assertNull(result)
    }

    @Test
    fun testScansRangeBelow4150DownTo4096() {
        val checked = mutableListOf<Int>()
        PortFinder.findLastRunningOpenCodeServer { p -> checked.add(p); false }
        assertEquals((4096..4149).reversed().toList(), checked)
    }

    @Test
    fun testStopsAtFirstHealthyPort() {
        val checked = mutableListOf<Int>()
        val result = PortFinder.findLastRunningOpenCodeServer { p -> checked.add(p); p == 4149 }
        assertEquals(4149, result)
        assertEquals(1, checked.size)
    }
}
