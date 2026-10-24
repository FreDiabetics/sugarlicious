package app.aapswear.mobile

internal data class SettingsScrollHeaderState(
    val titleAlpha: Float,
    val titleOffsetDp: Float,
    val backCircleAlpha: Float,
    val titleScale: Float,
    val collapseFraction: Float,
)

internal fun settingsScrollHeaderState(scrollDp: Float, titleHeightDp: Float, firstCardTopDp: Float, compactHeightDp: Float = 56f): SettingsScrollHeaderState {
    val scroll = scrollDp.coerceAtLeast(0f)
    val collapseDistance = (titleHeightDp - compactHeightDp).coerceAtLeast(1f)
    val progress = if (titleHeightDp > compactHeightDp) (scroll / collapseDistance).coerceIn(0f, 1f) else 0f
    return SettingsScrollHeaderState(
        titleAlpha = if (scroll < titleHeightDp) 1f else 0f,
        titleOffsetDp = (titleHeightDp - compactHeightDp).coerceAtLeast(0f) * .5f * progress,
        backCircleAlpha = ((scroll - firstCardTopDp + compactHeightDp) / 24f).coerceIn(0f, 1f),
        titleScale = if (titleHeightDp > 56f) 1f - (1f - 20f / 34f) * progress else 1f,
        collapseFraction = progress,
    )
}

internal fun settingsBottomScrollPaddingDp(
    viewportHeightDp: Float,
    lastCardHeightDp: Float,
    minimumDp: Float = 24f,
    headerHeightDp: Float = 56f,
    naturalContentHeightDp: Float = Float.NaN,
    expandedTitleHeightDp: Float = 0f,
): Float {
    if (naturalContentHeightDp.isFinite()) {
        if (naturalContentHeightDp <= 0f || viewportHeightDp <= 0f) return minimumDp
        val bodyHeight = (naturalContentHeightDp - expandedTitleHeightDp).coerceAtLeast(0f)
        // Short pages may collapse their title, but must not scroll it off-screen
        // merely because a large synthetic footer was added below one small card.
        if (bodyHeight + minimumDp <= viewportHeightDp - headerHeightDp) {
            return (viewportHeightDp - headerHeightDp - bodyHeight).coerceAtLeast(minimumDp)
        }
    }
    return (viewportHeightDp - lastCardHeightDp - headerHeightDp).coerceAtLeast(minimumDp)
}

internal data class SettingsCompactTitleLayout(
    val startDp: Float,
    val widthDp: Float,
    val heightDp: Float,
)

internal fun settingsCompactTitleLayout(availableWidthDp: Float, textHeightDp: Float, startDp: Float = 52f): SettingsCompactTitleLayout =
    SettingsCompactTitleLayout(startDp, (availableWidthDp - startDp - 16f).coerceAtLeast(1f), (textHeightDp + 16f).coerceAtLeast(56f))

internal fun lastVisibleSettingsCard(view: android.view.View): android.view.View? {
    if (view.visibility != android.view.View.VISIBLE) return null
    if (view is android.view.ViewGroup) {
        for (index in view.childCount - 1 downTo 0) lastVisibleSettingsCard(view.getChildAt(index))?.let { return it }
    }
    return view.takeIf { it.tag == "oneui_settings_card" }
}
