package app.aapswear.mobile

import android.content.Context
import app.aapswear.model.DiagnosticSeverity
import app.aapswear.protocol.WearProtocol
import app.aapswear.storage.DiagnosticEventStore
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

internal suspend fun Context.recordMobileDiagnostic(
    module: String,
    code: String,
    message: String,
    severity: DiagnosticSeverity = DiagnosticSeverity.INFO,
    metadata: Map<String, Any?> = emptyMap(),
) {
    DiagnosticEventStore(applicationContext).record(
        origin = "MOBILE",
        module = module,
        code = code,
        severity = severity,
        message = message,
        metadata = metadata,
    )
}

internal suspend fun requestWatchDiagnostics(context: Context): Int {
    val nodes = refreshReachableWatchNodeIds(context)
    nodes.forEach { nodeId ->
        Wearable
            .getMessageClient(context)
            .sendMessage(nodeId, WearProtocol.DIAGNOSTICS_REQUEST_PATH, byteArrayOf())
            .await()
    }
    return nodes.size
}
