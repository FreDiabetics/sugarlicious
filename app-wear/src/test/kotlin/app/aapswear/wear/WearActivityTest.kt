package app.aapswear.wear

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.aapswear.complications.R as ComplicationR
import app.aapswear.model.BasalState
import app.aapswear.protocol.WatchConfig
import app.aapswear.protocol.WatchColorSync
import app.aapswear.protocol.WatchAppearanceProfile
import app.aapswear.model.AppearanceMode
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

@RunWith(RobolectricTestRunner::class)
class WearActivityTest {
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
            listOf("display", "glucose", "graph", "tiles", "watchfaces", "connection", "diagnostics", "about"),
            headers.map { it.tag.toString().removePrefix("settings-category-") },
        )
        assertTrue(headers.all { it.minimumHeight >= (48 * activity.resources.displayMetrics.density).toInt() })
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
        assertEquals(Gravity.CENTER, activity.findViewById<LinearLayout>(R.id.wear_basal_card).gravity)
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
    fun `glucose metadata stays on one line across the full round safe card width`() {
        val activity =
            Robolectric
                .buildActivity(WearActivity::class.java)
                .create()
                .start()
                .resume()
                .get()
        val colors = WearDisplayPreferences.read(activity).uiColors
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
        delta.text = "Δ − · mg/dL"
        age.text = ""
        age.visibility = View.GONE
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
        assertEquals(colors.textSecondary, delta.currentTextColor)
        assertEquals(View.GONE, age.visibility)
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
                )
                .text
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
    fun `watch config does not overwrite independent local display preferences`() {
        val context =
            ApplicationProvider.getApplicationContext<
                android.content.Context
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
        val colors = WatchGraphColors(
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
    fun `explicit color sync keeps light and dark profiles independent`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(WearDisplayPreferences.PREFS, android.content.Context.MODE_PRIVATE).edit().clear().commit()
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
