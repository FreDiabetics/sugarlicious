package app.aapswear.mobile

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TargetState
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetGraphVisualQaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val palette = WidgetColorStore.load(context)
    private val now = 2_000_000_000L
    private val density = 2f

    @Test
    fun `visual QA matrix renders exact launcher sizes without bitmap stretching`() {
        val outputDir = File("build/reports/widget-visual-qa").apply { mkdirs() }
        val matrix =
            listOf(
                "very-small" to (220 to 140),
                "small" to (320 to 200),
                "wide" to (760 to 260),
                "medium" to (440 to 280),
                "large" to (680 to 420),
                "very-large" to (960 to 620),
                "tall" to (360 to 560),
            )
        val state = stateWithTerminalValues(118.0, 124.0)

        matrix.forEach { (name, size) ->
            val bitmap = render(state, size.first, size.second)
            assertEquals(size.first, bitmap.width)
            assertEquals(size.second, bitmap.height)
            val file = File(outputDir, "android-graph-widget-$name.png")
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            assertTrue(file.isFile)
            assertTrue(file.length() > 0L)
        }
    }

    @Test
    fun `widget uses same two-reading high activation and clears on first in-range reading`() {
        val width = 440
        val height = 280
        val metrics = WidgetGraphLayoutMetrics.resolve(width, height, density, density)
        val sampleX = (metrics.plotLeftPx + metrics.plotRightPx) / 2f
        val sampleY = (metrics.plotTopPx + widgetGlucoseYForTest(160.0, metrics.plotRect)) / 2f
        val x = sampleX.toInt().coerceIn(0, width - 1)
        val y = sampleY.toInt().coerceIn(0, height - 1)

        val oneHigh = render(stateFromHistory(listOf(120.0, 166.0)), width, height)
        val twoHigh = render(stateFromHistory(listOf(166.0, 171.0)), width, height)
        val backInRange = render(stateFromHistory(listOf(166.0, 171.0, 158.0)), width, height)

        assertNotEquals(oneHigh.getPixel(x, y), twoHigh.getPixel(x, y))
        assertEquals(oneHigh.getPixel(x, y), backInRange.getPixel(x, y))
    }

    @Test
    fun `latest dot remains inside plot and never reaches y axis gutter`() {
        val width = 760
        val height = 260
        val metrics = WidgetGraphLayoutMetrics.resolve(width, height, density, density)
        val maxDotCenter = metrics.plotRightPx - metrics.dotRadiusPx - metrics.dotOutlineWidthPx / 2f - metrics.gridStrokePx

        assertTrue(maxDotCenter + metrics.dotRadiusPx + metrics.dotOutlineWidthPx / 2f < metrics.yAxisLeftPx)
        assertTrue(metrics.plotRightPx < metrics.yAxisLeftPx)
        assertTrue(metrics.yAxisLeftPx < width)
    }

    private fun render(state: TherapyDisplayState, width: Int, height: Int): Bitmap =
        renderWidgetGraph(
            state = state,
            palette = palette,
            width = width,
            height = height,
            now = now,
            density = density,
            scaledDensity = density,
        )

    private fun stateWithTerminalValues(previous: Double, latest: Double): TherapyDisplayState =
        stateFromHistory(listOf(112.0, 116.0, previous, latest))

    private fun stateFromHistory(values: List<Double>): TherapyDisplayState {
        val points =
            values.mapIndexed { index, value ->
                val minutesBack = (values.lastIndex - index) * 5L
                GlucoseSample(valueMgDl = value, measuredAtEpochMs = now - minutesBack * 60_000L)
            }
        val latest = points.last()
        return TherapyDisplayState(
            source = DataSourceId.ANDROID_APS,
            receivedAtEpochMs = now,
            glucose =
                GlucoseState(
                    valueMgDl = latest.valueMgDl,
                    displayUnit = GlucoseUnit.MG_DL,
                    trend = Trend.FLAT,
                    measuredAtEpochMs = latest.measuredAtEpochMs,
                ),
            glucoseHistory = points.dropLast(1),
            target = TargetState(lowMgDl = 80.0, highMgDl = 160.0),
        )
    }

    private fun widgetGlucoseYForTest(valueMgDl: Double, plot: android.graphics.RectF): Float =
        plot.bottom - glucoseLogRatio(valueMgDl).toFloat() * plot.height()
}
