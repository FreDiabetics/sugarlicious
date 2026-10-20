package app.aapswear.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.model.AppearanceMode
import app.aapswear.model.CanonicalCgmHistory
import app.aapswear.model.CgmGraphPolicy
import app.aapswear.model.CgmGraphScaleMode
import app.aapswear.model.CgmGraphYScale
import app.aapswear.model.Freshness
import app.aapswear.model.GlucoseGraphScale
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseTrendSizing
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.GraphAxisLayoutSpec
import app.aapswear.model.GraphTimeWindow
import app.aapswear.model.RangeExcursion
import app.aapswear.model.RelativeGraphTimeAxis
import app.aapswear.model.TherapyDisplayFormatter
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TherapyProgressMetric
import app.aapswear.model.TherapyRingGeometry
import app.aapswear.model.TrendVisualSpec
import app.aapswear.model.TrendVisuals
import app.aapswear.model.resolveHorizontalTherapyLayout
import app.aapswear.model.resolveResponsiveTherapyLayout
import app.aapswear.storage.TherapyStateStore
import app.aapswear.uishared.TrendVectorPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import androidx.glance.color.ColorProvider as DayNightColorProvider

private fun widgetColor(argb: Int): ColorProvider = DayNightColorProvider(day = Color(argb), night = Color(argb))

enum class WidgetKind { GLUCOSE, GRAPH, GLUCOSE_GRAPH, METABOLIC }

private abstract class SugarliciousWidget(
    val stagedConfiguration: WidgetInstanceConfiguration? = null,
) : GlanceAppWidget() {
    protected abstract val kind: WidgetKind
    override val sizeMode: SizeMode = SizeMode.Exact

    override fun onCompositionError(context: Context, glanceId: GlanceId, appWidgetId: Int, throwable: Throwable) {
        // Never replace a valid existing frame with Glance's error/loading layout.
        android.util.Log.w("SugarliciousWidget", "Render failed for $appWidgetId; retaining previous view", throwable)
    }

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val appWidgetId = (id as? AppWidgetId)?.appWidgetId ?: android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
        val providerClassName =
            android.appwidget.AppWidgetManager
                .getInstance(context)
                .getAppWidgetInfo(appWidgetId)
                ?.provider
                ?.className
        val automaticShape = widgetUsesAutomaticShape(providerClassName)
        val nativeHostSized =
            widgetUsesNativeHostBounds(
                providerClassName,
                android.appwidget.AppWidgetManager
                    .getInstance(context)
                    .getAppWidgetOptions(appWidgetId),
            )
        val dashboardPreferences = context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)
        val appearanceMode = SugarliciousColorStore.activeMode(dashboardPreferences)
        val configurableKind =
            when (kind) {
                WidgetKind.GLUCOSE -> ConfigurableWidgetKind.GLUCOSE
                WidgetKind.GRAPH -> ConfigurableWidgetKind.GRAPH
                WidgetKind.GLUCOSE_GRAPH -> ConfigurableWidgetKind.GLUCOSE_GRAPH
                WidgetKind.METABOLIC -> ConfigurableWidgetKind.METABOLIC
            }
        val instance =
            (
                stagedConfiguration ?: WidgetInstanceConfigurationStore
                    .readIsolated(context, appWidgetId, configurableKind)
            ).resolvedAppearance(appearanceMode)
        val state = WidgetRenderStateCache.latest() ?: TherapyStateStore(context).state.first()
        val dashboardAppearance = DashboardUiPreferences.read(dashboardPreferences)
        val palette =
            WidgetColorStore
                .load(context)
                .with(instance.colorOverrides)
                .with(WidgetColorRole.BACKGROUND, widgetBackgroundArgb(instance.backgroundEnabled, instance.backgroundArgb))
        val thresholds = CgmThresholdPreferences.read(dashboardPreferences)
        provideContent {
            WidgetShell(kind, state, palette, thresholds, instance, appearanceMode, dashboardAppearance, automaticShape, nativeHostSized)
        }
    }
}

private class GlucoseWidget(
    configuration: WidgetInstanceConfiguration? = null,
) : SugarliciousWidget(configuration) {
    override val kind = WidgetKind.GLUCOSE
}

private class GraphWidget(
    configuration: WidgetInstanceConfiguration? = null,
) : SugarliciousWidget(configuration) {
    override val kind = WidgetKind.GRAPH
}

private class GlucoseGraphWidget(
    configuration: WidgetInstanceConfiguration? = null,
) : SugarliciousWidget(configuration) {
    override val kind = WidgetKind.GLUCOSE_GRAPH
}

private class MetabolicWidget(
    configuration: WidgetInstanceConfiguration? = null,
) : SugarliciousWidget(configuration) {
    override val kind = WidgetKind.METABOLIC
}

private fun deleteWidgetConfigurations(
    context: Context,
    ids: IntArray,
) = ids.forEach {
    WidgetInstanceConfigurationStore.delete(context, it)
}

