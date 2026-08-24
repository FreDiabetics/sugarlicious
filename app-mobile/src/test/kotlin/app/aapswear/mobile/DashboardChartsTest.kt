package app.aapswear.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.aapswear.model.CarbState
import app.aapswear.model.GlucosePrediction
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.InsulinState
import app.aapswear.model.PredictionKind
import app.aapswear.model.RangeExcursion
import app.aapswear.model.TargetState
import app.aapswear.model.TargetSample
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TherapyHistorySample
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.mobile.ui.theme.SugarliciousPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardChartsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun `glucose chart renders source target and prediction streams`() {
        val now = System.currentTimeMillis()
        val state = TherapyDisplayState(
            receivedAtEpochMs = now,
            glucose = GlucoseState(129.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
            glucoseHistory = listOf(100.0, 118.0, 112.0, 129.0).mapIndexed { index, value -> GlucoseSample(value, now - (3 - index) * 15 * 60_000L) },
            glucosePredictions = listOf(GlucosePrediction(PredictionKind.IOB, listOf(129.0, 120.0, 108.0).mapIndexed { index, value -> GlucoseSample(value, now + index * 5 * 60_000L) })),
            target = TargetState(80.0, 160.0, valueMgDl = 100.0),
        )
        val viewport = ChartViewport(6).apply {
            setFutureWindow(15L * 60_000L)
        }
        val bitmap = render(
            GlucoseDashboardChart(context = context, sharedViewport = viewport).apply {
                bind(
                    state = state,
                    unit = GlucoseUnit.MG_DL,
                    showPredictions = true,
                    durationHours = 6,
                    showTargetRange = true,
                    showTargetValue = true,
                    showPredictionIob = true,
                )
            },
            230,
        )
        val inRangePixels = count(bitmap) {
            Color.green(it) > 150 && Color.green(it) > Color.red(it) * 1.3
        }
        val predictionPixels = count(bitmap) { Color.blue(it) > 180 && Color.green(it) > 120 }
        assertTrue("inRange=$inRangePixels", inRangePixels > 20)
        assertTrue("prediction=$predictionPixels", predictionPixels > 2)
    }

    @Test fun `in range picker drives the graph while high and low stay transparent without sustained excursion`() {
        val preferences = context.getSharedPreferences("chart_region_colors", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().putString("themeMode", "DARK").commit()
        val high = Color.rgb(25, 40, 220)
        val inRange = Color.rgb(30, 210, 70)
        val low = Color.rgb(225, 35, 55)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.RANGE_HIGH, high)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.RANGE_IN_RANGE, inRange)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.RANGE_LOW, low)
        SugarliciousColors.apply(SugarliciousColorStore.load(preferences))

        val now = System.currentTimeMillis()
        val state = TherapyDisplayState(
            receivedAtEpochMs = now,
            glucose = GlucoseState(123.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
            glucoseHistory = listOf(GlucoseSample(123.0, now)),
            target = TargetState(80.0, 160.0),
        )
        val bitmap = render(
            GlucoseDashboardChart(context).apply {
                bind(
                    state = state,
                    unit = GlucoseUnit.MG_DL,
                    showPredictions = false,
                    durationHours = 3,
                    showTargetRange = true,
                )
            },
            230,
        )

        assertTrue(count(bitmap) { it == inRange } > 100)
        assertEquals(0, count(bitmap) { it == high })
        assertEquals(0, count(bitmap) { it == low })
        SugarliciousColors.apply(SugarliciousPalette.defaults())
    }

    @Test fun `range excursion requires two consecutive valid values`() {
        val now = 10_000_000L
        fun samples(vararg values: Double) = values.mapIndexed { index, value ->
            GlucoseSample(value, now - (values.lastIndex - index) * 5 * 60_000L)
        }

        assertNull(sustainedRangeExcursion(samples(79.0), 80.0, 160.0))
        assertEquals(RangeExcursion.LOW, sustainedRangeExcursion(samples(79.0, 70.0), 80.0, 160.0))
        assertEquals(RangeExcursion.HIGH, sustainedRangeExcursion(samples(161.0, 172.0), 80.0, 160.0))
        assertNull(sustainedRangeExcursion(samples(161.0, 159.0), 80.0, 160.0))
    }

    @Test fun `glucose chart marks stale signal period with configured signal loss color`() {
        val preferences = context.getSharedPreferences("chart_signal_loss_color", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().putString("themeMode", "DARK").commit()
        val signalLoss = Color.rgb(210, 30, 60)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.GRAPH_SIGNAL_LOSS, signalLoss)
        SugarliciousColors.apply(SugarliciousColorStore.load(preferences))

        val now = System.currentTimeMillis()
        val measured = now - 20L * 60_000L
        val state = TherapyDisplayState(
            receivedAtEpochMs = now,
            glucose = GlucoseState(118.0, GlucoseUnit.MG_DL, measuredAtEpochMs = measured),
            glucoseHistory = listOf(
                GlucoseSample(116.0, measured - 5 * 60_000L),
                GlucoseSample(118.0, measured),
            ),
            target = TargetState(80.0, 160.0),
        )
        val bitmap = render(
            GlucoseDashboardChart(context).apply {
                bind(state, GlucoseUnit.MG_DL, false, 3, showTargetRange = false, clockEpochMs = now)
            },
            230,
        )

        assertTrue(count(bitmap) { it == signalLoss } > 100)
        SugarliciousColors.apply(SugarliciousPalette.defaults())
    }

    @Test fun `glucose chart keeps cached predictions visible right of now divider`() {
        val now = System.currentTimeMillis()
        val state =
            TherapyDisplayState(
                receivedAtEpochMs = now - 10 * 60_000L,
                glucose =
                    GlucoseState(
                        121.0,
                        GlucoseUnit.MG_DL,
                        measuredAtEpochMs = now - 10 * 60_000L,
                    ),
                glucoseHistory =
                    listOf(
                        GlucoseSample(118.0, now - 20 * 60_000L),
                        GlucoseSample(121.0, now - 10 * 60_000L),
                    ),
                glucosePredictions =
                    listOf(
                        GlucosePrediction(
                            PredictionKind.IOB,
                            listOf(
                                GlucoseSample(124.0, now - 8 * 60_000L),
                                GlucoseSample(127.0, now - 3 * 60_000L),
                            ),
                        ),
                    ),
            )
        val viewport = ChartViewport(1).apply { setFutureWindow(15 * 60_000L) }
        val bitmap =
            render(
                GlucoseDashboardChart(context, sharedViewport = viewport).apply {
                    bind(
                        state = state,
                        unit = GlucoseUnit.MG_DL,
                        showPredictions = true,
                        durationHours = 1,
                        showPredictionIob = true,
                        clockEpochMs = now,
                    )
                },
                230,
            )

        val predictionPixels = count(bitmap) {
            Color.blue(it) > 180 && Color.green(it) > 120
        }
        assertTrue("cached prediction=$predictionPixels", predictionPixels > 2)
    }

    @Test fun `target value color is opaque and brighter than a transparent target band`() {
        val translucentGreen = Color.argb(18, 12, 90, 30)
        val result = luminousTargetValueColor(translucentGreen)
        assertEquals(255, Color.alpha(result))
        assertTrue(Color.green(result) > Color.green(translucentGreen))
    }

    @Test
    fun `target history becomes one continuous step path with vertical transitions`() {
        val paths = targetStepPaths(
            samples = listOf(
                TargetSample(100.0, 0L, 10_000L),
                TargetSample(120.0, 10_000L, 20_000L),
                TargetSample(95.0, 20_000L, 30_000L),
            ),
            start = 0L,
            end = 30_000L,
        )

        assertEquals(
            listOf(
                listOf(
                    0L to 100.0,
                    10_000L to 100.0,
                    10_000L to 120.0,
                    20_000L to 120.0,
                    20_000L to 95.0,
                    30_000L to 95.0,
                ),
            ),
            paths,
        )
    }

    @Test
    fun `missing target history remains a visible gap`() {
        val paths = targetStepPaths(
            samples = listOf(
                TargetSample(100.0, 0L, 10_000L),
                TargetSample(120.0, 200_000L, 300_000L),
            ),
            start = 0L,
            end = 300_000L,
        )

        assertEquals(2, paths.size)
    }

    @Test
    fun `temporary target returns to profile exactly at its explicit end`() {
        val paths = targetStepPaths(
            samples = listOf(
                TargetSample(100.0, 0L, 10_000L, temporary = false),
                TargetSample(150.0, 10_000L, 20_000L, temporary = true),
                // The profile value is first observed two minutes later. The renderer must still
                // connect at the authoritative temp-target end.
                TargetSample(100.0, 140_000L, 300_000L, temporary = false),
            ),
            start = 0L,
            end = 300_000L,
        )

        assertEquals(
            listOf(
                0L to 100.0,
                10_000L to 100.0,
                10_000L to 150.0,
                20_000L to 150.0,
                20_000L to 100.0,
                300_000L to 100.0,
            ),
            paths.single(),
        )
    }

    @Test fun `metabolic chart renders independent iob and cob areas`() {
        val now = System.currentTimeMillis()
        val history = (0..5).map { index ->
            TherapyHistorySample(
                now - (5 - index) * 15 * 60_000L,
                totalIob = 0.7 + index * 0.25,
                cobGrams = 8.0 + index * 5,
                insulinActivityUnitsPerMinute = 0.008 + index * 0.002,
                smbUnits = if (index == 2) 0.3 else null,
            )
        }
        val state = TherapyDisplayState(receivedAtEpochMs = now, insulin = InsulinState(totalIob = 1.95), carbs = CarbState(cobGrams = 33.0), therapyHistory = history)
        val bitmap = render(MetabolicDashboardChart(context).apply { bind(state, 6) }, 260)
        val bluePixels = count(bitmap) { Color.blue(it) > 170 && Color.blue(it) > Color.red(it) * 1.2 }
        val orangePixels = count(bitmap) { Color.red(it) > 170 && Color.green(it) > 70 && Color.blue(it) < 120 }
        val smbPixels = count(bitmap) { Color.green(it) > 170 && Color.blue(it) > 150 && Color.red(it) < 100 }
        val activityPixels = count(bitmap) { Color.red(it) > 190 && Color.green(it) > 150 && Color.blue(it) < 120 }
        assertTrue("blue=$bluePixels", bluePixels > 20)
        assertTrue("orange=$orangePixels", orangePixels > 20)
        assertTrue("smb=$smbPixels", smbPixels > 10)
        assertTrue("activity=$activityPixels", activityPixels > 4)
    }

    @Test fun `metabolic chart renders the current time divider through iob and cob`() {
        val now = System.currentTimeMillis()
        val state = TherapyDisplayState(
            receivedAtEpochMs = now,
            insulin = InsulinState(totalIob = 1.2),
            carbs = CarbState(cobGrams = 18.0),
            therapyHistory = listOf(
                TherapyHistorySample(now - 30 * 60_000L, totalIob = 1.4, cobGrams = 24.0),
                TherapyHistorySample(now, totalIob = 1.2, cobGrams = 18.0),
            ),
        )
        val viewport = ChartViewport(6).apply { setFutureWindow(60L * 60_000L) }
        val bitmap = render(MetabolicDashboardChart(context, sharedViewport = viewport).apply { bind(state, 6) }, 260)
        val dividerX = (bitmap.width * 5f / 6f).toInt()
        fun dividerPixels(yRange: IntRange): Int = yRange.sumOf { y ->
            ((dividerX - 3)..(dividerX + 3)).count { x ->
                val color = bitmap.getPixel(x, y)
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                red in 130..170 && green in 130..170 && blue in 130..170
            }
        }

        assertTrue("iobDivider=${dividerPixels(8..118)}", dividerPixels(8..118) > 12)
        assertTrue("cobDivider=${dividerPixels(140..250)}", dividerPixels(140..250) > 12)
    }

    @Test fun `toolkit metabolic scaling adds headroom aligns zero and uses fixed smb sizes`() {
        val iob = toolkitMetabolicRange(listOf(0.5, 2.0))
        val cob = toolkitMetabolicRange(listOf(10.0, 30.0), iob.zeroRatio)
        assertEquals(2.0 * 1.08, iob.maximum, 0.0001)
        assertEquals(-iob.maximum * 0.08, iob.minimum, 0.0001)
        assertEquals(30.0 * 1.08, cob.maximum, 0.0001)
        assertEquals(iob.zeroRatio, cob.zeroRatio, 0.0001)
        assertEquals(9f, toolkitSmbMarkerSide(0.1))
        assertEquals(12f, toolkitSmbMarkerSide(0.25))
        assertEquals(15f, toolkitSmbMarkerSide(0.5))
    }

    @Test fun `metabolic future projections follow recent observed decay`() {
        val now = 10_000_000L
        val history = listOf(
            TherapyHistorySample(now - 10 * 60_000L, totalIob = 1.0, cobGrams = 30.0),
            TherapyHistorySample(now, totalIob = 0.8, cobGrams = 20.0),
        )
        val iob = buildIobProjection(history, now, now + 10 * 60_000L)
        val cob = buildCobProjection(history, now, now + 10 * 60_000L)
        assertEquals(3, iob.size)
        assertEquals(0.8, iob[0].second, 0.0001)
        assertEquals(0.7, iob[1].second, 0.0001)
        assertEquals(0.6, iob[2].second, 0.0001)
        assertEquals(3, cob.size)
        assertEquals(20.0, cob[0].second, 0.0001)
        assertEquals(15.0, cob[1].second, 0.0001)
        assertEquals(10.0, cob[2].second, 0.0001)
    }

    @Test fun `glucose dots use alert color outside display range`() {
        val now = System.currentTimeMillis()
        val state = TherapyDisplayState(
            receivedAtEpochMs = now,
            glucose = GlucoseState(55.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
            glucoseHistory = listOf(62.0, 58.0, 55.0).mapIndexed { index, value ->
                GlucoseSample(value, now - (2 - index) * 5 * 60_000L)
            },
            target = TargetState(80.0, 160.0),
        )
        val bitmap = render(GlucoseDashboardChart(context).apply { bind(state, GlucoseUnit.MG_DL, false, 6) }, 230)
        val redPixels = count(bitmap) { Color.red(it) > 180 && Color.red(it) > Color.green(it) * 1.5 }
        assertTrue("red=$redPixels", redPixels > 2)
    }

    @Test fun `glucose chart compresses sub target range and keeps zero above edge`() {
        val zero = glucoseLogRatio(0.0)
        val low = glucoseLogRatio(80.0)
        val targetHigh = glucoseLogRatio(160.0)
        val maximum = glucoseLogRatio(400.0)
        assertTrue("zero=$zero", zero > 0.0)
        assertTrue("subTarget=${low - zero}", low - zero < targetHigh - low)
        assertEquals(1.0, maximum, 0.0001)
    }

    @Test fun `viewport cannot pan beyond configured future edge`() {
        val now = 10_000_000L
        val viewport = ChartViewport(6)
        viewport.setFutureWindow(0L)
        viewport.pan(-10_000f, 100f)
        assertEquals(0L, viewport.panMs)
        assertEquals(now, viewport.endEpochMs(now))

        viewport.setFutureWindow(60L * 60_000L)
        viewport.pan(-10_000f, 100f)
        assertEquals(0L, viewport.panMs)
        assertEquals(now + 60L * 60_000L, viewport.endEpochMs(now))
    }

    @Test
    fun `viewport zoom out and pan stop at available history and absolute twenty four hours`() {
        val viewport = ChartViewport(6)
        viewport.setAvailablePastWindow(8L * 60L * 60_000L)
        viewport.setHours(24f)
        assertEquals(8f, viewport.hours, 0.0001f)

        viewport.zoom(0.01f)
        assertEquals(8f, viewport.hours, 0.0001f)
        viewport.setHours(2f)
        viewport.pan(100_000f, 100f)
        assertEquals(-6L * 60L * 60_000L, viewport.panMs)

        viewport.setAvailablePastWindow(30L * 60L * 60_000L)
        viewport.setHours(30f)
        assertEquals(24f, viewport.hours, 0.0001f)
    }

    @Test
    fun `available graph window follows real canonical history`() {
        val now = 50L * 60L * 60_000L
        val state = TherapyDisplayState(
            receivedAtEpochMs = now,
            glucose = GlucoseState(120.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
            glucoseHistory = listOf(
                GlucoseSample(110.0, now - 7L * 60L * 60_000L),
                GlucoseSample(120.0, now),
            ),
        )

        assertEquals(7L * 60L * 60_000L, availableGlucoseHistoryWindowMs(state, now))
    }

    private fun render(view: View, height: Int): Bitmap {
        val width = 420
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private fun count(bitmap: Bitmap, predicate: (Int) -> Boolean): Int {
        var result = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (predicate(bitmap.getPixel(x, y))) result++
        return result
    }
}
