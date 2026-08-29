package app.aapswear.complications

import androidx.test.core.app.ApplicationProvider
import app.aapswear.model.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
