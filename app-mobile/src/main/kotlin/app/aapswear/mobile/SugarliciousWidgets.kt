package app.aapswear.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.mobile.ui.theme.SugarliciousIconSize
import app.aapswear.mobile.ui.theme.SugarliciousRadius
import app.aapswear.mobile.ui.theme.SugarliciousSpacing
import app.aapswear.model.CanonicalCgmHistory
import app.aapswear.model.Freshness
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.RangeExcursion
import app.aapswear.model.RelativeGraphTimeAxis
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.storage.TherapyStateStore
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

private fun coreWidgetColor(role: SugarliciousColorRole): ColorProvider =
    DayNightColorProvider(day = Color(role.lightArgb), night = Color(role.defaultArgb))

private fun widgetColor(argb: Int): ColorProvider =
    DayNightColorProvider(day = Color(argb), night = Color(argb))

private val WidgetCyan = coreWidgetColor(SugarliciousColorRole.SECONDARY)
private val WidgetIob = coreWidgetColor(SugarliciousColorRole.BLUE)
private val WidgetCob = coreWidgetColor(SugarliciousColorRole.ORANGE)
private val WidgetBasal = coreWidgetColor(SugarliciousColorRole.GREEN)
private val WidgetHeartRate = coreWidgetColor(SugarliciousColorRole.RED)

enum class WidgetKind { GLUCOSE, GRAPH, METABOLIC, ACTIVITY }

private abstract class SugarliciousWidget : GlanceAppWidget() {
    protected abstract val kind: WidgetKind
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = TherapyStateStore(context).state.first()
        val activitySnapshot = if (kind == WidgetKind.ACTIVITY) HealthConnectIntegration.snapshot(context) else null
        val palette = WidgetColorStore.load(context)
        provideContent { WidgetShell(context, kind, state, activitySnapshot, palette) }
    }
}

private class GlucoseWidget : SugarliciousWidget() { override val kind = WidgetKind.GLUCOSE }
private class GraphWidget : SugarliciousWidget() { override val kind = WidgetKind.GRAPH }
private class MetabolicWidget : SugarliciousWidget() { override val kind = WidgetKind.METABOLIC }
private class ActivityWidget : SugarliciousWidget() { override val kind = WidgetKind.ACTIVITY }

class GlucoseWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = GlucoseWidget() }
class GraphWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = GraphWidget() }
class MetabolicWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MetabolicWidget() }
class ActivityWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ActivityWidget() }

internal object SugarliciousWidgets {
    suspend fun update(context: Context) {
        GlucoseWidget().updateAll(context)
        GraphWidget().updateAll(context)
        MetabolicWidget().updateAll(context)
        ActivityWidget().updateAll(context)
    }
}

@Composable
private fun WidgetShell(
    context: Context,
    kind: WidgetKind,
    state: TherapyDisplayState?,
    activitySnapshot: HealthConnectSnapshot?,
    palette: WidgetPalette,
) {
    val size = LocalSize.current
    val compact = size.width < 210.dp || size.height < 130.dp
    val backgroundArgb =
        if (kind == WidgetKind.GRAPH) {
            SugarliciousColors.argb(SugarliciousColorRole.GRAPH_BACKGROUND)
        } else {
            palette.argb(WidgetColorRole.BACKGROUND)
        }
    val baseModifier =
        GlanceModifier
            .fillMaxSize()
            .background(widgetColor(backgroundArgb))
            .cornerRadius(SugarliciousRadius.Navigation)
            .clickable(actionStartActivity<MainActivity>())
    val contentModifier =
        if (kind == WidgetKind.GRAPH) baseModifier
        else baseModifier.padding(if (compact) SugarliciousSpacing.Sm else SugarliciousSpacing.Lg)

    Column(
        modifier = contentModifier,
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        when (kind) {
            WidgetKind.GLUCOSE -> GlucoseWidgetContent(state, palette)
            WidgetKind.GRAPH -> GraphWidgetContent(context, state, palette)
            WidgetKind.METABOLIC,
            WidgetKind.ACTIVITY,
            -> {
                WidgetHeader(kind, state, compact, palette)
                Spacer(GlanceModifier.height(if (compact) SugarliciousSpacing.Xs else SugarliciousSpacing.Md))
                if (kind == WidgetKind.METABOLIC) {
                    MetabolicWidgetContent(state, compact, palette)
                } else {
                    ActivityWidgetContent(activitySnapshot, compact, palette)
                }
            }
        }
    }
}

