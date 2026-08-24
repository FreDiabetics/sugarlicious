package app.aapswear.wear

import app.aapswear.model.DataSourceId
import app.aapswear.model.CgmSourceState
import app.aapswear.protocol.WatchDataSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class G7SourceTransitionTest {
    @Test fun `repeated configuration sync does not retrigger collector`() {
        assertFalse(
            shouldApplyG7CollectorSourceTransition(
                WatchDataSource.DEXCOM_G7_WATCH,
                WatchDataSource.DEXCOM_G7_WATCH,
            ),
        )
        assertFalse(
            shouldApplyG7CollectorSourceTransition(
                WatchDataSource.PHONE,
                WatchDataSource.PHONE,
            ),
        )
    }

    @Test fun `changing selected source triggers collector source control once`() {
        assertTrue(
            shouldApplyG7CollectorSourceTransition(
                WatchDataSource.PHONE,
                WatchDataSource.DEXCOM_G7_WATCH,
            ),
        )
        assertTrue(
            shouldApplyG7CollectorSourceTransition(
                WatchDataSource.DEXCOM_G7_WATCH,
                WatchDataSource.PHONE,
            ),
        )
        assertTrue(
            shouldApplyG7CollectorSourceTransition(
                WatchDataSource.DEXCOM_G7_WATCH,
                WatchDataSource.AUTOMATIC,
            ),
        )
    }

    @Test fun `alerts follow canonical Watch Direct in automatic mode`() {
        assertTrue(shouldEnableG7Alerts(WatchDataSource.AUTOMATIC, DataSourceId.DEXCOM_G7_WATCH, CgmSourceState.WATCH_DIRECT))
        assertFalse(shouldEnableG7Alerts(WatchDataSource.AUTOMATIC, DataSourceId.ANDROID_APS, CgmSourceState.MOBILE_PRIMARY))
        assertTrue(shouldEnableG7Alerts(WatchDataSource.AUTOMATIC, DataSourceId.ANDROID_APS, CgmSourceState.NO_SOURCE))
        assertFalse(shouldEnableG7Alerts(WatchDataSource.AUTOMATIC, null, null))
        assertTrue(shouldEnableG7Alerts(WatchDataSource.DEXCOM_G7_WATCH, null, null))
        assertFalse(shouldEnableG7Alerts(WatchDataSource.PHONE, DataSourceId.DEXCOM_G7_WATCH, CgmSourceState.WATCH_DIRECT))
    }
}
