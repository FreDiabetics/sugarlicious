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
    fun `Vigil persists in the reduced catalog`() {
        SugarliciousWatchFaceSelectionStore.write(context, DIRECT_TO_WATCH_FACE_INDEX)
        assertEquals(DIRECT_TO_WATCH_FACE_INDEX, SugarliciousWatchFaceSelectionStore.read(context))
    }

    @Test
    fun `direct to watch becomes relevant for explicit collector source`() {
        val preferences = DashboardUiPreferences(dataSource = DataSourcePreference.DEXCOM_G7_WATCH)
        assertTrue(SugarliciousWatchFaceSelectionStore.isDirectToWatchRelevant(context, null, preferences))
    }

    @Test
    fun `legacy collector source preference is ignored by AndroidAPS-only Mobile policy`() {
        context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)
            .edit()
            .putString("dataSource", DataSourcePreference.DEXCOM_G7_WATCH.name)
            .commit()

        assertFalse(SugarliciousWatchFaceSelectionStore.isDirectToWatchRelevant(context, null))
    }

    @Test
    fun `direct to watch becomes relevant for canonical watch direct state`() {
        val state = TherapyDisplayState(source = DataSourceId.DEXCOM_G7_WATCH, receivedAtEpochMs = 1L)
        assertTrue(
            SugarliciousWatchFaceSelectionStore.isDirectToWatchRelevant(
                context,
                state,
                DashboardUiPreferences(),
            ),
        )
    }

    @Test
    fun `direct to watch is not marked relevant without setup or collector state`() {
        assertFalse(
            SugarliciousWatchFaceSelectionStore.isDirectToWatchRelevant(
                context,
                null,
                DashboardUiPreferences(),
            ),
        )
    }

    @Test
    fun `direct to watch cannot be selected before collector is relevant`() {
        assertFalse(
            SugarliciousWatchFaceSelectionStore.isSelectable(
                DIRECT_TO_WATCH_FACE_INDEX,
                directToWatchRelevant = false,
            ),
        )
        assertTrue(
            SugarliciousWatchFaceSelectionStore.isSelectable(
                DIRECT_TO_WATCH_FACE_INDEX,
                directToWatchRelevant = true,
            ),
        )
    }

    @Test
    fun `unavailable saved direct to watch falls back to legacy selectable face`() {
        assertEquals(
            0,
            SugarliciousWatchFaceSelectionStore.resolveSelectableFallback(
                savedFaceIndex = DIRECT_TO_WATCH_FACE_INDEX,
                legacyFallback = 0,
                directToWatchRelevant = false,
            ),
        )
        assertEquals(
            DIRECT_TO_WATCH_FACE_INDEX,
            SugarliciousWatchFaceSelectionStore.resolveSelectableFallback(
                savedFaceIndex = DIRECT_TO_WATCH_FACE_INDEX,
                legacyFallback = 0,
                directToWatchRelevant = true,
            ),
        )
    }

    @Test
    fun `one hundred cgm collector and data layer refreshes cannot change selected face`() {
        val selectedFace = 0
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
