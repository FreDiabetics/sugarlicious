package app.aapswear.model

import kotlin.test.Test
import kotlin.test.assertEquals

class GlucoseTrendSizingTest {
    @Test
    fun `2x2 reference renderbox ratio stays stable`() {
        assertEquals(37.236f, GlucoseTrendSizing.arrowHeightForGlucoseHeight(58f), 0.01f)
    }

    @Test
    fun `arrow follows glucose height without changing typography`() {
        val glucoseHeight = 44f
        assertEquals(
            GlucoseTrendSizing.REFERENCE_ARROW_TO_GLUCOSE_HEIGHT,
            GlucoseTrendSizing.arrowHeightForGlucoseHeight(glucoseHeight) / glucoseHeight,
            0.0001f,
        )
    }
}
