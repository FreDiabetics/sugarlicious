package app.aapswear.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearIconsTest {
    @Test
    fun `only colored icons on light Wear surfaces receive a black silhouette`() {
        assertTrue(shouldOutlineWearIcon(0xFFF4F6F8.toInt(), colored = true))
        assertFalse(shouldOutlineWearIcon(0xFF101216.toInt(), colored = true))
        assertFalse(shouldOutlineWearIcon(0xFFF4F6F8.toInt(), colored = false))
    }
}
