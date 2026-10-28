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
