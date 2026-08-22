package app.aapswear.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.aapswear.model.DataSourceId
import app.aapswear.model.TherapyDisplayState
import app.aapswear.protocol.WatchRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SugarliciousWatchFaceSelectionStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `sixth Sugarlicious face persists without legacy clamp`() {
        SugarliciousWatchFaceSelectionStore.write(context, SUGARLICIOUS_G6_STYLE_FACE_INDEX)
        assertEquals(SUGARLICIOUS_G6_STYLE_FACE_INDEX, SugarliciousWatchFaceSelectionStore.read(context))
    }

    @Test
    fun `g6 style becomes relevant for explicit collector source`() {
        val preferences = DashboardUiPreferences(dataSource = DataSourcePreference.DEXCOM_G7_WATCH)
        assertTrue(SugarliciousWatchFaceSelectionStore.isG6StyleRelevant(context, null, preferences))
    }

    @Test
    fun `g6 style availability reads explicit collector source from dashboard preferences`() {
        context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)
            .edit()
            .putString("dataSource", DataSourcePreference.DEXCOM_G7_WATCH.name)
            .commit()

        assertTrue(SugarliciousWatchFaceSelectionStore.isG6StyleRelevant(context, null))
    }

    @Test
    fun `g6 style becomes relevant for canonical watch direct state`() {
        val state = TherapyDisplayState(source = DataSourceId.DEXCOM_G7_WATCH, receivedAtEpochMs = 1L)
        assertTrue(
            SugarliciousWatchFaceSelectionStore.isG6StyleRelevant(
                context,
                state,
                DashboardUiPreferences(),
            ),
        )
    }

    @Test
    fun `g6 style is not marked relevant without setup or collector state`() {
        assertFalse(
            SugarliciousWatchFaceSelectionStore.isG6StyleRelevant(
                context,
                null,
                DashboardUiPreferences(),
            ),
        )
    }

    @Test
    fun `g6 style cannot be selected before collector is relevant`() {
        assertFalse(
            SugarliciousWatchFaceSelectionStore.isSelectable(
                SUGARLICIOUS_G6_STYLE_FACE_INDEX,
                g6StyleRelevant = false,
            ),
        )
        assertTrue(
            SugarliciousWatchFaceSelectionStore.isSelectable(
                SUGARLICIOUS_G6_STYLE_FACE_INDEX,
                g6StyleRelevant = true,
            ),
        )
    }

    @Test
    fun `unavailable saved g6 style falls back to legacy selectable face`() {
        assertEquals(
            2,
            SugarliciousWatchFaceSelectionStore.resolveSelectableFallback(
                savedFaceIndex = SUGARLICIOUS_G6_STYLE_FACE_INDEX,
                legacyFallback = 2,
                g6StyleRelevant = false,
            ),
        )
        assertEquals(
            SUGARLICIOUS_G6_STYLE_FACE_INDEX,
            SugarliciousWatchFaceSelectionStore.resolveSelectableFallback(
                savedFaceIndex = SUGARLICIOUS_G6_STYLE_FACE_INDEX,
                legacyFallback = 2,
                g6StyleRelevant = true,
            ),
        )
    }

    @Test
    fun `one hundred cgm collector and data layer refreshes cannot change selected face`() {
        val selectedFace = 3
        SugarliciousWatchFaceSelectionStore.write(context, selectedFace)
        var reduced = selectedFace

        repeat(100) { update ->
            val event =
                when (update % 3) {
                    0 -> WatchFaceSelectionEvent.CGM_REFRESH
                    1 -> WatchFaceSelectionEvent.COLLECTOR_REFRESH
                    else -> WatchFaceSelectionEvent.DATA_LAYER_REFRESH
                }
            reduced = reduceWatchFaceSelection(reduced, update % sugarliciousWatchFaceCards.size, event)
            WatchRuntimeStatusStore.save(
                context,
                WatchRuntimeStatus(
                    activeSugarliciousFaceIndex = update % sugarliciousWatchFaceCards.size,
                    activeComplicationIds = listOf(update),
                    sentAtEpochMs = update.toLong(),
                ),
            )
        }

        assertEquals(selectedFace, reduced)
        assertEquals(selectedFace, SugarliciousWatchFaceSelectionStore.read(context))
    }
}
