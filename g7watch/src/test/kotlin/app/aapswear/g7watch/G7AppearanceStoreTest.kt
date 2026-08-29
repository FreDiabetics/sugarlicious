package app.aapswear.g7watch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import app.aapswear.model.AppearanceMode
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

    @Test fun `light and dark profiles are independent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("g7_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        val store = G7AppearanceStore(context)
        store.save(AppearanceMode.LIGHT, G7AppearanceRole.MENU_BACKGROUND, 0xFFEEDDCC.toInt())
        store.save(AppearanceMode.DARK, G7AppearanceRole.MENU_BACKGROUND, 0xFF112233.toInt())
        assertEquals(0xFFEEDDCC.toInt(), store.load(AppearanceMode.LIGHT).argb(G7AppearanceRole.MENU_BACKGROUND))
        assertEquals(0xFF112233.toInt(), store.load(AppearanceMode.DARK).argb(G7AppearanceRole.MENU_BACKGROUND))
    }

    @Test fun `explicit dark mode survives activity and store recreation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("g7_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        G7AppearanceStore(context).setActiveMode(AppearanceMode.DARK)
        assertEquals(AppearanceMode.DARK, G7AppearanceStore(context).activeMode())
        assertEquals(AppearanceMode.DARK, G7AppearanceStore(context).load().mode)
    }

    @Test fun `glucose and trend scales persist independently through 200 percent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("g7_appearance", Context.MODE_PRIVATE).edit().clear().commit()
        G7AppearanceStore(context).apply {
            setGlucoseScalePercent(200)
            setTrendScalePercent(125)
        }
        assertEquals(200, G7AppearanceStore(context).glucoseScalePercent())
        assertEquals(125, G7AppearanceStore(context).trendScalePercent())
    }
}
