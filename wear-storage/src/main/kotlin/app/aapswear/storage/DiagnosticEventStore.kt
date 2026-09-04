package app.aapswear.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.aapswear.model.DiagnosticEvent
import app.aapswear.model.DiagnosticSeverity
import app.aapswear.model.AppClock
import app.aapswear.model.SystemAppClock
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.diagnosticEventDataStore by preferencesDataStore("diagnostic_events")

/** A local, bounded diagnostic database using the same event format on Mobile and Wear. */
class DiagnosticEventStore(
    context: Context,
    private val clock: AppClock = SystemAppClock,
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val serializer = ListSerializer(DiagnosticEvent.serializer())

    val events: Flow<List<DiagnosticEvent>> =
        appContext.diagnosticEventDataStore.data.map { preferences ->
            decode(preferences[EVENTS_KEY]).sortedByDescending(DiagnosticEvent::occurredAtEpochMs)
        }

    suspend fun record(
        origin: String,
        module: String,
        code: String,
        severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
        message: String,
        metadata: Map<String, Any?> = emptyMap(),
        occurredAtEpochMs: Long = clock.nowEpochMs(),
    ) {
        append(
            listOf(
                DiagnosticEvent(
                    id = UUID.randomUUID().toString(),
                    occurredAtEpochMs = occurredAtEpochMs,
                    origin = clean(origin, 20),
                    module = clean(module, 40),
                    code = clean(code, 40),
                    severity = severity,
                    message = clean(message, 240),
                    metadata = sanitizeMetadata(metadata),
                ),
            ),
            occurredAtEpochMs,
        )
    }

    suspend fun append(
        incoming: List<DiagnosticEvent>,
        nowEpochMs: Long = clock.nowEpochMs(),
    ) {
        if (incoming.isEmpty()) return
        appContext.diagnosticEventDataStore.edit { preferences ->
            val retained = normalize(decode(preferences[EVENTS_KEY]) + incoming, nowEpochMs)
            preferences[EVENTS_KEY] = json.encodeToString(serializer, retained)
        }
    }

    suspend fun snapshot(): List<DiagnosticEvent> = events.first()

    suspend fun clear() {
        appContext.diagnosticEventDataStore.edit { it.remove(EVENTS_KEY) }
    }

    private fun normalize(values: List<DiagnosticEvent>, nowEpochMs: Long): List<DiagnosticEvent> =
        values
            .asSequence()
            .filter { it.occurredAtEpochMs in (nowEpochMs - RETENTION_MS)..(nowEpochMs + FUTURE_TOLERANCE_MS) }
            .map(::sanitizeEvent)
            .distinctBy(DiagnosticEvent::id)
            .sortedBy(DiagnosticEvent::occurredAtEpochMs)
            .toList()
            .takeLast(MAX_EVENTS)

    private fun sanitizeEvent(event: DiagnosticEvent): DiagnosticEvent =
        event.copy(
            id = clean(event.id, 80).ifBlank { UUID.randomUUID().toString() },
            origin = clean(event.origin, 20),
            module = clean(event.module, 40),
            code = clean(event.code, 40),
            message = clean(event.message, 240),
            metadata = sanitizeMetadata(event.metadata),
        )

    private fun decode(raw: String?): List<DiagnosticEvent> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun sanitizeMetadata(values: Map<String, Any?>): Map<String, String> =
        values.entries
            .asSequence()
            .filterNot { (key, _) -> SENSITIVE_KEY_PARTS.any { key.contains(it, ignoreCase = true) } }
            .take(MAX_METADATA_FIELDS)
            .associate { (key, value) -> clean(key, 40) to clean(value?.toString() ?: "—", 120) }

    private fun clean(value: String, maxLength: Int): String =
        value.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(maxLength)

    private companion object {
        val EVENTS_KEY = stringPreferencesKey("events_v1")
        const val MAX_EVENTS = 1_000
        const val MAX_METADATA_FIELDS = 12
        const val RETENTION_MS = 7L * 24L * 60L * 60_000L
        const val FUTURE_TOLERANCE_MS = 5L * 60_000L
        val SENSITIVE_KEY_PARTS = setOf("secret", "token", "password", "sharedkey", "gkey", "authraw", "packetraw")
    }
}
