package app.aapswear.mobile

/** A null height denotes the CGM card. The fourth card never reduces its height further. */
internal fun overviewFittedCgmHeight(preferred: Float, viewport: Float, header: Float, visibleCardHeights: List<Float?>): Float {
    val fittedCards = visibleCardHeights.take(3)
    if (null !in fittedCards || header <= 0f) return preferred
    val spacing = 4f + 2f + (fittedCards.size - 1).coerceAtLeast(0) * 12f
    val available = viewport - header - spacing - fittedCards.filterNotNull().sum()
    // Preserve a usable chart at extreme accessibility sizes; scrolling remains available.
    return minOf(preferred, available.coerceAtLeast(96f))
}
