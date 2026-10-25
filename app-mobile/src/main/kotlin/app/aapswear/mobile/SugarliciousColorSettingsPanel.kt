package app.aapswear.mobile

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import app.aapswear.mobile.ui.theme.SugarliciousColorGroup
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.mobile.ui.theme.SugarliciousColors
import app.aapswear.model.AppearanceControlContract
import app.aapswear.model.AppearanceMode
import app.aapswear.model.ArgbColor
import app.aapswear.model.TrendArrowStyle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

@Composable
internal fun SugarliciousColorSettingsPanel(
    showCgmGraph: Boolean = true,
    showMetabolicGraph: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val preferences =
        remember {
            context.getSharedPreferences(
                "dashboard_ui",
                Context.MODE_PRIVATE,
            )
        }

    var selectedMode by remember { mutableStateOf(SugarliciousColorStore.activeMode(preferences)) }
    var palette by remember { mutableStateOf(SugarliciousColorStore.load(preferences, selectedMode)) }
    var editingRole by remember {
        mutableStateOf<SugarliciousColorRole?>(null)
    }
    var editingTrendColor by remember { mutableStateOf<String?>(null) }
    var trendStyle by remember(selectedMode) { mutableStateOf(MobileTrendArrowAppearance.load(preferences, selectedMode)) }
    var watchSyncStatus by remember { mutableStateOf<String?>(null) }
    var cgmDotRadiusDp by remember(showCgmGraph, selectedMode) {
        mutableFloatStateOf(
            preferences
                .getFloat(
                    graphAppearanceKey(selectedMode, "dotRadiusDp"),
                    preferences.getFloat("cgm.dotRadiusDp", 2.4f),
                ).coerceIn(1.5f, 6.0f),
        )
    }
    var cgmHistoricalDotOutlineEnabled by remember(showCgmGraph, selectedMode) {
        mutableStateOf(
            preferences.getBoolean(
                graphAppearanceKey(selectedMode, "historicalDotOutlineEnabled"),
                preferences.getBoolean("cgm.dotOutlineEnabled", true),
            ),
        )
    }
    var cgmHistoricalDotOutlineWidthDp by remember(showCgmGraph, selectedMode) {
        mutableFloatStateOf(
            preferences
                .getFloat(
                    graphAppearanceKey(selectedMode, "historicalDotOutlineWidthDp"),
                    preferences.getFloat("cgm.dotOutlineWidthDp", 0.95f),
                ).coerceIn(0.25f, 3.0f),
        )
    }
    var cgmCurrentDotOutlineWidthDp by remember(showCgmGraph, selectedMode) {
        mutableFloatStateOf(
            preferences
                .getFloat(
                    graphAppearanceKey(selectedMode, "currentDotOutlineWidthDp"),
                    preferences.getFloat("cgm.dotOutlineWidthDp", 0.95f),
                ).coerceIn(0.25f, 3.0f),
        )
    }
    var predictionDotRadiusDp by remember(showCgmGraph, selectedMode) {
        mutableFloatStateOf(readMobilePredictionDotRadius(preferences, selectedMode))
    }
    var predictionDotOutlineWidthDp by remember(showCgmGraph, selectedMode) {
        mutableFloatStateOf(readMobilePredictionDotOutlineWidth(preferences, selectedMode))
    }

    fun reload() {
        palette = SugarliciousColorStore.load(preferences, selectedMode)
        trendStyle = MobileTrendArrowAppearance.load(preferences, selectedMode)
        if (selectedMode == SugarliciousColorStore.activeMode(preferences)) {
            SugarliciousColors.apply(palette)
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    OneUiWidgetStyle.card,
                    RoundedCornerShape(28.dp),
                ).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "FARBEN",
                    color = OneUiWidgetStyle.secondary,
                    style = oneUiMenuTypography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                )
                Text(
                    text = "Darstellung & Farben",
                    color = OneUiWidgetStyle.primary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        AppearanceModeSelector(selectedMode) { mode ->
            selectedMode = mode
            palette = SugarliciousColorStore.load(preferences, mode)
        }

        AppearanceThemePreview(palette, trendStyle, DashboardUiPreferences.read(preferences))

        Text("TRENDPFEIL", color = OneUiWidgetStyle.secondary, style = oneUiMenuTypography.bodyMedium, fontWeight = FontWeight.Bold)
        ColorSettingRow(null, "Füllfarbe", trendStyle.fillColor, { editingTrendColor = "fill" })
        GraphSettingSwitch("Kontur", "Separate Trendpfeil-Kontur", trendStyle.outlineEnabled) {
            trendStyle = trendStyle.copy(outlineEnabled = it)
            MobileTrendArrowAppearance.save(preferences, selectedMode, trendStyle)
        }
        if (trendStyle.outlineEnabled) {
            ColorSettingRow(null, "Konturfarbe", trendStyle.outlineColor, { editingTrendColor = "outline" })
            SugarliciousSettingSlider(
                "Konturdicke",
                "Trendpfeil-Kontur",
                trendStyle.outlineThicknessDp,
                0.25f..4f,
                "${String.format(
                    locale,
                    "%.2f",
                    trendStyle.outlineThicknessDp,
                )} dp",
                {
                    trendStyle = trendStyle.copy(outlineThicknessDp = it)
                },
                { MobileTrendArrowAppearance.save(preferences, selectedMode, trendStyle) },
            )
        }
        SugarliciousSettingSlider("Deckkraft", "Trendpfeil", trendStyle.alpha * 100f, 0f..100f, "${(trendStyle.alpha * 100).roundToInt()} %", {
            trendStyle = trendStyle.copy(alpha = it / 100f)
        }, { MobileTrendArrowAppearance.save(preferences, selectedMode, trendStyle) })

        Button(
            onClick = {
                watchSyncStatus = "Wird gesendet …"
                scope.launch {
                    watchSyncStatus =
                        runCatching { publishWatchColors(context.applicationContext) }
                            .fold(
                                onSuccess = { "Graphfarben wurden an die Watch gesendet." },
                                onFailure = { "Watch-Farben konnten nicht gesendet werden." },
                            )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = OneUiWidgetStyle.action,
                    contentColor = OneUiWidgetStyle.primary,
                ),
        ) {
            Text("AN WATCH SENDEN", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        watchSyncStatus?.let { status ->
            Text(status, color = OneUiWidgetStyle.secondary, style = oneUiMenuTypography.bodyMedium)
        }

        if (showCgmGraph) {
            Text(
                text = "CGM-PUNKTE",
                color = OneUiWidgetStyle.secondary,
                style = oneUiMenuTypography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )

            SugarliciousSettingSlider(
                title = "Punktgröße",
                description = "Größe der CGM-Dots im Glukosegraph",
                value = cgmDotRadiusDp,
                valueRange = 1.5f..6.0f,
                valueText = "${String.format(locale, "%.1f", cgmDotRadiusDp)} dp",
                onValueChange = { cgmDotRadiusDp = it },
                onValueChangeFinished = {
                    preferences.edit { putFloat(graphAppearanceKey(selectedMode, "dotRadiusDp"), cgmDotRadiusDp) }
                },
            )

            GraphSettingSwitch(
                title = "Historische Kontur",
                description = "Kontur um historische CGM-Dots anzeigen",
                checked = cgmHistoricalDotOutlineEnabled,
                onCheckedChange = { enabled ->
                    cgmHistoricalDotOutlineEnabled = enabled
                    preferences.edit { putBoolean(graphAppearanceKey(selectedMode, "historicalDotOutlineEnabled"), enabled) }
                },
            )

            if (cgmHistoricalDotOutlineEnabled) {
                SugarliciousSettingSlider(
                    title = "Historische Konturdicke",
                    description = "Dicke der Kontur historischer Dots",
                    value = cgmHistoricalDotOutlineWidthDp,
                    valueRange = 0.25f..3.0f,
                    valueText = "${String.format(locale, "%.2f", cgmHistoricalDotOutlineWidthDp)} dp",
                    onValueChange = { cgmHistoricalDotOutlineWidthDp = AppearanceControlContract.quantizeDotOutlineWidth(it) },
                    onValueChangeFinished = {
                        preferences.edit { putFloat(graphAppearanceKey(selectedMode, "historicalDotOutlineWidthDp"), cgmHistoricalDotOutlineWidthDp) }
                    },
                )
            }

            SugarliciousSettingSlider(
                title = "Aktuelle Konturdicke",
                description = "Der aktuelle echte CGM-Dot besitzt immer eine Kontur",
                value = cgmCurrentDotOutlineWidthDp,
                valueRange = 0.25f..3.0f,
                valueText = "${String.format(locale, "%.2f", cgmCurrentDotOutlineWidthDp)} dp",
                onValueChange = { cgmCurrentDotOutlineWidthDp = AppearanceControlContract.quantizeDotOutlineWidth(it) },
                onValueChangeFinished = {
                    preferences.edit { putFloat(graphAppearanceKey(selectedMode, "currentDotOutlineWidthDp"), cgmCurrentDotOutlineWidthDp) }
                },
            )

            SugarliciousSettingSlider(
                title = "Prediction-Punktgröße",
                description = "Größe der Vorhersagepunkte im CGM-Graph",
                value = predictionDotRadiusDp,
                valueRange = 1.0f..6.0f,
                valueText = "${String.format(locale, "%.1f", predictionDotRadiusDp)} dp",
                onValueChange = { predictionDotRadiusDp = it },
                onValueChangeFinished = {
                    preferences.edit {
                        putFloat(
                            graphAppearanceKey(selectedMode, "prediction.dotRadiusDp"),
                            predictionDotRadiusDp,
                        )
                    }
                },
            )

            SugarliciousSettingSlider(
                title = "Prediction-Konturdicke",
                description = "0,00 dp blendet die Kontur aus",
                value = predictionDotOutlineWidthDp,
                valueRange = 0.0f..3.0f,
                valueText = "${String.format(locale, "%.2f", predictionDotOutlineWidthDp)} dp",
                onValueChange = { predictionDotOutlineWidthDp = it },
                onValueChangeFinished = {
                    preferences.edit {
                        putFloat(
                            graphAppearanceKey(selectedMode, "prediction.dotOutlineWidthDp"),
                            predictionDotOutlineWidthDp,
                        )
                    }
                },
            )
        }

        SugarliciousColorGroup.entries.forEach { group ->
            val roles =
                SugarliciousColorRole.entries.filter {
                    it.group == group &&
                        colorRoleVisible(
                            role = it,
                            showCgmGraph = showCgmGraph,
                            showMetabolicGraph = showMetabolicGraph,
                            isLight = palette.isLight,
                        )
                }
            if (roles.isEmpty()) return@forEach
            Text(
                text = group.label,
                color = OneUiWidgetStyle.secondary,
                style = oneUiMenuTypography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )

            roles.forEach { role ->
                val argb = palette.argb(role)
                ColorSettingRow(
                    role = role,
                    argb = argb,
                    onEdit = { editingRole = role },
                )
            }
        }
    }

    editingRole?.let { role ->
        ColorEditorDialog(
            role = role,
            label = role.label,
            initialArgb = palette.argb(role),
            onReset = {
                SugarliciousColorStore.reset(preferences, selectedMode, role)
                reload()
                palette.argb(role)
            },
            actions =
                listOf(
                    ColorPickerAction("Alle Farben zurücksetzen") {
                        SugarliciousColorStore.resetAll(preferences)
                        reload()
                        palette.argb(role)
                    },
                ),
            onDismiss = { editingRole = null },
            onChange = { argb ->
                SugarliciousColorStore.save(preferences, selectedMode, role, argb)
                reload()
            },
        )
    }
    editingTrendColor?.let { field ->
        ColorEditorDialog(
            role = null,
            label = if (field == "fill") "Trendpfeil · Füllfarbe" else "Trendpfeil · Konturfarbe",
            initialArgb = if (field == "fill") trendStyle.fillColor else trendStyle.outlineColor,
            resetArgb = if (field == "fill") palette.argb(SugarliciousColorRole.GLUCOSE_IN_RANGE) else TrendArrowStyle.defaults(selectedMode, trendStyle.fillColor).outlineColor,
            onDismiss = { editingTrendColor = null },
            onChange = { color ->
                trendStyle = if (field == "fill") trendStyle.copy(fillColor = color) else trendStyle.copy(outlineColor = color)
                MobileTrendArrowAppearance.save(preferences, selectedMode, trendStyle)
            },
        )
    }
}

@Composable
internal fun WidgetColorSettingsPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var editingRole by remember { mutableStateOf<WidgetColorRole?>(null) }
    val preferences = remember { context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE) }
    var selectedMode by remember { mutableStateOf(SugarliciousColorStore.activeMode(preferences)) }
    var samsungCompact by remember { mutableStateOf(WidgetLayoutSizeStore.isSamsungCompact(context)) }
    val palette = remember(revision, selectedMode) { WidgetColorStore.load(context, selectedMode) }

    fun refreshWidgets() {
        revision++
        scope.launch { SugarliciousWidgets.update(context.applicationContext) }
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(OneUiWidgetStyle.card, RoundedCornerShape(28.dp))
                .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("WIDGET-STANDARDFARBEN", color = OneUiWidgetStyle.secondary, style = oneUiMenuTypography.bodyMedium, fontWeight = FontWeight.Bold)
        Text("Farben", color = OneUiWidgetStyle.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            WidgetSettingsToggle(
                checked = samsungCompact,
                onCheckedChange = { enabled ->
                    samsungCompact = enabled
                    WidgetLayoutSizeStore.setSamsungCompact(context, enabled)
                    refreshWidgets()
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Samsung-kompakte Widgetgröße", color = OneUiWidgetStyle.primary, style = oneUiMenuTypography.bodyLarge)
                Text(
                    "Gleicher Innenabstand wie bei Samsung-Widgets; gilt für alle Mobile-Widgets.",
                    color = OneUiWidgetStyle.secondary,
                    style = oneUiMenuTypography.bodyMedium,
                )
            }
        }
        AppearanceModeSelector(selectedMode) { selectedMode = it }
        Text(
            "Diese App-Defaults gelten nur für Widgets ohne eigenen Override. Zeitraum, Form und Tap-Ziel stellst du direkt pro Widget ein.",
            color = OneUiWidgetStyle.secondary,
            style = oneUiMenuTypography.bodyMedium,
        )
        WidgetColorRole.entries.filterNot { it in setOf(WidgetColorRole.DELTA_SYMBOL, WidgetColorRole.AGE_SYMBOL, WidgetColorRole.TIR_SYMBOL) }.forEach { role ->
            WidgetColorSettingRow(
                role = role,
                argb = palette.argb(role),
                onEdit = { editingRole = role },
            )
        }
    }

    editingRole?.let { role ->
        ColorEditorDialog(
            role = null,
            label = "Widget: ${role.label}",
            initialArgb = palette.argb(role),
            onReset = {
                WidgetColorStore.reset(context, selectedMode, role)
                refreshWidgets()
                WidgetColorStore.load(context, selectedMode).argb(role)
            },
            actions =
                listOf(
                    ColorPickerAction("Aus Mobile-Graph übernehmen") {
                        WidgetColorStore.copyFromMobileGraph(context, selectedMode)
                        refreshWidgets()
                        WidgetColorStore.load(context, selectedMode).argb(role)
                    },
                    ColorPickerAction("Alle Widget-Farben zurücksetzen") {
                        WidgetColorStore.resetAll(context)
                        refreshWidgets()
                        WidgetColorStore.load(context, selectedMode).argb(role)
                    },
                ),
            onDismiss = { editingRole = null },
            onChange = { argb ->
                WidgetColorStore.save(context, selectedMode, role, argb)
                refreshWidgets()
            },
        )
    }
}

