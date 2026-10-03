package app.aapswear.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

class SugarliciousAnalogPreviewGeometryTest {
    @Test
    fun `active preview keeps its established geometry`() {
        assertTrue(SugarliciousAnalogGeometry.graph == AnalogRectGeometry(59f, 63f, 394f, 138f))
        assertTrue(SugarliciousAnalogGeometry.graphContent == AnalogRectGeometry(129f, 64f, 255f, 138f))
        assertTrue(SugarliciousAnalogGeometry.middleLeft == AnalogRectGeometry(83f, 195f, 123f, 123f))
        assertTrue(SugarliciousAnalogGeometry.middleRight == AnalogRectGeometry(306f, 195f, 123f, 123f))
        assertTrue(SugarliciousAnalogGeometry.bottomCenter == AnalogRectGeometry(181f, 281f, 150f, 149f))
        assertTrue(SugarliciousAnalogGeometry.middleLeftText == AnalogRectGeometry(91f, 259f, 112f, 32f))
        assertTrue(SugarliciousAnalogGeometry.middleLeftTitle == AnalogRectGeometry(91f, 223f, 107f, 31f))
        assertTrue(SugarliciousAnalogGeometry.middleRightText == AnalogRectGeometry(314f, 259f, 107f, 32f))
        assertTrue(SugarliciousAnalogGeometry.middleRightTitle == AnalogRectGeometry(314f, 223f, 107f, 30f))
        assertTrue(SugarliciousAnalogGeometry.bottomText == AnalogRectGeometry(188f, 333f, 137f, 45f))
    }

    @Test fun `slots share one center and remain symmetric and center safe`() {
        val geometry = SugarliciousAnalogGeometry
        val left = geometry.centerOf(geometry.middleLeft)
        val right = geometry.centerOf(geometry.middleRight)
        val bottom = geometry.centerOf(geometry.bottomCenter)
        assertTrue(kotlin.math.abs((left.x + right.x) - geometry.CANVAS) < 0.001f)
        assertTrue(left.y == right.y)
        assertTrue(bottom.y > left.y)
        assertTrue(geometry.bottomCenter.y > geometry.center.y + geometry.centerSafetyRadius)
        assertTrue(geometry.graph.x >= geometry.center.x - geometry.safeRadius)
        assertTrue(geometry.graph.x + geometry.graph.width <= geometry.center.x + geometry.safeRadius)
        assertTrue((geometry.outerTextDiameter / 2f) + geometry.outerStroke / 2f <= geometry.safeRadius)
        assertTrue(kotlin.math.abs((geometry.graph.width / geometry.graph.height) - (346.25038f / 121.33356f)) < 0.01f)
        assertTrue(geometry.handPivot == geometry.center)
        assertTrue(geometry.outerProgressDiameter < geometry.outerTextDiameter)
    }

    @Test
    fun `authoritative WFS template remains on its native 450 canvas`() {
        val name = "sugarlicious_analog_template.png"
        val image =
            requireNotNull(
                ImageIO.read(repoFile("app-mobile/src/main/res/drawable-nodpi/$name")),
            ) { "$name must be a readable PNG" }
        assertTrue("$name must be 450 px wide", image.width == 450)
        assertTrue("$name must be 450 px high", image.height == 450)
    }

    @Test
    fun `dial uses the exact WFS luminance and outline colors`() {
        val template =
            requireNotNull(
                ImageIO.read(repoFile("app-mobile/src/main/res/drawable-nodpi/sugarlicious_analog_template.png")),
            )
        assertTrue(template.getRGB(291, 26) and 0xFFFFFF == 0x4C4C4C)
        assertTrue(template.getRGB(73, 225) and 0xFFFFFF == 0x888888)
        assertTrue("graph cutout must stay transparent", template.getRGB(225, 100) ushr 24 == 0)
        assertTrue("dial background outside graph must stay opaque", template.getRGB(225, 225) ushr 24 == 0xFF)
    }

    @Test
    fun `outer ranged rings are inset thick and share matching tracks`() {
        val document = watchFaceDocument()
        val expectedStarts = mapOf("0" to "288", "1" to "18", "2" to "108")

        expectedStarts.forEach { (slotId, start) ->
            val ranged = complication(slot(document.documentElement, slotId), "RANGED_VALUE")
            val arcs = ranged.getElementsByTagName("Arc").elements()
            assertEquals("slot $slotId needs track and value arcs", 2, arcs.size)
            arcs.forEach { arc ->
                assertEquals("256", arc.getAttribute("centerX"))
                assertEquals("256", arc.getAttribute("centerY"))
                assertEquals("388", arc.getAttribute("width"))
                assertEquals("388", arc.getAttribute("height"))
                assertEquals(start, arc.getAttribute("startAngle"))
                assertEquals("CLOCKWISE", arc.getAttribute("direction"))
                assertEquals(
                    "22",
                    arc
                        .getElementsByTagName("Stroke")
                        .item(0)
                        .asElement()
                        .getAttribute("thickness"),
                )
            }
            val valueTransform =
                arcs
                    .last()
                    .getElementsByTagName("Transform")
                    .item(0)
                    .asElement()
            assertTrue(valueTransform.getAttribute("value").contains("* 42"))
            assertTrue("zero-span ranges must be guarded", valueTransform.getAttribute("value").contains("=="))
        }

        val outerEdge = 256f + 388f / 2f + 22f / 2f
        assertTrue("outer rings need a safe bezel inset", outerEdge <= 462f)
    }

