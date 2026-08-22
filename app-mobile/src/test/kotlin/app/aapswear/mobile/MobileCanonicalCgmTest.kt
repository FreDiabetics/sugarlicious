package app.aapswear.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.CgmQuality
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayState
import app.aapswear.storage.PhoneTherapyStateStore
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MobileCanonicalCgmTest {
    @Test
    fun `direct Watch G7 backfill is discarded on Mobile`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = System.currentTimeMillis()
        val watch = reading("watch-$now", now - 60_000L)

        val accepted = MobileG7BackfillStore(context).merge(listOf(watch), now)

        assertTrue(accepted.isEmpty())
        assertTrue(MobileG7BackfillStore(context).snapshot().isEmpty())
    }

    @Test
    fun `Mobile canonical resolver remains phone only even when phone is stale or sensor error`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("mobile_watch_cgm_migration", Context.MODE_PRIVATE).edit().clear().commit()
        val now = System.currentTimeMillis()
        val stalePhone = phoneState(now, now - 30 * 60_000L)

        val staleResolved = MobileCanonicalCgmResolver.resolve(context, stalePhone, now)!!
        assertEquals(DataSourceId.ANDROID_APS, staleResolved.source)
        assertEquals(stalePhone.glucose?.measuredAtEpochMs, staleResolved.glucose?.measuredAtEpochMs)
        assertFalse(staleResolved.glucoseHistory.any { it.source == DataSourceId.DEXCOM_G7_WATCH })

        val sensorError = stalePhone.copy(
            glucose = stalePhone.glucose?.copy(quality = CgmQuality.SENSOR_ERROR),
        )
        val errorResolved = MobileCanonicalCgmResolver.resolve(context, sensorError, now)!!
        assertEquals(DataSourceId.ANDROID_APS, errorResolved.source)
        assertEquals(CgmQuality.SENSOR_ERROR, errorResolved.glucose?.quality)
    }

    @Test
    fun `migration removes persisted Watch current and history but keeps phone CGM`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("mobile_watch_cgm_migration", Context.MODE_PRIVATE).edit().clear().commit()
        val now = System.currentTimeMillis()
        val phone = phoneState(
            now,
            now - 30_000L,
            history = listOf(
                GlucoseSample(120.0, now - 5 * 60_000L, source = DataSourceId.ANDROID_APS),
            ),
        )
        val watchState = TherapyDisplayState(
            source = DataSourceId.DEXCOM_G7_WATCH,
            sourceVersion = "G7 Watch Collector",
            receivedAtEpochMs = now,
            glucose = GlucoseState(
                valueMgDl = 124.0,
                displayUnit = GlucoseUnit.MG_DL,
                measuredAtEpochMs = now - 60_000L,
                source = DataSourceId.DEXCOM_G7_WATCH,
                receivedAtEpochMs = now,
            ),
            glucoseHistory = listOf(
                GlucoseSample(118.0, now - 10 * 60_000L, source = DataSourceId.ANDROID_APS),
                GlucoseSample(124.0, now - 60_000L, source = DataSourceId.DEXCOM_G7_WATCH),
            ),
        )
        PhoneTherapyStateStore(context).save(phone)
        TherapyStateStore(context).save(watchState)

        assertTrue(MobileWatchCgmMigration.runOnce(context))
        val migrated = TherapyStateStore(context).state.first()!!

        assertEquals(DataSourceId.ANDROID_APS, migrated.source)
        assertEquals(phone.glucose?.measuredAtEpochMs, migrated.glucose?.measuredAtEpochMs)
        assertFalse(migrated.glucoseHistory.any { it.source == DataSourceId.DEXCOM_G7_WATCH })
        assertFalse(MobileWatchCgmMigration.runOnce(context))
    }

    @Test
    fun `sanitizing Watch-only state produces explicit no current glucose without inventing fallback`() {
        val now = System.currentTimeMillis()
        val sanitized = TherapyDisplayState(
            source = DataSourceId.DEXCOM_G7_WATCH,
            sourceVersion = "G7 Watch Collector",
            receivedAtEpochMs = now,
            glucose = GlucoseState(
                valueMgDl = 111.0,
                displayUnit = GlucoseUnit.MG_DL,
                measuredAtEpochMs = now - 60_000L,
                source = DataSourceId.DEXCOM_G7_WATCH,
                receivedAtEpochMs = now,
            ),
            glucoseHistory = listOf(
                GlucoseSample(109.0, now - 6 * 60_000L, source = DataSourceId.DEXCOM_G7_WATCH),
            ),
        ).withoutDirectWatchCgm()

        assertEquals(DataSourceId.OTHER, sanitized.source)
        assertNull(sanitized.glucose)
        assertTrue(sanitized.glucoseHistory.isEmpty())
        assertEquals("MOBILE_PHONE_ONLY:NO_WATCH_CGM", sanitized.sourceContract)
    }

    private fun phoneState(
        now: Long,
        measuredAt: Long,
        history: List<GlucoseSample> = emptyList(),
    ) = TherapyDisplayState(
        source = DataSourceId.ANDROID_APS,
        receivedAtEpochMs = now,
        glucose = GlucoseState(
            valueMgDl = 123.0,
            displayUnit = GlucoseUnit.MG_DL,
            measuredAtEpochMs = measuredAt,
            source = DataSourceId.ANDROID_APS,
            receivedAtEpochMs = now,
        ),
        glucoseHistory = history,
    )

    private fun reading(id: String, timestamp: Long) = CgmReading(
        id = id,
        source = DataSourceId.DEXCOM_G7_WATCH,
        sensorId = "sensor-a",
        sessionId = "session-a",
        glucoseMgDl = 121.0,
        timestampEpochMs = timestamp,
        receivedAtEpochMs = timestamp + 1_000L,
        status = CgmReadingStatus.VALID,
        sequenceNumber = 7L,
    )
}
