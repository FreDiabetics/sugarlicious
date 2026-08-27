package app.aapswear.mobile

import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.model.Freshness
import app.aapswear.model.FreshnessPolicy
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.CgmRangeClass
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
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
    val displayable = freshness == Freshness.CURRENT || freshness == Freshness.DELAYED
    val density = LocalDensity.current
    val screenHeightDp = with(density) {
        LocalWindowInfo.current.containerSize.height.toDp().value.roundToInt()
    }
    val metrics = DashboardLayoutMetrics.forScreenHeight(screenHeightDp)
    val gap = if (preferences.compact || preferences.showMetabolicGraph) 5.dp else 8.dp
    val baseGraphHeightDp = maxOf(
        metrics.metabolicChartHeight - 18,
        96,
    )
    val matchedGraphHeightDp = baseGraphHeightDp + 8
    val cgmGraphHeightDp = if (preferences.showMetabolicGraph) matchedGraphHeightDp else baseGraphHeightDp
    val metabolicGraphHeightDp = matchedGraphHeightDp
    val overviewHeightCompensationDp = if (preferences.showMetabolicGraph) 8 else 0

    val cgmChartViewport =
        remember {
            ChartViewport(
                preferences.graphHours,
            )
        }
    val metabolicChartViewport = cgmChartViewport

    val predictionFutureWindowMs =
        if (
            preferences.showCgmGraph &&
            preferences.anyCgmPredictionEnabled
        ) {
            val enabledPredictions = state?.glucosePredictions
                .orEmpty()
                .filter { series ->
                    when (
                        series.kind
                    ) {
                        app.aapswear.model.PredictionKind.IOB ->
                            preferences.showCgmPredictionIob

                        app.aapswear.model.PredictionKind.COB,
                        app.aapswear.model.PredictionKind.ACOB,
                        ->
                            preferences.showCgmPredictionCob

                        app.aapswear.model.PredictionKind.UAM ->
                            preferences.showCgmPredictionUam

                        app.aapswear.model.PredictionKind.ZERO_TEMP ->
                            preferences.showCgmPredictionZeroTemp
                    }
                }
            maxOf(PredictionDisplayTimeline.futureWindowMs(enabledPredictions, now), 60L * 60_000L)
        } else {
            0L
        }

    val metabolicFutureWindowMs =
        if (
            preferences.showMetabolicGraph &&
            preferences.anyCgmPredictionEnabled
        ) {
            90L * 60_000L
        } else {
            0L
        }

    LaunchedEffect(
        preferences.graphHours,
    ) {
        cgmChartViewport.setHours(
            preferences.graphHours.toFloat(),
            resetPan = true,
        )
        metabolicChartViewport.setHours(
            preferences.graphHours.toFloat(),
            resetPan = true,
        )
    }

    LaunchedEffect(
        predictionFutureWindowMs,
        metabolicFutureWindowMs,
    ) {
        cgmChartViewport.setFutureWindow(
            maxOf(
                predictionFutureWindowMs,
                metabolicFutureWindowMs,
            ),
        )
    }

    val glucoseText = if (displayable && glucose != null) formatGlucose(glucose.valueMgDl, unit) else "—"
    val rangePresentation = widgetRangePresentation(
        state = state,
        samples = canonicalWidgetSamples(state, now, 24L * 60L * 60_000L),
        thresholds = preferences.cgmThresholds,
        now = now,
    )
    val glucoseColor = when (rangePresentation.visibleRole) {
        WidgetColorRole.HIGH -> SugarliciousColors.GlucoseHigh
        WidgetColorRole.LOW -> SugarliciousColors.GlucoseLow
        WidgetColorRole.IN_RANGE -> SugarliciousColors.GlucoseInRange
        else -> SugarliciousColors.TextPrimary
    }
    val delta = if (displayable) formatDelta(glucose?.deltaMgDl, unit) else "—"
    val age = glucose?.measuredAtEpochMs?.let { "${((now - it).coerceAtLeast(0L) / 60_000L)} min" } ?: "—"
    val tirStats = calculateTirStats(state, now)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        OverviewWatchFaceTile(
            state = state,
            diagnostics = diagnostics,
            selectedFaceIndex = preferences.watchFaceIndex,
            onSelectedFace = callbacks.setWatchFaceIndex,
            onEdit = { callbacks.navigate(DashboardScreen.WATCH) },
        )

        OverviewInlineHeader(onSettings = { callbacks.navigate(DashboardScreen.SETTINGS) })

        GlucoseHeroCard(
            glucoseText = glucoseText,
            glucoseColor = glucoseColor,
            trend = if (displayable) glucose?.trend ?: Trend.UNKNOWN else Trend.UNKNOWN,
            delta = delta,
            age = age,
            unitLabel = unitLabel(unit),
            tirStats = tirStats,
            heightDp = maxOf(metrics.summaryTileHeight + 18 - overviewHeightCompensationDp, 100),
        )

        if (preferences.showDetails) {
            QuickStatsRow(
                state = state.takeIf { displayable },
                heightDp = maxOf(metrics.statTileHeight - overviewHeightCompensationDp, 56),
            )
        }

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
            )
        }
    }
}

