package app.sugarlicious.wear

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import app.sugarlicious.model.CgmRangeClass
import app.sugarlicious.model.CgmThresholds
import app.sugarlicious.model.GlucoseRangeBarInterval
import app.sugarlicious.model.GlucoseRangeBarPresentation
import app.sugarlicious.model.GlucoseRangeBarStyle
import app.sugarlicious.model.glucoseRangeBarPresentation
import app.sugarlicious.protocol.WatchUiColors
import kotlin.math.roundToInt

/** Single Android Canvas renderer shared by the Wear overview and the system tile snapshot.
 *
 * Matches Mobile's rounded underlay and two rounded colored sections separated by
 * a moving gap. The marker is computed from the current glucose interval, not history.
 */
internal object WearGlucoseRangeBarRenderer {
    fun color(interval: GlucoseRangeBarInterval, colors: WatchUiColors): Int =
        when (interval) {
            GlucoseRangeBarInterval.LOW -> colors.glucoseLow
            GlucoseRangeBarInterval.TARGET -> colors.accent
            GlucoseRangeBarInterval.HIGH -> colors.glucoseHigh
        }

    fun draw(
        canvas: Canvas,
        widthPx: Float,
        heightPx: Float,
        presentation: GlucoseRangeBarPresentation?,
        accent: Int,
        underlay: Int,
        gapPx: Float,
    ) {
        if (widthPx <= 0f || heightPx <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val radius = heightPx / 2f
        val marker = widthPx * (presentation?.position ?: 0.5f).coerceIn(0f, 1f)
        val halfGap = gapPx / 2f

        paint.color = underlay
        canvas.drawRoundRect(RectF(0f, 0f, widthPx, heightPx), radius, radius, paint)
        if (presentation == null) return

        paint.color = accent
        fun section(left: Float, right: Float) {
            if (right <= left) return
            val save = canvas.save()
            canvas.clipRect(left, 0f, right, heightPx)
            canvas.drawRoundRect(RectF(left, 0f, right, heightPx), radius, radius, paint)
            canvas.restoreToCount(save)
        }
        section(0f, (marker - halfGap).coerceAtLeast(0f))
        section((marker + halfGap).coerceAtMost(widthPx), widthPx)
    }
}

/** Native Kotlin-only view, animated on change and stopped when hidden/detached. */
internal class WearGlucoseRangeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var state: GlucoseRangeBarPresentation? = null
    private var displayedPosition: Float? = null
    private var accent: Int = android.graphics.Color.WHITE
    private var underlay: Int = android.graphics.Color.DKGRAY
    private var animator: ValueAnimator? = null
    private val barHeightPx: Float
        get() = GlucoseRangeBarStyle.WEAR_APP_HEIGHT_DP * resources.displayMetrics.density

    fun bind(
        valueMgDl: Double?,
        thresholds: CgmThresholds,
        colors: WatchUiColors,
    ) {
        val next = glucoseRangeBarPresentation(valueMgDl, thresholds)
        val previous = displayedPosition
        val changed = next != state
        state = next
        accent = next?.let { WearGlucoseRangeBarRenderer.color(it.interval, colors) } ?: colors.textSecondary
        underlay = colors.tileBorder
        if (!changed) {
            invalidate()
            return
        }
        animator?.cancel()
        animator = null
        if (next == null || previous == null || !isAttachedToWindow || !isShown || !ValueAnimator.areAnimatorsEnabled()) {
            displayedPosition = next?.position
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(previous, next.position).apply {
            duration = 300L
            addUpdateListener {
                displayedPosition = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val y = (height - barHeightPx) / 2f
        val save = canvas.save()
        canvas.translate(0f, y)
        WearGlucoseRangeBarRenderer.draw(
            canvas,
            width.toFloat(),
            barHeightPx,
            state?.copy(position = displayedPosition ?: state?.position ?: .5f),
            accent,
            underlay,
            GlucoseRangeBarStyle.MARKER_GAP_DP * resources.displayMetrics.density,
        )
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        displayedPosition = state?.position
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            animator?.cancel()
            animator = null
            displayedPosition = state?.position
        }
    }
}
