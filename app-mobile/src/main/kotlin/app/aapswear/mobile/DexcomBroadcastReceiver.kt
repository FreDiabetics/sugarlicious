package app.aapswear.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.aapswear.datasource.dexcom.DexcomBroadcastAdapter
import kotlinx.coroutines.*

/** The patched G7 app targets receivers holding its external-broadcast permission. */
class DexcomBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DexcomBroadcastAdapter.ACTION) return
        if (MobileGlucoseSource.DEXCOM_BROADCAST !in MobileSourceConfigurationStore.read(context).enabledGlucoseSources) return
        val extras = intent.extras ?: return
        val now = System.currentTimeMillis()
        val state = DexcomBroadcastAdapter.parse(extras, now) ?: return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000L) {
                    MobileCanonicalStateCoordinator.savePhoneInput(app, state, now)
                    PersistentBridgeService.start(app)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                app.recordMobileDiagnostic("SOURCE", "SRC-DEXCOM-500", "Dexcom broadcast could not be committed")
            } finally {
                pending.finish()
            }
        }
    }
}
