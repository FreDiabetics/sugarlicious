package app.aapswear.datasource.aaps
import kotlin.test.*

class AndroidApsDataSourceTest {
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
}
