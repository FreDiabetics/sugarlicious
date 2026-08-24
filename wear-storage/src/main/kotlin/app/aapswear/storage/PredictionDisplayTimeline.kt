package app.aapswear.storage

import app.aapswear.model.GlucosePrediction

/**
 * Builds the display-only future prediction timeline without rewriting source timestamps.
 *
 * AAPS renders CGM, predictions and the Now line on one real time axis. Prediction packets may
 * contain a seed point at or before the latest CGM reading; that seed is useful for calculation but
 * must not be squeezed to the right side of the divider. For display we therefore keep only real
 * future samples and preserve their original spacing.
 */
object PredictionDisplayTimeline {
    const val MAX_DISPLAY_WINDOW_MS = 2L * 60L * 60_000L

    fun anchor(
        predictions: List<GlucosePrediction>,
        nowEpochMs: Long,
    ): List<GlucosePrediction> =
        predictions.mapNotNull { series ->
            val samples =
                series.samples
                    .asSequence()
                    .filter { it.valueMgDl.isFinite() && it.valueMgDl in 20.0..1000.0 }
                    .filter { it.measuredAtEpochMs > nowEpochMs }
                    .distinctBy { it.measuredAtEpochMs }
                    .sortedBy { it.measuredAtEpochMs }
                    .toList()
            if (samples.isEmpty()) null else series.copy(samples = samples)
        }

    fun futureWindowMs(
        predictions: List<GlucosePrediction>,
        nowEpochMs: Long,
    ): Long =
        anchor(predictions, nowEpochMs)
            .flatMap { it.samples }
            .maxOfOrNull { it.measuredAtEpochMs }
            ?.minus(nowEpochMs)
            ?.coerceIn(0L, MAX_DISPLAY_WINDOW_MS)
            ?: 0L
}