@Composable
private fun GlucoseHeroCard(
    glucoseText: String,
    glucoseColor: Color,
    trend: Trend,
    delta: String,
    age: String,
    unitLabel: String,
    tirStats: TirStats,
    heightDp: Int,
) {
    val shape = RoundedCornerShape(28.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .background(SugarliciousColors.Surface, shape)
            .border(
                1.dp,
                SugarliciousColors.Border.copy(alpha = 0.85f),
                shape,
            )
            .clip(shape)
            .padding(
                horizontal = 18.dp,
                vertical = 2.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = glucoseText,
                            color = glucoseColor,
                            fontSize = 42.sp,
                            lineHeight = 44.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.8).sp,
                        )

                        Spacer(Modifier.width(6.dp))

                        SugarliciousTrendIndicator(trend, color = glucoseColor)
                    }

                    Spacer(Modifier.height(3.dp))

                    Text(
                        text = "Δ $delta · $age · $unitLabel",
                        color = SugarliciousColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.width(20.dp))

            TirProgressColumn(
                stats = tirStats,
                modifier = Modifier.width(194.dp).fillMaxHeight(),
            )
        }
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
    val samples = buildList {
        addAll(state?.glucoseHistory.orEmpty())
        state?.glucose?.let {
            add(
                app.aapswear.model.GlucoseSample(
                    valueMgDl = it.valueMgDl,
                    measuredAtEpochMs = it.measuredAtEpochMs,
                ),
            )
        }
    }
        .filter {
            it.measuredAtEpochMs in start..(now + 5 * 60_000L) &&
                it.valueMgDl in 20.0..1000.0
        }
        .associateBy { it.measuredAtEpochMs }
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
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        TirProgress(
            modifier = Modifier.fillMaxWidth(),
            percent = stats.above,
            accent = SugarliciousColors.color(app.aapswear.mobile.ui.theme.SugarliciousColorRole.PROGRESS_ABOVE),
        )

        TirProgress(
            modifier = Modifier.fillMaxWidth(),
            percent = stats.inRange,
            accent = SugarliciousColors.color(app.aapswear.mobile.ui.theme.SugarliciousColorRole.PROGRESS_IN_RANGE),
        )

        TirProgress(
            modifier = Modifier.fillMaxWidth(),
            percent = stats.below,
            accent = SugarliciousColors.color(app.aapswear.mobile.ui.theme.SugarliciousColorRole.PROGRESS_BELOW),
        )
    }
}

