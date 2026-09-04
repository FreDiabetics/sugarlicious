package app.aapswear.complications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.aapswear.model.AppearanceMode
import app.aapswear.model.CgmQuality
import app.aapswear.model.CgmSourceState
import app.aapswear.model.CgmThresholds
import app.aapswear.model.DataSourceId
import app.aapswear.model.Freshness
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.GraphTimeWindow
import app.aapswear.model.TargetState
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import app.aapswear.protocol.WatchGraphColors
import app.aapswear.protocol.DirectToWatchGraphColorDefaults
import app.aapswear.storage.TrendArrowStylePreferences
import app.aapswear.uishared.SharedWearCgmGraphInput
import app.aapswear.uishared.SharedWearCgmGraphPalette
import app.aapswear.uishared.SharedWearCgmGraphRenderer
import app.aapswear.uishared.SharedWearCgmGraphStyle
import app.aapswear.uishared.DirectToWatchGraphDefaults

internal data class DirectToWatchHeaderPresentation(
    val glucose: String,
    val secondary: String,
    val trend: Trend? = null,
)

internal data class DirectToWatchGraphStatusPresentation(val text: String)

internal object DirectToWatchPresentationFormatter {
    fun header(
        state: TherapyDisplayState?,
        nowEpochMs: Long,
        displayUnit: GlucoseUnit? = null,
    ): DirectToWatchHeaderPresentation {
        val freshness = TherapyDisplayFormatter.freshness(state, nowEpochMs)
        if (!isDirect(state) || !TherapyDisplayFormatter.isGlucoseDisplayable(state, nowEpochMs)) {
            return DirectToWatchHeaderPresentation(
                glucose = "—",
                secondary = unavailableLabel(state, freshness),
            )
        }
        val glucose = requireNotNull(state?.glucose)
        val resolvedUnit = displayUnit ?: glucose.displayUnit
        val delta = TherapyDisplayFormatter.signedDelta(glucose.deltaMgDl, resolvedUnit)
        val unit = if (resolvedUnit == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL"
        return DirectToWatchHeaderPresentation(
            glucose = if (resolvedUnit == GlucoseUnit.MMOL_L) {
                String.format(java.util.Locale.getDefault(), "%.1f", glucose.valueMgDl / 18.0)
            } else {
                glucose.valueMgDl.toInt().toString()
            },
            secondary = listOf(delta, unit).filter(String::isNotBlank).joinToString(" "),
            trend = glucose.trend.takeIf { TherapyDisplayFormatter.trendArrow(it).isNotBlank() },
        )
    }

    fun graphStatus(state: TherapyDisplayState?, nowEpochMs: Long, graphHours: Int): DirectToWatchGraphStatusPresentation {
        val freshness = TherapyDisplayFormatter.freshness(state, nowEpochMs)
        val age = TherapyDisplayFormatter.ageMinutesValue(state?.glucose?.measuredAtEpochMs, nowEpochMs)?.let { "${it}m" } ?: "—"
        val detail = if (isDirect(state) && state?.glucose != null) age else unavailableLabel(state, freshness)
        return DirectToWatchGraphStatusPresentation("${graphHours}h • $detail")
    }

    fun samples(state: TherapyDisplayState?, nowEpochMs: Long, graphHours: Int): List<GlucoseSample> {
        if (!isDirect(state)) return emptyList()
        val cutoff = nowEpochMs - graphHours * HOUR_MS
        return buildList {
            addAll(state?.glucoseHistory.orEmpty())
            state?.glucose?.let { glucose ->
                add(
                    GlucoseSample(
                        valueMgDl = glucose.valueMgDl,
                        measuredAtEpochMs = glucose.measuredAtEpochMs,
                        source = glucose.source,
                        sensorId = glucose.sensorId,
                        sessionId = glucose.sessionId,
                        sequenceNumber = glucose.sequenceNumber,
                        receivedAtEpochMs = glucose.receivedAtEpochMs,
                        quality = glucose.quality,
                    ),
                )
            }
        }.asSequence()
            .filter {
                it.source == DataSourceId.DEXCOM_G7_WATCH &&
                    it.quality == CgmQuality.VALID &&
                    it.valueMgDl.isFinite() && it.valueMgDl in 20.0..1_000.0 &&
                    it.measuredAtEpochMs in cutoff..(nowEpochMs + FUTURE_TOLERANCE_MS)
            }
            .distinctBy { listOf(it.sensorId, it.sessionId, it.sequenceNumber, it.measuredAtEpochMs) }
            .sortedBy(GlucoseSample::measuredAtEpochMs)
            .toList()
    }

    fun validTimeRange(state: TherapyDisplayState?, nowEpochMs: Long): TimeRange {
        // The provider owns STALE/NO_SOURCE rendering. Expiring the complication makes Wear OS
        // replace the information with EMPTY, especially in ambient mode.
        return TimeRange.ALWAYS
    }

    fun isDirect(state: TherapyDisplayState?): Boolean =
        state?.source == DataSourceId.DEXCOM_G7_WATCH &&
            G7LocalReadingResolver.sourceState(state) in setOf(CgmSourceState.WATCH_DIRECT, CgmSourceState.NO_SOURCE)

    private fun unavailableLabel(state: TherapyDisplayState?, freshness: Freshness): String = when {
        state?.glucose?.quality == CgmQuality.SENSOR_ERROR -> "SIGNAL LOSS"
        freshness == Freshness.STALE -> "STALE"
        else -> "NO_SOURCE"
    }

    const val HOUR_MS = 60L * 60_000L
    private const val FUTURE_TOLERANCE_MS = 5L * 60_000L
}

object DirectToWatchPreferences {
    const val NAME = "direct_to_watch"
    private const val KEY_GRAPH_HOURS = "graph.hours"
    private const val KEY_GRAPH_DOT_RADIUS = "graph_style_dot_radius"
    private const val KEY_GRAPH_DOT_OUTLINE_ENABLED = "graph_style_dot_outline_enabled"
    private const val KEY_GRAPH_DOT_OUTLINE_WIDTH = "graph_style_dot_outline_width"
    private const val KEY_GLUCOSE_UNIT = "display.glucose_unit"
    private const val KEY_GLUCOSE_BOLD = "display.glucose_bold"
    private const val KEY_TARGET_LOW = "target.low_mg_dl"
    private const val KEY_TARGET_HIGH = "target.high_mg_dl"
    val graphHourOptions = listOf(1, 3, 6, 12, 24)

