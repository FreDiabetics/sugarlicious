package app.aapswear.wear

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import app.aapswear.complications.AllProviders
import app.aapswear.model.BasalState
import app.aapswear.model.CarbState
import app.aapswear.model.DataCapability
import app.aapswear.model.DeviceState
import app.aapswear.model.GlucosePrediction
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.InsulinState
import app.aapswear.model.LoopState
import app.aapswear.model.PredictionKind
import app.aapswear.model.ProfileState
import app.aapswear.model.PumpState
import app.aapswear.model.TargetState
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import app.aapswear.storage.PersistentPredictionCache
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.sin

/** Debug-build-only synthetic state injection. This class is absent from release APKs. */
class DebugStateReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = TherapyStateStore(context)
                val previous = store.state.first()
                val incoming = syntheticState(intent.getStringExtra("mode") ?: "current")
                store.save(
                    PersistentPredictionCache.merge(
                        previous = previous,
                        incoming = incoming,
                        nowEpochMs = System.currentTimeMillis(),
                    ),
                )
                AllProviders.classes.forEach { provider ->
                    ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, provider)).requestUpdateAll()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun syntheticState(mode: String): TherapyDisplayState {
        val now = System.currentTimeMillis()
        val measuredAt = if (mode == "stale") now - 25 * 60_000L else now - 2 * 60_000L
        val history =
            (0 until 30).map { index ->
                val minutesAgo = (29 - index) * 5L
                GlucoseSample(
                    valueMgDl = 118.0 + sin(index / 3.0) * 20.0 + index * 0.3,
                    measuredAtEpochMs = now - minutesAgo * 60_000L,
                )
            }
        val predictions =
            if (mode == "predictions") {
                listOf(
                    GlucosePrediction(
                        PredictionKind.IOB,
                        (-3..12).map { index ->
                            GlucoseSample(
                                valueMgDl = 129.0 - index * 2.5,
                                measuredAtEpochMs = now + index * 5L * 60_000L,
                            )
                        },
                    ),
                )
            } else {
                emptyList()
            }
        return TherapyDisplayState(
            receivedAtEpochMs = now,
            sourceVersion = "4.0.0-dev synthetic",
            sourceContract = "AAPS_EXTENDED_STATUS_V1",
            glucose = if (mode == "none") null else GlucoseState(129.0, GlucoseUnit.MG_DL, Trend.FLAT, measuredAt, 6.0, 4.0),
            glucoseHistory = history,
            glucosePredictions = predictions,
            insulin = InsulinState(2.45, 1.65, 0.80),
            carbs = CarbState(36.0, 0.0),
            basal = BasalState(0.95, tempPercent = 110, displayText = "110%"),
            target = TargetState(80.0, 160.0),
            loop = LoopState("Aktiv", now - 3 * 60_000L),
            pump = PumpState("OK", 119.0, 82),
            device = DeviceState(78, 90),
            profile = ProfileState("Standard"),
            capabilities = DataCapability.entries.toSet(),
        )
    }
}
