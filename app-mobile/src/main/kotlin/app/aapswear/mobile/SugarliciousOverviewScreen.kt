package app.aapswear.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.model.FreshnessPolicy
import app.aapswear.model.GlucosePrediction
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.GlucoseVisualSpec
import app.aapswear.model.LoopVisualState
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import app.aapswear.model.loopPresentation
import app.aapswear.storage.PredictionDisplayTimeline
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun SugarliciousOverviewScreen(
    state: TherapyDisplayState?,
    diagnostics: DiagnosticsSnapshot,
    preferences: DashboardUiPreferences,
    now: Long,
    callbacks: DashboardCallbacks,
) {
    val unit = preferences.unitFor(state)
    val glucose = state?.glucose
    val freshness = FreshnessPolicy.classify(glucose?.measuredAtEpochMs, now)
    val knownGlucose =
        app.aapswear.model.TherapyDisplayFormatter
            .isGlucoseKnown(state)
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    val density = LocalDensity.current
    val screenHeightDp =
        with(density) {
            LocalWindowInfo.current.containerSize.height
                .toDp()
                .value
                .roundToInt()
        }
    val metrics = DashboardLayoutMetrics.forScreenHeight(screenHeightDp)
    val gap = if (preferences.compact || preferences.showMetabolicGraph) 5.dp else 8.dp
    val gapDp = if (preferences.compact || preferences.showMetabolicGraph) 5 else 8
    val visibility =
        DashboardVisibilityState(
            showTherapyDetails = false,
            showMetabolicGraph = preferences.showMetabolicGraph,
        )
    val cgmGraphHeightDp = metrics.cgmGraphHeight(visibility, gapDp)
    val metabolicGraphHeightDp = maxOf(metrics.metabolicChartHeight - 10, 104)
    val overviewHeightCompensationDp = if (preferences.showMetabolicGraph) 8 else 0

    val cgmChartViewport =
        rememberSaveable(
            saver =
                listSaver(
                    save = { viewport ->
                        val state = viewport.savedState()
                        listOf(
                            state.historyHours.toRawBits().toLong(),
                            state.navigationEndEpochMs ?: Long.MIN_VALUE,
                        )
                    },
                    restore = { values ->
                        ChartViewport(preferences.graphHours).apply {
                            restore(
                                GraphViewportSavedState(
                                    historyHours = Float.fromBits(values[0].toInt()),
                                    navigationEndEpochMs = values[1].takeUnless { it == Long.MIN_VALUE },
                                ),
                            )
                        }
                    },
                ),
        ) { ChartViewport(preferences.graphHours) }
    val metabolicChartViewport = cgmChartViewport
    var appliedGraphHours by remember { mutableIntStateOf(preferences.graphHours) }
    var sharedViewportSnapshot by remember(cgmChartViewport, now) {
        mutableStateOf(cgmChartViewport.snapshot(now))
    }
    DisposableEffect(cgmChartViewport, now) {
        val listener = { sharedViewportSnapshot = cgmChartViewport.snapshot(now) }
        cgmChartViewport.addListener(listener)
        listener()
        onDispose { cgmChartViewport.removeListener(listener) }
    }

    val enabledPredictions =
        if (preferences.showCgmGraph && preferences.predictionsEnabled) {
            state
                ?.glucosePredictions
                .orEmpty()
                .filter { it.kind in preferences.enabledPredictionKinds }
        } else {
            emptyList()
        }
    val predictionFutureWindowMs =
        predictionFutureWindowMs(
            enabled = preferences.showCgmGraph && preferences.predictionsEnabled,
            predictions = enabledPredictions,
            now = now,
        )
    val predictionViewportPolicy =
        resolvePredictionViewportPolicy(
            historyHours = cgmChartViewport.hours,
            predictionsEnabled = preferences.showCgmGraph && preferences.predictionsEnabled,
            futureWindowMs = predictionFutureWindowMs,
        )
    val sharedScaleRightBlend =
        predictionViewportPolicy.scaleRightBlendOverride
            ?: predictionScaleRightBlend(
                enabledPredictions,
                now,
                sharedViewportSnapshot.startEpochMs,
                sharedViewportSnapshot.endEpochMs,
            )

    LaunchedEffect(
        preferences.graphHours,
    ) {
        if (preferences.graphHours != appliedGraphHours) {
            cgmChartViewport.setHours(
                preferences.graphHours.toFloat(),
                resetPan = true,
            )
            appliedGraphHours = preferences.graphHours
        }
    }

    LaunchedEffect(
        predictionViewportPolicy.effectiveFutureWindowMs,
    ) {
        cgmChartViewport.setFutureWindow(predictionViewportPolicy.effectiveFutureWindowMs, now)
    }

    val glucoseText = if (knownGlucose && glucose != null) formatGlucose(glucose.valueMgDl, unit) else "—"
    val rangePresentation =
        widgetRangePresentation(
            state = state,
            samples = canonicalWidgetSamples(state, now, 24L * 60L * 60_000L),
            thresholds = preferences.cgmThresholds,
            now = now,
        )
    val glucoseColor =
        (if (TherapyDisplayFormatter.freshness(state, now) == app.aapswear.model.Freshness.SIGNAL_LOSS) Color(0xFF9E9E9E) else SugarliciousColors.color(overviewGlucoseColorRole(rangePresentation.visibleRole))).let { color -> if (displayable || TherapyDisplayFormatter.freshness(state, now) == app.aapswear.model.Freshness.SIGNAL_LOSS) color else color.copy(alpha = 0.62f) }
    val delta = if (displayable) formatDelta(glucose?.deltaMgDl, unit) else "—"
    val age = TherapyDisplayFormatter.ageMinutes(glucose?.measuredAtEpochMs, now)
    val tirStats = calculateTirStats(state, now)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        OverviewInlineHeader(
            onSettings = { callbacks.navigate(DashboardScreen.SETTINGS) },
            diagnostics = diagnostics,
            onWatch = { callbacks.navigate(DashboardScreen.WATCH) },
            sectionGapDp = gapDp,
        )

        GlucoseHeroCard(
            glucoseText = glucoseText,
            glucoseColor = glucoseColor,
            trend = if (displayable) glucose?.trend ?: Trend.UNKNOWN else Trend.UNKNOWN,
            delta = delta,
            age = age,
            unitLabel = unitLabel(unit),
            tirStats = tirStats,
            detailMode = preferences.glucoseTileDetailMode,
            therapyIndicators =
                therapyIndicatorPresentations(
                    state = state.takeIf { displayable },
                    iobMaximumUnits = preferences.iobProgressMaximumUnits,
                    nowEpochMs = now,
                    cobMaximumGrams = preferences.cobProgressMaximumGrams,
                ),
            visualSpec =
                GlucoseVisualSpec.twoByTwoWidgetReference().scaled(
                    preferences.glucoseScalePercent,
                    preferences.trendScalePercent,
                ),
            heightDp = maxOf(metrics.summaryTileHeight + 18 - overviewHeightCompensationDp, 100),
        )

        if (preferences.showCgmGraph) {
            GlucoseGraphSurface(
                state = state,
                preferences = preferences,
                viewport = cgmChartViewport,
                chartHeightDp = cgmGraphHeightDp,
                now = now,
                onGraphHours = callbacks.setGraphHours,
            )
        }

        if (preferences.showMetabolicGraph) {
            MetabolicGraphSurface(
                state = state,
                preferences = preferences,
                viewport = metabolicChartViewport,
                chartHeightDp = metabolicGraphHeightDp,
                now = now,
                scaleRightBlend = sharedScaleRightBlend,
            )
        }
    }
}

