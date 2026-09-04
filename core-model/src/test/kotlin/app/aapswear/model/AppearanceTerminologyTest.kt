package app.aapswear.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppearanceTerminologyTest {
    @Test
    fun `canonical labels are stable and unambiguous`() {
        assertEquals("Graph-Hintergrund", AppearanceTerminology.GRAPH_BACKGROUND)
        assertEquals("Zielbereich", AppearanceTerminology.GRAPH_TARGET_AREA)
        assertEquals("CGM-Punkte · im Ziel", AppearanceTerminology.GRAPH_DOT_IN_RANGE)
        assertEquals("Trendpfeil", AppearanceTerminology.TREND_ARROW)

        val graphLabels =
            listOf(
                AppearanceTerminology.GRAPH_BACKGROUND,
                AppearanceTerminology.GRAPH_LOW_AREA,
                AppearanceTerminology.GRAPH_TARGET_AREA,
                AppearanceTerminology.GRAPH_HIGH_AREA,
                AppearanceTerminology.GRAPH_LOW_LINE,
                AppearanceTerminology.GRAPH_HIGH_LINE,
                AppearanceTerminology.GRAPH_DOT_LOW,
                AppearanceTerminology.GRAPH_DOT_IN_RANGE,
                AppearanceTerminology.GRAPH_DOT_HIGH,
                AppearanceTerminology.GRAPH_DOT_OUTLINE,
                AppearanceTerminology.GRAPH_AXIS_TEXT,
                AppearanceTerminology.GRAPH_AXIS_TICK,
                AppearanceTerminology.GRAPH_NOW_LINE,
                AppearanceTerminology.GRAPH_DIVIDER,
            )
        assertTrue(graphLabels.all(String::isNotBlank))
        assertEquals(graphLabels.size, graphLabels.distinct().size)
    }
}