@Composable
private fun WidgetColorSettingRow(
    role: WidgetColorRole,
    argb: Int,
    onEdit: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(OneUiWidgetStyle.card, RoundedCornerShape(28.dp))
                .clickable(onClick = onEdit)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(28.dp)
                    .background(Color(argb), CircleShape)
                    .border(1.dp, SugarliciousColors.Border, CircleShape),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(role.label, color = OneUiWidgetStyle.primary, fontSize = 18.sp, fontWeight = FontWeight.Normal)
        }
    }
}

@Composable
internal fun AppearanceModeSelector(
    selected: AppearanceMode,
    onSelected: (AppearanceMode) -> Unit,
) {
    OneUiSettingsModeSelector(if (selected == AppearanceMode.LIGHT) DashboardThemeMode.LIGHT else DashboardThemeMode.DARK, showSystemChoice = false) {
        onSelected(if (it == DashboardThemeMode.LIGHT) AppearanceMode.LIGHT else AppearanceMode.DARK)
    }
}

@Composable
private fun AppearanceThemePreview(palette: app.aapswear.mobile.ui.theme.SugarliciousPalette, trendStyle: TrendArrowStyle, preferences: DashboardUiPreferences) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(palette.argb(SugarliciousColorRole.SURFACE)), RoundedCornerShape(28.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlucoseValueAndTrend(
            "123",
            Color(palette.argb(SugarliciousColorRole.GLUCOSE_IN_RANGE)),
            app.aapswear.model.Trend.FLAT,
            app.aapswear.model.GlucoseVisualSpec
                .twoByTwoWidgetReference()
                .scaled(preferences.glucoseScalePercent, preferences.trendScalePercent),
            trendStyle,
        )
        Spacer(Modifier.height(3.dp))
        Text("−2 mg/dL · 5m", color = Color(palette.argb(SugarliciousColorRole.TEXT_SECONDARY)), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GraphSettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(OneUiWidgetStyle.card, RoundedCornerShape(28.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = OneUiWidgetStyle.primary, fontSize = 18.sp, fontWeight = FontWeight.Normal)
            Text(description, color = OneUiWidgetStyle.secondary, style = oneUiMenuTypography.bodyMedium)
        }
        WidgetSettingsToggle(checked, onCheckedChange, label = title)
    }
}