@Composable
private fun WidgetHeader(kind: WidgetKind, state: TherapyDisplayState?, compact: Boolean, palette: WidgetPalette) {
    val freshness = TherapyDisplayFormatter.freshness(state, System.currentTimeMillis())
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Image(
            ImageProvider(R.mipmap.ic_launcher),
            null,
            GlanceModifier.size(if (compact) SugarliciousIconSize.Small else SugarliciousIconSize.Default),
        )
        Spacer(GlanceModifier.width(SugarliciousSpacing.Sm))
        Column {
            Text(
                "Sugarlicious",
                style = TextStyle(color = widgetColor(palette.argb(WidgetColorRole.TEXT)), fontWeight = FontWeight.Bold, fontSize = if (compact) 13.sp else 15.sp),
            )
            if (!compact && kind != WidgetKind.ACTIVITY) {
                Text(
                    TherapyDisplayFormatter.freshnessLabel(freshness),
                    style = TextStyle(color = statusColor(freshness, palette), fontWeight = FontWeight.Bold, fontSize = 10.sp),
                )
            }
        }
    }
}

@Composable
private fun GlucoseWidgetContent(state: TherapyDisplayState?, palette: WidgetPalette) {
    val size = LocalSize.current
    val now = System.currentTimeMillis()
    val glucose = state?.glucose
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    val value = if (displayable && glucose != null) TherapyDisplayFormatter.glucose(glucose) else "–"
    val arrow = if (displayable && glucose != null) TherapyDisplayFormatter.trendArrow(glucose.trend) else ""
    val low = state?.target?.lowMgDl ?: 80.0
    val high = state?.target?.highMgDl ?: 160.0
    val valueRole = when {
        glucose == null || !displayable -> SugarliciousColorRole.GRAPH_MUTED
        glucose.valueMgDl < low -> SugarliciousColorRole.CGM_DOT_LOW
        glucose.valueMgDl > high -> SugarliciousColorRole.CGM_DOT_HIGH
        else -> SugarliciousColorRole.CGM_DOT_IN_RANGE
    }
    val valueColor = widgetColor(SugarliciousColors.argb(valueRole))
    val trendColor = widgetColor(SugarliciousColors.argb(SugarliciousColorRole.GRAPH_LABEL))
    val verySmall = size.width < 125.dp || size.height < 82.dp
    val large = size.width >= 250.dp && size.height >= 130.dp
    val valueSize = when {
        verySmall -> 34.sp
        large -> 52.sp
        else -> 44.sp
    }
    val arrowSize = when {
        verySmall -> 22.sp
        large -> 31.sp
        else -> 27.sp
    }
    val gap = when {
        verySmall -> 5.dp
        large -> 9.dp
        else -> 7.dp
    }

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(value, style = TextStyle(color = valueColor, fontWeight = FontWeight.Bold, fontSize = valueSize))
        if (arrow.isNotBlank()) {
            Spacer(GlanceModifier.width(gap))
            Text(arrow, style = TextStyle(color = trendColor, fontWeight = FontWeight.Bold, fontSize = arrowSize))
        }
    }
}

