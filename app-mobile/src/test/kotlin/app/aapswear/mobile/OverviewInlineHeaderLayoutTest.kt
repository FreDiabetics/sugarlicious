package app.aapswear.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewInlineHeaderLayoutTest {
    @Test
    fun `overview icon and wordmark move left as one unit`() {
        assertEquals(2, OverviewHeaderLayout.START_PADDING_DP)
        assertEquals(0, OverviewHeaderLayout.LOGO_X_OFFSET_DP)
        assertTrue(OverviewHeaderLayout.LOGO_SLOT_WIDTH_DP >= 36)
        assertTrue(
            OverviewHeaderLayout.START_PADDING_DP +
                OverviewHeaderLayout.LOGO_SLOT_WIDTH_DP >= 40,
        )
    }
}