internal fun hasVisiblePredictions(
    predictions: List<GlucosePrediction>,
    now: Long,
    viewport: GraphViewportSnapshot,
): Boolean =
    PredictionDisplayTimeline
        .anchor(predictions, now)
        .any { series ->
            series.samples.any { it.measuredAtEpochMs in viewport.startEpochMs..viewport.endEpochMs }
        }

internal fun predictionFutureWindowMs(
    enabled: Boolean,
    predictions: List<GlucosePrediction>,
    now: Long,
): Long = if (enabled) PredictionDisplayTimeline.futureWindowMs(predictions, now) else 0L

@Composable
private fun GlucoseHeroCard(
    glucoseText: String,
    glucoseColor: Color,
    trend: Trend,
    delta: String,
    age: String,
    unitLabel: String,
    tirStats: TirStats,
    detailMode: GlucoseTileDetailMode,
    therapyIndicators: List<TherapyIndicatorPresentation>,
    visualSpec: GlucoseVisualSpec,
    heightDp: Int,
) {
    val shape = RoundedCornerShape(28.dp)
    val fontScale = LocalDensity.current.fontScale
    val stacked = fontScale > 1.2f

    @Composable
    fun GlucosePane(modifier: Modifier) {
        BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val metadata =
                buildAnnotatedString {
                    withStyle(SpanStyle(fontSize = 17.sp)) { append(delta) }
                    withStyle(SpanStyle(fontSize = 14.sp)) { append(" $unitLabel · $age") }
                }
            val measuredWidth = measurer.measure(metadata, LocalTextStyle.current.copy(fontWeight = FontWeight.Bold), softWrap = false).size.width
            val metadataScale = minOf(1f, with(density) { maxWidth.toPx() } / measuredWidth.coerceAtLeast(1))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                GlucoseValueAndTrend(
                    glucoseText,
                    glucoseColor,
                    trend,
                    visualSpec.copy(glucoseTextSize = visualSpec.glucoseTextSize * 1.1f, trendHeight = visualSpec.trendHeight * 1.1f),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = SugarliciousColors.TextPrimary, fontSize = (17 * metadataScale).sp, fontWeight = FontWeight.Bold)) { append(delta) }
                        withStyle(SpanStyle(color = SugarliciousColors.TextSecondary, fontSize = (14 * metadataScale).sp, fontWeight = FontWeight.Bold)) { append(" $unitLabel · ") }
                        withStyle(SpanStyle(color = SugarliciousColors.TextPrimary, fontSize = (14 * metadataScale).sp, fontWeight = FontWeight.Bold)) { append(age) }
                    },
                    color = SugarliciousColors.TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }

    @Composable
    fun DetailsPane(modifier: Modifier) {
        if (detailMode == GlucoseTileDetailMode.TIR) {
            TirProgressColumn(stats = tirStats, modifier = modifier)
        } else {
            TherapyIndicatorRow(indicators = therapyIndicators, modifier = modifier)
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height((heightDp * if (stacked) fontScale * 2.2f else 1f).dp)
            .background(SugarliciousColors.Surface, shape)
            .border(1.dp, SugarliciousColors.Border.copy(alpha = .85f), shape)
            .clip(shape)
            .padding(horizontal = 18.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (stacked) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                GlucosePane(Modifier.fillMaxWidth().weight(1f))
                DetailsPane(Modifier.fillMaxWidth().weight(2f))
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                GlucosePane(Modifier.weight(132f).fillMaxHeight())
                DetailsPane(Modifier.weight(224f).fillMaxHeight())
            }
        }
    }
}

