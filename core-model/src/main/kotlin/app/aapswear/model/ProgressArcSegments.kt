package app.aapswear.model

/** Separate round-ended fill/track arcs; the gap includes the two projecting stroke caps. */
data class ProgressArcSegments(
    val fillSweep: Float,
    val trackStartOffset: Float,
    val trackSweep: Float,
)

fun progressArcSegments(progress: Float?, diameter: Float, stroke: Float, sweep: Float = 280f): ProgressArcSegments {
    val fraction = progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val filled = sweep * fraction
    if (fraction <= 0f) return ProgressArcSegments(0f, 0f, sweep)
    if (fraction >= 1f) return ProgressArcSegments(sweep, sweep, 0f)
    val radius = ((diameter - stroke) / 2f).coerceAtLeast(stroke / 2f).coerceAtLeast(.001f)
    val capClearance = Math.toDegrees((stroke * 1.6f / radius).toDouble()).toFloat()
    val trackStart = (filled + capClearance).coerceAtMost(sweep)
    return ProgressArcSegments(filled, trackStart, sweep - trackStart)
}
