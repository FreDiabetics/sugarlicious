package app.aapswear.mobile

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/** Retains normal vertical scrolling while child charts handle horizontal pan and pinch zoom. */
class DashboardScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {
    var isUserScrollEnabled: Boolean = true
    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!isUserScrollEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> return false
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) return false
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                if (dx > dy) return false
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        isUserScrollEnabled && super.onTouchEvent(event)
}
