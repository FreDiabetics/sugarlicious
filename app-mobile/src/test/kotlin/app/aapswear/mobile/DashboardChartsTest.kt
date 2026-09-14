package app.aapswear.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.mobile.ui.theme.SugarliciousPalette
import app.aapswear.model.CarbState
import app.aapswear.model.GlucosePrediction
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.InsulinState
import app.aapswear.model.PredictionKind
import app.aapswear.model.RangeExcursion
import app.aapswear.model.TargetSample
import app.aapswear.model.TargetState
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TherapyEvent
import app.aapswear.model.TherapyEventKind
import app.aapswear.model.TherapyHistorySample
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
        val state =
            TherapyDisplayState(
                receivedAtEpochMs = now,
                glucose = GlucoseState(129.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
                glucoseHistory =
                    listOf(100.0, 118.0, 112.0, 129.0).mapIndexed { index, value ->
                        GlucoseSample(
                            value,
                            now - (3 - index) * 15 * 60_000L,
                        )
                    },
                glucosePredictions =
                    listOf(
                        GlucosePrediction(
                            PredictionKind.IOB,
                            listOf(129.0, 120.0, 108.0).mapIndexed { index, value ->
                                GlucoseSample(
                                    value,
                                    now + index * 5 * 60_000L,
                                )
                            },
                        ),
                    ),
                target = TargetState(80.0, 160.0, valueMgDl = 100.0),
            )
        val viewport =
            ChartViewport(6).apply {
                setFutureWindow(15L * 60_000L)
            }
        val bitmap =
            render(
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
        val inRangePixels =
            count(bitmap) {
                Color.green(it) > 150 && Color.green(it) > Color.red(it) * 1.3
            }
        val predictionPixels = count(bitmap) { Color.blue(it) > 180 && Color.green(it) > 120 }
        assertTrue("inRange=$inRangePixels", inRangePixels > 20)
        assertTrue("prediction=$predictionPixels", predictionPixels > 2)
    }

    @Test fun `in range picker drives the graph while high and low stay transparent without sustained excursion`() {
        val preferences = context.getSharedPreferences("chart_region_colors", android.content.Context.MODE_PRIVATE)
        preferences
            .edit()
            .clear()
            .putString("themeMode", "DARK")
            .commit()
        val high = Color.rgb(25, 40, 220)
        val inRange = Color.rgb(30, 210, 70)
        val low = Color.rgb(225, 35, 55)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.RANGE_HIGH, high)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.RANGE_IN_RANGE, inRange)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.RANGE_LOW, low)
        SugarliciousColors.apply(SugarliciousColorStore.load(preferences))

        val now = System.currentTimeMillis()
        val state =
            TherapyDisplayState(
                receivedAtEpochMs = now,
                glucose = GlucoseState(123.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
                glucoseHistory = listOf(GlucoseSample(123.0, now)),
                target = TargetState(80.0, 160.0),
            )
        val bitmap =
            render(
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

    @Test fun `range backgrounds continue across the prediction side`() {
        val preferences = context.getSharedPreferences("chart_prediction_range_color", android.content.Context.MODE_PRIVATE)
        preferences
            .edit()
            .clear()
            .putString("themeMode", "DARK")
            .commit()
        val inRange = Color.rgb(31, 211, 71)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.RANGE_IN_RANGE, inRange)
        SugarliciousColors.apply(SugarliciousColorStore.load(preferences))
        val now = System.currentTimeMillis()
        val state =
            TherapyDisplayState(
                receivedAtEpochMs = now,
                glucose = GlucoseState(120.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
                glucoseHistory = listOf(GlucoseSample(120.0, now)),
                target = TargetState(80.0, 160.0),
            )
        val viewport = ChartViewport(6).apply { setFutureWindow(60L * 60_000L, now) }
        val bitmap =
            render(
                GlucoseDashboardChart(context, sharedViewport = viewport).apply {
                    bind(state, GlucoseUnit.MG_DL, false, 6, showTargetRange = true, clockEpochMs = now)
                },
                230,
            )

        val rightPredictionLanePixels =
            (0 until bitmap.height).sumOf { y ->
                ((bitmap.width * 0.90).toInt() until (bitmap.width * 0.96).toInt()).count { x -> bitmap.getPixel(x, y) == inRange }
            }
        assertTrue("right prediction range pixels=$rightPredictionLanePixels", rightPredictionLanePixels > 20)
        SugarliciousColors.apply(SugarliciousPalette.defaults())
    }

    @Test fun `jetzt label is anchored at the prediction divider instead of the right edge`() {
        assertEquals(360f, currentTimeLabelAnchor(dividerX = 360f, plotRight = 420f, edgePadding = 3f))
        assertEquals(417f, currentTimeLabelAnchor(dividerX = 425f, plotRight = 420f, edgePadding = 3f))
    }

    @Test fun `range excursion requires two consecutive valid values`() {
        val now = 10_000_000L

        fun samples(vararg values: Double) =
            values.mapIndexed { index, value ->
                GlucoseSample(value, now - (values.lastIndex - index) * 5 * 60_000L)
            }

        assertNull(sustainedRangeExcursion(samples(79.0), 80.0, 160.0))
        assertEquals(RangeExcursion.LOW, sustainedRangeExcursion(samples(79.0, 70.0), 80.0, 160.0))
        assertEquals(RangeExcursion.HIGH, sustainedRangeExcursion(samples(161.0, 172.0), 80.0, 160.0))
        assertNull(sustainedRangeExcursion(samples(161.0, 159.0), 80.0, 160.0))
    }

    @Test fun `glucose chart marks stale signal period with configured signal loss color`() {
        val preferences = context.getSharedPreferences("chart_signal_loss_color", android.content.Context.MODE_PRIVATE)
        preferences
            .edit()
            .clear()
            .putString("themeMode", "DARK")
            .commit()
        val signalLoss = Color.rgb(210, 30, 60)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.GRAPH_SIGNAL_LOSS, signalLoss)
        SugarliciousColors.apply(SugarliciousColorStore.load(preferences))

        val now = System.currentTimeMillis()
        val measured = now - 20L * 60_000L
        val state =
            TherapyDisplayState(
                receivedAtEpochMs = now,
                glucose = GlucoseState(118.0, GlucoseUnit.MG_DL, measuredAtEpochMs = measured),
                glucoseHistory =
                    listOf(
                        GlucoseSample(116.0, measured - 5 * 60_000L),
                        GlucoseSample(118.0, measured),
                    ),
                target = TargetState(80.0, 160.0),
            )
        val bitmap =
            render(
                GlucoseDashboardChart(context).apply {
                    bind(state, GlucoseUnit.MG_DL, false, 3, showTargetRange = false, clockEpochMs = now)
                },
                230,
            )

        assertTrue(count(bitmap) { it == signalLoss } > 100)
        SugarliciousColors.apply(SugarliciousPalette.defaults())
    }

    @Test fun `stale signal keeps confirmed high background until a new valid transition`() {
        val preferences = context.getSharedPreferences("chart_stale_high_color", android.content.Context.MODE_PRIVATE)
        preferences
            .edit()
            .clear()
            .putString("themeMode", "DARK")
            .commit()
        val high = Color.rgb(213, 151, 17)
        SugarliciousColorStore.save(preferences, SugarliciousColorRole.RANGE_HIGH, high)
        SugarliciousColors.apply(SugarliciousColorStore.load(preferences))

        val now = System.currentTimeMillis()
        val measured = now - 20L * 60_000L
        val state =
            TherapyDisplayState(
                receivedAtEpochMs = now,
                glucose = GlucoseState(176.0, GlucoseUnit.MG_DL, measuredAtEpochMs = measured),
                glucoseHistory =
                    listOf(
                        GlucoseSample(170.0, measured - 5L * 60_000L),
                        GlucoseSample(176.0, measured),
                    ),
                target = TargetState(80.0, 160.0),
            )
        val bitmap =
            render(
                GlucoseDashboardChart(context).apply {
                    bind(state, GlucoseUnit.MG_DL, false, 3, showTargetRange = true, clockEpochMs = now)
                },
                230,
            )

        assertTrue(
            count(bitmap) { Color.red(it) > 80 && Color.green(it) > 45 && Color.blue(it) < 45 } > 100,
        )
        SugarliciousColors.apply(SugarliciousPalette.defaults())
    }

    @Test fun `glucose chart keeps future predictions at their real timestamps right of boundary`() {
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
                                GlucoseSample(121.0, now),
                                GlucoseSample(124.0, now + 5 * 60_000L),
                                GlucoseSample(127.0, now + 10 * 60_000L),
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

        val predictionPixels =
            count(bitmap) {
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
        val paths =
            targetStepPaths(
                samples =
                    listOf(
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
        val paths =
            targetStepPaths(
                samples =
                    listOf(
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
        val paths =
            targetStepPaths(
                samples =
                    listOf(
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
        val history =
            (0..5).map { index ->
                TherapyHistorySample(
                    now - (5 - index) * 15 * 60_000L,
                    totalIob = 0.7 + index * 0.25,
                    cobGrams = 8.0 + index * 5,
                    insulinActivityUnitsPerMinute = 0.008 + index * 0.002,
                    smbUnits = if (index == 2) 0.3 else null,
                )
            }
        val state =
            TherapyDisplayState(
                receivedAtEpochMs = now,
                insulin = InsulinState(totalIob = 1.95),
                carbs = CarbState(cobGrams = 33.0),
                therapyHistory = history,
            )
        val bitmap = render(MetabolicDashboardChart(context).apply { bind(state, 6) }, 260)
        val bluePixels = count(bitmap) { Color.blue(it) > 170 && Color.blue(it) > Color.red(it) * 1.2 }
        val orangePixels = count(bitmap) { Color.red(it) > 170 && Color.green(it) > 70 && Color.blue(it) < 120 }
        val smbPixels = count(bitmap) { Color.green(it) > 170 && Color.blue(it) > 150 && Color.red(it) < 100 }
        val activityPixels = count(bitmap) { Color.red(it) > 190 && Color.green(it) > 150 && Color.blue(it) < 120 }
        assertTrue("blue=$bluePixels", bluePixels > 20)
        assertTrue("orange=$orangePixels", orangePixels > 20)
        // Rasterization may move one antialiased edge pixel when the Y transform changes.
        assertTrue("smb=$smbPixels", smbPixels >= 9)
        assertTrue("activity=$activityPixels", activityPixels > 4)
    }

    @Test fun `metabolic chart renders the current time divider through iob and cob`() {
        val now = System.currentTimeMillis()
        val state =
            TherapyDisplayState(
                receivedAtEpochMs = now,
                insulin = InsulinState(totalIob = 1.2),
                carbs = CarbState(cobGrams = 18.0),
                therapyHistory =
                    listOf(
                        TherapyHistorySample(now - 30 * 60_000L, totalIob = 1.4, cobGrams = 24.0),
                        TherapyHistorySample(now, totalIob = 1.2, cobGrams = 18.0),
                    ),
            )
        val viewport = ChartViewport(6).apply { setFutureWindow(60L * 60_000L) }
        val bitmap = render(MetabolicDashboardChart(context, sharedViewport = viewport).apply { bind(state, 6) }, 260)
        val scaleEnd = (38f * context.resources.displayMetrics.density).toInt()
        val dividerX = (scaleEnd + (bitmap.width - scaleEnd) * 5f / 6f).toInt()
        assertTrue("scaleEnd=$scaleEnd width=${bitmap.width}", scaleEnd < bitmap.width)
        assertTrue("dividerX=$dividerX scaleEnd=$scaleEnd", dividerX > scaleEnd)
    }

    @Test fun `metabolic markers retain their AndroidAPS size thresholds`() {
        assertEquals(7f, toolkitSmbMarkerSide(0.1))
        assertEquals(11f, toolkitSmbMarkerSide(0.25))
        assertEquals(11f, toolkitSmbMarkerSide(0.5))
    }

    @Test fun `static metabolic scales keep cob iob and activity values fixed while viewport pans`() {
        val session = app.aapswear.model.GraphScaleSession()
        val now = 20_000_000L
        val all =
            listOf(
                TherapyHistorySample(now - 6 * 60 * 60_000L, totalIob = 0.5, cobGrams = 10.0, insulinActivityUnitsPerMinute = 0.01),
                TherapyHistorySample(now - 3 * 60 * 60_000L, totalIob = 2.0, cobGrams = 80.0, insulinActivityUnitsPerMinute = 0.05),
                TherapyHistorySample(now, totalIob = 1.0, cobGrams = 30.0, insulinActivityUnitsPerMinute = 0.02),
            )
        val first = resolveMetabolicScales(session, app.aapswear.model.CgmGraphScaleMode.STATIC, all, all.take(2))
        val second = resolveMetabolicScales(session, app.aapswear.model.CgmGraphScaleMode.STATIC, all, all.drop(1))

        assertEquals(first.iob.ratio(1.0), second.iob.ratio(1.0), 0.0)
        assertEquals(first.cob.ratio(30.0), second.cob.ratio(30.0), 0.0)
        assertEquals(first.activity.ratio(0.02), second.activity.ratio(0.02), 0.0)
    }

    @Test fun `dynamic metabolic scales react to different visible cob and activity ranges`() {
        val session = app.aapswear.model.GraphScaleSession()
        val now = 20_000_000L
        val low =
            listOf(
                TherapyHistorySample(now - 10 * 60_000L, totalIob = 0.5, cobGrams = 10.0, insulinActivityUnitsPerMinute = 0.01),
                TherapyHistorySample(now, totalIob = 1.0, cobGrams = 20.0, insulinActivityUnitsPerMinute = 0.02),
            )
        val high =
            listOf(
                TherapyHistorySample(now - 10 * 60_000L, totalIob = 2.0, cobGrams = 60.0, insulinActivityUnitsPerMinute = 0.05),
                TherapyHistorySample(now, totalIob = 4.0, cobGrams = 100.0, insulinActivityUnitsPerMinute = 0.10),
            )
        val all = low + high
        val first = resolveMetabolicScales(session, app.aapswear.model.CgmGraphScaleMode.DYNAMIC, all, low)
        val second = resolveMetabolicScales(session, app.aapswear.model.CgmGraphScaleMode.DYNAMIC, all, high)

        assertTrue(first.cob.bounds != second.cob.bounds)
        assertTrue(first.activity.bounds != second.activity.bounds)
    }

    @Test fun `static axis bounds survive graph session recreation`() {
        val preferences = context.getSharedPreferences("static_graph_scales", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val store = StaticGraphScaleStore(preferences)
        val firstSession = app.aapswear.model.GraphScaleSession()
        store.restore(firstSession)
        val first =
            firstSession.resolve(
                axis = app.aapswear.model.GraphAxis.COB,
                mode = app.aapswear.model.CgmGraphScaleMode.STATIC,
                seedValues = listOf(0.0, 40.0),
                visibleValues = listOf(0.0, 40.0),
                fallbackBounds = app.aapswear.model.GraphBounds(0.0, 10.0),
                minimumSpan = 1.0,
            )
        store.persist(app.aapswear.model.GraphAxis.COB, first)

        val recreatedSession = app.aapswear.model.GraphScaleSession()
        store.restore(recreatedSession)
        val afterRestartAndNewData =
            recreatedSession.resolve(
                axis = app.aapswear.model.GraphAxis.COB,
                mode = app.aapswear.model.CgmGraphScaleMode.STATIC,
                seedValues = listOf(0.0, 40.0, 120.0),
                visibleValues = listOf(40.0, 120.0),
                fallbackBounds = app.aapswear.model.GraphBounds(0.0, 10.0),
                minimumSpan = 1.0,
            )

        assertEquals(first.bounds, afterRestartAndNewData.bounds)
    }

    @Test fun `metabolic chart renders classified bolus carb and ecarb events`() {
        val now = System.currentTimeMillis()
        val history =
            listOf(
                TherapyHistorySample(now - 60 * 60_000L, totalIob = 0.3, cobGrams = 5.0),
                TherapyHistorySample(now - 30 * 60_000L, totalIob = 2.0, cobGrams = 60.0),
                TherapyHistorySample(now, totalIob = 1.4, cobGrams = 35.0),
            )
        val events =
            listOf(
                TherapyEvent("meal-bolus", TherapyEventKind.MEAL_BOLUS, now - 45 * 60_000L, 7.6),
                TherapyEvent("smb", TherapyEventKind.SMB, now - 20 * 60_000L, 0.2),
                TherapyEvent("correction", TherapyEventKind.MANUAL_CORRECTION, now - 15 * 60_000L, 1.0),
                TherapyEvent("meal-carbs", TherapyEventKind.MEAL_CARBS, now - 45 * 60_000L, 60.0),
                TherapyEvent("ecarbs", TherapyEventKind.ECARBS, now - 10 * 60_000L, 2.0),
            )
        val bitmap =
            render(
                MetabolicDashboardChart(context).apply {
                    bind(TherapyDisplayState(receivedAtEpochMs = now, therapyHistory = history, therapyEvents = events), 3)
                },
                260,
            )
        assertTrue(count(bitmap) { Color.blue(it) > 150 && Color.blue(it) > Color.red(it) } > 30)
        assertTrue(count(bitmap) { Color.red(it) > 160 && Color.green(it) > 70 && Color.blue(it) < 140 } > 30)
    }

    @Test fun `bolus marker sizes use the three requested discrete thresholds`() {
        assertEquals(7f, bolusMarkerSide(0.1))
        assertEquals(9f, bolusMarkerSide(0.2))
        assertEquals(11f, bolusMarkerSide(0.5))
        assertEquals(13f, bolusMarkerSide(1.5))
        assertEquals(16f, bolusMarkerSide(1.6))
        assertEquals(16f, bolusMarkerSide(7.6))
    }

    @Test fun `event curve position is interpolated at the real timestamp`() {
        val values =
            listOf(
                TherapyHistorySample(1_000L, totalIob = 1.0, cobGrams = 10.0),
                TherapyHistorySample(3_000L, totalIob = 3.0, cobGrams = 30.0),
            )
        assertEquals(2.0, interpolateTherapyValue(values, 2_000L, iob = true)!!, 0.0001)
        assertEquals(20.0, interpolateTherapyValue(values, 2_000L, iob = false)!!, 0.0001)
    }

    @Test fun `extended carbs become readable simulation markers across their duration`() {
        val start = 1_000_000L
        val markers =
            expandECarbSimulation(
                TherapyEvent("ecarbs", TherapyEventKind.ECARBS, start, 50.0, durationMinutes = 180),
            )
        assertEquals(12, markers.size)
        assertEquals(start, markers.first().timestampEpochMs)
        assertEquals(start + 165L * 60_000L, markers.last().timestampEpochMs)
        assertEquals(50.0, markers.sumOf { it.amount }, 0.0001)
        assertTrue(markers.all { it.kind == TherapyEventKind.ECARBS && it.amount >= 1.0 })
        assertEquals(10, markers.count { it.amount == 4.0 })
        assertEquals(2, markers.count { it.amount == 5.0 })

        val smallSimulation =
            expandECarbSimulation(
                TherapyEvent("small-ecarbs", TherapyEventKind.ECARBS, start, 2.0, durationMinutes = 60),
            )
        assertEquals(2, smallSimulation.size)
        assertTrue(smallSimulation.all { it.amount >= 1.0 })
        assertEquals(start + 30L * 60_000L, smallSimulation.last().timestampEpochMs)

        val fractionalSimulation =
            expandECarbSimulation(
                TherapyEvent("fractional-ecarbs", TherapyEventKind.ECARBS, start, 2.4, durationMinutes = 60),
            )
        assertEquals(listOf(1.0, 1.4), fractionalSimulation.map { it.amount })
        assertTrue(fractionalSimulation.all { it.amount >= 1.0 })
    }

    @Test fun `sub gram ecarb entries cannot become readable treatment markers`() {
        val events =
            listOf(
                TherapyEvent("tiny", TherapyEventKind.ECARBS, 1_000L, 0.4),
                TherapyEvent("valid", TherapyEventKind.ECARBS, 2_000L, 1.0),
            )
        val drawable = events.flatMap(::expandECarbSimulation).filterNot { it.kind == TherapyEventKind.ECARBS && it.amount < 1.0 }
        assertEquals(listOf("valid"), drawable.map { it.id })
        assertTrue(drawable.all { it.amount >= 1.0 })
    }

    @Test fun `insulin activity history and supplied prediction share one real boundary point`() {
        val boundary = 10_000L
        val (actual, prediction) =
            continuousActivitySeries(
                actual = listOf(0L to 0.01, 5_000L to 0.02, boundary to 0.03),
                future = listOf(boundary to 0.03, 15_000L to 0.02, 20_000L to 0.01),
                boundaryTime = boundary,
            )
        assertEquals(boundary, actual.last().first)
        assertEquals(actual.last(), prediction.first())
    }

    @Test fun `CGM insulin activity uses AndroidAPS eighty percent of full graph height`() {
        val now = System.currentTimeMillis()
        val history =
            (0..4).map { index ->
                TherapyHistorySample(
                    measuredAtEpochMs = now - (4 - index) * 5L * 60_000L,
                    insulinActivityUnitsPerMinute = 0.01 + index * 0.01,
                )
            }
        val state =
            TherapyDisplayState(
                receivedAtEpochMs = now,
                glucose = GlucoseState(120.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
                glucoseHistory = listOf(GlucoseSample(115.0, now - 5L * 60_000L), GlucoseSample(120.0, now)),
                therapyHistory = history,
                target = TargetState(80.0, 160.0),
            )
        val bitmap =
            render(
                GlucoseDashboardChart(context).apply {
                    bind(state, GlucoseUnit.MG_DL, false, 3, showActivity = true, clockEpochMs = now)
                },
                230,
            )
        val activityTop =
            (0 until bitmap.height).firstOrNull { y ->
                (0 until bitmap.width).any { x -> bitmap.getPixel(x, y) == Color.rgb(242, 201, 76) }
            } ?: bitmap.height
        assertTrue("activityTop=$activityTop", activityTop < bitmap.height * 0.35)
    }

    @Test fun `glucose dots use alert color outside display range`() {
        val now = System.currentTimeMillis()
        val state =
            TherapyDisplayState(
                receivedAtEpochMs = now,
                glucose = GlucoseState(55.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
                glucoseHistory =
                    listOf(62.0, 58.0, 55.0).mapIndexed { index, value ->
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

    @Test fun `configured glucose maximum becomes the visible top of the graph`() {
        assertEquals(1.0, glucoseLogRatio(300.0, 300.0), 0.0001)
        assertTrue(glucoseLogRatio(200.0, 300.0) < 1.0)
        assertEquals(1.0, glucoseLogRatio(600.0, 300.0), 0.0001)
    }

    @Test fun `dynamic scale expands immediately but resists minor jitter`() {
        val previous = app.aapswear.model.CgmGraphYScale(app.aapswear.model.CgmGraphScaleMode.DYNAMIC, 60.0, 220.0)
        assertEquals(previous, stableCgmScale(previous, previous.copy(minimumMgDl = 65.0, maximumMgDl = 215.0)))
        assertEquals(260.0, stableCgmScale(previous, previous.copy(maximumMgDl = 260.0)).maximumMgDl, 0.0)
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
        val now = System.currentTimeMillis()
        val viewport = ChartViewport(6)
        viewport.setAvailablePastWindow(8L * 60L * 60_000L, now)
        viewport.setHours(24f)
        assertEquals(8f, viewport.hours, 0.0001f)

        viewport.zoom(0.01f)
        assertEquals(8f, viewport.hours, 0.0001f)
        viewport.setHours(2f)
        viewport.pan(100_000f, 100f, now)
        assertEquals(-6L * 60L * 60_000L, viewport.panMs)
        val boundaryEnd = viewport.endEpochMs(now)
        repeat(20) { viewport.pan(100_000f, 100f, now) }
        assertEquals(boundaryEnd, viewport.endEpochMs(now))

        viewport.setAvailablePastWindow(30L * 60L * 60_000L, now)
        viewport.setHours(30f)
        assertEquals(24f, viewport.hours, 0.0001f)
    }

    @Test fun `visible graph hour label follows manual fractional zoom`() {
        assertEquals("3h", formatVisibleGraphHours(3f))
        assertEquals("3.6h", formatVisibleGraphHours(3.6f).replace(',', '.'))
        assertEquals("24h", formatVisibleGraphHours(120f))
    }

    @Test fun `target dash phase stays anchored to graph content while path moves`() {
        assertEquals(0f, contentAnchoredDashPhase(100f, 6f), 0.0001f)
        assertEquals(0f, contentAnchoredDashPhase(108f, 6f), 0.0001f)
        assertEquals(0f, contentAnchoredDashPhase(98f, 6f), 0.0001f)
    }

    @Test
    fun `available graph window follows real canonical history`() {
        val now = 50L * 60L * 60_000L
        val state =
            TherapyDisplayState(
                receivedAtEpochMs = now,
                glucose = GlucoseState(120.0, GlucoseUnit.MG_DL, measuredAtEpochMs = now),
                glucoseHistory =
                    listOf(
                        GlucoseSample(110.0, now - 7L * 60L * 60_000L),
                        GlucoseSample(120.0, now),
                    ),
            )

        assertEquals(7L * 60L * 60_000L, availableGlucoseHistoryWindowMs(state, now))
    }

    private fun render(
        view: View,
        height: Int,
    ): Bitmap {
        val width = 420
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private fun count(
        bitmap: Bitmap,
        predicate: (Int) -> Boolean,
    ): Int {
        var result = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (predicate(bitmap.getPixel(x, y))) result++
        return result
    }
}