    fun graphHours(context: Context): Int =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getInt(KEY_GRAPH_HOURS, 3).takeIf { it in graphHourOptions } ?: 3

    fun glucoseUnit(context: Context): GlucoseUnit = runCatching {
        GlucoseUnit.valueOf(
            context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getString(KEY_GLUCOSE_UNIT, GlucoseUnit.MG_DL.name)!!,
        )
    }.getOrDefault(GlucoseUnit.MG_DL)

    fun glucoseBold(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_GLUCOSE_BOLD, true)

    fun thresholds(context: Context): CgmThresholds {
        val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return CgmThresholds(
            veryHighMgDl = CgmThresholds.DEFAULT_VERY_HIGH_MG_DL,
            highMgDl = preferences.getFloat(KEY_TARGET_HIGH, CgmThresholds.DEFAULT_HIGH_MG_DL.toFloat()).toDouble(),
            lowMgDl = preferences.getFloat(KEY_TARGET_LOW, CgmThresholds.DEFAULT_LOW_MG_DL.toFloat()).toDouble(),
            veryLowMgDl = CgmThresholds.DEFAULT_VERY_LOW_MG_DL,
        ).takeIf(CgmThresholds::isValid) ?: CgmThresholds.DEFAULT
    }

    fun cycleGraphHours(context: Context): Int {
        val current = graphHours(context)
        val next = graphHourOptions[(graphHourOptions.indexOf(current) + 1) % graphHourOptions.size]
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt(KEY_GRAPH_HOURS, next).apply()
        return next
    }

