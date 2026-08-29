package app.aapswear.mobile

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.aapswear.model.DataSourceId
import app.aapswear.model.GlucoseSample
import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TargetState
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.Trend
import app.aapswear.model.AppearanceMode
import app.aapswear.mobile.ui.theme.SugarliciousTheme
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

internal enum class WidgetScaleMode { STATIC, DYNAMIC, LOGARITHMIC }
internal enum class WidgetShapeMode { STANDARD, PILL }

internal enum class ConfigurableWidgetKind(val hasGraph: Boolean) {
    GLUCOSE(false),
    GRAPH(true),
    GLUCOSE_GRAPH(true),
    METABOLIC(false),
    ACTIVITY(false),
}

internal fun configurableWidgetKind(providerClassName: String?): ConfigurableWidgetKind = when {
    providerClassName?.endsWith("GlucoseGraphWidgetReceiver") == true -> ConfigurableWidgetKind.GLUCOSE_GRAPH
    providerClassName?.endsWith("GraphWidgetReceiver") == true -> ConfigurableWidgetKind.GRAPH
    providerClassName?.endsWith("MetabolicWidgetReceiver") == true -> ConfigurableWidgetKind.METABOLIC
    providerClassName?.endsWith("ActivityWidgetReceiver") == true -> ConfigurableWidgetKind.ACTIVITY
    else -> ConfigurableWidgetKind.GLUCOSE
}

internal data class WidgetInstanceConfiguration(
    val graphHours: Int = 3,
    val showTimeAxis: Boolean = false,
    val scaleMode: WidgetScaleMode = WidgetScaleMode.STATIC,
    val backgroundArgb: Int = Color.BLACK,
    val launchPackage: String? = "app.aapswear",
    val backgroundEnabled: Boolean = true,
    val outlineEnabled: Boolean = false,
    val outlineArgb: Int = Color.DKGRAY,
    val cornerRadiusDp: Int = 0,
    val graphCornerRadiusDp: Int = DEFAULT_WIDGET_GRAPH_CORNER_RADIUS_DP,
    val shapeMode: WidgetShapeMode = WidgetShapeMode.STANDARD,
    val glucoseScalePercent: Int = 100,
    val trendScalePercent: Int = 100,
    val colorOverrides: Map<WidgetColorRole, Int> = emptyMap(),
    val lightBackgroundEnabled: Boolean = backgroundEnabled,
    val lightOutlineEnabled: Boolean = outlineEnabled,
    val lightBackgroundArgb: Int = backgroundArgb,
    val lightOutlineArgb: Int = outlineArgb,
    val lightColorOverrides: Map<WidgetColorRole, Int> = colorOverrides,
    val glucoseGraphValuePercent: Int = DEFAULT_COMBINED_WIDGET_VALUE_PERCENT,
    val showGlucoseUnit: Boolean = true,
)

internal fun WidgetInstanceConfiguration.resolvedAppearance(mode: AppearanceMode): WidgetInstanceConfiguration =
    if (mode == AppearanceMode.LIGHT) copy(
        backgroundEnabled = lightBackgroundEnabled,
        outlineEnabled = lightOutlineEnabled,
        backgroundArgb = lightBackgroundArgb,
        outlineArgb = lightOutlineArgb,
        colorOverrides = lightColorOverrides,
    ) else this

internal fun WidgetInstanceConfiguration.withBackground(mode: AppearanceMode, argb: Int): WidgetInstanceConfiguration =
    if (mode == AppearanceMode.LIGHT) copy(lightBackgroundArgb = argb) else copy(backgroundArgb = argb)

internal fun WidgetInstanceConfiguration.withOutline(mode: AppearanceMode, argb: Int): WidgetInstanceConfiguration =
    if (mode == AppearanceMode.LIGHT) copy(lightOutlineArgb = argb) else copy(outlineArgb = argb)

