package app.aapswear.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CgmGraphPolicyTest {
    private val minute = 60_000L

    @Test
    fun `two consecutive valid low readings activate low and one does not`() {
        assertNull(CgmGraphPolicy.rangeExcursion(listOf(sample(1, 65.0)), 70.0, 180.0))
        assertEquals(
            RangeExcursion.LOW,
            CgmGraphPolicy.rangeExcursion(listOf(sample(1, 65.0), sample(6, 60.0)), 70.0, 180.0),
        )
    }

    @Test
    fun `two consecutive valid high readings activate high and one does not`() {
        assertNull(CgmGraphPolicy.rangeExcursion(listOf(sample(1, 190.0)), 70.0, 180.0))
        assertEquals(
            RangeExcursion.HIGH,
            CgmGraphPolicy.rangeExcursion(listOf(sample(1, 190.0), sample(6, 200.0)), 70.0, 180.0),
        )
    }

    @Test
    fun `return in range clears an excursion`() {
        assertNull(
            CgmGraphPolicy.rangeExcursion(
                listOf(sample(1, 60.0), sample(6, 62.0), sample(11, 110.0)),
                70.0,
                180.0,
            ),
        )
    }

    @Test
    fun `invalid points gaps and session switches cannot activate a tint`() {
        assertNull(
            CgmGraphPolicy.rangeExcursion(
                listOf(sample(1, 60.0), sample(6, 61.0, quality = CgmQuality.INVALID)),
                70.0,
                180.0,
            ),
        )
        assertNull(
            CgmGraphPolicy.rangeExcursion(listOf(sample(1, 60.0), sample(10, 61.0)), 70.0, 180.0),
        )
        assertNull(
            CgmGraphPolicy.rangeExcursion(
                listOf(sample(1, 60.0, session = "one"), sample(6, 61.0, session = "two")),
                70.0,
                180.0,
            ),
        )
    }

    @Test
    fun `unknown cross source boundary requires two readings from the new stream`() {
        val phone =
            sample(1, 60.0, source = DataSourceId.ANDROID_APS).copy(
                sensorId = null,
                sessionId = null,
            )
        val watch = sample(6, 61.0, source = DataSourceId.DEXCOM_G7_WATCH)

        assertNull(CgmGraphPolicy.rangeExcursion(listOf(phone, watch), 70.0, 180.0))
    }

    private fun sample(
        minutes: Long,
        value: Double,
        session: String = "session",
        quality: CgmQuality = CgmQuality.VALID,
        source: DataSourceId = DataSourceId.DEXCOM_G7_WATCH,
    ) = GlucoseSample(
        valueMgDl = value,
        measuredAtEpochMs = minutes * minute,
        source = source,
        sensorId = "sensor",
        sessionId = session,
        sequenceNumber = minutes,
        receivedAtEpochMs = minutes * minute,
        quality = quality,
    )
}
