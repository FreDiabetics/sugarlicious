package app.aapswear.g7watch

import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.DataSourceId
import app.aapswear.model.Trend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class G7WatchActivityLayoutTest {
    @Test
    fun `collector screen keeps graph before live and system status without legacy graph title`() {
        val activity = Robolectric.buildActivity(G7WatchActivity::class.java).create().start().resume().get()
        val texts = mutableListOf<String>()
        collectText(activity.findViewById(android.R.id.content), texts)

        assertFalse(texts.any { it.contains("3h Verlauf", ignoreCase = true) })
        assertTrue("3h" in texts)

        val liveIndex = texts.indexOf("LIVE COLLECTORSTATUS")
        val systemIndex = texts.indexOf("SYSTEMSTATUS")
        assertTrue(liveIndex >= 0)
        assertTrue(systemIndex > liveIndex)
        assertTrue(texts.any { it == "Sensor einrichten" || it == "Sensor neu koppeln" })
        assertTrue(texts.any { it == "Collector starten" || it == "Collector stoppen" })
        assertTrue(texts.contains("←"))

        assertFalse(containsNativeButton(activity.findViewById(android.R.id.content)))
        activity.finish()
    }

    @Test
    fun `new reading refreshes collector graph while activity remains resumed`() {
        val controller = Robolectric.buildActivity(G7WatchActivity::class.java).create().start().resume()
        val activity = controller.get()
        val before = findGraph(activity.findViewById(android.R.id.content))
        assertNotNull(before)

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
        assertNotSame(before, after)

        controller.pause().stop().destroy()
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
