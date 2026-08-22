package app.aapswear.mobile

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverviewGraphDefaultsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `previous automatic twenty four hour migration returns overview to three hours`() {
        val preferences = context.getSharedPreferences("overview_graph_default_migration", android.content.Context.MODE_PRIVATE)
        preferences.edit()
            .clear()
            .putBoolean("graphHoursDefault24MigratedV4", true)
            .putInt("graphHours", 24)
            .commit()

        assertEquals(3, resolveOverviewGraphHoursPreference(preferences, 24))
        assertEquals(3, preferences.getInt("graphHours", -1))
    }

    @Test
    fun `explicit non legacy overview duration is preserved`() {
        val preferences = context.getSharedPreferences("overview_graph_explicit_duration", android.content.Context.MODE_PRIVATE)
        preferences.edit()
            .clear()
            .putInt("graphHours", 12)
            .commit()

        assertEquals(12, resolveOverviewGraphHoursPreference(preferences, 12))
        assertEquals(12, preferences.getInt("graphHours", -1))
    }
}
