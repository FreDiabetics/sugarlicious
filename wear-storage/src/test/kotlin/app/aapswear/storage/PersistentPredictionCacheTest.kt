package app.aapswear.storage

import app.aapswear.model.DataCapability
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucosePrediction
import app.aapswear.model.GlucoseSample
import app.aapswear.model.PredictionKind
import app.aapswear.model.TherapyDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentPredictionCacheTest {
    private val minute = 60_000L
    private val now = 10_000L * minute

    @Test
    fun `empty transient update retains persisted predictions`() {
        val previous =
            state(
                predictions = listOf(prediction(PredictionKind.IOB, now - minute, now + 90 * minute)),
            )

        val merged = PersistentPredictionCache.merge(previous, state(), now)

        assertEquals(previous.glucosePredictions, merged.glucosePredictions)
        assertTrue(DataCapability.PREDICTIONS in merged.capabilities)
    }

    @Test
    fun `new series replaces the same kind and keeps other cached kinds`() {
        val previous =
            state(
                predictions =
                    listOf(
                        prediction(PredictionKind.IOB, now, now + 60 * minute),
                        prediction(PredictionKind.UAM, now, now + 45 * minute),
                    ),
            )
        val replacement = prediction(PredictionKind.IOB, now, now + 120 * minute)

        val merged =
            PersistentPredictionCache.merge(
                previous,
                state(predictions = listOf(replacement)),
                now,
            )

        assertEquals(replacement, merged.glucosePredictions.first { it.kind == PredictionKind.IOB })
        assertTrue(merged.glucosePredictions.any { it.kind == PredictionKind.UAM })
    }

    @Test
    fun `expired predictions are removed from persistent display state`() {
        val expiredEnd = now - PersistentPredictionCache.RETENTION_AFTER_LAST_SAMPLE_MS - 1L
        val previous =
            state(
                predictions = listOf(prediction(PredictionKind.IOB, expiredEnd - minute, expiredEnd)),
            )

        val merged = PersistentPredictionCache.merge(previous, state(), now)

        assertTrue(merged.glucosePredictions.isEmpty())
        assertFalse(DataCapability.PREDICTIONS in merged.capabilities)
    }

    @Test
    fun `valid source-attributed predictions survive a temporary glucose source change`() {
        val previous =
            state(
                predictions = listOf(prediction(PredictionKind.IOB, now, now + 60 * minute)),
            )
        val xdrip = state(source = DataSourceId.XDRIP_PLUS)

        val merged = PersistentPredictionCache.merge(previous, xdrip, now)

        assertEquals(previous.glucosePredictions, merged.glucosePredictions)
        assertTrue(DataCapability.PREDICTIONS in merged.capabilities)
    }

    private fun state(
        source: DataSourceId = DataSourceId.ANDROID_APS,
        predictions: List<GlucosePrediction> = emptyList(),
    ) = TherapyDisplayState(
        source = source,
        receivedAtEpochMs = now,
        glucosePredictions = predictions,
        capabilities =
            if (predictions.isEmpty()) {
                emptySet()
            } else {
                setOf(DataCapability.PREDICTIONS)
            },
    )

    private fun prediction(
        kind: PredictionKind,
        first: Long,
        last: Long,
    ) = GlucosePrediction(
        kind,
        listOf(
            GlucoseSample(120.0, first),
            GlucoseSample(130.0, last),
        ),
    )
}
