package app.aapswear.wear

import android.content.Context
import android.net.Uri
import app.aapswear.model.DiagnosticBatch
import app.aapswear.model.DiagnosticEvent
import app.aapswear.model.DiagnosticSeverity
import app.aapswear.protocol.WearProtocol
import app.aapswear.storage.DiagnosticEventStore
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

internal suspend fun Context.recordWatchDiagnostic(
    module: String,
    code: String,
    message: String,
    severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
    metadata: Map<String, Any?> = emptyMap(),
) {
    DiagnosticEventStore(applicationContext).record(
        origin = "WATCH",
        module = module,
        code = code,
        severity = severity,
        message = message,
        metadata = metadata,
    )
}

internal suspend fun sendWatchDiagnostics(
    context: Context,
    nodeId: String,
) {
    val localEvents = DiagnosticEventStore(context).snapshot()
    val g7Events = readG7Diagnostics(context)
    val events = (localEvents + g7Events).distinctBy(DiagnosticEvent::id).sortedBy(DiagnosticEvent::occurredAtEpochMs).takeLast(1_000)
    Wearable
        .getMessageClient(context)
        .sendMessage(
            nodeId,
            WearProtocol.DIAGNOSTICS_BATCH_PATH,
            WearProtocol.encodeDiagnostics(DiagnosticBatch(events, System.currentTimeMillis())),
        ).await()
}

private fun readG7Diagnostics(context: Context): List<DiagnosticEvent> =
    runCatching {
        context.contentResolver
            .query(
                Uri.parse("content://app.aapswear.g7watch.readings/diagnostics"),
                null,
                null,
                null,
                null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            DiagnosticEvent(
                                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                                occurredAtEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at")),
                                origin = cursor.getString(cursor.getColumnIndexOrThrow("origin")),
                                module = cursor.getString(cursor.getColumnIndexOrThrow("module")),
                                code = cursor.getString(cursor.getColumnIndexOrThrow("code")),
                                severity =
                                    runCatching {
                                        DiagnosticSeverity.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("severity")))
                                    }.getOrDefault(DiagnosticSeverity.WARNING),
                                message = cursor.getString(cursor.getColumnIndexOrThrow("message")),
                                metadata =
                                    cursor
                                        .getString(cursor.getColumnIndexOrThrow("metadata"))
                                        .takeIf(String::isNotBlank)
                                        ?.let { mapOf("details" to it) }
                                        .orEmpty(),
                            ),
                        )
                    }
                }
            }.orEmpty()
    }.getOrDefault(emptyList())
