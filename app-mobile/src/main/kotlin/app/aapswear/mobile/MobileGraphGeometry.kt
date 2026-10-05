package app.aapswear.mobile

import android.graphics.RectF
import kotlin.math.min

internal data class MobileCgmGraphBounds(
    val content: RectF,
    val tile: RectF,
    val plot: RectF,
    val timeAxis: RectF,
    val valueAxis: RectF,
)

internal data class MobileMetabolicGraphBounds(
    val outer: RectF,
    val plotRegion: RectF,
    val timeAxis: RectF,
    val valueAxis: RectF,
    val iobLane: RectF,
    val cobLane: RectF,
    val iobData: RectF,
    val cobData: RectF,
    val separator: RectF,
)

internal fun mobileMetabolicGraphBounds(
    width: Float,
    height: Float,
    outlineInset: Float,
    timeAxisHeight: Float,
    valueAxisWidth: Float,
    scaleOnRight: Boolean,
    separatorHeight: Float = 2f,
    markerHeadroomMaximum: Float = 24f,
): MobileMetabolicGraphBounds {
    val outer = RectF(outlineInset, outlineInset, width - outlineInset, height - outlineInset)
    val plotRegion =
        if (scaleOnRight) {
            RectF(outer.left, outer.top, outer.right - valueAxisWidth, outer.bottom - timeAxisHeight)
        } else {
            RectF(outer.left + valueAxisWidth, outer.top, outer.right, outer.bottom - timeAxisHeight)
        }
    val timeAxis = RectF(plotRegion.left, plotRegion.bottom, plotRegion.right, outer.bottom)
    val valueAxis =
        if (scaleOnRight) {
            RectF(plotRegion.right, plotRegion.top, outer.right, plotRegion.bottom)
        } else {
            RectF(outer.left, plotRegion.top, plotRegion.left, plotRegion.bottom)
        }
    val separator = separatorHeight.coerceIn(0f, plotRegion.height())
    val laneHeight = ((plotRegion.height() - separator) / 2f).coerceAtLeast(0f)
    val iobLane = RectF(plotRegion.left, plotRegion.top, plotRegion.right, plotRegion.top + laneHeight)
    val separatorBounds = RectF(plotRegion.left, iobLane.bottom, plotRegion.right, iobLane.bottom + separator)
    val cobLane = RectF(plotRegion.left, separatorBounds.bottom, plotRegion.right, plotRegion.bottom)
    val markerHeadroom = min(markerHeadroomMaximum.coerceAtLeast(0f), laneHeight * 0.28f)
    val iobData = RectF(iobLane.left, iobLane.top + markerHeadroom, iobLane.right, iobLane.bottom)
    val cobData = RectF(cobLane.left, cobLane.top + markerHeadroom, cobLane.right, cobLane.bottom)
    return MobileMetabolicGraphBounds(outer, plotRegion, timeAxis, valueAxis, iobLane, cobLane, iobData, cobData, separatorBounds)
}

internal fun roundedPlotTopTangentY(plotTop: Float, cornerRadius: Float): Float = plotTop + cornerRadius.coerceAtLeast(0f)

internal fun graphTimeBounds(plot: RectF, pointRadius: Float, outlineWidth: Float): RectF {
    val requestedInset = (pointRadius + outlineWidth).coerceAtLeast(0f)
    val inset = requestedInset.coerceAtMost(plot.width().coerceAtLeast(0f) / 2f)
    return RectF(plot.left + inset, plot.top, plot.right - inset, plot.bottom)
}

internal fun mapGraphTimeX(time: Long, start: Long, end: Long, timeBounds: RectF): Float =
    timeBounds.left + timeToXFraction(time, start, end) * timeBounds.width()

internal fun mobileCgmGraphBounds(
    width: Float,
    height: Float,
    outlineInset: Float,
    timeAxisHeight: Float,
    valueAxisWidth: Float,
    scaleOnRight: Boolean,
): MobileCgmGraphBounds {
    val content = RectF(0f, 0f, width, height)
    val tile = RectF(outlineInset, outlineInset, width - outlineInset, height - outlineInset)
    val plot =
        if (scaleOnRight) {
            RectF(tile.left, tile.top, tile.right - valueAxisWidth, tile.bottom - timeAxisHeight)
        } else {
            RectF(tile.left + valueAxisWidth, tile.top, tile.right, tile.bottom - timeAxisHeight)
        }
    val timeAxis = RectF(plot.left, plot.bottom, plot.right, tile.bottom)
    val valueAxis =
        if (scaleOnRight) {
            RectF(plot.right, plot.top, tile.right, plot.bottom)
        } else {
            RectF(tile.left, plot.top, plot.left, plot.bottom)
        }
    return MobileCgmGraphBounds(content, tile, plot, timeAxis, valueAxis)
}