internal fun WidgetInstanceConfiguration.withBackgroundEnabled(mode: AppearanceMode, enabled: Boolean): WidgetInstanceConfiguration =
    if (mode == AppearanceMode.LIGHT) copy(lightBackgroundEnabled = enabled) else copy(backgroundEnabled = enabled)

internal fun WidgetInstanceConfiguration.withOutlineEnabled(mode: AppearanceMode, enabled: Boolean): WidgetInstanceConfiguration =
    if (mode == AppearanceMode.LIGHT) copy(lightOutlineEnabled = enabled) else copy(outlineEnabled = enabled)

internal fun WidgetInstanceConfiguration.withColorOverride(mode: AppearanceMode, role: WidgetColorRole, argb: Int?): WidgetInstanceConfiguration =
    if (mode == AppearanceMode.LIGHT) copy(
        lightColorOverrides = if (argb == null) lightColorOverrides - role else lightColorOverrides + (role to argb),
    ) else copy(colorOverrides = if (argb == null) colorOverrides - role else colorOverrides + (role to argb))

internal object WidgetInstanceConfigurationStore {
    private const val PREFS = "widget_instance_configuration"
    private fun key(id: Int, suffix: String) = "$id.$suffix"

    fun read(context: Context, appWidgetId: Int): WidgetInstanceConfiguration {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacyBackground = prefs.getInt(key(appWidgetId, "background"), Color.BLACK)
        val legacyOutline = prefs.getInt(key(appWidgetId, "outline"), Color.DKGRAY)
        val legacyOverrides = WidgetColorRole.entries.mapNotNull { role ->
            key(appWidgetId, "color.${role.preferenceKey}").takeIf(prefs::contains)?.let { role to prefs.getInt(it, Color.BLACK) }
        }.toMap()
        fun overrides(mode: AppearanceMode) = WidgetColorRole.entries.mapNotNull { role ->
            val modeKey = key(appWidgetId, "${mode.storageKey}.color.${role.preferenceKey}")
            when {
                prefs.contains(modeKey) -> role to prefs.getInt(modeKey, Color.BLACK)
                role in legacyOverrides -> role to legacyOverrides.getValue(role)
                else -> null
            }
        }.toMap()
        return WidgetInstanceConfiguration(
            graphHours = prefs.getInt(key(appWidgetId, "hours"), 3).takeIf { it in listOf(1, 2, 3, 6, 12, 24) } ?: 3,
            showTimeAxis = prefs.getBoolean(key(appWidgetId, "axis"), false),
            scaleMode = runCatching {
                WidgetScaleMode.valueOf(prefs.getString(key(appWidgetId, "scale"), WidgetScaleMode.STATIC.name)!!)
            }.getOrDefault(WidgetScaleMode.STATIC),
            backgroundArgb = prefs.getInt(key(appWidgetId, "dark.background"), legacyBackground),
            backgroundEnabled = prefs.getBoolean(key(appWidgetId, "background_enabled"), true),
            outlineEnabled = prefs.getBoolean(key(appWidgetId, "outline_enabled"), false),
            outlineArgb = prefs.getInt(key(appWidgetId, "dark.outline"), legacyOutline),
            cornerRadiusDp = prefs.getInt(key(appWidgetId, "corner_radius"), 0).coerceIn(0, 32),
            graphCornerRadiusDp = prefs.getInt(
                key(appWidgetId, "graph_corner_radius"),
                DEFAULT_WIDGET_GRAPH_CORNER_RADIUS_DP,
            ).coerceIn(MIN_WIDGET_GRAPH_CORNER_RADIUS_DP, MAX_WIDGET_GRAPH_CORNER_RADIUS_DP),
            shapeMode = runCatching {
                WidgetShapeMode.valueOf(prefs.getString(key(appWidgetId, "shape"), WidgetShapeMode.STANDARD.name)!!)
            }.getOrDefault(WidgetShapeMode.STANDARD),
            glucoseScalePercent = prefs.getInt(key(appWidgetId, "glucose_scale"), 100).coerceIn(70, 130),
            trendScalePercent = prefs.getInt(key(appWidgetId, "trend_scale"), 100).coerceIn(70, 130),
            colorOverrides = overrides(AppearanceMode.DARK),
            lightBackgroundArgb = prefs.getInt(key(appWidgetId, "light.background"), legacyBackground),
            lightOutlineArgb = prefs.getInt(key(appWidgetId, "light.outline"), legacyOutline),
            lightBackgroundEnabled = prefs.getBoolean(
                key(appWidgetId, "light.background_enabled"),
                prefs.getBoolean(key(appWidgetId, "background_enabled"), true),
            ),
            lightOutlineEnabled = prefs.getBoolean(
                key(appWidgetId, "light.outline_enabled"),
                prefs.getBoolean(key(appWidgetId, "outline_enabled"), false),
            ),
            lightColorOverrides = overrides(AppearanceMode.LIGHT),
            glucoseGraphValuePercent = prefs.getInt(
                key(appWidgetId, "glucose_graph_value_percent"),
                DEFAULT_COMBINED_WIDGET_VALUE_PERCENT,
            ).takeIf { it in MIN_COMBINED_WIDGET_VALUE_PERCENT..MAX_COMBINED_WIDGET_VALUE_PERCENT }
                ?: DEFAULT_COMBINED_WIDGET_VALUE_PERCENT,
            showGlucoseUnit = prefs.getBoolean(key(appWidgetId, "show_glucose_unit"), true),
            // One-time compatible fallback for widgets created before per-instance tap targets existed.
            // The configuration activity persists this resolved value for the individual widget.
            launchPackage = prefs.getString(key(appWidgetId, "launch"), null)
                ?: WidgetLaunchTargetStore.legacySelectedPackage(context),
        )
    }

