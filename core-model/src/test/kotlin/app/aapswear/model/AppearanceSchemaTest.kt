package app.aapswear.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AppearanceSchemaTest {
    @Test fun `argb conversion accepts rgb and argb`() {
        assertEquals(0xFF12AB34.toInt(), ArgbColor.parse("#12AB34"))
        assertEquals(0x8012AB34.toInt(), ArgbColor.parse("8012ab34"))
        assertEquals("#8012AB34", ArgbColor.format(0x8012AB34.toInt()))
        assertNull(ArgbColor.parse("#12345"))
    }

    @Test fun `light default removes outline while dark retains it`() {
        assertFalse(TrendArrowStyle.defaults(AppearanceMode.LIGHT, -1).outlineEnabled)
        assertEquals(0.65f, TrendArrowStyle.defaults(AppearanceMode.DARK, -1).renderSpec().outlineThicknessDp)
    }

    @Test fun `render spec normalizes values without mutating style semantics`() {
        val style = TrendArrowStyle(-1, false, 0, 99f, 500, 2f)
        val spec = style.renderSpec()
        assertEquals(0f, spec.outlineThicknessDp)
        assertEquals(2f, spec.scale)
        assertEquals(0xFFFFFFFF.toInt(), spec.fillColor)
    }

    @Test fun `owners represent isolated persistence domains`() {
        assertNotEquals(AppearanceOwner.MOBILE, AppearanceOwner.WEAR)
        assertNotEquals(AppearanceOwner.WEAR, AppearanceOwner.COLLECTOR)
    }
}
