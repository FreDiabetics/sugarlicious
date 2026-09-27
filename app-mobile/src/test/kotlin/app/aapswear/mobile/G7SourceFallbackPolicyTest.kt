package app.aapswear.mobile

import app.aapswear.model.Freshness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class G7SourceFallbackPolicyTest {
    @Test fun `automatic xDrip fallback is blocked only by usable AAPS freshness`() {
        assertTrue(aapsReadingBlocksAutomaticFallback(Freshness.CURRENT))
        assertTrue(aapsReadingBlocksAutomaticFallback(Freshness.DELAYED))
        assertFalse(aapsReadingBlocksAutomaticFallback(Freshness.STALE))
        assertFalse(aapsReadingBlocksAutomaticFallback(Freshness.SIGNAL_LOSS))
        assertFalse(aapsReadingBlocksAutomaticFallback(Freshness.ERROR))
        assertFalse(aapsReadingBlocksAutomaticFallback(Freshness.NO_DATA))
    }

    @Test fun `legacy forced G7 source migrates to AndroidAPS`() {
        assertEquals(
            DataSourcePreference.ANDROID_APS,
            migrateLegacyForcedG7Source(
                DataSourcePreference.DEXCOM_G7_WATCH,
                migrationDone = false,
            ),
        )
    }

    @Test fun `explicit G7 source cannot remain selected after migration`() {
        assertEquals(
            DataSourcePreference.ANDROID_APS,
            migrateLegacyForcedG7Source(
                DataSourcePreference.DEXCOM_G7_WATCH,
                migrationDone = true,
            ),
        )
    }

    @Test fun `existing AAPS source is not changed`() {
        assertEquals(
            DataSourcePreference.ANDROID_APS,
            migrateLegacyForcedG7Source(
                DataSourcePreference.ANDROID_APS,
                migrationDone = false,
            ),
        )
    }
}
