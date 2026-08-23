package app.aapswear.g7watch

import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.DataSourceId
import app.aapswear.model.Trend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class G7WatchActivityLayoutTest {
    @Before
    fun resetGraphPeriod() {
        G7AppearanceStore(androidx.test.core.app.ApplicationProvider.getApplicationContext()).setGraphHours(3)
    }

    @Test
    fun `collector overview uses compact header unified system status and bottom settings`() {
        val activity = Robolectric.buildActivity(G7WatchActivity::class.java).create().start().resume().get()
        val texts = mutableListOf<String>()
        collectText(activity.findViewById(android.R.id.content), texts)

        assertFalse(texts.any { it.contains("3h Verlauf", ignoreCase = true) })
        assertTrue("3h" in texts)

        val systemIndex = texts.indexOf("SYSTEMSTATUS")
        val settingsIndex = texts.indexOf("Einstellungen")
        val titleIndex = texts.indexOf("G7 Direct to Watch")
        val brandIndex = texts.indexOf("by Sugarlicious")
        assertTrue(systemIndex >= 0)
        assertTrue(settingsIndex > systemIndex)
        assertTrue(titleIndex > settingsIndex)
        assertTrue(brandIndex > titleIndex)
        assertTrue(texts.contains("STATUSINFORMATIONEN"))
        assertTrue(texts.contains("TECHNISCHE DETAILS"))
        assertTrue(texts.contains("AKTIONEN"))
        assertTrue(texts.any { it == "Sensor einrichten" || it == "Sensor neu koppeln" })
        assertTrue(texts.any { it == "Collector starten" || it == "Collector stoppen" })
        assertFalse(texts.contains("←"))
        assertFalse(texts.contains("⚙"))

        assertFalse(containsNativeButton(activity.findViewById(android.R.id.content)))
        activity.finish()
    }

    @Test
    fun `new reading updates existing graph and preserves scroll position`() {
        val controller = Robolectric.buildActivity(G7WatchActivity::class.java).create().start().resume()
        val activity = controller.get()
        val before = findGraph(activity.findViewById(android.R.id.content))
        assertNotNull(before)
        val systemCardBefore = findText(activity.findViewById(android.R.id.content), "SYSTEMSTATUS")!!.parent
        val scroll = findScrollView(activity.findViewById(android.R.id.content))!!
        measureAndLayout(activity.findViewById(android.R.id.content))
        scroll.scrollTo(0, 120)

        val now = System.currentTimeMillis()
        runBlocking {
            G7ReadingDatabase(activity).insert(
                CgmReading(
                    id = "ui-refresh-${System.nanoTime()}",
                    source = DataSourceId.DEXCOM_G7_WATCH,
                    sensorId = "sensor-ui-refresh",
                    sessionId = "session-ui-refresh",
                    glucoseMgDl = 123.0,
                    timestampEpochMs = now,
                    receivedAtEpochMs = now + 1_000L,
                    trend = Trend.FLAT,
                    status = CgmReadingStatus.VALID,
                ),
            )
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val after = findGraph(activity.findViewById(android.R.id.content))
        assertNotNull(after)
        assertSame(before, after)
        assertSame(systemCardBefore, findText(activity.findViewById(android.R.id.content), "SYSTEMSTATUS")!!.parent)
        assertEquals(120, scroll.scrollY)

        controller.pause().stop().destroy()
    }

    @Test
    fun `period change updates only graph state and preserves screen and scroll`() {
        val activity = Robolectric.buildActivity(G7WatchActivity::class.java).create().start().resume().get()
        val rootBefore = activity.findViewById<android.view.View>(android.R.id.content).let { (it as ViewGroup).getChildAt(0) }
        val graphBefore = findGraph(rootBefore)!!
        val scroll = findScrollView(rootBefore)!!
        measureAndLayout(activity.findViewById(android.R.id.content))
        scroll.scrollTo(0, 120)

        findText(rootBefore, "3h")!!.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val rootAfter = activity.findViewById<android.view.View>(android.R.id.content).let { (it as ViewGroup).getChildAt(0) }
        assertSame(rootBefore, rootAfter)
        assertSame(graphBefore, findGraph(rootAfter))
        assertNotNull(findText(rootAfter, "6h"))
        assertEquals(120, scroll.scrollY)
        activity.finish()
    }

    private fun collectText(view: android.view.View, output: MutableList<String>) {
        if (view is TextView) output += view.text?.toString().orEmpty()
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectText(view.getChildAt(index), output)
        }
    }

    private fun findGraph(view: android.view.View): G7CollectorGraphView? {
        if (view is G7CollectorGraphView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findGraph(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun findScrollView(view: android.view.View): ScrollView? {
        if (view is ScrollView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findScrollView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun findText(view: android.view.View, value: String): TextView? {
        if (view is TextView && view.text?.toString() == value) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findText(view.getChildAt(index), value)?.let { return it }
        return null
    }

    private fun measureAndLayout(view: android.view.View) {
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 400, 400)
    }

    private fun containsNativeButton(view: android.view.View): Boolean {
        if (view is Button) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsNativeButton(view.getChildAt(index))) return true
            }
        }
        return false
    }
}
