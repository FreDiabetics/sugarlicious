package app.aapswear.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverviewGraphRegressionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `legacy forced three hour overview migrates once to twenty four hours`() {
        val preferences = context.getSharedPreferences("overview_graph_migration", Context.MODE_PRIVATE)
        preferences.edit()
            .clear()
            .putBoolean("graphHoursDefault3Migrated", true)
            .putInt("graphHours", 3)
            .commit()

        val resolved = resolveOverviewGraphHoursPreference(preferences, 3)

        assertEquals(24, resolved)
        assertEquals(24, preferences.getInt("graphHours", -1))
        assertTrue(preferences.getBoolean("graphHoursDefault24MigratedV4", false))
    }

    @Test
    fun `explicit three hour choice survives after twenty four hour migration`() {
        val preferences = context.getSharedPreferences("overview_graph_explicit", Context.MODE_PRIVATE)
        preferences.edit()
            .clear()
            .putBoolean("graphHoursDefault24MigratedV4", true)
            .putInt("graphHours", 3)
            .commit()

        assertEquals(3, resolveOverviewGraphHoursPreference(preferences, 3))
    }

    @Test
    fun `requested twenty four hour viewport restores after asynchronous history arrives`() {
        val hourMs = 60L * 60_000L
        val viewport = ChartViewport(24)

        viewport.setAvailablePastWindow(hourMs)
        assertEquals(1f, viewport.hours, 0.001f)

        viewport.setAvailablePastWindow(24L * hourMs)
        assertEquals(24f, viewport.hours, 0.001f)
    }

    @Test
    fun `actual and prediction dot extents stay clear of now divider`() {
        val divider = 100f
        val radius = 4f
        val outline = 2f
        val safety = 2f
        val actualCenter = graphCenterBeforeDivider(divider, radius, outline, safety)
        val predictionCenter = graphCenterAfterDivider(divider, radius, outline, safety)

        val actualRightEdge = actualCenter + radius + outline / 2f
        val predictionLeftEdge = predictionCenter - radius - outline / 2f

        assertEquals(divider - safety, actualRightEdge, 0.001f)
        assertEquals(divider + safety, predictionLeftEdge, 0.001f)
        assertTrue(predictionLeftEdge > actualRightEdge)
    }
}
