package app.aapswear.mobile

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.view.isNotEmpty

/** Retains normal vertical scrolling while child charts handle horizontal pan and pinch zoom. */
class DashboardScrollView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : ScrollView(context, attrs, defStyleAttr) {
        var isUserScrollEnabled: Boolean = true
        var sectionSnapEnabled: Boolean = false
        var topPullEnabled: Boolean = false
        var topPullExpanded: Boolean = false
        var onTopPull: ((Float) -> Unit)? = null
        var onTopPullFinished: ((Float) -> Unit)? = null
        private var topPullTracking = false
        private var downX = 0f
        private var downY = 0f
        private var gestureStartX = 0f
        private var gestureStartY = 0f
        private var gestureStartScrollY = 0
        private var velocity: VelocityTracker? = null
        private val settleHeading =
            Runnable {
                if (!sectionSnapEnabled || !isUserScrollEnabled) return@Runnable
                val density = resources.displayMetrics.density
                val headings = mutableListOf<Int>()

                fun collect(view: View) {
                    if (view.visibility != View.VISIBLE) return
                    if (view.tag == "settings_scroll_title" || view.tag == "settings_snap_heading") {
                        val rect = android.graphics.Rect()
                        view.getDrawingRect(rect)
                        offsetDescendantRectToMyCoords(view, rect)
                        headings += rect.top + (if (view.tag == "settings_scroll_title") view.height else 0) - (56 * density).toInt()
                    }
                    if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                if (isNotEmpty()) collect(getChildAt(0))
                val limit = ((getChildAt(0)?.height ?: 0) - height + paddingTop + paddingBottom).coerceAtLeast(0)
                settingsSnapTarget(scrollY, headings, 0f, density, limit)?.let { smoothScrollTo(0, it) }
            }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                removeCallbacks(settleHeading)
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                gestureStartX = event.x
                gestureStartY = event.y
                gestureStartScrollY = scrollY
            }
            velocity?.addMovement(event)
            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                velocity?.computeCurrentVelocity(1000)
                val speed = (velocity?.yVelocity ?: 0f) / resources.displayMetrics.density
                val dx = kotlin.math.abs(event.x - gestureStartX)
                val dy = kotlin.math.abs(event.y - gestureStartY)
                if (event.actionMasked == MotionEvent.ACTION_UP && sectionSnapEnabled && scrollY != gestureStartScrollY && dy > ViewConfiguration.get(context).scaledTouchSlop && dy > dx && kotlin.math.abs(speed) <= 600f) {
                    postDelayed(settleHeading, 160L)
                }
                velocity?.recycle()
                velocity = null
            }
            return handled
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(settleHeading)
            velocity?.recycle()
            velocity = null
            super.onDetachedFromWindow()
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (!isUserScrollEnabled) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    topPullTracking = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> return false
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1) return false
                    val dx = kotlin.math.abs(event.x - downX)
                    val dy = event.y - downY
                    if (topPullEnabled && scrollY == 0 && kotlin.math.abs(dy) > ViewConfiguration.get(context).scaledTouchSlop && kotlin.math.abs(dy) > dx && (dy > 0f || topPullExpanded)) {
                        topPullTracking = true
                        return true
                    }
                    if (dx > kotlin.math.abs(dy)) return false
                }
            }
            return super.onInterceptTouchEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isUserScrollEnabled) return false
            if (!topPullTracking && topPullEnabled && scrollY == 0 && event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 1) {
                val dy = event.y - downY
                val dx = kotlin.math.abs(event.x - downX)
                if (kotlin.math.abs(dy) > ViewConfiguration.get(context).scaledTouchSlop && kotlin.math.abs(dy) > dx && (dy > 0f || topPullExpanded)) topPullTracking = true
            }
            if (topPullTracking) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> onTopPull?.invoke((event.y - downY) * .55f)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        topPullTracking = false
                        velocity?.computeCurrentVelocity(1000)
                        onTopPullFinished?.invoke(if (event.actionMasked == MotionEvent.ACTION_UP) velocity?.yVelocity ?: 0f else 0f)
                    }
                }
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                    if (
                        kotlin.math.abs(event.x - downX) <= touchSlop &&
                        kotlin.math.abs(event.y - downY) <= touchSlop
                    ) {
                        performClick()
                    }
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean = super.performClick()
    }
