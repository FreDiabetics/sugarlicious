package app.aapswear.mobile

import androidx.test.core.app.ApplicationProvider
import app.aapswear.model.SugarliciousComplicationIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComplicationAppearanceSettingsTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `provider override persists independently and reset restores system default`() {
        val id = SugarliciousComplicationIds.GLUCOSE_TREND
        ComplicationAppearanceSettingsStore.save(context, id, ComplicationAppearanceSettings(200, 12, -8))
        assertEquals(ComplicationAppearanceSettings(200, 12, -8), ComplicationAppearanceSettingsStore.load(context, id))
        assertNull(ComplicationAppearanceSettingsStore.load(context, SugarliciousComplicationIds.TREND_ONLY).trendScalePercent)

        ComplicationAppearanceSettingsStore.save(context, id, ComplicationAppearanceSettings())
        assertNull(ComplicationAppearanceSettingsStore.load(context, id).trendScalePercent)
        assertEquals(0, ComplicationAppearanceSettingsStore.load(context, id).trendOffsetXPercent)
    }
}
