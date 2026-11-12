package app.sugarlicious.wear

import android.app.Notification
import android.app.Service
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WearRuntimeLifecycleTest {
    @Test fun `watch placeholder settings cannot be mistaken for an exemption dialog`() {
        assertTrue(WearBackgroundAccess.isPlaceholderSettingsActivity("com.google.android.clockwork.settings.FakeSettingsActivity"))
        assertFalse(WearBackgroundAccess.isPlaceholderSettingsActivity("com.samsung.android.clockwork.settings.StMainSettingsActivity"))
    }

    @Test fun `battery exemption is evaluated after settings result rather than initial resume`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows
            .shadowOf(app)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val power = app.getSystemService(android.os.PowerManager::class.java)
        org.robolectric.Shadows
            .shadowOf(power)
            .setIgnoringBatteryOptimizations(app.packageName, false)
        org.robolectric.shadows.ShadowToast
            .reset()
        val controller = Robolectric.buildActivity(WearActivity::class.java).setup()
        try {
            val activity = controller.get()
            val request =
                org.robolectric.Shadows
                    .shadowOf(activity)
                    .nextStartedActivityForResult
            assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, request.intent.action)
            assertNull(
                org.robolectric.shadows.ShadowToast
                    .getTextOfLatestToast(),
            )
            org.robolectric.Shadows
                .shadowOf(power)
                .setIgnoringBatteryOptimizations(app.packageName, true)
            activity.activityResultRegistry.dispatchResult(request.requestCode, android.app.Activity.RESULT_OK, null)
            assertEquals(
                "Dauerbetrieb ist uneingeschränkt",
                org.robolectric.shadows.ShadowToast
                    .getTextOfLatestToast(),
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun `G7 reading receiver escapes the restricted receiver context`() {
        val application = ApplicationProvider.getApplicationContext<android.content.Context>()
        val receiverContext = ContextWrapper(application)

        assertSame(application, cgmReadingUpdateApplicationContext(receiverContext))
    }

    @Test fun `runtime restores after boot and package replacement only`() {
        assertTrue(shouldRestoreWearRuntime(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(shouldRestoreWearRuntime(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(shouldRestoreWearRuntime(Intent.ACTION_SCREEN_ON))
        assertFalse(shouldRestoreWearRuntime(null))
    }

    @Test fun `battery exemption request is package specific and first choice`() {
        val intents = WearBackgroundAccess.batterySettingsIntents("app.sugarlicious")

        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intents.first().action)
        assertEquals("package:app.sugarlicious", intents.first().dataString)
    }

    @Suppress("DEPRECATION") // Notification sound has no replacement inspection API.
    @Test
    fun `runtime service is sticky and notification is permanent silent service state`() {
        val controller = Robolectric.buildService(StateDataLayerService::class.java).create()
        val service = controller.get()
        val notification = service.runtimeNotification()

        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertFalse(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertNull(notification.sound)

        controller.destroy()
    }
}
