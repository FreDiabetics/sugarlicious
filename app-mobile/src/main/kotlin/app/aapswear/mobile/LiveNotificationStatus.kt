package app.aapswear.mobile

import android.app.NotificationManager
import android.content.Context
import android.os.Build

internal data class LiveNotificationStatus(
    val device: String,
    val samsungNowBar: String,
    val androidLiveUpdates: String,
    val promotedNotifications: String,
    val hardwareValidation: String,
)

internal fun liveNotificationStatus(
    liveRequested: Boolean,
    sdkInt: Int,
    manufacturer: String,
    canPostPromoted: Boolean?,
): LiveNotificationStatus {
    val samsung = manufacturer.equals("samsung", ignoreCase = true)
    return LiveNotificationStatus(
        device = if (samsung) "Samsung" else manufacturer.ifBlank { "Unbekannt" },
        samsungNowBar =
            when {
                !samsung -> "nicht verfügbar"
                liveRequested -> "angefordert"
                else -> "aus"
            },
        androidLiveUpdates = if (sdkInt >= 36) "unterstützt" else "nicht unterstützt",
        promotedNotifications =
            when (canPostPromoted) {
                true -> "erlaubt"
                false -> "nicht erlaubt"
                null -> "nicht verfügbar"
            },
        hardwareValidation = "nicht durchgeführt",
    )
}

internal fun liveNotificationStatus(
    context: Context,
    liveRequested: Boolean,
): LiveNotificationStatus =
    liveNotificationStatus(
        liveRequested = liveRequested,
        sdkInt = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER.orEmpty(),
        canPostPromoted =
            if (Build.VERSION.SDK_INT >= 36) {
                context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
            } else {
                null
            },
    )
