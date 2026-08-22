package app.aapswear.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.mobile.ui.theme.SugarliciousPalette
import app.aapswear.model.GlucosePrediction
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.PredictionKind
import app.aapswear.model.TargetSample
import app.aapswear.model.TargetState
import app.aapswear.model.TherapyDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CgmGraphCustomizationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)

    @Test
    fun `target value color reuses compatible picker role and persists independently from range`() {
        preferences.edit().clear().putString("themeMode", "DARK").commit()
        val targetColor = Color.rgb(224, 42, 205)
        val rangeColor = Color.rgb(24, 180, 65)
        val oldTargetBandColor = Color.rgb(6, 48, 18)

        assertEquals(SugarliciousColorRole.TARGET_BAND, SugarliciousColorRole.TARGET_VALUE)
        assertEquals("target_value", SugarliciousColorRole.TARGET_VALUE.preferenceKey)
        assertTrue(SugarliciousColorRole.TARGET_VALUE.configurable)
        assertTrue(colorRoleVisible(SugarliciousColorRole.TARGET_VALUE, showCgmGraph = true, showMetabolicGraph = false))
        assertFalse(colorRoleVisible(SugarliciousColorRole.TARGET_VALUE, showCgmGraph = false, showMetabolicGraph = false))

        preferences.edit().putInt("color.dark.target_band", oldTargetBandColor).commit()
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.TARGET_VALUE, targetColor)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.RANGE_IN_RANGE, rangeColor)
        val palette = SugarliciousColorStore.load(preferences)

        assertEquals(targetColor, palette.argb(SugarliciousColorRole.TARGET_VALUE))
        assertEquals(rangeColor, palette.argb(SugarliciousColorRole.RANGE_IN_RANGE))
        assertTrue(targetColor != rangeColor)
        assertTrue(oldTargetBandColor != palette.argb(SugarliciousColorRole.TARGET_VALUE))
    }

    @Test
    fun `configured target value color is used by target line`() {
        preferences.edit().clear().putString("themeMode", "DARK").commit()
        val targetColor = Color.rgb(224, 42, 205)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.TARGET_VALUE, targetColor)
        SugarliciousColors.apply(SugarliciousColorStore.load(preferences))

        try {
            val now = System.currentTimeMillis()
            val state = TherapyDisplayState(
                receivedAtEpochMs = now,
                glucose = GlucoseState(122.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
                glucoseHistory = listOf(
                    GlucoseSample(118.0, now - 10 * 60_000L),
                    GlucoseSample(120.0, now - 5 * 60_000L),
                    GlucoseSample(122.0, now),
                ),
                target = TargetState(80.0, 160.0, valueMgDl = 100.0),
            )

            val bitmap = render(
                GlucoseDashboardChart(context).apply {
                    bind(
                        state = state,
                        unit = GlucoseUnit.MG_DL,
                        showPredictions = false,
                        durationHours = 3,
                        showTargetRange = false,
                        showTargetValue = true,
                        cgmDotOutlineEnabled = false,
                        clockEpochMs = now,
                    )
                },
                240,
            )

            assertTrue("targetColorPixels=${count(bitmap) { it == targetColor }}", count(bitmap) { it == targetColor } > 40)
        } finally {
            SugarliciousColors.apply(SugarliciousPalette.defaults())
        }
    }

    @Test
    fun `overview graph forwards enabled target value preference`() {
        preferences.edit().clear().putString("themeMode", "DARK").commit()
        val targetColor = Color.rgb(224, 42, 205)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.TARGET_VALUE, targetColor)
        SugarliciousColors.apply(SugarliciousColorStore.load(preferences))

        try {
            val now = System.currentTimeMillis()
            val state = TherapyDisplayState(
                receivedAtEpochMs = now,
                glucose = GlucoseState(122.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
                glucoseHistory = listOf(
                    GlucoseSample(118.0, now - 10 * 60_000L),
                    GlucoseSample(120.0, now - 5 * 60_000L),
                    GlucoseSample(122.0, now),
                ),
                target = TargetState(80.0, 160.0, valueMgDl = 100.0),
            )
            val ui = DashboardUiPreferences(
                showCgmTargetRange = false,
                showCgmTargetValue = true,
                cgmDotOutlineEnabled = false,
            )

            val bitmap = render(
                GlucoseDashboardChart(context).apply {
                    bindOverview(state, ui, now)
                },
                240,
            )

            assertTrue("targetColorPixels=${count(bitmap) { it == targetColor }}", count(bitmap) { it == targetColor } > 40)
        } finally {
            SugarliciousColors.apply(SugarliciousPalette.defaults())
        }
    }

    @Test
    fun `changed target history invalidates an otherwise unchanged graph`() {
        preferences.edit().clear().putString("themeMode", "DARK").commit()
        val targetColor = Color.rgb(224, 42, 205)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.TARGET_VALUE, targetColor)
        SugarliciousColors.apply(SugarliciousColorStore.load(preferences))

        try {
            val now = System.currentTimeMillis()
            val base = TherapyDisplayState(
                receivedAtEpochMs = now,
                glucose = GlucoseState(122.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
                glucoseHistory = listOf(
                    GlucoseSample(118.0, now - 10 * 60_000L),
                    GlucoseSample(122.0, now),
                ),
                target = TargetState(80.0, 160.0),
            )
            val chart = GlucoseDashboardChart(context)
            chart.bind(
                state = base,
                unit = GlucoseUnit.MG_DL,
                showPredictions = false,
                durationHours = 3,
                showTargetRange = false,
                showTargetValue = true,
                cgmDotOutlineEnabled = false,
                clockEpochMs = now,
            )
            assertEquals(0, count(render(chart, 240)) { it == targetColor })

            chart.bind(
                state = base.copy(
                    targetHistory = listOf(
                        TargetSample(
                            valueMgDl = 100.0,
                            startedAtEpochMs = now - 3 * 60 * 60_000L,
                            endsAtEpochMs = now,
                        ),
                    ),
                ),
                unit = GlucoseUnit.MG_DL,
                showPredictions = false,
                durationHours = 3,
                showTargetRange = false,
                showTargetValue = true,
                cgmDotOutlineEnabled = false,
                clockEpochMs = now,
            )

            assertTrue(count(render(chart, 240)) { it == targetColor } > 40)
        } finally {
            SugarliciousColors.apply(SugarliciousPalette.defaults())
        }
    }

    @Test
    fun `prediction point size and zero outline preference change rendered dots`() {
        preferences.edit().clear().putString("themeMode", "DARK").commit()
        val predictionColor = Color.rgb(55, 190, 245)
        val outlineColor = Color.rgb(215, 40, 190)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.PREDICTION_IOB, predictionColor)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.GRAPH_CURRENT_OUTLINE, outlineColor)
        SugarliciousColors.apply(SugarliciousColorStore.load(preferences))

        try {
            val now = System.currentTimeMillis()
            val state = TherapyDisplayState(
                receivedAtEpochMs = now,
                glucose = GlucoseState(120.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
                glucoseHistory = listOf(
                    GlucoseSample(116.0, now - 5 * 60_000L),
                    GlucoseSample(120.0, now),
                ),
                glucosePredictions = listOf(
                    GlucosePrediction(
                        PredictionKind.IOB,
                        listOf(
                            GlucoseSample(120.0, now),
                            GlucoseSample(126.0, now + 5 * 60_000L),
                            GlucoseSample(132.0, now + 10 * 60_000L),
                        ),
                    ),
                ),
                target = TargetState(80.0, 160.0),
            )

            preferences.edit()
                .putFloat("cgm.prediction.dotRadiusDp", 1.0f)
                .putFloat("cgm.prediction.dotOutlineWidthDp", 0.0f)
                .commit()
            val small = renderPrediction(state, now)
            val smallFill = count(small) { it == predictionColor }
            val smallOutline = count(small) { it == outlineColor }

            preferences.edit()
                .putFloat("cgm.prediction.dotRadiusDp", 5.0f)
                .putFloat("cgm.prediction.dotOutlineWidthDp", 2.0f)
                .commit()
            val large = renderPrediction(state, now)
            val largeFill = count(large) { it == predictionColor }
            val largeOutline = count(large) { it == outlineColor }

            assertTrue("smallFill=$smallFill largeFill=$largeFill", largeFill > smallFill * 2)
            assertEquals(0, smallOutline)
            assertTrue("largeOutline=$largeOutline", largeOutline > 0)
        } finally {
            SugarliciousColors.apply(SugarliciousPalette.defaults())
        }
    }

    @Test
    fun `prediction style preferences are clamped by dashboard model`() {
        preferences.edit().clear()
            .putFloat("cgm.prediction.dotRadiusDp", 99f)
            .putFloat("cgm.prediction.dotOutlineWidthDp", -4f)
            .commit()

        val ui = DashboardUiPreferences.read(preferences)
        assertEquals(6.0f, ui.predictionDotRadiusDp, 0.0001f)
        assertEquals(0.0f, ui.predictionDotOutlineWidthDp, 0.0001f)
    }

    private fun renderPrediction(state: TherapyDisplayState, now: Long): Bitmap {
        val viewport = ChartViewport(1).apply { setFutureWindow(15 * 60_000L) }
        return render(
            GlucoseDashboardChart(context, sharedViewport = viewport).apply {
                bind(
                    state = state,
                    unit = GlucoseUnit.MG_DL,
                    showPredictions = true,
                    durationHours = 1,
                    showPredictionIob = true,
                    cgmDotOutlineEnabled = false,
                    clockEpochMs = now,
                )
            },
            240,
        )
    }

    private fun render(view: View, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, size, size)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun count(bitmap: Bitmap, predicate: (Int) -> Boolean): Int {
        var matches = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (predicate(bitmap.getPixel(x, y))) matches++
            }
        }
        return matches
    }
}
