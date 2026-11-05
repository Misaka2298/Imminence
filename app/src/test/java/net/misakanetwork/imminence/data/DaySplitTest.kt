package net.misakanetwork.imminence.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DaySplitTest {

    private val dayStart = Days.startOf(1_700_000_000_000L)

    @Test
    fun `range inside a single day is not split`() {
        val segments = DaySplit.segments(dayStart + 1_000L, dayStart + 60_000L)

        assertEquals(1, segments.size)
        assertEquals(59_000L, segments[0].millis)
        assertEquals(Days.keyOf(dayStart), segments[0].day)
    }

    @Test
    fun `range across midnight is split into two days`() {
        val segments = DaySplit.segments(dayStart - 30_000L, dayStart + 45_000L)

        assertEquals(2, segments.size)
        assertEquals(30_000L, segments[0].millis)
        assertEquals(45_000L, segments[1].millis)
        assertEquals(75_000L, segments.sumOf { it.millis })
    }

    @Test
    fun `empty range produces nothing`() {
        assertEquals(0, DaySplit.segments(dayStart, dayStart).size)
        assertEquals(0, DaySplit.segments(dayStart, dayStart - 1L).size)
    }
}
