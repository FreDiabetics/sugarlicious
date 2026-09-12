package app.aapswear.mobile

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SugarliciousAnalogPreviewGeometryTest {
    @Test
    fun `preview geometry matches final analog WFF`() {
        val xml = watchfaceFile().readText()

        assertTrue(xml.contains("slotId=\"7\"") && xml.contains("x=\"92\" y=\"68\" width=\"328\" height=\"140\""))
        assertTrue(xml.contains("<PartImage x=\"8\" y=\"8\" width=\"312\" height=\"124\">"))
        assertTrue(xml.contains("startAngle=\"285\" endAngle=\"333\" direction=\"CLOCKWISE\""))
        assertTrue(xml.contains("startAngle=\"15\" endAngle=\"63\" direction=\"CLOCKWISE\""))
        assertTrue(xml.contains("startAngle=\"103\" endAngle=\"151\" direction=\"CLOCKWISE\""))
        assertTrue(xml.contains("startAngle=\"253\" endAngle=\"205\" direction=\"COUNTER_CLOCKWISE\""))
        assertTrue(xml.contains("slotId=\"4\"") && xml.contains("x=\"62\" y=\"204\" width=\"132\" height=\"110\""))
        assertTrue(xml.contains("slotId=\"5\"") && xml.contains("x=\"318\" y=\"204\" width=\"132\" height=\"110\""))
        assertTrue(xml.contains("slotId=\"6\"") && xml.contains("x=\"146\" y=\"312\" width=\"220\" height=\"116\""))
        assertTrue(xml.contains("<PartText x=\"8\" y=\"64\" width=\"112\" height=\"32\">"))
        assertTrue(xml.contains("<PartText x=\"8\" y=\"28\" width=\"107\" height=\"31\">"))
        assertTrue(xml.contains("<PartText x=\"8\" y=\"64\" width=\"107\" height=\"32\">"))
        assertTrue(xml.contains("<PartText x=\"8\" y=\"28\" width=\"107\" height=\"30\">"))
        assertTrue(xml.contains("<Line startX=\"0\" startY=\"2\" endX=\"132\" endY=\"2\">"))
        assertTrue(xml.contains("<PartText x=\"18\" y=\"22\" width=\"184\" height=\"64\">"))
        assertTrue(xml.contains("resource=\"sugarlicious_analog_template\""))
        assertTrue(xml.contains("resource=\"hour_hand_transparent\""))
        assertTrue(xml.contains("resource=\"minute_hand_transparent\""))
        assertTrue(xml.contains("resource=\"second_hand_transparent\""))
        assertTrue(xml.contains("resource=\"hour_hand_tblack\""))
        assertTrue(xml.contains("resource=\"minute_hand_tblack\""))
        assertTrue(xml.contains("resource=\"second_hand_tblack\""))
        assertFalse(xml.contains("id=\"3\" displayName=\"hand_style_"))

        assertTrue(SugarliciousAnalogGeometry.graph == AnalogRectGeometry(92f, 68f, 328f, 140f))
        assertTrue(SugarliciousAnalogGeometry.graphContent == AnalogRectGeometry(100f, 76f, 312f, 124f))
        assertTrue(SugarliciousAnalogGeometry.middleLeft == AnalogRectGeometry(62f, 204f, 132f, 110f))
        assertTrue(SugarliciousAnalogGeometry.middleRight == AnalogRectGeometry(318f, 204f, 132f, 110f))
        assertTrue(SugarliciousAnalogGeometry.bottomCenter == AnalogRectGeometry(146f, 312f, 220f, 116f))
        assertTrue(SugarliciousAnalogGeometry.bottomText == AnalogRectGeometry(164f, 334f, 184f, 64f))
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
        assertTrue(kotlin.math.abs((geometry.graphContent.width / geometry.graphContent.height) - (312f / 124f)) < 0.01f)
        assertTrue(geometry.handPivot == geometry.center)
        assertTrue(geometry.outerProgressDiameter < geometry.outerTextDiameter)
    }

    @Test fun `runtime graph uses direct rectangular placement without skew transforms`() {
        val xml = watchfaceFile().readText()
        assertFalse(xml.contains("scaleX"))
        assertFalse(xml.contains("scaleY"))
        assertFalse(xml.contains("skew"))
        assertTrue(xml.contains("<PartImage x=\"8\" y=\"8\" width=\"312\" height=\"124\">"))
    }

    @Test fun `generic slot types have geometry specific renderers`() {
        val xml = watchfaceFile().readText()
        assertTrue(xml.contains("supportedTypes=\"LONG_TEXT RANGED_VALUE SMALL_IMAGE EMPTY\""))
        assertTrue(xml.contains("MONOCHROMATIC_IMAGE SMALL_IMAGE EMPTY"))
        assertTrue(xml.contains("<Complication type=\"MONOCHROMATIC_IMAGE\">"))
        assertTrue(xml.contains("<Complication type=\"SMALL_IMAGE\">"))
        assertTrue(xml.contains("target=\"endX\""))
    }

    @Test
    fun `system preview uses final template and overlay not stale target`() {
        val preview = previewFile().readText()
        assertTrue(preview.contains("@drawable/sugarlicious_analog_template"))
        assertTrue(preview.contains("@drawable/sugarlicious_analog_preview_overlay"))
        assertFalse(preview.contains("sugarlicious_analog_preview_target"))
    }

    @Test
    fun `legacy source artwork stays native while responsive dial uses WFF canvas`() {
        listOf(
            "indices_hours.png",
            "indices_dots.png",
            "graph_mask.png",
        ).forEach { name ->
            val image = requireNotNull(
                ImageIO.read(repoFile("watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi/$name")),
            ) { "$name must be a readable PNG" }
            assertTrue("$name must be 450 px wide", image.width == 450)
            assertTrue("$name must be 450 px high", image.height == 450)
        }
        val dial = requireNotNull(
            ImageIO.read(repoFile("watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi/sugarlicious_analog_template.png")),
        )
        assertTrue("ApeX dial must match the 512 WFF design canvas", dial.width == 512 && dial.height == 512)
    }

    private fun watchfaceFile(): File = repoFile(
        "watchfaces/sugarlicious-analog/src/main/res/raw/watchface.xml",
    )

    private fun previewFile(): File = repoFile(
        "watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi/preview.xml",
    )

    private fun repoFile(path: String): File {
        val cwd = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(File(cwd, path), File(cwd.parentFile, path))
        return candidates.firstOrNull(File::isFile)
            ?: error("Repository file not found: $path from ${cwd.absolutePath}")
    }
}
