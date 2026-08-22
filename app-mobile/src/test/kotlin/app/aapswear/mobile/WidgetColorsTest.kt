package app.aapswear.mobile

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TargetState
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetColorsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clear() {
        context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `all glucose severities have deterministic independent roles`() {
        assertEquals(WidgetColorRole.URGENT_LOW, widgetGlucoseColorRole(40.0, 80.0, 160.0))
        assertEquals(WidgetColorRole.LOW, widgetGlucoseColorRole(79.0, 80.0, 160.0))
        assertEquals(WidgetColorRole.IN_RANGE, widgetGlucoseColorRole(120.0, 80.0, 160.0))
        assertEquals(WidgetColorRole.HIGH, widgetGlucoseColorRole(161.0, 80.0, 160.0))
        assertEquals(WidgetColorRole.VERY_HIGH, widgetGlucoseColorRole(400.0, 80.0, 160.0))
    }

    @Test
    fun `copy from graph is a snapshot and remains independent afterwards`() {
        val preferences = context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)
        val first = Color.rgb(19, 81, 177)
        val later = Color.rgb(210, 44, 51)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.CGM_DOT_IN_RANGE, first)

        WidgetColorStore.copyFromMobileGraph(context)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.CGM_DOT_IN_RANGE, later)

        assertEquals(first, WidgetColorStore.load(context).argb(WidgetColorRole.IN_RANGE))
        assertTrue(WidgetColorStore.hasOverride(context, WidgetColorRole.IN_RANGE))
        WidgetColorStore.reset(context, WidgetColorRole.IN_RANGE)
        assertFalse(WidgetColorStore.hasOverride(context, WidgetColorRole.IN_RANGE))
    }

    @Test
    fun `graph widget renders canonical points and is registered`() {
        val now = 10_000_000L
        val palette = WidgetPalette(WidgetColorRole.entries.associateWith { role ->
            when (role) {
                WidgetColorRole.IN_RANGE -> Color.rgb(17, 231, 93)
                WidgetColorRole.BACKGROUND -> Color.rgb(12, 15, 18)
                WidgetColorRole.TEXT -> Color.WHITE
                else -> Color.rgb(230, 80, 80)
            }
        })
        val state = TherapyDisplayState(
            source = DataSourceId.ANDROID_APS,
            receivedAtEpochMs = now,
            glucose = GlucoseState(120.0, GlucoseUnit.MG_DL, Trend.FLAT, now - 60_000L),
            glucoseHistory = listOf(
                GlucoseSample(110.0, now - 10 * 60_000L),
                GlucoseSample(120.0, now - 5 * 60_000L),
            ),
            target = TargetState(lowMgDl = 80.0, highMgDl = 160.0),
        )

        val bitmap = renderWidgetGraph(state, palette, width = 400, height = 180, now = now)

        assertEquals(400, bitmap.width)
        assertEquals(180, bitmap.height)
        assertFalse(bitmap.isRecycled)
        assertEquals(3, canonicalWidgetSamples(state, now).size)
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, GraphWidgetReceiver::class.java),
            PackageManager.GET_META_DATA,
        )
        assertNotNull(receiver)
        assertNotNull(receiver.metaData)
    }

    @Test
    fun `freshness state is always explicit`() {
        assertEquals("AKTUELL", widgetFreshnessStatus(app.aapswear.model.Freshness.CURRENT))
        assertEquals("VERZÖGERT", widgetFreshnessStatus(app.aapswear.model.Freshness.DELAYED))
        assertEquals("VERALTET", widgetFreshnessStatus(app.aapswear.model.Freshness.STALE))
        assertEquals("SENSORFEHLER", widgetFreshnessStatus(app.aapswear.model.Freshness.ERROR))
        assertEquals("KEINE DATEN", widgetFreshnessStatus(app.aapswear.model.Freshness.NO_DATA))
    }

    @Test
    fun `light colored icons alone receive the silhouette`() {
        assertTrue(shouldOutlineSugarliciousIcon(isLight = true, colored = true))
        assertFalse(shouldOutlineSugarliciousIcon(isLight = false, colored = true))
        assertFalse(shouldOutlineSugarliciousIcon(isLight = true, colored = false))
    }
}
