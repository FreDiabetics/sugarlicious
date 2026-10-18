package app.aapswear.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WidgetCatalogContractTest {
    @Test
    fun `activity widget is absent from the product catalog`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val source = File("src/main/kotlin/app/aapswear/mobile/SugarliciousWidgets.kt").readText()

        assertFalse(manifest.contains("ActivityWidgetReceiver"))
        assertFalse(source.contains("class ActivityWidget"))
        assertFalse(source.contains("WidgetKind.ACTIVITY"))
        assertFalse(File("src/main/res/xml/activity_widget_info.xml").exists())
    }

    @Test
    fun `therapy widget reuses mobile presentations and ring geometry`() {
        val source = File("src/main/kotlin/app/aapswear/mobile/SugarliciousWidgets.kt").readText()

        assertTrue(source.contains("therapyIndicatorPresentations("))
        assertTrue(source.contains("TherapyRingGeometry()"))
        assertTrue(source.contains("renderTherapyIndicatorWidget("))
    }

    @Test
    fun `widget background keeps configured alpha and reset color`() {
        assertEquals(0x480A141E, widgetBackgroundArgb(true, 0x480A141E))
        assertEquals(0x00000000, widgetBackgroundArgb(false, 0xFFFF0000.toInt()))
        assertEquals(0x00000000, widgetHostBackgroundArgb())
        assertEquals(0xFF000000.toInt(), WidgetInstanceConfiguration().backgroundArgb)
    }
}
