package app.sugarlicious.mobile

import app.sugarlicious.model.CgmThresholds
import app.sugarlicious.model.GlucoseRangeBarInterval
import app.sugarlicious.model.glucoseRangeBarPresentation

internal enum class GlucoseBarRange { LOW, TARGET, HIGH }

internal data class GlucoseBarState(
    val range: GlucoseBarRange,
    val position: Float,
)

/** Mobile adapter for the same clamped interval semantics used by Wear. */
internal fun glucoseBarState(value: Double?, thresholds: CgmThresholds): GlucoseBarState? =
    glucoseRangeBarPresentation(value, thresholds)?.let {
        GlucoseBarState(
            range =
                when (it.interval) {
                    GlucoseRangeBarInterval.LOW -> GlucoseBarRange.LOW
                    GlucoseRangeBarInterval.TARGET -> GlucoseBarRange.TARGET
                    GlucoseRangeBarInterval.HIGH -> GlucoseBarRange.HIGH
                },
            position = it.position,
        )
    }
