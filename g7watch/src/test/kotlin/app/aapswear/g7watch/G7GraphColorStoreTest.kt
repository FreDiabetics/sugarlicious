package app.aapswear.g7watch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.aapswear.protocol.WatchColorSync
import app.aapswear.protocol.WatchGraphColors
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class G7GraphColorStoreTest {
    @Test
    fun `explicit graph color sync preserves independent semantic roles`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("g7_graph_colors", Context.MODE_PRIVATE).edit().clear().commit()
        val colors = WatchGraphColors(
            highLine = 0xFF110001.toInt(),
            lowLine = 0xFF220002.toInt(),
            axisLabel = 0xFF330003.toInt(),
            axisTick = 0xFF440004.toInt(),
            nowLine = 0xFF550005.toInt(),
            divider = 0xFF660006.toInt(),
        )

        G7GraphColorStore(context).save(
            WatchColorSync(graphColors = colors, sentAtEpochMs = 1234L),
        )

        assertEquals(colors, G7GraphColorStore(context).read())
    }
}
