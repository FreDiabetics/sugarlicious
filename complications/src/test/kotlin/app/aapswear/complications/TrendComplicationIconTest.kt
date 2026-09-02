package app.aapswear.complications

import androidx.test.core.app.ApplicationProvider
import app.aapswear.model.Trend
import app.aapswear.model.AppearanceMode
import app.aapswear.model.TrendArrowStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrendComplicationIconTest {
    @Test
    fun `all complication trends preserve supplied canvas geometry`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expected = mapOf(
            Trend.DOUBLE_UP to 125,
            Trend.SINGLE_UP to 60,
            Trend.FORTY_FIVE_UP to 60,
            Trend.FLAT to 60,
            Trend.FORTY_FIVE_DOWN to 60,
            Trend.SINGLE_DOWN to 60,
            Trend.DOUBLE_DOWN to 125,
        )
        expected.forEach { (trend, expectedWidth) ->
            val bitmap = TrendComplicationIcon.render(context, trend, 60)
            assertNotNull(bitmap)
            assertEquals(60, bitmap!!.height)
            assertEquals(expectedWidth, bitmap.width)
        }
    }

    @Test
    fun scaledDoubleArrowsKeepIntrinsicAspectRatioAtAllSupportedScales() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        listOf(70, 100, 150, 200).forEach { scale ->
            listOf(Trend.DOUBLE_UP, Trend.DOUBLE_DOWN).forEach { trend ->
                val bitmap = requireNotNull(TrendComplicationIcon.renderScaled(context, trend, 60, scale))
                assertEquals(125f / 60f, bitmap.width.toFloat() / bitmap.height, 0.02f)
            }
        }
    }

    @Test
    fun complicationScaleUsesMostOfHostIconAtDefaultAndStillGrows() {
        val small = TrendComplicationIcon.glyphFillFraction(70)
        val default = TrendComplicationIcon.glyphFillFraction(100)
        val large = TrendComplicationIcon.glyphFillFraction(200)

        assertEquals(0.70f, small, 0.001f)
        assertTrue(default >= 0.75f)
        assertTrue(default > small)
        assertEquals(1.0f, large, 0.001f)
    }

    @Test fun `runtime renderer accepts the same outline style used by previews`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val style = TrendArrowStyle.defaults(AppearanceMode.DARK, 0xFFFFFFFF.toInt()).copy(outlineEnabled = false, alpha = 0.6f)
        val bitmap = TrendComplicationIcon.renderScaled(context, Trend.FLAT, 60, 100, style = style)
        assertNotNull(bitmap)
        assertTrue(bitmap!!.hasAlpha())
    }
}
