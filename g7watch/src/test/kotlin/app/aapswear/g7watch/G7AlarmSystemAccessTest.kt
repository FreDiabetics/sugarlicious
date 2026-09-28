package app.aapswear.g7watch

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class G7AlarmSystemAccessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()

    @Test
    fun `policy settings use public Android actions in fallback order`() {
        val actions = G7AlarmSystemAccess.settingsCandidates(activity).map(Intent::getAction)

        assertEquals(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, actions[0])
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, actions[1])
        assertEquals(Settings.ACTION_SETTINGS, actions[2])
        assertFalse(actions.any { it == "android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS" })
    }

    @Test
    fun `launch failure continues with next resolvable public setting`() {
        val attempted = mutableListOf<String?>()
        val result =
            G7AlarmSystemAccess.openPolicySettings(
                activity = activity,
                canResolve = { true },
                launch = {
                    attempted += it.action
                    if (attempted.size == 1) error("vendor rejected activity")
                },
            )

        assertEquals(G7SettingsOpenResult.OPENED, result)
        assertEquals(
            listOf(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, Settings.ACTION_APP_NOTIFICATION_SETTINGS),
            attempted,
        )
    }

    @Test
    fun `unsupported device is reported when no public setting resolves`() {
        assertEquals(
            G7SettingsOpenResult.UNAVAILABLE,
            G7AlarmSystemAccess.openPolicySettings(activity, canResolve = { false }, launch = {}),
        )
    }

    @Test
    fun `snapshot reads notification permission and actual policy access`() {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        shadowOf(notificationManager).setNotificationPolicyAccessGranted(false)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val denied = G7AlarmSystemAccess.snapshot(context)
        assertFalse(denied.policyAccessGranted)
        assertTrue(denied.notificationPermissionGranted)

        shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        assertTrue(G7AlarmSystemAccess.snapshot(context).policyAccessGranted)
    }
}
