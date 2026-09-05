package app.aapswear.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal fun g7ReadingUpdateApplicationContext(context: Context): Context = context.applicationContext

/** Process-local invalidation bus retained for binary/source compatibility. */
internal object WearCanonicalStateEvents {
    private val mutableUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val updates = mutableUpdates.asSharedFlow()

    fun publishLocalReadingUpdate() {
        mutableUpdates.tryEmit(Unit)
    }
}

/**
 * Compatibility receiver only.
 *
 * Direct-to-Watch is a separate local collector application. Its LIVE and BACKFILL readings do not
 * enter Sugarlicious Wear, do not refresh Sugarlicious graphs/tiles/complications, and are not
 * forwarded to Mobile. Sugarlicious Wear is refreshed exclusively by its normal phone data layer.
 */
class G7ReadingUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_G7_READING_UPDATED) return
        // Intentionally no-op: Direct-to-Watch data is collector-local.
        context.applicationContext
    }

    private companion object {
        const val ACTION_G7_READING_UPDATED = "app.aapswear.g7watch.READING_UPDATED"
    }
}
