package app.aapswear.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SugarliciousAnalogPreviewGeometryTest {
    @Test
    fun `preview geometry matches final analog WFF`() {
        val xml = watchfaceFile().readText()

        assertTrue(xml.contains("slotId=\"7\"") && xml.contains("x=\"96\" y=\"78\" width=\"320\" height=\"112\""))
        assertTrue(xml.contains("startAngle=\"284\" endAngle=\"342\" direction=\"CLOCKWISE\""))
        assertTrue(xml.contains("startAngle=\"18\" endAngle=\"76\" direction=\"CLOCKWISE\""))
        assertTrue(xml.contains("startAngle=\"104\" endAngle=\"162\" direction=\"CLOCKWISE\""))
        assertTrue(xml.contains("startAngle=\"256\" endAngle=\"198\" direction=\"COUNTER_CLOCKWISE\""))
        assertTrue(xml.contains("slotId=\"4\"") && xml.contains("x=\"82\" y=\"193\" width=\"125\" height=\"125\""))
        assertTrue(xml.contains("slotId=\"5\"") && xml.contains("x=\"305\" y=\"193\" width=\"125\" height=\"125\""))
        assertTrue(xml.contains("slotId=\"6\"") && xml.contains("x=\"182\" y=\"283\" width=\"148\" height=\"148\""))
        assertTrue(xml.contains("resource=\"sugarlicious_analog_template\""))
        assertTrue(xml.contains("resource=\"hour_hand_transparent\""))
        assertTrue(xml.contains("resource=\"minute_hand_transparent\""))
        assertTrue(xml.contains("resource=\"second_hand_transparent\""))
        assertTrue(xml.contains("resource=\"hour_hand_tblack\""))
        assertTrue(xml.contains("resource=\"minute_hand_tblack\""))
        assertTrue(xml.contains("resource=\"second_hand_tblack\""))
        assertFalse(xml.contains("id=\"3\" displayName=\"hand_style_"))

        assertTrue(SugarliciousAnalogGeometry.graph == AnalogRectGeometry(96f, 78f, 320f, 112f))
        assertTrue(SugarliciousAnalogGeometry.middleLeft == AnalogRectGeometry(82f, 193f, 125f, 125f))
        assertTrue(SugarliciousAnalogGeometry.middleRight == AnalogRectGeometry(305f, 193f, 125f, 125f))
        assertTrue(SugarliciousAnalogGeometry.bottomCenter == AnalogRectGeometry(182f, 283f, 148f, 148f))
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
        assertTrue((geometry.outerDiameter / 2f) + geometry.outerStroke / 2f <= geometry.safeRadius)
        assertTrue(kotlin.math.abs((geometry.graph.width / geometry.graph.height) - (400f / 140f)) < 0.001f)
    }

    @Test fun `runtime graph uses direct rectangular placement without skew transforms`() {
        val xml = watchfaceFile().readText()
        assertFalse(xml.contains("scaleX"))
        assertFalse(xml.contains("scaleY"))
        assertFalse(xml.contains("skew"))
        assertTrue(xml.contains("<PartImage x=\"0\" y=\"0\" width=\"320\" height=\"112\">"))
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
