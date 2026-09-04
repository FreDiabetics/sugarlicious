package app.aapswear.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingOrigin
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MobileCanonicalCgmTest {
    @Test
    fun `validated Collector history persists deduplicates and may drive canonical fallback`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("mobile_canonical_cgm_resolver", Context.MODE_PRIVATE).edit().clear().commit()
        MobileG7BackfillStore(context).clear()
        val now = System.currentTimeMillis()
        val first = reading("first-$now", 1, now - 10 * 60_000L)
        val duplicate = first.copy(id = "duplicate-$now")
        val latest = reading("latest-$now", 2, now - 60_000L)
        val invalid = latest.copy(id = "invalid-$now", status = CgmReadingStatus.INVALID)

        val accepted = MobileG7BackfillStore(context).merge(listOf(first, duplicate, latest, invalid), now)
        assertTrue(first.id in accepted)
        assertTrue(duplicate.id in accepted)
        assertFalse(invalid.id in accepted)
        assertEquals(2, MobileG7BackfillStore(context).snapshot().size)

        val stalePhone = phone(now, now - 16 * 60_000L)
        val resolved = MobileCanonicalCgmResolver.resolve(context, stalePhone, now)!!
        assertEquals(DataSourceId.DEXCOM_G7_WATCH, resolved.source)
        assertEquals(CgmReadingOrigin.BACKFILL, latest.origin)
    }

    @Test
    fun `two fresh Mobile values recover automatic source from Watch`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MobileG7BackfillStore(context).clear()
        context.getSharedPreferences("mobile_canonical_cgm_resolver", Context.MODE_PRIVATE).edit().clear().commit()
        val now = System.currentTimeMillis()
        MobileG7BackfillStore(context).merge(listOf(reading("watch-$now", 3, now - 30_000L)), now)
        assertEquals(DataSourceId.DEXCOM_G7_WATCH, MobileCanonicalCgmResolver.resolve(context, phone(now, now - 16 * 60_000L), now)?.source)
        assertEquals(DataSourceId.DEXCOM_G7_WATCH, MobileCanonicalCgmResolver.resolve(context, phone(now, now - 20_000L), now)?.source)
        assertEquals(DataSourceId.ANDROID_APS, MobileCanonicalCgmResolver.resolve(context, phone(now, now - 10_000L), now)?.source)
    }

    private fun phone(now: Long, measuredAt: Long) = TherapyDisplayState(
        source = DataSourceId.ANDROID_APS,
        receivedAtEpochMs = now,
        glucose = GlucoseState(123.0, GlucoseUnit.MG_DL, measuredAtEpochMs = measuredAt, source = DataSourceId.ANDROID_APS, receivedAtEpochMs = now),
    )

    private fun reading(id: String, sequence: Long, timestamp: Long) = CgmReading(
        id = id, source = DataSourceId.DEXCOM_G7_WATCH, sensorId = "sensor", sessionId = "session",
        glucoseMgDl = 120.0 + sequence, timestampEpochMs = timestamp, receivedAtEpochMs = timestamp + 1_000L,
        status = CgmReadingStatus.VALID, sequenceNumber = sequence, origin = CgmReadingOrigin.BACKFILL,
    )
}
