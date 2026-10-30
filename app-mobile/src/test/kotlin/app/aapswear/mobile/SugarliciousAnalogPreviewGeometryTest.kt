package app.aapswear.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Expectations measured independently from the pinned original 450px WFS. */
class SugarliciousAnalogPreviewGeometryTest {
    @Test fun `selected Apex graph remains available in ambient without a frozen empty frame`() {
        val graph = slot("7")
        val ambient = graph.getElementsByTagName("Variant").item(0) as Element
        assertTrue(ambient.getAttribute("value").toInt() > 0)
        val small =
            (0 until graph.getElementsByTagName("Complication").length)
                .map { graph.getElementsByTagName("Complication").item(it) as Element }
                .single { it.getAttribute("type") == "SMALL_IMAGE" }
        val policies = small.getElementsByTagName("Variant")
        assertTrue((0 until policies.length).all { (policies.item(it) as Element).getAttribute("value").toInt() > 0 })
    }

    @Test fun `default configuration matches the captured Apex template`() {
        val root = document()
        val expected = mapOf("materialAccent" to "37", "materialSubTheme" to "37", "progressMarker" to "TRUE", "progressGradient" to "TRUE")
        val nodes = root.getElementsByTagName("UserConfigurations").item(0).childNodes
        (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.forEach { element ->
            expected[element.getAttribute("id")]?.let { assertEquals(it, element.getAttribute("defaultValue")) }
        }
    }

    @Test
    fun `glucose color is opt in and only changes value text not icons or progress`() {
        val configs = document().getElementsByTagName("BooleanConfiguration")
        val toggle = (0 until configs.length).map { configs.item(it) as Element }.singleOrNull { it.getAttribute("id") == "glucoseColor" }
        assertTrue("glucose coloring needs a separate switch", toggle != null)
        assertEquals("FALSE", toggle!!.getAttribute("defaultValue"))
        val fonts = document().getElementsByTagName("Font")
        val colored = (0 until fonts.length).map { fonts.item(it) as Element }.filter { it.getAttribute("color") == "[COMPLICATION.RANGED_VALUE_COLORS]" }
        assertTrue("number must consume the Wear color hint", colored.isNotEmpty())
        colored.forEach { font ->
            assertTrue(font.textContent.contains("%s"))
            assertEquals("[COMPLICATION.TEXT]", (font.getElementsByTagName("Parameter").item(0) as Element).getAttribute("expression"))
        }
        listOf("Stroke", "PartImage").forEach { tag ->
            val nodes = document().getElementsByTagName(tag)
            assertTrue((0 until nodes.length).map { nodes.item(it) as Element }.none { it.getAttribute("color").contains("RANGED_VALUE_COLORS") || it.getAttribute("tintColor").contains("RANGED_VALUE_COLORS") })
        }
    }

    @Test
    fun `every circular progress fill switches gradient and solid visibility directly`() {
        (0..6).forEach { id ->
            val nodes = slot(id.toString()).getElementsByTagName("Group")
            val groups = (0 until nodes.length).map { nodes.item(it) as Element }
            listOf("gradient" to "255 : 0", "solid" to "0 : 255").forEach { (mode, alpha) ->
                val group = groups.singleOrNull { it.getAttribute("name") == "progress-$mode-$id" }
                assertTrue("slot $id needs a directly bound $mode layer", group != null)
                val transform = group!!.getElementsByTagName("Transform").item(0) as Element
                assertEquals("[CONFIGURATION.progressGradient] == \"TRUE\" ? $alpha", transform.getAttribute("value"))
                assertEquals(if (mode == "gradient") 1 else 0, group.getElementsByTagName("SweepGradient").length)
            }
        }
    }

    @Test
    fun `hand switching never registers nested configuration listeners in Samsung editor`() {
        val groups = document().getElementsByTagName("Group")
        val hands = (0 until groups.length).map { groups.item(it) as Element }.single { it.getAttribute("name") == "hands-and-indicators" }
        assertEquals("switch visibility without rebuilding the editor listener map", 0, hands.getElementsByTagName("ListConfiguration").length)
        assertEquals(3, hands.getElementsByTagName("HourHand").length)
        assertEquals(3, hands.getElementsByTagName("MinuteHand").length)
        assertEquals(6, hands.getElementsByTagName("SecondHand").length)
    }

    @Test
    fun `smooth seconds use highest supported WFF1 sweep frequency for all hand sets`() {
        val sweeps = document().getElementsByTagName("Sweep")
        assertEquals(3, sweeps.length)
        (0 until sweeps.length).forEach { assertEquals("15", (sweeps.item(it) as Element).getAttribute("frequency")) }
    }

    @Test
    fun `point mode shows the full unchanged colored bar under a hollow black contour`() {
        (0..7).forEach { id ->
            val groups = slot(id.toString()).getElementsByTagName("Group")
            val fill = (0 until groups.length).map { groups.item(it) as Element }.single { it.getAttribute("name") == "progress-fill-$id" }
            val directTransforms = (0 until fill.childNodes.length).mapNotNull { fill.childNodes.item(it) as? Element }.filter { it.tagName == "Transform" }
            assertTrue("point mode must retain the value fill", directTransforms.none { it.getAttribute("target") == "alpha" })
            val marker = (0 until groups.length).map { groups.item(it) as Element }.single { it.getAttribute("name") == "progress-marker-$id" }
            val circles = marker.getElementsByTagName("Ellipse")
            assertEquals("one unfilled ring", 1, circles.length)
            assertEquals("no fill, dark disk or eraser underneath", 0, marker.getElementsByTagName("Fill").length)
            assertEquals("#FF000000", (marker.getElementsByTagName("Stroke").item(0) as Element).getAttribute("color"))
            val transforms = fill.getElementsByTagName("Transform")
            val ends = (0 until transforms.length).map { transforms.item(it) as Element }.filter { it.getAttribute("target") in setOf("endAngle", "endX") }
            assertTrue(ends.isNotEmpty())
            val endpoint = listOf(333, 63, 151, 243, 500, 500, 500, 90)[id]
            val markerMode = if (id == 7) "[CONFIGURATION.progressMarker] == \"TRUE\"" else "([CONFIGURATION.progressMarker] == \"TRUE\" && [CONFIGURATION.progressGap] != \"TRUE\"${if (id == 1) " && [CONFIGURATION.upperRightMarkers] != \"TRUE\"" else ""})"
            assertTrue("no dark remainder in valid marker mode", ends.all { it.getAttribute("value").startsWith("$markerMode && [COMPLICATION.RANGED_VALUE_MAX] > [COMPLICATION.RANGED_VALUE_MIN] ? $endpoint :") })
            if (id == 1) {
                val segmentSwitches = (0 until transforms.length).map { transforms.item(it) as Element }.filter { it.getAttribute("target") == "alpha" && it.getAttribute("value").contains("upperRightMarkers") }
                assertTrue(segmentSwitches.isNotEmpty())
                assertTrue("segment selection is independent of the marker", segmentSwitches.all { !it.getAttribute("value").contains("CONFIGURATION.progressMarker") })
                val ranged = marker.parentNode as Element
                val directGroups = (0 until ranged.childNodes.length).mapNotNull { ranged.childNodes.item(it) as? Element }
                assertEquals("ring must render above segment artwork", marker, directGroups.last())
                val segments = (0 until ranged.getElementsByTagName("PartDraw").length).map { ranged.getElementsByTagName("PartDraw").item(it) as Element }.filter { it.getAttribute("name").startsWith("upper-right-segment-") }
                assertEquals(33, segments.size)
                assertTrue("segmented bars honor marker eligibility instead of forcing a full bar", segments.all { (it.getElementsByTagName("Transform").item(0) as Element).getAttribute("value").contains("$markerMode ||") })
            }
        }
    }

    @Test
    fun `point indicator inner diameter matches track thickness in every ranged slot`() {
        (0..7).forEach { id ->
            val variants = slot(id.toString()).getElementsByTagName("Complication")
            val ranged = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == "RANGED_VALUE" }
            val draws = ranged.getElementsByTagName("PartDraw")
            val groups = ranged.getElementsByTagName("Group")
            val marker = (0 until groups.length).map { groups.item(it) as Element }.single { it.getAttribute("name") == "progress-marker-$id" }
            val track = (0 until draws.length).map { draws.item(it) as Element }.first { it.getAttribute("alpha") == "55" }
            val thickness = (track.getElementsByTagName("Stroke").item(0) as Element).getAttribute("thickness").toDouble()
            val circles = marker.getElementsByTagName("Ellipse")
            assertEquals("one contour, no extra complication slot", 1, circles.length)
            (0 until circles.length).forEach { index ->
                val circle = circles.item(index) as Element
                val stroke = (circle.getElementsByTagName("Stroke").item(0) as Element).getAttribute("thickness").toDouble()
                assertEquals(listOf(6.778676, 6.778676, 6.778676, 6.778676, 4.24, 4.24, 5.185784, 2.84)[id], stroke, .0001)
                assertTrue("contour must remain clearly visible at watch size", stroke >= thickness * .25)
                assertTrue("center must remain visibly hollow", circle.getAttribute("width").toDouble() - stroke >= thickness * .4)
                assertEquals("inner contour follows both track edges", thickness, circle.getAttribute("width").toDouble() - stroke, .0001)
                assertEquals(circle.getAttribute("width"), circle.getAttribute("height"))
                assertEquals("just the black contour", 1, circle.getElementsByTagName("Stroke").length)
            }
            val transforms = marker.getElementsByTagName("Transform")
            assertTrue((0 until transforms.length).map { transforms.item(it) as Element }.any { it.getAttribute("target") == "alpha" && it.getAttribute("value").contains("CONFIGURATION.progressMarker") })
        }
    }

    @Test
    fun `only image graph receives a pattern knockout behind its transparent artwork`() {
        val variants = slot("7").getElementsByTagName("Complication")
        val image = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == "SMALL_IMAGE" }
        val children = (0 until image.childNodes.length).mapNotNull { image.childNodes.item(it) as? Element }
        assertEquals(listOf("Group"), children.map { it.tagName })
        val group = children.single()
        val backing = group.getElementsByTagName("Rectangle").item(0) as Element
        val backingDraw = backing.parentNode as Element
        assertEquals("97", backingDraw.getAttribute("height"))
        assertEquals("91", backingDraw.getAttribute("y"))
        assertEquals("#FF000000", (backing.getElementsByTagName("Fill").item(0) as Element).getAttribute("color"))
        val mask = group.getElementsByTagName("PartDraw").item(0) as Element
        val graph = group.getElementsByTagName("PartImage").item(0) as Element
        assertEquals("MASK", mask.getAttribute("renderMode"))
        val maskArc = mask.getElementsByTagName("Arc").item(0) as Element
        assertEquals("256", maskArc.getAttribute("centerX"))
        assertEquals("256", maskArc.getAttribute("centerY"))
        assertEquals("165", maskArc.getAttribute("width"))
        assertEquals("165", (maskArc.getElementsByTagName("Stroke").item(0) as Element).getAttribute("thickness"))
        assertEquals("[CONFIGURATION.materialAccent.0]", group.getAttribute("tintColor"))
        assertTrue("preserve neutral dot tones before theme tinting", !graph.hasAttribute("tintColor"))
    }

    @Test
    fun `lower left text matches the opposite outer complication in each supported text format`() {
        listOf("SHORT_TEXT", "RANGED_VALUE").forEach { type ->
            fun fonts(id: String): Set<String> {
                val variants = slot(id).getElementsByTagName("Complication")
                val variant = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == type }
                val fonts = variant.getElementsByTagName("Font")
                return (0 until fonts.length).map { (fonts.item(it) as Element).getAttribute("size") }.toSet()
            }
            assertEquals("outer lower left must not use a smaller font", fonts("2"), fonts("3"))
        }
    }

    @Test
    fun `outer therapy labels also retain signed values with one decimal and generic fallback`() {
        (0..3).forEach { id ->
            val parameters = slot(id.toString()).getElementsByTagName("Parameter")
            val expressions = (0 until parameters.length).map { (parameters.item(it) as Element).getAttribute("expression") }
            assertTrue("slot $id needs the same IOB precision as the center", expressions.any { it.contains("numberFormat(\"#.#\"") })
            assertTrue(expressions.contains("[COMPLICATION.TEXT]"))
        }
    }

    @Test
    fun `weekday layout replaces graph and retains German labels without extra complication slots`() {
        val variants = slot("7").getElementsByTagName("Complication")
        val text = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == "SHORT_TEXT" }
        val groups = text.getElementsByTagName("Group")
        val days = (0..6).map { index -> (0 until groups.length).map { groups.item(it) as Element }.single { it.getAttribute("name") == "weekday-$index" } }
        val labels = listOf("M", "D", "M", "D", "F", "S", "S")
        days.forEachIndexed { index, day ->
            assertEquals("\"${labels[index]}\"", (day.getElementsByTagName("Parameter").item(0) as Element).getAttribute("expression"))
        }
        assertTrue("Wednesday selection must clear the upper ranged label", days[2].getAttribute("y").toDouble() >= 86)
        assertEquals(0, text.getElementsByTagName("Image").let { nodes -> (0 until nodes.length).count { (nodes.item(it) as Element).getAttribute("resource") == "[COMPLICATION.SMALL_IMAGE]" } })
        val left = 211.0
        val top = 138.0
        val right = 301.0
        val bottom = 194.0
        days.forEach { day ->
            val angle = Math.toRadians(day.getAttribute("angle").toDouble())
            val halfWidth = 18 * kotlin.math.abs(kotlin.math.cos(angle)) + 15 * kotlin.math.abs(kotlin.math.sin(angle))
            val halfHeight = 18 * kotlin.math.abs(kotlin.math.sin(angle)) + 15 * kotlin.math.abs(kotlin.math.cos(angle))
            val cx = day.getAttribute("x").toDouble() + 18
            val cy = day.getAttribute("y").toDouble() + 18
            assertTrue("${day.getAttribute("name")} selection overlaps graph", right <= cx - halfWidth || left >= cx + halfWidth || bottom <= cy - halfHeight || top >= cy + halfHeight)
        }
    }

    @Test
    fun `upper alternative renders provider title text image and real ranged progress`() {
        val variants = slot("7").getElementsByTagName("Complication")
        listOf("SHORT_TEXT", "RANGED_VALUE").forEach { type ->
            val variant = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == type }
            val parameters = variant.getElementsByTagName("Parameter")
            val fields = (0 until parameters.length).map { (parameters.item(it) as Element).getAttribute("expression") }
            assertTrue(fields.containsAll(listOf("[COMPLICATION.TITLE]", "[COMPLICATION.TEXT]")))
            assertTrue((variant.getElementsByTagName("Image").item(0) as Element).getAttribute("resource").contains("MONOCHROMATIC_IMAGE"))
            if (type == "RANGED_VALUE") {
                val transforms = variant.getElementsByTagName("Transform")
                assertTrue("ranged value must not silently become text only", (0 until transforms.length).any { (transforms.item(it) as Element).getAttribute("value").contains("RANGED_VALUE_VALUE") })
            }
        }
    }

    @Test
    fun `both native color menus expose every measured reference palette within platform limit`() {
        val fixtureFile = repoFile("watchfaces/sugarlicious-analog/reference/public-theme-colors.json")
        val fixtureJson = Json.parseToJsonElement(fixtureFile.readText())
        val fixture = fixtureJson.jsonObject.getValue("palettes").jsonArray
        val nodes = document().getElementsByTagName("ColorConfiguration")
        listOf("materialAccent", "materialSubTheme").forEach { id ->
            val config = (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttribute("id") == id }
            val choices = config.getElementsByTagName("ColorOption")
            assertTrue(choices.length <= 100)
            val colors =
                (0 until choices.length)
                    .map {
                        (choices.item(it) as Element)
                            .getAttribute("colors")
                            .split(" ")
                            .take(3)
                            .joinToString(" ")
                    }.toSet()
            fixture.forEach { palette ->
                val roles = palette.jsonObject.getValue("colors").jsonArray
                val tones = listOf(roles[0], roles[1], roles[3]).joinToString(" ") { it.jsonPrimitive.content }
                assertTrue("$id missing ${palette.jsonObject.getValue("name")}", tones in colors)
            }
        }
    }

    @Test
    fun `minute markers reuse measured dial gaps rather than drawing over wide hour numerals`() {
        val groups = document().getElementsByTagName("Group")
        val dots = (0 until groups.length).map { groups.item(it) as Element }.single { it.getAttribute("name") == "minute-dots" }
        assertEquals(26, dots.getElementsByTagName("Ellipse").length)
    }

    @Test
    fun `short text uses vivid theme tone while bottom trend uses title accent`() {
        (0..3).forEach { id ->
            val variants = slot(id.toString()).getElementsByTagName("Complication")
            val short = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == "SHORT_TEXT" }
            assertEquals("[CONFIGURATION.materialAccent.2]", (short.getElementsByTagName("Font").item(0) as Element).getAttribute("color"))
        }
        assertEquals("[CONFIGURATION.materialSubTheme.1]", (slot("6").getElementsByTagName("PartImage").item(0) as Element).getAttribute("tintColor"))
    }

    @Test
    fun `every clinical slot honors night color and hides in hands only ambient mode`() {
        (0..6).forEach { id ->
            val node = slot(id.toString())
            assertEquals("slot $id", "[CONFIGURATION.nightMode]", node.getAttribute("tintColor"))
            val ambient = node.getElementsByTagName("Variant").item(0) as? Element
            assertTrue("slot $id needs ambient policy", ambient != null)
            assertEquals("[CONFIGURATION.aodStyle] == \"0\" ? 96 : 0", ambient!!.getAttribute("value"))
        }
    }

    @Test
    fun `opaque circle cleanup follows graph and precedes clinical ink`() {
        val root = document()
        val parts = root.getElementsByTagName("PartDraw")
        val cleanup = (0 until parts.length).map { parts.item(it) as Element }.singleOrNull { it.getAttribute("name") == "erase-baked-circles" }
        assertTrue("cleanup must not disappear with contours in ambient", cleanup != null)
        assertEquals("Scene", cleanup!!.parentNode.nodeName)
        assertEquals(3, cleanup.getElementsByTagName("Ellipse").length)
        assertEquals(0, cleanup.getElementsByTagName("Variant").length)
        assertTrue(cleanup.compareDocumentPosition((root.getElementsByTagName("Scene").item(0) as Element).getElementsByTagName("ComplicationSlot").item(0)).toInt() and 2 != 0)
        val sceneSlots = (root.getElementsByTagName("Scene").item(0) as Element).getElementsByTagName("ComplicationSlot")
        val middleLeft = (0 until sceneSlots.length).map { sceneSlots.item(it) as Element }.single { it.getAttribute("slotId") == "4" }
        assertTrue(cleanup.compareDocumentPosition(middleLeft).toInt() and 4 != 0)
    }

    @Test
    fun `reference decoration exposes native options without creating new complication slots`() {
        val root = document()
        val user = root.getElementsByTagName("UserConfigurations").item(0) as Element
        val ids = (0 until user.childNodes.length).mapNotNull { user.childNodes.item(it) as? Element }.map { it.getAttribute("id") }.toSet()
        assertTrue(ids.containsAll(setOf("dialTheme", "nightMode", "backgroundPattern", "secondStyle", "indexStyle", "minuteMarkers", "aodStyle", "showAmPm", "unreadIndicator")))
        assertEquals(8, (root.getElementsByTagName("Scene").item(0) as Element).getElementsByTagName("ComplicationSlot").length)
        val source = repoFile("watchfaces/sugarlicious-analog/src/main/res/raw/watchface.xml").readText()
        listOf("nightMode", "backgroundPattern", "secondStyle", "indexStyle", "minuteMarkers", "aodStyle", "showAmPm", "unreadIndicator").forEach { id ->
            val scene = source.substringAfter("<Scene")
            assertTrue("option $id must affect the scene", scene.contains("CONFIGURATION.$id") || scene.contains("<ListConfiguration id=\"$id\""))
        }
    }

    @Test
    fun `outer short text uses native provider images instead of unsupported inline resources`() {
        listOf("0", "1", "2", "3").forEach { id ->
            val variants = slot(id).getElementsByTagName("Complication")
            val short = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == "SHORT_TEXT" }
            assertEquals(0, short.getElementsByTagName("InlineImage").length)
            assertEquals("[COMPLICATION.MONOCHROMATIC_IMAGE]", (short.getElementsByTagName("Image").item(0) as Element).getAttribute("resource"))
        }
    }

    @Test
    fun `reference orange has distinct value title track and vivid end colors`() {
        val configs = document().getElementsByTagName("ColorConfiguration")
        listOf("materialAccent", "materialSubTheme").forEach { id ->
            val config = (0 until configs.length).map { configs.item(it) as Element }.single { it.getAttribute("id") == id }
            val options = config.getElementsByTagName("ColorOption")
            val orange = (0 until options.length).map { options.item(it) as Element }.singleOrNull { it.getAttribute("id") == "37" }
            assertTrue("measured reference palette missing", orange != null)
            assertEquals(
                "#FFFFCCB1 #FFFF935C #FFFF5F0D",
                orange!!
                    .getAttribute("colors")
                    .split(" ")
                    .take(3)
                    .joinToString(" "),
            )
        }
    }

    @Test
    fun `unit spacing uses a thin space instead of a full word space`() {
        val templates = slot("4").getElementsByTagName("Template")
        val units = (0 until templates.length).map { templates.item(it) as Element }.filter { it.getElementsByTagName("Parameter").length == 2 }
        assertTrue(units.isNotEmpty())
        units.forEach { assertTrue(it.textContent.startsWith("%s\u2009%s")) }
    }

    @Test
    fun `bottom icon grows while all three contours use the dark subtheme track`() {
        val images = slot("6").getElementsByTagName("PartImage")
        val icon = images.item(0) as Element
        near(32.0, icon, "width")
        near(32.0, icon, "height")
        near(114.0, icon, "y")
        val draws = document().getElementsByTagName("PartDraw")
        (0 until draws.length).map { draws.item(it) as Element }.filter { it.getAttribute("name").endsWith("-contour") }.forEach {
            assertEquals("55", it.getAttribute("alpha"))
            assertEquals("[CONFIGURATION.materialSubTheme.1]", (it.getElementsByTagName("Stroke").item(0) as Element).getAttribute("color"))
        }
    }

    @Test
    fun `marker endpoint is longer and lower left text grows away from progress`() {
        val lines = slot("1").getElementsByTagName("Line")
        val first = lines.item(0) as Element
        val last = lines.item(lines.length - 1) as Element

        fun length(line: Element): Double = kotlin.math.hypot(line.getAttribute("endX").toDouble() - line.getAttribute("startX").toDouble(), line.getAttribute("endY").toDouble() - line.getAttribute("startY").toDouble())

        assertTrue(length(last) > length(first) * 1.4)
        assertTrue((first.getElementsByTagName("Stroke").item(0) as Element).getAttribute("thickness").toFloat() >= 5f)
        val types = slot("3").getElementsByTagName("Complication")
        val range = (0 until types.length).map { types.item(it) as Element }.single { it.getAttribute("type") == "RANGED_VALUE" }
        val text = range.getElementsByTagName("TextCircular").item(0) as Element
        assertEquals("END", text.getAttribute("align"))
        near(248.0, text, "endAngle")
    }

    @Test
    fun `progress gradients and upper right markers are independent native options`() {
        val root = document()
        val options = root.getElementsByTagName("BooleanConfiguration")
        val ids = (0 until options.length).map { (options.item(it) as Element).getAttribute("id") }.toSet()
        assertTrue(ids.containsAll(setOf("progressGradient", "upperRightMarkers")))
        assertEquals(7, root.getElementsByTagName("SweepGradient").length)
        assertEquals(33, slot("1").getElementsByTagName("Line").length)
        (0 until root.getElementsByTagName("SweepGradient").length).forEach { index ->
            val gradient = root.getElementsByTagName("SweepGradient").item(index) as Element
            val tones = gradient.getAttribute("colors").split(" ")
            assertEquals("#00FFFFFF", tones.first())
            assertEquals("#FFFFFFFF", tones.last())
            assertEquals("gradient remains tied to the full scale, not the changing progress endpoint", 0, gradient.getElementsByTagName("Transform").length)
            assertTrue(gradient.getAttribute("endAngle").toDouble() > gradient.getAttribute("startAngle").toDouble())
            val draw = gradient.parentNode.parentNode.parentNode as Element
            assertTrue(draw.getAttribute("tintColor").startsWith("[CONFIGURATION.material"))
        }
    }

    @Test
    fun `progress gradients clamp round caps instead of wrapping vivid color onto the pale start`() {
        val gradients = document().getElementsByTagName("SweepGradient")
        (0 until gradients.length).forEach { index ->
            val gradient = gradients.item(index) as Element
            val stroke = gradient.parentNode as Element
            val arc = stroke.parentNode as Element
            val start = arc.getAttribute("startAngle").toDouble()
            val span = if (arc.getAttribute("width").toDouble() > 300) 48.0 else 280.0
            val radius = arc.getAttribute("width").toDouble() / 2
            val capAngle = Math.toDegrees(kotlin.math.asin((stroke.getAttribute("thickness").toDouble() / 2 + 1) / radius))
            val gradientStart = gradient.getAttribute("startAngle").toDouble()
            val gradientEnd = gradient.getAttribute("endAngle").toDouble()
            assertTrue("start cap crosses the shader seam", gradientStart < start - capAngle)
            assertTrue("full end cap crosses the shader seam", gradientEnd > start + span + capAngle)
            val colors = gradient.getAttribute("colors").split(" ")
            assertEquals(listOf("#00FFFFFF", "#00FFFFFF", "#FFFFFFFF", "#FFFFFFFF"), colors)
            val stops = gradient.getAttribute("positions").split(" ").map(String::toDouble)
            assertEquals(start, gradientStart + stops[1] * (gradientEnd - gradientStart), .02)
            assertEquals(start + span, gradientStart + stops[2] * (gradientEnd - gradientStart), .02)
        }
    }

    @Test
    fun `lower left range places icon toward bottom but short text keeps it on left`() {
        val variants = slot("3").getElementsByTagName("Complication")
        val ranged = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == "RANGED_VALUE" }
        val image = ranged.getElementsByTagName("PartImage").item(0) as Element
        assertTrue(image.getAttribute("y").toFloat() > 400)
        val text = ranged.getElementsByTagName("TextCircular").item(0) as Element
        assertTrue(text.getAttribute("startAngle").toFloat() > 250)
        val short = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == "SHORT_TEXT" }
        val shortIcon = short.getElementsByTagName("PartImage").item(0) as Element
        assertTrue(shortIcon.getAttribute("x").toFloat() < 100)
    }

    @Test
    fun `center contour strokes follow the selected theme`() {
        val root = document()
        val draws = root.getElementsByTagName("PartDraw")
        listOf("center-left-contour", "center-right-contour", "center-bottom-contour").forEach { name ->
            val draw = (0 until draws.length).map { draws.item(it) as Element }.singleOrNull { it.getAttribute("name") == name }
            assertTrue("missing themed contour $name", draw != null)
            val stroke = draw!!.getElementsByTagName("Stroke").item(0) as Element
            assertTrue(stroke.getAttribute("color").startsWith("[CONFIGURATION.material"))
        }
    }

    @Test
    fun `center fields style recognized therapy units without changing generic text`() {
        listOf("4", "5", "6").forEach { id ->
            val node = slot(id)
            val fonts = node.getElementsByTagName("Font")
            assertTrue((0 until fonts.length).any { (fonts.item(it) as Element).getElementsByTagName("Parameter").length == 2 })
            val parameters = node.getElementsByTagName("Parameter")
            val expressions = (0 until parameters.length).map { (parameters.item(it) as Element).getAttribute("expression") }
            assertTrue(expressions.any { it.contains("numberFormat(\"#.#\"") })
            assertTrue(expressions.contains("[COMPLICATION.TEXT]"))
            assertTrue(expressions.contains("[COMPLICATION.TITLE]"))
        }
    }

    @Test
    fun `editor outlines isolate quadrants and contain only the visible graph`() {
        val sectors = listOf(270 to 360, 0 to 90, 90 to 180, 180 to 270)
        sectors.forEachIndexed { id, (start, end) ->
            val arc = slot(id.toString()).getElementsByTagName("BoundingArc").item(0) as Element
            near(start.toDouble(), arc, "startAngle")
            near(end.toDouble(), arc, "endAngle")
        }
        val graph = slot("7").getElementsByTagName("BoundingBox").item(0) as Element
        near(91.0, graph, "x")
        near(91.0, graph, "y")
        near(330.0, graph, "width")
        near(97.0, graph, "height")
        val configs = slot("6").ownerDocument.getElementsByTagName("BooleanConfiguration")
        assertTrue((0 until configs.length).none { (configs.item(it) as Element).getAttribute("id") in setOf("bottomIcon", "centerLeftIcon", "centerRightIcon") })
    }

    @Test
    fun `picker has exactly eight slots and excludes unsupported standalone icons and long text`() {
        val nodes = (slot("0").ownerDocument.getElementsByTagName("Scene").item(0) as Element).getElementsByTagName("ComplicationSlot")
        assertEquals(8, nodes.length)
        assertEquals((0..7).map(Int::toString).toSet(), (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("slotId") }.toSet())
        (0..6).forEach { id ->
            assertEquals("RANGED_VALUE SHORT_TEXT EMPTY", slot(id.toString()).getAttribute("supportedTypes"))
        }
        assertEquals("SMALL_IMAGE SHORT_TEXT RANGED_VALUE EMPTY", slot("7").getAttribute("supportedTypes"))
    }

    @Test
    fun `center short text has no invisible inline icon spacing`() {
        listOf("4", "5", "6").forEach { id ->
            val variants = slot(id).getElementsByTagName("Complication")
            val short = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == "SHORT_TEXT" }
            assertEquals(0, short.getElementsByTagName("PartImage").length)
            assertEquals(0, short.getElementsByTagName("InlineImage").length)
            val parts = short.getElementsByTagName("PartText")
            (0 until parts.length).forEach { index ->
                val part = parts.item(index) as Element
                assertEquals(slot(id).getAttribute("width").toFloat() / 2, part.getAttribute("x").toFloat() + part.getAttribute("width").toFloat() / 2, .01f)
            }
        }
    }

    @Test
    fun `outer short text keeps provider image and centered text within the same slot`() {
        (0..3).forEach { id ->
            val variants = slot(id.toString()).getElementsByTagName("Complication")
            val short = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == "SHORT_TEXT" }
            assertEquals(1, short.getElementsByTagName("PartImage").length)
            val circular = short.getElementsByTagName("TextCircular").item(0) as Element
            assertEquals("CENTER", circular.getAttribute("align"))
            assertTrue(kotlin.math.abs(circular.getAttribute("endAngle").toFloat() - circular.getAttribute("startAngle").toFloat()) >= 70f)
        }
    }

    @Test
    fun `transparent hand pair keeps the new source black translucent center`() {
        listOf("hour", "minute").forEach { kind ->
            val image = javax.imageio.ImageIO.read(repoFile("watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi/${kind}_hand_transparent.png"))
            val pixel = image.getRGB(image.width / 2, image.height * 140 / 512)
            assertTrue("new $kind center has 70 percent black fill", pixel ushr 24 in 177..180)
            assertEquals(0, pixel and 0xFFFFFF)
        }
    }

    @Test
    fun `all monochromatic complication icons follow their selected theme`() {
        (0..6).forEach { id ->
            val images = slot(id.toString()).getElementsByTagName("PartImage")
            (0 until images.length)
                .map { images.item(it) as Element }
                .filter {
                    it
                        .getElementsByTagName("Image")
                        .item(0)
                        ?.attributes
                        ?.getNamedItem("resource")
                        ?.nodeValue == "[COMPLICATION.MONOCHROMATIC_IMAGE]"
                }.forEach { assertTrue("slot $id icon needs palette tint", it.getAttribute("tintColor").startsWith("[CONFIGURATION.")) }
        }
    }

    @Test
    fun `complication text is larger than WFS baseline and consistently bold`() {
        val source =
            kotlinx.serialization.json.Json
                .parseToJsonElement(repoFile("watchfaces/sugarlicious-analog/reference/wfs-geometry.json").readText())
                .jsonObject
        val slots = source.getValue("slots").jsonArray
        (0..6).forEach { id ->
            val sourceSlot = slots[id].jsonObject
            val sourceText =
                sourceSlot
                    .getValue("children")
                    .jsonArray
                    .map { it.jsonObject }
                    .first { it.getValue("field").jsonPrimitive.content == "shortText" }
            val valueSize = kotlin.math.round(sourceText.getValue("fontSize").jsonPrimitive.float * 512 / 450).toInt()
            val fonts = slot(id.toString()).getElementsByTagName("Font")
            (0 until fonts.length).forEach {
                val font = fonts.item(it) as Element
                val isTitle =
                    font
                        .getElementsByTagName("Parameter")
                        .item(0)
                        ?.attributes
                        ?.getNamedItem("expression")
                        ?.nodeValue
                        ?.contains("[COMPLICATION.TITLE]") == true
                val expected =
                    if (isTitle) {
                        if (id == 4) 27 else 25
                    } else {
                        valueSize
                    }
                val owner = generateSequence(font.parentNode) { it.parentNode }.filterIsInstance<Element>().first { it.tagName == "Complication" }
                val ranged = owner.getAttribute("type") == "RANGED_VALUE"
                if (font.getAttribute("size") == "24") {
                    assertTrue(font.getAttribute("size").toInt() < 31)
                } else if (id < 4 && ranged) {
                    assertEquals(if (id == 0) 28 else 30, font.getAttribute("size").toInt())
                } else {
                    assertTrue("slot $id title=$isTitle must be enlarged", font.getAttribute("size").toInt() > expected)
                }
                assertEquals("slot $id must be bold", "BOLD", font.getAttribute("weight"))
            }
        }
    }

    @Test
    fun `titles and values use different tones even with identical theme selections`() {
        listOf("4", "5", "6").forEach { id ->
            val fonts = slot(id).getElementsByTagName("Font")
            (0 until fonts.length).forEach {
                val font = fonts.item(it) as Element
                val parameter = font.getElementsByTagName("Parameter").item(0) as Element
                val title = parameter.getAttribute("expression").contains("[COMPLICATION.TITLE]")
                if (font.getAttribute("color") == "[COMPLICATION.RANGED_VALUE_COLORS]") {
                    assertTrue("provider color must not affect the title", !title)
                } else if (font.getAttribute("size") != "24") {
                    assertTrue("slot $id needs distinct semantic palette tones", font.getAttribute("color").endsWith(if (title) ".1]" else ".0]"))
                }
            }
        }
    }

    @Test
    fun `theme palettes are actually bound to text and progress colors`() {
        val root = document()
        val configs = root.getElementsByTagName("ColorConfiguration")
        listOf("materialAccent", "materialSubTheme").forEach { id ->
            val config = (0 until configs.length).map { configs.item(it) as Element }.single { it.getAttribute("id") == id }
            val options = config.getElementsByTagName("ColorOption")
            assertTrue(options.length >= 37)
            (0 until options.length).forEach {
                assertEquals(if (id == "materialAccent") 4 else 3, (options.item(it) as Element).getAttribute("colors").split(" ").size)
            }
            val fonts = root.getElementsByTagName("Font")
            assertTrue((0 until fonts.length).any { (fonts.item(it) as Element).getAttribute("color") == "[CONFIGURATION.$id.0]" })
            val strokes = root.getElementsByTagName("Stroke")
            assertTrue((0 until strokes.length).any { (strokes.item(it) as Element).getAttribute("color") == "[CONFIGURATION.$id.1]" })
            val draws = root.getElementsByTagName("PartDraw")
            assertTrue((0 until draws.length).any { (draws.item(it) as Element).getAttribute("tintColor") == "[CONFIGURATION.$id.2]" })
        }
    }

    @Test
    fun `neutral graph fills the same upper inner semicircle as Orbit`() {
        val variants = slot("7").getElementsByTagName("Complication")
        val image =
            (0 until variants.length)
                .map { variants.item(it) as Element }
                .single { it.getAttribute("type") == "SMALL_IMAGE" }
                .getElementsByTagName("PartImage")
                .item(0) as Element
        val left = image.getAttribute("x").toInt()
        val top = image.getAttribute("y").toInt()
        val width = image.getAttribute("width").toInt()
        val height = image.getAttribute("height").toInt()
        assertTrue("graph must be larger than the previous 228x80 chart", width * height > 22_000)
        assertEquals("cap ends above middle complications", 188, top + height)
        assertTrue("cap leaves clearance above both middle circles", top + height < SugarliciousAnalogGeometry.middleLeft.y && top + height < SugarliciousAnalogGeometry.middleRight.y)
        val frame = image.parentNode.parentNode as Element
        assertEquals("no rectangular pill frame", 0, frame.getElementsByTagName("RoundRectangle").length)
        assertEquals("curved cap height", 97, height)
        assertEquals(left.toFloat(), SugarliciousAnalogGeometry.graphContent.x)
        assertEquals(top.toFloat(), SugarliciousAnalogGeometry.graphContent.y)
        assertEquals(width.toFloat(), SugarliciousAnalogGeometry.graphContent.width)
        assertEquals(height.toFloat(), SugarliciousAnalogGeometry.graphContent.height)
    }

    @Test
    fun `all outer slots offer ranged arc text and icon including lower left`() {
        listOf("0", "1", "2", "3").forEach { id ->
            val variants = slot(id).getElementsByTagName("Complication")
            listOf("SHORT_TEXT", "RANGED_VALUE").forEach { type ->
                val variant = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == type }
                assertTrue("slot $id $type needs an icon", variant.getElementsByTagName("PartImage").length + variant.getElementsByTagName("InlineImage").length > 0)
                assertEquals("slot $id $type needs exclusive formatted, generic and optional glucose-color paths", if (type == "RANGED_VALUE") 3 else 2, variant.getElementsByTagName("TextCircular").length)
                if (type == "RANGED_VALUE") assertEquals("slot $id needs track, remaining gap arc, opaque gradient base, color overlay and exclusive solid style", 5, variant.getElementsByTagName("Arc").length)
            }
        }
    }

    @Test
    fun `center ranged slots contain curved progress title value and open segment icon`() {
        listOf("4", "5", "6").forEach { id ->
            val variants = slot(id).getElementsByTagName("Complication")
            val ranged = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == "RANGED_VALUE" }
            assertEquals("slot $id needs circular rather than linear progress with a separate remainder", 5, ranged.getElementsByTagName("Arc").length)
            assertEquals(0, ranged.getElementsByTagName("Line").length)
            val parameters = ranged.getElementsByTagName("Parameter")
            assertTrue((0 until parameters.length).any { (parameters.item(it) as Element).getAttribute("expression") == "[COMPLICATION.TITLE]" })
            val fonts = ranged.getElementsByTagName("Font")
            (0 until fonts.length).forEach {
                val font = fonts.item(it) as Element
                assertTrue(font.getAttribute("size").toInt() >= 22)
            }
            if (id == "6") {
                val image = ranged.getElementsByTagName("PartImage").item(0) as Element
                assertTrue("icon below text in open segment", image.getAttribute("y").toFloat() > slot(id).getAttribute("height").toFloat() * .75f)
            } else {
                assertEquals(0, ranged.getElementsByTagName("PartImage").length)
            }
        }
    }

    @Test
    fun `source typography and provider bindings remain intact`() {
        listOf("4", "5").forEach { id ->
            val variants = slot(id).getElementsByTagName("Complication")
            val short = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == "SHORT_TEXT" }
            val fonts = short.getElementsByTagName("Font")
            assertEquals("BOLD", (fonts.item(0) as Element).getAttribute("weight"))
            assertEquals("BOLD", (fonts.item(1) as Element).getAttribute("weight"))
            val title =
                (0 until fonts.length).map { fonts.item(it) as Element }.single { font ->
                    (font.getElementsByTagName("Parameter").item(0) as Element).getAttribute("expression") == "[COMPLICATION.TITLE]"
                }
            assertEquals("[CONFIGURATION.materialSubTheme.1]", title.getAttribute("color"))
        }
        val graphPolicy = slot("7").getElementsByTagName("DefaultProviderPolicy").item(0) as Element
        assertEquals("app.sugarlicious/app.aapswear.complications.GlucoseGraphComplication", graphPolicy.getAttribute("primaryProvider"))
        assertEquals("SMALL_IMAGE", graphPolicy.getAttribute("primaryProviderType"))
        val bottomPolicy = slot("6").getElementsByTagName("DefaultProviderPolicy").item(0) as Element
        assertEquals("app.sugarlicious/app.aapswear.complications.GlucoseTrendRangedValueComplication", bottomPolicy.getAttribute("primaryProvider"))
    }

    @Test
    fun `all hand resources and AOD behavior remain packaged`() {
        val root = document()
        listOf("HourHand", "MinuteHand", "SecondHand").forEach { tag ->
            val nodes = root.getElementsByTagName(tag)
            assertEquals(if (tag == "SecondHand") 6 else 3, nodes.length)
            (0 until nodes.length).forEach { index ->
                val hand = nodes.item(index) as Element
                val resource = hand.getAttribute("resource")
                assertTrue(repoFile("watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi/$resource.png").isFile)
                if (tag == "SecondHand") {
                    val ambient = hand.getElementsByTagName("Variant").item(0) as Element
                    assertEquals("AMBIENT", ambient.getAttribute("mode"))
                    assertEquals("alpha", ambient.getAttribute("target"))
                    assertEquals("0", ambient.getAttribute("value"))
                }
            }
        }
    }

    @Test
    fun `original dial template keeps transparent graph cutout`() {
        val image = javax.imageio.ImageIO.read(repoFile("design-assets/watchface-templates/apex-original-dial.png"))
        assertEquals(450, image.width)
        assertEquals(450, image.height)
        assertEquals(0, image.getRGB(225, 100) ushr 24)
        assertEquals(255, image.getRGB(225, 225) ushr 24)
        assertEquals(0x4C4C4C, image.getRGB(291, 26) and 0xFFFFFF)
    }

    @Test
    fun `outer progress uses source outside diameter minus stroke`() {
        listOf("0" to 285.0, "1" to 15.0, "2" to 103.0, "3" to 195.0).forEach { (id, start) ->
            val arcs = slot(id).getElementsByTagName("Arc")
            assertEquals(5, arcs.length)
            (0 until arcs.length).forEach { index ->
                val arc = arcs.item(index) as Element
                near(391.3956, arc, "width")
                near(if (arc.getAttribute("direction") == "COUNTER_CLOCKWISE") start + 48.0 else start, arc, "startAngle")
                near(17.0667, arc.getElementsByTagName("Stroke").item(0) as Element, "thickness")
            }
            val fillArc = (0 until arcs.length).map { arcs.item(it) as Element }.first { it.getAttribute("direction") == "CLOCKWISE" && it.getElementsByTagName("Transform").length > 0 }
            val transform = fillArc.getElementsByTagName("Transform").item(0) as Element
            assertTrue(transform.getAttribute("value").contains("* 48"))
            assertTrue(transform.getAttribute("value").contains("=="))
        }
    }

    @Test
    fun `outer images retain original cardinal anchors not inferred arc midpoints`() {
        listOf(50.4605 to 220.9077, 267.6812 to 46.5433, 439.8649 to 258.9285)
            .forEachIndexed { index, (x, y) ->
                val images = slot(index.toString()).getElementsByTagName("PartImage")
                (0 until images.length).forEach { n ->
                    val image = images.item(n) as Element
                    near(x, image, "x")
                    near(y, image, "y")
                    near(25.0311, image, "width")
                    near(25.0311, image, "height")
                }
            }
    }

    @Test
    fun `curved text paths are independent of progress sweeps`() {
        listOf(338.0, 67.0, 178.0, 270.0).forEachIndexed { index, start ->
            val variants = slot(index.toString()).getElementsByTagName("Complication")
            val ranged = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == "RANGED_VALUE" }
            val texts = ranged.getElementsByTagName("TextCircular")
            (0 until texts.length).forEach { n ->
                val text = texts.item(n) as Element
                near(start, text, "startAngle")
                near(if (index == 3) 391.3956 else 390.2578, text, "width")
                assertEquals(if (index < 2) "CLOCKWISE" else "COUNTER_CLOCKWISE", text.getAttribute("direction"))
                assertEquals(if (index >= 2) "END" else "START", text.getAttribute("align"))
            }
        }
    }

    @Test
    fun `middle editor outlines follow the circular complication areas`() {
        listOf("4", "5", "6").forEach { id ->
            assertEquals(1, slot(id).getElementsByTagName("BoundingOval").length)
            assertEquals(0, slot(id).getElementsByTagName("BoundingBox").length)
        }
        val value = slot("4").getElementsByTagName("PartText").item(0) as Element
        near(8.0, value, "x")
        near(60.0, value, "y")
        near(107.0, value, "width")
        near(38.0, value, "height")
    }

    @Test
    fun `bottom ring retains WFS center stroke and open segment icon`() {
        val arcs = slot("6").getElementsByTagName("Arc")
        assertEquals(5, arcs.length)
        (0 until arcs.length).forEach { index ->
            val arc = arcs.item(index) as Element
            near(75.0, arc, "centerX")
            near(76.2622, arc, "centerY")
            near(125.1556, arc, "width")
            near(if (arc.getAttribute("direction") == "COUNTER_CLOCKWISE") 500.0 else 220.0, arc, "startAngle")
            near(11.3778, arc.getElementsByTagName("Stroke").item(0) as Element, "thickness")
        }
        val variants = slot("6").getElementsByTagName("Complication")
        val ranged = (0 until variants.length).map { variants.item(it) as Element }.single { it.getAttribute("type") == "RANGED_VALUE" }
        val image = ranged.getElementsByTagName("PartImage").item(0) as Element
        near(59.0, image, "x")
        near(114.0, image, "y")
        near(32.0, image, "width")
        near(32.0, image, "height")
    }

    @Test
    fun `three hand packages retain stable style IDs`() {
        val nodes = document().getElementsByTagName("AnalogClock")
        val clocks = (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getElementsByTagName("HourHand").length > 0 }
        assertEquals(3, clocks.size)
        listOf("hour_hand", "hour_hand_transparent", "hour_hand_tblack").forEachIndexed { index, resource ->
            val clock = clocks[index]
            assertEquals(resource, (clock.getElementsByTagName("HourHand").item(0) as Element).getAttribute("resource"))
            val visibility = (clock.parentNode as Element).getElementsByTagName("Transform").item(0) as Element
            assertEquals("[CONFIGURATION.handStyle] == \"$index\" ? 255 : 0", visibility.getAttribute("value"))
        }
    }

    @Test
    fun `mobile reuses native template without a duplicate hand renderer`() {
        assertEquals(391f, SugarliciousAnalogGeometry.outerProgressDiameter)
        assertEquals(390f, SugarliciousAnalogGeometry.outerTextDiameter)
        assertEquals(125f, SugarliciousAnalogGeometry.bottomArcDiameter)
        val source = repoFile("app-mobile/src/main/kotlin/app/aapswear/mobile/SugarliciousAnalogFacePreview.kt").readText()
        assertTrue(source.contains("R.drawable.apex_template_preview"))
        assertTrue(source.contains("ContentScale.Fit"))
        assertFalse(source.contains("AnalogPreviewHand("))
        val preview = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(repoFile("watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi/preview.xml"))
        assertEquals("bitmap", preview.documentElement.tagName)
        assertEquals("@drawable/apex_template_preview", preview.documentElement.getAttribute("android:src"))
    }

    private fun near(expected: Double, element: Element, attribute: String) =
        assertEquals("${element.tagName} $attribute", expected, element.getAttribute(attribute).toDouble(), 0.51)

    private fun document() =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(repoFile("watchfaces/sugarlicious-analog/src/main/res/raw/watchface.xml"))

    private fun slot(id: String): Element {
        val nodes = (document().getElementsByTagName("Scene").item(0) as Element).getElementsByTagName("ComplicationSlot")
        return (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttribute("slotId") == id }
    }

    private fun repoFile(path: String): File {
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(File(cwd, path), File(cwd.parentFile, path)).firstOrNull(File::isFile)
            ?: error("Repository file not found: $path")
    }
}
