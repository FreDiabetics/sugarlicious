package app.aapswear.g7watch

import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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

    private fun collectText(view: android.view.View, output: MutableList<String>) {
        if (view is TextView) output += view.text?.toString().orEmpty()
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectText(view.getChildAt(index), output)
        }
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
