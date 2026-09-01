package app.aapswear.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SugarliciousAnalogPreviewGeometryTest {
    @Test
    fun `preview geometry matches final analog WFF`() {
        val xml = watchfaceFile().readText()

        assertTrue(xml.contains("slotId=\"7\"") && xml.contains("x=\"59\" y=\"63\" width=\"394\" height=\"138\""))
        assertTrue(xml.contains("startAngle=\"285\" endAngle=\"333\" direction=\"CLOCKWISE\""))
        assertTrue(xml.contains("startAngle=\"15\" endAngle=\"63\" direction=\"CLOCKWISE\""))
        assertTrue(xml.contains("startAngle=\"103\" endAngle=\"151\" direction=\"CLOCKWISE\""))
        assertTrue(xml.contains("startAngle=\"253\" endAngle=\"205\" direction=\"COUNTER_CLOCKWISE\""))
        assertTrue(xml.contains("slotId=\"4\"") && xml.contains("x=\"83\" y=\"195\" width=\"123\" height=\"123\""))
        assertTrue(xml.contains("slotId=\"5\"") && xml.contains("x=\"306\" y=\"195\" width=\"123\" height=\"123\""))
        assertTrue(xml.contains("slotId=\"6\"") && xml.contains("x=\"181\" y=\"281\" width=\"150\" height=\"149\""))
        assertTrue(xml.contains("resource=\"sugarlicious_analog_template\""))
        assertTrue(xml.contains("resource=\"hour_hand_transparent\""))
        assertTrue(xml.contains("resource=\"minute_hand_transparent\""))
        assertTrue(xml.contains("resource=\"second_hand_transparent\""))
        assertTrue(xml.contains("resource=\"hour_hand_tblack\""))
        assertTrue(xml.contains("resource=\"minute_hand_tblack\""))
        assertTrue(xml.contains("resource=\"second_hand_tblack\""))
        assertFalse(xml.contains("id=\"3\" displayName=\"hand_style_"))

        assertTrue(SugarliciousAnalogGeometry.graph == AnalogRectGeometry(59f, 63f, 394f, 138f))
        assertTrue(SugarliciousAnalogGeometry.middleLeft == AnalogRectGeometry(83f, 195f, 123f, 123f))
        assertTrue(SugarliciousAnalogGeometry.middleRight == AnalogRectGeometry(306f, 195f, 123f, 123f))
        assertTrue(SugarliciousAnalogGeometry.bottomCenter == AnalogRectGeometry(181f, 281f, 150f, 149f))
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

    @Test fun `runtime graph uses direct rectangular placement without skew transforms`() {
        val xml = watchfaceFile().readText()
        assertFalse(xml.contains("scaleX"))
        assertFalse(xml.contains("scaleY"))
        assertFalse(xml.contains("skew"))
        assertTrue(xml.contains("<PartImage x=\"0\" y=\"0\" width=\"394\" height=\"138\">"))
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

    private fun watchfaceFile(): File = repoFile(
        "watchfaces/sugarlicious-analog/src/main/res/raw/watchface.xml",
    )

    private fun previewFile(): File = repoFile(
        "watchfaces/sugarlicious-analog/src/main/res/drawable-nodpi/preview.xml",
    )

    private fun repoFile(path: String): File {
        val cwd = File(System.getProperty("user.dir"))
        val candidates = listOf(File(cwd, path), File(cwd.parentFile, path))
        return candidates.firstOrNull(File::isFile)
            ?: error("Repository file not found: $path from ${cwd.absolutePath}")
    }
}
