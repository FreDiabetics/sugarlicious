package app.aapswear.storage
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.aapswear.model.CanonicalCgmHistory
import app.aapswear.model.DailyTimeInRange
import app.aapswear.model.GlucoseSample
import app.aapswear.model.TherapyDisplayState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.therapyDataStore by preferencesDataStore("therapy_display_state")

class TherapyStateStore(
    private val context: Context,
) {
    private val key = stringPreferencesKey("state_v1")
    private val archiveKey = stringPreferencesKey("daily_tir_history_v1")
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    val state: Flow<TherapyDisplayState?> =
        context.therapyDataStore.data.map { preferences ->
            preferences[key]?.let { raw ->
                runCatching { json.decodeFromString<TherapyDisplayState>(raw) }
                    .onFailure { Log.w("SugarliciousStorage", "State decode failed: ${it.javaClass.simpleName}") }
                    .getOrNull()
            }
        }

    suspend fun save(value: TherapyDisplayState) {
        context.therapyDataStore.edit { it[key] = json.encodeToString(value) }
        recordHistory(value)
    }

    /** Separate bounded historical cache: never replaces the resolver's current display state. */
    suspend fun recordHistory(value: TherapyDisplayState?, now: Long = System.currentTimeMillis()): List<GlucoseSample> {
        var result = emptyList<GlucoseSample>()
        context.therapyDataStore.edit { preferences ->
            val old = preferences[archiveKey]?.let { runCatching { json.decodeFromString<List<GlucoseSample>>(it) }.getOrNull() }.orEmpty()
            val incoming =
                value?.glucoseHistory.orEmpty() +
                    listOfNotNull(
                        value?.glucose?.let {
                            GlucoseSample(it.valueMgDl, it.measuredAtEpochMs, it.source, it.sensorId, it.sessionId, it.sequenceNumber, it.receivedAtEpochMs, it.quality)
                        },
                    )
            result = CanonicalCgmHistory.merge(old + incoming, now, windowMs = DailyTimeInRange.ARCHIVE_WINDOW_MS, futureToleranceMs = 0, maxPoints = DailyTimeInRange.ARCHIVE_MAX_POINTS)
            if (result != old) preferences[archiveKey] = json.encodeToString(result)
        }
        return result
    }
}
