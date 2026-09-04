package app.aapswear.wear

import androidx.test.core.app.ApplicationProvider
import app.aapswear.protocol.WatchUiColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WearTileAppearanceStoreTest {
    @Test
    fun `each system tile persists its content independently`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WearTileContentStore.write(context, WearTileKind.GLUCOSE, WearTileContent.GRAPH)
        WearTileContentStore.write(context, WearTileKind.THERAPY, WearTileContent.PUMP)

        assertEquals(WearTileContent.GRAPH, WearTileContentStore.read(context, WearTileKind.GLUCOSE))
        assertEquals(WearTileContent.PUMP, WearTileContentStore.read(context, WearTileKind.THERAPY))
    }
    private val context
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearPreferences() {
        WearTileKind.entries.forEach { kind ->
            context.getSharedPreferences(kind.preferenceName, android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun `glucose and therapy tile appearance are independent`() {
        val glucose = WatchUiColors(background = 0xFF112233.toInt(), glucoseHigh = 0xFF445566.toInt())
        val therapy = WatchUiColors(background = 0xFF778899.toInt(), iob = 0xFFAABBCC.toInt())

        WearTileAppearanceStore.write(context, WearTileKind.GLUCOSE, glucose)
        WearTileAppearanceStore.write(context, WearTileKind.THERAPY, therapy)

        assertEquals(glucose, WearTileAppearanceStore.read(context, WearTileKind.GLUCOSE))
        assertEquals(therapy, WearTileAppearanceStore.read(context, WearTileKind.THERAPY))
        assertNotEquals(
            WearTileAppearanceStore.read(context, WearTileKind.GLUCOSE).background,
            WearTileAppearanceStore.read(context, WearTileKind.THERAPY).background,
        )
    }

    @Test
    fun `tile writes do not mutate wear overview colors`() {
        val overview = WearDisplayPreferences(uiColors = WatchUiColors(background = 0xFF010203.toInt()))
        WearDisplayPreferences.saveLocal(context, overview)

        WearTileAppearanceStore.write(
            context,
            WearTileKind.GLUCOSE,
            WatchUiColors(background = 0xFFABCDEF.toInt()),
        )

        assertEquals(overview.uiColors, WearDisplayPreferences.read(context).uiColors)
    }
}
