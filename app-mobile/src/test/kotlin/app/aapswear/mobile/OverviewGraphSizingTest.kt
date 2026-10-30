package app.aapswear.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class OverviewGraphSizingTest {
    @Test fun `three cards fit measured viewport with unchanged card gaps`() {
        val height = overviewFittedCgmHeight(400f, 800f, 280f, listOf(180f, null, 150f))
        assertEquals(160f, height)
        assertEquals(800f, 280f + 6f + 24f + 180f + height + 150f)
    }

    @Test fun `fourth card scrolls without shrinking graph again`() {
        val firstThree = listOf(180f, null, 150f)
        assertEquals(
            overviewFittedCgmHeight(400f, 800f, 280f, firstThree),
            overviewFittedCgmHeight(400f, 800f, 280f, firstThree + 130f),
        )
    }

    @Test fun `two cards recover space and different third cards use their actual heights`() {
        assertEquals(322f, overviewFittedCgmHeight(400f, 800f, 280f, listOf(180f, null)))
        assertEquals(190f, overviewFittedCgmHeight(400f, 800f, 280f, listOf(180f, null, 120f)))
        assertEquals(96f, overviewFittedCgmHeight(400f, 500f, 280f, listOf(180f, null, 150f)))
    }
}
