package app.aapswear.mobile

import app.aapswear.model.GlucoseSample
import app.aapswear.model.Trend
import org.junit.Assert.assertEquals
import org.junit.Test

class TrendArrowResolverTest {
    @Test
    fun `AAPS trend always wins`() {
        val result =
            TrendArrowResolver.resolve(
                Trend.SINGLE_UP,
                listOf(GlucoseSample(100.0, 0L), GlucoseSample(50.0, 5 * 60_000L)),
                5 * 60_000L,
                "DoubleDown",
            )
        assertEquals(Trend.SINGLE_UP, result)
    }

    @Test
    fun `calculates forty five up from five minute rate`() {
        val history =
            listOf(
                GlucoseSample(100.0, 0L),
                GlucoseSample(107.5, 5 * 60_000L),
            )
        assertEquals(
            Trend.FORTY_FIVE_UP,
            TrendArrowResolver.resolve(Trend.UNKNOWN, history, 5 * 60_000L),
        )
    }

    @Test
    fun `explicit direction is preferred over calculated fallback`() {
        val history =
            listOf(
                GlucoseSample(100.0, 0L),
                GlucoseSample(100.0, 5 * 60_000L),
            )
        assertEquals(
            Trend.DOUBLE_DOWN,
            TrendArrowResolver.resolve(
                Trend.UNKNOWN,
                history,
                5 * 60_000L,
                "DoubleDown",
            ),
        )
    }
}