@Composable
private fun GraphWidgetContent(
    context: Context,
    state: TherapyDisplayState?,
    palette: WidgetPalette,
) {
    val size = LocalSize.current
    val density = context.resources.displayMetrics.density.coerceAtLeast(0.5f)
    val scaledDensity = context.resources.displayMetrics.scaledDensity.coerceAtLeast(0.5f)
    val widthPx = (size.width.value * density).roundToInt().coerceAtLeast(1)
    val heightPx = (size.height.value * density).roundToInt().coerceAtLeast(1)
    val bitmap =
        renderWidgetGraph(
            state = state,
            palette = palette,
            width = widthPx,
            height = heightPx,
            density = density,
            scaledDensity = scaledDensity,
        )
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = "Sugarlicious CGM-Graph",
        modifier = GlanceModifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
    )
}

@Composable
private fun MetabolicWidgetContent(state: TherapyDisplayState?, compact: Boolean, palette: WidgetPalette) {
    val now = System.currentTimeMillis()
    val freshness = TherapyDisplayFormatter.freshness(state, now)
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    val horizontalInset = if (compact) 16.dp else 32.dp
    val width = ((LocalSize.current.width - horizontalInset) / 3).coerceAtLeast(if (compact) 48.dp else 56.dp)

    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        FlatMetric("IOB", state?.insulin?.totalIob?.takeIf { displayable }?.let { String.format(Locale.US, "%.1f U", it) } ?: "–", WidgetIob, GlanceModifier.width(width), compact, palette)
        FlatMetric("COB", state?.carbs?.cobGrams?.takeIf { displayable }?.let { String.format(Locale.US, "%.0f g", it) } ?: "–", WidgetCob, GlanceModifier.width(width), compact, palette)
        FlatMetric("BASAL", state?.basal?.currentUnitsPerHour?.takeIf { displayable }?.let { String.format(Locale.US, "%.2f", it) } ?: "–", WidgetBasal, GlanceModifier.width(width), compact, palette)
    }
    Spacer(GlanceModifier.height(if (compact) SugarliciousSpacing.Xs else SugarliciousSpacing.Sm))
    Text(
        if (displayable) widgetStatusLine(state, freshness, now, compact) else TherapyDisplayFormatter.freshnessLabel(freshness),
        style = TextStyle(color = statusColor(freshness, palette), fontWeight = FontWeight.Medium, fontSize = if (compact) 9.sp else 11.sp),
    )
}

@Composable
private fun FlatMetric(
    label: String,
    value: String,
    accent: ColorProvider,
    modifier: GlanceModifier,
    compact: Boolean,
    palette: WidgetPalette,
) {
    Column(modifier = modifier.padding(horizontal = SugarliciousSpacing.Xs)) {
        Text(label, style = TextStyle(color = accent, fontWeight = FontWeight.Bold, fontSize = if (compact) 9.sp else 11.sp))
        Text(value, style = TextStyle(color = widgetColor(palette.argb(WidgetColorRole.TEXT)), fontWeight = FontWeight.Bold, fontSize = if (compact) 15.sp else 19.sp))
    }
}

@Composable
private fun ActivityWidgetContent(snapshot: HealthConnectSnapshot?, compact: Boolean, palette: WidgetPalette) {
    val horizontalInset = if (compact) 16.dp else 32.dp
    val width = ((LocalSize.current.width - horizontalInset) / 2).coerceAtLeast(if (compact) 72.dp else 80.dp)
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        FlatMetric("SCHRITTE", snapshot?.steps?.toString() ?: "–", WidgetCyan, GlanceModifier.width(width), compact, palette)
        FlatMetric("PULS", snapshot?.latestHeartRate?.let { "$it bpm" } ?: "–", WidgetHeartRate, GlanceModifier.width(width), compact, palette)
    }
    Spacer(GlanceModifier.height(if (compact) SugarliciousSpacing.Sm else SugarliciousSpacing.Md))
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        FlatMetric("AKTIV", snapshot?.activeMinutes?.let { "$it min" } ?: "–", WidgetBasal, GlanceModifier.width(width), compact, palette)
        FlatMetric("KCAL", snapshot?.activeCaloriesKcal?.let { String.format(Locale.US, "%.0f", it) } ?: "–", WidgetCob, GlanceModifier.width(width), compact, palette)
    }
}

