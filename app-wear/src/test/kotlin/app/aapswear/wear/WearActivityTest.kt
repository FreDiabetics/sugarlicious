package app.aapswear.wear

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.aapswear.complications.R as ComplicationR
import app.aapswear.model.BasalState
import app.aapswear.protocol.WatchConfig
import app.aapswear.protocol.WatchGlucoseUnit
import app.aapswear.protocol.WatchUiColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WearActivityTest {
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
    fun `glucose metadata is larger grey and constrained by the primary row`() {
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
        delta.text = "+5"
        age.text = "2 min"
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

        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, info.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, primary.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, meta.layoutParams.width)
        assertEquals(colors.textSecondary, delta.currentTextColor)
        assertEquals(colors.textSecondary, age.currentTextColor)
        assertEquals(delta.textSize, age.textSize, 0.5f)
        assertEquals(primary.measuredWidth, info.measuredWidth)
        assertEquals(info.measuredWidth, meta.measuredWidth)
        assertEquals(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                14f,
                activity.resources.displayMetrics,
            ),
            delta.textSize,
            0.5f,
        )
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
    fun `watch config persists phone display preferences`() {
        val context =
            ApplicationProvider.getApplicationContext<
                android.content.Context
            >()

        WearDisplayPreferences.save(
            context,
            WatchConfig(
                graphHours = 6,
                showPredictions = false,
                glucoseUnit = WatchGlucoseUnit.MMOL_L,
                showTherapyStats = false,
                uiColors = WatchUiColors(basal = 0xFF123456.toInt()),
                sentAtEpochMs = 1234L,
            ),
        )

        val preferences =
            WearDisplayPreferences.read(context)

        assertEquals(6, preferences.graphHours)
        assertEquals(false, preferences.showPredictions)
        assertEquals(
            WatchGlucoseUnit.MMOL_L,
            preferences.glucoseUnit,
        )
        assertEquals(false, preferences.showTherapyStats)
        assertEquals(0xFF123456.toInt(), preferences.uiColors.basal)
        assertEquals(1234L, preferences.syncedAtEpochMs)
    }
}