    fun saveGraphHours(context: Context, hours: Int) {
        require(hours in graphHourOptions)
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt(KEY_GRAPH_HOURS, hours).apply()
        requestUpdates(context)
    }

    fun graphColors(context: Context): WatchGraphColors {
        val defaults = DirectToWatchGraphColorDefaults.create()
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return WatchGraphColors(
            graphBackground = p.getInt("graph_color_background", defaults.graphBackground),
            rangeLow = p.getInt("graph_color_range_low", defaults.rangeLow),
            rangeInRange = p.getInt("graph_color_range_in", defaults.rangeInRange),
            rangeHigh = p.getInt("graph_color_range_high", defaults.rangeHigh),
            cgmLow = p.getInt("graph_color_cgm_low", defaults.cgmLow),
            cgmInRange = p.getInt("graph_color_cgm_in", defaults.cgmInRange),
            cgmHigh = p.getInt("graph_color_cgm_high", defaults.cgmHigh),
            cgmVeryLow = p.getInt("graph_color_cgm_very_low", defaults.cgmVeryLow),
            cgmVeryHigh = p.getInt("graph_color_cgm_very_high", defaults.cgmVeryHigh),
            divider = p.getInt("graph_color_divider", defaults.divider),
            highLine = p.getInt("graph_color_high_line", defaults.highLine),
            lowLine = p.getInt("graph_color_low_line", defaults.lowLine),
            axisLabel = p.getInt("graph_color_axis_label", defaults.axisLabel),
            axisTick = p.getInt("graph_color_axis_tick", defaults.axisTick),
            nowLine = p.getInt("graph_color_now_line", defaults.nowLine),
            outline = p.getInt("graph_color_outline", defaults.outline),
            targetValue = p.getInt("graph_color_target_value", defaults.targetValue),
            signalLoss = p.getInt("graph_color_signal_loss", defaults.signalLoss),
            predictionIob = p.getInt("graph_color_prediction_iob", defaults.predictionIob),
            predictionCob = p.getInt("graph_color_prediction_cob", defaults.predictionCob),
            predictionUam = p.getInt("graph_color_prediction_uam", defaults.predictionUam),
            predictionZeroTemp = p.getInt("graph_color_prediction_zero_temp", defaults.predictionZeroTemp),
        )
    }

    fun saveGraphColors(context: Context, colors: WatchGraphColors) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt("graph_color_background", colors.graphBackground)
            .putInt("graph_color_range_low", colors.rangeLow)
            .putInt("graph_color_range_in", colors.rangeInRange)
            .putInt("graph_color_range_high", colors.rangeHigh)
            .putInt("graph_color_cgm_low", colors.cgmLow)
            .putInt("graph_color_cgm_in", colors.cgmInRange)
            .putInt("graph_color_cgm_high", colors.cgmHigh)
            .putInt("graph_color_cgm_very_low", colors.cgmVeryLow)
            .putInt("graph_color_cgm_very_high", colors.cgmVeryHigh)
            .putInt("graph_color_divider", colors.divider)
            .putInt("graph_color_high_line", colors.highLine)
            .putInt("graph_color_low_line", colors.lowLine)
            .putInt("graph_color_axis_label", colors.axisLabel)
            .putInt("graph_color_axis_tick", colors.axisTick)
            .putInt("graph_color_now_line", colors.nowLine)
            .putInt("graph_color_outline", colors.outline)
            .putInt("graph_color_target_value", colors.targetValue)
            .putInt("graph_color_signal_loss", colors.signalLoss)
            .putInt("graph_color_prediction_iob", colors.predictionIob)
            .putInt("graph_color_prediction_cob", colors.predictionCob)
            .putInt("graph_color_prediction_uam", colors.predictionUam)
            .putInt("graph_color_prediction_zero_temp", colors.predictionZeroTemp)
            .apply()
        requestUpdates(context)
    }

