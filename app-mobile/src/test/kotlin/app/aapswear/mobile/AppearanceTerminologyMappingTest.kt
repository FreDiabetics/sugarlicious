package app.aapswear.mobile

import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.model.AppearanceTerminology
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceTerminologyMappingTest {
    @Test
    fun `mobile and widget equivalent roles use canonical labels`() {
        assertEquals(AppearanceTerminology.GRAPH_BACKGROUND, SugarliciousColorRole.GRAPH_BACKGROUND.label)
        assertEquals(AppearanceTerminology.GRAPH_BACKGROUND, WidgetColorRole.GRAPH_BACKGROUND.label)
        assertEquals(AppearanceTerminology.GRAPH_DOT_LOW, SugarliciousColorRole.CGM_DOT_LOW.label)
        assertEquals(AppearanceTerminology.GRAPH_DOT_LOW, WidgetColorRole.DOT_LOW.label)
        assertEquals(AppearanceTerminology.GRAPH_DOT_IN_RANGE, SugarliciousColorRole.CGM_DOT_IN_RANGE.label)
        assertEquals(AppearanceTerminology.GRAPH_DOT_IN_RANGE, WidgetColorRole.DOT_IN_RANGE.label)
        assertEquals(AppearanceTerminology.GRAPH_DOT_HIGH, SugarliciousColorRole.CGM_DOT_HIGH.label)
        assertEquals(AppearanceTerminology.GRAPH_DOT_HIGH, WidgetColorRole.DOT_HIGH.label)
        assertEquals(AppearanceTerminology.GRAPH_AXIS_TEXT, SugarliciousColorRole.GRAPH_LABEL.label)
        assertEquals(AppearanceTerminology.GRAPH_AXIS_TEXT, WidgetColorRole.AXIS.label)
    }
}
