package app.aapswear.mobile

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.Metric.FixedFloat
import androidx.core.app.NotificationCompat.Metric.FixedInt
import androidx.test.core.app.ApplicationProvider
import app.aapswear.model.CarbState
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.InsulinState
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LiveNotificationAdaptersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [35])
    fun `api 35 does not request official android live update`() {
        val builder = builder()

        val result = AndroidLiveNotificationAdapter.apply(builder, presentation(), 35)
        val notification = builder.build()

        assertFalse(result.promotionRequested)
        assertNull(result.metric)
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
    }

    @Test
    @Config(sdk = [36])
    fun `api 36 requests promotion and puts glucose plus trend in capsule`() {
        val builder = builder()

        val result = AndroidLiveNotificationAdapter.apply(builder, presentation(), 36)
        val notification = builder.build()

        assertTrue(result.promotionRequested)
        assertEquals("118 ↘", result.shortCriticalText)
        assertNull(result.metric)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
    }

    @Test
    @Config(sdk = [37])
    fun `api 37 uses fixed integer glucose as critical metric`() {
        val builder = builder()

        val result = AndroidLiveNotificationAdapter.apply(builder, presentation(), 37)
        val metricValue = result.metric!!.value

        assertTrue(metricValue is FixedInt)
        assertEquals(118, (metricValue as FixedInt).value)
        assertEquals(0, result.criticalMetricIndex)
        assertTrue(builder.build().hasPromotableCharacteristics())
    }

    @Test
    @Config(sdk = [37])
    fun `api 37 integer metric uses the same rounding as the glucose title`() {
        val builder = builder()
        val rounded = state().copy(glucose = state().glucose!!.copy(valueMgDl = 118.8))

        val result = AndroidLiveNotificationAdapter.apply(builder, notificationPresentation(rounded, NOW), 37)

        assertEquals("119", notificationPresentation(rounded, NOW).glucoseText)
        assertEquals(119, (result.metric!!.value as FixedInt).value)
    }

    @Test
    @Config(sdk = [37])
    fun `api 37 mmol metric retains one fraction digit`() {
        val builder = builder()
        val state = state().copy(glucose = state().glucose!!.copy(valueMgDl = 162.0, displayUnit = GlucoseUnit.MMOL_L))

        val result = AndroidLiveNotificationAdapter.apply(builder, notificationPresentation(state, NOW, GlucoseUnit.MMOL_L), 37)
        val metricValue = result.metric!!.value as FixedFloat

        assertEquals(9.0f, metricValue.value, 0f)
        assertEquals(1, metricValue.minFractionDigits)
        assertEquals(1, metricValue.maxFractionDigits)
    }

    @Test
    @Config(sdk = [36])
    fun `unknown trend is not replaced by flat in capsule`() {
        val unknown = state().copy(glucose = state().glucose!!.copy(trend = Trend.UNKNOWN))
        val builder = builder()

        val result = AndroidLiveNotificationAdapter.apply(builder, notificationPresentation(unknown, NOW), 36)

        assertEquals("118", result.shortCriticalText)
    }

    @Test
    @Config(sdk = [35])
    fun `samsung adapter writes all supported extras independently of android promotion`() {
        val builder = builder()

        val texts = SamsungLiveNotificationAdapter.apply(context, builder, presentation(), "Samsung", android.R.drawable.ic_dialog_info)
        val notification = builder.build()

        assertEquals("118 ↘ −6 · Basal 0.45U/h", texts!!.drawerPrimary)
        assertEquals("IOB 1.30U · COB 22g", texts.drawerSecondary)
        assertEquals("118 ↘ −6", texts.nowBarPrimary)
        assertEquals("IOB 1.30U · COB 22g", texts.nowBarSecondary)
        assertEquals("118 ↘ −6", texts.chipExpandedText)
        assertEquals(1, notification.extras.getInt(SamsungLiveNotificationAdapter.EXTRA_STYLE))
        assertEquals(texts.drawerPrimary, notification.extras.getString(SamsungLiveNotificationAdapter.EXTRA_PRIMARY_INFO))
        assertEquals(texts.drawerSecondary, notification.extras.getString(SamsungLiveNotificationAdapter.EXTRA_SECONDARY_INFO))
        assertEquals(texts.nowBarPrimary, notification.extras.getString(SamsungLiveNotificationAdapter.EXTRA_NOWBAR_PRIMARY_INFO))
        assertEquals(texts.nowBarSecondary, notification.extras.getString(SamsungLiveNotificationAdapter.EXTRA_NOWBAR_SECONDARY_INFO))
        assertEquals(texts.chipExpandedText, notification.extras.getString(SamsungLiveNotificationAdapter.EXTRA_CHIP_EXPANDED_TEXT))
        assertEquals(
            context.getColor(R.color.app_accent),
            notification.extras.getInt(SamsungLiveNotificationAdapter.EXTRA_CHIP_BACKGROUND_COLOR),
        )
        assertNotNull(notification.extras.getParcelable(SamsungLiveNotificationAdapter.EXTRA_CHIP_ICON, Icon::class.java))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
    }

    @Test
    @Config(sdk = [37])
    fun `non samsung device receives no proprietary extras`() {
        val builder = builder()

        val texts = SamsungLiveNotificationAdapter.apply(context, builder, presentation(), "Google", android.R.drawable.ic_dialog_info)
        val notification = builder.build()

        assertNull(texts)
        assertFalse(notification.extras.containsKey(SamsungLiveNotificationAdapter.EXTRA_STYLE))
        assertFalse(notification.extras.containsKey(SamsungLiveNotificationAdapter.EXTRA_PRIMARY_INFO))
    }

    private fun builder(): NotificationCompat.Builder =
        NotificationCompat
            .Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentTitle("Sugarlicious")

    private fun presentation(): NotificationPresentation = notificationPresentation(state(), NOW)

    private fun state(): TherapyDisplayState =
        TherapyDisplayState(
            receivedAtEpochMs = NOW,
            glucose =
                GlucoseState(
                    valueMgDl = 118.0,
                    displayUnit = GlucoseUnit.MG_DL,
                    trend = Trend.FORTY_FIVE_DOWN,
                    measuredAtEpochMs = NOW,
                    deltaMgDl = -6.0,
                ),
            insulin = InsulinState(totalIob = 1.3),
            carbs = CarbState(cobGrams = 22.0),
            basal = app.aapswear.model.BasalState(currentUnitsPerHour = 0.45),
        )

    private companion object {
        const val NOW = 2_000_000L
    }
}
