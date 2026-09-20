package app.aapswear.storage

import app.aapswear.model.DataCapability
import app.aapswear.model.GlucosePrediction
import app.aapswear.model.PredictionKind
import app.aapswear.model.TherapyDisplayState

/**
 * Keeps the last valid AAPS prediction series inside the persisted display state.
 *
 * AAPS status broadcasts can temporarily omit their Suggested/Enacted payload while
 * the phone or Watch reconnects. An empty update must not erase a still useful graph
 * lane. Cached points remain display-only and age out after they have left the longest
 * supported graph window.
 */
object PersistentPredictionCache {
    const val RETENTION_AFTER_LAST_SAMPLE_MS = 3L * 60L * 60_000L

    private const val MAX_PAST_SAMPLE_AGE_MS = 24L * 60L * 60_000L
    private const val MAX_FUTURE_SAMPLE_OFFSET_MS = 6L * 60L * 60_000L
    private const val MAX_SAMPLES_PER_SERIES = 300

    fun merge(
        previous: TherapyDisplayState?,
        incoming: TherapyDisplayState,
        nowEpochMs: Long,
    ): TherapyDisplayState {
        // Prediction samples carry their own source. Keep a still-valid AAPS forecast when the
        // selected glucose transport temporarily changes (for example Automatic falling back to
        // xDrip or direct G7). Dropping it solely because TherapyDisplayState.source changed caused
        // the intermittent disappearing curves reported during short connection gaps.
        val previousSeries = previous?.glucosePredictions.orEmpty()
        val merged =
            mergeSeries(
                previous = previousSeries,
                incoming = incoming.glucosePredictions,
                nowEpochMs = nowEpochMs,
            )
        val capabilities =
            if (merged.isNotEmpty()) {
                incoming.capabilities + DataCapability.PREDICTIONS
            } else {
                incoming.capabilities - DataCapability.PREDICTIONS
            }

        return incoming.copy(
            glucosePredictions = merged,
            capabilities = capabilities,
        )
    }

    internal fun mergeSeries(
        previous: List<GlucosePrediction>,
        incoming: List<GlucosePrediction>,
        nowEpochMs: Long,
    ): List<GlucosePrediction> {
        val byKind = linkedMapOf<PredictionKind, GlucosePrediction>()
        normalized(previous, nowEpochMs).forEach { byKind[it.kind] = it }
        normalized(incoming, nowEpochMs).forEach { byKind[it.kind] = it }
        return PredictionKind.entries.mapNotNull(byKind::get)
    }

    private fun normalized(
        values: List<GlucosePrediction>,
        nowEpochMs: Long,
    ): List<GlucosePrediction> {
        val earliestSample = nowEpochMs - MAX_PAST_SAMPLE_AGE_MS
        val latestSample = nowEpochMs + MAX_FUTURE_SAMPLE_OFFSET_MS
        val oldestRetainedEnd = nowEpochMs - RETENTION_AFTER_LAST_SAMPLE_MS

        return values.mapNotNull { series ->
            val samples =
                series.samples
                    .asSequence()
                    .filter {
                        it.valueMgDl.isFinite() &&
                            it.valueMgDl in 20.0..1000.0 &&
                            it.measuredAtEpochMs in earliestSample..latestSample
                    }.associateBy { it.measuredAtEpochMs }
                    .values
                    .sortedBy { it.measuredAtEpochMs }
                    .takeLast(MAX_SAMPLES_PER_SERIES)
            samples
                .takeIf {
                    it.isNotEmpty() &&
                        it.last().measuredAtEpochMs >= oldestRetainedEnd
                }?.let { GlucosePrediction(series.kind, it) }
        }
    }
}