@Composable
internal fun SugarliciousSettingSlider(
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    var editingValue by remember { mutableStateOf(false) }
    var enteredValue by remember(value, editingValue) { mutableStateOf(value.toString().trimEnd('0').trimEnd('.')) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(OneUiWidgetStyle.card, RoundedCornerShape(28.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = OneUiWidgetStyle.primary, fontSize = 18.sp, fontWeight = FontWeight.Normal)
                Text(description, color = OneUiWidgetStyle.secondary, style = oneUiMenuTypography.bodyMedium)
            }
            Text(
                valueText,
                color = OneUiWidgetStyle.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .background(OneUiWidgetStyle.action, RoundedCornerShape(10.dp))
                        .clickable { editingValue = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        MobileSettingsSeekBar(title, value, valueRange, onValueChange, onValueChangeFinished)
    }
    if (editingValue) {
        AlertDialog(
            onDismissRequest = { editingValue = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = enteredValue,
                    onValueChange = { enteredValue = it },
                    label = { Text("Wert ${valueRange.start} bis ${valueRange.endInclusive}") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    enteredValue
                        .replace(',', '.')
                        .toFloatOrNull()
                        ?.takeIf { it in valueRange }
                        ?.let {
                            onValueChange(it)
                            onValueChangeFinished()
                            editingValue = false
                        }
                }) { Text("ÜBERNEHMEN") }
            },
            dismissButton = { TextButton(onClick = { editingValue = false }) { Text("ABBRECHEN") } },
        )
    }
}

@Composable
private fun ColorSettingRow(
    role: SugarliciousColorRole?,
    label: String = role?.label ?: "Farbe",
    argb: Int,
    onEdit: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(OneUiWidgetStyle.card, RoundedCornerShape(28.dp))
                .clickable(onClick = onEdit)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .background(Color(argb), CircleShape)
                    .border(1.dp, SugarliciousColors.Border, CircleShape),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(text = label, color = OneUiWidgetStyle.primary, fontSize = 18.sp, fontWeight = FontWeight.Normal)
        }
        Spacer(Modifier.width(6.dp))
        if (role != null) {
            ColorRoleExample(role = role, argb = argb, modifier = Modifier.width(88.dp).height(42.dp))
        }
    }
}

