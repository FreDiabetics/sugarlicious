package app.aapswear.mobile

import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetGraphAxisBoundsTest {
    @Test
    fun `time labels clear the measured ink contour including compact composition`() {
        listOf(360f to 60f, 440f to 60f, 800f to 60f).forEach { (width, radius) ->
            val labelWidth = 32f
            val left = widgetTimeAxisLabelX(8f, labelWidth, false, width, 4f, radius)
            val right = widgetTimeAxisLabelX(width - 8f, labelWidth, true, width, 4f, radius)
            assertTrue(left - labelWidth / 2f >= radius)
            assertTrue(right <= width - radius)
        }
    }

    @Test
    fun `corner allowance uses actual label ink instead of unused font descender space`() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f }
        val ink = android.graphics.Rect().also { paint.getTextBounds("3h", 0, 2, it) }
        val inset = widgetTimeAxisInkInset(60f, 140f, 130f, "3h", paint)
        val fontInset = roundedWidgetContourInset(60f, 140f - 130f - paint.fontMetrics.descent)
        assertTrue("unused descender space must not push the label right", inset < fontInset)
        assertEquals(roundedWidgetContourInset(60f, 140f - 130f - ink.bottom), inset, 0.01f)
    }

    @Test
    fun `3h and jetzt labels stay fully within widget bounds`() {
        val density = 2f
        listOf(
            220 to 140,
            320 to 200,
            440 to 280,
            680 to 420,
            760 to 260,
            960 to 620,
            360 to 560,
        ).forEach { (width, height) ->
            val metrics = WidgetGraphLayoutMetrics.resolve(width, height, density, density)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = metrics.axisTextPx }
            val leftTextWidth = paint.measureText("3h")
            val rightTextWidth = paint.measureText("jetzt")

            assertTrue("3h clipped at ${width}x$height", metrics.plotLeftPx + leftTextWidth <= width)
            assertTrue("jetzt clipped at ${width}x$height", metrics.plotRightPx - rightTextWidth >= 0f)
            assertTrue("right axis reserve missing at ${width}x$height", metrics.plotRightPx + rightTextWidth < width + rightTextWidth)
        }
    }
}
