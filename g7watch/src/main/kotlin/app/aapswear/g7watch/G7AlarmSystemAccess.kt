package app.aapswear.g7watch

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.Settings

internal enum class G7SettingsOpenResult { OPENED, UNAVAILABLE, FAILED }

internal data class G7AlarmSystemSnapshot(
    val policyAccessGranted: Boolean,
    val notificationPermissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val alarmVolume: Int,
    val alarmVolumeMaximum: Int,
    val ringerMode: Int,
)

internal object G7AlarmSystemAccess {
    fun isAccessGranted(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun snapshot(context: Context): G7AlarmSystemSnapshot {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        return G7AlarmSystemSnapshot(
            policyAccessGranted = notifications.isNotificationPolicyAccessGranted,
            notificationPermissionGranted =
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            appNotificationsEnabled = notifications.areNotificationsEnabled(),
            alarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM),
            alarmVolumeMaximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            ringerMode = audio.ringerMode,
        )
    }

    fun settingsCandidates(activity: Activity): List<Intent> =
        listOf(
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName),
            Intent(Settings.ACTION_SETTINGS),
        )

    fun openPolicySettings(
        activity: Activity,
        canResolve: (Intent) -> Boolean = { it.resolveActivity(activity.packageManager) != null },
        launch: (Intent) -> Unit = activity::startActivity,
    ): G7SettingsOpenResult {
        var attempted = false
        settingsCandidates(activity).forEach { intent ->
            if (!canResolve(intent)) return@forEach
            attempted = true
            if (runCatching { launch(intent) }.isSuccess) return G7SettingsOpenResult.OPENED
        }
        return if (attempted) G7SettingsOpenResult.FAILED else G7SettingsOpenResult.UNAVAILABLE
    }
}
