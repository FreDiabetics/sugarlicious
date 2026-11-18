package app.sugarlicious.tools.maintenance.watchface

import org.w3c.dom.Element
import java.io.StringWriter
import java.net.URI
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.test.*

class GraphPopupTargetsTest {
    @Test fun repeatedNativeHandGenerationPreservesIndependentGraphImages() {
        val root = parseXml(watchfaceRepositoryRoot().resolve("watchfaces/sugarlicious-analog/src/main/res/raw/watchface.xml"))
        repeat(2) {
            restoreNativeHands(root, "apex")
            configureWatchfaceGraphPopup(root, "apex")
            assertEquals("[COMPLICATION.MONOCHROMATIC_IMAGE]", root.need(".//Group[@name='graph-cap-ink']/PartImage/Image").attr("resource"))
            assertEquals("[COMPLICATION.SMALL_IMAGE]", root.need(".//Group[@name='graph-range-ink']/PartImage/Image").attr("resource"))
        }
    }

    private fun Element.serialized(): String = StringWriter().also { TransformerFactory.newInstance().newTransformer().transform(DOMSource(this), StreamResult(it)) }.toString()

    @Test fun everyEditorGraphColorHasOneExactReadOnlyLaunchTarget() {
        for ((family, module, configuration) in listOf(Triple("orbit", "orbit", "primary"), Triple("apex", "analog", "materialAccent"))) {
            val root = parseXml(watchfaceRepositoryRoot().resolve("watchfaces/sugarlicious-$module/src/main/res/raw/watchface.xml"))
            root.one(".//Group[@name='native-graph-popup-tap']")?.remove()
            val mask = root.need(".//PartDraw[@renderMode='MASK']").serialized()
            val configurations = root.need("UserConfigurations").serialized()
            configureWatchfaceGraphPopup(root, family)
            val popup = root.need(".//Group[@name='native-graph-popup-tap']")
            val targets = popup.all(".//Launch").map { URI(it.attr("target")) }
            val rangeConfiguration = if (family == "orbit") "secondary" else "materialSubTheme"
            val rangeColors = root.all("UserConfigurations/ColorConfiguration[@id='$rangeConfiguration']/ColorOption").map { it.attr("colors").substringBefore(' ') }.toSet()
            val palettes = root.all("UserConfigurations/ColorConfiguration[@id='$configuration']/ColorOption").map { it.attr("colors").split(' ').take(2) }.toSet()
            assertEquals(palettes.size * rangeColors.size, targets.size)
            val pairs = targets.map { uri -> uri.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') } }.toSet()
            val expected = palettes.flatMap { palette -> rangeColors.map { range -> mapOf("color" to palette[0].removePrefix("#"), "inRange" to range.removePrefix("#"), "outline" to palette[1].removePrefix("#")) } }.toSet()
            assertEquals(expected, pairs)
            assertTrue(popup.need("Condition/Expressions").children().all { "[CONFIGURATION.$configuration.1] == colorRgb(" in it.textContent })
            assertTrue(targets.all { it.scheme == "app" && it.host == "sugarlicious.watchface-graph" && it.path == "/$family" })
            for (part in popup.all(".//PartDraw")) {
                assertEquals(listOf("91", "91", "330", if (family == "orbit") "165" else "97"), listOf("x", "y", "width", "height").map(part::attr))
            }
            val expressions = popup.all(".//Expression").map { it.textContent }
            assertTrue(expressions.all { "[CONFIGURATION.$configuration.0] == colorRgb(" in it || "[CONFIGURATION.$rangeConfiguration.0] == colorRgb(" in it })
            if (family == "orbit") {
                assertTrue(popup.need("Condition/Expressions").children().all { "[CONFIGURATION.arcContent] == \"3\"" in it.textContent })
                val scene = root.need("Scene")
                val drawOrder = scene.children()
                assertSame(scene, popup.parentNode)
                for (slot in listOf("8", "9")) assertTrue(drawOrder.indexOf(popup) > drawOrder.indexOf(scene.need("ComplicationSlot[@slotId='$slot']")))
                assertTrue(drawOrder.indexOf(popup) < drawOrder.indexOf(scene.need("PartDraw[@name='native-hands-center-tap']")))
            }
            assertNull(popup.one(".//Default"))
            val once = root.serialized()
            configureWatchfaceGraphPopup(root, family)
            assertEquals(once, root.serialized())
            root.need(".//Group[@name='native-graph-popup-tap']").remove()
            assertEquals(mask, root.need(".//PartDraw[@renderMode='MASK']").serialized())
            assertEquals(configurations, root.need("UserConfigurations").serialized())
        }
    }
}
