package app.aapswear.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchFacePresetStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sugarlicious_watchface_presets", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `preset list stays aligned with every Sugarlicious watch face`() {
        assertEquals(sugarliciousWatchFaceCards.indices, WatchFacePresetStore.supportedFaceIndices)
        assertEquals(sugarliciousWatchFaceCards.size, WatchFacePresetStore.readAll(context).size)
        assertEquals(List(sugarliciousWatchFaceCards.size) { emptyList<Int>() }, WatchFacePresetStore.readAll(context))
    }

    @Test
    fun `ApeX keeps its own preset`() {
        WatchFacePresetStore.save(context, faceIndex = 0, complicationIds = listOf(1))

        assertEquals(listOf(1), WatchFacePresetStore.read(context, faceIndex = 0))
    }
}
