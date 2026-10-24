package app.aapswear.mobile

import org.junit.Assert.*
import org.junit.Test

class MobileSettingsScrollHeaderTest {
    @Test fun `short page stops with compact title above first card`() {
        val padding = settingsBottomScrollPaddingDp(600f, 100f, naturalContentHeightDp = 350f, expandedTitleHeightDp = 250f)
        val maximumScroll = 350f + padding - 600f
        assertEquals(194f, maximumScroll, .01f)
        val state = settingsScrollHeaderState(maximumScroll, 250f, 250f)
        assertEquals(1f, state.titleAlpha, 0f)
        assertEquals(20f / 34f, state.titleScale, .001f)
        assertEquals(0f, state.backCircleAlpha, 0f)
    }

    @Test fun `long page still allows last card to reach header`() {
        val padding = settingsBottomScrollPaddingDp(600f, 100f, naturalContentHeightDp = 950f, expandedTitleHeightDp = 250f)
        val maximumScroll = 950f + padding - 600f
        assertEquals(56f, 850f - maximumScroll, .01f)
        assertEquals(0f, settingsScrollHeaderState(maximumScroll, 250f, 250f).titleAlpha, 0f)
    }

    @Test fun `large font compact title reserves back area and height for two lines`() {
        val layout = settingsCompactTitleLayout(320f, 96f, 58f)
        assertEquals(58f, layout.startDp, 0f)
        assertEquals(246f, layout.widthDp, 0f)
        assertEquals(112f, layout.heightDp, 0f)
        val compact = settingsScrollHeaderState(138f, 250f, 250f, layout.heightDp)
        assertEquals(69f, compact.titleOffsetDp, .01f)
        assertEquals(1f, compact.titleAlpha, 0f)
        val maximumScroll = 300f + 100f + settingsBottomScrollPaddingDp(600f, 100f, headerHeightDp = layout.heightDp) - 600f
        assertEquals(112f, 300f - maximumScroll, .01f)
    }

    @Test fun `title shrinks above first card then scrolls out while back circle waits for content`() {
        val expanded = settingsScrollHeaderState(0f, 250f, 250f)
        val middle = settingsScrollHeaderState(80f, 250f, 250f)
        val collapsed = settingsScrollHeaderState(250f, 250f, 250f)
        assertEquals(1f, expanded.titleAlpha, 0f)
        assertEquals(0f, expanded.backCircleAlpha, 0f)
        assertEquals(1f, middle.titleAlpha, 0f)
        assertTrue(middle.titleOffsetDp > 0)
        assertEquals(0f, middle.backCircleAlpha, 0f)
        assertEquals(0f, collapsed.titleAlpha, 0f)
        assertEquals(1f, collapsed.backCircleAlpha, 0f)
        val compact = settingsScrollHeaderState(194f, 250f, 250f)
        assertEquals(20f / 34f, compact.titleScale, .001f)
        assertEquals(97f, compact.titleOffsetDp, .001f)
        assertEquals(1f, compact.titleAlpha, 0f)
        assertEquals(expanded, settingsScrollHeaderState(-10f, 250f, 250f))
    }

    @Test fun `last card can reach the header even on a short settings page`() {
        val padding = settingsBottomScrollPaddingDp(600f, 100f)
        val maximumScroll = 300f + 100f + padding - 600f
        assertEquals(56f, 300f - maximumScroll, .01f)
        assertTrue(settingsBottomScrollPaddingDp(400f, 700f) >= 24f)
        assertEquals(84f, settingsBottomScrollPaddingDp(400f, 700f, 84f), 0f)
    }
}