@Composable
internal fun GlucoseValueAndTrend(
    glucoseText: String,
    glucoseColor: Color,
    trend: Trend,
    visualSpec: GlucoseVisualSpec,
    trendStyle: app.aapswear.model.TrendArrowStyle = MobileTrendArrowAppearance.style,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = glucoseText,
            color = glucoseColor,
            fontSize = visualSpec.glucoseTextSize.sp,
            lineHeight = (visualSpec.glucoseTextSize * GlucoseTrendSizing.REFERENCE_GLUCOSE_LINE_HEIGHT_SP / GlucoseTrendSizing.REFERENCE_GLUCOSE_TEXT_SP).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.8).sp,
        )
        Spacer(Modifier.width(6.dp))
        SugarliciousTrendIndicator(trend, arrowSize = visualSpec.trendHeight.dp, color = glucoseColor, style = trendStyle)
    }
}

private data class TirStats(
    val inRange: Int?,
    val below: Int?,
    val above: Int?,
)

private fun calculateTirStats(
    state: TherapyDisplayState?,
    now: Long,
): TirStats {
    val start = now - 24L * 60L * 60_000L
    val targetLow = state?.target?.lowMgDl ?: 80.0
    val targetHigh = state?.target?.highMgDl ?: 160.0
    val samples =
        buildList {
            addAll(state?.glucoseHistory.orEmpty())
            state?.glucose?.let {
                add(
                    app.aapswear.model.GlucoseSample(
                        valueMgDl = it.valueMgDl,
                        measuredAtEpochMs = it.measuredAtEpochMs,
                    ),
                )
            }
        }.filter {
            it.measuredAtEpochMs in start..(now + 5 * 60_000L) &&
                it.valueMgDl in 20.0..1000.0
        }.associateBy { it.measuredAtEpochMs }
            .values

    if (samples.isEmpty()) {
        return TirStats(null, null, null)
    }

    val total = samples.size.toDouble()
    val below = samples.count { it.valueMgDl < targetLow }
    val inRange = samples.count { it.valueMgDl in targetLow..targetHigh }
    val above = samples.count { it.valueMgDl > targetHigh }

    return TirStats(
        inRange = (inRange / total * 100.0).roundToInt(),
        below = (below / total * 100.0).roundToInt(),
        above = (above / total * 100.0).roundToInt(),
    )
}

