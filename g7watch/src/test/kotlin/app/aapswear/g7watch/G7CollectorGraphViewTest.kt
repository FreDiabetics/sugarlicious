package app.aapswear.g7watch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.DataSourceId
import app.aapswear.model.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class G7CollectorGraphViewTest {
    private val now = 20_000_000L
    private val highArea = Color.rgb(208, 72, 48)
    private val background = Color.rgb(25, 25, 25)

    @Test
    fun `second consecutive high reading turns on configured high area`() {
        val oneHigh = render(listOf(reading("1", 166.0, now)))
        val twoHigh = render(
            listOf(
                reading("1", 166.0, now - 5 * 60_000L),
                reading("2", 171.0, now),
            ),
        )

        val x = 137
        val y = 12
        assertNotEquals(highArea, oneHigh.getPixel(x, y))
        assertEquals(highArea, twoHigh.getPixel(x, y))
    }

    @Test
    fun `return to target immediately removes high area`() {
        val graph = render(
            listOf(
                reading("1", 166.0, now - 10 * 60_000L),
                reading("2", 171.0, now - 5 * 60_000L),
                reading("3", 150.0, now),
            ),
        )

        assertNotEquals(highArea, graph.getPixel(137, 12))
    }

    private fun render(readings: List<CgmReading>): Bitmap {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val colors = G7AppearanceRole.entries.associateWith { it.defaultArgb }.toMutableMap().apply {
            this[G7AppearanceRole.GRAPH_BACKGROUND] = background
            this[G7AppearanceRole.GRAPH_HIGH_AREA] = highArea
            this[G7AppearanceRole.GRAPH_TARGET_AREA] = Color.rgb(88, 88, 88)
            this[G7AppearanceRole.GRAPH_HIGH_LINE] = Color.YELLOW
            this[G7AppearanceRole.GRAPH_LOW_LINE] = Color.RED
            this[G7AppearanceRole.GRAPH_GRID] = Color.TRANSPARENT
            this[G7AppearanceRole.GRAPH_AXIS_TEXT] = Color.TRANSPARENT
            this[G7AppearanceRole.GRAPH_TILE_BORDER] = Color.TRANSPARENT
            this[G7AppearanceRole.GRAPH_NOW_MARKER] = Color.TRANSPARENT
        }
        val view = G7CollectorGraphView(context)
        view.bind(
            readings = readings,
            palette = G7AppearancePalette(colors),
            graphHours = 3,
            nowEpochMs = now,
            targetLowMgDl = 80.0,
            targetHighMgDl = 160.0,
        )
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(150, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 400, 150)
        val bitmap = Bitmap.createBitmap(400, 150, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun reading(id: String, value: Double, measuredAt: Long) =
        CgmReading(
            id = id,
            source = DataSourceId.DEXCOM_G7_WATCH,
            sensorId = "sensor",
            sessionId = "session",
            glucoseMgDl = value,
            timestampEpochMs = measuredAt,
            receivedAtEpochMs = measuredAt + 1_000L,
            trend = Trend.FLAT,
            status = CgmReadingStatus.VALID,
        )
}
