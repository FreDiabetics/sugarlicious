package app.sugarlicious.model

/** Shared interval semantics for Mobile Compose, the Wear app, and Wear OS tiles.
 * Only the presentation is clamped; canonical glucose measurements are never altered.
 */
enum class GlucoseRangeBarInterval { LOW, TARGET, HIGH }

data class GlucoseRangeBarPresentation(
    val interval: GlucoseRangeBarInterval,
    val position: Float,
)

object GlucoseRangeBarStyle {
    const val MINIMUM_MG_DL = 40.0
    const val MAXIMUM_MG_DL = 400.0
    const val MARKER_GAP_DP = 7f
    const val MOBILE_HEIGHT_DP = 16f
    const val WEAR_APP_HEIGHT_DP = 10f
    const val WEAR_TILE_HEIGHT_DP = 12f
}

fun glucoseRangeBarPresentation(valueMgDl: Double?, thresholds: CgmThresholds): GlucoseRangeBarPresentation? {
    val interval =
        when (valueMgDl?.let(thresholds::classify)) {
            CgmRangeClass.VERY_LOW, CgmRangeClass.LOW -> GlucoseRangeBarInterval.LOW
            CgmRangeClass.IN_RANGE -> GlucoseRangeBarInterval.TARGET
            CgmRangeClass.HIGH, CgmRangeClass.VERY_HIGH -> GlucoseRangeBarInterval.HIGH
            null -> return null
        }
    val (minimum, maximum) =
        when (interval) {
            GlucoseRangeBarInterval.LOW -> GlucoseRangeBarStyle.MINIMUM_MG_DL to thresholds.lowMgDl
            GlucoseRangeBarInterval.TARGET -> thresholds.lowMgDl to thresholds.highMgDl
            GlucoseRangeBarInterval.HIGH -> thresholds.highMgDl to GlucoseRangeBarStyle.MAXIMUM_MG_DL
        }
    val position =
        if (maximum > minimum) {
            ((requireNotNull(valueMgDl) - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0).toFloat()
        } else {
            if (interval == GlucoseRangeBarInterval.LOW) 0f else 1f
        }
    return GlucoseRangeBarPresentation(interval, position)
}