private fun widgetStatusLine(state: TherapyDisplayState?, freshness: Freshness, now: Long, compact: Boolean): String {
    val source = TherapyDisplayFormatter.sourceName(state?.source)
    val age = TherapyDisplayFormatter.ageMinutesValue(state?.glucose?.measuredAtEpochMs, now)?.let { "$it min" }
    val parts = if (compact) listOf(source, age.orEmpty()) else listOf(source, age.orEmpty(), TherapyDisplayFormatter.freshnessLabel(freshness))
    return parts.filter(String::isNotBlank).joinToString(" · ")
}

internal fun widgetAge(state: TherapyDisplayState?, now: Long): String =
    TherapyDisplayFormatter.ageMinutesValue(state?.glucose?.measuredAtEpochMs, now)?.let { "$it min" }.orEmpty()

internal fun widgetFreshnessStatus(freshness: Freshness): String = TherapyDisplayFormatter.freshnessLabel(freshness)

private fun statusColor(freshness: Freshness, palette: WidgetPalette): ColorProvider =
    widgetColor(
        palette.argb(
            when (freshness) {
                Freshness.CURRENT -> WidgetColorRole.IN_RANGE
                Freshness.DELAYED -> WidgetColorRole.HIGH
                Freshness.STALE, Freshness.ERROR, Freshness.NO_DATA -> WidgetColorRole.URGENT_LOW
            },
        ),
    )

/**
 * Renders a new bitmap for the exact launcher-provided widget size. The graph deliberately shares
 * the Mobile graph's canonical history, logarithmic glucose mapping, range-excursion policy,
 * relative time-axis policy and semantic Sugarlicious color roles. No completed bitmap is reused
 * across widget sizes.
 */
