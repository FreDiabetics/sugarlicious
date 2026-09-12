package app.aapswear.g7watch

import android.content.Context
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.roundToInt

/**
 * SugarWear scroll container with Wear OS rotary-input support.
 * Content geometry stays untouched; the platform owns the transient edge scrollbar.
 */
internal class G7EdgeFadeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.scrollViewStyle,
) : ScrollView(context, attrs, defStyleAttr) {
    private val rotaryScrollFactor = ViewConfiguration.get(context).scaledVerticalScrollFactor

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!hasFocus()) requestFocus()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (
            event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val delta = (-event.getAxisValue(MotionEvent.AXIS_SCROLL) * rotaryScrollFactor).roundToInt()
            if (delta != 0) smoothScrollBy(0, delta)
            awakenScrollBars()
            return true
        }
        return super.onGenericMotionEvent(event)
    }
}

/** Preserve content geometry and use the native transient Wear OS scroll indicator. */
internal fun ScrollView.applyG7EdgeFade(): ScrollView = apply {
    isVerticalScrollBarEnabled = true
    isScrollbarFadingEnabled = true
    scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
    scrollBarDefaultDelayBeforeFade = 250
    scrollBarFadeDuration = 250
    isVerticalFadingEdgeEnabled = false
    overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
}
