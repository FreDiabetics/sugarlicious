package app.aapswear.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CgmGraphYScaleTest {
    @Test
    fun `all modes map equal boundary values to equal pixels`() {
        CgmGraphScaleMode.entries.forEach { mode ->
            val scale =
                CgmGraphYScale.resolve(
                    mode,
                    visibleValuesMgDl = listOf(80.0, 120.0, 240.0),
                    requiredValuesMgDl = listOf(80.0, 160.0),
                )
            val historyPixelY = 320.0 - scale.ratio(120.0) * 280.0
            val predictionPixelY = 320.0 - scale.ratio(120.0) * 280.0
            assertEquals(historyPixelY, predictionPixelY, 0.0, mode.name)
        }
    }

    @Test
    fun `dynamic modes include visible predictions and targets with padding`() {
        listOf(CgmGraphScaleMode.DYNAMIC, CgmGraphScaleMode.LOGARITHMIC_DYNAMIC).forEach { mode ->
            val scale = CgmGraphYScale.resolve(mode, listOf(110.0, 260.0), requiredValuesMgDl = listOf(80.0, 160.0))
            assertTrue(scale.minimumMgDl < 80.0)
            assertTrue(scale.maximumMgDl > 260.0)
        }
    }

    @Test
    fun `logarithmic modes use a real logarithmic transform`() {
        val scale = CgmGraphYScale(CgmGraphScaleMode.LOGARITHMIC, 40.0, 400.0)
        assertEquals(0.5, scale.ratio(kotlin.math.sqrt(40.0 * 400.0)), 0.000001)
        assertEquals(kotlin.math.sqrt(40.0 * 400.0), scale.inverseRatio(0.5), 0.000001)
    }

    @Test
    fun `static CGM modes ignore visible viewport while dynamic modes respond`() {
        listOf(CgmGraphScaleMode.STATIC, CgmGraphScaleMode.LOGARITHMIC).forEach { mode ->
            val low = CgmGraphYScale.resolve(mode, listOf(80.0, 120.0), 40.0, 400.0)
            val high = CgmGraphYScale.resolve(mode, listOf(180.0, 320.0), 40.0, 400.0)
            assertEquals(low.minimumMgDl, high.minimumMgDl, 0.0, mode.name)
            assertEquals(low.maximumMgDl, high.maximumMgDl, 0.0, mode.name)
            assertEquals(low.ratio(120.0), high.ratio(120.0), 0.0, mode.name)
        }
        listOf(CgmGraphScaleMode.DYNAMIC, CgmGraphScaleMode.LOGARITHMIC_DYNAMIC).forEach { mode ->
            val low = CgmGraphYScale.resolve(mode, listOf(80.0, 120.0), requiredValuesMgDl = listOf(80.0, 160.0))
            val high = CgmGraphYScale.resolve(mode, listOf(180.0, 320.0), requiredValuesMgDl = listOf(80.0, 160.0))
            assertTrue(low.maximumMgDl != high.maximumMgDl, mode.name)
        }
    }
}
