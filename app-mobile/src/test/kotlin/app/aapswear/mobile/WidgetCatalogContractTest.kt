package app.aapswear.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class WidgetCatalogContractTest {
    @Test
    fun `adaptive glucose and graph presets are registered with exact target cells`() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val receivers = manifest.getElementsByTagName("receiver")
        val names =
            (0 until receivers.length).associate { index ->
                val element = receivers.item(index) as org.w3c.dom.Element
                val name = element.getAttribute("android:name")
                val metadata = element.getElementsByTagName("meta-data")
                val resource = if (metadata.length > 0) (metadata.item(0) as org.w3c.dom.Element).getAttribute("android:resource") else ""
                name to resource
            }

        assertEquals("@xml/glucose_widget_info", names[".GlucoseWidgetReceiver"])
        assertEquals("@xml/glucose_widget_2x2_info", names[".GlucoseWidget2x2Receiver"])
        assertEquals("@xml/glucose_widget_4x2_info", names[".GlucoseWidget4x2Receiver"])
        assertEquals("@xml/graph_widget_info", names[".GraphWidgetReceiver"])
        assertEquals("@xml/graph_widget_2x2_info", names[".GraphWidget2x2Receiver"])
        assertEquals("@xml/graph_widget_4x2_info", names[".GraphWidget4x2Receiver"])

        assertTargetCells("glucose_widget_info.xml", 2, 1)
        assertTargetCells("glucose_widget_2x2_info.xml", 2, 2)
        assertTargetCells("glucose_widget_4x2_info.xml", 4, 2)
        assertTargetCells("graph_widget_info.xml", 2, 1)
        assertTargetCells("graph_widget_2x2_info.xml", 2, 2)
        assertTargetCells("graph_widget_4x2_info.xml", 4, 2)
    }

    private fun assertTargetCells(fileName: String, width: Int, height: Int) {
        val root =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(File("src/main/res/xml/$fileName"))
                .documentElement
        assertEquals(width.toString(), root.getAttribute("android:targetCellWidth"))
        assertEquals(height.toString(), root.getAttribute("android:targetCellHeight"))
    }

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
