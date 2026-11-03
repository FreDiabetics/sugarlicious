package app.aapswear.mobile

import app.aapswear.model.CgmThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlucoseBarScaleTest {
    @Test fun `marker follows glucose and clamps only at scale ends`() {
        val scale = glucoseBarScale(CgmThresholds.DEFAULT)
        assertEquals(.25f, scale.fraction(100.0)!!, .001f)
        assertEquals(0f, scale.fraction(-1.0)!!, .001f)
        assertEquals(1f, scale.fraction(500.0)!!, .001f)
        assertNull(scale.fraction(Double.NaN))
    }

    @Test fun `ninety percent time in range occupies ninety percent of bar`() {
        val distribution = glucoseBarDistribution(MeasuredRangeStats(100, 90, 5, 5, 300000))!!
        assertEquals(.9f, distribution.highStart - distribution.lowEnd, .0001f)
        assertEquals(.5f, glucoseBarMarker(125.0, CgmThresholds.DEFAULT, distribution)!!, .0001f)
        assertEquals(.05f, glucoseBarMarker(70.0, CgmThresholds.DEFAULT, distribution)!!, .0001f)
        assertEquals(.95f, glucoseBarMarker(180.0, CgmThresholds.DEFAULT, distribution)!!, .0001f)
    }

    @Test fun `missing measured coverage never invents range shares`() {
        assertNull(glucoseBarDistribution(MeasuredRangeStats(1, null, null, null)))
        assertNull(glucoseBarMarker(Double.NaN, CgmThresholds.DEFAULT, GlucoseBarDistribution(.1f, .9f)))
    }

    @Test fun `short measured excursions survive rounded zero percent`() {
        val distribution = glucoseBarDistribution(MeasuredRangeStats(100, 100, 0, 0, 100000, 400.0, 100.0))!!
        assertEquals(.004f, distribution.lowEnd, .00001f)
        assertEquals(.999f, distribution.highStart, .00001f)
    }
}
