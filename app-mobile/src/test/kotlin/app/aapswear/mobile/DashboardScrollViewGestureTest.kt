package app.aapswear.mobile

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DashboardScrollViewGestureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `horizontal chart gesture is not intercepted by vertical dashboard scroll`() {
        val view = DashboardScrollView(context)
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 50f, 50f, 0)
        val horizontal = MotionEvent.obtain(0L, 16L, MotionEvent.ACTION_MOVE, 95f, 53f, 0)

        view.onInterceptTouchEvent(down)
        assertFalse(view.onInterceptTouchEvent(horizontal))

        down.recycle()
        horizontal.recycle()
    }

    @Test fun `multi pointer chart gesture is never intercepted`() {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0 },
            MotionEvent.PointerProperties().apply { id = 1 },
        )
        val coordinates = arrayOf(
            MotionEvent.PointerCoords().apply { x = 40f; y = 50f },
            MotionEvent.PointerCoords().apply { x = 100f; y = 50f },
        )
        val event = MotionEvent.obtain(
            0L, 16L, MotionEvent.ACTION_MOVE, 2, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, 0, 0,
        )

        assertFalse(DashboardScrollView(context).onInterceptTouchEvent(event))
        event.recycle()
    }
}
