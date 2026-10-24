package app.aapswear.wear

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WearTherapyProgressViewTest {
    @Test
    fun `ring accepts only finite normalized progress and retains missing state`() {
        val view = WearTherapyProgressView(ApplicationProvider.getApplicationContext())
        view.bind(Float.NaN, Color.CYAN)
        assertEquals(null, view.progress)
        view.bind(2f, Color.CYAN)
        assertEquals(1f, view.progress)
        view.bind(-1f, Color.CYAN)
        assertEquals(0f, view.progress)
        view.bind(null, Color.CYAN)
        assertEquals(null, view.progress)
    }
}
