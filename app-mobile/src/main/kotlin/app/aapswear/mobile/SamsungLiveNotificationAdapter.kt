package app.aapswear.mobile

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.core.app.NotificationCompat

internal data class SamsungLiveTexts(
    val drawerPrimary: String,
    val drawerSecondary: String?,
    val nowBarPrimary: String,
    val nowBarSecondary: String?,
    val chipExpandedText: String,
)

internal object SamsungLiveNotificationAdapter {
    const val EXTRA_STYLE = "android.ongoingActivityNoti.style"
    const val EXTRA_PRIMARY_INFO = "android.ongoingActivityNoti.primaryInfo"
    const val EXTRA_SECONDARY_INFO = "android.ongoingActivityNoti.secondaryInfo"
    const val EXTRA_CHIP_ICON = "android.ongoingActivityNoti.chipIcon"
    const val EXTRA_CHIP_EXPANDED_TEXT = "android.ongoingActivityNoti.chipExpandedText"
    const val EXTRA_NOWBAR_PRIMARY_INFO = "android.ongoingActivityNoti.nowbarPrimaryInfo"
    const val EXTRA_NOWBAR_SECONDARY_INFO = "android.ongoingActivityNoti.nowbarSecondaryInfo"

    private const val STANDARD_STYLE = 1

    fun apply(
        context: Context,
        builder: NotificationCompat.Builder,
        presentation: NotificationPresentation,
        manufacturer: String,
        notificationIconRes: Int,
    ): SamsungLiveTexts? {
        if (!manufacturer.equals("samsung", ignoreCase = true)) return null
        val texts = texts(presentation)
        val extras =
            Bundle().apply {
                putInt(EXTRA_STYLE, STANDARD_STYLE)
                putString(EXTRA_PRIMARY_INFO, texts.drawerPrimary)
                putString(EXTRA_NOWBAR_PRIMARY_INFO, texts.nowBarPrimary)
                putString(EXTRA_CHIP_EXPANDED_TEXT, texts.chipExpandedText)
                putParcelable(EXTRA_CHIP_ICON, Icon.createWithResource(context, notificationIconRes))
                texts.drawerSecondary?.let { putString(EXTRA_SECONDARY_INFO, it) }
                texts.nowBarSecondary?.let { putString(EXTRA_NOWBAR_SECONDARY_INFO, it) }
            }
        builder.addExtras(extras)
        return texts
    }

    fun texts(presentation: NotificationPresentation): SamsungLiveTexts {
        val primary = presentation.compactPrimaryText ?: presentation.statusText ?: "Keine Quelle"
        return SamsungLiveTexts(
            drawerPrimary = presentation.drawerPrimaryText,
            drawerSecondary = presentation.therapyText,
            nowBarPrimary = primary,
            nowBarSecondary = presentation.therapyText,
            chipExpandedText = primary,
        )
    }
}
