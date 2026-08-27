package app.aapswear.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.PathParser
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.AppWidgetId
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
import app.aapswear.mobile.ui.theme.SugarliciousIconSize
import app.aapswear.mobile.ui.theme.SugarliciousRadius
import app.aapswear.mobile.ui.theme.SugarliciousSpacing
import app.aapswear.model.Freshness
import app.aapswear.model.CanonicalCgmHistory
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.CgmGraphPolicy
import app.aapswear.model.CgmRangeClass
import app.aapswear.model.RangeExcursion
import app.aapswear.model.RelativeGraphTimeAxis
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TrendVisuals
import app.aapswear.storage.TherapyStateStore
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

private fun coreWidgetColor(role: SugarliciousColorRole): ColorProvider =
    DayNightColorProvider(day = Color(role.lightArgb), night = Color(role.defaultArgb))

private fun widgetColor(argb: Int): ColorProvider =
    DayNightColorProvider(day = Color(argb), night = Color(argb))

private fun blendArgb(base: Int, overlay: Int, fraction: Float): Int {
    val amount = fraction.coerceIn(0f, 1f)
    fun channel(baseChannel: Int, overlayChannel: Int): Int =
        (baseChannel + (overlayChannel - baseChannel) * amount).toInt().coerceIn(0, 255)
    return AndroidColor.argb(
        channel(AndroidColor.alpha(base), AndroidColor.alpha(overlay)),
        channel(AndroidColor.red(base), AndroidColor.red(overlay)),
        channel(AndroidColor.green(base), AndroidColor.green(overlay)),
        channel(AndroidColor.blue(base), AndroidColor.blue(overlay)),
    )
}

private val WidgetCyan = coreWidgetColor(SugarliciousColorRole.SECONDARY)
private val WidgetIob = coreWidgetColor(SugarliciousColorRole.BLUE)
private val WidgetCob = coreWidgetColor(SugarliciousColorRole.ORANGE)
private val WidgetBasal = coreWidgetColor(SugarliciousColorRole.GREEN)
private val WidgetHeartRate = coreWidgetColor(SugarliciousColorRole.RED)

enum class WidgetKind { GLUCOSE, GRAPH, GLUCOSE_GRAPH, METABOLIC, ACTIVITY }

private abstract class SugarliciousWidget : GlanceAppWidget() {
    protected abstract val kind: WidgetKind
    final override val sizeMode: SizeMode = SizeMode.Exact

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = (id as? AppWidgetId)?.appWidgetId ?: android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
        val instance = WidgetInstanceConfigurationStore.read(context, appWidgetId)
        val state = TherapyStateStore(context).state.first()
        val activitySnapshot = if (kind == WidgetKind.ACTIVITY) HealthConnectIntegration.snapshot(context) else null
        val palette = WidgetColorStore.load(context)
        val thresholds = CgmThresholdPreferences.read(context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE))
        provideContent { WidgetShell(kind, state, activitySnapshot, palette.with(WidgetColorRole.BACKGROUND, instance.backgroundArgb), thresholds, instance) }
    }
}

private class GlucoseWidget : SugarliciousWidget() { override val kind = WidgetKind.GLUCOSE }
private class GraphWidget : SugarliciousWidget() { override val kind = WidgetKind.GRAPH }
private class GlucoseGraphWidget : SugarliciousWidget() { override val kind = WidgetKind.GLUCOSE_GRAPH }
private class MetabolicWidget : SugarliciousWidget() { override val kind = WidgetKind.METABOLIC }
private class ActivityWidget : SugarliciousWidget() { override val kind = WidgetKind.ACTIVITY }

private fun deleteWidgetConfigurations(context: Context, ids: IntArray) = ids.forEach { WidgetInstanceConfigurationStore.delete(context, it) }
class GlucoseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlucoseWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) { deleteWidgetConfigurations(context, appWidgetIds); super.onDeleted(context, appWidgetIds) }
}
class GraphWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GraphWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) { deleteWidgetConfigurations(context, appWidgetIds); super.onDeleted(context, appWidgetIds) }
}
class GlucoseGraphWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlucoseGraphWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetInstanceConfigurationStore.delete(context, it) }
        super.onDeleted(context, appWidgetIds)
    }
}
class MetabolicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MetabolicWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) { deleteWidgetConfigurations(context, appWidgetIds); super.onDeleted(context, appWidgetIds) }
}
class ActivityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ActivityWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) { deleteWidgetConfigurations(context, appWidgetIds); super.onDeleted(context, appWidgetIds) }
}