@Composable
private fun ColorRoleExample(
    role: SugarliciousColorRole,
    argb: Int,
    modifier: Modifier = Modifier,
) {
    val selectedColor = Color(argb)
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier =
            modifier
                .background(SugarliciousColors.Surface, shape)
                .border(1.dp, SugarliciousColors.Border, shape)
                .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (role) {
            SugarliciousColorRole.BACKGROUND -> {
                Box(
                    Modifier.fillMaxSize().background(selectedColor, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(46.dp)
                            .height(20.dp)
                            .background(SugarliciousColors.Surface, RoundedCornerShape(6.dp)),
                    )
                }
            }

            SugarliciousColorRole.SURFACE,
            SugarliciousColorRole.SURFACE_HIGH,
            SugarliciousColorRole.SURFACE_RAISED,
            SugarliciousColorRole.SURFACE_SELECTED,
            -> {
                Box(
                    Modifier
                        .width(60.dp)
                        .height(27.dp)
                        .background(selectedColor, RoundedCornerShape(8.dp))
                        .border(1.dp, SugarliciousColors.Border, RoundedCornerShape(8.dp)),
                )
            }

            SugarliciousColorRole.BORDER -> {
                Box(Modifier.width(60.dp).height(27.dp).border(2.dp, selectedColor, RoundedCornerShape(8.dp)))
            }

            SugarliciousColorRole.TEXT_PRIMARY,
            SugarliciousColorRole.TEXT_SECONDARY,
            -> {
                Text("Aa", color = selectedColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            SugarliciousColorRole.ON_PRIMARY -> {
                Box(
                    Modifier.fillMaxSize().background(SugarliciousColors.Primary, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Aa", color = selectedColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            SugarliciousColorRole.ON_SECONDARY -> {
                Box(
                    Modifier.fillMaxSize().background(SugarliciousColors.Secondary, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Aa", color = selectedColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            SugarliciousColorRole.GLUCOSE_LOW,
            SugarliciousColorRole.GLUCOSE_IN_RANGE,
            SugarliciousColorRole.GLUCOSE_HIGH,
            SugarliciousColorRole.RANGE_LOW,
            SugarliciousColorRole.RANGE_VERY_LOW,
            SugarliciousColorRole.RANGE_IN_RANGE,
            SugarliciousColorRole.RANGE_HIGH,
            SugarliciousColorRole.RANGE_VERY_HIGH,
            SugarliciousColorRole.CGM_DOT_LOW,
            SugarliciousColorRole.CGM_DOT_IN_RANGE,
            SugarliciousColorRole.CGM_DOT_HIGH,
            -> {
                Text("123", color = selectedColor, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }

            SugarliciousColorRole.PROGRESS_BELOW,
            SugarliciousColorRole.PROGRESS_IN_RANGE,
            SugarliciousColorRole.PROGRESS_ABOVE,
            -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .background(SugarliciousColors.SurfaceRaised, RoundedCornerShape(999.dp)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.68f)
                            .height(12.dp)
                            .background(selectedColor, RoundedCornerShape(999.dp)),
                    )
                }
            }

            SugarliciousColorRole.TARGET_BAND -> {
                Box(Modifier.fillMaxWidth().height(18.dp).background(selectedColor, RoundedCornerShape(5.dp)))
            }

            SugarliciousColorRole.PREDICTION_IOB,
            SugarliciousColorRole.PREDICTION_COB,
            SugarliciousColorRole.PREDICTION_UAM,
            SugarliciousColorRole.PREDICTION_ZERO_TEMP,
            -> {
                Canvas(Modifier.fillMaxSize()) {
                    repeat(5) { index ->
                        drawCircle(
                            color = selectedColor,
                            radius = 3.3.dp.toPx(),
                            center =
                                Offset(
                                    x = 9.dp.toPx() + index * 14.dp.toPx(),
                                    y = size.height - 8.dp.toPx() - index * 4.dp.toPx(),
                                ),
                        )
                    }
                }
            }

            SugarliciousColorRole.GRAPH_IOB,
            SugarliciousColorRole.GRAPH_COB,
            SugarliciousColorRole.GRAPH_INSULIN_ACTIVITY,
            -> {
                Canvas(Modifier.fillMaxSize()) {
                    val points =
                        listOf(
                            Offset(4.dp.toPx(), size.height - 5.dp.toPx()),
                            Offset(22.dp.toPx(), size.height - 15.dp.toPx()),
                            Offset(40.dp.toPx(), size.height - 11.dp.toPx()),
                            Offset(58.dp.toPx(), 7.dp.toPx()),
                        )
                    points.zipWithNext().forEach { (start, end) ->
                        drawLine(color = selectedColor, start = start, end = end, strokeWidth = 3.dp.toPx())
                    }
                }
            }

            SugarliciousColorRole.GRAPH_GRID -> {
                Canvas(Modifier.fillMaxSize()) {
                    repeat(3) { index ->
                        val y = size.height * index / 2f
                        drawLine(selectedColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    repeat(3) { index ->
                        val x = size.width * index / 2f
                        drawLine(selectedColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    }
                }
            }

            SugarliciousColorRole.GRAPH_LABEL -> {
                Text("120  18", color = selectedColor, fontSize = 12.sp, textAlign = TextAlign.Center)
            }

            SugarliciousColorRole.GRAPH_MUTED,
            SugarliciousColorRole.GRAPH_DIVIDER,
            SugarliciousColorRole.GRAPH_BORDER,
            SugarliciousColorRole.GRAPH_SIGNAL_LOSS,
            -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(4) { Box(Modifier.width(11.dp).height(2.dp).background(selectedColor)) }
                }
            }

            SugarliciousColorRole.GRAPH_CURRENT_OUTLINE -> {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(selectedColor, 12.dp.toPx(), center, style = Stroke(3.dp.toPx()))
                    drawCircle(SugarliciousColors.GlucoseInRange, 7.dp.toPx(), center)
                }
            }

            SugarliciousColorRole.GRAPH_BACKGROUND -> {
                Box(Modifier.fillMaxSize().background(selectedColor, RoundedCornerShape(10.dp)))
            }

            else -> {
                val label =
                    when (role) {
                        SugarliciousColorRole.BLUE -> "IOB"
                        SugarliciousColorRole.ORANGE -> "COB"
                        SugarliciousColorRole.YELLOW -> "!"
                        SugarliciousColorRole.RED -> "!"
                        SugarliciousColorRole.PURPLE -> "●"
                        SugarliciousColorRole.GREEN -> "●"
                        SugarliciousColorRole.BRAND_GREEN -> "S"
                        SugarliciousColorRole.PRIMARY -> "AKTIV"
                        SugarliciousColorRole.SECONDARY -> "INFO"
                        else -> "●"
                    }
                Box(
                    modifier =
                        Modifier
                            .background(selectedColor.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                            .border(1.dp, selectedColor, RoundedCornerShape(999.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = selectedColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
internal fun ColorEditorDialog(
    role: SugarliciousColorRole?,
    label: String = role?.label ?: "Farbe",
    initialArgb: Int,
    onDismiss: () -> Unit,
    onChange: (Int) -> Unit,
    widgetStyle: OneUiWidgetSettingsColors? = null,
    resetArgb: Int? = null,
    onReset: (() -> Int)? = null,
    actions: List<ColorPickerAction> = emptyList(),
) {
    OneUiWidgetColorPicker(label, initialArgb, widgetStyle ?: LocalOneUiWidgetSettingsColors.current, resetArgb, onDismiss, onChange, onReset, actions)
}

@Composable
internal fun SaturationBrightnessPicker(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onChange: (Float, Float) -> Unit,
) {
    val currentOnChange by rememberUpdatedState(onChange)
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics {
                    contentDescription = "Farbspektrum"
                    stateDescription = "Sättigung ${(saturation * 100).roundToInt()} %, Helligkeit ${(brightness * 100).roundToInt()} %"
                    customActions =
                        listOf(
                            CustomAccessibilityAction("Sättigung erhöhen") {
                                currentOnChange((saturation + .05f).coerceAtMost(1f), brightness)
                                true
                            },
                            CustomAccessibilityAction("Sättigung verringern") {
                                currentOnChange((saturation - .05f).coerceAtLeast(0f), brightness)
                                true
                            },
                            CustomAccessibilityAction("Helligkeit erhöhen") {
                                currentOnChange(saturation, (brightness + .05f).coerceAtMost(1f))
                                true
                            },
                            CustomAccessibilityAction("Helligkeit verringern") {
                                currentOnChange(saturation, (brightness - .05f).coerceAtLeast(0f))
                                true
                            },
                        )
                }.pointerInput(Unit) {
                    detectTapGestures { offset ->
                        currentOnChange((offset.x / size.width).coerceIn(0f, 1f), (1f - offset.y / size.height).coerceIn(0f, 1f))
                    }
                }.pointerInput(Unit) {
                    fun update(offset: Offset) {
                        currentOnChange(
                            (offset.x / size.width).coerceIn(0f, 1f),
                            (1f - offset.y / size.height).coerceIn(0f, 1f),
                        )
                    }
                    detectDragGestures(onDragStart = ::update) { change, _ -> update(change.position) }
                },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f))))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val inset = 9.dp.toPx()
        val marker = Offset((saturation * size.width).coerceIn(inset, size.width - inset), ((1f - brightness) * size.height).coerceIn(inset, size.height - inset))
        drawCircle(Color.White, 7.dp.toPx(), marker, style = Stroke(2.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.65f), 9.dp.toPx(), marker, style = Stroke(1.dp.toPx()))
    }
}

@Composable
internal fun HuePicker(
    hue: Float,
    onChange: (Float) -> Unit,
) {
    val currentOnChange by rememberUpdatedState(onChange)
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics {
                    contentDescription = "Farbton"
                    progressBarRangeInfo = ProgressBarRangeInfo(hue, 0f..360f)
                    setProgress { value ->
                        if (value.isFinite()) currentOnChange(value.coerceIn(0f, 360f))
                        value.isFinite()
                    }
                }.pointerInput(Unit) {
                    detectTapGestures { offset -> currentOnChange((offset.x / size.width).coerceIn(0f, 1f) * 360f) }
                }.pointerInput(Unit) {
                    fun update(offset: Offset) = currentOnChange((offset.x / size.width).coerceIn(0f, 1f) * 360f)
                    detectDragGestures(onDragStart = ::update) { change, _ -> update(change.position) }
                },
    ) {
        drawRoundRect(
            brush =
                Brush.horizontalGradient(
                    listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                ),
            cornerRadius =
                androidx.compose.ui.geometry
                    .CornerRadius(size.height / 2f),
        )
        val x = (hue / 360f * size.width).coerceIn(9.dp.toPx(), size.width - 9.dp.toPx())
        drawCircle(Color.White, 7.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(2.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.55f), 9.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(1.dp.toPx()))
    }
}

@Composable
internal fun AlphaPicker(
    color: Color,
    alpha: Float,
    onChange: (Float) -> Unit,
) {
    val currentOnChange by rememberUpdatedState(onChange)
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics {
                    contentDescription = "Deckkraft"
                    progressBarRangeInfo = ProgressBarRangeInfo(alpha, 0f..1f)
                    stateDescription = "Transparenz ${(100 - alpha * 100).roundToInt()} %"
                    setProgress { value ->
                        if (value.isFinite()) currentOnChange(value.coerceIn(0f, 1f))
                        value.isFinite()
                    }
                }.pointerInput(Unit) {
                    detectTapGestures { offset -> currentOnChange((offset.x / size.width).coerceIn(0f, 1f)) }
                }.pointerInput(Unit) {
                    fun update(offset: Offset) = currentOnChange((offset.x / size.width).coerceIn(0f, 1f))
                    detectDragGestures(onDragStart = ::update) { change, _ -> update(change.position) }
                },
    ) {
        val cell = 7.dp.toPx()
        var row = 0
        var y = 0f
        while (y < size.height) {
            var column = 0
            var x = 0f
            while (x < size.width) {
                drawRect(
                    color = if ((row + column) % 2 == 0) Color(0xFFBEBEBE) else Color(0xFF707070),
                    topLeft = Offset(x, y),
                    size =
                        androidx.compose.ui.geometry
                            .Size(cell, cell),
                )
                x += cell
                column++
            }
            y += cell
            row++
        }
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color.copy(alpha = 1f))),
            cornerRadius =
                androidx.compose.ui.geometry
                    .CornerRadius(size.height / 2f),
        )
        val x = (alpha * size.width).coerceIn(9.dp.toPx(), size.width - 9.dp.toPx())
        drawCircle(Color.White, 7.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(2.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.55f), 9.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(1.dp.toPx()))
    }
}

@Composable
internal fun NotificationGraphSettingsPanel() {
    val context = LocalContext.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val preferences = remember { context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE) }
    var revision by remember { mutableIntStateOf(0) }
    var editingRole by remember { mutableStateOf<SugarliciousColorRole?>(null) }
    var selectedMode by remember { mutableStateOf(SugarliciousColorStore.activeMode(preferences)) }
    val palette = SugarliciousColorStore.load(preferences, selectedMode)
    val modePrefix = "notification.color.${selectedMode.storageKey}."

    fun key(role: SugarliciousColorRole) = modePrefix + role.preferenceKey

    fun legacyOverrideKey(role: SugarliciousColorRole) = "notification.color.override." + role.preferenceKey

    fun resolved(role: SugarliciousColorRole): Int =
        when {
            preferences.contains(key(role)) -> preferences.getInt(key(role), palette.argb(role))
            else -> NotificationGraphColorDefaults.argb(palette, role)
        }

    val collapsedInitial =
        remember(revision, selectedMode) {
            NotificationGraphDotStyleStore.read(preferences, selectedMode, NotificationGraphProfile.COLLAPSED)
        }
    val expandedInitial =
        remember(revision, selectedMode) {
            NotificationGraphDotStyleStore.read(preferences, selectedMode, NotificationGraphProfile.EXPANDED)
        }
    var collapsedRadius by remember(revision) { mutableFloatStateOf(collapsedInitial.cgmRadiusDp) }
    var collapsedOutlineEnabled by remember(revision) { mutableStateOf(collapsedInitial.historicalOutlineEnabled) }
    var collapsedOutlineWidth by remember(revision) { mutableFloatStateOf(collapsedInitial.historicalOutlineWidthDp) }
    var collapsedCurrentOutlineWidth by remember(revision) { mutableFloatStateOf(collapsedInitial.currentOutlineWidthDp) }
    var expandedRadius by remember(revision) { mutableFloatStateOf(expandedInitial.cgmRadiusDp) }
    var expandedOutlineEnabled by remember(revision) { mutableStateOf(expandedInitial.historicalOutlineEnabled) }
    var expandedOutlineWidth by remember(revision) { mutableFloatStateOf(expandedInitial.historicalOutlineWidthDp) }
    var expandedCurrentOutlineWidth by remember(revision) { mutableFloatStateOf(expandedInitial.currentOutlineWidthDp) }
    var scaleLaneOpacity by remember(revision) {
        mutableFloatStateOf(preferences.getInt("notification.graph.scale_lane_opacity_percent", 30).toFloat())
    }
    var collapsedLayout by remember(revision) {
        mutableStateOf(NotificationLayoutSettingsStore.read(preferences, NotificationGraphProfile.COLLAPSED))
    }
    var expandedLayout by remember(revision) {
        mutableStateOf(NotificationLayoutSettingsStore.read(preferences, NotificationGraphProfile.EXPANDED))
    }

    fun saveLayout(
        profile: NotificationGraphProfile,
        value: NotificationLayoutSettings,
    ) {
        NotificationLayoutSettingsStore.save(preferences, profile, value)
        PersistentBridgeService.refresh(context)
    }

    fun saveCollapsed() {
        NotificationGraphDotStyleStore.save(
            preferences,
            selectedMode,
            NotificationGraphProfile.COLLAPSED,
            NotificationGraphDotStyle(collapsedRadius, collapsedOutlineEnabled, collapsedOutlineWidth, collapsedOutlineEnabled, collapsedOutlineWidth, collapsedCurrentOutlineWidth),
        )
    }

    fun saveExpanded() {
        NotificationGraphDotStyleStore.save(
            preferences,
            selectedMode,
            NotificationGraphProfile.EXPANDED,
            NotificationGraphDotStyle(expandedRadius, expandedOutlineEnabled, expandedOutlineWidth, expandedOutlineEnabled, expandedOutlineWidth, expandedCurrentOutlineWidth),
        )
    }

    val roles =
        listOf(
            SugarliciousColorRole.CGM_DOT_LOW,
            SugarliciousColorRole.CGM_DOT_IN_RANGE,
            SugarliciousColorRole.CGM_DOT_HIGH,
            SugarliciousColorRole.GRAPH_CURRENT_OUTLINE,
            SugarliciousColorRole.GRAPH_BACKGROUND,
            SugarliciousColorRole.GRAPH_DIVIDER,
            SugarliciousColorRole.RANGE_LOW,
            SugarliciousColorRole.RANGE_IN_RANGE,
            SugarliciousColorRole.RANGE_HIGH,
            SugarliciousColorRole.TARGET_VALUE,
            SugarliciousColorRole.GRAPH_SIGNAL_LOSS,
        )

    Column(
        Modifier
            .fillMaxWidth()
            .background(OneUiWidgetStyle.card, RoundedCornerShape(28.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NOTIFICATION-GRAPH", color = OneUiWidgetStyle.secondary, style = oneUiMenuTypography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("CGM-Dots & Farben", color = OneUiWidgetStyle.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (palette.isLight) "Aktuelle Farbvariante: Hell" else "Aktuelle Farbvariante: Dunkel",
                    color = OneUiWidgetStyle.secondary,
                    fontSize = 9.sp,
                )
            }
        }

        AppearanceModeSelector(selectedMode) {
            selectedMode = it
            revision++
        }

        Text(
            "Farben gelten gemeinsam für eingeklappt und ausgeklappt. Punktgeometrie wird getrennt gespeichert.",
            color = OneUiWidgetStyle.secondary,
            style = oneUiMenuTypography.bodyMedium,
        )
        SugarliciousSettingSlider(
            "Skalenbereich",
            "Nur Graphfläche und Zielbereichslinien; Beschriftungen bleiben deckend",
            scaleLaneOpacity,
            0f..100f,
            "${scaleLaneOpacity.roundToInt()} %",
            { value ->
                scaleLaneOpacity = value
                preferences.edit { putInt("notification.graph.scale_lane_opacity_percent", value.roundToInt()) }
                PersistentBridgeService.refresh(context)
            },
            {},
        )

        Text(
            "EINGEKLAPPT",
            color = OneUiWidgetStyle.secondary,
            style = oneUiMenuTypography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 5.dp),
        )
        NotificationLayoutControls(
            value = collapsedLayout,
            onChange = {
                collapsedLayout = it
                saveLayout(NotificationGraphProfile.COLLAPSED, it)
            },
        )
        SugarliciousSettingSlider(
            title = "Punktgröße",
            description = "CGM-Dots im eingeklappten Notification-Graph",
            value = collapsedRadius,
            valueRange = 1.5f..6f,
            valueText = "${String.format(locale, "%.1f", collapsedRadius)} dp",
            onValueChange = { collapsedRadius = it },
            onValueChangeFinished = ::saveCollapsed,
        )
        GraphSettingSwitch(
            title = "Historische Kontur",
            description = "Kontur historischer CGM-Dots",
            checked = collapsedOutlineEnabled,
            onCheckedChange = {
                collapsedOutlineEnabled = it
                saveCollapsed()
            },
        )
        if (collapsedOutlineEnabled) {
            SugarliciousSettingSlider(
                title = "Konturdicke",
                description = "Konturdicke im eingeklappten Notification-Graph",
                value = collapsedOutlineWidth,
                valueRange = 0.25f..3f,
                valueText = "${String.format(locale, "%.2f", collapsedOutlineWidth)} dp",
                onValueChange = { collapsedOutlineWidth = it },
                onValueChangeFinished = ::saveCollapsed,
            )
        }
        SugarliciousSettingSlider(
            title = "Aktuelle Konturdicke",
            description = "Aktueller CGM-Dot (immer aktiv)",
            value = collapsedCurrentOutlineWidth,
            valueRange = 0.25f..3f,
            valueText = "${String.format(locale, "%.2f", collapsedCurrentOutlineWidth)} dp",
            onValueChange = { collapsedCurrentOutlineWidth = it },
            onValueChangeFinished = ::saveCollapsed,
        )

        Text(
            "AUSGEKLAPPT",
            color = OneUiWidgetStyle.secondary,
            style = oneUiMenuTypography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )
        NotificationLayoutControls(
            value = expandedLayout,
            onChange = {
                expandedLayout = it
                saveLayout(NotificationGraphProfile.EXPANDED, it)
            },
        )
        SugarliciousSettingSlider(
            title = "Punktgröße",
            description = "CGM-Dots im ausgeklappten Notification-Graph",
            value = expandedRadius,
            valueRange = 1.5f..6f,
            valueText = "${String.format(locale, "%.1f", expandedRadius)} dp",
            onValueChange = { expandedRadius = it },
            onValueChangeFinished = ::saveExpanded,
        )
        GraphSettingSwitch(
            title = "Historische Kontur",
            description = "Kontur historischer CGM-Dots",
            checked = expandedOutlineEnabled,
            onCheckedChange = {
                expandedOutlineEnabled = it
                saveExpanded()
            },
        )
        if (expandedOutlineEnabled) {
            SugarliciousSettingSlider(
                title = "Konturdicke",
                description = "Konturdicke im ausgeklappten Notification-Graph",
                value = expandedOutlineWidth,
                valueRange = 0.25f..3f,
                valueText = "${String.format(locale, "%.2f", expandedOutlineWidth)} dp",
                onValueChange = { expandedOutlineWidth = it },
                onValueChangeFinished = ::saveExpanded,
            )
        }
        SugarliciousSettingSlider(
            title = "Aktuelle Konturdicke",
            description = "Aktueller CGM-Dot (immer aktiv)",
            value = expandedCurrentOutlineWidth,
            valueRange = 0.25f..3f,
            valueText = "${String.format(locale, "%.2f", expandedCurrentOutlineWidth)} dp",
            onValueChange = { expandedCurrentOutlineWidth = it },
            onValueChangeFinished = ::saveExpanded,
        )
        TextButton(
            onClick = {
                val copied = NotificationGraphDotStyleStore.read(preferences, selectedMode, NotificationGraphProfile.COLLAPSED)
                NotificationGraphDotStyleStore.save(preferences, selectedMode, NotificationGraphProfile.EXPANDED, copied)
                expandedRadius = copied.cgmRadiusDp
                expandedOutlineEnabled = copied.historicalOutlineEnabled
                expandedOutlineWidth = copied.historicalOutlineWidthDp
                expandedCurrentOutlineWidth = copied.currentOutlineWidthDp
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("VON EINGEKLAPPT ÜBERNEHMEN", color = OneUiWidgetStyle.primary, style = oneUiMenuTypography.bodyMedium, fontWeight = FontWeight.Bold)
        }

        Text(
            "FARBEN",
            color = OneUiWidgetStyle.secondary,
            style = oneUiMenuTypography.bodyMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 6.dp),
        )
        roles.forEach { role ->
            ColorSettingRow(
                role = role,
                argb = resolved(role),
                onEdit = { editingRole = role },
            )
        }
    }

    editingRole?.let { role ->
        ColorEditorDialog(
            role = role,
            label = role.label,
            initialArgb = resolved(role),
            onReset = {
                preferences.edit {
                    remove(key(role))
                    remove(legacyOverrideKey(role))
                }
                revision++
                resolved(role)
            },
            actions =
                listOf(
                    ColorPickerAction("Farben aus Mobile übernehmen") {
                        preferences.edit {
                            roles.forEach { source -> putInt(key(source), palette.argb(source)) }
                        }
                        revision++
                        resolved(role)
                    },
                    ColorPickerAction("Notification-Graph zurücksetzen") {
                        NotificationGraphDotStyleStore.resetProfiles(preferences)
                        preferences.edit {
                            roles.forEach {
                                remove(key(it))
                                remove(legacyOverrideKey(it))
                            }
                        }
                        revision++
                        resolved(role)
                    },
                ),
            onDismiss = { editingRole = null },
            onChange = { argb ->
                preferences.edit { putInt(key(role), argb) }
                revision++
            },
        )
    }
}