@Composable
private fun TirProgressColumn(
    stats: TirStats,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        TirProgress(
            modifier = Modifier.fillMaxWidth(),
            percent = stats.above,
            rangeLabel = "Über Ziel",
            accent = SugarliciousColors.color(app.aapswear.mobile.ui.theme.SugarliciousColorRole.PROGRESS_ABOVE),
        )

        TirProgress(
            modifier = Modifier.fillMaxWidth(),
            percent = stats.inRange,
            rangeLabel = "Im Ziel",
            accent = SugarliciousColors.color(app.aapswear.mobile.ui.theme.SugarliciousColorRole.PROGRESS_IN_RANGE),
        )

        TirProgress(
            modifier = Modifier.fillMaxWidth(),
            percent = stats.below,
            rangeLabel = "Unter Ziel",
            accent = SugarliciousColors.color(app.aapswear.mobile.ui.theme.SugarliciousColorRole.PROGRESS_BELOW),
        )
    }
}

@Composable
private fun TirProgress(
    modifier: Modifier,
    percent: Int?,
    rangeLabel: String,
    accent: Color,
) {
    val safePercent = (percent ?: 0).coerceIn(0, 100)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = oneUiMenuTypography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold)
    val labelWidth =
        with(density) {
            measurer
                .measure("100%", labelStyle, softWrap = false)
                .size.width
                .toDp()
        } + 4.dp
    val track = SugarliciousColors.SurfaceRaised
    Row(
        modifier =
            modifier.heightIn(min = 24.dp).semantics(mergeDescendants = true) {
                contentDescription = rangeLabel
                stateDescription = percent?.let { "$safePercent%" } ?: "Keine Daten"
                if (percent != null) progressBarRangeInfo = ProgressBarRangeInfo(safePercent.toFloat(), 0f..100f)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Health-style data bars need their own full-height pill geometry: the native
        // horizontal widget keeps a thin drawable even inside a taller view.
        Canvas(Modifier.weight(1f).height(14.dp)) {
            val radius =
                androidx.compose.ui.geometry
                    .CornerRadius(size.height / 2f)
            drawRoundRect(color = track, cornerRadius = radius)
            if (safePercent > 0) {
                drawRoundRect(
                    color = accent,
                    size =
                        androidx.compose.ui.geometry
                            .Size(size.width * safePercent / 100f, size.height),
                    cornerRadius = radius,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = percent?.let { "$safePercent%" } ?: "—",
            modifier = Modifier.width(labelWidth).clearAndSetSemantics { },
            color = SugarliciousColors.TextPrimary,
            style = labelStyle,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
        )
    }
}

internal data class OverviewLoopTileState(
    val iconRes: Int,
    val label: String,
    val accent: Color,
)

internal fun overviewLoopTileState(state: TherapyDisplayState?): OverviewLoopTileState {
    val presentation = loopPresentation(state)
    val (icon, color) =
        when (presentation.visualState) {
            LoopVisualState.PUMP_SUSPENDED -> R.drawable.ic_pump_suspended to SugarliciousColors.Red
            LoopVisualState.SUSPENDED -> R.drawable.ic_loop_suspended to SugarliciousColors.Orange
            LoopVisualState.DEACTIVATED,
            LoopVisualState.UNKNOWN,
            -> R.drawable.ic_loop_deactivated to SugarliciousColors.TextSecondary
            LoopVisualState.CLOSED -> R.drawable.ic_loop_closed to SugarliciousColors.Green
        }
    return OverviewLoopTileState(icon, presentation.label, color)
}

@Composable
private fun GlucoseGraphSurface(
    state: TherapyDisplayState?,
    preferences: DashboardUiPreferences,
    viewport: ChartViewport,
    chartHeightDp: Int,
    now: Long,
    onGraphHours: (Int) -> Unit,
) {
    var visibleHours by remember(viewport) { mutableFloatStateOf(viewport.visibleHours) }
    val scaleTapInteraction = remember { MutableInteractionSource() }
    DisposableEffect(viewport) {
        val listener = { visibleHours = viewport.visibleHours }
        viewport.addListener(listener)
        onDispose { viewport.removeListener(listener) }
    }
    Box(modifier = Modifier.fillMaxWidth().height(chartHeightDp.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                GlucoseDashboardChart(
                    context = it,
                    sharedViewport = viewport,
                )
            },
            update = {
                it.bindOverview(state, preferences, now)
            },
        )
        Text(
            text = formatVisibleGraphHours(visibleHours),
            color = Color.Transparent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .clickable(indication = null, interactionSource = scaleTapInteraction) {
                        val currentIndex = OVERVIEW_GRAPH_HOUR_OPTIONS.indexOf(preferences.graphHours).coerceAtLeast(0)
                        val next = OVERVIEW_GRAPH_HOUR_OPTIONS[(currentIndex + 1) % OVERVIEW_GRAPH_HOUR_OPTIONS.size]
                        onGraphHours(next)
                    }.padding(start = 9.dp, top = 7.dp, end = 8.dp, bottom = 7.dp),
        )
    }
}

internal fun formatVisibleGraphHours(hours: Float): String {
    val bounded = hours.coerceIn(1f, 24f)
    val rounded = bounded.roundToInt()
    return if (kotlin.math.abs(bounded - rounded) < 0.05f) {
        "${rounded}h"
    } else {
        String.format(Locale.getDefault(), "%.1fh", bounded)
    }
}

internal fun GlucoseDashboardChart.bindOverview(
    state: TherapyDisplayState?,
    preferences: DashboardUiPreferences,
    nowEpochMs: Long,
) {
    bind(
        state = state,
        unit = preferences.unitFor(state),
        showPredictions = preferences.predictionsEnabled,
        durationHours = preferences.graphHours,
        showTargetRange = true,
        showTargetValue = preferences.showCgmTargetValue,
        showCurrentBasal = preferences.showCgmCurrentBasal,
        showBaseBasal = preferences.showCgmBaseBasal,
        showActivity = preferences.showCgmActivity,
        showPredictionIob = preferences.showCgmPredictionIob,
        showPredictionCob = preferences.showCgmPredictionCob,
        showPredictionUam = preferences.showCgmPredictionUam,
        showPredictionZeroTemp = preferences.showCgmPredictionZeroTemp,
        cgmDotRadiusDp = preferences.cgmDotRadiusDp,
        cgmDotOutlineEnabled = preferences.cgmDotOutlineEnabled,
        cgmDotOutlineWidthDp = preferences.cgmDotOutlineWidthDp,
        cgmHistoricalDotOutlineEnabled = preferences.cgmHistoricalDotOutlineEnabled,
        cgmHistoricalDotOutlineWidthDp = preferences.cgmHistoricalDotOutlineWidthDp,
        cgmCurrentDotOutlineWidthDp = preferences.cgmCurrentDotOutlineWidthDp,
        graphMaximumMgDl = preferences.graphMaximumMgDl,
        graphMinimumMgDl = preferences.graphMinimumMgDl,
        graphScaleMode = preferences.graphScaleMode,
        clockEpochMs = nowEpochMs,
    )
}

@Composable
private fun MetabolicGraphSurface(
    state: TherapyDisplayState?,
    preferences: DashboardUiPreferences,
    viewport: ChartViewport,
    chartHeightDp: Int,
    now: Long,
    scaleRightBlend: Float,
) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(chartHeightDp.dp),
        factory = {
            MetabolicDashboardChart(
                context = it,
                sharedViewport = viewport,
            )
        },
        update = {
            it.bind(
                state,
                preferences.graphHours,
                TreatmentMarkerVisibility(
                    mealBolus = preferences.showMealBolusMarkers,
                    correction = preferences.showCorrectionMarkers,
                    smb = preferences.showSmbMarkers,
                    mealCarbs = preferences.showMealCarbMarkers,
                    eCarbs = preferences.showECarbMarkers,
                ),
                scaleRightBlend = scaleRightBlend,
                showTimeAxis = !preferences.showCgmGraph,
                iobGraphScaleMode = preferences.iobGraphScaleMode,
                cobGraphScaleMode = preferences.cobGraphScaleMode,
                iobMaximumUnits = preferences.iobGraphMaximumUnits,
                cobMaximumGrams = preferences.cobGraphMaximumGrams,
                showPredictionDivider = preferences.showPredictionDivider,
                clockEpochMs = now,
            )
        },
    )
}

