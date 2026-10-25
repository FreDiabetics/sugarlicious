package app.aapswear.uishared

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.widget.SeekBar
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class WearSettingsControlsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `slider track does not stretch to its touch target and stays visible on black`() {
        val slider = SeekBar(context)
        // The user's saved border is transparent gray, not opaque black.
        WearSettingsUi.slider(slider, Color.GREEN, 0x00404040)
        val track = slider.progressDrawable
        assertEquals(WearSettingsUi.dp(context, 10), track.intrinsicHeight)
        track.setBounds(0, 0, 300, 48)
        track.level = 2500
        val bitmap = android.graphics.Bitmap.createBitmap(300, 48, android.graphics.Bitmap.Config.ARGB_8888)
        track.draw(android.graphics.Canvas(bitmap))
        assertEquals(Color.GREEN, bitmap.getPixel(30, 24))
        assertEquals(Color.rgb(70, 70, 73), bitmap.getPixel(200, 24))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(200, 0))
    }

    @Test fun `scrolling across a switch never commits but an ordinary tap does`() {
        val toggle = ScrollSafeSwitch(context)

        fun touch(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(0, 20, action, x, y, 0)
            toggle.onTouchEvent(event)
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 20f, 20f)
        touch(MotionEvent.ACTION_MOVE, 30f, 120f)
        touch(MotionEvent.ACTION_UP, 30f, 120f)
        assertFalse(toggle.isChecked)
        touch(MotionEvent.ACTION_DOWN, 20f, 20f)
        touch(MotionEvent.ACTION_UP, 20f, 20f)
        assertTrue(toggle.isChecked)
    }
}