    fun graphStyle(context: Context): SharedWearCgmGraphStyle {
        val defaults = DirectToWatchGraphDefaults.style()
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return defaults.copy(
            dotRadiusDp = p.getFloat(KEY_GRAPH_DOT_RADIUS, defaults.dotRadiusDp).coerceIn(1.5f, 6f),
            dotOutlineEnabled = p.getBoolean(KEY_GRAPH_DOT_OUTLINE_ENABLED, defaults.dotOutlineEnabled),
            dotOutlineWidthDp = p.getFloat(KEY_GRAPH_DOT_OUTLINE_WIDTH, defaults.dotOutlineWidthDp).coerceIn(0.25f, 3f),
            cornerRadiusDp = p.getFloat("graph_style_corner_radius", defaults.cornerRadiusDp).coerceIn(0f, 40f),
            borderEnabled = p.getBoolean("graph_style_border_enabled", defaults.borderEnabled),
            timeAxisEnabled = p.getBoolean("graph_style_time_axis_enabled", defaults.timeAxisEnabled),
            targetTicksEnabled = p.getBoolean("graph_style_target_ticks_enabled", defaults.targetTicksEnabled),
            targetLabelsOutsideRange = true,
            rangeBackgroundEnabled = p.getBoolean("graph_style_range_background_enabled", defaults.rangeBackgroundEnabled),
        )
    }

    fun saveGraphStyle(context: Context, style: SharedWearCgmGraphStyle) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_GRAPH_DOT_RADIUS, style.dotRadiusDp.coerceIn(1.5f, 6f))
            .putBoolean(KEY_GRAPH_DOT_OUTLINE_ENABLED, style.dotOutlineEnabled)
            .putFloat(KEY_GRAPH_DOT_OUTLINE_WIDTH, style.dotOutlineWidthDp.coerceIn(0.25f, 3f))
            .putFloat("graph_style_corner_radius", style.cornerRadiusDp.coerceIn(0f, 40f))
            .putBoolean("graph_style_border_enabled", style.borderEnabled)
            .putBoolean("graph_style_time_axis_enabled", style.timeAxisEnabled)
            .putBoolean("graph_style_target_ticks_enabled", style.targetTicksEnabled)
            .putBoolean("graph_style_range_background_enabled", style.rangeBackgroundEnabled)
            .apply()
        requestUpdates(context)
    }

    fun resetGraphAppearance(context: Context) {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        p.edit().apply {
            p.all.keys.filter { it.startsWith("graph_color_") || it.startsWith("graph_style_") }.forEach(::remove)
        }.apply()
        requestUpdates(context)
    }

    fun trendStyle(context: Context, mode: AppearanceMode) =
        TrendArrowStylePreferences.read(
            context.getSharedPreferences(NAME, Context.MODE_PRIVATE),
            mode,
            Color.WHITE,
        )

    fun activeAppearanceMode(context: Context): AppearanceMode =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString("appearance.active_mode", null)
            ?.let { stored -> AppearanceMode.entries.firstOrNull { it.storageKey == stored } }
            ?: AppearanceMode.DARK

    fun saveTrendStyle(context: Context, mode: AppearanceMode, style: app.aapswear.model.TrendArrowStyle) {
        TrendArrowStylePreferences.write(context.getSharedPreferences(NAME, Context.MODE_PRIVATE), mode, style)
        requestUpdates(context)
    }

    fun resetTrendStyle(context: Context, mode: AppearanceMode) {
        TrendArrowStylePreferences.reset(context.getSharedPreferences(NAME, Context.MODE_PRIVATE), mode)
        requestUpdates(context)
    }

    fun requestUpdates(context: Context) {
        listOf(
            DirectToWatchHeaderComplication::class.java,
            DirectToWatchGraphComplication::class.java,
            DirectToWatchStatusComplication::class.java,
        ).forEach { service ->
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, service)).requestUpdateAll()
        }
    }
}

abstract class DirectToWatchComplicationService : SuspendingComplicationDataSourceService() {
    final override fun getPreviewData(type: ComplicationType): ComplicationData = build(previewState(), System.currentTimeMillis())

