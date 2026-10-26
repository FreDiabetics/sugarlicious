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

internal data class SettingsSubmenuHeaderState(
    val compactTitleAlpha: Float,
    val expandedTitleAlpha: Float,
    val backCircleAlpha: Float,
    val compactTitleOffsetDp: Float,
)

internal fun settingsSubmenuExpansionTarget(expansionDp: Float, maximumDp: Float, velocityDpPerSecond: Float): Float =
    when {
        velocityDpPerSecond <= -600f -> 0f
        velocityDpPerSecond >= 600f -> maximumDp
        expansionDp >= maximumDp * .35f -> maximumDp
        else -> 0f
    }

/** The button remains fixed; only title opacity/offset and the real expandable header change. */
internal fun settingsSubmenuHeaderState(
    scrollDp: Float,
    expansionDp: Float,
    maximumExpansionDp: Float,
    firstCardTopDp: Float,
    backBottomDp: Float = 52f,
): SettingsSubmenuHeaderState {
    val scroll = scrollDp.coerceAtLeast(0f)
    val expansion = (expansionDp / maximumExpansionDp.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val compact = (1f - scroll / 20f).coerceIn(0f, 1f) * (1f - expansion / .4f).coerceIn(0f, 1f)
    val expanded =
        ((expansion - .25f) / .5f).coerceIn(0f, 1f) *
            (1f - scroll / (expansionDp * .7f).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val gap = firstCardTopDp - scroll - backBottomDp
    return SettingsSubmenuHeaderState(compact, expanded, ((14f - gap) / 10f).coerceIn(0f, 1f), -6f * (1f - compact))
}