/** Platform updates and resize use the same completed render path as settings and taps. */
abstract class SugarliciousWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        val action = intent.action
        val managed =
            action == android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE ||
                action == android.appwidget.AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED ||
                action == android.content.Intent.ACTION_LOCALE_CHANGED ||
                action == ACTION_DEBUG_UPDATE
        if (!managed) {
            super.onReceive(context, intent)
            return
        }
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val ids =
            if (action == android.appwidget.AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED) {
                intArrayOf(intent.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
            } else {
                intent.getIntArrayExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS)
                    ?: manager.getAppWidgetIds(android.content.ComponentName(context, javaClass))
            }
        val pending = goAsync()
        receiverScope.launch {
            try {
                ids.filter { it >= 0 }.forEach { id ->
                    manager.getAppWidgetInfo(id)?.provider?.className?.let { provider ->
                        SugarliciousWidgets.update(context.applicationContext, id, configurableWidgetKind(provider))
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("SugarliciousWidget", "Widget update failed; retaining previous view", error)
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

class GlucoseWidgetReceiver : SugarliciousWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlucoseWidget()

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        deleteWidgetConfigurations(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

class GlucoseFamily2x1Receiver : SugarliciousWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlucoseWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        deleteWidgetConfigurations(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

class GlucoseWidget2x2Receiver : SugarliciousWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlucoseWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        deleteWidgetConfigurations(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

class GlucoseWidget4x2Receiver : SugarliciousWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlucoseWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        deleteWidgetConfigurations(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

class GraphWidgetReceiver : SugarliciousWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GraphWidget()

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        deleteWidgetConfigurations(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

class GraphFamily2x1Receiver : SugarliciousWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GraphWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        deleteWidgetConfigurations(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

class GraphWidget2x2Receiver : SugarliciousWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GraphWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        deleteWidgetConfigurations(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

class GraphWidget4x2Receiver : SugarliciousWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GraphWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        deleteWidgetConfigurations(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

class GlucoseGraphWidgetReceiver : SugarliciousWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlucoseGraphWidget()

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { WidgetInstanceConfigurationStore.delete(context, it) }
        super.onDeleted(context, appWidgetIds)
    }
}

class MetabolicWidgetReceiver : SugarliciousWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MetabolicWidget()

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        deleteWidgetConfigurations(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

class TherapyFamily2x1Receiver : SugarliciousWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MetabolicWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        deleteWidgetConfigurations(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

class TherapyFamily2x2Receiver : SugarliciousWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MetabolicWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        deleteWidgetConfigurations(context, appWidgetIds)
        super.onDeleted(context, appWidgetIds)
    }
}

internal object SugarliciousWidgets {
    suspend fun update(context: Context) {
        TherapyStateStore(context).state.first()?.let { SugarliciousWidgetUpdateCoordinator.updateAll(context, it) }
    }

    suspend fun update(
        context: Context,
        appWidgetId: Int,
        kind: ConfigurableWidgetKind,
    ) {
        val (target, revision) = SugarliciousWidgetUpdateCoordinator.submitInstance(context, appWidgetId, kind)
        SugarliciousWidgetUpdateCoordinator.flush(context, target, revision)
    }

    suspend fun update(
        context: Context,
        state: TherapyDisplayState,
    ) {
        SugarliciousWidgetUpdateCoordinator.updateAll(context, state)
    }

    @SuppressLint("RestrictedApi")
    @OptIn(androidx.glance.ExperimentalGlanceApi::class)
    internal suspend fun renderInstance(
        context: Context,
        appWidgetId: Int,
        kind: ConfigurableWidgetKind,
    ) = WidgetRenderTransactions.run(appWidgetId) {
        val views = prepareInstance(context, appWidgetId, kind)
        android.appwidget.AppWidgetManager
            .getInstance(context)
            .updateAppWidget(appWidgetId, views)
    }

    @SuppressLint("RestrictedApi")
    @OptIn(androidx.glance.ExperimentalGlanceApi::class)
    internal suspend fun prepareInstance(
        context: Context,
        appWidgetId: Int,
        kind: ConfigurableWidgetKind,
        configuration: WidgetInstanceConfiguration? = null,
    ): android.widget.RemoteViews {
        val id = AppWidgetId(appWidgetId)
        val widget =
            when (kind) {
                ConfigurableWidgetKind.GLUCOSE -> GlucoseWidget(configuration)
                ConfigurableWidgetKind.GRAPH -> GraphWidget(configuration)
                ConfigurableWidgetKind.GLUCOSE_GRAPH -> GlucoseGraphWidget(configuration)
                ConfigurableWidgetKind.METABOLIC -> MetabolicWidget(configuration)
            }
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        return widget.compose(context, id, options = manager.getAppWidgetOptions(appWidgetId))
    }

    internal suspend fun renderFamily(
        context: Context,
        kind: WidgetKind,
    ) {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val providers = manager.installedProviders.filter { it.provider.packageName == context.packageName && configurableWidgetKind(it.provider.className).name == kind.name }
        providers.forEach { provider ->
            manager.getAppWidgetIds(provider.provider).forEach { id ->
                try {
                    renderInstance(context, id, configurableWidgetKind(provider.provider.className))
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    context.recordMobileDiagnostic("WIDGET", "WIDGET-RENDER-503", "Widget retained last-known-good surface", app.aapswear.model.DiagnosticSeverity.WARNING, mapOf("widgetId" to id, "error" to error.javaClass.simpleName))
                }
            }
        }
    }
}

@Composable
private fun WidgetShell(
    kind: WidgetKind,
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    thresholds: app.aapswear.model.CgmThresholds,
    instance: WidgetInstanceConfiguration,
    appearanceMode: AppearanceMode,
    dashboardAppearance: DashboardUiPreferences,
    automaticShape: Boolean,
    nativeHostSized: Boolean,
) {
    val size = LocalSize.current
    val context = LocalContext.current
    val contentInsets = widgetContentInsetsDp(kind, WidgetLayoutSizeStore.isSamsungCompact(context), nativeHostSized)
    val renderSize = widgetRenderSizeDp(size.width.value, size.height.value, contentInsets)
    val layout = responsiveWidgetLayout(renderSize.widthDp, renderSize.heightDp)
    val resolvedRadius =
        if (automaticShape) {
            adaptiveGlucoseCornerRadius(
                widgetFamilyLayout(renderSize.widthDp, renderSize.heightDp),
                renderSize.heightDp,
                systemWidgetCornerRadiusDp(context),
            )
        } else {
            resolveWidgetCornerRadiusDp(
                instance,
                heightDp = renderSize.heightDp,
                systemDefaultDp = systemWidgetCornerRadiusDp(context),
                pillAllowed = widgetSupportsPill(kind),
            )
        }
    val renderInstance = instance.copy(cornerRadiusDp = resolvedRadius.roundToInt())
    val launchComponent = WidgetLaunchTargetStore.launchComponent(context, instance.launchPackage)
    // Every remaining widget renders its configured background into one bitmap. Keeping the
    // Glance host transparent avoids compositing the same alpha twice and making it too opaque.
    val outerBackground = widgetColor(widgetHostBackgroundArgb())

    Column(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(outerBackground)
                .cornerRadius(resolvedRadius.dp)
                .padding(horizontal = contentInsets.horizontalDp.dp, vertical = contentInsets.verticalDp.dp)
                .clickable(actionStartActivity(launchComponent), rippleOverride = android.R.color.transparent),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        when (kind) {
            WidgetKind.GLUCOSE ->
                GlucoseWidgetContent(
                    state,
                    palette,
                    thresholds,
                    layout,
                    renderInstance,
                    appearanceMode,
                    renderSize,
                    automaticShape,
                )
            WidgetKind.GRAPH -> GraphWidgetContent(state, palette, thresholds, layout, renderInstance, renderSize)
            WidgetKind.GLUCOSE_GRAPH ->
                GlucoseGraphWidgetContent(state, palette, thresholds, layout, renderInstance, appearanceMode, renderSize)
            WidgetKind.METABOLIC ->
                MetabolicWidgetContent(state, palette, renderInstance, appearanceMode, dashboardAppearance, renderSize, automaticShape)
        }
    }
}

internal data class WidgetContentInsets(
    val horizontalDp: Float,
    val verticalDp: Float,
) {
    companion object {
        val ZERO = WidgetContentInsets(0f, 0f)
    }
}

internal data class WidgetRenderSize(
    val widthDp: Float,
    val heightDp: Float,
)

internal fun widgetRenderSizeDp(
    hostWidthDp: Float,
    hostHeightDp: Float,
    insets: WidgetContentInsets,
): WidgetRenderSize =
    WidgetRenderSize(
        widthDp = (hostWidthDp - 2f * insets.horizontalDp).coerceAtLeast(1f),
        heightDp = (hostHeightDp - 2f * insets.verticalDp).coerceAtLeast(1f),
    )

internal fun widgetPreviewRenderSizeDp(
    kind: WidgetKind,
    hostWidthDp: Float,
    hostHeightDp: Float,
    samsungCompact: Boolean,
    nativeHostSized: Boolean = false,
): WidgetRenderSize =
    widgetRenderSizeDp(
        hostWidthDp,
        hostHeightDp,
        widgetContentInsetsDp(kind, samsungCompact, nativeHostSized),
    )

internal fun widgetContentInsetsDp(
    kind: WidgetKind,
    samsungCompact: Boolean = false,
    nativeHostSized: Boolean = false,
): WidgetContentInsets =
    when (kind) {
        WidgetKind.GLUCOSE, WidgetKind.GRAPH, WidgetKind.GLUCOSE_GRAPH, WidgetKind.METABOLIC ->
            if (samsungCompact && !nativeHostSized) {
                WidgetContentInsets(
                    horizontalDp = SAMSUNG_COMPACT_WIDGET_HORIZONTAL_INSET_DP,
                    verticalDp = SAMSUNG_COMPACT_WIDGET_VERTICAL_INSET_DP,
                )
            } else {
                WidgetContentInsets.ZERO
            }
    }

@Composable
private fun GlucoseWidgetContent(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    thresholds: app.aapswear.model.CgmThresholds,
    layout: ResponsiveWidgetLayout,
    instance: WidgetInstanceConfiguration,
    appearanceMode: AppearanceMode,
    renderSize: WidgetRenderSize,
    adaptiveFamily: Boolean,
) {
    val pixelDensity =
        LocalContext.current.resources.displayMetrics.density
            .coerceAtLeast(1f)
    val context = LocalContext.current
    val trendStyle =
        instance.trendStyle(
            appearanceMode,
            MobileTrendArrowAppearance.load(context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE), appearanceMode),
        )
    val width = (renderSize.widthDp * pixelDensity).roundToInt().coerceAtLeast(48)
    val height = (renderSize.heightDp * pixelDensity).roundToInt().coerceAtLeast(48)
    val bitmap =
        if (adaptiveFamily) {
            renderAdaptiveGlucoseWidget(
                state = state,
                palette = palette,
                width = width,
                height = height,
                thresholds = thresholds,
                pixelDensity = pixelDensity,
                configuration = instance,
                trendStyle = trendStyle,
                systemCornerRadiusDp = systemWidgetCornerRadiusDp(context),
            )
        } else {
            renderMinimalGlucoseWidget(
                state = state,
                palette = palette,
                width = width,
                height = height,
                thresholds = thresholds,
                layout = layout,
                pixelDensity = pixelDensity,
                options =
                    GlucoseWidgetRenderOptions(
                        glucoseScale = configurationScale(instance.glucoseScalePercent),
                        trendScale = configurationScale(instance.trendScalePercent),
                        outlineEnabled = instance.outlineEnabled,
                        outlineArgb = instance.outlineArgb,
                        cornerRadiusDp = instance.cornerRadiusDp.toFloat(),
                        trendStyle = trendStyle,
                        glucoseBold = instance.glucoseBold,
                        configuration = instance,
                    ),
            )
        }
    Image(ImageProvider(bitmap), "Glukose und Trend", GlanceModifier.fillMaxSize(), contentScale = ContentScale.Fit)
}

internal data class AdaptiveGlucoseGeometry(
    val valueBounds: RectF,
    val graphBounds: RectF? = null,
    val dividerX: Float? = null,
)

internal data class CompactGlucoseColumns(
    val valueLeft: Float,
    val supplementCenter: Float,
)

internal fun compactGlucoseColumns(width: Float, valueWidth: Float, supplementWidth: Float, density: Float): CompactGlucoseColumns {
    val gap = minOf(12f * density, (width - valueWidth - supplementWidth).coerceAtLeast(0f))
    val left = ((width - valueWidth - gap - supplementWidth) / 2f).coerceAtLeast(0f)
    return CompactGlucoseColumns(left, left + valueWidth + gap + supplementWidth / 2f)
}

internal data class WidgetGlucoseContentGeometry(
    val valueBounds: RectF,
    val groupWidth: Float,
)

internal fun adaptiveGlucoseGeometry(
    width: Float,
    height: Float,
    layout: WidgetFamilyLayout,
): AdaptiveGlucoseGeometry =
    when (layout) {
        WidgetFamilyLayout.COMPACT_2X1,
        WidgetFamilyLayout.DETAIL_2X2,
        -> AdaptiveGlucoseGeometry(RectF(0f, 0f, width, height))
        WidgetFamilyLayout.WIDE_4X2 -> {
            val divider = width / 2f
            AdaptiveGlucoseGeometry(
                valueBounds = RectF(0f, 0f, divider - 12f, height),
                graphBounds = RectF(divider + width * .035f, height * .16f, width * .965f, height * .86f),
                dividerX = divider,
            )
        }
    }

internal fun adaptiveGlucoseCornerRadius(
    layout: WidgetFamilyLayout,
    heightDp: Float,
    systemRadiusDp: Float,
): Float = if (layout == WidgetFamilyLayout.COMPACT_2X1) heightDp / 2f else systemRadiusDp

internal fun renderAdaptiveGlucoseWidget(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    width: Int,
    height: Int,
    now: Long = System.currentTimeMillis(),
    thresholds: app.aapswear.model.CgmThresholds = app.aapswear.model.CgmThresholds.DEFAULT,
    pixelDensity: Float = 1f,
    configuration: WidgetInstanceConfiguration,
    trendStyle: app.aapswear.model.TrendArrowStyle,
    systemCornerRadiusDp: Float = SAMSUNG_WIDGET_RADIUS_FALLBACK_DP,
): Bitmap {
    val safeDensity = pixelDensity.coerceAtLeast(1f)
    val widthDp = width / safeDensity
    val heightDp = height / safeDensity
    val familyLayout = widgetFamilyLayout(widthDp, heightDp)
    val geometry = adaptiveGlucoseGeometry(width.toFloat(), height.toFloat(), familyLayout)
    val radiusDp = adaptiveGlucoseCornerRadius(familyLayout, heightDp, systemCornerRadiusDp)
    val bitmap = createBitmap(width.coerceAtLeast(48), height.coerceAtLeast(48), Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    clipWidgetCanvas(canvas, bitmap.width, bitmap.height, radiusDp, safeDensity)
    canvas.drawColor(palette.argb(WidgetColorRole.BACKGROUND))

    val glucose = state?.glucose
    val unit = if (glucose?.displayUnit == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL"
    val delta = TherapyDisplayFormatter.signedDelta(glucose?.deltaMgDl, glucose?.displayUnit ?: GlucoseUnit.MG_DL).replace('-', '−')
    val age = TherapyDisplayFormatter.ageMinutes(glucose?.measuredAtEpochMs, now)
    val history =
        buildList {
            addAll(state?.glucoseHistory.orEmpty())
            glucose?.let { add(GlucoseSample(it.valueMgDl, it.measuredAtEpochMs)) }
        }
    val tir = currentDayTirPercent(history, thresholds.lowMgDl, thresholds.highMgDl, now)
    val text = widgetGlucoseFamilyText(familyLayout, age, delta, unit, tir)
    val valueWidth =
        geometry.valueBounds
            .width()
            .roundToInt()
            .coerceAtLeast(48)
    val valueHeight =
        when (familyLayout) {
            WidgetFamilyLayout.COMPACT_2X1 -> height
            WidgetFamilyLayout.DETAIL_2X2, WidgetFamilyLayout.WIDE_4X2 -> (height * .47f).roundToInt()
        }.coerceAtLeast(48)
    var valueContent: WidgetGlucoseContentGeometry? = null
    val valueBitmap =
        renderMinimalGlucoseWidget(
            state = state,
            palette = palette,
            width = valueWidth,
            height = valueHeight,
            now = now,
            thresholds = thresholds,
            layout = responsiveWidgetLayout(valueWidth / safeDensity, valueHeight / safeDensity),
            pixelDensity = safeDensity,
            options =
                GlucoseWidgetRenderOptions(
                    glucoseScale = configurationScale(configuration.glucoseScalePercent) * if (familyLayout == WidgetFamilyLayout.COMPACT_2X1) .88f else .82f,
                    trendScale = configurationScale(configuration.trendScalePercent),
                    leftAligned = familyLayout == WidgetFamilyLayout.COMPACT_2X1,
                    leftInsetDp = 0f,
                    centerGlyphs = true,
                    cornerRadiusDp = 0f,
                    drawBackground = false,
                    trendStyle = trendStyle,
                    glucoseBold = configuration.glucoseBold,
                ),
            onContentGeometry = { valueContent = it },
        )

    fun centeredPaint(sizeSp: Float, role: WidgetColorRole, bold: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.argb(role)
            textSize = sizeSp * safeDensity
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    val compactAgePaint = centeredPaint(15f * configuration.ageScalePercent / 100f, WidgetColorRole.AGE, configuration.ageBold)
    val compactDeltaPaint = centeredPaint(15f * configuration.deltaScalePercent / 100f, WidgetColorRole.DELTA, configuration.deltaBold)
    val compactColumns =
        compactGlucoseColumns(
            width.toFloat(),
            valueContent?.groupWidth ?: valueWidth.toFloat(),
            maxOf(compactAgePaint.measureText(text.age.orEmpty()), compactDeltaPaint.measureText(text.delta.orEmpty())),
            safeDensity,
        )
    val valueTop = geometry.valueBounds.top + if (familyLayout == WidgetFamilyLayout.COMPACT_2X1) 0f else 4f * safeDensity
    val valueLeft = if (familyLayout == WidgetFamilyLayout.COMPACT_2X1) compactColumns.valueLeft else geometry.valueBounds.left
    canvas.drawBitmap(valueBitmap, valueLeft, valueTop, null)

    when (familyLayout) {
        WidgetFamilyLayout.COMPACT_2X1 -> {
            val x = compactColumns.supplementCenter
            canvas.drawText(text.age.orEmpty(), x + configuration.ageOffsetXDp * safeDensity, height * .43f + configuration.ageOffsetYDp * safeDensity, compactAgePaint)
            canvas.drawText(text.delta.orEmpty(), x + configuration.deltaOffsetXDp * safeDensity, height * .68f + configuration.deltaOffsetYDp * safeDensity, compactDeltaPaint)
        }
        WidgetFamilyLayout.DETAIL_2X2,
        WidgetFamilyLayout.WIDE_4X2,
        -> {
            val centerX = geometry.valueBounds.centerX()
            val baseY = height * .58f - 5f * safeDensity
            val detailPaint = centeredPaint(14f * configuration.deltaScalePercent / 100f, WidgetColorRole.DELTA, configuration.deltaBold)
            val agePaint = centeredPaint(13f * configuration.ageScalePercent / 100f, WidgetColorRole.AGE, configuration.ageBold)
            val tirPaint = centeredPaint(13f, WidgetColorRole.TIR, configuration.tirBold)
            val rows =
                listOf(
                    Triple(text.delta.orEmpty(), detailPaint, WidgetColorRole.DELTA_ICON),
                    Triple(text.age.orEmpty(), agePaint, WidgetColorRole.AGE_ICON),
                    Triple(text.tir.orEmpty(), tirPaint, WidgetColorRole.TIR_ICON),
                )
            val iconSize = 22f * safeDensity
            val gap = 6f * safeDensity
            val blockWidth = iconSize + gap + rows.maxOf { it.second.measureText(it.first) }
            val left = centerX - blockWidth / 2f
            rows.forEachIndexed { index, (label, paint, role) ->
                val offsetX =
                    if (index == 0) {
                        configuration.deltaOffsetXDp
                    } else if (index == 1) {
                        configuration.ageOffsetXDp
                    } else {
                        0
                    }
                val offsetY =
                    if (index == 0) {
                        configuration.deltaOffsetYDp
                    } else if (index == 1) {
                        configuration.ageOffsetYDp
                    } else {
                        0
                    }
                val y = baseY + (index * 25f + offsetY) * safeDensity
                paint.textAlign = Paint.Align.LEFT
                val iconTop = y + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f - iconSize / 2f
                WidgetDetailIcons.draw(canvas, role, left + offsetX * safeDensity, iconTop, iconSize, palette.argb(role), AndroidColor.BLACK)
                canvas.drawText(label, left + iconSize + gap + offsetX * safeDensity, y, paint)
            }
        }
    }

    geometry.dividerX?.let { x ->
        canvas.drawLine(
            x,
            18f * safeDensity,
            x,
            height - 18f * safeDensity,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = configuration.outlineArgb
                strokeWidth = safeDensity
                alpha = 110
            },
        )
    }
    geometry.graphBounds?.let { bounds ->
        valueContent?.let { bounds.top = valueTop + it.valueBounds.top }
        val graph =
            renderWidgetGraph(
                state = state,
                palette = palette,
                width = bounds.width().roundToInt().coerceAtLeast(96),
                height = bounds.height().roundToInt().coerceAtLeast(72),
                now = now,
                thresholds = thresholds,
                layout = responsiveWidgetLayout(bounds.width() / safeDensity, bounds.height() / safeDensity),
                pixelDensity = safeDensity,
                configuration = configuration.copy(outlineEnabled = false, graphCornerRadiusDp = 4),
                clipToWidgetShape = false,
                showScaleAndAge = true,
                showAgeInHeader = false,
                headerStartInsetDp = 6f,
            )
        canvas.drawBitmap(graph, bounds.left, bounds.top, null)
    }
    drawWidgetOutline(
        canvas,
        bitmap.width,
        bitmap.height,
        GlucoseWidgetRenderOptions(
            outlineEnabled = configuration.outlineEnabled,
            outlineArgb = configuration.outlineArgb,
            cornerRadiusDp = radiusDp,
        ),
        safeDensity,
    )
    return bitmap
}

internal const val SAMSUNG_WIDGET_RADIUS_FALLBACK_DP = 28f
internal const val DEFAULT_WIDGET_GRAPH_CORNER_RADIUS_DP = 16
internal const val MIN_WIDGET_GRAPH_CORNER_RADIUS_DP = 4
internal const val MAX_WIDGET_GRAPH_CORNER_RADIUS_DP = 32
internal const val DEFAULT_COMBINED_WIDGET_VALUE_PERCENT = 38
internal const val MIN_COMBINED_WIDGET_VALUE_PERCENT = 34
internal const val MAX_COMBINED_WIDGET_VALUE_PERCENT = 44

internal fun resolveWidgetCornerRadiusDp(
    configuration: WidgetInstanceConfiguration,
    heightDp: Float,
    systemDefaultDp: Float = SAMSUNG_WIDGET_RADIUS_FALLBACK_DP,
    pillAllowed: Boolean = true,
): Float =
    when {
        pillAllowed && configuration.shapeMode == WidgetShapeMode.PILL -> heightDp.coerceAtLeast(1f) / 2f
        else -> systemDefaultDp
    }

internal fun widgetSupportsPill(kind: ConfigurableWidgetKind): Boolean =
    kind == ConfigurableWidgetKind.GLUCOSE ||
        kind == ConfigurableWidgetKind.GRAPH ||
        kind == ConfigurableWidgetKind.METABOLIC

private fun widgetSupportsPill(kind: WidgetKind): Boolean =
    kind == WidgetKind.GLUCOSE || kind == WidgetKind.GRAPH || kind == WidgetKind.METABOLIC

@SuppressLint("DiscouragedApi") // This optional framework dimen has no public android.R symbol; fallback remains mandatory.
internal fun systemWidgetCornerRadiusDp(context: Context): Float {
    val resources = context.resources
    val identifier = resources.getIdentifier("system_app_widget_background_radius", "dimen", "android")
    if (identifier == 0) return SAMSUNG_WIDGET_RADIUS_FALLBACK_DP
    return runCatching { resources.getDimension(identifier) / resources.displayMetrics.density }
        .getOrDefault(SAMSUNG_WIDGET_RADIUS_FALLBACK_DP)
        .takeIf { it > 0f }
        ?: SAMSUNG_WIDGET_RADIUS_FALLBACK_DP
}

@Composable
private fun GraphWidgetContent(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    thresholds: app.aapswear.model.CgmThresholds,
    layout: ResponsiveWidgetLayout,
    instance: WidgetInstanceConfiguration,
    renderSize: WidgetRenderSize,
) {
    val pixelDensity =
        LocalContext.current.resources.displayMetrics.density
            .coerceAtLeast(1f)
    val now = System.currentTimeMillis()
    val bitmap =
        renderWidgetGraph(
            state = state,
            palette = palette,
            width = (renderSize.widthDp * pixelDensity).roundToInt().coerceAtLeast(96),
            height = (renderSize.heightDp * pixelDensity).roundToInt().coerceAtLeast(72),
            thresholds = thresholds,
            layout = layout,
            pixelDensity = pixelDensity,
            now = now,
            configuration = instance,
        )
    val excursion = CgmGraphPolicy.rangeExcursion(canonicalWidgetSamples(state, now), thresholds)
    val label = graphScaleAgeText(instance.graphHours, TherapyDisplayFormatter.ageMinutesValue(state?.glucose?.measuredAtEpochMs, now))
    Box(GlanceModifier.fillMaxSize()) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = "Sugarlicious CGM-Graph",
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Box(
            modifier = GlanceModifier.width(140.dp).height(48.dp).clickable(actionRunCallback<CycleGraphScaleAction>(), rippleOverride = android.R.color.transparent),
        ) {
            Text(
                text = label,
                modifier = GlanceModifier.padding(start = widgetGraphHeaderStartDp(instance.cornerRadiusDp.toFloat()).dp, top = 6.dp),
                style = TextStyle(color = widgetColor(palette.argb(graphScaleAgeColorRole(excursion))), fontSize = layout.graphAxisTextSp.sp),
            )
        }
    }
}

class CycleGraphScaleAction : ActionCallback {
    @SuppressLint("RestrictedApi")
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: androidx.glance.action.ActionParameters,
    ) {
        val appWidgetId = (glanceId as? AppWidgetId)?.appWidgetId ?: return
        WidgetRenderTransactions.run(appWidgetId) {
            val current = WidgetInstanceConfigurationStore.readIsolated(context, appWidgetId, ConfigurableWidgetKind.GRAPH)
            WidgetInstanceConfigurationStore.save(context, appWidgetId, current.copy(graphHours = nextWidgetGraphHours(current.graphHours)))
        }
        SugarliciousWidgets.update(context, appWidgetId, ConfigurableWidgetKind.GRAPH)
    }
}

@Composable
private fun MetabolicWidgetContent(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    configuration: WidgetInstanceConfiguration,
    appearanceMode: AppearanceMode,
    dashboardAppearance: DashboardUiPreferences,
    renderSize: WidgetRenderSize,
    adaptiveFamily: Boolean,
) {
    val context = LocalContext.current
    val density =
        context.resources.displayMetrics.density
            .coerceAtLeast(1f)
    val bitmap =
        renderTherapyIndicatorWidget(
            context,
            state,
            palette,
            (renderSize.widthDp * density).roundToInt(),
            (renderSize.heightDp * density).roundToInt(),
            System.currentTimeMillis(),
            dashboardAppearance,
            configuration,
            density,
            appearanceMode,
            adaptiveFamily,
        )
    Image(ImageProvider(bitmap), "IOB, COB und Basal", GlanceModifier.fillMaxSize(), contentScale = ContentScale.Fit)
}

internal fun widgetBackgroundArgb(
    enabled: Boolean,
    configuredArgb: Int,
): Int = if (enabled) configuredArgb else AndroidColor.TRANSPARENT

internal fun widgetHostBackgroundArgb(): Int = AndroidColor.TRANSPARENT

internal fun therapyWidgetTextArgb(
    appearanceMode: AppearanceMode,
    palette: WidgetPalette,
): Int = if (appearanceMode == AppearanceMode.LIGHT) AndroidColor.BLACK else palette.argb(WidgetColorRole.TEXT)

internal fun therapyWidgetSecondaryTextAlpha(
    appearanceMode: AppearanceMode,
    textArgb: Int,
): Int =
    if (appearanceMode == AppearanceMode.LIGHT) {
        255
    } else {
        (AndroidColor.alpha(textArgb) * 0.72f).roundToInt()
    }

internal fun renderTherapyIndicatorWidget(
    context: Context,
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    width: Int,
    height: Int,
    now: Long,
    dashboardAppearance: DashboardUiPreferences,
    configuration: WidgetInstanceConfiguration,
    density: Float =
        context.resources.displayMetrics.density
            .coerceAtLeast(1f),
    appearanceMode: AppearanceMode = AppearanceMode.DARK,
    adaptiveFamily: Boolean = true,
): Bitmap {
    val safeWidth = width.coerceAtLeast(96)
    val safeHeight = height.coerceAtLeast(48)
    val bitmap = createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val renderDensity = density.coerceAtLeast(0.5f)
    clipWidgetCanvas(
        canvas,
        safeWidth,
        safeHeight,
        configuration.cornerRadiusDp.takeIf { it > 0 }?.toFloat() ?: SAMSUNG_WIDGET_RADIUS_FALLBACK_DP,
        renderDensity,
    )
    canvas.drawColor(widgetBackgroundArgb(configuration.backgroundEnabled, configuration.backgroundArgb))

    val allIndicators =
        therapyIndicatorPresentations(
            state,
            dashboardAppearance.iobProgressMaximumUnits,
            now,
            dashboardAppearance.cobProgressMaximumGrams,
        )
    val metricByIndex = listOf(TherapyProgressMetric.IOB, TherapyProgressMetric.COB, TherapyProgressMetric.BASAL)
    val indicatorsByMetric = metricByIndex.zip(allIndicators).toMap()
    val resolvedLayout =
        if (adaptiveFamily) {
            resolveWidgetTherapyLayout(
                safeWidth / renderDensity,
                safeHeight / renderDensity,
                configuration.therapyProgressAppearance,
            )
        } else {
            resolveResponsiveTherapyLayout(
                safeWidth / renderDensity,
                safeHeight / renderDensity,
                configuration.therapyProgressAppearance,
            )
        }
    val geometry = TherapyRingGeometry()
    val valuePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = therapyWidgetTextArgb(appearanceMode, palette)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    val secondaryPaint =
        Paint(valuePaint).apply {
            alpha = therapyWidgetSecondaryTextAlpha(appearanceMode, color)
        }

    resolvedLayout.forEach { resolved ->
        valuePaint.typeface = Typeface.create(Typeface.DEFAULT, if (configuration.therapyTextBold["${resolved.metric.name}.primary"] ?: true) Typeface.BOLD else Typeface.NORMAL)
        secondaryPaint.typeface = Typeface.create(Typeface.DEFAULT, if (configuration.therapyTextBold["${resolved.metric.name}.secondary"] ?: true) Typeface.BOLD else Typeface.NORMAL)
        val indicator = indicatorsByMetric.getValue(resolved.metric)
        val centerX = resolved.slot.centerXDp * renderDensity
        val centerY = resolved.slot.centerYDp * renderDensity
        val diameter = resolved.slot.diameterDp * renderDensity
        val stroke = resolved.strokeWidthDp * renderDensity
        val ringBounds =
            RectF(
                centerX - diameter / 2f + stroke / 2f,
                centerY - diameter / 2f + stroke / 2f,
                centerX + diameter / 2f - stroke / 2f,
                centerY + diameter / 2f - stroke / 2f,
            )
        val accent = SugarliciousColors.argb(indicator.colorRole)
        val trackPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                alpha = (AndroidColor.alpha(accent) * 0.30f).roundToInt()
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
            }
        canvas.drawArc(ringBounds, geometry.composeStartDegrees, geometry.sweepDegrees, false, trackPaint)
        indicator.progress?.takeIf { it > 0f }?.let { progress ->
            canvas.drawArc(
                ringBounds,
                geometry.composeStartDegrees,
                geometry.sweepDegrees * progress.coerceIn(0f, 1f),
                false,
                Paint(trackPaint).apply { alpha = AndroidColor.alpha(accent) },
            )
        }

        valuePaint.textSize =
            therapyIndicatorFontSizeSp(indicator.value) * renderDensity * (resolved.primaryTextSizeSp / 19f)
        val valueBaseline = centerY - (valuePaint.ascent() + valuePaint.descent()) / 2f - 3f * renderDensity
        canvas.drawText(indicator.value, centerX, valueBaseline, valuePaint)
        indicator.secondary?.let { secondary ->
            secondaryPaint.textSize =
                therapyIndicatorSecondaryFontSizeSp(secondary) * renderDensity * (resolved.secondaryTextSizeSp / 11f)
            canvas.drawText(secondary, centerX, valueBaseline + 12f * renderDensity, secondaryPaint)
        }

        val iconSize = indicator.iconSizeDp * renderDensity * (resolved.iconSizeDp / 19f)
        val iconLeft = (centerX - iconSize / 2f).roundToInt()
        val iconTop = therapyWidgetIconTopPx(centerY, diameter, iconSize, iconLeft.toFloat(), safeWidth.toFloat(), safeHeight.toFloat(), configuration.cornerRadiusDp * renderDensity).roundToInt()
        ContextCompat.getDrawable(context, indicator.iconRes)?.mutate()?.also { icon ->
            icon.setTint(accent)
            icon.setBounds(iconLeft, iconTop, iconLeft + iconSize.roundToInt(), iconTop + iconSize.roundToInt())
            icon.draw(canvas)
        }
    }
    return bitmap
}

internal fun therapyWidgetIconTopPx(
    centerY: Float,
    diameter: Float,
    iconSize: Float,
    iconLeft: Float,
    width: Float,
    height: Float,
    cornerRadius: Float,
): Float {
    val preferredTop = centerY + diameter / 2f - iconSize * .78f
    val sideDistance = minOf(iconLeft, width - iconLeft - iconSize)
    val bottomLimit = height - roundedWidgetContourInset(cornerRadius, sideDistance) - 1f
    return minOf(preferredTop, bottomLimit - iconSize)
}

internal fun resolveWidgetTherapyLayout(
    availableWidthDp: Float,
    availableHeightDp: Float,
    appearance: app.aapswear.model.TherapyProgressAppearance,
): List<app.aapswear.model.ResolvedTherapyIndicator> {
    val enabled = TherapyProgressMetric.entries.filter(appearance.validated().enabledMetrics::contains)
    val familyLayout = widgetFamilyLayout(availableWidthDp, availableHeightDp)
    val capacity = if (familyLayout == WidgetFamilyLayout.COMPACT_2X1) 2 else 3
    val selected = enabled.take(capacity)
    if (selected.isEmpty()) return emptyList()
    val selectedAppearance = appearance.copy(enabledMetrics = selected.toSet())
    if (familyLayout != WidgetFamilyLayout.DETAIL_2X2 || selected.size < 3) {
        return resolveHorizontalTherapyLayout(availableWidthDp, availableHeightDp, selectedAppearance)
    }

    val halfHeight = availableHeightDp / 2f
    val top =
        resolveHorizontalTherapyLayout(
            availableWidthDp,
            halfHeight,
            appearance.copy(enabledMetrics = setOf(selected.first())),
        )
    val bottom =
        resolveHorizontalTherapyLayout(
            availableWidthDp,
            halfHeight,
            appearance.copy(enabledMetrics = selected.drop(1).toSet()),
        ).map { indicator ->
            indicator.copy(slot = indicator.slot.copy(centerYDp = indicator.slot.centerYDp + halfHeight))
        }
    return top + bottom
}

private fun widgetStatusLine(
    state: TherapyDisplayState?,
    freshness: Freshness,
    now: Long,
    compact: Boolean,
): String {
    val source = TherapyDisplayFormatter.sourceName(state?.source)
    val age = TherapyDisplayFormatter.ageMinutes(state?.glucose?.measuredAtEpochMs, now).takeUnless { it == "—" }
    val parts =
        if (compact) {
            listOf(
                source,
                age.orEmpty(),
            )
        } else {
            listOf(source, age.orEmpty(), TherapyDisplayFormatter.freshnessLabel(freshness))
        }
    return parts.filter(String::isNotBlank).joinToString(" · ")
}

internal fun widgetAge(
    state: TherapyDisplayState?,
    now: Long,
): String = TherapyDisplayFormatter.ageMinutes(state?.glucose?.measuredAtEpochMs, now).takeUnless { it == "—" }.orEmpty()

internal fun widgetFreshnessStatus(freshness: Freshness): String = TherapyDisplayFormatter.freshnessLabel(freshness)

private fun statusColor(
    freshness: Freshness,
    palette: WidgetPalette,
): ColorProvider =
    widgetColor(
        palette.argb(
            when (freshness) {
                Freshness.CURRENT -> WidgetColorRole.IN_RANGE
                Freshness.DELAYED -> WidgetColorRole.HIGH
                Freshness.STALE, Freshness.SIGNAL_LOSS, Freshness.ERROR, Freshness.NO_DATA -> WidgetColorRole.URGENT_LOW
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
    width: Int,
    height: Int,
    now: Long = System.currentTimeMillis(),
    thresholds: app.aapswear.model.CgmThresholds = app.aapswear.model.CgmThresholds.DEFAULT,
    layout: ResponsiveWidgetLayout = responsiveWidgetLayout(width.toFloat(), height.toFloat()),
    pixelDensity: Float = 1f,
    configuration: WidgetInstanceConfiguration = WidgetInstanceConfiguration(showTimeAxis = true),
    clipToWidgetShape: Boolean = true,
    graphLeftInsetDp: Float? = null,
    graphHorizontalInsetDp: Float = 0f,
    density: Float = pixelDensity,
    scaledDensity: Float = density,
    showScaleAndAge: Boolean = false,
    showAgeInHeader: Boolean = true,
    forceOpaqueGraphSurface: Boolean = false,
    headerStartInsetDp: Float? = null,
): Bitmap {
    val safeWidth = width.coerceAtLeast(96)
    val safeHeight = height.coerceAtLeast(72)
    val bitmap = createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    if (clipToWidgetShape) {
        clipWidgetCanvas(
            canvas,
            safeWidth,
            safeHeight,
            configuration.cornerRadiusDp.takeIf { it > 0 }?.toFloat() ?: SAMSUNG_WIDGET_RADIUS_FALLBACK_DP,
            pixelDensity,
        )
    }
    val outerBackground =
        if (clipToWidgetShape &&
            configuration.backgroundEnabled
        ) {
            configuration.backgroundArgb
        } else {
            AndroidColor.TRANSPARENT
        }
    val graphBackground =
        palette.argb(WidgetColorRole.GRAPH_BACKGROUND).let { color ->
            if (forceOpaqueGraphSurface) color or 0xFF000000.toInt() else color
        }
    val text = palette.argb(WidgetColorRole.AXIS)
    canvas.drawColor(outerBackground)

    // LocalSize is expressed in dp while an ImageProvider bitmap is pixel based. Rendering at
    // dp resolution makes launchers upscale the bitmap, producing the blurred/stretched result.
    val renderDensity = density.coerceIn(0.5f, 4f)
    val renderScaledDensity = scaledDensity.coerceIn(0.5f, 4f)
    val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = text
            textSize = layout.graphAxisTextSp * renderScaledDensity
            typeface = Typeface.create(Typeface.DEFAULT, if (configuration.graphLabelBold) Typeface.BOLD else Typeface.NORMAL)
        }
    val metrics =
        widgetGraphMetrics(
            safeWidth,
            safeHeight,
            renderDensity,
            layout.copy(graphDotRadiusDp = configuration.cgmDotRadiusDp),
            textPaint,
            configuration.showTimeAxis,
            configuration.graphCornerRadiusDp.toFloat(),
            graphLeftInsetDp,
            graphHorizontalInsetDp,
            maxOf(
                configuration.historicalDotOutlineWidthDp.takeIf { configuration.historicalDotOutlineEnabled } ?: 0f,
                configuration.currentDotOutlineWidthDp,
            ),
        )
    val plot = metrics.plot
    val graphBounds = metrics.graphBounds
    val targetLow = thresholds.lowMgDl
    val targetHigh = thresholds.highMgDl
    val windowMs = configuration.graphHours.toLong() * 60L * 60_000L
    val allSamples = canonicalWidgetSamples(state, now, windowMs)
    val samples = allSamples
    val excursion = CgmGraphPolicy.rangeExcursion(allSamples, thresholds)
    val yScale = widgetYScale(configuration.scaleMode, allSamples.map(GlucoseSample::valueMgDl), targetLow, targetHigh)
    val targetTop = yScale.map(targetHigh, plot)
    val targetBottom = yScale.map(targetLow, plot)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun laneColor(color: Int): Int {
        val alpha = (color ushr 24) * configuration.scaleLaneOpacityPercent.coerceIn(0, 100) / 100
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    fun drawSplitArea(
        top: Float,
        bottom: Float,
        color: Int,
    ) {
        fill.color = color
        canvas.drawRect(graphBounds.left, top, metrics.yTickStartPx, bottom, fill)
        fill.color = laneColor(color)
        canvas.drawRect(metrics.yTickStartPx, top, graphBounds.right, bottom, fill)
    }
    val graphClip = widgetGraphClipPath(metrics)
    canvas.save()
    canvas.clipPath(graphClip)
    fill.color = graphBackground
    canvas.drawRect(graphBounds.left, graphBounds.top, metrics.yTickStartPx, graphBounds.bottom, fill)
    fill.color = laneColor(graphBackground)
    canvas.drawRect(metrics.yTickStartPx, graphBounds.top, graphBounds.right, targetTop, fill)
    canvas.drawRect(metrics.yTickStartPx, targetBottom, graphBounds.right, graphBounds.bottom, fill)
    if (excursion == RangeExcursion.HIGH) {
        drawSplitArea(graphBounds.top, targetTop, palette.argb(WidgetColorRole.RANGE_HIGH))
    }
    drawSplitArea(targetTop, targetBottom, palette.argb(WidgetColorRole.RANGE_IN_RANGE))
    if (excursion == RangeExcursion.LOW) {
        drawSplitArea(targetBottom, graphBounds.bottom, palette.argb(WidgetColorRole.RANGE_LOW))
    }
    val line =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = metrics.lineWidthPx
        }
    line.color = palette.argb(WidgetColorRole.HIGH_LINE)
    canvas.drawLine(graphBounds.left, targetTop, metrics.yTickStartPx, targetTop, line)
    line.color = laneColor(palette.argb(WidgetColorRole.HIGH_LINE))
    canvas.drawLine(metrics.yTickStartPx, targetTop, graphBounds.right, targetTop, line)
    line.color = palette.argb(WidgetColorRole.LOW_LINE)
    canvas.drawLine(graphBounds.left, targetBottom, metrics.yTickStartPx, targetBottom, line)
    line.color = laneColor(palette.argb(WidgetColorRole.LOW_LINE))
    canvas.drawLine(metrics.yTickStartPx, targetBottom, graphBounds.right, targetBottom, line)

    val timeWindow = GraphTimeWindow.live(now, windowMs)
    val start = timeWindow.startEpochMs

    fun x(timestamp: Long): Float = widgetGraphPointX(timestamp, timeWindow, plot)
    val liveX = x(timeWindow.liveEdgeEpochMs)
    val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val currentSample = samples.maxByOrNull(GlucoseSample::measuredAtEpochMs)
    samples.forEach { sample ->
        // The widget is redrawn as one bitmap. Do not pin multiple samples to an inner edge:
        // clipping is handled by the widget shape and every X position remains timestamp-derived.
        val pointX = x(sample.measuredAtEpochMs)
        val y = yScale.map(sample.valueMgDl, plot)
        pointPaint.color =
            palette.argb(
                when (widgetGlucoseColorRole(sample.valueMgDl, thresholds)) {
                    WidgetColorRole.HIGH, WidgetColorRole.VERY_HIGH -> WidgetColorRole.DOT_HIGH
                    WidgetColorRole.LOW, WidgetColorRole.URGENT_LOW -> WidgetColorRole.DOT_LOW
                    else -> WidgetColorRole.DOT_IN_RANGE
                },
            )
        val radius = metrics.dotRadiusPx
        canvas.drawCircle(pointX, y, radius, pointPaint)
        val isCurrent = sample == currentSample
        val outlineEnabled = isCurrent || configuration.historicalDotOutlineEnabled
        if (outlineEnabled) {
            val outlineWidthDp = if (isCurrent) configuration.currentDotOutlineWidthDp else configuration.historicalDotOutlineWidthDp
            val outlinePaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = outlineWidthDp.coerceIn(0.25f, 3f) * renderDensity
                    color = palette.argb(if (isCurrent) WidgetColorRole.CURRENT_DOT_OUTLINE else WidgetColorRole.DOT_OUTLINE)
                }
            canvas.drawCircle(pointX, y, radius + outlinePaint.strokeWidth / 2f, outlinePaint)
        }
    }
    canvas.restore()

    if (showScaleAndAge) {
        val ageMinutes = TherapyDisplayFormatter.ageMinutesValue(state?.glucose?.measuredAtEpochMs, now)
        val status = if (showAgeInHeader) graphScaleAgeText(configuration.graphHours, ageMinutes) else "${configuration.graphHours}h"
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, if (configuration.graphLabelBold) Typeface.BOLD else Typeface.NORMAL)
        val inset = 6f * renderDensity
        textPaint.color = palette.argb(graphScaleAgeColorRole(excursion))
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = metrics.axisTextSizePx
        val baseline = graphBounds.top + inset - textPaint.fontMetrics.ascent
        val startInset = headerStartInsetDp ?: widgetGraphHeaderStartDp(configuration.cornerRadiusDp.toFloat())
        canvas.drawText(status, graphBounds.left + startInset * renderDensity, baseline, textPaint)
        textPaint.color = text
    }

    textPaint.textAlign = Paint.Align.CENTER
    line.strokeWidth = metrics.lineWidthPx
    if (configuration.showTimeAxis) {
        RelativeGraphTimeAxis.ticks(start, now, now, RelativeGraphTimeAxis.intervalHours(configuration.graphHours.toDouble())).forEach { tick ->
            val tickX = if (tick.hoursBack == 0) liveX else x(tick.timestampEpochMs)
            if (tickX in plot.left..plot.right) {
                val labelSize = if (tick.hoursBack == 0) metrics.nowTextSizePx else metrics.axisTextSizePx
                textPaint.textSize = labelSize
                textPaint.textAlign = if (tick.hoursBack == 0) Paint.Align.RIGHT else Paint.Align.CENTER
                val labelX =
                    widgetTimeAxisLabelX(
                        tickX,
                        textPaint.measureText(tick.label),
                        tick.hoursBack == 0,
                        safeWidth.toFloat(),
                        metrics.edgeGapPx,
                        widgetTimeAxisInkInset(configuration.cornerRadiusDp * renderDensity, safeHeight.toFloat(), metrics.axisBaselinePx, tick.label, textPaint),
                    )
                line.color = palette.argb(if (tick.hoursBack == 0) WidgetColorRole.DIVIDER else WidgetColorRole.AXIS_TICK)
                canvas.drawLine(tickX, graphBounds.bottom, tickX, graphBounds.bottom + 5f * renderDensity, line)
                canvas.drawText(tick.label, labelX, metrics.axisBaselinePx, textPaint)
                textPaint.textSize = metrics.axisTextSizePx
            }
        }
    }
    textPaint.textAlign = Paint.Align.LEFT
    line.color = palette.argb(WidgetColorRole.AXIS_TICK)
    val targetLabelGap = 2f * renderDensity

    fun drawTargetLabel(value: Double, proposedBaseline: Float, alternativeBaseline: Float) {
        val label = value.roundToInt().toString()
        val font = textPaint.fontMetrics
        val candidate = if (proposedBaseline + font.ascent < metrics.edgeGapPx || proposedBaseline + font.descent > safeHeight - metrics.edgeGapPx) alternativeBaseline else proposedBaseline
        val baseline = candidate.coerceIn(-font.ascent + metrics.edgeGapPx, safeHeight - font.descent - metrics.edgeGapPx)
        val radius = configuration.cornerRadiusDp * renderDensity
        val contourInset =
            maxOf(
                roundedWidgetContourInset(radius, baseline + font.ascent),
                roundedWidgetContourInset(radius, safeHeight - baseline - font.descent),
            )
        val labelX = minOf(metrics.yLabelLeftPx, safeWidth - contourInset - textPaint.measureText(label) - metrics.edgeGapPx)
        canvas.drawText(label, labelX, baseline, textPaint)
    }
    drawTargetLabel(targetHigh, targetTop - targetLabelGap - textPaint.fontMetrics.descent, targetTop + targetLabelGap - textPaint.fontMetrics.ascent)
    drawTargetLabel(targetLow, targetBottom + targetLabelGap - textPaint.fontMetrics.ascent, targetBottom - targetLabelGap - textPaint.fontMetrics.descent)
    drawWidgetOutline(canvas, safeWidth, safeHeight, configuration, renderDensity)
    return bitmap
}

internal fun widgetGraphPointX(
    timestampEpochMs: Long,
    timeWindow: GraphTimeWindow,
    plot: RectF,
): Float = timeWindow.plotX(timestampEpochMs, plot.left, plot.width())

internal fun roundedWidgetContourInset(radius: Float, distanceFromEdge: Float): Float {
    val safeRadius = radius.coerceAtLeast(0f)
    val offset = safeRadius - distanceFromEdge.coerceIn(0f, safeRadius)
    return safeRadius - kotlin.math.sqrt((safeRadius * safeRadius - offset * offset).coerceAtLeast(0f))
}

internal fun widgetGraphHeaderStartDp(radiusDp: Float): Float = maxOf(10f, roundedWidgetContourInset(radiusDp, 6f) + 2f)

internal fun widgetTimeAxisInkInset(radius: Float, height: Float, baseline: Float, label: String, paint: Paint): Float {
    val ink = Rect().also { paint.getTextBounds(label, 0, label.length, it) }
    return roundedWidgetContourInset(radius, height - baseline - ink.bottom)
}

internal fun widgetTimeAxisLabelX(
    tickX: Float,
    labelWidth: Float,
    rightAligned: Boolean,
    width: Float,
    edgeGap: Float,
    cornerRadius: Float,
): Float {
    // The axis sits near the bottom of the bitmap, where rectangular bounds alone do not
    // protect labels from the host's rounded contour (including the compact composition).
    val inset = maxOf(edgeGap, cornerRadius).coerceIn(0f, ((width - labelWidth) / 2f).coerceAtLeast(0f))
    return if (rightAligned) {
        tickX.coerceIn(labelWidth + inset, width - inset)
    } else {
        tickX.coerceIn(labelWidth / 2f + inset, width - labelWidth / 2f - inset)
    }
}

internal data class WidgetGraphMetrics(
    val graphBounds: RectF,
    val plot: RectF,
    val graphCornerRadiusPx: Float,
    val axisTextSizePx: Float,
    val nowTextSizePx: Float,
    val axisBaselinePx: Float,
    val dotRadiusPx: Float,
    val outlineWidthPx: Float,
    val lineWidthPx: Float,
    val edgeGapPx: Float,
    val yTickStartPx: Float,
    val yTickEndPx: Float,
    val yLabelLeftPx: Float,
)

internal fun widgetGraphClipPath(metrics: WidgetGraphMetrics): Path =
    Path().apply {
        addRoundRect(
            metrics.graphBounds.left,
            metrics.graphBounds.top,
            metrics.graphBounds.right,
            metrics.graphBounds.bottom,
            metrics.graphCornerRadiusPx,
            metrics.graphCornerRadiusPx,
            Path.Direction.CW,
        )
    }

internal fun widgetGraphMetrics(
    widthPx: Int,
    heightPx: Int,
    density: Float,
    layout: ResponsiveWidgetLayout,
    textPaint: Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = layout.graphAxisTextSp * density.coerceIn(1f, 4f)
        },
    showTimeAxis: Boolean = true,
    graphCornerRadiusDp: Float = DEFAULT_WIDGET_GRAPH_CORNER_RADIUS_DP.toFloat(),
    graphLeftInsetDp: Float? = null,
    graphHorizontalInsetDp: Float = 0f,
    maxDotOutlineWidthDp: Float = CgmGraphVisualPolicy.DOT_OUTLINE_WIDTH_DP,
): WidgetGraphMetrics {
    val safeDensity = density.coerceIn(1f, 4f)
    val lowSurface = heightPx / safeDensity < 110f
    val axisText = (if (lowSurface) minOf(layout.graphAxisTextSp, 8f) else layout.graphAxisTextSp) * safeDensity
    val nowText = (layout.graphAxisTextSp + 2f).coerceAtMost(13f) * safeDensity
    textPaint.textSize = axisText
    // Axis text sits inside the full-bleed surface, but must also clear the launcher's rounded
    // clipping mask. A tiny edge clamp is insufficient near the lower corners (notably for 3h).
    val edgeGap = (if (lowSurface) 4f else 8f) * safeDensity
    val dotRadius = layout.graphDotRadiusDp.coerceIn(1.5f, 6f) * safeDensity
    val outline = maxOf(layout.graphOutlineDp.coerceIn(0.8f, 1f), maxDotOutlineWidthDp.coerceIn(0f, 3f)) * safeDensity
    val lineWidth = layout.graphLineDp.coerceIn(1f, 1.2f) * safeDensity
    val yLabelWidth = maxOf(textPaint.measureText("160"), textPaint.measureText("80"))
    textPaint.textSize = nowText
    textPaint.textSize = axisText
    val fontHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
    val bottomBand =
        if (showTimeAxis) {
            maxOf(fontHeight + (if (lowSurface) 2f else 4f) * safeDensity, (if (lowSurface) 12f else 16f) * safeDensity)
                .coerceAtMost(heightPx * 0.28f)
        } else {
            2f * safeDensity
        }
    val graphLeftInset = (graphLeftInsetDp ?: if (lowSurface) 4f else 8f) * safeDensity
    val graphVerticalInset = (if (lowSurface) 1f else 3f) * safeDensity
    val graphLeft = 0f
    val graphTop = 0f
    val axisSpec = if (lowSurface) GraphAxisLayoutSpec.COMPACT else GraphAxisLayoutSpec.DEFAULT
    val labelLeft = widthPx - graphLeftInset - yLabelWidth
    val yTickEnd = labelLeft - axisSpec.tickToLabelGapDp * safeDensity
    val yTickStart = yTickEnd - axisSpec.tickLengthDp * safeDensity
    // Match the compact Mobile lane: the live edge sits immediately before the target tick/label
    // group instead of reserving a second, visually empty gutter.
    val graphRight =
        (yTickStart + 2f * safeDensity)
            .coerceAtLeast(graphLeft + 24f * safeDensity)
    val graphBottom = (heightPx - bottomBand - graphVerticalInset).coerceAtLeast(graphTop + 24f * safeDensity)
    val visualInset = graphHorizontalInsetDp.coerceAtLeast(0f) * safeDensity
    val graphBounds = RectF(visualInset, 0f, widthPx - visualInset, graphBottom)
    val pointInset = (dotRadius + outline / 2f).coerceAtMost(minOf(graphBounds.width(), graphBounds.height()) * 0.12f)
    val plot =
        RectF(
            graphBounds.left + pointInset,
            graphTop + pointInset,
            graphRight - pointInset,
            graphBounds.bottom - pointInset,
        )
    val cornerRadius =
        (
            graphCornerRadiusDp.coerceIn(
                MIN_WIDGET_GRAPH_CORNER_RADIUS_DP.toFloat(),
                MAX_WIDGET_GRAPH_CORNER_RADIUS_DP.toFloat(),
            ) * safeDensity
        ).coerceAtMost(minOf(graphBounds.width(), graphBounds.height()) / 2f)
    val baseline = if (showTimeAxis) heightPx - textPaint.fontMetrics.descent - 1f * safeDensity else heightPx.toFloat()
    return WidgetGraphMetrics(
        graphBounds = graphBounds,
        plot = plot,
        graphCornerRadiusPx = cornerRadius,
        axisTextSizePx = axisText,
        nowTextSizePx = nowText,
        axisBaselinePx = baseline,
        dotRadiusPx = dotRadius,
        outlineWidthPx = outline,
        lineWidthPx = lineWidth,
        edgeGapPx = edgeGap,
        yTickStartPx = yTickStart,
        yTickEndPx = yTickEnd,
        // Target values begin immediately beside the live/Now boundary. The lane still keeps its
        // full clipping/background width, but no longer leaves a visually empty gutter before text.
        yLabelLeftPx = plot.right + pointInset + 1f * safeDensity,
    )
}

@Composable
private fun GlucoseGraphWidgetContent(
    state: TherapyDisplayState?,
    palette: WidgetPalette,
    thresholds: app.aapswear.model.CgmThresholds,
    layout: ResponsiveWidgetLayout,
    instance: WidgetInstanceConfiguration,
    appearanceMode: AppearanceMode,
    renderSize: WidgetRenderSize,
) {
    val context = LocalContext.current
    val density =
        context.resources.displayMetrics.density
            .coerceAtLeast(1f)
    val trendStyle =
        instance.trendStyle(
            appearanceMode,
            MobileTrendArrowAppearance.load(context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE), appearanceMode),
        )
    val bitmap =
        renderGlucoseGraphWidget(
            state,
            palette,
            (renderSize.widthDp * density).roundToInt().coerceAtLeast(96),
            (renderSize.heightDp * density).roundToInt().coerceAtLeast(96),
            System.currentTimeMillis(),
            thresholds,
            layout,
            density,
            instance,
            trendStyle,
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
    options: GlucoseWidgetRenderOptions =
        GlucoseWidgetRenderOptions(
            glucoseScale = configurationScale(100),
            trendScale = configurationScale(100),
        ),
    onContentGeometry: ((WidgetGlucoseContentGeometry) -> Unit)? = null,
): Bitmap {
    val bitmap = createBitmap(width.coerceAtLeast(48), height.coerceAtLeast(48), Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    clipWidgetCanvas(canvas, bitmap.width, bitmap.height, options.cornerRadiusDp, pixelDensity)
    if (options.drawBackground) {
        canvas.drawColor(palette.argb(WidgetColorRole.BACKGROUND))
    }
    val displayable = TherapyDisplayFormatter.isGlucoseDisplayable(state, now)
    val known = TherapyDisplayFormatter.isGlucoseKnown(state)
    val samples = canonicalWidgetSamples(state, now)
    val presentation = widgetRangePresentation(state, samples, thresholds, now)
    val glucose = state?.glucose
    val configuration = options.configuration
    val supplement =
        configuration?.let {
            val unit = if (glucose?.displayUnit == GlucoseUnit.MMOL_L) "mmol/L" else "mg/dL"
            val delta = TherapyDisplayFormatter.signedDelta(glucose?.deltaMgDl, glucose?.displayUnit ?: GlucoseUnit.MG_DL).replace('-', '−')
            val age = TherapyDisplayFormatter.ageMinutes(glucose?.measuredAtEpochMs, now)
            widgetGlucoseSupplement(it.showDelta, it.showAge, delta, age, unit)
        }
    val value = if (known && glucose != null) TherapyDisplayFormatter.glucose(glucose) else "–"
    val color = palette.argb(if (known) presentation.visibleRole else WidgetColorRole.TEXT)
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = layout.glucoseTextSp * pixelDensity.coerceIn(1f, 4f) * options.glucoseScale
            typeface = widgetTextTypeface(options.glucoseBold)
        }
    val textWidth = paint.measureText(value)
    val textBounds = Rect().also { paint.getTextBounds(value, 0, value.length, it) }
    val textHeight = textBounds.height().toFloat().coerceAtLeast(paint.textSize * 0.65f)
    val spec = if (displayable) glucose?.trend?.let(TrendVisuals::spec) else null
    // The widget arrow intentionally sits 4 dp below the Mobile value height. It remains the
    // canonical Mobile vector and is uniformly scaled for every rotation.
    val arrowTargetHeight = widgetTrendArrowTargetHeight(textHeight, options.trendScale, pixelDensity)
    val arrowGeometry = spec?.let { trendArrowGeometry(arrowTargetHeight, it) }
    val arrowSize = arrowGeometry?.scalePx ?: 0f
    val arrowWidth = arrowGeometry?.groupWidthPx ?: 0f
    val gap = if (spec != null) (paint.textSize * (6f / 42f)).coerceAtLeast(3f) else 0f
    val groupWidth = textWidth + gap + arrowWidth
    val startX =
        if (options.leftAligned) {
            options.leftInsetDp * pixelDensity
        } else {
            widgetGlucoseGroupStartX(bitmap.width.toFloat(), groupWidth, supplement != null)
        }
    val centerY = bitmap.height / 2f + options.verticalOffsetDp * pixelDensity
    val baseline = centerY - if (options.centerGlyphs) (textBounds.top + textBounds.bottom) / 2f else (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
    onContentGeometry?.invoke(WidgetGlucoseContentGeometry(RectF(startX, baseline + textBounds.top, startX + textWidth, baseline + textBounds.bottom), groupWidth))
    canvas.drawText(value, startX, baseline, paint)
    if (spec != null) {
        val arrowPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = options.trendStyle.fillColor
                style = Paint.Style.FILL
                alpha = (255 * options.trendStyle.alpha).roundToInt().coerceIn(0, 255)
            }
        val outlinePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = options.trendStyle.outlineColor
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                strokeWidth = options.trendStyle.outlineThicknessDp * pixelDensity / arrowSize.coerceAtLeast(0.01f)
                alpha = (255 * options.trendStyle.alpha).roundToInt().coerceIn(0, 255)
            }
        val left = startX + textWidth + gap
        val top = centerY - arrowTargetHeight / 2f
        canvas.withTranslation(left, top) {
            scale(arrowSize, arrowSize)
            TrendVectorPaths.forAsset(spec.asset).forEach { pathData ->
                val path = PathParser.createPathFromPathData(pathData)
                if (options.trendStyle.outlineEnabled) drawPath(path, outlinePaint)
                drawPath(path, arrowPaint)
            }
        }
    }
    configuration?.let { widgetConfiguration ->
        supplement?.let { extra ->
            val right = bitmap.width - 12f * pixelDensity

            fun supplementalPaint(scale: Int, role: WidgetColorRole) =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = palette.argb(role)
                    textSize = paint.textSize * .42f * scale / 100f
                    textAlign = Paint.Align.RIGHT
                    val bold =
                        when (role) {
                            WidgetColorRole.DELTA -> widgetConfiguration.deltaBold
                            WidgetColorRole.AGE -> widgetConfiguration.ageBold
                            else -> widgetConfiguration.deltaUnitBold
                        }
                    typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
                }

            fun draw(text: String?, x: Float, y: Int, baseY: Float, p: Paint) {
                if (!text.isNullOrBlank()) canvas.drawText(text, x, baseY + y * pixelDensity, p)
            }

            val agePaint = supplementalPaint(widgetConfiguration.ageScalePercent, WidgetColorRole.AGE)
            val deltaPaint = supplementalPaint(widgetConfiguration.deltaScalePercent, WidgetColorRole.DELTA)
            val unitPaint = supplementalPaint(widgetConfiguration.unitScalePercent, WidgetColorRole.UNIT)
            val ageRight = right + widgetConfiguration.ageOffsetXDp * pixelDensity
            val separatorGap = 5f * pixelDensity
            if (extra.topRight != null) {
                draw(extra.topRight, ageRight, widgetConfiguration.ageOffsetYDp, centerY, agePaint)
            }
            if (extra.separator != null && extra.topLeft != null && extra.topRight != null) {
                val ageWidth = agePaint.measureText(extra.topRight)
                val separatorPaint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = palette.argb(WidgetColorRole.AGE)
                        textSize = minOf(deltaPaint.textSize, agePaint.textSize)
                        textAlign = Paint.Align.RIGHT
                    }
                val separatorRight = ageRight - ageWidth - separatorGap
                draw(extra.separator, separatorRight, 0, centerY, separatorPaint)
                val deltaRight =
                    separatorRight - separatorPaint.measureText(extra.separator) - separatorGap +
                        widgetConfiguration.deltaOffsetXDp * pixelDensity
                draw(extra.topLeft, deltaRight, widgetConfiguration.deltaOffsetYDp, centerY, deltaPaint)
            } else {
                draw(
                    extra.topLeft,
                    right + widgetConfiguration.deltaOffsetXDp * pixelDensity,
                    widgetConfiguration.deltaOffsetYDp,
                    centerY,
                    deltaPaint,
                )
            }
            draw(
                extra.bottomRight,
                right + widgetConfiguration.unitOffsetXDp * pixelDensity,
                widgetConfiguration.unitOffsetYDp,
                centerY + paint.textSize * .38f,
                unitPaint,
            )
        }
    }
    drawWidgetOutline(canvas, bitmap.width, bitmap.height, options, pixelDensity)
    return bitmap
}

internal data class GlucoseWidgetRenderOptions(
    val glucoseScale: Float = 1f,
    val trendScale: Float = 1f,
    val leftAligned: Boolean = false,
    val leftInsetDp: Float = 12f,
    val verticalOffsetDp: Float = 0f,
    val centerGlyphs: Boolean = false,
    val outlineEnabled: Boolean = false,
    val outlineArgb: Int = AndroidColor.DKGRAY,
    val cornerRadiusDp: Float = 20f,
    val drawBackground: Boolean = true,
    val trendStyle: app.aapswear.model.TrendArrowStyle =
        app.aapswear.model.TrendArrowStyle.defaults(
            AppearanceMode.DARK,
            AndroidColor.WHITE,
        ),
    val glucoseBold: Boolean = true,
    val configuration: WidgetInstanceConfiguration? = null,
)

internal data class WidgetGlucoseSupplement(
    val topLeft: String? = null,
    val separator: String? = null,
    val topRight: String? = null,
    val bottomRight: String? = null,
)

internal fun widgetGlucoseSupplement(showDelta: Boolean, showAge: Boolean, delta: String, age: String, unit: String): WidgetGlucoseSupplement? =
    when {
        showDelta && showAge -> WidgetGlucoseSupplement(topLeft = delta, separator = "·", topRight = age, bottomRight = unit)
        showDelta -> WidgetGlucoseSupplement(topLeft = delta, bottomRight = unit)
        showAge -> WidgetGlucoseSupplement(topRight = age)
        else -> null
    }

internal fun widgetGlucoseGroupStartX(containerWidthPx: Float, groupWidthPx: Float, hasSupplement: Boolean): Float {
    val availableWidth = if (hasSupplement) containerWidthPx * .62f else containerWidthPx
    return ((availableWidth - groupWidthPx) / 2f).coerceAtLeast(0f)
}

/** Uses Android's system family at real weight 900; Typeface.BOLD is only weight 700. */
internal fun widgetTextTypeface(extraBold: Boolean): Typeface = Typeface.create(if (extraBold) "sans-serif-black" else "sans-serif", Typeface.NORMAL)

private fun configurationScale(percent: Int): Float = GlucoseTrendSizing.scaleFactor(percent)

internal fun widgetTrendArrowTargetHeight(
    textHeightPx: Float,
    trendScale: Float,
    pixelDensity: Float,
): Float =
    GlucoseTrendSizing
        .arrowHeightForGlucoseHeight(textHeightPx, trendScale)
        .coerceAtLeast(8f * pixelDensity.coerceIn(1f, 4f))

private fun drawWidgetOutline(
    canvas: AndroidCanvas,
    width: Int,
    height: Int,
    configuration: WidgetInstanceConfiguration,
    density: Float,
) {
    if (!configuration.outlineEnabled) return
    val stroke = density.coerceIn(1f, 4f)
    val radius = (configuration.cornerRadiusDp.takeIf { it > 0 } ?: 20) * density
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = configuration.outlineArgb
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
    canvas.drawRoundRect(stroke / 2f, stroke / 2f, width - stroke / 2f, height - stroke / 2f, radius, radius, paint)
}

private fun drawWidgetOutline(
    canvas: AndroidCanvas,
    width: Int,
    height: Int,
    options: GlucoseWidgetRenderOptions,
    density: Float,
) {
    if (!options.outlineEnabled) return
    val stroke = density.coerceIn(1f, 4f)
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = options.outlineArgb
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
    val radius = options.cornerRadiusDp * density
    canvas.drawRoundRect(stroke / 2f, stroke / 2f, width - stroke / 2f, height - stroke / 2f, radius, radius, paint)
}

private fun clipWidgetCanvas(
    canvas: AndroidCanvas,
    width: Int,
    height: Int,
    radiusDp: Float,
    density: Float,
) {
    val radius = radiusDp * density.coerceIn(1f, 4f)
    val clip =
        Path().apply {
            addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, Path.Direction.CW)
        }
    canvas.clipPath(clip)
}

internal data class TrendArrowGeometry(
    val scalePx: Float,
    val groupWidthPx: Float,
)

internal fun trendArrowGeometry(
    targetVisibleHeightPx: Float,
    spec: TrendVisualSpec,
): TrendArrowGeometry {
    val scale = targetVisibleHeightPx / spec.canvasHeight
    return TrendArrowGeometry(
        scalePx = scale,
        groupWidthPx = spec.canvasWidth * scale,
    )
}

internal data class WidgetYScale(
    val mode: WidgetScaleMode,
    val minimum: Double,
    val maximum: Double,
) {
    fun map(
        value: Double,
        plot: RectF,
    ): Float {
        val ratio =
            when (mode) {
                WidgetScaleMode.STATIC -> GlucoseGraphScale.ratio(value)
                WidgetScaleMode.LOGARITHMIC -> {
                    val safe = value.coerceAtLeast(1.0)
                    (
                        (kotlin.math.ln(safe) - kotlin.math.ln(minimum)) /
                            (kotlin.math.ln(maximum) - kotlin.math.ln(minimum))
                    ).coerceIn(0.0, 1.0)
                }
                WidgetScaleMode.DYNAMIC -> ((value - minimum) / (maximum - minimum)).coerceIn(0.0, 1.0)
            }
        return plot.bottom - ratio.toFloat() * plot.height()
    }
}

internal fun widgetYScale(
    mode: WidgetScaleMode,
    values: List<Double>,
    targetLow: Double,
    targetHigh: Double,
): WidgetYScale =
    when (mode) {
        WidgetScaleMode.STATIC -> WidgetYScale(mode, 40.0, 400.0)
        WidgetScaleMode.LOGARITHMIC -> WidgetYScale(mode, 40.0, 400.0)
        WidgetScaleMode.DYNAMIC -> {
            val scale =
                CgmGraphYScale.resolve(
                    mode = CgmGraphScaleMode.DYNAMIC,
                    visibleValuesMgDl = values,
                    requiredValuesMgDl = listOf(targetLow, targetHigh),
                )
            WidgetYScale(mode, scale.minimumMgDl, scale.maximumMgDl)
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
    trendStyle: app.aapswear.model.TrendArrowStyle =
        app.aapswear.model.TrendArrowStyle
            .defaults(AppearanceMode.DARK, AndroidColor.WHITE),
): Bitmap {
    val safeWidth = width.coerceAtLeast(96)
    val safeHeight = height.coerceAtLeast(96)
    val combinedMetrics =
        combinedWidgetMetrics(
            safeWidth,
            safeHeight,
            pixelDensity,
            configuration.glucoseGraphValuePercent,
        )
    val topHeight = combinedMetrics.headerHeightPx
    val graphWidth = combinedMetrics.graphFrame.width()
    val graphHeight = combinedMetrics.graphFrame.height()
    val topLayout = responsiveWidgetLayout(safeWidth / pixelDensity, topHeight / pixelDensity)
    val top =
        renderMinimalGlucoseWidget(
            state,
            palette,
            safeWidth,
            topHeight,
            now,
            thresholds,
            topLayout,
            pixelDensity,
            GlucoseWidgetRenderOptions(
                glucoseScale = configurationScale(configuration.glucoseScalePercent) * 0.78f,
                // The reference ratio was measured from this 2x2 composition. Scaling the glucose
                // typography keeps the accepted reference appearance; the arrow then follows it.
                trendScale = configurationScale(configuration.trendScalePercent),
                leftAligned = true,
                leftInsetDp = 12f,
                verticalOffsetDp = -4f,
                trendStyle = trendStyle,
                glucoseBold = configuration.glucoseBold,
                drawBackground = false,
            ),
        )
    val graph =
        renderWidgetGraph(
            state,
            palette,
            graphWidth,
            graphHeight,
            now,
            thresholds,
            responsiveWidgetLayout(graphWidth / pixelDensity, graphHeight / pixelDensity),
            pixelDensity,
            configuration.copy(outlineEnabled = false),
            clipToWidgetShape = false,
            graphLeftInsetDp = 12f,
            graphHorizontalInsetDp = 5f,
            showScaleAndAge = true,
            forceOpaqueGraphSurface = true,
        )
    val bitmap = createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    clipWidgetCanvas(
        canvas,
        safeWidth,
        safeHeight,
        configuration.cornerRadiusDp.takeIf { it > 0 }?.toFloat() ?: SAMSUNG_WIDGET_RADIUS_FALLBACK_DP,
        pixelDensity,
    )
    canvas.drawColor(if (configuration.backgroundEnabled) configuration.backgroundArgb else AndroidColor.TRANSPARENT)
    canvas.drawBitmap(top, 0f, 0f, null)
    canvas.drawBitmap(graph, combinedMetrics.graphFrame.left.toFloat(), combinedMetrics.graphFrame.top.toFloat(), null)

    val unit =
        when (state?.glucose?.displayUnit) {
            GlucoseUnit.MMOL_L -> "mmol/L"
            GlucoseUnit.MG_DL -> "mg/dL"
            null -> ""
        }
    if (configuration.showGlucoseUnit && unit.isNotBlank() && TherapyDisplayFormatter.isGlucoseDisplayable(state, now)) {
        val delta = state?.glucose?.let { TherapyDisplayFormatter.signedDelta(it.deltaMgDl, it.displayUnit) }.orEmpty()
        val secondary = listOf(delta, unit).filter(String::isNotBlank).joinToString(" ")
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.argb(WidgetColorRole.DELTA_UNIT)
                textAlign = Paint.Align.LEFT
                textSize = (13f * pixelDensity).coerceAtMost(topHeight * 0.19f)
                typeface = widgetTextTypeface(configuration.deltaUnitBold)
            }
        canvas.drawText(secondary, 12f * pixelDensity, topHeight - 6f * pixelDensity, paint)
    }
    drawWidgetOutline(canvas, safeWidth, safeHeight, configuration, pixelDensity)
    return bitmap
}

internal fun combinedWidgetTopHeight(
    heightPx: Int,
    valuePercent: Int,
): Int {
    val safeHeight = heightPx.coerceAtLeast(96)
    return (safeHeight * valuePercent.coerceIn(MIN_COMBINED_WIDGET_VALUE_PERCENT, MAX_COMBINED_WIDGET_VALUE_PERCENT) / 100f)
        .roundToInt()
        .coerceIn(40, safeHeight - 64)
}

internal data class CombinedWidgetMetrics(
    val headerHeightPx: Int,
    val graphFrame: Rect,
)

internal fun combinedWidgetMetrics(
    widthPx: Int,
    heightPx: Int,
    density: Float,
    valuePercent: Int,
): CombinedWidgetMetrics {
    val safeWidth = widthPx.coerceAtLeast(96)
    val safeHeight = heightPx.coerceAtLeast(96)
    val safeDensity = density.coerceIn(1f, 4f)
    val headerHeight = combinedWidgetTopHeight(safeHeight, valuePercent)
    val sectionGap = (2f * safeDensity).roundToInt().coerceAtLeast(1)
    val graphTop =
        (headerHeight + sectionGap - (4f * safeDensity).roundToInt())
            .coerceAtLeast(40)
            .coerceAtMost(safeHeight - 64)
    return CombinedWidgetMetrics(
        headerHeightPx = headerHeight,
        graphFrame = Rect(0, graphTop, safeWidth, safeHeight),
    )
}

internal fun canonicalWidgetSamples(
    state: TherapyDisplayState?,
    now: Long,
    windowMs: Long = 3L * 60L * 60_000L,
): List<GlucoseSample> {
    val currentSample =
        state?.glucose?.let { glucose ->
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
    return CanonicalCgmHistory
        .merge(
            samples = state?.glucoseHistory.orEmpty() + listOfNotNull(currentSample),
            nowEpochMs = now,
            preferredSource = state?.source,
            windowMs = windowMs,
        ).filter { it.measuredAtEpochMs in (now - windowMs)..now }
}
