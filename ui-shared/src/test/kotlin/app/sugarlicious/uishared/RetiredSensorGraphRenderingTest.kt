package app.sugarlicious.uishared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import app.sugarlicious.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RetiredSensorGraphRenderingTest {
    @Test fun `shared graph keeps retired dots hollow and active dots filled even with older active timestamp`() {
        val now = 2_000_000_000_000L
        val old = GlucoseSample(120.0, now - 600_000, DataSourceId.DEXCOM_G7_WATCH, "old", "old-session")
        val active = GlucoseSample(130.0, now - 1_200_000, DataSourceId.DEXCOM_G7_WATCH, "new", "new-session")
        val palette = SharedWearCgmGraphPalette(Color.BLACK, Color.BLACK, Color.BLACK, Color.BLACK, Color.GRAY, Color.GRAY, Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE, Color.CYAN, Color.CYAN, Color.CYAN, Color.BLACK, Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE)
        val input = SharedWearCgmGraphInput(listOf(active, old), timeWindow = GraphTimeWindow.live(now, 7_200_000), nowEpochMs = now, thresholds = CgmThresholds.DEFAULT, palette = palette, style = SharedWearCgmGraphStyle(dotRadiusDp = 4f, timeAxisEnabled = false, borderEnabled = false), activeIdentity = CgmGraphSensorIdentity(DataSourceId.DEXCOM_G7_WATCH, "new", "new-session"))
        val bitmap = Bitmap.createBitmap(454, 220, Bitmap.Config.ARGB_8888)
        try {
            val metrics = requireNotNull(SharedWearCgmGraphRenderer.render(Canvas(bitmap), 454, 220, 1f, 1f, input))
            val x = metrics.sampleXFor(input.timeWindow, old.measuredAtEpochMs).toInt()
            val y = metrics.yFor(old.valueMgDl).toInt()
            assertEquals(Color.BLACK, bitmap.getPixel(x, y))
            assertTrue((-1..1).any { bitmap.getPixel(x + 4 + it, y) != Color.BLACK })
            assertEquals(Color.WHITE, bitmap.getPixel(metrics.sampleXFor(input.timeWindow, active.measuredAtEpochMs).toInt(), metrics.yFor(active.valueMgDl).toInt()))
        } finally {
            bitmap.recycle()
        }
    }
}
