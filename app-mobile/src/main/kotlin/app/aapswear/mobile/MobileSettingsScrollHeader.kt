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

internal data class SettingsCompactTitleLayout(
    val startDp: Float,
    val widthDp: Float,
    val heightDp: Float,
)

internal fun settingsCompactTitleLayout(availableWidthDp: Float, textHeightDp: Float, startDp: Float = 52f): SettingsCompactTitleLayout =
    SettingsCompactTitleLayout(startDp, (availableWidthDp - startDp - 16f).coerceAtLeast(1f), (textHeightDp + 16f).coerceAtLeast(56f))
