package app.aapswear.g7watch

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.aapswear.g7.CgmAlarmType
import app.aapswear.g7.CgmAlarmSettings
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7Sensor
import app.aapswear.model.DataSourceId
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class G7CgmAlarmsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notificationManager by lazy { context.getSystemService(NotificationManager::class.java) }

    @Before
    fun setUp() {
        context.getSharedPreferences("g7_cgm_alarm_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("g7_cgm_alarm_settings", Context.MODE_PRIVATE).edit().clear().commit()
        G7AlertPolicyStore.setPolicy(context, true)
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        G7CgmAlarmCoordinator.clearSuppressed(context)
        G7AlertPolicyStore.setPolicy(context, false)
    }

    @Test
    fun `active notification is restored after process restart`() {
        G7CgmAlarmCoordinator.onReading(context, reading("sensor-a", "session-a", 65.0), NOW)
        notificationManager.cancel(LOW_NOTIFICATION_ID)
        assertNull(shadowOf(notificationManager).getNotification(LOW_NOTIFICATION_ID))

        G7CgmAlarmCoordinator.restore(context, NOW + 1_000L)

        assertNotNull(shadowOf(notificationManager).getNotification(LOW_NOTIFICATION_ID))
    }

    @Test
    fun `sensor session change cancels alarms from the previous sensor`() {
        G7CgmAlarmCoordinator.onReading(context, reading("sensor-a", "session-a", 65.0), NOW)
        assertNotNull(shadowOf(notificationManager).getNotification(LOW_NOTIFICATION_ID))

        G7CgmAlarmCoordinator.onReading(
            context,
            reading("sensor-b", "session-b", 110.0),
            NOW + 5 * 60_000L,
        )

        assertNull(shadowOf(notificationManager).getNotification(LOW_NOTIFICATION_ID))
    }

    @Test
    fun `invalid packet does not resolve an alarm from the last valid reading`() {
        G7CgmAlarmCoordinator.onReading(context, reading("sensor-a", "session-a", 65.0), NOW)

        G7CgmAlarmCoordinator.onReading(
            context,
            reading("sensor-a", "session-a", 0.0).copy(status = CgmReadingStatus.INVALID),
            NOW + 5 * 60_000L,
        )

        assertNotNull(shadowOf(notificationManager).getNotification(LOW_NOTIFICATION_ID))
    }

    @Test
    fun `urgent low threshold remains forty even with stale persisted preferences`() {
        context.getSharedPreferences("g7_cgm_alarm_settings", Context.MODE_PRIVATE)
            .edit()
            .putFloat("very_low", 30f)
            .commit()

        assertEquals(40.0, G7AlarmSettingsStore.read(context).veryLowThreshold, 0.0)
    }

    @Test
    fun `alarm preferences round trip without changing fixed safety boundaries`() {
        G7AlarmSettingsStore.write(
            context,
            CgmAlarmSettings(
                veryHighThreshold = 280.0,
                highThreshold = 190.0,
                lowThreshold = 75.0,
                veryLowThreshold = 40.0,
                rapidRiseThreshold = 3.0,
                rapidFallThreshold = 2.5,
                signalLossMinutes = 16,
                soundEnabled = false,
                vibrationEnabled = false,
                repeatIntervalMinutes = 30,
            ),
        )

        val restored = G7AlarmSettingsStore.read(context)
        assertEquals(280.0, restored.veryHighThreshold, 0.0)
        assertEquals(190.0, restored.highThreshold, 0.0)
        assertEquals(75.0, restored.lowThreshold, 0.0)
        assertEquals(40.0, restored.veryLowThreshold, 0.0)
        assertEquals(16, restored.signalLossMinutes)
        assertEquals(30, restored.repeatIntervalMinutes)
        assertEquals(false, restored.soundEnabled)
        assertEquals(false, restored.vibrationEnabled)
    }

    @Test
    fun `corrupt alarm thresholds are normalized before engine construction`() {
        context.getSharedPreferences("g7_cgm_alarm_settings", Context.MODE_PRIVATE)
            .edit()
            .putFloat("very_high", 100f)
            .putFloat("high", 900f)
            .putFloat("low", 900f)
            .putFloat("rapid_rise", -4f)
            .commit()

        val restored = G7AlarmSettingsStore.read(context)
        assertEquals(true, restored.veryHighThreshold > restored.highThreshold)
        assertEquals(true, restored.lowThreshold > restored.veryLowThreshold)
        assertEquals(0.5, restored.rapidRiseThreshold, 0.0)
    }

    @Test
    fun `automatic suppression expires when the canonical Mobile timeout is reached`() {
        G7AlertPolicyStore.setPolicy(context, false, NOW + 1_000L)

        assertEquals(false, G7AlertPolicyStore.alarmsEnabled(context, NOW))
        assertEquals(NOW + 1_000L, G7AlertPolicyStore.nextAutomaticEnableAt(context, NOW))
        assertEquals(true, G7AlertPolicyStore.alarmsEnabled(context, NOW + 1_000L))
        assertNull(G7AlertPolicyStore.nextAutomaticEnableAt(context, NOW + 1_000L))
    }

    @Test
    fun `enabling Watch alarms evaluates the reading that caused source failover`() {
        val transitionReading =
            reading("sensor-a", "session-a", 65.0).copy(
                timestampEpochMs = System.currentTimeMillis(),
                receivedAtEpochMs = System.currentTimeMillis(),
            )
        G7AlertPolicyStore.setPolicy(context, false)
        G7SensorStateStore(context).save(
            G7PersistedState(
                sensor = G7Sensor(sensorId = "sensor-a", sessionId = "session-a"),
                collectorEnabled = false,
                lastReading = transitionReading,
            ),
        )

        G7SourceControlReceiver().onReceive(
            context,
            Intent(G7SourceControlReceiver.ACTION_SET_SOURCE)
                .putExtra(G7SourceControlReceiver.EXTRA_G7_SELECTED, false)
                .putExtra(G7SourceControlReceiver.EXTRA_ALARMS_ENABLED, true),
        )

        assertNotNull(shadowOf(notificationManager).getNotification(LOW_NOTIFICATION_ID))
    }

    private fun reading(sensorId: String, sessionId: String, glucose: Double) =
        CgmReading(
            id = "$sensorId-$sessionId-$glucose",
            source = DataSourceId.DEXCOM_G7_WATCH,
            sensorId = sensorId,
            sessionId = sessionId,
            glucoseMgDl = glucose,
            timestampEpochMs = NOW,
            receivedAtEpochMs = NOW,
        )

    private companion object {
        const val NOW = 1_800_000_000_000L
        val LOW_NOTIFICATION_ID = 7_100 + CgmAlarmType.LOW.ordinal
    }
}
