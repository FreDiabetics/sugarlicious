package app.aapswear.mobile

import app.aapswear.model.RelativeGraphTimeAxis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartViewportRegressionTest {
    private val hour = 60L * 60_000L
    private val now = 48L * hour

    @Test
    fun `twenty four hours is an invariant including prediction space`() {
        val viewport = ChartViewport(23)
        viewport.setAvailablePastWindow(48L * hour, now)
        assertEquals(23f, viewport.visibleHours, 0.0001f)

        viewport.setHours(24f)
        assertEquals(24f, viewport.visibleHours, 0.0001f)

        viewport.setHours(100f)
        assertEquals(24f, viewport.visibleHours, 0.0001f)

        viewport.setFutureWindow(2L * hour, now)
        assertEquals(24f, viewport.visibleHours, 0.0001f)
        assertEquals(22f, viewport.hours, 0.0001f)
    }

    @Test
    fun `invalid restored duration is normalized before rendering`() {
        val viewport = ChartViewport(36)
        viewport.setAvailablePastWindow(48L * hour, now)
        assertEquals(MAX_VISIBLE_GRAPH_HOURS, viewport.visibleHours, 0.0001f)
        assertTrue(viewport.snapshot(now).durationMs <= 24L * hour)
    }

    @Test
    fun `pinch keeps its focal timestamp while constraints permit`() {
        val viewport = ChartViewport(12)
        viewport.setAvailablePastWindow(24L * hour, now)
        viewport.pan(120f, 600f, now)
        val before = viewport.snapshot(now)
        val focal = 0.35f
        val focalBefore = before.startEpochMs + (before.durationMs * focal).toLong()

        viewport.zoom(2f, focal, now)

        val after = viewport.snapshot(now)
        val focalAfter = after.startEpochMs + (after.durationMs * focal).toLong()
        assertTrue(kotlin.math.abs(focalBefore - focalAfter) <= 2L)
        assertTrue(after.visibleHours <= MAX_VISIBLE_GRAPH_HOURS)
    }

    @Test
    fun `extreme pan zoom and fling sized steps never escape viewport bounds`() {
        val viewport = ChartViewport(3)
        viewport.setAvailablePastWindow(36L * hour, now)
        viewport.setFutureWindow(hour, now)
        repeat(12) {
            viewport.pan(if (it % 2 == 0) 100_000f else -100_000f, 400f, now)
            viewport.zoom(if (it % 2 == 0) 0.01f else 4f, 0.4f, now)
            assertTrue(viewport.snapshot(now).durationMs <= 24L * hour)
            assertTrue(viewport.snapshot(now).endEpochMs <= now + hour)
        }
    }

    @Test
    fun `axis and divider derive from each current canonical snapshot`() {
        val viewport = ChartViewport(6)
        viewport.setAvailablePastWindow(24L * hour, now)
        val first = viewport.snapshot(now)
        val firstTicks = RelativeGraphTimeAxis.ticks(first.startEpochMs, first.endEpochMs, first.liveEdgeEpochMs)

        viewport.pan(180f, 360f, now)
        val second = viewport.snapshot(now)
        val secondTicks = RelativeGraphTimeAxis.ticks(second.startEpochMs, second.endEpochMs, second.liveEdgeEpochMs)

        assertNotEquals(first.startEpochMs, second.startEpochMs)
        assertNotEquals(firstTicks.map { it.timestampEpochMs }, secondTicks.map { it.timestampEpochMs })
        assertEquals(second.liveEdgeEpochMs, second.endEpochMs - viewport.futureWindowMs)
    }

    @Test
    fun `new data keeps a historical viewport stable`() {
        val viewport = ChartViewport(6)
        viewport.setAvailablePastWindow(24L * hour, now)
        viewport.pan(120f, 360f, now)
        val historicalEnd = viewport.snapshot(now).endEpochMs

        viewport.setAvailablePastWindow(24L * hour, now + 5L * 60_000L)

        assertEquals(historicalEnd, viewport.snapshot(now + 5L * 60_000L).endEpochMs)
    }
}
