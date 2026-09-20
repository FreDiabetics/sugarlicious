package app.aapswear.complications

import android.content.Context
import android.database.MatrixCursor
import androidx.test.core.app.ApplicationProvider
import app.aapswear.model.CgmQuality
import app.aapswear.model.DataSourceId
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import app.aapswear.protocol.WatchDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class G7DirectProviderOutcomeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L

    @Test
    fun `successful provider snapshot supplies Watch Direct reading`() {
        val resolved = resolve(DirectProviderOutcome.Success(DirectSnapshot(listOf(reading()), DirectStatus("ACTIVE", "ACTIVE"))))

        assertEquals(DataSourceId.DEXCOM_G7_WATCH, resolved?.source)
        assertEquals(123.0, resolved?.glucose?.valueMgDl ?: 0.0, 0.0)
        assertTrue(resolved?.sourceContract?.endsWith("PROVIDER_SUCCESS") == true)
    }

    @Test
    fun `empty unavailable denied and malformed outcomes remain distinct and non throwing`() {
        val outcomes =
            listOf(
                DirectProviderOutcome.Empty to "EMPTY",
                DirectProviderOutcome.Failure(ProviderFailureKind.UNAVAILABLE) to "UNAVAILABLE",
                DirectProviderOutcome.Failure(ProviderFailureKind.DENIED) to "DENIED",
                DirectProviderOutcome.Failure(ProviderFailureKind.MALFORMED) to "MALFORMED",
            )

        outcomes.forEach { (outcome, code) ->
            context
                .getSharedPreferences("direct_provider_diagnostics", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            val resolved = resolve(outcome)
            assertEquals(null, resolved)
            assertEquals(
                code,
                context.getSharedPreferences("direct_provider_diagnostics", Context.MODE_PRIVATE).getString("last_code", null),
            )
        }
    }

    @Test
    fun `Android provider classifies empty unavailable denied and malformed cursors`() {
        val empty = AndroidDirectCgmProvider(context) { uri -> emptyCursor(uri.lastPathSegment == "state") }.read()
        val unavailable = AndroidDirectCgmProvider(context) { throw UnsupportedOperationException("offline") }.read()
        val denied = AndroidDirectCgmProvider(context) { throw SecurityException("denied") }.read()
        val malformed =
            AndroidDirectCgmProvider(context) {
                MatrixCursor(arrayOf("unexpected")).apply { addRow(arrayOf("value")) }
            }.read()

        assertEquals(DirectProviderOutcome.Empty, empty)
        assertEquals(DirectProviderOutcome.Failure(ProviderFailureKind.UNAVAILABLE), unavailable)
        assertEquals(DirectProviderOutcome.Failure(ProviderFailureKind.DENIED), denied)
        assertEquals(DirectProviderOutcome.Failure(ProviderFailureKind.MALFORMED), malformed)
    }

    private fun emptyCursor(status: Boolean): MatrixCursor =
        MatrixCursor(
            if (status) {
                arrayOf("sensor_state", "session_state")
            } else {
                arrayOf("status", "glucose", "sensor_id", "session_id", "sequence_number", "measured_at", "received_at", "delta", "trend")
            },
        )

    private fun resolve(outcome: DirectProviderOutcome): TherapyDisplayState? =
        G7LocalReadingResolver.resolve(
            context = context,
            fallback = null,
            nowEpochMs = now,
            dataSource = WatchDataSource.DEXCOM_G7_WATCH,
            provider = DirectCgmProvider { outcome },
        )

    private fun reading() =
        DirectReading(
            sensorId = "sensor",
            sessionId = "session",
            sequenceNumber = 7L,
            value = 123.0,
            measuredAt = now - 60_000L,
            receivedAt = now,
            delta = 1.0,
            trend = Trend.FLAT,
            quality = CgmQuality.VALID,
        )
}
