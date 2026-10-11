package app.aapswear.mobile

import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.Metric
import androidx.core.app.NotificationCompat.Metric.FixedFloat
import androidx.core.app.NotificationCompat.Metric.FixedInt
import androidx.core.app.NotificationCompat.MetricStyle

internal data class AndroidLiveApplication(
    val promotionRequested: Boolean,
    val shortCriticalText: String?,
    val metric: Metric?,
    val criticalMetricIndex: Int?,
)

internal data class AndroidLiveDiagnostics(
    val apiSupported: Boolean,
    val canPostPromotedNotifications: Boolean?,
    val hasPromotableCharacteristics: Boolean?,
)

internal object AndroidLiveNotificationAdapter {
    fun apply(
        builder: NotificationCompat.Builder,
        presentation: NotificationPresentation,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): AndroidLiveApplication {
        if (sdkInt < 36) return AndroidLiveApplication(false, null, null, null)

        builder.setRequestPromotedOngoing(true)
        val shortText = presentation.androidCapsuleText
        if (!shortText.isNullOrBlank()) builder.setShortCriticalText(shortText)

        val metric = if (sdkInt >= 37) glucoseMetric(presentation) else null
        if (metric != null) {
            builder.setStyle(
                MetricStyle()
                    .addMetric(metric)
                    .setCriticalMetric(0),
            )
        }
        return AndroidLiveApplication(
            promotionRequested = true,
            shortCriticalText = shortText,
            metric = metric,
            criticalMetricIndex = metric?.let { 0 },
        )
    }

    fun diagnostics(
        notificationManager: NotificationManager,
        notification: android.app.Notification?,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): AndroidLiveDiagnostics {
        if (sdkInt < 36) return AndroidLiveDiagnostics(false, null, null)
        return AndroidLiveDiagnostics(
            apiSupported = true,
            canPostPromotedNotifications = notificationManager.canPostPromotedNotifications(),
            hasPromotableCharacteristics = notification?.hasPromotableCharacteristics(),
        )
    }

    private fun glucoseMetric(presentation: NotificationPresentation): Metric? {
        val numeric = presentation.glucoseNumeric ?: return null
        val unit = presentation.unit ?: return null
        val value =
            if (unit == app.aapswear.model.GlucoseUnit.MMOL_L) {
                FixedFloat(numeric.toFloat(), null, 1, 1)
            } else {
                FixedInt(numeric.toInt(), null)
            }
        return Metric(value, presentation.therapyText.orEmpty())
    }
}
