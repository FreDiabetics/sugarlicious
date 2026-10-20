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
class OneUiMobileSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    @Config(sdk = [26])
    fun `native controls and app theme remain available at the minimum supported API`() {
        val themed = android.view.ContextThemeWrapper(context, R.style.AppTheme)
        assertEquals(26, android.os.Build.VERSION.SDK_INT)
        mobileSettingsControlContext(themed, dark = true)
        mobileSettingsControlContext(themed, dark = false)
        val toggle = mobileSettingsSwitch(themed, false) {}
        toggle.toggle()
        assertTrue(toggle.isChecked)
    }

    @Test
    fun `selected native controls use the public device palette rather than framework blue`() {
        listOf(true, false).forEach { dark ->
            val themed = mobileSettingsControlContext(context, dark)
            val attributes = themed.obtainStyledAttributes(intArrayOf(android.R.attr.colorControlActivated))
            try {
                assertEquals(
                    themed.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600),
                    attributes.getColor(0, 0),
                )
            } finally {
                attributes.recycle()
            }
        }
    }

    @Test
    fun `non touch user adjustments persist immediately but touch dragging commits once at the end`() {
        var draft = 0
        val saved = mutableListOf<Int>()
        val interaction = SettingsSliderInteraction({ draft = it }, { saved.add(draft) })
        interaction.changed(20, fromUser = false)
        assertTrue(saved.isEmpty())
        interaction.changed(30, fromUser = true)
        assertEquals(listOf(30), saved)
        interaction.started()
        interaction.changed(40, fromUser = true)
        interaction.changed(50, fromUser = true)
        assertEquals(listOf(30), saved)
        interaction.stopped()
        assertEquals(listOf(30, 50), saved)
    }

    @Test
    fun `native controls follow the selected app mode without changing device configuration`() {
        val originalMode = context.resources.configuration.uiMode
        val dark = mobileSettingsControlContext(context, dark = true)
        val light = mobileSettingsControlContext(context, dark = false)
        val mask = android.content.res.Configuration.UI_MODE_NIGHT_MASK
        assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_YES, dark.resources.configuration.uiMode and mask)
        assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_NO, light.resources.configuration.uiMode and mask)
        assertEquals(originalMode, context.resources.configuration.uiMode)
    }

    @Test
    fun `binding a native switch does not write settings and toggling sends the selected value`() {
        val changes = mutableListOf<Boolean>()
        val toggle = mobileSettingsSwitch(context, true, changes::add)
        assertTrue(toggle.isChecked)
        assertTrue(changes.isEmpty())
        toggle.toggle()
        assertEquals(listOf(false), changes)
        assertEquals(1f, toggle.scaleX)
        assertEquals(1f, toggle.scaleY)
    }

    @Test
    fun `settings page navigation returns from a category before leaving settings`() {
        val navigation = MobileSettingsNavigation()
        assertEquals(null, navigation.category)
        navigation.open("general", "Allgemein")
        assertEquals("Allgemein", navigation.title)
        assertTrue(navigation.back())
        assertEquals(null, navigation.category)
        assertEquals(false, navigation.back())
    }
}
