package app.aapswear.mobile

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.aapswear.model.DataSourceId
import app.aapswear.model.TherapyDisplayState
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.mobileSourceInputs by preferencesDataStore("mobile_source_inputs")

/** Raw source snapshots are distinct from the single canonical display state. */
internal class MobileSourceInputStore(
    context: Context,
) {
    private val app = context.applicationContext
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private fun stateKey(source: DataSourceId) = stringPreferencesKey("state.${source.name}")

    private fun retiredKey(source: DataSourceId) = stringPreferencesKey("retired.${source.name}")

    private fun decode(values: Preferences): Map<DataSourceId, TherapyDisplayState> =
        buildMap {
            MOBILE_INPUT_SOURCES.forEach { source ->
                values[stateKey(source)]?.let { raw ->
                    runCatching { json.decodeFromString<TherapyDisplayState>(raw) }
                        .getOrNull()
                        ?.takeIf { it.source == source }
                        ?.let { put(source, it) }
                }
            }
        }

    suspend fun read(): Map<DataSourceId, TherapyDisplayState> = decode(app.mobileSourceInputs.data.first())

    suspend fun merge(incoming: TherapyDisplayState, now: Long): Map<DataSourceId, TherapyDisplayState> {
        require(incoming.source in MOBILE_INPUT_SOURCES)
        val updated =
            app.mobileSourceInputs.edit { values ->
                val source = incoming.source
                if (source == DataSourceId.DEXCOM_SHARE) {
                    val activeIdentity = values[stringPreferencesKey("identity.DEXCOM_SHARE")]
                    if (activeIdentity != null && incoming.sourceVersion != activeIdentity) return@edit
                }
                val previous = decode(values)[source]
                val retired = values[retiredKey(source)]?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()
                val session = incoming.glucose?.sessionId
                if (session != null && session in retired) return@edit
                val oldSession = previous?.glucose?.sessionId
                val changesSession = session != null && oldSession != null && session != oldSession
                if (changesSession && incoming.glucose!!.measuredAtEpochMs < previous.glucose!!.measuredAtEpochMs) return@edit
                if (changesSession) values[retiredKey(source)] = json.encodeToString((retired + oldSession).distinct().takeLast(64))
                val merged = mergeMobileSourceInput(previous, incoming, now)
                values[stateKey(source)] = json.encodeToString(merged)
            }
        return decode(updated)
    }

    /** Account-scoped archives preserve history without mixing two Share accounts. */
    suspend fun activateShareIdentity(identity: String): Boolean {
        var changed = false
        app.mobileSourceInputs.edit { values ->
            val identityKey = stringPreferencesKey("identity.DEXCOM_SHARE")
            val old = values[identityKey]
            val pendingKey = stringPreferencesKey("pending.DEXCOM_SHARE")
            if (old == identity) {
                changed = values[pendingKey] == identity
                return@edit
            }
            val source = DataSourceId.DEXCOM_SHARE
            val archiveIdentity = old ?: "legacy-unscoped"
            values[stateKey(source)]?.let { values[stringPreferencesKey("archive.share.$archiveIdentity")] = it }
            values[retiredKey(source)]?.let { values[stringPreferencesKey("archive.retired.share.$archiveIdentity")] = it }
            val restored = values[stringPreferencesKey("archive.share.$identity")]
            if (restored == null) values.remove(stateKey(source)) else values[stateKey(source)] = restored
            val retired = values[stringPreferencesKey("archive.retired.share.$identity")]
            if (retired == null) values.remove(retiredKey(source)) else values[retiredKey(source)] = retired
            changed = true
            values[pendingKey] = identity
            values[identityKey] = identity
        }
        return changed
    }

    suspend fun completeShareIdentity(identity: String) {
        app.mobileSourceInputs.edit { values ->
            val pendingKey = stringPreferencesKey("pending.DEXCOM_SHARE")
            if (values[pendingKey] == identity) values.remove(pendingKey)
        }
    }

    suspend fun clear(source: DataSourceId) {
        require(source in MOBILE_INPUT_SOURCES)
        app.mobileSourceInputs.edit { values ->
            values.remove(stateKey(source))
            values.remove(retiredKey(source))
        }
    }
}
