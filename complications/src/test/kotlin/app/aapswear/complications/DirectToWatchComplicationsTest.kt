package app.aapswear.complications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.aapswear.model.CgmQuality
import app.aapswear.model.DataSourceId
import app.aapswear.model.FreshnessPolicy
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.uishared.SharedWearCgmGraphStyle
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DirectToWatchComplicationsTest {
    private val now = 1_800_000_000_000L

    @Test fun `fresh watch direct renders glucose trend then delta and unit`() {
        val header = DirectToWatchPresentationFormatter.header(directState(now - 2 * 60_000L), now)
        assertEquals("152", header.glucose)
        assertEquals("+1 mg/dL", header.secondary)
        assertEquals(Trend.FLAT, header.trend)
    }

    @Test fun `stale direct value is not rendered as current`() {
        val header = DirectToWatchPresentationFormatter.header(directState(now - 16 * 60_000L), now)
        assertEquals("—", header.glucose)
        assertEquals("STALE", header.secondary)
    }

    @Test fun `mobile fresh is never shown as direct`() {
        val mobile = directState(now - 60_000L).copy(
            source = DataSourceId.ANDROID_APS,
            sourceContract = "CANONICAL_CGM_V2:MOBILE_PRIMARY:test",
            glucose = directState(now - 60_000L).glucose?.copy(source = DataSourceId.ANDROID_APS),
        )
        val header = DirectToWatchPresentationFormatter.header(mobile, now)
        assertEquals("—", header.glucose)
        assertEquals("NO_SOURCE", header.secondary)
        assertTrue(DirectToWatchPresentationFormatter.samples(mobile, now, 3).isEmpty())
    }

    @Test fun `absent data is explicit no source`() {
        assertEquals("NO_SOURCE", DirectToWatchPresentationFormatter.header(null, now).secondary)
        assertEquals("3h • NO_SOURCE", DirectToWatchPresentationFormatter.graphStatus(null, now, 3).text)
    }

    @Test fun `invalid delta is not invented`() {
        val state = directState(now - 60_000L).copy(glucose = directState(now - 60_000L).glucose?.copy(deltaMgDl = null))
        assertEquals("mg/dL", DirectToWatchPresentationFormatter.header(state, now).secondary)
    }

    @Test fun `graph accepts only valid direct samples inside selected window`() {
        val state = directState(now - 60_000L).copy(
            glucoseHistory = listOf(
                GlucoseSample(110.0, now - 4 * 60 * 60_000L, source = DataSourceId.DEXCOM_G7_WATCH),
                GlucoseSample(120.0, now - 2 * 60 * 60_000L, source = DataSourceId.DEXCOM_G7_WATCH),
                GlucoseSample(130.0, now - 10 * 60_000L, source = DataSourceId.ANDROID_APS),
                GlucoseSample(140.0, now - 5 * 60_000L, source = DataSourceId.DEXCOM_G7_WATCH, quality = CgmQuality.INVALID),
            ),
        )
        val samples = DirectToWatchPresentationFormatter.samples(state, now, 3)
        assertTrue(samples.any { it.valueMgDl == 120.0 })
        assertTrue(samples.none { it.valueMgDl in setOf(110.0, 130.0, 140.0) })
    }

    @Test fun `fresh payload expires when it becomes stale`() {
        val measuredAt = now - 2 * 60_000L
        val range = DirectToWatchPresentationFormatter.validTimeRange(directState(measuredAt), now)
        val staleAt = measuredAt + FreshnessPolicy.DELAYED_MAX_MS
        assertTrue(Instant.ofEpochMilli(staleAt) in range)
        assertFalse(Instant.ofEpochMilli(staleAt + 1) in range)
    }

    @Test fun `graph scale cycles in its own persistence file`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(DirectToWatchPreferences.NAME, Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(3, DirectToWatchPreferences.graphHours(context))
        assertEquals(6, DirectToWatchPreferences.cycleGraphHours(context))
        assertEquals(6, DirectToWatchPreferences.graphHours(context))
        assertFalse(context.getSharedPreferences("watch_display", Context.MODE_PRIVATE).contains("graph.hours"))
    }

    @Test fun `graph appearance persists and resets only in direct watch preferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val direct = context.getSharedPreferences(DirectToWatchPreferences.NAME, Context.MODE_PRIVATE)
        val shared = context.getSharedPreferences("watch_display", Context.MODE_PRIVATE)
        direct.edit().clear().commit()
        shared.edit().putInt("graph_color_background", 0xFF010203.toInt()).commit()

        val colors = WatchGraphColors().copy(
            graphBackground = 0xFF112233.toInt(),
            rangeInRange = 0xFF445566.toInt(),
            cgmInRange = 0xFF778899.toInt(),
        )
        val style = SharedWearCgmGraphStyle(dotRadiusDp = 4.2f, dotOutlineEnabled = false, dotOutlineWidthDp = 1.7f)
        DirectToWatchPreferences.saveGraphColors(context, colors)
        DirectToWatchPreferences.saveGraphStyle(context, style)

        assertEquals(colors.graphBackground, DirectToWatchPreferences.graphColors(context).graphBackground)
        assertEquals(colors.rangeInRange, DirectToWatchPreferences.graphColors(context).rangeInRange)
        assertEquals(4.2f, DirectToWatchPreferences.graphStyle(context).dotRadiusDp)
        assertFalse(DirectToWatchPreferences.graphStyle(context).dotOutlineEnabled)
        assertEquals(0xFF010203.toInt(), shared.getInt("graph_color_background", 0))

        DirectToWatchPreferences.resetGraphAppearance(context)
        assertEquals(WatchGraphColors().graphBackground, DirectToWatchPreferences.graphColors(context).graphBackground)
        assertEquals(SharedWearCgmGraphStyle().dotRadiusDp, DirectToWatchPreferences.graphStyle(context).dotRadiusDp)
        assertEquals(0xFF010203.toInt(), shared.getInt("graph_color_background", 0))
    }

    private fun directState(measuredAt: Long) = TherapyDisplayState(
        source = DataSourceId.DEXCOM_G7_WATCH,
        sourceContract = "CANONICAL_CGM_V2:WATCH_DIRECT:test",
        receivedAtEpochMs = now,
        glucose = GlucoseState(
            valueMgDl = 152.0,
            displayUnit = GlucoseUnit.MG_DL,
            trend = Trend.FLAT,
            measuredAtEpochMs = measuredAt,
            deltaMgDl = 1.0,
            source = DataSourceId.DEXCOM_G7_WATCH,
        ),
    )
}
