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

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        isUserScrollEnabled && super.onInterceptTouchEvent(event)

    override fun onTouchEvent(event: MotionEvent): Boolean =
        isUserScrollEnabled && super.onTouchEvent(event)
}
