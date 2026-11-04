package app.aapswear.mobile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinalOverviewAppearanceTest {
    @Test fun `notification icon uses light accent in light mode and dark accent in dark mode`() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        listOf(false, true).forEach { dark ->
            val context =
                app.createConfigurationContext(
                    Configuration(app.resources.configuration).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    },
                )
            val expected = (if (dark) dynamicLightColorScheme(context) else dynamicDarkColorScheme(context)).primary.toArgb()
            assertEquals(expected, SamsungLiveNotificationAdapter.systemNotificationIconColor(context))
            val background = (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary.toArgb()
            assertEquals(background, SamsungLiveNotificationAdapter.systemAccentColor(context))
        }
    }

    @Test fun `glucose underlay is neutral opaque gray lighter than the tile surface`() {
        listOf(0xff191919.toInt(), 0xff303030.toInt(), 0xfffdfdfd.toInt()).forEach { tile ->
            val track = glucoseBarTrackArgb(tile)
            assertEquals(255, Color.alpha(track))
            assertEquals(Color.red(track), Color.green(track))
            assertEquals(Color.red(track), Color.blue(track))
            assertTrue(Color.red(track) > Color.red(tile))
        }
    }
}