@Composable
private fun TirProgress(
    modifier: Modifier,
    percent: Int?,
    accent: Color,
) {
    val safePercent = (percent ?: 0).coerceIn(0, 100)
    val progress = safePercent / 100f

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    SugarliciousColors.SurfaceRaised,
                    RoundedCornerShape(999.dp),
                ),
        ) {
            if (progress > 0f) {
                val fillWidth =
                    (maxWidth * progress)
                        .coerceAtLeast(12.dp)
                        .coerceAtMost(maxWidth)

                Box(
                    modifier = Modifier
                        .width(fillWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            accent,
                            RoundedCornerShape(999.dp),
                        ),
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Text(
            text = percent?.let { "$it%" } ?: "—",
            modifier = Modifier.width(42.dp),
            color = SugarliciousColors.TextPrimary,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}
@Composable
private fun QuickStatsRow(
    state: TherapyDisplayState?,
    heightDp: Int,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LoopStateCard(Modifier.weight(1f), overviewLoopTileState(state), heightDp)
        CombinedIobCobCard(Modifier.weight(1f), state, heightDp)
        QuickStatCard(Modifier.weight(1f), R.drawable.ic_basal, "BASAL", formatNumber(state?.basal?.currentUnitsPerHour, 2), "IE/h", SugarliciousColors.Secondary, heightDp)
    }
}

internal data class OverviewLoopTileState(val iconRes: Int, val label: String, val accent: Color)

internal fun overviewLoopTileState(state: TherapyDisplayState?): OverviewLoopTileState {
    val pump = state?.pump?.status.orEmpty().lowercase(Locale.ROOT)
    val loop = state?.loop?.status.orEmpty().lowercase(Locale.ROOT)
    return when {
        listOf("suspend", "paused", "disconnect", "stopped").any(pump::contains) ->
            OverviewLoopTileState(R.drawable.ic_pump_suspended, "Pumpe pausiert", SugarliciousColors.Red)
        listOf("suspend", "paused").any(loop::contains) ->
            OverviewLoopTileState(R.drawable.ic_loop_suspended, "Loop pausiert", SugarliciousColors.Orange)
        loop.isBlank() || listOf("disabled", "off", "open", "deactivated").any(loop::contains) ->
            OverviewLoopTileState(R.drawable.ic_loop_deactivated, "Loop aus", SugarliciousColors.TextSecondary)
        else -> OverviewLoopTileState(R.drawable.ic_loop_closed, "Closed Loop", SugarliciousColors.Green)
    }
}

@Composable
private fun LoopStateCard(modifier: Modifier, state: OverviewLoopTileState, heightDp: Int) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier.height(heightDp.dp).background(SugarliciousColors.Surface, shape)
            .border(1.dp, SugarliciousColors.Border.copy(alpha = 0.72f), shape)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("LOOP", color = SugarliciousColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            SugarliciousIcon(state.iconRes, state.label, Modifier.size(28.dp), state.accent)
            Spacer(Modifier.width(6.dp))
            Text(state.label, color = SugarliciousColors.TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CombinedIobCobCard(modifier: Modifier, state: TherapyDisplayState?, heightDp: Int) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier.height(heightDp.dp).background(SugarliciousColors.Surface, shape)
            .border(1.dp, SugarliciousColors.Border.copy(alpha = 0.72f), shape)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            QuickMetricHeader(R.drawable.ic_iob, "IOB", SugarliciousColors.Blue)
            QuickMetricHeader(R.drawable.ic_carbs, "COB", SugarliciousColors.Orange)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            InlineMetricValue(formatNumber(state?.insulin?.totalIob, 2), "IE")
            InlineMetricValue(formatNumber(state?.carbs?.cobGrams, 0), "g")
        }
    }
}

@Composable
private fun QuickMetricHeader(iconRes: Int, label: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SugarliciousIcon(iconRes, null, Modifier.size(13.dp), accent)
        Spacer(Modifier.width(3.dp))
        Text(label, color = SugarliciousColors.TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun InlineMetricValue(value: String, suffix: String) {
    Row {
        Text(value, modifier = Modifier.alignByBaseline(), color = SugarliciousColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(3.dp))
        Text(suffix, modifier = Modifier.alignByBaseline(), color = SugarliciousColors.TextSecondary, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun QuickStatCard(
    modifier: Modifier,
    iconRes: Int,
    title: String,
    value: String,
    suffix: String,
    accent: Color,
    heightDp: Int,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .height(heightDp.dp)
            .background(SugarliciousColors.Surface, shape)
            .border(1.dp, SugarliciousColors.Border.copy(alpha = 0.72f), shape)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SugarliciousIcon(
                drawableRes = iconRes,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = accent,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                color = SugarliciousColors.TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        InlineMetricValue(value, suffix)
    }
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
    var visibleHours by remember(viewport) { mutableFloatStateOf(viewport.hours) }
    DisposableEffect(viewport) {
        val listener = { visibleHours = viewport.hours }
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
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .clickable {
                    val next = OVERVIEW_GRAPH_HOUR_OPTIONS.firstOrNull { it > visibleHours + 0.05f }
                        ?: OVERVIEW_GRAPH_HOUR_OPTIONS.first()
                    onGraphHours(next)
                }
                .padding(start = 9.dp, top = 7.dp, end = 8.dp, bottom = 7.dp),
        )
    }
}

internal fun formatVisibleGraphHours(hours: Float): String {
    val bounded = hours.coerceIn(1f, 24f)
    val rounded = bounded.roundToInt()
    return if (kotlin.math.abs(bounded - rounded) < 0.05f) "${rounded}h"
    else String.format(Locale.getDefault(), "%.1fh", bounded)
}

internal fun GlucoseDashboardChart.bindOverview(
    state: TherapyDisplayState?,
    preferences: DashboardUiPreferences,
    nowEpochMs: Long,
) {
    bind(
        state = state,
        unit = preferences.unitFor(state),
        showPredictions = preferences.anyCgmPredictionEnabled,
        durationHours = preferences.graphHours,
        showTargetRange = true,
        showTargetValue = preferences.showCgmTargetValue,
        showBasal = preferences.showCgmBasal,
        showActivity = preferences.showCgmActivity,
        showPredictionIob = preferences.showCgmPredictionIob,
        showPredictionCob = preferences.showCgmPredictionCob,
        showPredictionUam = preferences.showCgmPredictionUam,
        showPredictionZeroTemp = preferences.showCgmPredictionZeroTemp,
        cgmDotRadiusDp = preferences.cgmDotRadiusDp,
        cgmDotOutlineEnabled = preferences.cgmDotOutlineEnabled,
        cgmDotOutlineWidthDp = preferences.cgmDotOutlineWidthDp,
        clockEpochMs = nowEpochMs,
    )
}

@Composable
private fun MetabolicGraphSurface(
    state: TherapyDisplayState?,
    preferences: DashboardUiPreferences,
    viewport: ChartViewport,
    chartHeightDp: Int,
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
            )
        },
    )
}

private fun formatGlucose(valueMgDl: Double, unit: GlucoseUnit): String =
    if (unit == GlucoseUnit.MMOL_L) String.format(Locale.getDefault(), "%.1f", valueMgDl / 18.0)
    else valueMgDl.roundToInt().toString()

private fun formatDelta(valueMgDl: Double?, unit: GlucoseUnit): String {
    if (valueMgDl == null) return "—"
    val converted = if (unit == GlucoseUnit.MMOL_L) valueMgDl / 18.0 else valueMgDl
    val prefix = if (converted >= 0.0) "+" else ""
    val body = if (unit == GlucoseUnit.MMOL_L) String.format(Locale.getDefault(), "%.1f", converted)
    else converted.roundToInt().toString()
    return prefix + body
}

private fun formatNumber(value: Double?, digits: Int): String =
    value?.let { String.format(Locale.getDefault(), "%.${digits}f", it) } ?: "—"

private fun unitLabel(unit: GlucoseUnit): String =
    if (unit == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL"
