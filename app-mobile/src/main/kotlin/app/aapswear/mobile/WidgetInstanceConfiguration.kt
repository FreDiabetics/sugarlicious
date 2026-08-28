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
import app.aapswear.mobile.ui.theme.SugarliciousTheme
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
    val launchPackage: String? = null,
    val backgroundEnabled: Boolean = true,
    val outlineEnabled: Boolean = false,
    val outlineArgb: Int = Color.DKGRAY,
    val cornerRadiusDp: Int = 0,
    val shapeMode: WidgetShapeMode = WidgetShapeMode.STANDARD,
    val glucoseScalePercent: Int = 100,
    val trendScalePercent: Int = 100,
    val colorOverrides: Map<WidgetColorRole, Int> = emptyMap(),
)

internal object WidgetInstanceConfigurationStore {
    private const val PREFS = "widget_instance_configuration"
    private fun key(id: Int, suffix: String) = "$id.$suffix"

    fun read(context: Context, appWidgetId: Int): WidgetInstanceConfiguration {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WidgetInstanceConfiguration(
            graphHours = prefs.getInt(key(appWidgetId, "hours"), 3).takeIf { it in listOf(1, 2, 3, 6, 12, 24) } ?: 3,
            showTimeAxis = prefs.getBoolean(key(appWidgetId, "axis"), false),
            scaleMode = runCatching {
                WidgetScaleMode.valueOf(prefs.getString(key(appWidgetId, "scale"), WidgetScaleMode.STATIC.name)!!)
            }.getOrDefault(WidgetScaleMode.STATIC),
            backgroundArgb = prefs.getInt(key(appWidgetId, "background"), Color.BLACK),
            backgroundEnabled = prefs.getBoolean(key(appWidgetId, "background_enabled"), true),
            outlineEnabled = prefs.getBoolean(key(appWidgetId, "outline_enabled"), false),
            outlineArgb = prefs.getInt(key(appWidgetId, "outline"), Color.DKGRAY),
            cornerRadiusDp = prefs.getInt(key(appWidgetId, "corner_radius"), 0).coerceIn(0, 32),
            shapeMode = runCatching {
                WidgetShapeMode.valueOf(prefs.getString(key(appWidgetId, "shape"), WidgetShapeMode.STANDARD.name)!!)
            }.getOrDefault(WidgetShapeMode.STANDARD),
            glucoseScalePercent = prefs.getInt(key(appWidgetId, "glucose_scale"), 100).coerceIn(70, 130),
            trendScalePercent = prefs.getInt(key(appWidgetId, "trend_scale"), 100).coerceIn(70, 130),
            colorOverrides = WidgetColorRole.entries.mapNotNull { role ->
                key(appWidgetId, "color.${role.preferenceKey}").takeIf(prefs::contains)?.let { role to prefs.getInt(it, Color.BLACK) }
            }.toMap(),
            launchPackage = prefs.getString(key(appWidgetId, "launch"), null),
        )
    }

