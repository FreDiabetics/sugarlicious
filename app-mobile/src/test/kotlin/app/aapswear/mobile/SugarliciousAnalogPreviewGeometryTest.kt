package app.aapswear.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SugarliciousAnalogPreviewGeometryTest {
    @Test
    fun `preview geometry matches final analog WFF`() {
        val xml = watchfaceFile().readText()

        assertTrue(xml.contains("slotId=\"7\"") && xml.contains("x=\"89\" y=\"77\" width=\"335\" height=\"117\""))
        assertTrue(xml.contains("startAngle=\"250\" endAngle=\"336\" direction=\"CLOCKWISE\""))
        assertTrue(xml.contains("startAngle=\"8\" endAngle=\"67\" direction=\"CLOCKWISE\""))
        assertTrue(xml.contains("startAngle=\"96\" endAngle=\"158\" direction=\"CLOCKWISE\""))
        assertTrue(xml.contains("startAngle=\"266\" endAngle=\"190\" direction=\"COUNTER_CLOCKWISE\""))
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

        assertTrue(SugarliciousAnalogGeometry.graph == AnalogRectGeometry(89f, 77f, 335f, 117f))
        assertTrue(SugarliciousAnalogGeometry.middleLeft == AnalogRectGeometry(82f, 193f, 125f, 125f))
        assertTrue(SugarliciousAnalogGeometry.middleRight == AnalogRectGeometry(305f, 193f, 125f, 125f))
        assertTrue(SugarliciousAnalogGeometry.bottomCenter == AnalogRectGeometry(182f, 283f, 148f, 148f))
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
