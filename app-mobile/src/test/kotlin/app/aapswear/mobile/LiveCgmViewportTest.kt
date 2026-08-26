package app.aapswear.mobile

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class LiveCgmViewportTest {
    @Test fun `timestamp positions preserve five minute and two minute spacing`() {
        val start = 12L * 60L * 60_000L
        val end = start + 60L * 60_000L
        val points = listOf(0L, 5L, 10L, 15L).map { timeToXFraction(start + it * 60_000L, start, end) }
        assertEquals(points[1] - points[0], points[2] - points[1], 0.00001f)
        assertEquals(points[2] - points[1], points[3] - points[2], 0.00001f)
        val twoMinutes = timeToXFraction(start + 17L * 60_000L, start, end) - points[3]
        assertTrue(twoMinutes < points[3] - points[2])
    }

    @Test fun `user pan remains stable until explicit return to now`() {
        val viewport = ChartViewport(3).apply { setAvailablePastWindow(12L * 60L * 60_000L) }
        viewport.pan(100f, 300f, referenceNow = 1_000_000L)
        val historicalEnd = viewport.endEpochMs(1_000_000L)
        assertEquals(ChartViewport.Mode.USER_NAVIGATING, viewport.mode)
        assertEquals(historicalEnd, viewport.endEpochMs(1_000_000L + 5L * 60_000L))
        viewport.returnToNow()
        assertEquals(ChartViewport.Mode.LIVE_FOLLOW, viewport.mode)
        assertEquals(1_300_000L, viewport.endEpochMs(1_300_000L))
    }
}
