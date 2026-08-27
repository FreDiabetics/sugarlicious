package app.aapswear.mobile

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Bitmap
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TargetState
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetPresentationTest {
    private val now = 20_000_000L
    private val thresholds = app.aapswear.model.CgmThresholds.DEFAULT
    private val palette = WidgetPalette(WidgetColorRole.entries.associateWith { role ->
        when (role) {
            WidgetColorRole.GRAPH_BACKGROUND, WidgetColorRole.BACKGROUND -> Color.BLACK
            WidgetColorRole.IN_RANGE, WidgetColorRole.TEXT, WidgetColorRole.AXIS -> Color.WHITE
            WidgetColorRole.HIGH, WidgetColorRole.RANGE_HIGH, WidgetColorRole.HIGH_LINE -> Color.YELLOW
            WidgetColorRole.LOW, WidgetColorRole.RANGE_LOW, WidgetColorRole.LOW_LINE -> Color.RED
            else -> Color.GRAY
        }
    })

    @Test
    fun `responsive layout covers narrow wide low and high surfaces`() {
        assertEquals(WidgetWidthClass.VERY_NARROW, responsiveWidgetLayout(90f, 80f).widthClass)
        assertEquals(WidgetHeightClass.LOW, responsiveWidgetLayout(90f, 80f).heightClass)
        assertEquals(WidgetWidthClass.VERY_WIDE, responsiveWidgetLayout(620f, 260f).widthClass)
        assertEquals(WidgetHeightClass.HIGH, responsiveWidgetLayout(620f, 260f).heightClass)
        assertTrue(responsiveWidgetLayout(90f, 80f).glucoseTextSp < responsiveWidgetLayout(400f, 220f).glucoseTextSp)
    }

    @Test
    fun `first high remains in range and second high activates high`() {
        val first = state(listOf(sample(150.0, -10), sample(190.0, -5)), 190.0)
        val second = state(listOf(sample(190.0, -10), sample(195.0, -5)), 195.0)
        assertEquals(WidgetColorRole.IN_RANGE, presentation(first).visibleRole)
        assertEquals(WidgetColorRole.HIGH, presentation(second).visibleRole)
    }

    @Test
    fun `first low remains in range and second low activates low`() {
        val first = state(listOf(sample(100.0, -10), sample(65.0, -5)), 65.0)
        val second = state(listOf(sample(65.0, -10), sample(60.0, -5)), 60.0)
        assertEquals(WidgetColorRole.IN_RANGE, presentation(first).visibleRole)
        assertEquals(WidgetColorRole.LOW, presentation(second).visibleRole)
    }

    @Test
    fun `return in range resets immediately and duplicate cannot confirm`() {
        val returned = state(listOf(sample(190.0, -15), sample(195.0, -10), sample(150.0, -5)), 150.0)
        val duplicate = sample(190.0, -5, sequence = 7)
        val duplicates = state(listOf(duplicate, duplicate.copy()), 190.0)
        assertEquals(WidgetColorRole.IN_RANGE, presentation(returned).visibleRole)
        assertEquals(WidgetColorRole.IN_RANGE, presentation(duplicates).visibleRole)
    }

    @Test
    fun `stale glucose is hidden and renders no colored current value`() {
        val stale = state(listOf(sample(190.0, -40), sample(195.0, -35)), 195.0, currentMinutes = -35)
        assertEquals(WidgetColorRole.TEXT, presentation(stale).visibleRole)
    }

    @Test
    fun `graph and glucose render at every requested surface without card inset`() {
        listOf(96 to 72, 160 to 90, 260 to 140, 420 to 180, 640 to 260).forEach { (width, height) ->
            val state = state(listOf(sample(115.0, -10), sample(120.0, -5)), 120.0)
            val graph = renderWidgetGraph(state, palette, width, height, now, thresholds)
            val glucose = renderMinimalGlucoseWidget(state, palette, width, height, now, thresholds)
            assertEquals(width, graph.width)
            assertEquals(height, graph.height)
            assertEquals(width, glucose.width)
            assertEquals(height, glucose.height)
            assertFalse(graph.isRecycled)
            assertFalse(glucose.isRecycled)
        }
    }

    @Test
    fun `graph metrics reserve complete axes and keep mobile dot proportions`() {
        listOf(96 to 72, 160 to 90, 260 to 140, 420 to 180, 640 to 260).forEach { (width, height) ->
            val layout = responsiveWidgetLayout(width.toFloat(), height.toFloat())
            val metrics = widgetGraphMetrics(width, height, 1f, layout, Paint())
            assertEquals(0f, metrics.plot.left, 0.01f)
            assertTrue(metrics.plot.right < width)
            assertTrue(metrics.plot.bottom < height)
            assertTrue(metrics.plot.width() > 0f)
            assertTrue(metrics.plot.height() > 0f)
            assertEquals(2.4f, metrics.dotRadiusPx, 0.01f)
            assertEquals(0.95f, metrics.outlineWidthPx, 0.01f)
        }
    }

    @Test
    fun `trend arrow keeps the same vector scale in every direction`() {
        val targetHeight = 48f
        val scales = listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)
            .map { rotation -> normalizedTrendArrowGeometry(targetHeight, rotation, 1).scalePx }
        scales.forEach { scale -> assertEquals(scales.first(), scale, 0.0001f) }
        assertEquals(targetHeight, 83.65f * scales.first(), 0.05f)
    }

    @Test
    fun `render hardware resize regression matrix when requested`() {
        val output = System.getenv("WIDGET_MATRIX_DIR")?.let(::File) ?: return
        output.mkdirs()
        val state = state(
            history = (0..36).map { index -> sample(105.0 + index * 1.8, -180 + index * 5) },
            current = 170.0,
            currentMinutes = 0,
        )
        val density = 2f
        listOf(
            "very-small" to (192 to 144),
            "small" to (320 to 180),
            "medium" to (520 to 280),
            "large" to (840 to 360),
            "extra-wide" to (1280 to 520),
        ).forEach { (name, dimensions) ->
            val (width, height) = dimensions
            val bitmap = renderWidgetGraph(
                state = state,
                palette = palette,
                width = width,
                height = height,
                now = now,
                thresholds = thresholds,
                layout = responsiveWidgetLayout(width / density, height / density),
                pixelDensity = density,
            )
            FileOutputStream(File(output, "$name.png")).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        }
    }

    private fun presentation(state: TherapyDisplayState) =
        widgetRangePresentation(state, canonicalWidgetSamples(state, now), thresholds, now)

    private fun sample(value: Double, minutes: Int, sequence: Long? = null) = GlucoseSample(
        valueMgDl = value,
        measuredAtEpochMs = now + minutes * 60_000L,
        source = DataSourceId.ANDROID_APS,
        sensorId = "sensor",
        sessionId = "session",
        sequenceNumber = sequence,
    )

    private fun state(
        history: List<GlucoseSample>,
        current: Double,
        currentMinutes: Int = -5,
    ) = TherapyDisplayState(
        source = DataSourceId.ANDROID_APS,
        receivedAtEpochMs = now,
        glucose = GlucoseState(current, GlucoseUnit.MG_DL, Trend.FORTY_FIVE_UP, now + currentMinutes * 60_000L),
        glucoseHistory = history,
        target = TargetState(lowMgDl = thresholds.lowMgDl, highMgDl = thresholds.highMgDl),
    )
}
