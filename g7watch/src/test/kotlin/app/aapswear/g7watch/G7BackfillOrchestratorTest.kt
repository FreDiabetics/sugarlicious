package app.aapswear.g7watch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.aapswear.g7.CollectorCycleClassification
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class G7BackfillOrchestratorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clear() {
        context.getSharedPreferences("g7_expected_window_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("g7_collector_state", Context.MODE_PRIVATE).edit().clear().commit()
        G7SensorStateStore(context).save(G7PersistedState(sensor = G7Sensor("sensor-a", "session-a")))
    }

    @Test fun `oldest same-session gap is selected and legacy detection time is repaired`() {
        val ledger = G7ExpectedWindowLedger(context)
        val newer = ledger.create(600_000L, 590_000L)
        ledger.markFinal(newer.expectedWindowId, CollectorCycleClassification.MISSED_SENSOR_WINDOW, true)
        val older = ledger.create(300_000L, 290_000L)
        ledger.markFinal(older.expectedWindowId, CollectorCycleClassification.MISSED_SENSOR_WINDOW, true)

        val selected = ledger.oldestOpenGap(older.sensorId, older.sessionId)

        assertEquals(older.expectedWindowId, selected?.expectedWindowId)
        assertNotNull(ledger.window(older.expectedWindowId)?.gapDetectedAt)
    }

    @Test fun `closed gap and another sensor session are never selected`() {
        val ledger = G7ExpectedWindowLedger(context)
        val gap = ledger.create(300_000L, 290_000L)
        ledger.markFinal(gap.expectedWindowId, CollectorCycleClassification.MISSED_SENSOR_WINDOW, true)
        val sensorId = gap.sensorId ?: "unknown"
        val sessionId = gap.sessionId ?: "unknown"
        ledger.markNextLiveAndBackfill(sensorId, sessionId, 600_000L, 601_000L, 602_000L, 601_500L, 601_700L, listOf(300_000L))

        assertNull(ledger.oldestOpenGap(sensorId, sessionId))
        assertNull(ledger.oldestOpenGap("other", "other"))
    }

    @Test fun `request attempts persist across ledger instances and incomplete responses keep gap open`() {
        val ledger = G7ExpectedWindowLedger(context)
        val gap = ledger.create(300_000L, 290_000L)
        ledger.markFinal(gap.expectedWindowId, CollectorCycleClassification.MISSED_SENSOR_WINDOW, true)
        val sensorId = gap.sensorId ?: "unknown"
        val sessionId = gap.sessionId ?: "unknown"

        ledger.markRecoveryRequestStarted(sensorId, sessionId, 600_000L, 601_500L)
        ledger.markNextLiveAndBackfill(sensorId, sessionId, 600_000L, 601_000L, 602_000L, 601_500L, 601_700L, emptyList())

        val afterRestart = G7ExpectedWindowLedger(context).oldestOpenGap(gap.sensorId, gap.sessionId)
        assertNotNull(afterRestart)
        assertEquals(1, afterRestart?.recoveryAttemptCount)
        assertEquals("RESPONSE_WITHOUT_GAP", afterRestart?.lastRecoveryOutcome)

        ledger.markRecoveryRequestStarted(sensorId, sessionId, 900_000L, 901_500L)
        assertEquals(2, ledger.window(gap.expectedWindowId)?.recoveryAttemptCount)
    }

    @Test fun `one coalesced response closes every matching open window but never another session`() {
        val ledger = G7ExpectedWindowLedger(context)
        val first = ledger.create(300_000L, 290_000L)
        ledger.markFinal(first.expectedWindowId, CollectorCycleClassification.MISSED_SENSOR_WINDOW, true)
        val second = ledger.create(600_000L, 590_000L)
        ledger.markFinal(second.expectedWindowId, CollectorCycleClassification.MISSED_SENSOR_WINDOW, true)
        val sensorId = first.sensorId ?: "unknown"
        val sessionId = first.sessionId ?: "unknown"

        ledger.markRecoveryRequestStarted(sensorId, sessionId, 900_000L, 901_500L)
        ledger.markNextLiveAndBackfill(sensorId, sessionId, 900_000L, 901_000L, 902_000L, 901_500L, 901_700L, listOf(300_000L, 600_000L))

        assertNull(ledger.oldestOpenGap(first.sensorId, first.sessionId))
        assertTrue(ledger.snapshot().filter { it.expectedWindowId in setOf(first.expectedWindowId, second.expectedWindowId) }.all { !it.recoveryRequired })
    }
}
