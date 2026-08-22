package app.aapswear.g7watch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import app.aapswear.g7.CgmReading
import app.aapswear.g7.G7Sensor
import app.aapswear.model.CgmGraphPolicy
import app.aapswear.model.CgmQuality
import app.aapswear.model.GlucoseGraphScale
import app.aapswear.model.GlucoseSample
import app.aapswear.model.RangeExcursion
import kotlin.math.max

internal fun currentG7SessionReadings(
    readings: List<CgmReading>,
    sensor: G7Sensor?,
): List<CgmReading> =
    if (sensor == null) {
        emptyList()
    } else {
        readings.filter { reading ->
            reading.sensorId == sensor.sensorId &&
                (sensor.sessionId == null || reading.sessionId == sensor.sessionId)
        }
    }

internal class G7GlucoseChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var readings: List<CgmReading> = emptyList()
    private var nowEpochMs: Long = System.currentTimeMillis()
    private var colors = G7GraphColorStore(context).read()

    fun update(values: List<CgmReading>, nowEpochMs: Long = System.currentTimeMillis()) {
        readings = values
            .filter {
                it.status == app.aapswear.g7.CgmReadingStatus.VALID &&
                    it.glucoseMgDl.isFinite() &&
                    it.glucoseMgDl in 20.0..1_000.0 &&
                    it.timestampEpochMs in (nowEpochMs - WINDOW_MS)..(nowEpochMs + FUTURE_TOLERANCE_MS) &&
                    it.receivedAtEpochMs >= it.timestampEpochMs - FUTURE_TOLERANCE_MS &&
                    it.receivedAtEpochMs <= nowEpochMs + FUTURE_TOLERANCE_MS
            }
            .sortedBy(CgmReading::timestampEpochMs)
        this.nowEpochMs = nowEpochMs
        colors = G7GraphColorStore(context).read()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        paint.style = Paint.Style.FILL
        paint.color = colors.graphBackground
        canvas.drawRoundRect(bounds, 18f * density, 18f * density, paint)

        val samples = readings.map { reading ->
            GlucoseSample(
                valueMgDl = reading.glucoseMgDl,
                measuredAtEpochMs = reading.timestampEpochMs,
                source = reading.source,
                sensorId = reading.sensorId,
                sessionId = reading.sessionId,
                sequenceNumber = reading.sequenceNumber,
                receivedAtEpochMs = reading.receivedAtEpochMs,
                quality = if (reading.status == app.aapswear.g7.CgmReadingStatus.VALID) CgmQuality.VALID else CgmQuality.INVALID,
            )
        }
        val excursion = CgmGraphPolicy.rangeExcursion(samples, LOW_MG_DL, HIGH_MG_DL)
        if (excursion != null) {
            paint.color = when (excursion) {
                RangeExcursion.LOW -> colors.rangeLow
                RangeExcursion.HIGH -> colors.rangeHigh
            }
            paint.alpha = 42
            canvas.drawRoundRect(bounds, 18f * density, 18f * density, paint)
            paint.alpha = 255
        }

        val left = 16f * density
        val right = width - 16f * density
        val top = 10f * density
        val bottom = height - 12f * density
        paint.strokeWidth = max(1f, density)
        paint.color = colors.divider
        paint.alpha = 100
        listOf(LOW_MG_DL, HIGH_MG_DL).forEach { threshold ->
            val y = bottom - GlucoseGraphScale.ratio(threshold).toFloat() * (bottom - top)
            canvas.drawLine(left, y, right, y, paint)
        }
        paint.alpha = 255

        readings.forEachIndexed { index, reading ->
            val x = left + ((reading.timestampEpochMs - (nowEpochMs - WINDOW_MS)).toFloat() / WINDOW_MS) * (right - left)
            val y = bottom - GlucoseGraphScale.ratio(reading.glucoseMgDl).toFloat() * (bottom - top)
            paint.style = Paint.Style.FILL
            paint.color = when {
                reading.glucoseMgDl < LOW_MG_DL -> colors.cgmLow
                reading.glucoseMgDl > HIGH_MG_DL -> colors.cgmHigh
                else -> colors.cgmInRange
            }
            canvas.drawCircle(x, y, if (index == readings.lastIndex) 4.2f * density else 3.0f * density, paint)
            if (index == readings.lastIndex) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.1f * density
                paint.color = colors.outline
                canvas.drawCircle(x, y, 4.2f * density, paint)
            }
        }
    }

    private val density: Float get() = resources.displayMetrics.density

    private companion object {
        const val WINDOW_MS = 3L * 60L * 60_000L
        const val FUTURE_TOLERANCE_MS = 5L * 60_000L
        const val LOW_MG_DL = 80.0
        const val HIGH_MG_DL = 160.0
    }
}
