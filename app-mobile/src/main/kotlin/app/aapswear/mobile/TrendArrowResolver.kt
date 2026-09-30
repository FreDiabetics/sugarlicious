package app.aapswear.mobile

import app.aapswear.model.CanonicalTrendPolicy
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.Trend
import app.aapswear.model.TrendRateProfile
import app.aapswear.model.ValueProvenance

/**
 * AndroidAPS is authoritative whenever its External Companion Apps broadcast contains a trend:
 * AAPS forwards lastBG.trendArrow directly. This resolver only supplies a fallback when AAPS
 * reports no usable arrow.
 */
internal object TrendArrowResolver {
    data class Resolution(
        val trend: Trend,
        val rateMgDlPerMinute: Double? = null,
        val provenance: ValueProvenance = ValueProvenance.UNAVAILABLE,
    )

    fun resolve(
        aapsTrend: Trend,
        current: GlucoseState,
        history: List<GlucoseSample>,
        nightscoutDirection: String? = null,
    ): Trend = resolveWithRate(aapsTrend, current, history, nightscoutDirection).trend

    fun resolveWithRate(
        aapsTrend: Trend,
        current: GlucoseState,
        history: List<GlucoseSample>,
        nightscoutDirection: String? = null,
    ): Resolution {
        if (aapsTrend != Trend.UNKNOWN) return Resolution(aapsTrend, provenance = ValueProvenance.SOURCE)

        CanonicalTrendPolicy.fromDirection(nightscoutDirection).takeUnless { it == Trend.UNKNOWN }?.let {
            return Resolution(it, provenance = ValueProvenance.SOURCE)
        }
        val currentSample =
            GlucoseSample(
                valueMgDl = current.valueMgDl,
                measuredAtEpochMs = current.measuredAtEpochMs,
                source = current.source,
                sensorId = current.sensorId,
                sessionId = current.sessionId,
                sequenceNumber = current.sequenceNumber,
                receivedAtEpochMs = current.receivedAtEpochMs,
                quality = current.quality,
            )
        val derived = CanonicalTrendPolicy.derive(currentSample, history, TrendRateProfile.ANDROID_APS)
        return Resolution(
            derived?.trend ?: Trend.UNKNOWN,
            derived?.rateMgDlPerMinute,
            if (derived == null) ValueProvenance.UNAVAILABLE else ValueProvenance.DERIVED,
        )
    }

    fun directionToTrend(direction: String?): Trend? = CanonicalTrendPolicy.fromDirection(direction).takeUnless { it == Trend.UNKNOWN }
}
