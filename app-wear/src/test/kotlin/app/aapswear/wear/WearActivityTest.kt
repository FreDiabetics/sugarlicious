package app.aapswear.wear

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.aapswear.model.AppearanceMode
import app.aapswear.model.BasalState
import app.aapswear.model.CgmThresholds
import app.aapswear.protocol.WatchAppearanceProfile
import app.aapswear.protocol.WatchColorSync
import app.aapswear.protocol.WatchConfig
import app.aapswear.protocol.WatchDataSource
import app.aapswear.protocol.WatchGlucoseUnit
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.protocol.WatchUiColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import app.aapswear.complications.R as ComplicationR

@RunWith(RobolectricTestRunner::class)
class WearActivityTest {
    @Test
    fun `therapy overview keeps separate cards with IOB and COB side by side`() {
        val activity = Robolectric.buildActivity(WearActivity::class.java).setup().get()
        listOf(
            R.id.wear_iob_progress to R.id.wear_iob_card,
            R.id.wear_cob_progress to R.id.wear_cob_card,
            R.id.wear_basal_progress to R.id.wear_basal_card,
        ).forEach { (ringId, cardId) ->
            val ring = activity.findViewById<WearTherapyProgressView>(ringId)
            assertNotNull(ring)
            assertEquals(activity.findViewById<View>(cardId), (ring.parent as View).parent)
        }
        assertEquals(LinearLayout.HORIZONTAL, activity.findViewById<LinearLayout>(R.id.wear_therapy_row).orientation)
    }

    @Test
    fun `overview ends with native settings action and contains no app branding`() {
        val activity = Robolectric.buildActivity(WearActivity::class.java).setup().get()
        val settings = activity.findViewById<android.widget.Button>(R.id.wear_settings_action)
        val menu = settings.parent as ViewGroup
        val views = mutableListOf<View>()

        fun collect(view: View) {
            views += view
            if (view is ViewGroup) repeat(view.childCount) { collect(view.getChildAt(it)) }
        }
        collect(menu)
        assertTrue(views.filterIsInstance<TextView>().none { it.text.toString() == "Sugarlicious" })
        assertTrue(views.filterIsInstance<ImageView>().none { it.contentDescription?.toString() == "Sugarlicious" })
        assertEquals("Einstellungen", settings.text.toString())
        assertEquals(menu.childCount - 1, menu.indexOfChild(settings))
        assertTrue(settings.minimumHeight >= (48 * activity.resources.displayMetrics.density).toInt())
        assertNotNull(settings.compoundDrawablesRelative[0])
        settings.performClick()
        assertEquals(
            WearSettingsActivity::class.java.name,
            org.robolectric.Shadows
                .shadowOf(activity)
                .nextStartedActivity.component
                ?.className,
        )
    }

    @Test
    fun `first card starts inside the round display safe area`() {
        val activity = Robolectric.buildActivity(WearActivity::class.java).setup().get()
        val menu = activity.findViewById<View>(R.id.wear_glucose_card).parent as ViewGroup

        assertEquals((30 * activity.resources.displayMetrics.density).toInt(), menu.paddingTop)
    }

    @Test
    fun `wear appearance is dark by default and explicit selection remains active`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context
            .getSharedPreferences(WearDisplayPreferences.PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        assertEquals(AppearanceMode.DARK, WearDisplayPreferences.activeAppearanceMode(context))
        WearDisplayPreferences.setActiveAppearanceMode(context, AppearanceMode.LIGHT)
        assertEquals(AppearanceMode.LIGHT, WearDisplayPreferences.activeAppearanceMode(context))
        WearDisplayPreferences.setActiveAppearanceMode(context, AppearanceMode.DARK)
        assertEquals(AppearanceMode.DARK, WearDisplayPreferences.activeAppearanceMode(context))
    }

    @Test
    fun `legacy Wear source selection cannot override AndroidAPS phone policy`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences(WearDisplayPreferences.PREFS, android.content.Context.MODE_PRIVATE)
        prefs
            .edit()
            .clear()
            .putString("data_source", WatchDataSource.DEXCOM_G7_WATCH.name)
            .commit()

