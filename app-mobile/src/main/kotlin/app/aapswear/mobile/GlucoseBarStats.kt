package app.aapswear.mobile

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.aapswear.model.CgmThresholds
import app.aapswear.model.GlucoseSample
import app.aapswear.model.TherapyDisplayState
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Calendar-day statistics from the same gap-safe integration as the TIR card. */
@Composable
internal fun rememberGlucoseBarStats(state: TherapyDisplayState?, now: Long, thresholds: CgmThresholds): MeasuredRangeStats {
    val context = LocalContext.current
    val store = remember(context) { TherapyStateStore(context.applicationContext) }
    val revision by store.archiveRevision.collectAsState(initial = 0L)
    val start = localDayStartEpochMs(now)
    val archive by produceState<List<GlucoseSample>?>(null, store, revision, start) {
        value = withContext(Dispatchers.IO) { store.rangeHistory(start - 10 * 60_000L, now) }
    }
    val stats by produceState(MeasuredRangeStats(0, null, null, null), state, archive, now / 60_000L, start, thresholds) {
        val history = archive ?: return@produceState
        value = withContext(Dispatchers.Default) { dailyGlucoseRanges(state, now, archive = history, thresholds = thresholds).tir }
    }
    return stats
}