    fun save(context: Context, appWidgetId: Int, value: WidgetInstanceConfiguration) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(key(appWidgetId, "hours"), value.graphHours)
            .putBoolean(key(appWidgetId, "axis"), value.showTimeAxis)
            .putString(key(appWidgetId, "scale"), value.scaleMode.name)
            .putInt(key(appWidgetId, "background"), value.backgroundArgb)
            .putBoolean(key(appWidgetId, "background_enabled"), value.backgroundEnabled)
            .putBoolean(key(appWidgetId, "outline_enabled"), value.outlineEnabled)
            .putInt(key(appWidgetId, "outline"), value.outlineArgb)
            .putInt(key(appWidgetId, "corner_radius"), value.cornerRadiusDp)
            .putString(key(appWidgetId, "shape"), value.shapeMode.name)
            .putInt(key(appWidgetId, "glucose_scale"), value.glucoseScalePercent)
            .putInt(key(appWidgetId, "trend_scale"), value.trendScalePercent)
            .apply {
                WidgetColorRole.entries.forEach { role ->
                    val roleKey = key(appWidgetId, "color.${role.preferenceKey}")
                    value.colorOverrides[role]?.let { putInt(roleKey, it) } ?: remove(roleKey)
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
                    WidgetConfigurationPreview(widgetKind, value, previewWidthDp, previewHeightDp)
                    WidgetSettingsSection("Allgemein") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(value.backgroundEnabled, { value = value.copy(backgroundEnabled = it) })
                            Text("Hintergrund anzeigen", color = ComposeColor.White)
                        }
                        WidgetColorSetting("Hintergrund", value.backgroundArgb, { editBackground = true }) {
                            value = value.copy(backgroundArgb = Color.BLACK)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(value.outlineEnabled, { value = value.copy(outlineEnabled = it) })
                            Text("Kontur anzeigen", color = ComposeColor.White)
                        }
                        if (value.outlineEnabled) {
                            WidgetColorSetting("Kontur", value.outlineArgb, { editOutline = true }) {
                                value = value.copy(outlineArgb = Color.DKGRAY)
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
                                WidgetColorSetting(role.label, value.colorOverrides[role] ?: WidgetColorStore.load(this@WidgetConfigurationActivity).argb(role), { editRole = role }) {
                                    value = value.copy(colorOverrides = value.colorOverrides - role)
                                }
                            }
                        }
                        WidgetSettingsSection("Trendpfeil") {
                            WidgetPercentSlider("Größe", value.trendScalePercent) { value = value.copy(trendScalePercent = it) }
                            listOf(WidgetColorRole.TREND_HIGH, WidgetColorRole.TREND_IN_RANGE, WidgetColorRole.TREND_LOW).forEach { role ->
                                WidgetColorSetting(role.label, value.colorOverrides[role] ?: WidgetColorStore.load(this@WidgetConfigurationActivity).argb(role), { editRole = role }) {
                                    value = value.copy(colorOverrides = value.colorOverrides - role)
                                }
                            }
                        }
                    }
                    if (widgetKind.hasGraph) {
                        WidgetSettingsSection("Graph") {
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
                                WidgetColorSetting(role.label, value.colorOverrides[role] ?: WidgetColorStore.load(this@WidgetConfigurationActivity).argb(role), { editRole = role }) {
                                    value = value.copy(colorOverrides = value.colorOverrides - role)
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
                        initialArgb = value.backgroundArgb,
                        onDismiss = { editBackground = false },
                        onSave = { value = value.copy(backgroundArgb = it); editBackground = false },
                    )
                }
                if (editOutline) {
                    ColorEditorDialog(
                        role = null, label = "Widget-Kontur", initialArgb = value.outlineArgb,
                        onDismiss = { editOutline = false },
                        onSave = { value = value.copy(outlineArgb = it); editOutline = false },
                    )
                }
                editRole?.let { role ->
                    val palette = WidgetColorStore.load(this@WidgetConfigurationActivity)
                    ColorEditorDialog(
                        role = null,
                        label = role.label,
                        initialArgb = value.colorOverrides[role] ?: palette.argb(role),
                        onDismiss = { editRole = null },
                        onSave = { color -> value = value.copy(colorOverrides = value.colorOverrides + (role to color)); editRole = null },
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
private fun WidgetConfigurationPreview(kind: ConfigurableWidgetKind, configuration: WidgetInstanceConfiguration, widthDp: Int, heightDp: Int) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val state = remember(now) { widgetPreviewState(now) }
    val palette = remember(context, configuration) {
        WidgetColorStore.load(context).with(configuration.colorOverrides)
            .with(WidgetColorRole.BACKGROUND, if (configuration.backgroundEnabled) configuration.backgroundArgb else Color.TRANSPARENT)
    }
    val previewWidth = (widthDp * 2).coerceIn(160, 900)
    val previewHeight = (heightDp * 2).coerceIn(96, 900)
    val previewLayout = responsiveWidgetLayout(widthDp.toFloat(), heightDp.toFloat())
    val previewRadius = resolveWidgetCornerRadiusDp(
        configuration, heightDp.toFloat(), SAMSUNG_WIDGET_RADIUS_FALLBACK_DP,
        pillAllowed = kind == ConfigurableWidgetKind.GLUCOSE,
    )
    val renderConfiguration = configuration.copy(cornerRadiusDp = previewRadius.toInt())
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
    onChange: (Int) -> Unit,
) {
    Text("$label · $value $suffix", color = ComposeColor.White, fontWeight = FontWeight.Bold)
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