@Composable
private fun NotificationLayoutControls(
    value: NotificationLayoutSettings,
    onChange: (NotificationLayoutSettings) -> Unit,
) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    SugarliciousSettingSlider("CGM-Größe", "Nur der Glukosewert", value.glucoseScalePercent.toFloat(), 70f..200f, "${value.glucoseScalePercent} %", {
        onChange(value.copy(glucoseScalePercent = it.toInt()))
    }, {})
    SugarliciousSettingSlider("CGM X", "Relative horizontale Position", value.glucoseXPercent.toFloat(), -40f..40f, "${value.glucoseXPercent} %", {
        onChange(value.copy(glucoseXPercent = it.toInt()))
    }, {})
    SugarliciousSettingSlider("CGM Y", "Relative vertikale Position", value.glucoseYPercent.toFloat(), -40f..40f, "${value.glucoseYPercent} %", {
        onChange(value.copy(glucoseYPercent = it.toInt()))
    }, {})
    SugarliciousSettingSlider(
        "Trendpfeilgröße",
        if (value.trendScalePercent == null) "Systemstandard" else "Notification-Override",
        (value.trendScalePercent ?: 100).toFloat(),
        70f..200f,
        value.trendScalePercent?.let { "$it %" } ?: "System",
        { onChange(value.copy(trendScalePercent = it.toInt())) },
        {},
    )
    OneUiResetIcon(onClick = { onChange(value.copy(trendScalePercent = null)) }, description = "Trendpfeilgröße auf Systemstandard zurücksetzen")
    SugarliciousSettingSlider("Trend X", "Relative horizontale Position", value.trendXPercent.toFloat(), -40f..40f, "${value.trendXPercent} %", {
        onChange(value.copy(trendXPercent = it.toInt()))
    }, {})
    SugarliciousSettingSlider("Trend Y", "Relative vertikale Position", value.trendYPercent.toFloat(), -40f..40f, "${value.trendYPercent} %", {
        onChange(value.copy(trendYPercent = it.toInt()))
    }, {})
    SugarliciousSettingSlider(
        "Delta/Age-Größe",
        "Sekundärzeile",
        value.metaScalePercent.toFloat(),
        70f..160f,
        String.format(
            locale,
            "%d %%",
            value.metaScalePercent,
        ),
        {
            onChange(value.copy(metaScalePercent = it.toInt()))
        },
        {},
    )
}

internal fun toHex(argb: Int): String = ArgbColor.format(argb)

internal fun copyWidgetColorHex(context: android.content.Context, argb: Int) {
    context
        .getSystemService(android.content.ClipboardManager::class.java)
        .setPrimaryClip(android.content.ClipData.newPlainText("HEX", toHex(argb)))
}
