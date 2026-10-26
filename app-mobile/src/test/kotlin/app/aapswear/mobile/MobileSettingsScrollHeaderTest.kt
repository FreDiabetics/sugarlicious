package app.aapswear.mobile

import org.junit.Assert.*
import org.junit.Test

class MobileSettingsScrollHeaderTest {
    @Test fun `submenu title fades before back surface meets the first card`() {
        val opened = settingsSubmenuHeaderState(0f, 0f, 160f, 88f)
        assertEquals(1f, opened.compactTitleAlpha, 0f)
        assertEquals(0f, opened.expandedTitleAlpha, 0f)
        assertEquals(0f, opened.backCircleAlpha, 0f)
        val titleGone = settingsSubmenuHeaderState(20f, 0f, 160f, 88f)
        assertEquals(0f, titleGone.compactTitleAlpha, 0f)
        assertEquals(0f, titleGone.backCircleAlpha, 0f)
        val beforeTouch = settingsSubmenuHeaderState(32f, 0f, 160f, 88f)
        assertEquals(1f, beforeTouch.backCircleAlpha, 0f)
        assertTrue(88f - 32f > 52f)
    }

    @Test fun `pull down crossfades compact and expanded titles with a small stagger`() {
        val crossing = settingsSubmenuHeaderState(0f, 48f, 160f, 136f)
        assertTrue(crossing.compactTitleAlpha < .3f)
        assertTrue(crossing.expandedTitleAlpha in 0f..0.2f)
        val expanded = settingsSubmenuHeaderState(0f, 160f, 160f, 248f)
        assertEquals(0f, expanded.compactTitleAlpha, 0f)
        assertEquals(1f, expanded.expandedTitleAlpha, 0f)
        assertEquals(0f, expanded.backCircleAlpha, 0f)
        assertEquals(0f, settingsSubmenuHeaderState(160f, 160f, 160f, 248f).expandedTitleAlpha, 0f)
    }

    @Test fun `large font compact title reserves back area and height for two lines`() {
        val layout = settingsCompactTitleLayout(320f, 96f, 58f)
        assertEquals(58f, layout.startDp, 0f)
        assertEquals(246f, layout.widthDp, 0f)
        assertEquals(112f, layout.heightDp, 0f)
        val compact = settingsScrollHeaderState(138f, 250f, 250f, layout.heightDp)
        assertEquals(69f, compact.titleOffsetDp, .01f)
        assertEquals(1f, compact.titleAlpha, 0f)
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
}
