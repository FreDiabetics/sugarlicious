package app.aapswear.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class OverviewInlineHeaderLayoutTest {
    @Test
    fun `compact glucose age uses the shared two minute form`() {
        assertEquals(
            "2m",
            app.aapswear.model.TherapyDisplayFormatter
                .ageMinutes(0L, 120_000L),
        )
    }
}
