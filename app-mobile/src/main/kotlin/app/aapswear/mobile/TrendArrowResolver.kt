package app.aapswear.mobile

import app.aapswear.model.GlucoseSample
import app.aapswear.model.Trend

/**
 * AndroidAPS is authoritative whenever its External Companion Apps broadcast contains a trend:
 * AAPS forwards lastBG.trendArrow directly. This resolver only supplies a fallback when AAPS
 * reports no usable arrow.
 */
internal object TrendArrowResolver {
    fun resolve(
        aapsTrend: Trend,
        history: List<GlucoseSample>,
        currentTimestamp: Long,
        nightscoutDirection: String? = null,
    ): Trend {
        if (aapsTrend != Trend.UNKNOWN) return aapsTrend

        directionToTrend(nightscoutDirection)?.let { return it }

        val current =
            history
                .filter { it.measuredAtEpochMs <= currentTimestamp }
                .maxByOrNull { it.measuredAtEpochMs }
                ?: return Trend.UNKNOWN

        val previous =
            history
                .asSequence()
                .filter { it.measuredAtEpochMs < current.measuredAtEpochMs }
                .map { sample ->
                    val minutes = (current.measuredAtEpochMs - sample.measuredAtEpochMs) / 60_000.0
                    sample to minutes
                }.filter { (_, minutes) -> minutes in 3.0..12.0 }
                .minByOrNull { (_, minutes) -> kotlin.math.abs(minutes - 5.0) }
                ?: return Trend.UNKNOWN

        val rateMgDlPerMinute =
            (current.valueMgDl - previous.first.valueMgDl) / previous.second

        return when {
            rateMgDlPerMinute > 3.0 -> Trend.DOUBLE_UP
            rateMgDlPerMinute > 2.0 -> Trend.SINGLE_UP
            rateMgDlPerMinute > 1.0 -> Trend.FORTY_FIVE_UP
            rateMgDlPerMinute >= -1.0 -> Trend.FLAT
            rateMgDlPerMinute >= -2.0 -> Trend.FORTY_FIVE_DOWN
            rateMgDlPerMinute >= -3.0 -> Trend.SINGLE_DOWN
            else -> Trend.DOUBLE_DOWN
        }
    }

    fun directionToTrend(direction: String?): Trend? =
        when (direction?.trim()) {
            "DoubleUp", "⇈" -> Trend.DOUBLE_UP
            "SingleUp", "↑" -> Trend.SINGLE_UP
            "FortyFiveUp", "↗" -> Trend.FORTY_FIVE_UP
            "Flat", "→" -> Trend.FLAT
            "FortyFiveDown", "↘" -> Trend.FORTY_FIVE_DOWN
            "SingleDown", "↓" -> Trend.SINGLE_DOWN
            "DoubleDown", "⇊" -> Trend.DOUBLE_DOWN
            else -> null
        }
}
