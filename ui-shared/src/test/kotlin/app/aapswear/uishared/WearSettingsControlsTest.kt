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

    @Test fun `slider exposes its purpose and remains keyboard accessible`() {
        val slider = SeekBar(context)
        WearSettingsUi.slider(slider, Color.GREEN, Color.DKGRAY, "Konturdicke")
        assertEquals("Konturdicke", slider.contentDescription)
        assertTrue(slider.isFocusable)
        assertFalse(slider.isFocusableInTouchMode)
    }

    @Test fun `header keeps readable wrapping and a named back button at double font size`() {
        val scaled =
            context.createConfigurationContext(
                android.content.res
                    .Configuration(context.resources.configuration)
                    .apply { fontScale = 2f },
            )
        val header = WearSettingsUi.header(scaled, "Farben und Darstellung", Color.WHITE) {}
        header.measure(
            android.view.View.MeasureSpec
                .makeMeasureSpec(WearSettingsUi.dp(scaled, 192), android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec
                .makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        header.layout(0, 0, header.measuredWidth, header.measuredHeight)
        val back = header.getChildAt(0)
        assertTrue(back is android.widget.ImageButton)
        assertEquals("Zurück", back.contentDescription)
        assertEquals(WearSettingsUi.dp(scaled, 48), back.measuredWidth)
        val title = header.getChildAt(1) as android.widget.TextView
        assertTrue(title.lineCount > 1)
        assertTrue(title.isAccessibilityHeading)
    }

    @Test fun `wear list typography matches Samsung Health fifteen and twelve sp roles`() {
        assertEquals(15f, WearSettingsUi.textSize(11f), 0f)
        assertEquals(12f, WearSettingsUi.textSize(8.5f), 0f)
    }

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

    @Test fun `switch thumb stays a circle inside the taller track bounds`() {
        val toggle = ScrollSafeSwitch(context)
        WearSettingsUi.toggle(toggle, Color.BLUE, Color.WHITE)
        val thumb = toggle.thumbDrawable
        val width = WearSettingsUi.dp(context, 20)
        val height = WearSettingsUi.dp(context, 24)
        thumb.setBounds(0, 0, width, height)
        val image = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        thumb.draw(android.graphics.Canvas(image))
        assertEquals(Color.TRANSPARENT, image.getPixel(width / 2, 0))
        assertEquals(Color.WHITE, image.getPixel(width / 2, height / 2))
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

    @Test fun `vertical drag across slider never starts tracking or writes a value`() {
        val slider =
            ScrollSafeSeekBar(context).apply {
                max = 100
                progress = 30
                layout(0, 0, 240, 60)
            }
        var writes = 0
        slider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) writes++
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {
                    writes++
                }

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    writes++
                }
            },
        )
        listOf(
            Triple(MotionEvent.ACTION_DOWN, 150f, 20f),
            Triple(MotionEvent.ACTION_MOVE, 155f, 120f),
            Triple(MotionEvent.ACTION_CANCEL, 155f, 120f),
        ).forEach { (action, x, y) ->
            val event = MotionEvent.obtain(0, 20, action, x, y, 0)
            slider.onTouchEvent(event)
            event.recycle()
        }
        assertEquals(30, slider.progress)
        assertEquals(0, writes)
    }
}
