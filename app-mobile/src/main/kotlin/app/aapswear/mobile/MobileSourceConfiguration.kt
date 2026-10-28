package app.aapswear.mobile

import android.content.Context
import androidx.core.content.edit
import app.aapswear.model.DataSourceId

internal enum class MobileGlucoseSource(
    val sourceId: DataSourceId?,
    val label: String,
) {
    NONE(null, "Keine"),
    ANDROID_APS(DataSourceId.ANDROID_APS, "AndroidAPS"),
    DEXCOM_BROADCAST(DataSourceId.DEXCOM_G7_APP, "Dexcom G7 Broadcast"),
    DEXCOM_SHARE(DataSourceId.DEXCOM_SHARE, "Dexcom Share"),
}

internal data class MobileSourceConfiguration(
    val glucoseSource: MobileGlucoseSource = MobileGlucoseSource.ANDROID_APS,
    val aapsTherapyEnabled: Boolean = true,
    val shareRegion: String = "ous",
    val credentialsRevision: Long = 0L,
    val enabledGlucoseSources: Set<MobileGlucoseSource> = setOf(glucoseSource).filter { it != MobileGlucoseSource.NONE }.toSet(),
)

internal object MobileSourceConfigurationStore {
    const val PREFIX = "source."

    private fun preferences(context: Context) = context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)

    fun read(context: Context): MobileSourceConfiguration {
        val prefs = preferences(context)
        return MobileSourceConfiguration(
            runCatching { MobileGlucoseSource.valueOf(prefs.getString("source.glucose", "ANDROID_APS")!!) }.getOrDefault(MobileGlucoseSource.ANDROID_APS),
            prefs.getBoolean("source.aaps.therapy", true),
            prefs.getString("source.share.region", "ous").takeIf { it in setOf("us", "ous") } ?: "ous",
            prefs.getLong("source.share.credentials_revision", 0L),
            prefs.getStringSet("source.glucose.enabled", null)?.mapNotNull { name -> MobileGlucoseSource.entries.find { it.name == name && it != MobileGlucoseSource.NONE } }?.toSet()
                ?: setOf(runCatching { MobileGlucoseSource.valueOf(prefs.getString("source.glucose", "ANDROID_APS")!!) }.getOrDefault(MobileGlucoseSource.ANDROID_APS)).filter { it != MobileGlucoseSource.NONE }.toSet(),
        )
    }

    fun setGlucoseEnabled(context: Context, source: MobileGlucoseSource, enabled: Boolean) {
        require(source != MobileGlucoseSource.NONE)
        val current = read(context).enabledGlucoseSources
        val next = if (enabled) current + source else current - source
        preferences(context).edit { putStringSet("source.glucose.enabled", next.map { it.name }.toSet()) }
        PersistentBridgeService.start(context)
    }

    fun setAndroidApsEnabled(context: Context, enabled: Boolean) {
        val prefs = preferences(context)
        val current = read(context)
        val next = current.enabledGlucoseSources.toMutableSet()
        prefs.edit {
            if (!enabled) {
                putBoolean("source.aaps.previous_glucose", MobileGlucoseSource.ANDROID_APS in next)
                putBoolean("source.aaps.previous_therapy", current.aapsTherapyEnabled)
                next.remove(MobileGlucoseSource.ANDROID_APS)
                putBoolean("source.aaps.therapy", false)
            } else {
                val restoreGlucose = prefs.getBoolean("source.aaps.previous_glucose", true)
                val restoreTherapy = prefs.getBoolean("source.aaps.previous_therapy", true)
                if (restoreGlucose || !restoreTherapy) next.add(MobileGlucoseSource.ANDROID_APS)
                putBoolean("source.aaps.therapy", restoreTherapy)
            }
            putStringSet("source.glucose.enabled", next.map { it.name }.toSet())
        }
        PersistentBridgeService.start(context)
    }

    fun setTherapy(context: Context, enabled: Boolean) {
        preferences(context).edit { putBoolean("source.aaps.therapy", enabled) }
        PersistentBridgeService.start(context)
    }
}
