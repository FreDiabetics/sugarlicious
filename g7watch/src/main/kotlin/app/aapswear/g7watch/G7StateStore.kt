package app.aapswear.g7watch

import android.content.Context
import app.aapswear.g7.G7PersistedState
import kotlinx.serialization.json.Json

internal class G7SensorStateStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("g7_collector_state", Context.MODE_PRIVATE)
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    fun read(): G7PersistedState =
        preferences
            .getString(KEY_STATE, null)
            ?.let { runCatching { json.decodeFromString<G7PersistedState>(it) }.getOrNull() }
            ?: G7PersistedState()

    fun save(state: G7PersistedState) {
        // Authentication material is intentionally not part of G7PersistedState. Once the
        // protocol is validated, secrets must be stored with Android Keystore protection.
        preferences.edit().putString(KEY_STATE, json.encodeToString(G7PersistedState.serializer(), state)).apply()
    }

    private companion object {
        const val KEY_STATE = "state"
    }
}
