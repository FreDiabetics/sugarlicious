package app.aapswear.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TherapyProgressResponsiveLayoutTest {
    private val appearance = TherapyProgressAppearance()

    @Test
    fun `wide therapy widget lays indicators out from left to right`() {
        val indicators = resolveResponsiveTherapyLayout(220f, 90f, appearance)

        assertEquals(TherapyProgressMetric.entries, indicators.map { it.metric })
        assertTrue(indicators.zipWithNext().all { (left, right) -> left.slot.centerXDp < right.slot.centerXDp })
        assertTrue(indicators.map { it.slot.centerYDp }.distinct().size == 1)
    }

    @Test
    fun `tall therapy widget lays indicators out from top to bottom`() {
        listOf(90f to 180f, 90f to 270f).forEach { (width, height) ->
            val indicators = resolveResponsiveTherapyLayout(width, height, appearance)

            assertEquals(TherapyProgressMetric.entries, indicators.map { it.metric })
            assertTrue(indicators.zipWithNext().all { (top, bottom) -> top.slot.centerYDp < bottom.slot.centerYDp })
            assertTrue(indicators.map { it.slot.centerXDp }.distinct().size == 1)
        }
    }

    @Test
    fun `therapy widget switches orientation exactly at portrait aspect`() {
        val square = resolveResponsiveTherapyLayout(100f, 100f, appearance)
        val portrait = resolveResponsiveTherapyLayout(99f, 100f, appearance)

        assertTrue(square[0].slot.centerXDp < square[1].slot.centerXDp)
        assertTrue(portrait[0].slot.centerYDp < portrait[1].slot.centerYDp)
    }
}
