package app.aapswear.complications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import app.aapswear.protocol.DirectToWatchSettingsContract

class DirectToWatchSettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DirectToWatchSettingsContract.ACTION_APPLY) return
        val values = (if (Build.VERSION.SDK_INT >= 33) {
            intent.getBundleExtra(DirectToWatchSettingsContract.EXTRA_VALUES)
        } else {
            @Suppress("DEPRECATION")
            intent.getBundleExtra(DirectToWatchSettingsContract.EXTRA_VALUES)
        }) ?: Bundle.EMPTY
        val preferences = context.getSharedPreferences(DirectToWatchPreferences.NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().apply {
            values.keySet().forEach { key -> when (val value = values.get(key)) {
                is Int -> putInt(key, value); is Float -> putFloat(key, value); is Boolean -> putBoolean(key, value); is String -> putString(key, value)
            } }
        }.apply()
        DirectToWatchPreferences.requestUpdates(context)
    }
}
