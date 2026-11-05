package app.sugarlicious.mobile

import app.sugarlicious.model.CgmThresholds
import app.sugarlicious.model.GlucoseBarScale

// Retain existing Mobile call sites while sharing the exact interval mapping with Wear.
internal typealias GlucoseBarRange = app.sugarlicious.model.GlucoseBarRange
internal typealias GlucoseBarState = app.sugarlicious.model.GlucoseBarState

internal fun glucoseBarState(value: Double?, thresholds: CgmThresholds): GlucoseBarState? =
    GlucoseBarScale.state(value, thresholds)
