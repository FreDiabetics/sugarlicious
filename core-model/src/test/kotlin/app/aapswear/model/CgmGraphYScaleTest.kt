package app.aapswear.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CgmGraphYScaleTest {
    @Test
    fun `all modes map equal boundary values to equal pixels`() {
        CgmGraphScaleMode.entries.forEach { mode ->
            val scale = CgmGraphYScale.resolve(
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
    }
}
