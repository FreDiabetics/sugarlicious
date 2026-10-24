package app.aapswear.uishared

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.roundToInt

/** Crown/bezel gestures always scroll, even when a slider currently has focus. */
class WearSettingsScrollView(
    context: Context,
) : ScrollView(context) {
    init {
        isFocusableInTouchMode = true
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            scrollBy(0, (-event.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(context).scaledVerticalScrollFactor * .55f).roundToInt())
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }
}
