package app.aapswear.wear

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.aapswear.model.AppearanceMode
import app.aapswear.protocol.WatchUiColors
import app.aapswear.uishared.ScrollSafeSeekBar
import app.aapswear.uishared.ScrollSafeSwitch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WearSettingsPresentationTest {
    @Test
    fun `custom display colors cannot hide settings or change their chrome`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WearDisplayPreferences.setActiveAppearanceMode(context, AppearanceMode.DARK)
        val stored =
            WearDisplayPreferences().copy(
                uiColors = WatchUiColors().copy(background = Color.RED, tileBackground = Color.GREEN, textPrimary = Color.BLACK),
            )
        WearDisplayPreferences.saveLocal(context, AppearanceMode.DARK, stored)
        val before = WearDisplayPreferences.read(context, AppearanceMode.DARK)
        val controller = Robolectric.buildActivity(WearSettingsActivity::class.java).setup()
        val activity = controller.get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val scroll = content.getChildAt(0) as ScrollView
        val category = descendants(scroll).single { it.tag == "settings-category-glucose" }
        val label = descendants(category).filterIsInstance<TextView>().single { it.text.toString() == "Glukose" }
        assertEquals(Color.WHITE, label.currentTextColor)
        assertEquals(Color.rgb(35, 35, 38), (category.background as GradientDrawable).color!!.defaultColor)
        assertEquals(before, WearDisplayPreferences.read(context, AppearanceMode.DARK))
        controller.pause().stop().destroy()
    }

    @Test
    fun `every category builds without changing preferences and graph choices retain all periods`() {
        val controller = Robolectric.buildActivity(WearSettingsActivity::class.java).setup()
        val activity = controller.get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val before = WearDisplayPreferences.read(activity, WearDisplayPreferences.activeAppearanceMode(activity))
        val keys = descendants(content).mapNotNull { (it.tag as? String)?.takeIf { tag -> tag.startsWith("settings-category-") } }.toList()
        assertEquals(11, keys.size)
        keys.forEach { key ->
            descendants(content).single { it.tag == key }.performClick()
            if (key == "settings-category-graph") {
                val buttons = descendants(content).filterIsInstance<Button>().toList()
                WearDisplayPreferences.allowedGraphHours.forEach { hours ->
                    val label = activity.getString(R.string.graph_hours, hours)
                    val button = buttons.single { it.text.toString().removePrefix("✓  ") == label }
                    assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, button.layoutParams.width)
                }
                val scroll = content.getChildAt(0) as ScrollView
                val page = scroll.getChildAt(0) as LinearLayout
                val sliders = descendants(page).filterIsInstance<ScrollSafeSeekBar>().toList()
                assertTrue(sliders.isNotEmpty())
                assertTrue(sliders.all { (it.parent as View).parent === page })
                assertTrue(descendants(page).any { it is ScrollSafeSwitch })
            }
            assertEquals(before, WearDisplayPreferences.read(activity, WearDisplayPreferences.activeAppearanceMode(activity)))
            descendants(content).filterIsInstance<TextView>().single { it.contentDescription == "Zurück" }.performClick()
        }
        controller.pause().stop().destroy()
    }

    private fun descendants(view: View): Sequence<View> =
        sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
}
