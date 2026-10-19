package app.aapswear.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewInlineHeaderLayoutTest {
    @Test
    fun `wordmark reserves the former watch area instead of lifting the lower overview`() {
        val source = java.io.File("src/main/kotlin/app/aapswear/mobile/OverviewInlineHeader.kt").readText()
        assertTrue(source.contains("overviewWatchReplacementHeightDp("))
        assertEquals(293f, overviewWatchReplacementHeightDp(22f, 5), .001f)
        assertEquals(288f, overviewWatchReplacementHeightDp(10f, 8), .001f)
    }

    @Test
    fun `compact glucose age uses the shared two minute form`() {
        assertEquals(
            "2m",
            app.aapswear.model.TherapyDisplayFormatter
                .ageMinutes(0L, 120_000L),
        )
    }

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
