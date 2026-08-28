package app.aapswear.mobile

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WidgetInstanceConfigurationTest {
    @Test
    fun `configuration options follow the concrete widget provider`() {
        assertEquals(ConfigurableWidgetKind.GLUCOSE, configurableWidgetKind("app.aapswear.mobile.GlucoseWidgetReceiver"))
        assertEquals(ConfigurableWidgetKind.GRAPH, configurableWidgetKind("app.aapswear.mobile.GraphWidgetReceiver"))
        assertEquals(ConfigurableWidgetKind.GLUCOSE_GRAPH, configurableWidgetKind("app.aapswear.mobile.GlucoseGraphWidgetReceiver"))
        assertFalse(ConfigurableWidgetKind.GLUCOSE.hasGraph)
        assertTrue(ConfigurableWidgetKind.GRAPH.hasGraph)
    }

    @Test
    fun `target scale moves right only for a pure cgm graph`() {
        assertTrue(targetScaleOnRight(false, false, false, false))
        assertFalse(targetScaleOnRight(true, false, false, false))
        assertFalse(targetScaleOnRight(false, true, false, false))
        assertFalse(targetScaleOnRight(false, false, true, false))
        assertFalse(targetScaleOnRight(false, false, false, true))
    }
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `two widget instances retain independent appearance and graph settings`() {
        val first = WidgetInstanceConfiguration(
            6, true, WidgetScaleMode.DYNAMIC, Color.argb(120, 10, 20, 30), "app.aapswear",
            backgroundEnabled = false, glucoseScalePercent = 88, trendScalePercent = 112,
            colorOverrides = mapOf(WidgetColorRole.TREND_HIGH to Color.MAGENTA),
        )
        val second = WidgetInstanceConfiguration(
            24, false, WidgetScaleMode.LOGARITHMIC, Color.BLACK, "com.eveningoutpost.dexdrip",
            colorOverrides = mapOf(WidgetColorRole.DOT_IN_RANGE to Color.GREEN),
        )
        WidgetInstanceConfigurationStore.save(context, 101, first)
        WidgetInstanceConfigurationStore.save(context, 202, second)

        assertEquals(first, WidgetInstanceConfigurationStore.read(context, 101))
        assertEquals(second, WidgetInstanceConfigurationStore.read(context, 202))
        assertNotEquals(WidgetInstanceConfigurationStore.read(context, 101), WidgetInstanceConfigurationStore.read(context, 202))
    }

    @Test
    fun `legacy global tap target is captured independently by each widget`() {
        val sugarlicious = WidgetLaunchTarget("app.aapswear", "Sugarlicious")
        val xdrip = WidgetLaunchTarget("com.eveningoutpost.dexdrip", "xDrip+")
        try {
            WidgetLaunchTargetStore.select(context, xdrip)
            val migrated = WidgetInstanceConfigurationStore.read(context, 707)
            assertEquals(xdrip.packageName, migrated.launchPackage)
            WidgetInstanceConfigurationStore.save(context, 707, migrated)

            WidgetLaunchTargetStore.select(context, sugarlicious)
            assertEquals(xdrip.packageName, WidgetInstanceConfigurationStore.read(context, 707).launchPackage)
            assertEquals(sugarlicious.packageName, WidgetInstanceConfigurationStore.read(context, 808).launchPackage)
        } finally {
            WidgetInstanceConfigurationStore.delete(context, 707)
            WidgetInstanceConfigurationStore.delete(context, 808)
            WidgetLaunchTargetStore.select(context, sugarlicious)
        }
    }

    @Test
    fun `deleting one widget leaves the other configuration intact`() {
        val first = WidgetInstanceConfiguration(graphHours = 12)
        val second = WidgetInstanceConfiguration(graphHours = 2)
        WidgetInstanceConfigurationStore.save(context, 303, first)
        WidgetInstanceConfigurationStore.save(context, 404, second)

        WidgetInstanceConfigurationStore.delete(context, 303)

        assertEquals(WidgetInstanceConfiguration(), WidgetInstanceConfigurationStore.read(context, 303))
        assertEquals(second, WidgetInstanceConfigurationStore.read(context, 404))
    }

    @Test
    fun `standard and pill shapes remain independent per widget instance`() {
        val standard = WidgetInstanceConfiguration(shapeMode = WidgetShapeMode.STANDARD, cornerRadiusDp = 24)
        val pill = WidgetInstanceConfiguration(shapeMode = WidgetShapeMode.PILL)
        WidgetInstanceConfigurationStore.save(context, 505, standard)
        WidgetInstanceConfigurationStore.save(context, 606, pill)

        assertEquals(standard, WidgetInstanceConfigurationStore.read(context, 505))
        assertEquals(pill, WidgetInstanceConfigurationStore.read(context, 606))
    }

    @Test
    fun `combined widget keeps value graph ratio and unit setting per instance`() {
        val compactValue = WidgetInstanceConfiguration(glucoseGraphValuePercent = 22, showGlucoseUnit = false)
        val largeValue = WidgetInstanceConfiguration(glucoseGraphValuePercent = 44, showGlucoseUnit = true)
        WidgetInstanceConfigurationStore.save(context, 909, compactValue)
        WidgetInstanceConfigurationStore.save(context, 910, largeValue)

        assertEquals(compactValue, WidgetInstanceConfigurationStore.read(context, 909))
        assertEquals(largeValue, WidgetInstanceConfigurationStore.read(context, 910))
        assertTrue(combinedWidgetTopHeight(400, 22) < combinedWidgetTopHeight(400, 44))
    }

    @Test
    fun `pill radius tracks half of every resized widget height`() {
        val pill = WidgetInstanceConfiguration(shapeMode = WidgetShapeMode.PILL)
        assertEquals(40f, resolveWidgetCornerRadiusDp(pill, 80f), 0.01f)
        assertEquals(75f, resolveWidgetCornerRadiusDp(pill, 150f), 0.01f)
    }

    @Test
    fun `standard shape uses custom radius or samsung compatible fallback`() {
        assertEquals(
            SAMSUNG_WIDGET_RADIUS_FALLBACK_DP,
            resolveWidgetCornerRadiusDp(WidgetInstanceConfiguration(), 120f),
            0.01f,
        )
        assertEquals(
            18f,
            resolveWidgetCornerRadiusDp(WidgetInstanceConfiguration(cornerRadiusDp = 18), 120f),
            0.01f,
        )
    }

    @Test
    fun `all y scale modes keep target and readings inside the plot`() {
        val plot = android.graphics.RectF(0f, 0f, 100f, 100f)
        WidgetScaleMode.entries.forEach { mode ->
            val scale = widgetYScale(mode, listOf(55.0, 120.0, 260.0), 80.0, 160.0)
            listOf(55.0, 80.0, 120.0, 160.0, 260.0).forEach { value ->
                check(scale.map(value, plot) in 0f..100f)
            }
        }
    }
}
