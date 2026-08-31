package app.aapswear.mobile

import android.content.Context
import androidx.core.content.edit
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.protocol.WearProtocol
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

internal data class ComplicationAppearanceSettings(
    val trendScalePercent: Int? = null,
    val trendOffsetXPercent: Int = 0,
    val trendOffsetYPercent: Int = 0,
)

internal object ComplicationAppearanceSettingsStore {
    private const val PREFS = "complication_appearance"

    fun load(context: Context, catalogId: Int): ComplicationAppearanceSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val scaleKey = "$catalogId.trendScale"
        return ComplicationAppearanceSettings(
            trendScalePercent = if (prefs.contains(scaleKey)) {
                prefs.getInt(scaleKey, GlucoseTrendSizing.DEFAULT_SCALE_PERCENT)
                    .coerceIn(GlucoseTrendSizing.MIN_SCALE_PERCENT, GlucoseTrendSizing.MAX_SCALE_PERCENT)
            } else null,
            trendOffsetXPercent = prefs.getInt("$catalogId.trendX", 0).coerceIn(-50, 50),
            trendOffsetYPercent = prefs.getInt("$catalogId.trendY", 0).coerceIn(-50, 50),
        )
    }

    fun save(context: Context, catalogId: Int, value: ComplicationAppearanceSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (value.trendScalePercent == null) remove("$catalogId.trendScale")
            else putInt("$catalogId.trendScale", value.trendScalePercent)
            putInt("$catalogId.trendX", value.trendOffsetXPercent)
            putInt("$catalogId.trendY", value.trendOffsetYPercent)
        }
    }
}

internal suspend fun syncComplicationAppearance(
    context: Context,
    catalogId: Int,
    value: ComplicationAppearanceSettings,
) {
    val request = PutDataMapRequest.create(WearProtocol.COMPLICATION_APPEARANCE_PATH).apply {
        dataMap.putInt("catalogId", catalogId)
        dataMap.putInt("trendScale", value.trendScalePercent ?: 0)
        dataMap.putInt("trendX", value.trendOffsetXPercent)
        dataMap.putInt("trendY", value.trendOffsetYPercent)
        dataMap.putLong("updatedAt", System.currentTimeMillis())
    }.asPutDataRequest().setUrgent()
    Wearable.getDataClient(context).putDataItem(request).await()
}
