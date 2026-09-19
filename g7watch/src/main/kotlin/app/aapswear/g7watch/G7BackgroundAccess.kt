package app.aapswear.g7watch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

internal object G7BackgroundAccess {
    fun isBatteryUnrestricted(context: Context): Boolean =
        context
            .getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    internal fun batterySettingsIntents(packageName: String): List<Intent> {
        val packageUri = "package:$packageName".toUri()
        return listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri),
            Intent(Settings.ACTION_SETTINGS),
        )
    }

    /**
     * Opens the first battery/background settings surface that exists on the current Wear build.
     * Grant state is never faked locally; the Activity re-reads PowerManager on resume.
     */
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