        assertEquals(WatchDataSource.PHONE, WearDisplayPreferences.read(context).dataSource)
    }

    @Test
    fun `graph scale cycles through every supported duration and wraps`() {
        assertEquals(2, nextWearGraphHours(1))
        assertEquals(3, nextWearGraphHours(2))
        assertEquals(6, nextWearGraphHours(3))
        assertEquals(12, nextWearGraphHours(6))
        assertEquals(24, nextWearGraphHours(12))
        assertEquals(1, nextWearGraphHours(24))
        assertEquals(1, nextWearGraphHours(99))
    }

    @Test
    fun `graph status combines scale with event timestamp age`() {
        assertEquals("3h • 2m", wearGraphScaleAgeText(3, 1_000L, 121_000L))
        assertEquals("24h • —", wearGraphScaleAgeText(24, null, 121_000L))
    }

    @Test
    fun `settings are round safe and grouped into independent sections`() {
        val activity = Robolectric.buildActivity(WearSettingsActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val headers = mutableListOf<View>()

        fun collect(view: View) {
            if (view.tag?.toString()?.startsWith("settings-category-") == true) headers += view
            if (view is ViewGroup) (0 until view.childCount).forEach { collect(view.getChildAt(it)) }
        }
        collect(root)

        assertEquals(
            listOf("display", "glucose", "trend", "delta", "therapy", "graph", "tiles", "watchfaces", "connection", "diagnostics", "about"),
            headers.map { it.tag.toString().removePrefix("settings-category-") },
        )
        assertTrue(headers.all { it.minimumHeight >= (48 * activity.resources.displayMetrics.density).toInt() })

        root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 480, 480)
        val categoryScroll = root.getChildAt(0) as android.widget.ScrollView
        categoryScroll.scrollTo(0, 200)
        assertTrue(categoryScroll.scrollY > 0)

        // Opening an element exposes its complete controls without the other elements' controls.
        headers.single { it.tag == "settings-category-trend" }.performClick()
        org.robolectric.Shadows
            .shadowOf(android.os.Looper.getMainLooper())
            .idle()
        assertEquals("A new submenu must open at its header", 0, (root.getChildAt(0) as android.widget.ScrollView).scrollY)
        val text = mutableListOf<String>()

        fun collectText(view: View) {
            if (view is TextView) text += view.text.toString()
            if (view is ViewGroup) (0 until view.childCount).forEach { collectText(view.getChildAt(it)) }
        }
        collectText(root)
        assertTrue("Trendpfeil" in text)
        assertTrue("Trendpfeil · Füllfarbe" in text)
        assertTrue("Trendpfeil-Kontur" in text)
        assertTrue("Trend-Deckkraft" in text)
        assertTrue("Trend-Stil zurücksetzen" in text)
        assertTrue("IOB" !in text && "COB" !in text && "Basal" !in text)
    }

    @Test
    fun `therapy overview uses colored supplied icons and neutral values`() {
        val activity =
            Robolectric
                .buildActivity(WearActivity::class.java)
                .create()
                .start()
                .resume()
                .get()
        val colors = WearDisplayPreferences.read(activity).uiColors

        assertEquals(
            colors.iob,
            activity.findViewById<ImageView>(R.id.wear_iob_icon).imageTintList?.defaultColor,
        )
        assertEquals(
            colors.cob,
            activity.findViewById<ImageView>(R.id.wear_cob_icon).imageTintList?.defaultColor,
        )
        assertEquals(
            colors.basal,
            activity.findViewById<ImageView>(R.id.wear_basal_icon).imageTintList?.defaultColor,
        )
        assertEquals(colors.textPrimary, activity.findViewById<TextView>(R.id.wear_iob).currentTextColor)
        assertEquals(colors.textPrimary, activity.findViewById<TextView>(R.id.wear_cob).currentTextColor)
        assertEquals(colors.textPrimary, activity.findViewById<TextView>(R.id.wear_basal).currentTextColor)
        assertEquals(Gravity.START or Gravity.CENTER_VERTICAL, activity.findViewById<LinearLayout>(R.id.wear_basal_card).gravity)
    }

    @Test
    fun `basal icon follows normal higher and lower temp basal`() {
        assertEquals(
            ComplicationR.drawable.ic_complication_basal,
            basalIconResource(BasalState(currentUnitsPerHour = 0.8)),
        )
        assertEquals(
            ComplicationR.drawable.ic_complication_basal_more,
            basalIconResource(
                BasalState(
                    currentUnitsPerHour = 0.8,
                    tempAbsoluteUnitsPerHour = 1.2,
                ),
            ),
        )
        assertEquals(
            ComplicationR.drawable.ic_complication_basal_less,
            basalIconResource(
                BasalState(
                    currentUnitsPerHour = 0.8,
                    tempAbsoluteUnitsPerHour = 0.4,
                ),
            ),
        )
        assertEquals(
            ComplicationR.drawable.ic_complication_basal_more,
            basalIconResource(BasalState(tempPercent = 150)),
        )
        assertEquals(
            ComplicationR.drawable.ic_complication_basal_less,
            basalIconResource(BasalState(tempPercent = 50)),
        )
    }

    @Test
    fun `glucose metadata uses stacked rows with white values and smaller gray units`() {
        val activity =
            Robolectric
                .buildActivity(WearActivity::class.java)
                .create()
                .start()
                .resume()
                .get()
        val root = activity.findViewById<View>(R.id.wear_root)
        val info = activity.findViewById<ViewGroup>(R.id.wear_glucose_info_block)
        val primary = activity.findViewById<ViewGroup>(R.id.wear_glucose_primary_row)
        val meta = activity.findViewById<ViewGroup>(R.id.wear_glucose_meta_row)
        val glucose = activity.findViewById<TextView>(R.id.wear_glucose)
        val trend = activity.findViewById<View>(R.id.wear_trend_container)
        val delta = activity.findViewById<TextView>(R.id.wear_delta)
        val age = activity.findViewById<TextView>(R.id.wear_age)
        val density = activity.resources.displayMetrics.density

        glucose.text = "123"
        delta.text = app.aapswear.uishared.glucoseMetadataText("-5 mg/dL")
        age.text = app.aapswear.uishared.glucoseMetadataText("vor 2 Min")
        age.visibility = View.VISIBLE
        trend.visibility = View.VISIBLE
        root.measure(
            View.MeasureSpec.makeMeasureSpec(
                (320f * density).toInt(),
                View.MeasureSpec.EXACTLY,
            ),
            View.MeasureSpec.makeMeasureSpec(
                (320f * density).toInt(),
                View.MeasureSpec.EXACTLY,
            ),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, info.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, primary.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, meta.layoutParams.width)
        assertEquals(android.graphics.Color.WHITE, delta.currentTextColor)
        val styled = delta.text as android.text.Spanned
        assertTrue(styled.getSpans(0, styled.length, android.text.style.RelativeSizeSpan::class.java).isNotEmpty())
        assertTrue(styled.getSpans(0, styled.length, android.text.style.ForegroundColorSpan::class.java).any { it.foregroundColor == android.graphics.Color.rgb(181, 181, 181) })
        assertEquals(View.VISIBLE, age.visibility)
        assertTrue(delta.bottom <= age.top)
        assertEquals(1, age.lineCount)
        assertTrue(info.measuredWidth > primary.measuredWidth)
        assertEquals(info.measuredWidth, meta.measuredWidth)
        assertEquals(1, delta.lineCount)
        assertEquals(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                14f,
                activity.resources.displayMetrics,
            ),
            delta.textSize,
            0.5f,
        )
        assertEquals(0, activity.resources.getIdentifier("wear_glucose_status", "id", activity.packageName))
    }

    @Test
    fun `dashboard exposes chart and bridge status`() {
        val activity =
            Robolectric
                .buildActivity(WearActivity::class.java)
                .create()
                .start()
                .resume()
                .get()

        assertEquals(
            "—",
            activity
                .findViewById<TextView>(
                    R.id.wear_glucose,
                ).text
                .toString(),
        )
        assertNotNull(
            activity.findViewById<WearGlucoseChart>(
                R.id.wear_glucose_chart,
            ),
        )
        assertNotNull(
            activity.findViewById<TextView>(
                R.id.wear_connection,
            ),
        )
        assertEquals(
            0,
            activity.resources.getIdentifier(
                "wear_config_info",
                "id",
                activity.packageName,
            ),
        )
    }

    @Test
    fun `graph scale age control is pill free and changes persisted viewport`() {
        val activity =
            Robolectric
                .buildActivity(WearActivity::class.java)
                .create()
                .start()
                .resume()
                .get()
        WearDisplayPreferences.saveLocal(activity, WearDisplayPreferences.read(activity).copy(graphHours = 3))
        val control = activity.findViewById<TextView>(R.id.wear_graph_period)

        control.performClick()

        assertEquals(6, WearDisplayPreferences.read(activity).graphHours)
        assertTrue(control.text.toString().startsWith("6h • "))
        assertEquals(null, control.background)
        assertTrue(control.minimumWidth >= (72 * activity.resources.displayMetrics.density).toInt())
    }

    @Test
    fun `watch config does not overwrite independent local display preferences`() {
        val context =
            ApplicationProvider.getApplicationContext<
                android.content.Context,
            >()

        WearDisplayPreferences.saveLocal(
            context,
            WearDisplayPreferences(
                graphHours = 12,
                showPredictions = true,
                glucoseUnit = WatchGlucoseUnit.MG_DL,
                showTherapyStats = true,
                graphColors = WatchGraphColors(nowLine = 0xFFABCDEF.toInt()),
                uiColors = WatchUiColors(basal = 0xFFABCDEF.toInt()),
            ),
        )

        WearDisplayPreferences.save(
            context,
            WatchConfig(
                graphHours = 6,
                showPredictions = false,
                glucoseUnit = WatchGlucoseUnit.MMOL_L,
                showTherapyStats = false,
                graphColors = WatchGraphColors(nowLine = 0xFF123456.toInt()),
                uiColors = WatchUiColors(basal = 0xFF123456.toInt()),
                sentAtEpochMs = 1234L,
            ),
        )

        val preferences =
            WearDisplayPreferences.read(context)

        assertEquals(12, preferences.graphHours)
        assertEquals(true, preferences.showPredictions)
        assertEquals(
            WatchGlucoseUnit.MG_DL,
            preferences.glucoseUnit,
        )
        assertEquals(true, preferences.showTherapyStats)
        assertEquals(0xFFABCDEF.toInt(), preferences.graphColors.nowLine)
        assertEquals(0xFFABCDEF.toInt(), preferences.uiColors.basal)
        assertEquals(1234L, preferences.syncedAtEpochMs)
    }

    @Test
    fun `explicit color sync updates every semantic graph role`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val colors =
            WatchGraphColors(
                highLine = 0xFF110001.toInt(),
                lowLine = 0xFF220002.toInt(),
                axisLabel = 0xFF330003.toInt(),
                axisTick = 0xFF440004.toInt(),
                nowLine = 0xFF550005.toInt(),
                divider = 0xFF660006.toInt(),
            )

        WearDisplayPreferences.applySyncedColors(
            context,
            WatchColorSync(graphColors = colors, sentAtEpochMs = 4321L),
        )

        assertEquals(colors, WearDisplayPreferences.read(context).graphColors)
    }

    @Test
    fun `explicit color sync never overwrites local glucose unit or target range`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val localThresholds = CgmThresholds(240.0, 165.0, 82.0, 55.0)
        WearDisplayPreferences.saveLocal(
            context,
            WearDisplayPreferences(glucoseUnit = WatchGlucoseUnit.MMOL_L, cgmThresholds = localThresholds),
        )

        WearDisplayPreferences.applySyncedColors(
            context,
            WatchColorSync(graphColors = WatchGraphColors(), cgmThresholds = CgmThresholds.DEFAULT, sentAtEpochMs = 9876L),
        )

        val result = WearDisplayPreferences.read(context)
        assertEquals(WatchGlucoseUnit.MMOL_L, result.glucoseUnit)
        assertEquals(localThresholds, result.cgmThresholds)
    }

    @Test
    fun `explicit color sync keeps light and dark profiles independent`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context
            .getSharedPreferences(WearDisplayPreferences.PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val light = WatchGraphColors(graphBackground = 0xFFF4F4F4.toInt())
        val dark = WatchGraphColors(graphBackground = 0xFF090909.toInt())
        WearDisplayPreferences.applySyncedColors(
            context,
            WatchColorSync(
                graphColors = dark,
                lightProfile = WatchAppearanceProfile(graphColors = light),
                darkProfile = WatchAppearanceProfile(graphColors = dark),
                sentAtEpochMs = 555L,
            ),
        )
        assertEquals(light, WearDisplayPreferences.read(context, AppearanceMode.LIGHT).graphColors)
        assertEquals(dark, WearDisplayPreferences.read(context, AppearanceMode.DARK).graphColors)
    }
}