    final override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData =
        build(G7LocalReadingResolver.resolveWatchDirect(this), System.currentTimeMillis())

    protected abstract fun build(state: TherapyDisplayState?, nowEpochMs: Long): ComplicationData

    protected fun collectorTapAction(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(G7_PACKAGE)?.apply {
            component = ComponentName(G7_PACKAGE, G7_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: Intent().setComponent(ComponentName(G7_PACKAGE, G7_ACTIVITY))
        return PendingIntent.getActivity(this, 701, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    protected fun graphScaleTapAction(): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            702,
            Intent(this, DirectToWatchGraphScaleReceiver::class.java).setAction(DirectToWatchGraphScaleReceiver.ACTION_CYCLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    protected fun readThresholds(): CgmThresholds {
        return DirectToWatchPreferences.thresholds(this)
    }

    private fun previewState(): TherapyDisplayState {
        val now = System.currentTimeMillis()
        val history = (0..36).map { index ->
            GlucoseSample(
                valueMgDl = 105.0 + index * 1.3,
                measuredAtEpochMs = now - (36 - index) * 5L * 60_000L,
                source = DataSourceId.DEXCOM_G7_WATCH,
            )
        }
        return TherapyDisplayState(
            source = DataSourceId.DEXCOM_G7_WATCH,
            sourceContract = "CANONICAL_CGM_V2:WATCH_DIRECT:preview",
            sourceVersion = "G7 Watch Collector",
            receivedAtEpochMs = now,
            glucose = GlucoseState(
                valueMgDl = 152.0,
                displayUnit = GlucoseUnit.MG_DL,
                trend = Trend.FLAT,
                measuredAtEpochMs = now - 2 * 60_000L,
                deltaMgDl = 1.0,
                source = DataSourceId.DEXCOM_G7_WATCH,
            ),
            glucoseHistory = history,
            target = TargetState(lowMgDl = 70.0, highMgDl = 180.0),
        )
    }

    companion object {
        private const val G7_PACKAGE = "app.aapswear.g7watch"
        private const val G7_ACTIVITY = "app.aapswear.g7watch.G7WatchActivity"
    }
}

class DirectToWatchHeaderComplication : DirectToWatchComplicationService() {
    override fun build(state: TherapyDisplayState?, nowEpochMs: Long): ComplicationData {
        val presentation = DirectToWatchPresentationFormatter.header(state, nowEpochMs, DirectToWatchPreferences.glucoseUnit(this))
        val bitmap = renderHeader(presentation, DirectToWatchPreferences.glucoseBold(this))
        return SmallImageComplicationData.Builder(
            SmallImage.Builder(Icon.createWithBitmap(bitmap), SmallImageType.PHOTO).build(),
            PlainComplicationText.Builder("Direct to Watch ${presentation.glucose}, ${presentation.secondary}").build(),
        )
            .setTapAction(collectorTapAction())
            .setValidTimeRange(DirectToWatchPresentationFormatter.validTimeRange(state, nowEpochMs))
            .build()
    }

    internal fun renderHeader(presentation: DirectToWatchHeaderPresentation, glucoseBold: Boolean = true): Bitmap {
        val width = 340
        val height = 108
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 61f
            typeface = if (glucoseBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = Paint.Align.LEFT
        }
        val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SECONDARY_TEXT
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        val mode = DirectToWatchPreferences.activeAppearanceMode(this)
        val style = DirectToWatchPreferences.trendStyle(this, mode)
        val arrow = presentation.trend?.let {
            TrendComplicationIcon.renderScaled(this, it, 34, style.sizePercent, style = style)
        }?.let(TrendComplicationIcon::cropTransparentPadding)
        val gap = if (arrow == null) 0f else 6f
        val valueWidth = valuePaint.measureText(presentation.glucose)
        canvas.drawText(presentation.glucose, 0f, 58f, valuePaint)
        arrow?.let {
            val arrowTop = (34f - it.height / 2f).coerceAtLeast(0f)
            canvas.drawBitmap(it, valueWidth + gap, arrowTop, null)
        }
        canvas.drawText(presentation.secondary, 0f, 96f, secondaryPaint)
        return bitmap
    }

    private companion object { const val SECONDARY_TEXT = 0xFFA8A8BA.toInt() }
}

class DirectToWatchStatusComplication : DirectToWatchComplicationService() {
    override fun build(state: TherapyDisplayState?, nowEpochMs: Long): ComplicationData {
        val text = DirectToWatchPresentationFormatter.graphStatus(state, nowEpochMs, DirectToWatchPreferences.graphHours(this)).text
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder(text).build(),
        ).setTapAction(graphScaleTapAction())
            .setValidTimeRange(DirectToWatchPresentationFormatter.validTimeRange(state, nowEpochMs))
            .build()
    }
}

class DirectToWatchGraphComplication : DirectToWatchComplicationService() {
    override fun build(state: TherapyDisplayState?, nowEpochMs: Long): ComplicationData {
        val hours = DirectToWatchPreferences.graphHours(this)
        val bitmap = renderGraph(state, nowEpochMs, hours)
        return SmallImageComplicationData.Builder(
            SmallImage.Builder(Icon.createWithBitmap(bitmap), SmallImageType.PHOTO).build(),
            PlainComplicationText.Builder("$hours Stunden Direct-to-Watch-Glukoseverlauf").build(),
        ).setTapAction(graphScaleTapAction())
            .setValidTimeRange(DirectToWatchPresentationFormatter.validTimeRange(state, nowEpochMs))
            .build()
    }

    internal fun renderGraph(state: TherapyDisplayState?, nowEpochMs: Long, hours: Int): Bitmap {
        val width = 410
        val height = 250
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val density = resources.displayMetrics.density
        val graphStyle = DirectToWatchPreferences.graphStyle(this)
        val radius = graphStyle.cornerRadiusDp * density
        canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, Path.Direction.CW) })
        val colors = DirectToWatchPreferences.graphColors(this)
        val thresholds = readThresholds()
        SharedWearCgmGraphRenderer.render(
            canvas = canvas,
            widthPx = width,
            heightPx = height,
            density = density,
            scaledDensity = resources.displayMetrics.scaledDensity,
            input = SharedWearCgmGraphInput(
                history = DirectToWatchPresentationFormatter.samples(state, nowEpochMs, hours),
                timeWindow = GraphTimeWindow.live(nowEpochMs, hours * DirectToWatchPresentationFormatter.HOUR_MS),
                nowEpochMs = nowEpochMs,
                thresholds = thresholds,
                palette = colors.toSharedPalette(),
                style = graphStyle,
                emptyLabel = DirectToWatchPresentationFormatter.header(state, nowEpochMs, DirectToWatchPreferences.glucoseUnit(this)).secondary,
            ),
        )
        return bitmap
    }

    private fun WatchGraphColors.toSharedPalette() = SharedWearCgmGraphPalette(
        background = graphBackground,
        targetArea = rangeInRange,
        highArea = rangeHigh,
        lowArea = rangeLow,
        highLine = highLine,
        lowLine = lowLine,
        dotHigh = cgmHigh,
        dotVeryHigh = cgmVeryHigh,
        dotInRange = cgmInRange,
        dotLow = cgmLow,
        dotVeryLow = cgmVeryLow,
        dotOutline = outline,
        axisText = axisLabel,
        axisTick = axisTick,
        nowLine = nowLine,
        border = divider,
        predictionIob = predictionIob,
        predictionCob = predictionCob,
        predictionUam = predictionUam,
        predictionZeroTemp = predictionZeroTemp,
        targetText = targetValue,
        emptyText = signalLoss,
    )
}

class DirectToWatchGraphScaleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CYCLE) return
        DirectToWatchPreferences.cycleGraphHours(context)
        DirectToWatchPreferences.requestUpdates(context)
    }

    companion object { const val ACTION_CYCLE = "app.aapswear.complications.DIRECT_TO_WATCH_CYCLE_GRAPH" }
}
