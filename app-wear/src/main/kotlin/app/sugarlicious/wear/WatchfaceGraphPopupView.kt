package app.sugarlicious.wear

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.View
import app.sugarlicious.complications.WatchfaceGraphProfile
import app.sugarlicious.model.CgmGraphHistoryPresentation
import app.sugarlicious.model.GlucoseSample
import app.sugarlicious.model.GraphTimeWindow
import app.sugarlicious.uishared.SystemTypography
import app.sugarlicious.uishared.WatchfaceCgmGraphRenderer

internal data class GraphPopupTarget(
    val profile: WatchfaceGraphProfile,
    val argb: Int,
    val inRangeArgb: Int? = null,
    val outlineArgb: Int? = null,
) {
    companion object {
        fun parse(uri: Uri?): GraphPopupTarget? {
            if (uri == null || !uri.isHierarchical || uri.scheme != "app" || uri.host != "sugarlicious.watchface-graph" || uri.port != -1 || uri.userInfo != null || uri.fragment != null) return null
            if ("color" !in uri.queryParameterNames || uri.queryParameterNames.any { it !in setOf("color", "inRange", "outline") } || uri.getQueryParameters("color").size != 1) return null
            val profile =
                when (uri.path) {
                    "/orbit" -> WatchfaceGraphProfile.ORBIT
                    "/apex" -> WatchfaceGraphProfile.APEX
                    else -> return null
                }
            val color = uri.getQueryParameter("color") ?: return null
            if (!Regex("FF[0-9A-Fa-f]{6}").matches(color)) return null
            val inRange = uri.getQueryParameter("inRange")
            if (inRange != null && (!Regex("FF[0-9A-Fa-f]{6}").matches(inRange) || uri.getQueryParameters("inRange").size != 1)) return null
            val outline = uri.getQueryParameter("outline")
            if (outline != null && (!Regex("FF[0-9A-Fa-f]{6}").matches(outline) || uri.getQueryParameters("outline").size != 1)) return null
            return GraphPopupTarget(profile, color.toLong(16).toInt(), inRange?.toLong(16)?.toInt(), outline?.toLong(16)?.toInt())
        }
    }
}

internal data class GraphPopupGeometry(
    val widthDp: Float,
    val heightDp: Float,
    val cornerRadiusDp: Float,
)

/** Same physical geometry as SugarWear's cgmGraphTileContentSpec, including its 21-dp header. */
internal fun graphPopupGeometry(screenWidthDp: Int, screenHeightDp: Int): GraphPopupGeometry {
    val shortest = minOf(screenWidthDp.takeIf { it > 0 } ?: 192, screenHeightDp.takeIf { it > 0 } ?: 192)
    val side = shortest * 0.76f
    return GraphPopupGeometry(side, (side - 21f).coerceAtLeast(1f), side * 0.13f)
}

/** Rendering only; the existing overview ViewModel owns source selection and lifecycle. */
@SuppressLint("ViewConstructor") // Created by the activity with a validated, required watchface target.
internal class WatchfaceGraphPopupView(
    context: Context,
    private val target: GraphPopupTarget,
) : View(context) {
    private var state = WearOverviewUiState()
    private var samples: List<GlucoseSample> = emptyList()
    private val border =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = target.outlineArgb ?: target.argb
            style = Paint.Style.STROKE
        }
    private val messagePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = target.argb
            textAlign = Paint.Align.CENTER
            SystemTypography.apply(this, false)
        }

    init {
        contentDescription = "Glukoseverlauf · ${if (target.profile == WatchfaceGraphProfile.ORBIT) "Orbit" else "ApeX"}"
    }

    fun update(value: WearOverviewUiState) {
        state = value
        val therapy = value.displayTherapy
        samples =
            buildList {
                addAll(therapy?.glucoseHistory.orEmpty())
                therapy?.glucose?.let {
                    add(GlucoseSample(it.valueMgDl, it.measuredAtEpochMs, it.source, it.sensorId, it.sessionId, it.sequenceNumber, it.receivedAtEpochMs, it.quality))
                }
            }
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val stroke = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_PT, OUTLINE_WIDTH_PT, resources.displayMetrics)
        border.strokeWidth = stroke
        val inset = stroke / 2f
        val radius = (width * .13f - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, border)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val message =
            when {
                state.error != null || state.snapshot.error != null -> "Graph konnte nicht geladen werden"
                state.nowEpochMs <= 0 -> "Graph wird geladen"
                samples.none { it.measuredAtEpochMs in (state.nowEpochMs - HISTORY_MS)..state.nowEpochMs } -> "Keine Messwerte"
                else -> null
            }
        if (message != null) {
            messagePaint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
            canvas.drawText(message, width / 2f, height / 2f - (messagePaint.ascent() + messagePaint.descent()) / 2f, messagePaint)
            return
        }
        WatchfaceCgmGraphRenderer.render(
            canvas,
            width,
            height,
            samples,
            GraphTimeWindow.live(state.nowEpochMs, HISTORY_MS),
            state.snapshot.preferences.cgmThresholds,
            color = target.argb,
            inRangeColor = target.inRangeArgb,
            semicircle = false,
            activeIdentity = CgmGraphHistoryPresentation.identity(state.displayTherapy?.glucose) ?: CgmGraphHistoryPresentation.latestIdentity(samples),
        )
    }

    private companion object {
        const val HISTORY_MS = 90 * 60_000L
        const val OUTLINE_WIDTH_PT = 0.25f
    }
}
