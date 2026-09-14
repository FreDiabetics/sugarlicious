package app.aapswear.wear

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

internal object WearBackgroundAccess {
    fun isBatteryUnrestricted(context: Context): Boolean =
        context
            .getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    internal fun batterySettingsIntents(packageName: String): List<Intent> {
        val packageUri = Uri.parse("package:$packageName")
        return listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri),
        )
    }

    fun openBatterySettings(activity: Activity): Boolean {
        for (intent in batterySettingsIntents(activity.packageName)) {
            val opened =
                runCatching {
                    activity.startActivity(intent)
                    true
                }.getOrDefault(false)
            if (opened) return true
        }
        return false
    }
}
