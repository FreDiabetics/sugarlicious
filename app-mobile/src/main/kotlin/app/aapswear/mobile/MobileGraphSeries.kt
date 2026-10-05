package app.aapswear.mobile

import app.aapswear.model.TherapyHistorySample

internal fun finiteInsulinActivitySeries(points: List<TherapyHistorySample>): List<Pair<Long, Double>> =
    points
        .mapNotNull { point ->
            point.insulinActivityUnitsPerMinute?.takeIf(Double::isFinite)?.let { point.measuredAtEpochMs to it }
        }.sortedBy { it.first }

internal fun smoothActivitySeries(values: List<Pair<Long, Double>>): List<Pair<Long, Double>> {
    val ordered = values.distinctBy { it.first }.sortedBy { it.first }
    if (ordered.size < 3) return ordered
    return ordered.mapIndexed { index, point ->
        if (index == 0 || index == ordered.lastIndex) {
            point
        } else {
            val smoothed = (ordered[index - 1].second + 2.0 * point.second + ordered[index + 1].second) / 4.0
            point.first to smoothed
        }
    }
}

internal fun smoothActivityToLiveEdge(
    values: List<Pair<Long, Double>>,
    liveEdge: Long,
    start: Long,
    end: Long,
): List<Pair<Long, Double>> = extendSeriesToLiveEdge(smoothActivitySeries(values), liveEdge, start, end)
