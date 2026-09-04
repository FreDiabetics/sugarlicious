package app.aapswear.g7watch

import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.g7.G7Sensor
import app.aapswear.model.DataSourceId
import app.aapswear.model.AppearanceMode
import app.aapswear.model.CgmThresholds
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.Trend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class G7WatchActivityLayoutTest {
    @Before
    fun resetGraphPeriod() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        G7AppearanceStore(context).setGraphHours(3)
        val seed = CgmReading(
            id = "layout-seed", source = DataSourceId.DEXCOM_G7_WATCH,
            sensorId = "layout-sensor", sessionId = "layout-session", glucoseMgDl = 120.0,
            timestampEpochMs = System.currentTimeMillis(), receivedAtEpochMs = System.currentTimeMillis(),
            status = CgmReadingStatus.VALID,
        )
        G7SensorStateStore(context).save(
            G7SensorStateStore(context).read().copy(
                sensor = G7Sensor("layout-sensor", "layout-session"),
                lastReading = seed,
            ),
        )
    }

    @Test
    fun `collector settings keep live status above eight grouped sections`() {
        val activity = Robolectric.buildActivity(G7SettingsActivity::class.java).setup().get()
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        val headers = mutableListOf<android.view.View>()

        fun collect(view: android.view.View) {
            if (view.tag?.toString()?.startsWith("settings-category-") == true) headers += view
            if (view is ViewGroup) (0 until view.childCount).forEach { collect(view.getChildAt(it)) }
        }
        collect(root)

        assertNotNull(findText(root, "LIVE COLLECTOR STATUS"))
        assertEquals(
            G7SettingsSection.entries.map { "settings-category-${it.name.lowercase()}" },
            headers.map { it.tag.toString() },
        )
        assertTrue(headers.all { it.minimumHeight >= (48 * activity.resources.displayMetrics.density).toInt() })
        activity.finish()
    }

    @Test
    fun `alarm settings menu opens alarm configuration and not system status`() {
        val activity = Robolectric.buildActivity(G7SettingsActivity::class.java).setup().get()
        val root = activity.findViewById<android.view.View>(android.R.id.content)

        val alarmHeaderText = findText(root, "Alarme")!!
        (alarmHeaderText.parent.parent as android.view.View).performClick()

        val intent = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(G7AlarmSettingsActivity::class.java.name, intent.component?.className)
        assertFalse(intent.component?.className == G7SystemStatusActivity::class.java.name)
        activity.finish()
    }

    @Test
    fun `direct to watch category opens complete watchface settings`() {
        val settings = Robolectric.buildActivity(G7SettingsActivity::class.java).setup().get()
        val root = settings.findViewById<android.view.View>(android.R.id.content)
        val header = findText(root, "Direct to Watch")!!
        (header.parent.parent as android.view.View).performClick()
        assertEquals(G7DirectToWatchSettingsActivity::class.java.name, Shadows.shadowOf(settings).nextStartedActivity.component?.className)

        val activity = Robolectric.buildActivity(G7DirectToWatchSettingsActivity::class.java).setup().get()
        val texts = mutableListOf<String>()
        collectText(activity.findViewById(android.R.id.content), texts)
        assertTrue("Watchface" in texts)
        assertTrue(texts.any { it.startsWith("Größe ·") })
        assertTrue("LOW-Bereich" in texts)
        assertTrue("Zielbereich" in texts)
        assertTrue("HIGH-Bereich" in texts)
        assertTrue("Eckenrundung · 20.0 dp" in texts)
        assertTrue("Graphkontur" in texts)
        assertTrue("Zeitachsenskala" in texts)
        assertTrue("Zuckerwert fett" in texts)
        assertFalse("Horizontale Zielwert-Striche" in texts)
        assertTrue("Kontur · bisherige Punkte" in texts)
        assertTrue("Kontur · aktueller Wert" in texts)
        assertFalse("Range-Hintergrundfärbung" in texts)
        assertTrue("Prognose · Zero Temp" in texts)
        assertTrue("GLUKOSE · EINHEIT" in texts)
        assertTrue("GLUKOSE · ZIELBEREICH" in texts)
    }

    @Test
    fun `direct to watch keeps independent unit and target range`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(app.aapswear.protocol.DirectToWatchSettingsContract.PREFERENCES, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val store = G7DirectToWatchSettingsStore(context)
        val thresholds = CgmThresholds(250.0, 168.0, 81.0, 50.0)

        store.saveGlucoseUnit(GlucoseUnit.MMOL_L)
        store.saveGlucoseBold(false)
        store.saveActiveAppearanceMode(AppearanceMode.LIGHT)
        assertTrue(store.saveThresholds(thresholds))

        assertEquals(GlucoseUnit.MMOL_L, store.glucoseUnit())
        assertFalse(store.glucoseBold())
        assertEquals(AppearanceMode.LIGHT, store.activeAppearanceMode(AppearanceMode.DARK))
        assertEquals(thresholds, store.thresholds())
    }

    @Test
    fun `direct settings keep prior fields when several controls update rapidly`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(app.aapswear.protocol.DirectToWatchSettingsContract.PREFERENCES, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val store = G7DirectToWatchSettingsStore(context)

        store.saveGraphStyle(store.graphStyle().copy(dotRadiusDp = 5f))
        store.saveGraphStyle(store.graphStyle().copy(rangeBackgroundEnabled = false))
        store.saveGraphColors(store.graphColors().copy(graphBackground = 0xFF010203.toInt()))
        store.saveGraphColors(store.graphColors().copy(rangeHigh = 0x44112233))
        store.saveTrendStyle(AppearanceMode.DARK, store.trendStyle(AppearanceMode.DARK).copy(sizePercent = 200))
        store.saveTrendStyle(AppearanceMode.DARK, store.trendStyle(AppearanceMode.DARK).copy(alpha = .4f))

        assertEquals(5f, store.graphStyle().dotRadiusDp)
        assertTrue(store.graphStyle().rangeBackgroundEnabled)
        assertEquals(0xFF010203.toInt(), store.graphColors().graphBackground)
        assertEquals(0x44112233, store.graphColors().rangeHigh)
        assertEquals(200, store.trendStyle(AppearanceMode.DARK).sizePercent)
        assertEquals(.4f, store.trendStyle(AppearanceMode.DARK).alpha)
    }

    @Test
    fun `direct to watch settings preserve the scroll view and position when a choice changes`() {
        val activity = Robolectric.buildActivity(G7DirectToWatchSettingsActivity::class.java).setup().get()
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        val scroll = findScrollView(root)!!
        measureAndLayout(root)
        scroll.scrollTo(0, 180)

        findText(root, "mmol/L")!!.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertSame(scroll, findScrollView(activity.findViewById(android.R.id.content)))
        assertEquals(180, scroll.scrollY)
        activity.finish()
    }

    @Test
    fun `direct settings sliders and toggles do not rebuild or reset the page`() {
        val activity = Robolectric.buildActivity(G7DirectToWatchSettingsActivity::class.java).setup().get()
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        val scroll = findScrollView(root)!!
        measureAndLayout(root)
        scroll.scrollTo(0, 220)

        val sliderRow = findTextStartingWith(root, "Punktgröße ·")!!.parent as ViewGroup
        val seekBar = (0 until sliderRow.childCount).map { sliderRow.getChildAt(it) }.filterIsInstance<SeekBar>().single()
        seekBar.setProgress((seekBar.progress + 4).coerceAtMost(seekBar.max), true)
        val toggleRow = findText(root, "Graphkontur")!!.parent as ViewGroup
        val toggle = (0 until toggleRow.childCount).map { toggleRow.getChildAt(it) }.filterIsInstance<Switch>().single()
        toggle.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertSame(scroll, findScrollView(activity.findViewById(android.R.id.content)))
        assertEquals(220, scroll.scrollY)
        activity.finish()
    }

    @Test
    fun `collector overview contains only primary data and action elements`() {
        val activity = Robolectric.buildActivity(G7WatchActivity::class.java).create().start().resume().get()
        val texts = mutableListOf<String>()
        collectText(activity.findViewById(android.R.id.content), texts)

        assertFalse(texts.any { it.contains("3h Verlauf", ignoreCase = true) })
        assertTrue("3h" in texts)
        assertFalse(texts.any { it.contains("Watch Direct", ignoreCase = true) })

        val systemIndex = texts.indexOf("Systemstatus")
        val titleIndex = texts.indexOf("G7 Direct to Watch")
        val brandIndex = texts.indexOf("by Sugarlicious")
        assertTrue(systemIndex >= 0)
        assertTrue(titleIndex > systemIndex)
        assertTrue(brandIndex > titleIndex)
        assertFalse(texts.contains("SENSOR"))
        assertFalse(texts.contains("VERBINDUNG"))
        assertFalse(texts.contains("ZEITPLANUNG"))
        assertFalse(texts.contains("DIAGNOSE"))
        assertFalse(texts.any { it == "Sensor einrichten" || it == "Sensor neu koppeln" })
        assertFalse(texts.any { it == "Collector starten" || it == "Collector stoppen" })
        assertFalse(texts.contains("←"))
        assertNotNull(findImageByDescription(activity.findViewById(android.R.id.content), "Einstellungen"))
        assertNotNull(findImageByDescription(activity.findViewById(android.R.id.content), "G7 Watch Collector"))

        assertFalse(containsNativeButton(activity.findViewById(android.R.id.content)))
        activity.finish()
    }

    @Test
    fun `new reading updates existing graph and preserves scroll position`() {
        val controller = Robolectric.buildActivity(G7WatchActivity::class.java).create().start().resume()
        val activity = controller.get()
        val before = findGraph(activity.findViewById(android.R.id.content))
        assertNotNull(before)
        val scroll = findScrollView(activity.findViewById(android.R.id.content))!!
        measureAndLayout(activity.findViewById(android.R.id.content))
        scroll.scrollTo(0, 120)

        val now = System.currentTimeMillis()
        runBlocking {
            G7ReadingDatabase(activity).insert(
                CgmReading(
                    id = "ui-refresh-${System.nanoTime()}",
                    source = DataSourceId.DEXCOM_G7_WATCH,
                    sensorId = "sensor-ui-refresh",
                    sessionId = "session-ui-refresh",
                    glucoseMgDl = 123.0,
                    timestampEpochMs = now,
                    receivedAtEpochMs = now + 1_000L,
                    trend = Trend.FLAT,
                    status = CgmReadingStatus.VALID,
                ),
            )
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val after = findGraph(activity.findViewById(android.R.id.content))
        assertNotNull(after)
        assertSame(before, after)
        assertEquals(120, scroll.scrollY)

        controller.pause().stop().destroy()
    }

    @Test
    fun `system status pill and settings icon open existing screens`() {
        val activity = Robolectric.buildActivity(G7WatchActivity::class.java).create().start().resume().get()
        val root = activity.findViewById<android.view.View>(android.R.id.content)

        findText(root, "Systemstatus")!!.performClick()
        assertEquals(G7SystemStatusActivity::class.java.name, Shadows.shadowOf(activity).nextStartedActivity.component?.className)

        findImageByDescription(root, "Einstellungen")!!.performClick()
        assertEquals(G7SettingsActivity::class.java.name, Shadows.shadowOf(activity).nextStartedActivity.component?.className)
        activity.finish()
    }

    private fun findImageByDescription(root: android.view.View, description: String): ImageView? {
        if (root is ImageView && root.contentDescription?.toString() == description) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findImageByDescription(root.getChildAt(index), description)?.let { return it }
            }
        }
        return null
    }

    @Test
    fun `collector overview uses canonical drawable trend arrow geometry`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = System.currentTimeMillis() + 60_000L
        val reading =
            CgmReading(
                id = "canonical-trend-${System.nanoTime()}",
                source = DataSourceId.DEXCOM_G7_WATCH,
                sensorId = "sensor-canonical-trend",
                sessionId = "session-canonical-trend",
                glucoseMgDl = 123.0,
                timestampEpochMs = now,
                receivedAtEpochMs = now,
                trend = Trend.FORTY_FIVE_UP,
                status = CgmReadingStatus.VALID,
            )
        runBlocking {
            G7ReadingDatabase(context).let { database ->
                try {
                    database.insert(reading)
                } finally {
                    database.close()
                }
            }
        }
        G7SensorStateStore(context).save(
            G7SensorStateStore(context).read().copy(
                sensor = G7Sensor(reading.sensorId, reading.sessionId),
                lastReading = reading,
            ),
        )
        val activity = Robolectric.buildActivity(G7WatchActivity::class.java).create().start().resume().get()
        val arrows = mutableListOf<ImageView>()
        collectTrendArrows(activity.findViewById(android.R.id.content), arrows)

        assertEquals(1, arrows.size)
        assertEquals(0f, arrows.single().rotation)
        assertTrue(arrows.single().contentDescription.toString().contains("FORTY_FIVE_UP"))
        activity.finish()
    }

    @Test
    fun `system status screen keeps all real status groups and back navigation`() {
        val activity = Robolectric.buildActivity(G7SystemStatusActivity::class.java).create().start().resume().get()
        val texts = mutableListOf<String>()
        collectText(activity.findViewById(android.R.id.content), texts)

        listOf("LIVE COLLECTOR STATUS", "SYSTEMSTATUS", "SENSOR", "VERBINDUNG", "ZEITPLANUNG", "HARDWARETEST", "DIAGNOSE", "AKTIONEN").forEach {
            assertTrue("Missing group $it", texts.any { text -> text.contains(it) })
        }
        findTextStartingWith(activity.findViewById(android.R.id.content), "▸  HARDWARETEST")!!.performClick()
        findTextStartingWith(activity.findViewById(android.R.id.content), "▸  DIAGNOSE")!!.performClick()
        texts.clear()
        collectText(activity.findViewById(android.R.id.content), texts)
        listOf(
            "Sensorstatus", "Session", "Sensorcode", "GTIN", "Seriennummer", "Letzter Wert", "Trendrate", "BLE-Name", "Kulanzende",
            "Status", "Reconnect-Strategie", "Hinweis", "Empfohlene Aktion", "Nächster Reconnect", "Geräte in der Nähe",
            "Benachrichtigungen", "GATT verbunden", "Aktiver Attempt", "Fehlercode",
        ).forEach {
            assertTrue("Missing status field $it", texts.contains(it))
        }
        assertTrue(texts.contains("Systemstatus"))
        findTextStartingWith(activity.findViewById(android.R.id.content), "‹")!!.performClick()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `period change updates only graph state and preserves screen and scroll`() {
        val activity = Robolectric.buildActivity(G7WatchActivity::class.java).create().start().resume().get()
        val rootBefore = activity.findViewById<android.view.View>(android.R.id.content).let { (it as ViewGroup).getChildAt(0) }
        val graphBefore = findGraph(rootBefore)!!
        val scroll = findScrollView(rootBefore)!!
        measureAndLayout(activity.findViewById(android.R.id.content))
        scroll.scrollTo(0, 120)

        findText(rootBefore, "3h")!!.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val rootAfter = activity.findViewById<android.view.View>(android.R.id.content).let { (it as ViewGroup).getChildAt(0) }
        assertSame(rootBefore, rootAfter)
        assertSame(graphBefore, findGraph(rootAfter))
        assertNotNull(findText(rootAfter, "6h"))
        assertEquals(120, scroll.scrollY)
        activity.finish()
    }

    private fun collectText(view: android.view.View, output: MutableList<String>) {
        if (view is TextView) output += view.text?.toString().orEmpty()
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectText(view.getChildAt(index), output)
        }
    }

    private fun findGraph(view: android.view.View): G7CollectorGraphView? {
        if (view is G7CollectorGraphView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findGraph(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun findScrollView(view: android.view.View): ScrollView? {
        if (view is ScrollView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findScrollView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun findText(view: android.view.View, value: String): TextView? {
        if (view is TextView && view.text?.toString() == value) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findText(view.getChildAt(index), value)?.let { return it }
        return null
    }

    private fun collectTrendArrows(view: android.view.View, output: MutableList<ImageView>) {
        if (view is ImageView && view.contentDescription?.toString()?.startsWith("Trend ") == true) output += view
        if (view is ViewGroup) for (index in 0 until view.childCount) collectTrendArrows(view.getChildAt(index), output)
    }

    private fun findTextStartingWith(view: android.view.View, value: String): TextView? {
        if (view is TextView && view.text?.toString()?.startsWith(value) == true) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findTextStartingWith(view.getChildAt(index), value)?.let { return it }
        return null
    }

    private fun measureAndLayout(view: android.view.View) {
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 400, 400)
    }

    private fun containsNativeButton(view: android.view.View): Boolean {
        if (view is Button) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsNativeButton(view.getChildAt(index))) return true
            }
        }
        return false
    }
}