    fun save(context: Context, appWidgetId: Int, value: WidgetInstanceConfiguration) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(key(appWidgetId, "hours"), value.graphHours)
            .putBoolean(key(appWidgetId, "axis"), value.showTimeAxis)
            .putString(key(appWidgetId, "scale"), value.scaleMode.name)
            .putInt(key(appWidgetId, "dark.background"), value.backgroundArgb)
            .putInt(key(appWidgetId, "light.background"), value.lightBackgroundArgb)
            .putBoolean(key(appWidgetId, "background_enabled"), value.backgroundEnabled)
            .putBoolean(key(appWidgetId, "outline_enabled"), value.outlineEnabled)
            .putBoolean(key(appWidgetId, "light.background_enabled"), value.lightBackgroundEnabled)
            .putBoolean(key(appWidgetId, "light.outline_enabled"), value.lightOutlineEnabled)
            .putInt(key(appWidgetId, "dark.outline"), value.outlineArgb)
            .putInt(key(appWidgetId, "light.outline"), value.lightOutlineArgb)
            .putInt(key(appWidgetId, "corner_radius"), value.cornerRadiusDp)
            .putInt(key(appWidgetId, "graph_corner_radius"), value.graphCornerRadiusDp)
            .putString(key(appWidgetId, "shape"), value.shapeMode.name)
            .putInt(key(appWidgetId, "glucose_scale"), value.glucoseScalePercent)
            .putInt(key(appWidgetId, "trend_scale"), value.trendScalePercent)
            .putInt(key(appWidgetId, "glucose_graph_value_percent"), value.glucoseGraphValuePercent)
            .putBoolean(key(appWidgetId, "show_glucose_unit"), value.showGlucoseUnit)
            .apply {
                WidgetColorRole.entries.forEach { role ->
                    val roleKey = key(appWidgetId, "dark.color.${role.preferenceKey}")
                    value.colorOverrides[role]?.let { putInt(roleKey, it) } ?: remove(roleKey)
                    val lightRoleKey = key(appWidgetId, "light.color.${role.preferenceKey}")
                    value.lightColorOverrides[role]?.let { putInt(lightRoleKey, it) } ?: remove(lightRoleKey)
                }
            }
            .apply { if (value.launchPackage == null) remove(key(appWidgetId, "launch")) else putString(key(appWidgetId, "launch"), value.launchPackage) }
            .apply()
    }

    fun delete(context: Context, appWidgetId: Int) {
        val prefix = "$appWidgetId."
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith(prefix) }.forEach(::remove) }.apply()
    }
}

class WidgetConfigurationActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var currentConfiguration: WidgetInstanceConfiguration? = null
    private var currentWidgetKind: ConfigurableWidgetKind = ConfigurableWidgetKind.GLUCOSE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val initial = WidgetInstanceConfigurationStore.read(this, appWidgetId)
        val manager = AppWidgetManager.getInstance(this)
        val widgetKind = configurableWidgetKind(manager.getAppWidgetInfo(appWidgetId)?.provider?.className)
        currentWidgetKind = widgetKind
        val widgetOptions = manager.getAppWidgetOptions(appWidgetId)
        val previewWidthDp = widgetOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220).coerceAtLeast(80)
        val previewHeightDp = widgetOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110).coerceAtLeast(48)
        setContent {
            SugarliciousTheme {
                var value by remember { mutableStateOf(initial) }
                var editBackground by remember { mutableStateOf(false) }
                var editOutline by remember { mutableStateOf(false) }
                var editRole by remember { mutableStateOf<WidgetColorRole?>(null) }
                val dashboardPreferences = remember { getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE) }
                var selectedMode by remember { mutableStateOf(SugarliciousColorStore.activeMode(dashboardPreferences)) }
                val appearance = value.resolvedAppearance(selectedMode)
                currentConfiguration = value
                LaunchedEffect(value) {
                    WidgetInstanceConfigurationStore.save(this@WidgetConfigurationActivity, appWidgetId, value)
                    SugarliciousWidgets.update(this@WidgetConfigurationActivity, appWidgetId, widgetKind)
                }
                Column(
                    Modifier.background(ComposeColor(0xFF000000)).padding(18.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Widget konfigurieren", color = ComposeColor.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    AppearanceModeSelector(selectedMode) { selectedMode = it }
                    WidgetConfigurationPreview(widgetKind, value, selectedMode, previewWidthDp, previewHeightDp)
                    WidgetSettingsSection("Allgemein") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(appearance.backgroundEnabled, { value = value.withBackgroundEnabled(selectedMode, it) })
                            Text("Hintergrund anzeigen", color = ComposeColor.White)
                        }
                        WidgetColorSetting("Hintergrund", appearance.backgroundArgb, { editBackground = true }) {
                            value = value.withBackground(selectedMode, Color.BLACK)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(appearance.outlineEnabled, { value = value.withOutlineEnabled(selectedMode, it) })
                            Text("Kontur anzeigen", color = ComposeColor.White)
                        }
                        if (appearance.outlineEnabled) {
                            WidgetColorSetting("Kontur", appearance.outlineArgb, { editOutline = true }) {
                                value = value.withOutline(selectedMode, Color.DKGRAY)
                            }
                        }
                        WidgetPercentSlider("Eckenradius", if (value.cornerRadiusDp == 0) SAMSUNG_WIDGET_RADIUS_FALLBACK_DP.toInt() else value.cornerRadiusDp, 8..32, "dp") {
                            value = value.copy(cornerRadiusDp = it)
                        }
                        if (widgetKind == ConfigurableWidgetKind.GLUCOSE) {
                            Text("Form", color = ComposeColor.LightGray, fontSize = 11.sp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WidgetShapeMode.entries.forEach { mode ->
                                    Text(
                                        if (mode == WidgetShapeMode.STANDARD) "STANDARD" else "PILLE",
                                        color = if (value.shapeMode == mode) ComposeColor(0xFF6DE892) else ComposeColor.White,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { value = value.copy(shapeMode = mode) }
                                            .background(ComposeColor(0xFF242424), RoundedCornerShape(999.dp))
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (widgetKind == ConfigurableWidgetKind.GLUCOSE || widgetKind == ConfigurableWidgetKind.GLUCOSE_GRAPH) {
                        WidgetSettingsSection("Glukosewert") {
                            WidgetPercentSlider("Größe", value.glucoseScalePercent) { value = value.copy(glucoseScalePercent = it) }
                            listOf(WidgetColorRole.HIGH, WidgetColorRole.IN_RANGE, WidgetColorRole.LOW).forEach { role ->
                                WidgetColorSetting(role.label, appearance.colorOverrides[role] ?: WidgetColorStore.load(this@WidgetConfigurationActivity, selectedMode).argb(role), { editRole = role }) {
                                    value = value.withColorOverride(selectedMode, role, null)
                                }
                            }
                        }
                        WidgetSettingsSection("Trendpfeil") {
                            WidgetPercentSlider("Größe", value.trendScalePercent) { value = value.copy(trendScalePercent = it) }
                            listOf(WidgetColorRole.TREND_HIGH, WidgetColorRole.TREND_IN_RANGE, WidgetColorRole.TREND_LOW).forEach { role ->
                                WidgetColorSetting(role.label, appearance.colorOverrides[role] ?: WidgetColorStore.load(this@WidgetConfigurationActivity, selectedMode).argb(role), { editRole = role }) {
                                    value = value.withColorOverride(selectedMode, role, null)
                                }
                            }
                        }
                    }
                    if (widgetKind == ConfigurableWidgetKind.GLUCOSE_GRAPH) {
                        WidgetSettingsSection("Kombination") {
                            WidgetPercentSlider(
                                "Wertbereich",
                                value.glucoseGraphValuePercent,
                                MIN_COMBINED_WIDGET_VALUE_PERCENT..MAX_COMBINED_WIDGET_VALUE_PERCENT,
                            ) { value = value.copy(glucoseGraphValuePercent = it) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(value.showGlucoseUnit, { value = value.copy(showGlucoseUnit = it) })
                                Text("Maßeinheit anzeigen", color = ComposeColor.White)
                            }
                        }
                    }
                    if (widgetKind.hasGraph) {
                        WidgetSettingsSection("Graph") {
                        WidgetPercentSlider(
                            "Graph-Eckenradius",
                            value.graphCornerRadiusDp,
                            MIN_WIDGET_GRAPH_CORNER_RADIUS_DP..MAX_WIDGET_GRAPH_CORNER_RADIUS_DP,
                            "dp",
                            DEFAULT_WIDGET_GRAPH_CORNER_RADIUS_DP,
                        ) { value = value.copy(graphCornerRadiusDp = it) }
                        Text("Zeitraum", color = ComposeColor.LightGray, fontSize = 11.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1, 2, 3, 6, 12, 24).forEach { hours ->
                                Text(
                                    "${hours}h",
                                    color = if (value.graphHours == hours) ComposeColor(0xFF6DE892) else ComposeColor.White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { value = value.copy(graphHours = hours) }.padding(8.dp),
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(value.showTimeAxis, { value = value.copy(showTimeAxis = it) })
                            Text("Zeitachse anzeigen", color = ComposeColor.White)
                        }
                        Text("Skalierung", color = ComposeColor.LightGray, fontSize = 11.sp)
                        WidgetScaleMode.entries.forEach { mode ->
                            Text(
                                when (mode) {
                                    WidgetScaleMode.STATIC -> "Statisch"
                                    WidgetScaleMode.DYNAMIC -> "Dynamisch"
                                    WidgetScaleMode.LOGARITHMIC -> "Logarithmisch"
                                },
                                color = if (value.scaleMode == mode) ComposeColor(0xFF6DE892) else ComposeColor.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth().clickable { value = value.copy(scaleMode = mode) }.padding(10.dp),
                            )
                        }
                            listOf(
                                WidgetColorRole.GRAPH_BACKGROUND, WidgetColorRole.RANGE_HIGH, WidgetColorRole.RANGE_IN_RANGE,
                                WidgetColorRole.RANGE_LOW, WidgetColorRole.HIGH_LINE, WidgetColorRole.LOW_LINE,
                                WidgetColorRole.DOT_HIGH, WidgetColorRole.DOT_IN_RANGE, WidgetColorRole.DOT_LOW,
                                WidgetColorRole.DOT_OUTLINE, WidgetColorRole.AXIS, WidgetColorRole.AXIS_TICK,
                                WidgetColorRole.DIVIDER,
                            ).forEach { role ->
                                WidgetColorSetting(role.label, appearance.colorOverrides[role] ?: WidgetColorStore.load(this@WidgetConfigurationActivity, selectedMode).argb(role), { editRole = role }) {
                                    value = value.withColorOverride(selectedMode, role, null)
                                }
                            }
                        }
                    }
                    Text("Beim Antippen öffnen", color = ComposeColor.LightGray, fontSize = 11.sp)
                    WidgetLaunchTargetStore.available(this@WidgetConfigurationActivity).forEach { target ->
                        Text(
                            target.label,
                            color = if ((value.launchPackage ?: packageName) == target.packageName) ComposeColor(0xFF6DE892) else ComposeColor.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().clickable { value = value.copy(launchPackage = target.packageName) }.padding(10.dp),
                        )
                    }
                }
                if (editBackground) {
                    ColorEditorDialog(
                        role = null,
                        label = "Widget-Hintergrund",
                        initialArgb = appearance.backgroundArgb,
                        onDismiss = { editBackground = false },
                        onChange = { value = value.withBackground(selectedMode, it) },
                    )
                }
                if (editOutline) {
                    ColorEditorDialog(
                        role = null, label = "Widget-Kontur", initialArgb = appearance.outlineArgb,
                        onDismiss = { editOutline = false },
                        onChange = { value = value.withOutline(selectedMode, it) },
                    )
                }
                editRole?.let { role ->
                    val palette = WidgetColorStore.load(this@WidgetConfigurationActivity, selectedMode)
                    ColorEditorDialog(
                        role = null,
                        label = role.label,
                        initialArgb = appearance.colorOverrides[role] ?: palette.argb(role),
                        onDismiss = { editRole = null },
                        onChange = { color -> value = value.withColorOverride(selectedMode, role, color) },
                    )
                }
            }
        }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
    }

    override fun onStop() {
        currentConfiguration?.let { configuration ->
            WidgetInstanceConfigurationStore.save(this, appWidgetId, configuration)
            lifecycleScope.launch { SugarliciousWidgets.update(this@WidgetConfigurationActivity, appWidgetId, currentWidgetKind) }
        }
        super.onStop()
    }
}

@androidx.compose.runtime.Composable
private fun WidgetConfigurationPreview(kind: ConfigurableWidgetKind, configuration: WidgetInstanceConfiguration, mode: AppearanceMode, widthDp: Int, heightDp: Int) {
    val context = LocalContext.current
    val themedConfiguration = configuration.resolvedAppearance(mode)
    val now = System.currentTimeMillis()
    val state = remember(now) { widgetPreviewState(now) }
    val palette = remember(context, themedConfiguration, mode) {
        WidgetColorStore.load(context, mode).with(themedConfiguration.colorOverrides)
            .with(WidgetColorRole.BACKGROUND, if (themedConfiguration.backgroundEnabled) themedConfiguration.backgroundArgb else Color.TRANSPARENT)
    }
    val previewWidth = (widthDp * 2).coerceIn(160, 900)
    val previewHeight = (heightDp * 2).coerceIn(96, 900)
    val previewLayout = responsiveWidgetLayout(widthDp.toFloat(), heightDp.toFloat())
    val previewRadius = resolveWidgetCornerRadiusDp(
        themedConfiguration, heightDp.toFloat(), SAMSUNG_WIDGET_RADIUS_FALLBACK_DP,
        pillAllowed = kind == ConfigurableWidgetKind.GLUCOSE,
    )
    val renderConfiguration = themedConfiguration.copy(cornerRadiusDp = previewRadius.toInt())
    val bitmap = remember(kind, renderConfiguration, state, palette) {
        when (kind) {
            ConfigurableWidgetKind.GRAPH -> renderWidgetGraph(state, palette, previewWidth, previewHeight, now, app.aapswear.model.CgmThresholds.DEFAULT, previewLayout, 2f, renderConfiguration)
            ConfigurableWidgetKind.GLUCOSE_GRAPH -> renderGlucoseGraphWidget(
                state, palette, previewWidth, previewHeight, now, app.aapswear.model.CgmThresholds.DEFAULT,
                previewLayout, 2f, renderConfiguration,
            )
            else -> renderMinimalGlucoseWidget(
                state, palette, previewWidth, previewHeight, now, app.aapswear.model.CgmThresholds.DEFAULT, previewLayout, 2f,
                GlucoseWidgetRenderOptions(
                    glucoseScale = configuration.glucoseScalePercent / 100f,
                    trendScale = configuration.trendScalePercent / 100f,
                    outlineEnabled = configuration.outlineEnabled,
                    outlineArgb = configuration.outlineArgb,
                    cornerRadiusDp = previewRadius,
                ),
            )
        }
    }
    AndroidView(
        factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { it.setImageBitmap(bitmap) },
        modifier = Modifier.fillMaxWidth().aspectRatio(widthDp.toFloat() / heightDp.coerceAtLeast(1)),
    )
}

@androidx.compose.runtime.Composable
private fun WidgetSettingsSection(title: String, content: @androidx.compose.runtime.Composable () -> Unit) {
    Surface(color = ComposeColor(0xFF1B1B1B), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = ComposeColor(0xFF6DE892), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            content()
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetColorSetting(label: String, argb: Int, onEdit: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = ComposeColor.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Box(
            Modifier.fillMaxWidth().height(44.dp)
                .background(ComposeColor(argb), RoundedCornerShape(14.dp))
                .border(1.dp, ComposeColor(0xFF666666), RoundedCornerShape(14.dp))
                .clickable(onClick = onEdit),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(toHex(argb), color = ComposeColor.LightGray, fontSize = 10.sp)
            Text("ZURÜCKSETZEN", color = ComposeColor(0xFF6DE892), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onReset).padding(vertical = 4.dp))
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetPercentSlider(
    label: String,
    value: Int,
    range: IntRange = 70..130,
    suffix: String = "%",
    resetValue: Int? = null,
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("$label · $value $suffix", color = ComposeColor.White, fontWeight = FontWeight.Bold)
        if (resetValue != null && value != resetValue) {
            Text(
                "ZURÜCKSETZEN",
                color = ComposeColor(0xFF6DE892),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onChange(resetValue) }.padding(vertical = 4.dp),
            )
        }
    }
    Slider(value = value.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = range.first.toFloat()..range.last.toFloat())
}

private fun widgetPreviewState(now: Long): TherapyDisplayState = TherapyDisplayState(
    source = DataSourceId.ANDROID_APS,
    receivedAtEpochMs = now,
    glucose = GlucoseState(125.0, GlucoseUnit.MG_DL, Trend.FLAT, now),
    glucoseHistory = (0..36).map { index ->
        val minutesBack = (36 - index) * 5L
        GlucoseSample(
            valueMgDl = 108.0 + kotlin.math.sin(index / 5.0) * 18.0 + index * 0.45,
            measuredAtEpochMs = now - minutesBack * 60_000L,
            source = DataSourceId.ANDROID_APS,
        )
    },
    target = TargetState(lowMgDl = 80.0, highMgDl = 160.0),
)
