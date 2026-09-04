package app.aapswear.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlucoseWidgetContractTest {
    @Test
    fun `glucose widget content stays value and trend only`() {
        val source = File("src/main/kotlin/app/aapswear/mobile/SugarliciousWidgets.kt").readText()
        val start = source.indexOf("private fun GlucoseWidgetContent")
        val end = source.indexOf("private fun GraphWidgetContent", start)
        assertTrue(start >= 0)
        assertTrue(end > start)
        val body = source.substring(start, end)

        assertTrue(body.contains("renderMinimalGlucoseWidget"))
        assertFalse(body.contains("signedDelta"))
        assertFalse(body.contains("widgetAge("))
        assertFalse(body.contains("widgetFreshnessStatus"))
        assertFalse(body.contains("mg/dL"))
        assertFalse(body.contains("mmol/L"))
    }
}
