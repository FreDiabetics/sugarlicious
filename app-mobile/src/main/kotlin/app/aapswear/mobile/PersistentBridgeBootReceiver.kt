package app.aapswear.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.aapswear.storage.TherapyStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun shouldRestorePersistentBridge(action: String?): Boolean =
    action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED

class PersistentBridgeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (shouldRestorePersistentBridge(intent.action)) {
            PersistentBridgeService.start(context)
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    // Reading is intentional: restoration must not rewrite timestamps, resolver
                    // counters, or turn the persisted snapshot into a new measurement event.
                    TherapyStateStore(context.applicationContext).state.first()
                    SugarliciousWidgets.update(context.applicationContext)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
