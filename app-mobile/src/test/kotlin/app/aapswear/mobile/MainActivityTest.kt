package app.aapswear.mobile

import android.graphics.Color
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.mobile.ui.theme.derivedTargetValueArgb
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {

    @Test fun `watch config uses mobile therapy icon colors`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().putString("themeMode", "DARK").commit()
        val iob = Color.rgb(10, 20, 30)
        val cob = Color.rgb(40, 50, 60)
        val basal = Color.rgb(70, 80, 90)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.BLUE, iob)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.ORANGE, cob)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.SECONDARY, basal)

        val colors = readWatchConfig(context).uiColors

        assertEquals(iob, colors.iob)
        assertEquals(cob, colors.cob)
        assertEquals(basal, colors.basal)
    }

    @Test fun `watch config carries direct G7 source selection`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE)
            .edit().clear().putString("dataSource", DataSourcePreference.DEXCOM_G7_WATCH.name).commit()
        assertEquals(app.aapswear.protocol.WatchDataSource.DEXCOM_G7_WATCH, readWatchConfig(context).dataSource)
    }

    @Test fun `app surfaces are neutral gray and system accent follows the icon`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        listOf(R.color.app_background, R.color.app_surface, R.color.app_surface_high, R.color.app_surface_raised, R.color.app_surface_selected).forEach { colorId ->
            val color = context.getColor(colorId)
            assertEquals(Color.red(color), Color.green(color))
            assertEquals(Color.green(color), Color.blue(color))
        }
        assertEquals(Color.rgb(109, 232, 146), context.getColor(R.color.app_accent))
    }

    @Test fun `diagnostics update while overview dashboard stays visible`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val diagnostics = context.getSharedPreferences("diagnostics", android.content.Context.MODE_PRIVATE)
        diagnostics.edit().clear().commit()
        context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE).edit().clear().commit()

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val dashboard = activity.findViewById<ViewGroup>(R.id.dashboard_content)

        assertNotNull(dashboard)
        assertTrue(dashboard.childCount > 0)
        val originalComposeView = dashboard.getChildAt(0)

        diagnostics.edit()
            .putLong("received", 1_000L)
            .putString("contract", "AAPS_EXTENDED_STATUS_V1")
            .putString("sourceVersion", "4.0.0-dev-b")
            .putInt("reachableWatches", 1)
            .putString("lastSyncStatus", "ok")
            .commit()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(dashboard.childCount > 0)
        assertSame(originalComposeView, dashboard.getChildAt(0))

        activity.findViewById<View>(R.id.top_settings).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val settingsText = textOf(activity.findViewById(R.id.dashboard_content))
        assertTrue(settingsText.contains("Datenquelle"))
        assertTrue(settingsText.contains("AndroidAPS"))
        assertTrue(settingsText.contains("Datenverwaltung"))
        assertTrue(settingsText.contains("Dauerbetrieb"))
        assertTrue(settingsText.contains("Einstellungen sichern"))
        assertTrue(settingsText.contains("Einstellungen wiederherstellen"))
        controller.pause().stop().destroy()
    }

    @Test fun `inline settings persist without submenu`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        activity.findViewById<View>(R.id.top_settings).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val details = activity.findViewById<android.widget.Switch>(R.id.dashboard_details_switch)
        assertTrue(details.isChecked)
        details.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(preferences.getBoolean("showDetails", true))

        val live = activity.findViewById<android.widget.Switch>(R.id.dashboard_live_notification_switch)
        assertFalse(live.isChecked)
        live.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(preferences.getBoolean(PersistentBridgeService.PREFERENCE_LIVE_NOTIFICATION, false))

        controller.pause().stop().destroy()
    }

    @Test fun `color store preserves configured transparency`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val configured = Color.argb(96, 84, 223, 48)

        SugarliciousColorStore.save(preferences, SugarliciousColorRole.RANGE_IN_RANGE, configured)

        assertEquals(96, Color.alpha(SugarliciousColorStore.load(preferences).argb(SugarliciousColorRole.RANGE_IN_RANGE)))
    }

    @Test fun `light target value defaults to the opaque graph line color`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().putString("themeMode", "LIGHT").commit()

        val target = SugarliciousColorStore.load(preferences).argb(SugarliciousColorRole.TARGET_VALUE)

        assertEquals(255, Color.alpha(target))
        assertEquals(derivedTargetValueArgb(SugarliciousColorRole.RANGE_IN_RANGE.lightArgb), target)
    }

    @Test fun `cgm dot appearance settings are read from preferences`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE)
        preferences.edit()
            .clear()
            .putFloat("cgm.dotRadiusDp", 4.25f)
            .putBoolean("cgm.dotOutlineEnabled", false)
            .putFloat("cgm.dotOutlineWidthDp", 1.75f)
            .commit()

        val ui = DashboardUiPreferences.read(preferences)

        assertEquals(4.25f, ui.cgmDotRadiusDp, 0.001f)
        assertFalse(ui.cgmDotOutlineEnabled)
        assertEquals(1.75f, ui.cgmDotOutlineWidthDp, 0.001f)
    }

    @Test fun `notification graph defaults are independent from dashboard graph`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE)
        preferences.edit()
            .clear()
            .putInt("graphHours", 24)
            .putBoolean(PersistentBridgeService.PREFERENCE_NOTIFICATION_GRAPH_ENABLED, false)
            .putInt(PersistentBridgeService.PREFERENCE_NOTIFICATION_GRAPH_HOURS, 2)
            .commit()

        val ui = DashboardUiPreferences.read(preferences)

        assertEquals(24, ui.graphHours)
        assertFalse(ui.notificationGraphEnabled)
        assertEquals(2, ui.notificationGraphHours)
    }

    @Test fun `fresh install uses requested overview and CGM defaults`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        val ui = DashboardUiPreferences.read(preferences)
        assertTrue(ui.showCgmGraph)
        assertTrue(ui.showDetails)
        assertTrue(ui.showCgmTargetRange)
        assertTrue(ui.showCgmTargetValue)
        assertFalse(ui.showCgmBasal)
        assertFalse(ui.showCgmActivity)
        assertFalse(ui.anyCgmPredictionEnabled)
        assertFalse(ui.showMetabolicGraph)
        assertTrue(ui.notificationGraphEnabled)
        assertEquals(3, ui.notificationGraphHours)

        controller.pause().stop().destroy()
    }

    @Test fun `saving carousel position does not rebuild the visible dashboard`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().putInt("watchFaceIndex", 1).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val dashboard = activity.findViewById<ViewGroup>(R.id.dashboard_content)
        val originalComposeView = dashboard.getChildAt(0)

        preferences.edit().putInt("watchFaceIndex", 2).commit()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(2, preferences.getInt("watchFaceIndex", -1))
        assertSame(originalComposeView, dashboard.getChildAt(0))
        controller.pause().stop().destroy()
    }

    @Test fun `Sugarlicious about tile shows compact branding and contact pills`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        activity.findViewById<View>(R.id.top_settings).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val settingsText = textOf(activity.findViewById(R.id.dashboard_content))
        assertTrue(settingsText.contains("Sugarlicious"))
        assertFalse(settingsText.contains("typ1.diafreddy@gmail.com"))
        assertFalse(settingsText.contains("FreDiabetics/aaps_wearable-suite"))
        assertFalse(settingsText.contains("Unabhängiges Projekt"))
        assertTrue(settingsText.contains("GitHub"))
        assertTrue(settingsText.contains("E-Mail"))
        assertFalse(settingsText.contains("Watchfaces"))

        activity.findViewById<View>(R.id.dashboard_github).performClick()
        val githubIntent = shadowOf(activity).nextStartedActivity
        assertEquals("github.com", githubIntent.data?.host)

        activity.findViewById<View>(R.id.dashboard_contact_email).performClick()
        val emailIntent = shadowOf(activity).nextStartedActivity
        assertEquals("mailto", emailIntent.data?.scheme)
        assertEquals("typ1.diafreddy@gmail.com", emailIntent.data?.schemeSpecificPart)
        controller.pause().stop().destroy()
    }

    @Test fun `bottom navigation is removed and compact top navigation is available`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertEquals(0, activity.resources.getIdentifier("menu_button", "id", activity.packageName))
        assertEquals(0, activity.resources.getIdentifier("more_button", "id", activity.packageName))
        assertEquals(0, activity.resources.getIdentifier("bottom_navigation", "id", activity.packageName))
        assertNotNull(activity.findViewById<View>(R.id.top_app_bar))
        assertNotNull(activity.findViewById<View>(R.id.top_settings))
        activity.findViewById<View>(R.id.top_settings).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(textOf(activity.findViewById(R.id.dashboard_content)).contains("Einstellungen"))
        controller.pause().stop().destroy()
    }

    @Test fun `settings accordion keeps exact order allows multiple sections and resets after leaving`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.top_settings).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        fun categories(): List<View> {
            val root = activity.findViewById<ViewGroup>(R.id.dashboard_content)
            return (0 until root.childCount)
                .map(root::getChildAt)
                .filter { it.tag?.toString()?.startsWith("settings-category-") == true }
        }
        fun contentAfter(header: View): View {
            val root = activity.findViewById<ViewGroup>(R.id.dashboard_content)
            return root.getChildAt(root.indexOfChild(header) + 1)
        }

        var headers = categories()
        assertEquals(
            listOf("general", "display", "cgm_graph", "notification", "data", "diagnostics", "about"),
            headers.map { it.tag.toString().removePrefix("settings-category-") },
        )
        assertTrue(headers.all { contentAfter(it).visibility == View.GONE })
        assertNotNull(findImageWithDescription(activity.findViewById(R.id.dashboard_content), "Automatisch"))
        assertNotNull(findImageWithDescription(activity.findViewById(R.id.dashboard_content), "AndroidAPS"))
        assertNotNull(findImageWithDescription(activity.findViewById(R.id.dashboard_content), "xDrip+"))
        assertNotNull(findImageWithDescription(activity.findViewById(R.id.dashboard_content), "Dexcom G7 Watch"))

        headers[0].performClick()
        headers[1].performClick()
        assertEquals(View.VISIBLE, contentAfter(headers[0]).visibility)
        assertEquals(View.VISIBLE, contentAfter(headers[1]).visibility)
        headers[0].performClick()
        assertEquals(View.GONE, contentAfter(headers[0]).visibility)
        assertEquals(View.VISIBLE, contentAfter(headers[1]).visibility)

        activity.findViewById<View>(R.id.top_back).performClick()
        activity.findViewById<View>(R.id.top_settings).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        headers = categories()
        assertTrue(headers.all { contentAfter(it).visibility == View.GONE })
        assertTrue(textOf(activity.findViewById(R.id.dashboard_content)).contains("Über"))
        controller.pause().stop().destroy()
    }

    @Test fun `overview is fixed and watch menu has only its inline header`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val scroll = activity.findViewById<DashboardScrollView>(R.id.dashboard_scroll)

        assertFalse(scroll.isUserScrollEnabled)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.scroll_fade).visibility)

        activity.findViewById<View>(R.id.watch_fixed_header)
        activity.javaClass.getDeclaredMethod("navigate", DashboardScreen::class.java).apply {
            isAccessible = true
            invoke(activity, DashboardScreen.WATCH)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(scroll.isUserScrollEnabled)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.top_app_bar).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.watch_fixed_header).visibility)
        controller.pause().stop().destroy()
    }

    @Test
    @Config(sdk = [36])
    fun `live notification switch opens the Android 16 promotion permission when needed`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        activity.findViewById<View>(R.id.top_settings).performClick()
        activity.findViewById<View>(R.id.dashboard_live_notification_switch).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(context.getSharedPreferences("dashboard_ui", android.content.Context.MODE_PRIVATE)
            .getBoolean(PersistentBridgeService.PREFERENCE_LIVE_NOTIFICATION, false))
        val settingsIntent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS, settingsIntent.action)
        assertEquals(activity.packageName, settingsIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        controller.pause().stop().destroy()
    }

    private fun textOf(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { textOf(view.getChildAt(it)) }
        else -> ""
    }

    private fun findImageWithDescription(view: View, description: String): android.widget.ImageView? = when (view) {
        is android.widget.ImageView -> view.takeIf { it.contentDescription?.toString() == description }
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { index ->
            findImageWithDescription(view.getChildAt(index), description)
        }
        else -> null
    }
}
