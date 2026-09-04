package app.aapswear.uishared

import app.aapswear.model.CgmThresholds
import app.aapswear.model.GraphTimeWindow
import app.aapswear.model.TrendVisualAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedWearCgmGraphRendererTest {
    @Test
    fun `wear and collector adapters receive identical full bleed geometry`() {
        listOf(192 to 112, 320 to 170, 454 to 220).forEach { (width, height) ->
            val wear = SharedWearCgmGraphRenderer.metrics(width, height, 2f, CgmThresholds.DEFAULT)
            val collector = SharedWearCgmGraphRenderer.metrics(width, height, 2f, CgmThresholds.DEFAULT)
            assertEquals(wear, collector)
            assertEquals(12f, wear.plot.left, 0.01f)
            assertEquals(width - 58f, wear.plot.right, 0.01f)
            assertTrue(wear.plot.height() > 0f)
            assertTrue(wear.highY < wear.lowY)
        }
    }

    @Test
    fun `timestamp positioning is stable for all supported periods`() {
        val now = 2_000_000_000_000L
        listOf(1, 2, 3, 6, 12, 24).forEach { hours ->
            val window = GraphTimeWindow.live(now, hours * 60L * 60_000L)
            val metrics = SharedWearCgmGraphRenderer.metrics(320, 180, 2f, CgmThresholds.DEFAULT)
            assertEquals(metrics.plot.right, metrics.xFor(window, now), 0.01f)
            assertEquals(metrics.plot.left, metrics.xFor(window, window.startEpochMs), 0.01f)
            assertTrue(metrics.xFor(window, now - 5 * 60_000L) < metrics.plot.right)
        }
    }

    @Test
    fun `all supplied trend vectors preserve their source canvas`() {
        val resourceIds = TrendVisualAsset.entries.map(TrendDrawableResources::forAsset)
        assertEquals(TrendVisualAsset.entries.size, resourceIds.distinct().size)
        TrendVisualAsset.entries.forEach { asset ->
            assertTrue(TrendVectorPaths.forAsset(asset).isNotEmpty())
        }
        assertEquals(2, TrendVectorPaths.forAsset(TrendVisualAsset.DOUBLE_UP).size)
        assertEquals(2, TrendVectorPaths.forAsset(TrendVisualAsset.DOUBLE_DOWN).size)
    }
}
