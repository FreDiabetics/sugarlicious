package app.aapswear.mobile

import android.graphics.Paint
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetGraphAxisBoundsTest {
    @Test
    fun `time labels avoid the bottom rounded corners including compact composition`() {
        listOf(360f to 60f, 440f to 60f, 800f to 60f).forEach { (width, radius) ->
            val labelWidth = 32f
            val left = widgetTimeAxisLabelX(8f, labelWidth, false, width, 4f, radius)
            val right = widgetTimeAxisLabelX(width - 8f, labelWidth, true, width, 4f, radius)
            assertTrue(left - labelWidth / 2f >= radius)
            assertTrue(right <= width - radius)
        }
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
