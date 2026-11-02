package app.aapswear.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class OverviewGraphSizingTest {
    @Test fun `three therapy columns fit exact landscape pixels without rounding overflow`() {
        val width = overviewProgressColumnWidthPx(1508, 42, 364)
        assertEquals(474, width)
        org.junit.Assert.assertTrue(width * 3 + 84 <= 1508)
        assertEquals(364, overviewProgressColumnWidthPx(900, 42, 364))
    }

    @Test fun `metabolic graph stays readable on compact and large profiles`() {
        assertEquals(180, overviewMetabolicGraphHeight(98))
        assertEquals(202, overviewMetabolicGraphHeight(170))
    }

    @Test fun `independently scrolling landscape does not shrink chart to fit cards`() {
        assertEquals(400f, overviewFittedCgmHeight(400f, 360f, 0f, listOf(180f, null, 202f)))
    }

    @Test fun `therapy and CGM exactly fill initial viewport regardless of following cards`() {
        val height = overviewFittedCgmHeight(400f, 800f, 280f, listOf(180f, null, 300f, 240f))
        assertEquals(322f, height)
        assertEquals(800f, 280f + 6f + 12f + 180f + height)
    }

    @Test fun `disabling therapy lifts graph to same top and extends it to bottom`() {
        assertEquals(514f, overviewFittedCgmHeight(400f, 800f, 280f, listOf(null, 300f)))
        assertEquals(514f, overviewFittedCgmHeight(400f, 800f, 280f, listOf(null)))
    }

    @Test fun `following cards never shrink CGM and accessible minimum remains usable`() {
        assertEquals(
            overviewFittedCgmHeight(400f, 800f, 280f, listOf(180f, null)),
            overviewFittedCgmHeight(400f, 800f, 280f, listOf(180f, null, 500f)),
        )
        assertEquals(96f, overviewFittedCgmHeight(400f, 500f, 280f, listOf(180f, null)))
    }
}