internal object SugarliciousWidgets {
    suspend fun update(context: Context) {
        GlucoseWidget().updateAll(context)
        GraphWidget().updateAll(context)
        GlucoseGraphWidget().updateAll(context)
        MetabolicWidget().updateAll(context)
        ActivityWidget().updateAll(context)
    }
}

@Composable
private fun WidgetShell(
    kind: WidgetKind,
    state: TherapyDisplayState?,
    activitySnapshot: HealthConnectSnapshot?,
    palette: WidgetPalette,
    thresholds: app.aapswear.model.CgmThresholds,
    instance: WidgetInstanceConfiguration,
) {
    val size = LocalSize.current
    val layout = responsiveWidgetLayout(size.width.value, size.height.value)
    val launchComponent = WidgetLaunchTargetStore.launchComponent(LocalContext.current, instance.launchPackage)
    val compact = layout.widthClass <= WidgetWidthClass.NARROW || layout.heightClass == WidgetHeightClass.LOW
    val background = widgetColor(palette.argb(WidgetColorRole.BACKGROUND))
    val outerBackground =
        if (kind == WidgetKind.GRAPH || kind == WidgetKind.GLUCOSE || kind == WidgetKind.GLUCOSE_GRAPH) {
            widgetColor(instance.backgroundArgb)
        } else {
            background
        }
    val contentPadding = if (kind == WidgetKind.GRAPH || kind == WidgetKind.GLUCOSE_GRAPH) 0.dp else layout.paddingDp.dp

    Column(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(outerBackground)
                .cornerRadius(layout.cornerRadiusDp.dp)
                .padding(contentPadding)
                .clickable(actionStartActivity(launchComponent)),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        when (kind) {
            WidgetKind.GLUCOSE -> GlucoseWidgetContent(state, palette, thresholds, layout)
            WidgetKind.GRAPH -> GraphWidgetContent(state, palette, thresholds, layout, instance)
            WidgetKind.GLUCOSE_GRAPH -> GlucoseGraphWidgetContent(state, palette, thresholds, layout, instance)
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
private fun GlucoseWidgetContent(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    thresholds: app.aapswear.model.CgmThresholds,
    layout: ResponsiveWidgetLayout,
) {
    val size = LocalSize.current
    val pixelDensity = LocalContext.current.resources.displayMetrics.density.coerceAtLeast(1f)
    val bitmap = renderMinimalGlucoseWidget(
        state = state,
        palette = palette,
        width = (size.width.value * pixelDensity).roundToInt().coerceAtLeast(48),
        height = (size.height.value * pixelDensity).roundToInt().coerceAtLeast(48),
        thresholds = thresholds,
        layout = layout,
        pixelDensity = pixelDensity,
    )
    Image(ImageProvider(bitmap), "Glukose und Trend", GlanceModifier.fillMaxSize(), contentScale = ContentScale.Fit)
}

@Composable
private fun GraphWidgetContent(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    thresholds: app.aapswear.model.CgmThresholds,
    layout: ResponsiveWidgetLayout,
    instance: WidgetInstanceConfiguration,
) {
    val size = LocalSize.current
    val pixelDensity = LocalContext.current.resources.displayMetrics.density.coerceAtLeast(1f)
    val bitmap = renderWidgetGraph(
        state = state,
        palette = palette,
        width = (size.width.value * pixelDensity).roundToInt().coerceAtLeast(96),
        height = (size.height.value * pixelDensity).roundToInt().coerceAtLeast(72),
        thresholds = thresholds,
        layout = layout,
        pixelDensity = pixelDensity,
        configuration = instance,
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
 * Produces the real Glance graph surface. It deliberately uses the same canonical history and
 * fixed logarithmic glucose axis as the in-app graph, while retaining an independent palette.
 */
internal fun renderWidgetGraph(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    width: Int = 800,
    height: Int = 360,
    now: Long = System.currentTimeMillis(),
    thresholds: app.aapswear.model.CgmThresholds = app.aapswear.model.CgmThresholds.DEFAULT,
    layout: ResponsiveWidgetLayout = responsiveWidgetLayout(width.toFloat(), height.toFloat()),
    pixelDensity: Float = 1f,
    configuration: WidgetInstanceConfiguration = WidgetInstanceConfiguration(showTimeAxis = true),
): Bitmap {
    val safeWidth = width.coerceAtLeast(96)
    val safeHeight = height.coerceAtLeast(72)
    val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val background = configuration.backgroundArgb
    val text = palette.argb(WidgetColorRole.AXIS)
    canvas.drawColor(background)

    // LocalSize is expressed in dp while an ImageProvider bitmap is pixel based. Rendering at
    // dp resolution makes launchers upscale the bitmap, producing the blurred/stretched result.
    val density = pixelDensity.coerceIn(1f, 4f)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = text
        textSize = layout.graphAxisTextSp * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val metrics = widgetGraphMetrics(safeWidth, safeHeight, density, layout, textPaint, configuration.showTimeAxis)
    val plot = metrics.plot
    val targetLow = thresholds.lowMgDl
    val targetHigh = thresholds.highMgDl
    val windowMs = configuration.graphHours.toLong() * 60L * 60_000L
    val allSamples = canonicalWidgetSamples(state, now, windowMs)
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    val samples = if (displayable) allSamples else emptyList()
    val excursion = if (displayable) CgmGraphPolicy.rangeExcursion(allSamples, thresholds) else null
    val yScale = widgetYScale(configuration.scaleMode, allSamples.map(GlucoseSample::valueMgDl), targetLow, targetHigh)
    val targetTop = yScale.map(targetHigh, plot)
    val targetBottom = yScale.map(targetLow, plot)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    if (excursion == RangeExcursion.HIGH) {
        fill.color = palette.argb(WidgetColorRole.RANGE_HIGH)
        canvas.drawRect(plot.left, plot.top, plot.right, targetTop, fill)
    }
    fill.color = palette.argb(WidgetColorRole.RANGE_IN_RANGE)
    canvas.drawRect(plot.left, targetTop, plot.right, targetBottom, fill)
    if (excursion == RangeExcursion.LOW) {
        fill.color = palette.argb(WidgetColorRole.RANGE_LOW)
        canvas.drawRect(plot.left, targetBottom, plot.right, plot.bottom, fill)
    }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = metrics.lineWidthPx
    }
    line.color = palette.argb(WidgetColorRole.HIGH_LINE)
    canvas.drawLine(plot.left, targetTop, plot.right, targetTop, line)
    line.color = palette.argb(WidgetColorRole.LOW_LINE)
    canvas.drawLine(plot.left, targetBottom, plot.right, targetBottom, line)

    val start = now - windowMs
    val latestTimestamp = samples.maxOfOrNull(GlucoseSample::measuredAtEpochMs) ?: now
    fun x(timestamp: Long): Float = plot.left + ((timestamp - start).toFloat() / windowMs) * plot.width()
    val latestX = x(latestTimestamp).coerceIn(
        plot.left + metrics.dotRadiusPx + metrics.outlineWidthPx,
        plot.right - metrics.dotRadiusPx - metrics.outlineWidthPx,
    )
    val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = metrics.outlineWidthPx
        color = palette.argb(WidgetColorRole.DOT_OUTLINE)
    }
    samples.forEach { sample ->
        val pointX = x(sample.measuredAtEpochMs).coerceIn(
            plot.left + metrics.dotRadiusPx + metrics.outlineWidthPx,
            plot.right - metrics.dotRadiusPx - metrics.outlineWidthPx,
        )
        val y = yScale.map(sample.valueMgDl, plot)
        pointPaint.color = palette.argb(widgetGlucoseColorRole(sample.valueMgDl, thresholds))
        val radius = metrics.dotRadiusPx
        canvas.drawCircle(pointX, y, radius, pointPaint)
        canvas.drawCircle(pointX, y, radius + outlinePaint.strokeWidth / 2f, outlinePaint)
    }

    textPaint.textAlign = Paint.Align.CENTER
    line.color = palette.argb(WidgetColorRole.DIVIDER)
    line.strokeWidth = metrics.lineWidthPx
    if (configuration.showTimeAxis) RelativeGraphTimeAxis.ticks(start, now, now, RelativeGraphTimeAxis.intervalHours(configuration.graphHours.toDouble())).forEach { tick ->
        val tickX = if (tick.hoursBack == 0) latestX else x(tick.timestampEpochMs)
        if (tickX in plot.left..plot.right) {
            val labelSize = if (tick.hoursBack == 0) metrics.nowTextSizePx else metrics.axisTextSizePx
            textPaint.textSize = labelSize
            val labelHalfWidth = textPaint.measureText(tick.label) / 2f
            val labelX = tickX.coerceIn(labelHalfWidth + metrics.edgeGapPx, safeWidth - labelHalfWidth - metrics.edgeGapPx)
            canvas.drawLine(labelX, plot.bottom + 2f * density, labelX, plot.bottom + 7f * density, line)
            canvas.drawText(tick.label, labelX, metrics.axisBaselinePx, textPaint)
            textPaint.textSize = metrics.axisTextSizePx
        }
    }
    textPaint.textAlign = Paint.Align.LEFT
    canvas.drawText(targetHigh.roundToInt().toString(), plot.right + 4f * density, targetTop - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f, textPaint)
    canvas.drawText(targetLow.roundToInt().toString(), plot.right + 4f * density, targetBottom - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f, textPaint)
    return bitmap
}

internal data class WidgetGraphMetrics(
    val plot: RectF,
    val axisTextSizePx: Float,
    val nowTextSizePx: Float,
    val axisBaselinePx: Float,
    val dotRadiusPx: Float,
    val outlineWidthPx: Float,
    val lineWidthPx: Float,
    val edgeGapPx: Float,
)

internal fun widgetGraphMetrics(
    widthPx: Int,
    heightPx: Int,
    density: Float,
    layout: ResponsiveWidgetLayout,
    textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = layout.graphAxisTextSp * density.coerceIn(1f, 4f)
    },
    showTimeAxis: Boolean = true,
): WidgetGraphMetrics {
    val safeDensity = density.coerceIn(1f, 4f)
    val axisText = layout.graphAxisTextSp * safeDensity
    val nowText = (layout.graphAxisTextSp + 2f).coerceAtMost(13f) * safeDensity
    textPaint.textSize = axisText
    // Axis text sits inside the full-bleed surface, but must also clear the launcher's rounded
    // clipping mask. A tiny edge clamp is insufficient near the lower corners (notably for 3h).
    val edgeGap = 8f * safeDensity
    val dotRadius = layout.graphDotRadiusDp.coerceIn(2.4f, 2.5f) * safeDensity
    val outline = layout.graphOutlineDp.coerceIn(0.8f, 1f) * safeDensity
    val lineWidth = layout.graphLineDp.coerceIn(1f, 1.2f) * safeDensity
    val yLabelWidth = maxOf(textPaint.measureText("160"), textPaint.measureText("80"))
    textPaint.textSize = nowText
    val nowLabelHalf = textPaint.measureText("jetzt") / 2f
    textPaint.textSize = axisText
    val fontHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
    val bottomBand = if (showTimeAxis) {
        maxOf(fontHeight + 12f * safeDensity, 22f * safeDensity).coerceAtMost(heightPx * 0.34f)
    } else {
        2f * safeDensity
    }
    // Full bleed also applies to the left side of the plotted range. X-axis labels are clamped
    // independently below, and samples have their own radius clamp, so reserving a blank strip
    // here only looked like a translucent overlay on top of the graph.
    val leftInset = 0f
    val rightInset = maxOf(yLabelWidth + 9f * safeDensity, nowLabelHalf + edgeGap)
    val topInset = maxOf(2f * safeDensity, dotRadius + outline)
    val plotRight = (widthPx - rightInset).coerceAtLeast(leftInset + 24f * safeDensity)
    val plotBottom = (heightPx - bottomBand).coerceAtLeast(topInset + 24f * safeDensity)
    val baseline = if (showTimeAxis) heightPx - 8f * safeDensity else heightPx.toFloat()
    return WidgetGraphMetrics(
        plot = RectF(leftInset, topInset, plotRight, plotBottom),
        axisTextSizePx = axisText,
        nowTextSizePx = nowText,
        axisBaselinePx = baseline,
        dotRadiusPx = dotRadius,
        outlineWidthPx = outline,
        lineWidthPx = lineWidth,
        edgeGapPx = edgeGap,
    )
}

@Composable
private fun GlucoseGraphWidgetContent(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    thresholds: app.aapswear.model.CgmThresholds,
    layout: ResponsiveWidgetLayout,
    instance: WidgetInstanceConfiguration,
) {
    val size = LocalSize.current
    val density = LocalContext.current.resources.displayMetrics.density.coerceAtLeast(1f)
    val bitmap = renderGlucoseGraphWidget(
        state, palette,
        (size.width.value * density).roundToInt().coerceAtLeast(96),
        (size.height.value * density).roundToInt().coerceAtLeast(96),
        System.currentTimeMillis(), thresholds, layout, density, instance,
    )
    Image(ImageProvider(bitmap), "Glukose, Trend und CGM-Graph", GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
}

internal fun renderMinimalGlucoseWidget(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    width: Int,
    height: Int,
    now: Long = System.currentTimeMillis(),
    thresholds: app.aapswear.model.CgmThresholds = app.aapswear.model.CgmThresholds.DEFAULT,
    layout: ResponsiveWidgetLayout = responsiveWidgetLayout(width.toFloat(), height.toFloat()),
    pixelDensity: Float = 1f,
): Bitmap {
    val bitmap = Bitmap.createBitmap(width.coerceAtLeast(48), height.coerceAtLeast(48), Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(palette.argb(WidgetColorRole.BACKGROUND))
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    val samples = canonicalWidgetSamples(state, now)
    val presentation = widgetRangePresentation(state, samples, thresholds, now)
    val glucose = state?.glucose
    val value = if (displayable && glucose != null) TherapyDisplayFormatter.glucose(glucose) else "–"
    val color = palette.argb(if (displayable) presentation.visibleRole else WidgetColorRole.TEXT)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = layout.glucoseTextSp * pixelDensity.coerceIn(1f, 4f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textWidth = paint.measureText(value)
    val textBounds = Rect().also { paint.getTextBounds(value, 0, value.length, it) }
    val textHeight = textBounds.height().toFloat().coerceAtLeast(paint.textSize * 0.65f)
    val spec = if (displayable) glucose?.trend?.let(TrendVisuals::spec) else null
    val arrowGeometry = spec?.let { normalizedTrendArrowGeometry(textHeight, it.rotationDegrees, it.arrowCount) }
    val arrowSize = arrowGeometry?.scalePx ?: 0f
    val arrowWidth = arrowGeometry?.groupWidthPx ?: 0f
    val gap = if (spec != null) (paint.textSize * (6f / 42f)).coerceAtLeast(3f) else 0f
    val groupWidth = textWidth + gap + arrowWidth
    val startX = (bitmap.width - groupWidth) / 2f
    val baseline = bitmap.height / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
    canvas.drawText(value, startX, baseline, paint)
    if (spec != null) {
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val count = spec.arrowCount
        val spacing = arrowGeometry?.centerSpacingPx ?: 0f
        val arrowPathData = "M88.61,35.97L52.23,2.14c-3.23,-3 -8.3,-2.82 -11.31,0.41 -1.46,1.57 -2.22,3.6 -2.14,5.74 0.08,2.14 0.98,4.12 2.55,5.57l21.47,19.96H8c-4.41,0 -8,3.59 -8,8s3.59,8 8,8h54.82l-21.49,19.97c-1.57,1.46 -2.47,3.44 -2.55,5.57 -0.08,2.14 0.68,4.18 2.14,5.74 1.57,1.69 3.71,2.54 5.86,2.54 1.95,0 3.91,-0.71 5.45,-2.14l36.38,-33.82c1.62,-1.51 2.55,-3.65 2.55,-5.86s-0.93,-4.35 -2.55,-5.86z"
        repeat(count) { index ->
            val centerX = startX + textWidth + gap + (arrowGeometry?.singleVisibleWidthPx ?: 0f) / 2f + index * spacing
            val centerY = bitmap.height / 2f
            val path = PathParser.createPathFromPathData(arrowPathData)
            // Keep the canonical Mobile arrow untouched. Canvas transforms make the operation
            // explicit: center the source vector, scale uniformly, rotate around its own visual
            // center, then place it in the reserved arrow cell. Matrix post-order previously
            // caused direction-dependent offsets and apparent distortion.
            canvas.save()
            canvas.translate(centerX, centerY)
            canvas.rotate(spec.rotationDegrees)
            canvas.scale(arrowSize, arrowSize)
            canvas.translate(-91.16f / 2f, -83.65f / 2f)
            canvas.drawPath(path, arrowPaint)
            canvas.restore()
        }
    }
    return bitmap
}

internal data class TrendArrowGeometry(
    val scalePx: Float,
    val singleVisibleWidthPx: Float,
    val centerSpacingPx: Float,
    val groupWidthPx: Float,
)

internal fun normalizedTrendArrowGeometry(targetVisibleHeightPx: Float, rotationDegrees: Float, arrowCount: Int): TrendArrowGeometry {
    val radians = Math.toRadians(rotationDegrees.toDouble())
    val sine = kotlin.math.abs(kotlin.math.sin(radians)).toFloat()
    val cosine = kotlin.math.abs(kotlin.math.cos(radians)).toFloat()
    val rawWidth = 91.16f
    val rawHeight = 83.65f
    val rotatedWidth = rawWidth * cosine + rawHeight * sine
    // Every trend direction is the exact same Mobile vector at the exact same uniform scale.
    // Scaling against the rotated bounding-box height made the 45-degree variants visibly
    // smaller even though their source artwork was identical.
    val scale = targetVisibleHeightPx / rawHeight
    val visibleWidth = rotatedWidth * scale
    val spacing = visibleWidth + targetVisibleHeightPx * 0.04f
    val count = arrowCount.coerceIn(1, 2)
    return TrendArrowGeometry(
        scalePx = scale,
        singleVisibleWidthPx = visibleWidth,
        centerSpacingPx = spacing,
        groupWidthPx = visibleWidth + (count - 1) * spacing,
    )
}

internal data class WidgetYScale(val mode: WidgetScaleMode, val minimum: Double, val maximum: Double) {
    fun map(value: Double, plot: RectF): Float {
        val ratio = when (mode) {
            WidgetScaleMode.LOGARITHMIC -> {
                val safe = value.coerceAtLeast(1.0)
                ((kotlin.math.ln(safe) - kotlin.math.ln(minimum)) /
                    (kotlin.math.ln(maximum) - kotlin.math.ln(minimum))).coerceIn(0.0, 1.0)
            }
            else -> ((value - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0)
        }
        return plot.bottom - ratio.toFloat() * plot.height()
    }
}

internal fun widgetYScale(
    mode: WidgetScaleMode,
    values: List<Double>,
    targetLow: Double,
    targetHigh: Double,
): WidgetYScale = when (mode) {
    WidgetScaleMode.STATIC -> WidgetYScale(mode, 40.0, 400.0)
    WidgetScaleMode.LOGARITHMIC -> WidgetYScale(mode, 40.0, 400.0)
    WidgetScaleMode.DYNAMIC -> {
        val valid = values.filter { it.isFinite() && it in 20.0..1_000.0 } + listOf(targetLow, targetHigh)
        val low = ((valid.minOrNull() ?: 40.0) - 20.0).coerceAtMost(40.0).coerceAtLeast(20.0)
        val high = ((valid.maxOrNull() ?: 400.0) + 20.0).coerceAtLeast(180.0).coerceAtMost(1_000.0)
        WidgetYScale(mode, low, high.coerceAtLeast(low + 20.0))
    }
}

internal fun renderGlucoseGraphWidget(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    width: Int,
    height: Int,
    now: Long = System.currentTimeMillis(),
    thresholds: app.aapswear.model.CgmThresholds = app.aapswear.model.CgmThresholds.DEFAULT,
    layout: ResponsiveWidgetLayout = responsiveWidgetLayout(width.toFloat(), height.toFloat()),
    pixelDensity: Float = 1f,
    configuration: WidgetInstanceConfiguration = WidgetInstanceConfiguration(),
): Bitmap {
    val safeWidth = width.coerceAtLeast(96)
    val safeHeight = height.coerceAtLeast(96)
    val topHeight = (safeHeight * 0.38f).roundToInt().coerceIn(46, safeHeight - 48)
    val graphHeight = safeHeight - topHeight
    val topLayout = responsiveWidgetLayout(safeWidth / pixelDensity, topHeight / pixelDensity)
    val top = renderMinimalGlucoseWidget(state, palette, safeWidth, topHeight, now, thresholds, topLayout, pixelDensity)
    val graph = renderWidgetGraph(state, palette, safeWidth, graphHeight, now, thresholds, layout, pixelDensity, configuration)
    val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(configuration.backgroundArgb)
    canvas.drawBitmap(top, 0f, 0f, null)
    canvas.drawBitmap(graph, 0f, topHeight.toFloat(), null)

    val unit = when (state?.glucose?.displayUnit) {
        GlucoseUnit.MMOL_L -> "mmol/L"
        GlucoseUnit.MG_DL -> "mg/dL"
        null -> ""
    }
    if (unit.isNotBlank() && TherapyDisplayFormatter.isGlucoseDisplayable(state, now)) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.argb(WidgetColorRole.TEXT)
            alpha = 170
            textAlign = Paint.Align.CENTER
            textSize = (10f * pixelDensity).coerceAtMost(topHeight * 0.15f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(unit, safeWidth / 2f, topHeight - 3f * pixelDensity, paint)
    }
    return bitmap
}

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
