package app.aapswear.g7watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Applies end-to-end Mobile acknowledgements without exposing a writable reading provider. */
class G7SyncControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ACKNOWLEDGE) return
        val ids = intent.getStringArrayListExtra(EXTRA_READING_IDS).orEmpty()
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_ACK_IDS)
            .toSet()
        if (ids.isEmpty()) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                G7ReadingDatabase(context).let { database ->
                    try {
                        database.markSynced(ids)
                    } finally {
                        database.close()
                    }
                }
                context.applicationContext.recordG7Diagnostic(
                    code = "G7-SYNC-200",
                    message = "Mobile acknowledged persisted G7 history",
                    metadata = mapOf(
                        "batchId" to intent.getStringExtra(EXTRA_BATCH_ID),
                        "acknowledged" to ids.size,
                    ),
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACKNOWLEDGE = "app.aapswear.g7watch.ACK_SYNC"
        const val EXTRA_BATCH_ID = "batch_id"
        const val EXTRA_READING_IDS = "reading_ids"
        private const val MAX_ACK_IDS = 100
    }
}