private fun formatGlucose(
    valueMgDl: Double,
    unit: GlucoseUnit,
): String =
    if (unit == GlucoseUnit.MMOL_L) {
        String.format(Locale.getDefault(), "%.1f", valueMgDl / 18.0)
    } else {
        valueMgDl.roundToInt().toString()
    }

private fun formatDelta(
    valueMgDl: Double?,
    unit: GlucoseUnit,
): String {
    if (valueMgDl == null) return "—"
    val converted = if (unit == GlucoseUnit.MMOL_L) valueMgDl / 18.0 else valueMgDl
    val prefix = if (converted >= 0.0) "+" else ""
    val body =
        if (unit == GlucoseUnit.MMOL_L) {
            String.format(Locale.getDefault(), "%.1f", converted)
        } else {
            converted.roundToInt().toString()
        }
    return prefix + body
}

private fun formatNumber(
    value: Double?,
    digits: Int,
): String = value?.let { String.format(Locale.getDefault(), "%.${digits}f", it) } ?: "—"

private fun unitLabel(unit: GlucoseUnit): String = if (unit == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL"

internal fun overviewGlucoseColorRole(role: WidgetColorRole): SugarliciousColorRole =
    when (role) {
        WidgetColorRole.VERY_HIGH -> SugarliciousColorRole.GLUCOSE_VERY_HIGH
        WidgetColorRole.URGENT_LOW -> SugarliciousColorRole.GLUCOSE_VERY_LOW
        WidgetColorRole.HIGH -> SugarliciousColorRole.GLUCOSE_HIGH
        WidgetColorRole.LOW -> SugarliciousColorRole.GLUCOSE_LOW
        WidgetColorRole.IN_RANGE -> SugarliciousColorRole.GLUCOSE_IN_RANGE
        else -> SugarliciousColorRole.TEXT_PRIMARY
    }
