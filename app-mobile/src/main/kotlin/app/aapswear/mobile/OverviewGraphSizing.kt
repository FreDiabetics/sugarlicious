package app.aapswear.mobile

/** Fill the initial viewport through CGM; every following card remains below the fold. */
internal fun overviewFittedCgmHeight(preferred: Float, viewport: Float, header: Float, visibleCardHeights: List<Float?>): Float {
    val cgmIndex = visibleCardHeights.indexOf(null)
    if (cgmIndex < 0 || header <= 0f) return preferred
    val precedingCards = visibleCardHeights.take(cgmIndex)
    val spacing = 4f + 2f + precedingCards.size * 12f
    // Large system fonts may require scrolling even before the graph; never collapse it.
    return (viewport - header - spacing - precedingCards.filterNotNull().sum()).coerceAtLeast(96f)
}

/** Keep IOB/COB readable; additional content is reached by scrolling. */
internal fun overviewMetabolicGraphHeight(profileHeight: Int): Int = maxOf(profileHeight + 32, 180)

/** Floor in pixels so three rounded child widths cannot exceed a FlowRow by one pixel. */
internal fun overviewProgressColumnWidthPx(available: Int, gap: Int, minimum: Int): Int =
    maxOf(minimum, ((available - 2 * gap).coerceAtLeast(0) / 3)).coerceIn(1, available.coerceAtLeast(1))
