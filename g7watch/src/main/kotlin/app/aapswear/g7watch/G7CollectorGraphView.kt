package app.aapswear.g7watch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmReadingOrigin
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.model.CgmQuality
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GraphTimeWindow
import app.aapswear.model.RelativeGraphTimeAxis
import app.aapswear.uishared.SharedWearCgmGraphInput
import app.aapswear.uishared.SharedWearCgmGraphPalette
import app.aapswear.uishared.SharedWearCgmGraphRenderer

@SuppressLint("DrawAllocation")
internal class G7CollectorGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val directSettings by lazy { G7DirectToWatchSettingsStore(context) }
    private var readings: List<CgmReading> = emptyList()
    private var nowEpochMs = 0L

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width > 0 && view.height > 0) {
                    outline.setRoundRect(
                        0,
                        0,
                        view.width,
                        view.height,
                        directSettings.graphStyle().cornerRadiusDp * density,
                    )
                }
            }
        }
        clipToOutline = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
    }

    fun bind(
        readings: List<CgmReading>,
        palette: G7AppearancePalette,
        graphHours: Int,
        nowEpochMs: Long = System.currentTimeMillis(),
        targetLowMgDl: Double = 80.0,
        targetHighMgDl: Double = 160.0,
    ) {
        // Legacy parameters remain only for source compatibility with G7WatchActivity. Rendering is
        // owned entirely by the Direct-to-Watch settings store, never by Sugarlicious colors.
        palette.hashCode()
        graphHours.hashCode()
        targetLowMgDl.hashCode()
        targetHighMgDl.hashCode()
        this.readings = normalizeLocalHistory(readings)
        this.nowEpochMs = nowEpochMs
        invalidateOutline()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = nowEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val graphHours = directSettings.graphHours()
        val thresholds = directSettings.thresholds()
        val style = directSettings.graphStyle()
        val colors = directSettings.graphColors()
        val window = GraphTimeWindow.live(now, graphHours * RelativeGraphTimeAxis.HOUR_MS)

        SharedWearCgmGraphRenderer.render(
            canvas,
            width,
            height,
            density,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics),
            SharedWearCgmGraphInput(
                history = readings.map { it.toGraphSample() },
                timeWindow = window,
                nowEpochMs = now,
                thresholds = thresholds,
                palette = SharedWearCgmGraphPalette(
                    background = colors.graphBackground,
                    targetArea = colors.rangeInRange,
                    highArea = colors.rangeHigh,
                    lowArea = colors.rangeLow,
                    highLine = colors.highLine,
                    lowLine = colors.lowLine,
                    dotHigh = colors.cgmHigh,
                    dotInRange = colors.cgmInRange,
                    dotLow = colors.cgmLow,
                    dotVeryHigh = colors.cgmVeryHigh,
                    dotVeryLow = colors.cgmVeryLow,
                    dotOutline = colors.outline,
                    axisText = colors.axisLabel,
                    axisTick = colors.axisTick,
                    nowLine = colors.nowLine,
                    border = colors.divider,
                    predictionIob = colors.predictionIob,
                    predictionCob = colors.predictionCob,
                    predictionUam = colors.predictionUam,
                    predictionZeroTemp = colors.predictionZeroTemp,
                    targetText = colors.targetValue,
                    emptyText = colors.signalLoss,
                ),
                style = style,
            ),
        )
    }

    /**
     * LIVE and BACKFILL are origins of one local sensor series. A backfilled copy of an existing
     * event must never become a second plotted row. Prefer LIVE for equal sensor/session/sequence;
     * BACKFILL is retained only where it actually repairs a missing event.
     */
    private fun normalizeLocalHistory(source: List<CgmReading>): List<CgmReading> {
        val valid = source.filter { it.status == CgmReadingStatus.VALID }
        val newest = valid.maxByOrNull { it.timestampEpochMs }
        val sameSession = newest?.let { latest ->
            valid.filter { it.sensorId == latest.sensorId && it.sessionId == latest.sessionId }
        }.orEmpty()

        return sameSession
            .groupBy { reading ->
                LocalReadingIdentity(
                    reading.sensorId,
                    reading.sessionId,
                    reading.sequenceNumber,
                    if (reading.sequenceNumber == null) reading.timestampEpochMs / FALLBACK_BUCKET_MS else 0L,
                )
            }
            .values
            .map { duplicates ->
                duplicates.maxWithOrNull(
                    compareBy<CgmReading> { if (it.origin == CgmReadingOrigin.LIVE) 1 else 0 }
                        .thenBy { it.receivedAtEpochMs },
                ) ?: duplicates.first()
            }
            .sortedBy(CgmReading::timestampEpochMs)
    }

    private fun CgmReading.toGraphSample() = GlucoseSample(
        valueMgDl = glucoseMgDl,
        measuredAtEpochMs = timestampEpochMs,
        source = source,
        sensorId = sensorId,
        sessionId = sessionId,
        sequenceNumber = sequenceNumber,
        receivedAtEpochMs = receivedAtEpochMs,
        quality = CgmQuality.VALID,
    )

    private data class LocalReadingIdentity(
        val sensorId: String,
        val sessionId: String,
        val sequenceNumber: Long?,
        val fallbackMinuteBucket: Long,
    )

    private companion object {
        const val FALLBACK_BUCKET_MS = 60_000L
    }
}

/** Compatibility helpers kept for the older pure geometry tests. New rendering uses ui-shared. */
internal object G7GraphLayout {
    fun timeX(timestamp: Long, start: Long, now: Long, left: Float, right: Float): Float =
        left + ((timestamp - start).toDouble() / (now - start).coerceAtLeast(1L)).coerceIn(0.0, 1.0).toFloat() * (right - left)

    fun predictionX(mappedX: Float, dividerX: Float, outerRadius: Float, safetyGap: Float): Float =
        maxOf(mappedX, dividerX + outerRadius + safetyGap)

    fun highLabelBaseline(lineY: Float, metrics: Paint.FontMetrics, gap: Float): Float = lineY - gap - metrics.descent

    fun lowLabelBaseline(lineY: Float, metrics: Paint.FontMetrics, gap: Float): Float = lineY + gap - metrics.ascent
}
