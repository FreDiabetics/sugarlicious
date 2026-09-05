package app.aapswear.wear

import android.content.Context
import app.aapswear.g7.G7SyncDispatch
import app.aapswear.protocol.G7ReadingAck

/**
 * Direct-to-Watch sensor history is private to the standalone collector.
 *
 * Sugarlicious Wear must never request, transport, acknowledge, persist or render sensor backfill.
 * Backfill is fetched by the Direct-to-Watch collector only and is stored in its local reading DB
 * for repairing gaps in the Direct-to-Watch graph.
 *
 * These no-op entry points remain temporarily for source compatibility with older callers while
 * the transport hooks are removed from the surrounding services.
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