    @Test
    fun `bottom ranged renderer has matching arcs and its trend icon in the open segment`() {
        val document = watchFaceDocument()
        val ranged = complication(slot(document.documentElement, "6"), "RANGED_VALUE")
        val arcs = ranged.getElementsByTagName("Arc").elements()

        assertEquals(2, arcs.size)
        arcs.forEach { arc ->
            assertEquals("75", arc.getAttribute("centerX"))
            assertEquals("75", arc.getAttribute("centerY"))
            assertEquals("124", arc.getAttribute("width"))
            assertEquals("124", arc.getAttribute("height"))
            assertEquals("232", arc.getAttribute("startAngle"))
            assertEquals(
                "16",
                arc
                    .getElementsByTagName("Stroke")
                    .item(0)
                    .asElement()
                    .getAttribute("thickness"),
            )
        }
        val valueTransform =
            arcs
                .last()
                .getElementsByTagName("Transform")
                .item(0)
                .asElement()
        assertTrue(valueTransform.getAttribute("value").contains("* 256"))
        assertTrue("zero-span ranges must be guarded", valueTransform.getAttribute("value").contains("=="))

        val icon =
            ranged
                .getElementsByTagName("Image")
                .elements()
                .singleOrNull { it.getAttribute("resource") == "[COMPLICATION.MONOCHROMATIC_IMAGE]" }
        assertNotNull("the ranged renderer itself must draw the provider trend icon", icon)
        val iconPart = icon!!.parentNode.asElement()
        assertTrue(iconPart.getAttribute("y").toInt() >= 112)
    }

    @Test
    fun `graph stays provider driven and preserves the native WFS aspect ratio`() {
        val document = watchFaceDocument()
        val graphSlot = slot(document.documentElement, "7")
        val policy = graphSlot.getElementsByTagName("DefaultProviderPolicy").item(0).asElement()
        assertEquals(
            "app.aapswear/app.aapswear.complications.GlucoseGraphComplication",
            policy.getAttribute("primaryProvider"),
        )
        assertEquals("SMALL_IMAGE", policy.getAttribute("primaryProviderType"))

        val imagePart =
            complication(graphSlot, "SMALL_IMAGE")
                .getElementsByTagName("PartImage")
                .item(0)
                .asElement()
        assertEquals("70", imagePart.getAttribute("x"))
        assertEquals("1", imagePart.getAttribute("y"))
        assertEquals("255", imagePart.getAttribute("width"))
        assertEquals("138", imagePart.getAttribute("height"))
        assertTrue(kotlin.math.abs(255f / 138f - 224f / 121.3336f) < 0.01f)
    }

    @Test
    fun `three complete hand packages remain selectable`() {
        val document = watchFaceDocument()
        val declared =
            document
                .documentElement
                .getElementsByTagName("UserConfigurations")
                .item(0)
                .asElement()
                .getElementsByTagName("ListConfiguration")
                .elements()
                .single { it.getAttribute("id") == "handStyle" }
        assertEquals(listOf("0", "1", "2"), declared.getElementsByTagName("ListOption").elements().map { it.getAttribute("id") })

        val rendered =
            document
                .documentElement
                .getElementsByTagName("Scene")
                .item(0)
                .asElement()
                .childNodes
                .elements()
                .single { it.tagName == "ListConfiguration" && it.getAttribute("id") == "handStyle" }
        val expected =
            listOf(
                listOf("hour_hand", "minute_hand", "second_hand"),
                listOf("hour_hand_transparent", "minute_hand_transparent", "second_hand_transparent"),
                listOf("hour_hand_tblack", "minute_hand_tblack", "second_hand_tblack"),
            )
        val options = rendered.getElementsByTagName("ListOption").elements()
        assertEquals(3, options.size)
        options.zip(expected).forEach { (option, resources) ->
            val actual =
                listOf("HourHand", "MinuteHand", "SecondHand").map { tag ->
                    option
                        .getElementsByTagName(tag)
                        .item(0)
                        .asElement()
                        .getAttribute("resource")
                }
            assertEquals(resources, actual)
            resources.forEach { resource ->
                assertTrue(
                    "$resource must be packaged",
                    repoFile("watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi/$resource.png").isFile,
                )
            }
            val secondHand = option.getElementsByTagName("SecondHand").item(0).asElement()
            val ambient = secondHand.getElementsByTagName("Variant").item(0).asElement()
            assertEquals("AMBIENT", ambient.getAttribute("mode"))
            assertEquals("0", ambient.getAttribute("value"))
        }
    }

    private fun watchFaceDocument() =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(repoFile("watchfaces/sugarlicious-analog/src/main/res/raw/watchface.xml"))

    private fun slot(root: Element, slotId: String): Element =
        root
            .getElementsByTagName("ComplicationSlot")
            .elements()
            .single { it.getAttribute("slotId") == slotId }

    private fun complication(slot: Element, type: String): Element =
        slot
            .childNodes
            .elements()
            .single { it.tagName == "Complication" && it.getAttribute("type") == type }

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).mapNotNull { index -> item(index) as? Element }

    private fun org.w3c.dom.Node.asElement(): Element = this as Element

    private fun repoFile(path: String): File {
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(File(cwd, path), File(cwd.parentFile, path))
        return candidates.firstOrNull(File::isFile)
            ?: error("Repository file not found: $path from ${cwd.absolutePath}")
    }
}
