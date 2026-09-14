package app.aapswear.datasource.xdrip

import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XdripPayloadAdapterTest {
    private val now = 1_900_000_000_000L

    @Test fun parsesOfficialBroadcastExtras() {
        val state =
            XdripPayloadAdapter().parse(
                mapOf(
                    XdripContract.EXTRA_BG to 126.0,
                    XdripContract.EXTRA_TIME to now - 60_000L,
                    XdripContract.EXTRA_SLOPE_NAME to "FortyFiveUp",
                    XdripContract.EXTRA_UNITS to "mmol/L",
                    XdripContract.EXTRA_VERSION to "2026.08.08",
                ),
                now,
            )!!
        assertEquals(DataSourceId.XDRIP_PLUS, state.source)
        assertEquals(126.0, state.glucose!!.valueMgDl, 0.0)
        assertEquals(GlucoseUnit.MMOL_L, state.glucose!!.displayUnit)
        assertEquals(Trend.FORTY_FIVE_UP, state.glucose!!.trend)
    }

    @Test fun rejectsInvalidGlucose() {
        assertNull(
            XdripPayloadAdapter().parse(
                mapOf(XdripContract.EXTRA_BG to 0, XdripContract.EXTRA_TIME to now),
                now,
            ),
        )
    }
}
