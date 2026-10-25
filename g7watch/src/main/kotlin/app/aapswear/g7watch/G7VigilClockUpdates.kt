package app.aapswear.g7watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import app.aapswear.complications.DirectToWatchAmbientClockComplication
import app.aapswear.complications.DirectToWatchClockComplication
import app.aapswear.complications.DirectToWatchHandsComplication
import app.aapswear.complications.DirectToWatchPreferences
import app.aapswear.complications.VigilHands

/** Refresh bitmap clocks from system time events while the foreground collector owns the runtime. */
internal class G7VigilClockUpdates(
    private val context: Context,
    private val requestUpdate: (Class<*>) -> Unit = { provider ->
        if (provider == DirectToWatchHandsComplication::class.java) {
            VigilHands.requestUpdate(context)
        } else {
            DirectToWatchPreferences.requestClockUpdate(context, provider)
        }
    },
) {
    private var registered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action in actions) refresh()
            }
        }

    fun start() {
        if (registered) return
        context.registerReceiver(receiver, IntentFilter().apply { actions.forEach(::addAction) }, Context.RECEIVER_NOT_EXPORTED)
        registered = true
        refresh()
    }

    fun stop() {
        if (!registered) return
        context.unregisterReceiver(receiver)
        registered = false
    }

    private fun refresh() {
        requestUpdate(DirectToWatchClockComplication::class.java)
        requestUpdate(DirectToWatchAmbientClockComplication::class.java)
        requestUpdate(DirectToWatchHandsComplication::class.java)
    }

    private companion object {
        val actions = setOf(Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
    }
}
