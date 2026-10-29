package app.aapswear.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MobileSourceConfigurationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)

    @Before fun prepare() {
        prefs.edit().clear().commit()
    }

    @Test fun `Nightscout treatment-only source remains active without enabling glucose after master restore`() {
        context
            .getSharedPreferences("nightscout_treatment_config", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", true)
            .putString("url", "https://example.org")
            .commit()
        MobileSourceConfigurationStore.setNightscoutEnabled(context, false)
        assertFalse(NightscoutConfigurationStore.read(context).enabled)
        MobileSourceConfigurationStore.setNightscoutEnabled(context, true)
        assertTrue(NightscoutConfigurationStore.read(context).enabled)
        assertFalse(MobileGlucoseSource.NIGHTSCOUT in MobileSourceConfigurationStore.read(context).enabledGlucoseSources)
    }

    @Test fun `old configuration enabling both Dexcom streams resolves to the displayed Share choice`() {
        prefs.edit().putStringSet("source.glucose.enabled", setOf("ANDROID_APS", "DEXCOM_SHARE", "DEXCOM_BROADCAST")).commit()
        assertEquals(setOf(MobileGlucoseSource.ANDROID_APS, MobileGlucoseSource.DEXCOM_SHARE), MobileSourceConfigurationStore.read(context).enabledGlucoseSources)
    }

    @Test fun `Dexcom selector is exclusive and master restores choice without disabling therapy`() {
        MobileSourceConfigurationStore.setDexcom(context, MobileGlucoseSource.DEXCOM_SHARE, true)
        MobileSourceConfigurationStore.setDexcom(context, MobileGlucoseSource.DEXCOM_BROADCAST, true)
        assertEquals(setOf(MobileGlucoseSource.ANDROID_APS, MobileGlucoseSource.DEXCOM_BROADCAST), MobileSourceConfigurationStore.read(context).enabledGlucoseSources)
        MobileSourceConfigurationStore.setDexcom(context, MobileGlucoseSource.DEXCOM_BROADCAST, false)
        assertEquals(MobileGlucoseSource.DEXCOM_BROADCAST, MobileSourceConfigurationStore.preferredDexcom(context))
        assertTrue(MobileSourceConfigurationStore.read(context).aapsTherapyEnabled)
    }

    @Test fun `source master preserves other sources and restores its field choices`() {
        prefs
            .edit()
            .putStringSet("source.glucose.enabled", setOf("ANDROID_APS", "DEXCOM_SHARE"))
            .putBoolean("source.aaps.therapy", true)
            .commit()
        MobileSourceConfigurationStore.setAndroidApsEnabled(context, false)
        val disabled = MobileSourceConfigurationStore.read(context)
        assertEquals(setOf(MobileGlucoseSource.DEXCOM_SHARE), disabled.enabledGlucoseSources)
        assertFalse(disabled.aapsTherapyEnabled)
        MobileSourceConfigurationStore.setAndroidApsEnabled(context, true)
        val restored = MobileSourceConfigurationStore.read(context)
        assertEquals(setOf(MobileGlucoseSource.ANDROID_APS, MobileGlucoseSource.DEXCOM_SHARE), restored.enabledGlucoseSources)
        assertTrue(restored.aapsTherapyEnabled)
    }

    @Test fun `therapy-only AndroidAPS selection remains therapy-only after master toggle`() {
        prefs
            .edit()
            .putStringSet("source.glucose.enabled", setOf("DEXCOM_SHARE"))
            .putBoolean("source.aaps.therapy", true)
            .commit()
        MobileSourceConfigurationStore.setAndroidApsEnabled(context, false)
        MobileSourceConfigurationStore.setAndroidApsEnabled(context, true)
        val restored = MobileSourceConfigurationStore.read(context)
        assertFalse(MobileGlucoseSource.ANDROID_APS in restored.enabledGlucoseSources)
        assertTrue(restored.aapsTherapyEnabled)
    }
}
