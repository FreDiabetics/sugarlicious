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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal enum class WidgetScaleMode { STATIC, DYNAMIC, LOGARITHMIC }

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
            launchPackage = prefs.getString(key(appWidgetId, "launch"), null),
        )
    }

    fun save(context: Context, appWidgetId: Int, value: WidgetInstanceConfiguration) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(key(appWidgetId, "hours"), value.graphHours)
            .putBoolean(key(appWidgetId, "axis"), value.showTimeAxis)
            .putString(key(appWidgetId, "scale"), value.scaleMode.name)
            .putInt(key(appWidgetId, "background"), value.backgroundArgb)
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
        val widgetKind = configurableWidgetKind(AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className)
        setContent {
            SugarliciousTheme {
                var value by remember { mutableStateOf(initial) }
                var editColor by remember { mutableStateOf(false) }
                Column(
                    Modifier.background(ComposeColor(0xFF000000)).padding(18.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Widget konfigurieren", color = ComposeColor.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    WidgetConfigurationPreview(widgetKind, value)
                    if (widgetKind.hasGraph) {
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
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { editColor = true }.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Widget-Hintergrund", color = ComposeColor.White, fontWeight = FontWeight.Bold)
                        Text(toHex(value.backgroundArgb), color = ComposeColor(value.backgroundArgb))
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
                    Button(onClick = {
                        WidgetInstanceConfigurationStore.save(this@WidgetConfigurationActivity, appWidgetId, value)
                        CoroutineScope(Dispatchers.IO).launch { SugarliciousWidgets.update(applicationContext) }
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                        finish()
                    }, modifier = Modifier.fillMaxWidth()) { Text("SPEICHERN") }
                }
                if (editColor) {
                    ColorEditorDialog(
                        role = null,
                        label = "Widget-Hintergrund",
                        initialArgb = value.backgroundArgb,
                        onDismiss = { editColor = false },
                        onSave = { value = value.copy(backgroundArgb = it); editColor = false },
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetConfigurationPreview(kind: ConfigurableWidgetKind, configuration: WidgetInstanceConfiguration) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val state = remember(now) { widgetPreviewState(now) }
    val palette = remember(context, configuration.backgroundArgb) {
        WidgetColorStore.load(context).with(WidgetColorRole.BACKGROUND, configuration.backgroundArgb)
            .with(WidgetColorRole.GRAPH_BACKGROUND, configuration.backgroundArgb)
    }
    val bitmap = remember(kind, configuration, state, palette) {
        when (kind) {
            ConfigurableWidgetKind.GRAPH -> renderWidgetGraph(state, palette, 720, 300, now, app.aapswear.model.CgmThresholds.DEFAULT, configuration = configuration)
            ConfigurableWidgetKind.GLUCOSE_GRAPH -> renderGlucoseGraphWidget(
                state, palette, 560, 560, now, app.aapswear.model.CgmThresholds.DEFAULT,
                configuration = configuration,
            )
            else -> renderMinimalGlucoseWidget(state, palette, 560, 240, now, app.aapswear.model.CgmThresholds.DEFAULT)
        }
    }
    AndroidView(
        factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { it.setImageBitmap(bitmap) },
        modifier = Modifier.fillMaxWidth().height(if (kind == ConfigurableWidgetKind.GLUCOSE_GRAPH) 220.dp else 140.dp),
    )
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