internal fun renderWidgetGraph(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    width: Int,
    height: Int,
    now: Long = System.currentTimeMillis(),
    density: Float = 1f,
    scaledDensity: Float = density,
): Bitmap {
    @Suppress("UNUSED_VARIABLE")
    val retainedPaletteForNonGraphWidgets = palette
    val metrics = WidgetGraphLayoutMetrics.resolve(width, height, density, scaledDensity)
    val bitmap = Bitmap.createBitmap(metrics.widthPx, metrics.heightPx, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val background = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_BACKGROUND)
    canvas.drawColor(background)

    val plot = metrics.plotRect
    if (plot.width() <= 8f || plot.height() <= 8f) return bitmap

    val targetLow = state?.target?.lowMgDl?.takeIf(Double::isFinite) ?: 80.0
    val targetHigh = state?.target?.highMgDl?.takeIf(Double::isFinite)?.takeIf { it >= targetLow } ?: 160.0
    val windowMs = 3L * 60L * 60_000L
    val start = now - windowMs
    val samples = canonicalWidgetSamples(state, now, windowMs)
    val excursion =
        if (TherapyDisplayFormatter.isGlucoseDisplayable(state, now)) {
            sustainedRangeExcursion(samples, targetLow, targetHigh)
        } else {
            null
        }

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    val dotOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    val highY = widgetGlucoseY(targetHigh, plot)
    val lowY = widgetGlucoseY(targetLow, plot)
    if (excursion == RangeExcursion.HIGH) {
        fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.RANGE_HIGH)
        canvas.drawRect(plot.left, plot.top, plot.right, highY, fillPaint)
    }
    fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.RANGE_IN_RANGE)
    canvas.drawRect(plot.left, highY, plot.right, lowY, fillPaint)
    if (excursion == RangeExcursion.LOW) {
        fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.RANGE_LOW)
        canvas.drawRect(plot.left, lowY, plot.right, plot.bottom, fillPaint)
    }

    linePaint.strokeWidth = metrics.boundaryStrokePx
    linePaint.color = opaqueColor(SugarliciousColors.argb(SugarliciousColorRole.RANGE_HIGH))
    canvas.drawLine(plot.left, highY, plot.right, highY, linePaint)
    linePaint.color = opaqueColor(SugarliciousColors.argb(SugarliciousColorRole.RANGE_LOW))
    canvas.drawLine(plot.left, lowY, plot.right, lowY, linePaint)

    drawWidgetTimeAxis(canvas, metrics, start, now)
    drawWidgetYAxis(canvas, metrics, state, targetHigh, targetLow, highY, lowY)

    linePaint.strokeWidth = metrics.currentTimeStrokePx
    linePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_DIVIDER)
    canvas.drawLine(plot.right, plot.top, plot.right, plot.bottom, linePaint)

    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    if (!displayable) {
        val signalStart =
            samples.lastOrNull()?.measuredAtEpochMs?.let { timestamp ->
                mapWidgetX(timestamp, start, now, plot)
            }?.coerceIn(plot.left, plot.right) ?: plot.left
        if (signalStart < plot.right) {
            fillPaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_SIGNAL_LOSS)
            canvas.drawRect(signalStart, plot.top, plot.right, plot.bottom, fillPaint)
        }
    }

    val maxCenterX = plot.right - metrics.dotRadiusPx - metrics.dotOutlineWidthPx / 2f - metrics.gridStrokePx
    val minCenterX = plot.left + metrics.dotRadiusPx + metrics.dotOutlineWidthPx / 2f + metrics.gridStrokePx
    samples.forEachIndexed { index, sample ->
        val mappedX = mapWidgetX(sample.measuredAtEpochMs, start, now, plot)
        val x = mappedX.coerceIn(minCenterX, maxCenterX)
        val y = widgetGlucoseY(sample.valueMgDl, plot)
        val currentExtra = if (index == samples.lastIndex) CgmGraphVisualPolicy.CURRENT_DOT_EXTRA_DP * density else 0f
        val radius = (metrics.dotRadiusPx + currentExtra).coerceAtMost(2.7f * density)
        fillPaint.color = widgetMobileDotColor(sample.valueMgDl, targetLow, targetHigh)
        canvas.drawCircle(x, y, radius, fillPaint)
        dotOutlinePaint.color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_CURRENT_OUTLINE)
        dotOutlinePaint.strokeWidth = metrics.dotOutlineWidthPx
        canvas.drawCircle(x, y, radius + metrics.dotOutlineWidthPx / 2f, dotOutlinePaint)
    }

    if (samples.size < 2) {
        val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_MUTED)
            textSize = metrics.axisTextPx
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val label = if (displayable) "Noch kein Verlauf" else widgetFreshnessStatus(TherapyDisplayFormatter.freshness(state, now))
        canvas.drawText(label, plot.centerX(), plot.centerY(), messagePaint)
    }
    return bitmap
}

private fun drawWidgetTimeAxis(
    canvas: AndroidCanvas,
    metrics: WidgetGraphLayoutMetrics,
    start: Long,
    now: Long,
) {
    val plot = metrics.plotRect
    val ticks = RelativeGraphTimeAxis.ticks(start, now, now)
    val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_GRID)
        strokeWidth = metrics.gridStrokePx
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_LABEL)
        textSize = metrics.axisTextPx
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val baseline =
        (metrics.heightPx - metrics.outerInsetPx - textPaint.fontMetrics.descent)
            .coerceAtLeast(plot.bottom + metrics.tickHeightPx + metrics.bottomAxisGapPx - textPaint.fontMetrics.ascent)
            .coerceAtMost(metrics.heightPx - metrics.outerInsetPx - textPaint.fontMetrics.descent)
    ticks.forEach { tick ->
        val x = mapWidgetX(tick.timestampEpochMs, start, now, plot).coerceIn(plot.left, plot.right)
        canvas.drawLine(
            x,
            plot.bottom + metrics.bottomAxisGapPx,
            x,
            plot.bottom + metrics.bottomAxisGapPx + metrics.tickHeightPx,
            tickPaint,
        )
        val align = when {
            tick.timestampEpochMs <= start + 30_000L -> Paint.Align.LEFT
            tick.hoursBack == 0 -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        textPaint.textAlign = align
        val labelX = when (align) {
            Paint.Align.LEFT -> plot.left
            Paint.Align.RIGHT -> plot.right
            else -> x
        }
        canvas.drawText(tick.label, labelX, baseline, textPaint)
    }
}

