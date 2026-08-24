package app.aapswear.wear

import android.content.Context
import app.aapswear.g7.G7SyncDispatch
import app.aapswear.protocol.G7ReadingAck

/**
 * Direct G7 Watch readings are local-Watch CGM data and are never backfilled into Sugarlicious
 * Mobile CGM history. The object remains as a source-compatibility shim for older call sites while
 * the transport paths are retired; it deliberately has no pending state and performs no IPC.
 */
internal object G7BackfillSync {
    suspend fun sendPending(
        context: Context,
        targetNodeId: String? = null,
    ): G7SyncDispatch? {
        context.applicationContext
        targetNodeId?.length
        return null
    }

    suspend fun acknowledge(
        context: Context,
        ack: G7ReadingAck,
    ): Int {
        context.applicationContext
        ack.batchId.length
        return 0
    }
}
