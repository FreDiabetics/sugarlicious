package app.aapswear.mobile

import app.aapswear.model.GlucoseSample
import app.aapswear.model.RangeExcursion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetGraphRangePolicyTest {
    private val now = 2_000_000_000L

    @Test
    fun `single high does not activate and second consecutive high does`() {
        assertNull(sustainedRangeExcursion(samples(120.0, 166.0), 80.0, 160.0))
        assertEquals(RangeExcursion.HIGH, sustainedRangeExcursion(samples(166.0, 171.0), 80.0, 160.0))
    }

    @Test
    fun `single low does not activate and second consecutive low does`() {
        assertNull(sustainedRangeExcursion(samples(120.0, 77.0), 80.0, 160.0))
        assertEquals(RangeExcursion.LOW, sustainedRangeExcursion(samples(77.0, 73.0), 80.0, 160.0))
    }

    @Test
    fun `first in-range value clears active excursion`() {
        assertNull(sustainedRangeExcursion(samples(166.0, 171.0, 158.0), 80.0, 160.0))
        assertNull(sustainedRangeExcursion(samples(77.0, 73.0, 84.0), 80.0, 160.0))
    }

    private fun samples(vararg values: Double): List<GlucoseSample> =
        values.mapIndexed { index, value ->
            val minutesBack = (values.lastIndex - index) * 5L
            GlucoseSample(valueMgDl = value, measuredAtEpochMs = now - minutesBack * 60_000L)
        }
}