private fun drawWidgetYAxis(
    canvas: AndroidCanvas,
    metrics: WidgetGraphLayoutMetrics,
    state: TherapyDisplayState?,
    targetHigh: Double,
    targetLow: Double,
    highY: Float,
    lowY: Float,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SugarliciousColors.argb(SugarliciousColorRole.GRAPH_LABEL)
        textSize = metrics.yAxisTextPx
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val unit = state?.glucose?.displayUnit ?: GlucoseUnit.MG_DL
    val highLabel = widgetGlucoseLabel(targetHigh, unit)
    val lowLabel = widgetGlucoseLabel(targetLow, unit)
    val fm = paint.fontMetrics
    val minBaseline = metrics.plotTopPx - fm.ascent
    val maxBaseline = metrics.plotBottomPx - fm.descent
    val highBaseline = (highY - 2f * metrics.gridStrokePx - fm.descent).coerceIn(minBaseline, maxBaseline)
    val lowBaseline = (lowY + 2f * metrics.gridStrokePx - fm.ascent).coerceIn(minBaseline, maxBaseline)
    canvas.drawText(highLabel, metrics.yAxisLeftPx, highBaseline, paint)
    canvas.drawText(lowLabel, metrics.yAxisLeftPx, lowBaseline, paint)
}

private fun mapWidgetX(timestamp: Long, start: Long, end: Long, plot: RectF): Float {
    if (end <= start) return plot.right
    val ratio = ((timestamp - start).toDouble() / (end - start).toDouble()).coerceIn(0.0, 1.0)
    return plot.left + ratio.toFloat() * plot.width()
}

private fun widgetGlucoseY(valueMgDl: Double, plot: RectF): Float =
    plot.bottom - glucoseLogRatio(valueMgDl).toFloat() * plot.height()

private fun widgetGlucoseLabel(valueMgDl: Double, unit: GlucoseUnit): String =
    if (unit == GlucoseUnit.MMOL_L) String.format(Locale.getDefault(), "%.1f", valueMgDl / 18.0)
    else valueMgDl.roundToInt().toString()

private fun widgetMobileDotColor(value: Double, low: Double, high: Double): Int = when {
    value < low -> SugarliciousColors.argb(SugarliciousColorRole.CGM_DOT_LOW)
    value > high -> SugarliciousColors.argb(SugarliciousColorRole.CGM_DOT_HIGH)
    else -> SugarliciousColors.argb(SugarliciousColorRole.CGM_DOT_IN_RANGE)
}

private fun opaqueColor(color: Int): Int = AndroidColor.argb(255, AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color))

internal fun canonicalWidgetSamples(
    state: TherapyDisplayState?,
    now: Long,
    windowMs: Long = 3L * 60L * 60_000L,
): List<GlucoseSample> {
    val currentSample = state?.glucose?.let { glucose ->
        GlucoseSample(
            valueMgDl = glucose.valueMgDl,
            measuredAtEpochMs = glucose.measuredAtEpochMs,
            source = glucose.source,
            sensorId = glucose.sensorId,
            sessionId = glucose.sessionId,
            sequenceNumber = glucose.sequenceNumber,
            receivedAtEpochMs = glucose.receivedAtEpochMs,
            quality = glucose.quality,
        )
    }
    return CanonicalCgmHistory.merge(
        samples = state?.glucoseHistory.orEmpty() + listOfNotNull(currentSample),
        nowEpochMs = now,
        preferredSource = state?.source,
        windowMs = windowMs,
    ).filter { it.measuredAtEpochMs in (now - windowMs)..now }
}
