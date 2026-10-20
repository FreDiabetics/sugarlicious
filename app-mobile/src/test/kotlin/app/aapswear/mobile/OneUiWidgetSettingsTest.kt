package app.aapswear.mobile

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OneUiWidgetSettingsTest {
    @Test
    fun `all widget switches share a fixed trailing column`() {
        val source = java.io.File("src/main/kotlin/app/aapswear/mobile/OneUiWidgetSettings.kt").readText()
        val toggle = source.substringAfter("internal fun WidgetSettingsToggle(").substringBefore("internal fun WidgetToggleSetting(")
        assertTrue(toggle.contains("Modifier.width(64.dp).height(48.dp).padding(end = 10.dp)"))
        assertTrue(toggle.contains("gravity = Gravity.CENTER_VERTICAL or Gravity.END"))
        assertTrue(toggle.contains("android.widget.Switch(context)"))
        org.junit.Assert.assertFalse(toggle.contains("trackDrawable ="))
        org.junit.Assert.assertFalse(toggle.contains("thumbDrawable ="))
    }

    @Test
    fun `color copy action copies the exact ARGB hex including transparency`() {
        val context =
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<android.content.Context>()
        copyWidgetColorHex(context, 0x12ABCDEF)
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals(
            "#12ABCDEF",
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                .toString(),
        )
    }

    @Test
    fun `widget color rows do not expose hex or reset outside the picker`() {
        val source = java.io.File("src/main/kotlin/app/aapswear/mobile/WidgetInstanceConfiguration.kt").readText()
        val row = source.substringAfter("private fun WidgetColorSetting(").substringBefore("private fun WidgetPercentSlider(")
        org.junit.Assert.assertFalse(row.contains("toHex("))
        org.junit.Assert.assertFalse(row.contains("ZURÜCKSETZEN"))
    }

    @Test
    fun `widget settings do not override the device font family`() {
        val source = java.io.File("src/main/kotlin/app/aapswear/mobile/OneUiWidgetSettings.kt").readText()
        org.junit.Assert.assertFalse(source.contains("Typeface.create("))
        assertTrue(source.contains("fontFamily = FontFamily.Default"))
    }

    @Test
    fun `failed publication restores the previous instance settings`() {
        var setting = 10
        runCatching {
            commitWidgetSettings({ setting = 20 }, { error("host rejected update") }, { setting = 10 })
        }
        assertEquals(10, setting)
        commitWidgetSettings({ setting = 20 }, {}, { setting = 10 })
        assertEquals(20, setting)
    }

    @Test
    fun `preview retains its homescreen aspect and accounts for legacy host scaling`() {
        val native = oneUiWidgetPreviewPlacement(154f, 69f, 1f, 391f)
        assertEquals(154f, native.widthDp, .001f)
        assertEquals(69f, native.heightDp, .001f)
        val legacy = oneUiWidgetPreviewPlacement(185.6f, 82.8f, .8333333f, 391f)
        assertEquals(154.66666f, legacy.widthDp, .01f)
        assertEquals(69f, legacy.heightDp, .01f)
        val wide = oneUiWidgetPreviewPlacement(345f, 175f, 1f, 391f)
        assertTrue(wide.heightDp <= 122f)
        assertEquals(345f / 175f, wide.widthDp / wide.heightDp, .001f)
        val invalid = oneUiWidgetPreviewPlacement(154f, 69f, Float.NaN, 391f)
        assertEquals(native, invalid)
    }

    @Test
    fun `widget settings use the system wallpaper window without copying wallpaper data`() {
        val controller = Robolectric.buildActivity(WidgetConfigurationActivity::class.java).create()
        try {
            assertTrue(
                controller
                    .get()
                    .window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0,
            )
        } finally {
            controller.destroy()
        }
    }
}
