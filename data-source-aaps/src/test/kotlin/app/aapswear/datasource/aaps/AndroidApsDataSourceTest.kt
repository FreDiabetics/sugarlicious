package app.aapswear.datasource.aaps
import kotlin.test.*

class AndroidApsDataSourceTest {
    @Test
    fun `official package catalog contains only standard AndroidAPS`() {
        assertEquals(listOf("info.nightscout.androidaps"), AapsCapabilityDetector.KNOWN_PACKAGES)
    }

    @Test fun detectsContracts() {
        val d = AapsCapabilityDetector()
        assertEquals(AapsContract.UNSUPPORTED, d.detect(emptyMap()))
        assertEquals(
            AapsContract.STABLE_LEGACY_STATUS,
            d.detect(
                mapOf(
                    "glucoseMgdl" to 100,
                    "glucoseTimeStamp" to 1,
                ),
            ),
        )
        assertEquals(
            AapsContract.DEV_EXTENDED_STATUS_V1,
            d.detect(
                mapOf(
                    "glucoseMgdl" to 100,
                    "glucoseTimeStamp" to 1,
                    "deltaMgdl" to 1.0,
                ),
            ),
        )
        assertEquals("AAPS_EXTENDED_STATUS_V1", AapsContract.DEV_EXTENDED_STATUS_V1.id)
    }

    @Test fun keepsOnlyLastValidState() {
        val s = AndroidApsDataSource()
        assertNull(s.accept(mapOf("glucoseMgdl" to 1, "glucoseTimeStamp" to 1), 2))
        val valid =
            assertNotNull(
                s.accept(
                    mapOf(
                        "glucoseMgdl" to 100,
                        "glucoseTimeStamp" to 1,
                    ),
                    2,
                ),
            )
        ;assertEquals(valid, s.latest())
    }

    @Test fun rejectsImplausibleFutureState() {
        val s = AndroidApsDataSource()
        assertNull(
            s.accept(
                mapOf(
                    "glucoseMgdl" to 100,
                    "glucoseTimeStamp" to 400_001L,
                ),
                100_000L,
            ),
        )
    }

    @Test fun `rejects non finite timestamps and glucose values`() {
        val validator = AapsPayloadValidator()

        assertFalse(validator.isValid(mapOf("glucoseMgdl" to Double.NaN, "glucoseTimeStamp" to 1L), 2L))
        assertFalse(validator.isValid(mapOf("glucoseMgdl" to 100.0, "glucoseTimeStamp" to Double.POSITIVE_INFINITY), 2L))
    }
}
