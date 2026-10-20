package app.aapswear.mobile

import android.content.Context
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import app.aapswear.mobile.ui.theme.mobileMenuAccentArgb
import app.aapswear.mobile.ui.theme.mobileMenuOnAccentArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MobileMenuColorsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `dark menu uses readable device palette tone`() {
        assertEquals(context.getColor(android.R.color.system_accent1_200), mobileMenuAccentArgb(context, dark = true))
    }

    @Test
    fun `light menu uses contrasting device palette tone`() {
        assertEquals(context.getColor(android.R.color.system_accent1_600), mobileMenuAccentArgb(context, dark = false))
    }

    @Test
    fun `dark menu foreground uses matching readable device tone`() {
        val foreground = mobileMenuOnAccentArgb(context, dark = true)
        assertEquals(context.getColor(android.R.color.system_accent1_800), foreground)
        assertTrue(ColorUtils.calculateContrast(foreground, mobileMenuAccentArgb(context, dark = true)) >= 4.5)
    }

    @Test
    fun `light menu foreground uses matching readable device tone`() {
        val foreground = mobileMenuOnAccentArgb(context, dark = false)
        assertEquals(context.getColor(android.R.color.system_accent1_0), foreground)
        assertTrue(ColorUtils.calculateContrast(foreground, mobileMenuAccentArgb(context, dark = false)) >= 4.5)
    }
}
