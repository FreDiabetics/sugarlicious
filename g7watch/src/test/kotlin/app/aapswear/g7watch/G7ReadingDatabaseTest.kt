package app.aapswear.g7watch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.DataSourceId
import app.aapswear.model.Trend
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class G7ReadingDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: G7ReadingDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        database = G7ReadingDatabase(context)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun `retains all decoded sensor documentation fields`() = runBlocking {
        val now = System.currentTimeMillis()
        val reading =
            CgmReading(
                id = "reading-14",
                source = DataSourceId.DEXCOM_G7_WATCH,
                sensorId = "sensor-id",
                sessionId = "session-id",
                glucoseMgDl = 123.0,
                timestampEpochMs = now - 15_000L,
                receivedAtEpochMs = now,
                deltaMgDl = 2.0,
                trend = Trend.FLAT,
                trendRateMgDlPerMinute = 0.4,
                predictedMgDl = 126.0,
                sensorAgeSeconds = 15L,
                status = CgmReadingStatus.VALID,
                sequenceNumber = 14L,
                displayOnly = true,
                rawSourceTimestamp = 123_456L,
                sensorStartEpochMs = now - 900_000L,
                sensorEndEpochMs = now + 864_000_000L,
                graceEndEpochMs = now + 907_200_000L,
                protocolStatusCode = 1,
                calibrationStateCode = 6,
                reservedField = 42,
            )

        database.insert(reading)

        assertEquals(reading, database.getLatest())
    }

    @Test
    fun `only valid readings enter the Watch to Mobile backfill queue`() = runBlocking {
        val now = System.currentTimeMillis()
        val valid =
            CgmReading(
                id = "valid",
                source = DataSourceId.DEXCOM_G7_WATCH,
                sensorId = "sensor-id",
                sessionId = "session-id",
                glucoseMgDl = 123.0,
                timestampEpochMs = now - 5_000L,
                receivedAtEpochMs = now,
                status = CgmReadingStatus.VALID,
            )
        val sensorError =
            valid.copy(
                id = "sensor-error",
                timestampEpochMs = now,
                status = CgmReadingStatus.SENSOR_ERROR,
            )
        val newerValid =
            valid.copy(
                id = "newer-valid",
                timestampEpochMs = now - 1_000L,
                receivedAtEpochMs = now,
            )

        database.insert(newerValid)
        database.insert(sensorError)
        database.insert(valid)

        assertEquals(listOf(valid, newerValid), database.getUnsynced())
        assertEquals(sensorError, database.getLatest())
        assertEquals(newerValid, database.getLatestValid())
    }

    @Test
    fun `temporal predecessor ignores future rows and other sessions`() = runBlocking {
        val currentAt = System.currentTimeMillis()
        val base =
            CgmReading(
                id = "previous",
                source = DataSourceId.DEXCOM_G7_WATCH,
                sensorId = "sensor-a",
                sessionId = "session-a",
                glucoseMgDl = 120.0,
                timestampEpochMs = currentAt - 300_000L,
                receivedAtEpochMs = currentAt - 299_000L,
                status = CgmReadingStatus.VALID,
            )
        val older = base.copy(id = "older", timestampEpochMs = currentAt - 600_000L)
        val future = base.copy(id = "future", timestampEpochMs = currentAt + 300_000L)
        val otherSession =
            base.copy(
                id = "other-session",
                sessionId = "session-b",
                timestampEpochMs = currentAt - 60_000L,
            )

        database.insert(future)
        database.insert(otherSession)
        database.insert(older)
        database.insert(base)

        assertEquals(
            base,
            database.getLatestValidBefore("sensor-a", "session-a", currentAt),
        )
        assertNull(
            database.getLatestValidBefore("sensor-a", "missing-session", currentAt),
        )
    }

    @Test
    fun `duplicate sensor error sequence is retained only once`() = runBlocking {
        val now = System.currentTimeMillis()
        val error = CgmReading(
            id = "error-1",
            source = DataSourceId.DEXCOM_G7_WATCH,
            sensorId = "sensor-a",
            sessionId = "session-a",
            glucoseMgDl = 0.0,
            timestampEpochMs = now,
            receivedAtEpochMs = now,
            status = CgmReadingStatus.SENSOR_ERROR,
            sequenceNumber = 42L,
        )

        assertEquals(true, database.insert(error))
        assertEquals(false, database.insert(error.copy(id = "error-2", receivedAtEpochMs = now + 300_000L)))
        assertEquals(1, database.query().size)
        assertEquals(true, database.insert(error.copy(id = "error-3", sequenceNumber = 43L)))
        assertEquals(2, database.query().size)
    }

    @Test
    fun `validated live and backfill identity deduplicates without a migration index`() = runBlocking {
        val now = System.currentTimeMillis()
        val live = CgmReading(
            id = "live-42", source = DataSourceId.DEXCOM_G7_WATCH,
            sensorId = "sensor-a", sessionId = "session-a", glucoseMgDl = 123.0,
            timestampEpochMs = now, receivedAtEpochMs = now,
            status = CgmReadingStatus.VALID, sequenceNumber = 42L,
            rawSourceTimestamp = 12_600L,
        )

        assertEquals(true, database.insert(live))
        assertEquals(false, database.insert(live.copy(id = "backfill-42", receivedAtEpochMs = now + 10_000L)))
        assertEquals(true, database.insert(live.copy(id = "later-42", timestampEpochMs = now + 300_000L, rawSourceTimestamp = 12_900L)))
        assertEquals(2, database.query().size)
    }

    private companion object {
        const val DATABASE_NAME = "g7_readings.db"
    }
}
