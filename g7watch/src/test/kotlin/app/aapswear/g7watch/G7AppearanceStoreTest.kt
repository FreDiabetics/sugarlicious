package app.aapswear.g7watch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class G7AppearanceStoreTest {
    @Test fun `colors persist alpha and reset to defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("g7_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        val store = G7AppearanceStore(context)
        val custom = 0x12345678

        store.save(G7AppearanceRole.GRAPH_TARGET_AREA, custom)
        assertEquals(custom, G7AppearanceStore(context).load().argb(G7AppearanceRole.GRAPH_TARGET_AREA))

        store.reset(G7AppearanceRole.GRAPH_TARGET_AREA)
        assertEquals(G7AppearanceRole.GRAPH_TARGET_AREA.defaultArgb, G7AppearanceStore(context).load().argb(G7AppearanceRole.GRAPH_TARGET_AREA))
    }

    @Test fun `graph period cycles and survives new store instance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("g7_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        val store = G7AppearanceStore(context)

        assertEquals(3, store.graphHours())
        assertEquals(6, store.nextGraphHours())
        assertEquals(6, G7AppearanceStore(context).graphHours())
        assertEquals(12, store.nextGraphHours())
        assertEquals(24, store.nextGraphHours())
        assertEquals(1, store.nextGraphHours())
        assertEquals(2, store.nextGraphHours())
        assertEquals(3, store.nextGraphHours())
    }
}
