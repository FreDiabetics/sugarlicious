package app.aapswear.mobile

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aapswear.mobile.ui.theme.SugarliciousColorGroup
import app.aapswear.mobile.ui.theme.SugarliciousColorRole
import app.aapswear.mobile.ui.theme.SugarliciousColorStore
import app.aapswear.mobile.ui.theme.SugarliciousColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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

    var palette by remember {
        mutableStateOf(
            SugarliciousColorStore.load(preferences),
        )
    }
    var editingRole by remember {
        mutableStateOf<SugarliciousColorRole?>(null)
    }
    var watchSyncStatus by remember { mutableStateOf<String?>(null) }
    var cgmDotRadiusDp by remember(showCgmGraph) {
        mutableFloatStateOf(preferences.getFloat("cgm.dotRadiusDp", 2.4f).coerceIn(1.5f, 6.0f))
    }
    var cgmDotOutlineEnabled by remember(showCgmGraph) {
        mutableStateOf(preferences.getBoolean("cgm.dotOutlineEnabled", true))
    }
    var cgmDotOutlineWidthDp by remember(showCgmGraph) {
        mutableFloatStateOf(preferences.getFloat("cgm.dotOutlineWidthDp", 0.95f).coerceIn(0.25f, 3.0f))
    }

    fun reload() {
        palette = SugarliciousColorStore.load(preferences)
        SugarliciousColors.apply(palette)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                SugarliciousColors.Surface,
                RoundedCornerShape(24.dp),
            )
            .border(
                1.dp,
                SugarliciousColors.Border,
                RoundedCornerShape(24.dp),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "FARBEN",
                    color = SugarliciousColors.TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                )
                Text(
                    text = "Darstellung & Farben",
                    color = SugarliciousColors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            TextButton(
                onClick = {
                    SugarliciousColorStore.resetAll(preferences)
                    reload()
                },
            ) {
                Text(
                    "ALLES RESET",
                    color = SugarliciousColors.Primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

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
            colors = ButtonDefaults.buttonColors(
                containerColor = SugarliciousColors.Primary,
                contentColor = SugarliciousColors.OnPrimary,
            ),
        ) {
            Text("AN WATCH SENDEN", fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
        watchSyncStatus?.let { status ->
            Text(status, color = SugarliciousColors.TextSecondary, fontSize = 10.sp)
        }

        if (showCgmGraph) {
            Text(
                text = "CGM-PUNKTE",
                color = SugarliciousColors.TextSecondary,
                fontSize = 10.sp,
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
                    preferences.edit().putFloat("cgm.dotRadiusDp", cgmDotRadiusDp).apply()
                },
            )

            GraphSettingSwitch(
                title = "Kontur",
                description = "Kontur um die CGM-Dots anzeigen",
                checked = cgmDotOutlineEnabled,
                onCheckedChange = { enabled ->
                    cgmDotOutlineEnabled = enabled
                    preferences.edit().putBoolean("cgm.dotOutlineEnabled", enabled).apply()
                },
            )

            if (cgmDotOutlineEnabled) {
                SugarliciousSettingSlider(
                    title = "Konturdicke",
                    description = "Dicke der Dot-Kontur",
                    value = cgmDotOutlineWidthDp,
                    valueRange = 0.25f..3.0f,
                    valueText = "${String.format(locale, "%.2f", cgmDotOutlineWidthDp)} dp",
                    onValueChange = { cgmDotOutlineWidthDp = it },
                    onValueChangeFinished = {
                        preferences.edit().putFloat("cgm.dotOutlineWidthDp", cgmDotOutlineWidthDp).apply()
                    },
                )
            }
        }

        SugarliciousColorGroup.entries.forEach { group ->
            val roles =
                SugarliciousColorRole.entries.filter {
                    it.group == group &&
                        colorRoleVisible(
                            role = it,
                            showCgmGraph = showCgmGraph,
                            showMetabolicGraph = showMetabolicGraph,
                        )
                }
            if (roles.isEmpty()) return@forEach
            Text(
                text = group.label.uppercase(),
                color = SugarliciousColors.TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )

            roles
                .forEach { role ->
                    val argb = palette.argb(role)

                    ColorSettingRow(
                        role = role,
                        argb = argb,
                        isDefault = argb == if (palette.isLight) role.lightArgb else role.defaultArgb,
                        onEdit = {
                            editingRole = role
                        },
                        onReset = {
                            SugarliciousColorStore.reset(
                                preferences,
                                role,
                            )
                            reload()
                        },
                    )
                }
        }
    }

    editingRole?.let { role ->
        ColorEditorDialog(
            role = role,
            label = role.label,
            initialArgb = palette.argb(role),
            onDismiss = {
                editingRole = null
            },
            onSave = { argb ->
                SugarliciousColorStore.save(
                    preferences,
                    role,
                    argb,
                )
                reload()
                editingRole = null
            },
        )
    }
}

@Composable
internal fun WidgetColorSettingsPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    var editingRole by remember { mutableStateOf<WidgetColorRole?>(null) }
    val palette = remember(revision, SugarliciousColors.palette.isLight) { WidgetColorStore.load(context) }

    fun refreshWidgets() {
        revision++
        scope.launch { SugarliciousWidgets.update(context.applicationContext) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SugarliciousColors.Surface, RoundedCornerShape(24.dp))
            .border(1.dp, SugarliciousColors.Border, RoundedCornerShape(24.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("GLUKOSEWIDGET", color = SugarliciousColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text("Farben", color = SugarliciousColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Die Farben werden als eigene Kopie gespeichert und ändern sich danach unabhängig vom mobilen Graphen.",
            color = SugarliciousColors.TextSecondary,
            fontSize = 10.sp,
        )
        Button(
            onClick = {
                WidgetColorStore.copyFromMobileGraph(context)
                refreshWidgets()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = SugarliciousColors.Primary,
                contentColor = SugarliciousColors.OnPrimary,
            ),
        ) {
            Text("AUS MOBILE-GRAPH ÜBERNEHMEN", fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(
            onClick = {
                WidgetColorStore.resetAll(context)
                refreshWidgets()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("WIDGET-FARBEN ZURÜCKSETZEN", color = SugarliciousColors.Primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        WidgetColorRole.entries.forEach { role ->
            WidgetColorSettingRow(
                role = role,
                argb = palette.argb(role),
                isDefault = !WidgetColorStore.hasOverride(context, role),
                onEdit = { editingRole = role },
                onReset = {
                    WidgetColorStore.reset(context, role)
                    refreshWidgets()
                },
            )
        }
    }

    editingRole?.let { role ->
        ColorEditorDialog(
            role = null,
            label = "Widget: ${role.label}",
            initialArgb = palette.argb(role),
            onDismiss = { editingRole = null },
            onSave = { argb ->
                WidgetColorStore.save(context, role, argb)
                editingRole = null
                refreshWidgets()
            },
        )
    }
}

@Composable
private fun WidgetColorSettingRow(
    role: WidgetColorRole,
    argb: Int,
    isDefault: Boolean,
    onEdit: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SugarliciousColors.SurfaceHigh, RoundedCornerShape(16.dp))
            .clickable(onClick = onEdit)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(Color(argb), CircleShape)
                .border(1.dp, SugarliciousColors.Border, CircleShape),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(role.label, color = SugarliciousColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(toHex(argb), color = SugarliciousColors.TextSecondary, fontSize = 9.sp)
        }
        if (!isDefault) {
            TextButton(onClick = onReset) {
                Text("RESET", color = SugarliciousColors.Primary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private val cgmGraphColorRoles =
    setOf(
        SugarliciousColorRole.RANGE_LOW,
        SugarliciousColorRole.RANGE_IN_RANGE,
        SugarliciousColorRole.RANGE_HIGH,
        SugarliciousColorRole.TARGET_BAND,
        SugarliciousColorRole.CGM_DOT_LOW,
        SugarliciousColorRole.CGM_DOT_IN_RANGE,
        SugarliciousColorRole.CGM_DOT_HIGH,
        SugarliciousColorRole.GRAPH_DIVIDER,
        SugarliciousColorRole.GRAPH_SIGNAL_LOSS,
    )

internal fun colorRoleVisible(
    role: SugarliciousColorRole,
    showCgmGraph: Boolean,
    showMetabolicGraph: Boolean,
): Boolean {
    if (!role.configurable) return false
    if (!showCgmGraph && role in cgmGraphColorRoles) return false
    if (
        role == SugarliciousColorRole.GRAPH_BACKGROUND &&
        !showCgmGraph &&
        !showMetabolicGraph
    ) {
        return false
    }
    return true
}

@Composable
private fun GraphSettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SugarliciousColors.SurfaceHigh, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = SugarliciousColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(description, color = SugarliciousColors.TextSecondary, fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(width = 50.dp, height = 30.dp),
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SugarliciousColors.Primary,
                    uncheckedThumbColor = SugarliciousColors.TextSecondary,
                    uncheckedTrackColor = SugarliciousColors.SurfaceRaised,
                    uncheckedBorderColor = SugarliciousColors.Border,
                ),
        )
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SugarliciousColors.SurfaceHigh, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = SugarliciousColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(description, color = SugarliciousColors.TextSecondary, fontSize = 10.sp)
            }
            Text(valueText, color = SugarliciousColors.Primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            modifier = Modifier.height(32.dp),
            colors =
                SliderDefaults.colors(
                    thumbColor = SugarliciousColors.Primary,
                    activeTrackColor = SugarliciousColors.Primary,
                    inactiveTrackColor = SugarliciousColors.SurfaceRaised,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
        )
    }
}

@Composable
private fun ColorSettingRow(
    role: SugarliciousColorRole,
    argb: Int,
    isDefault: Boolean,
    onEdit: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                SugarliciousColors.SurfaceHigh,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onEdit)
            .padding(
                horizontal = 11.dp,
                vertical = 9.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(
                    Color(argb),
                    CircleShape,
                )
                .border(
                    1.dp,
                    SugarliciousColors.Border,
                    CircleShape,
                ),
        )

        Spacer(Modifier.width(9.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = role.label,
                color = SugarliciousColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = toHex(argb),
                color = SugarliciousColors.TextSecondary,
                fontSize = 9.sp,
            )
        }

        if (!isDefault) {
            TextButton(
                onClick = onReset,
                modifier = Modifier.padding(horizontal = 2.dp),
            ) {
                Text(
                    "RESET",
                    color = SugarliciousColors.Primary,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.width(6.dp))

        ColorRoleExample(
            role = role,
            argb = argb,
            modifier = Modifier
                .width(88.dp)
                .height(42.dp),
        )
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
        modifier = modifier
            .background(
                SugarliciousColors.Surface,
                shape,
            )
            .border(
                1.dp,
                SugarliciousColors.Border,
                shape,
            )
            .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (role) {
            SugarliciousColorRole.BACKGROUND -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(selectedColor, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(46.dp)
                            .height(20.dp)
                            .background(
                                SugarliciousColors.Surface,
                                RoundedCornerShape(6.dp),
                            ),
                    )
                }
            }

            SugarliciousColorRole.SURFACE,
            SugarliciousColorRole.SURFACE_HIGH,
            SugarliciousColorRole.SURFACE_RAISED,
            SugarliciousColorRole.SURFACE_SELECTED
            -> {
                Box(
                    Modifier
                        .width(60.dp)
                        .height(27.dp)
                        .background(
                            selectedColor,
                            RoundedCornerShape(8.dp),
                        )
                        .border(
                            1.dp,
                            SugarliciousColors.Border,
                            RoundedCornerShape(8.dp),
                        ),
                )
            }

            SugarliciousColorRole.BORDER -> {
                Box(
                    Modifier
                        .width(60.dp)
                        .height(27.dp)
                        .border(
                            2.dp,
                            selectedColor,
                            RoundedCornerShape(8.dp),
                        ),
                )
            }

            SugarliciousColorRole.TEXT_PRIMARY,
            SugarliciousColorRole.TEXT_SECONDARY
            -> {
                Text(
                    text = "Aa",
                    color = selectedColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            SugarliciousColorRole.ON_PRIMARY -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            SugarliciousColors.Primary,
                            RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Aa",
                        color = selectedColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            SugarliciousColorRole.ON_SECONDARY -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            SugarliciousColors.Secondary,
                            RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Aa",
                        color = selectedColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            SugarliciousColorRole.GLUCOSE_LOW,
            SugarliciousColorRole.GLUCOSE_IN_RANGE,
            SugarliciousColorRole.GLUCOSE_HIGH,
            SugarliciousColorRole.RANGE_LOW,
            SugarliciousColorRole.RANGE_IN_RANGE,
            SugarliciousColorRole.RANGE_HIGH,
            SugarliciousColorRole.CGM_DOT_LOW,
            SugarliciousColorRole.CGM_DOT_IN_RANGE,
            SugarliciousColorRole.CGM_DOT_HIGH
            -> {
                Text(
                    text = "123",
                    color = selectedColor,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            SugarliciousColorRole.PROGRESS_BELOW,
            SugarliciousColorRole.PROGRESS_IN_RANGE,
            SugarliciousColorRole.PROGRESS_ABOVE
            -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .background(
                            SugarliciousColors.SurfaceRaised,
                            RoundedCornerShape(999.dp),
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.68f)
                            .height(12.dp)
                            .background(
                                selectedColor,
                                RoundedCornerShape(999.dp),
                            ),
                    )
                }
            }

            SugarliciousColorRole.TARGET_BAND -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(
                            selectedColor,
                            RoundedCornerShape(5.dp),
                        ),
                )
            }

            SugarliciousColorRole.PREDICTION_IOB,
            SugarliciousColorRole.PREDICTION_COB,
            SugarliciousColorRole.PREDICTION_UAM,
            SugarliciousColorRole.PREDICTION_ZERO_TEMP
            -> {
                Canvas(Modifier.fillMaxSize()) {
                    repeat(5) { index ->
                        drawCircle(
                            color = selectedColor,
                            radius = 3.3.dp.toPx(),
                            center = Offset(
                                x = 9.dp.toPx() + index * 14.dp.toPx(),
                                y =
                                    size.height -
                                        8.dp.toPx() -
                                        index * 4.dp.toPx(),
                            ),
                        )
                    }
                }
            }

            SugarliciousColorRole.GRAPH_IOB,
            SugarliciousColorRole.GRAPH_COB
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
                        drawLine(
                            color = selectedColor,
                            start = start,
                            end = end,
                            strokeWidth = 3.dp.toPx(),
                        )
                    }
                }
            }

            SugarliciousColorRole.GRAPH_GRID -> {
                Canvas(Modifier.fillMaxSize()) {
                    repeat(3) { index ->
                        val y = size.height * index / 2f
                        drawLine(
                            color = selectedColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    repeat(3) { index ->
                        val x = size.width * index / 2f
                        drawLine(
                            color = selectedColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }
            }

            SugarliciousColorRole.GRAPH_LABEL -> {
                Text(
                    text = "120  18",
                    color = selectedColor,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }

            SugarliciousColorRole.GRAPH_MUTED,
            SugarliciousColorRole.GRAPH_DIVIDER,
            SugarliciousColorRole.GRAPH_SIGNAL_LOSS -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(4) {
                        Box(
                            Modifier
                                .width(11.dp)
                                .height(2.dp)
                                .background(selectedColor),
                        )
                    }
                }
            }

            SugarliciousColorRole.GRAPH_CURRENT_OUTLINE -> {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        color = selectedColor,
                        radius = 12.dp.toPx(),
                        center = center,
                        style = Stroke(
                            width = 3.dp.toPx(),
                        ),
                    )
                    drawCircle(
                        color = SugarliciousColors.GlucoseInRange,
                        radius = 7.dp.toPx(),
                        center = center,
                    )
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
                    }

                Box(
                    modifier = Modifier
                        .background(
                            selectedColor.copy(alpha = 0.18f),
                            RoundedCornerShape(999.dp),
                        )
                        .border(
                            1.dp,
                            selectedColor,
                            RoundedCornerShape(999.dp),
                        )
                        .padding(
                            horizontal = 9.dp,
                            vertical = 4.dp,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = selectedColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorEditorDialog(
    role: SugarliciousColorRole?,
    label: String,
    initialArgb: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    val initialHsv = remember(role, initialArgb) {
        FloatArray(3).also { AndroidColor.colorToHSV(initialArgb, it) }
    }
    var hue by remember(role, initialArgb) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(role, initialArgb) { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember(role, initialArgb) { mutableFloatStateOf(initialHsv[2]) }
    var alpha by remember(role, initialArgb) {
        mutableFloatStateOf(AndroidColor.alpha(initialArgb) / 255f)
    }
    var hex by remember(role, initialArgb) {
        mutableStateOf(toHex(initialArgb))
    }

    fun currentArgb(): Int = AndroidColor.HSVToColor(
        (alpha * 255f).roundToInt().coerceIn(0, 255),
        floatArrayOf(hue, saturation, brightness),
    )

    fun syncHex() {
        hex = toHex(currentArgb())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                label,
                color = SugarliciousColors.TextPrimary,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (role != null) {
                    ColorRoleExample(
                        role = role,
                        argb = currentArgb(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(62.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(62.dp)
                            .background(Color(currentArgb()), RoundedCornerShape(12.dp))
                            .border(1.dp, SugarliciousColors.Border, RoundedCornerShape(12.dp)),
                    )
                }

                OutlinedTextField(
                    value = hex,
                    onValueChange = { value ->
                        val normalized =
                            value
                                .trim()
                                .uppercase()
                                .let {
                                    if (it.startsWith("#")) it
                                    else "#$it"
                                }
                                .take(9)

                        hex = normalized

                        parseHex(normalized)?.let { parsed ->
                            val hsv = FloatArray(3).also { AndroidColor.colorToHSV(parsed, it) }
                            hue = hsv[0]
                            saturation = hsv[1]
                            brightness = hsv[2]
                            alpha = AndroidColor.alpha(parsed) / 255f
                        }
                    },
                    label = {
                        Text("HEX")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                SaturationBrightnessPicker(
                    hue = hue,
                    saturation = saturation,
                    brightness = brightness,
                    onChange = { newSaturation, newBrightness ->
                        saturation = newSaturation
                        brightness = newBrightness
                        syncHex()
                    },
                )
                HuePicker(
                    hue = hue,
                    onChange = {
                        hue = it
                        syncHex()
                    },
                )
                Text(
                    text = "Transparenz ${(100f - alpha * 100f).roundToInt()} %",
                    color = SugarliciousColors.TextSecondary,
                    fontSize = 11.sp,
                )
                AlphaPicker(
                    color = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness))),
                    alpha = alpha,
                    onChange = {
                        alpha = it
                        syncHex()
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(currentArgb())
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SugarliciousColors.Primary,
                    contentColor = SugarliciousColors.OnPrimary,
                ),
            ) {
                Text("SPEICHERN")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "ABBRECHEN",
                    color = SugarliciousColors.TextSecondary,
                )
            }
        },
        containerColor = SugarliciousColors.Surface,
        titleContentColor = SugarliciousColors.TextPrimary,
        textContentColor = SugarliciousColors.TextPrimary,
    )
}

@Composable
private fun SaturationBrightnessPicker(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onChange: (Float, Float) -> Unit,
) {
    Canvas(
        modifier = Modifier.fillMaxWidth().height(180.dp)
            .pointerInput(hue) {
                fun update(offset: Offset) {
                    onChange(
                        (offset.x / size.width).coerceIn(0f, 1f),
                        (1f - offset.y / size.height).coerceIn(0f, 1f),
                    )
                }
                detectDragGestures(onDragStart = ::update) { change, _ -> update(change.position) }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f))))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val marker = Offset(saturation * size.width, (1f - brightness) * size.height)
        drawCircle(Color.White, 7.dp.toPx(), marker, style = Stroke(2.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.65f), 9.dp.toPx(), marker, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun HuePicker(hue: Float, onChange: (Float) -> Unit) {
    Canvas(
        modifier = Modifier.fillMaxWidth().height(28.dp)
            .pointerInput(Unit) {
                fun update(offset: Offset) = onChange((offset.x / size.width).coerceIn(0f, 1f) * 360f)
                detectDragGestures(onDragStart = ::update) { change, _ -> update(change.position) }
            },
    ) {
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
        )
        val x = hue / 360f * size.width
        drawCircle(Color.White, 7.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(2.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.55f), 9.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun AlphaPicker(color: Color, alpha: Float, onChange: (Float) -> Unit) {
    Canvas(
        modifier = Modifier.fillMaxWidth().height(28.dp)
            .pointerInput(Unit) {
                fun update(offset: Offset) = onChange((offset.x / size.width).coerceIn(0f, 1f))
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
                    size = androidx.compose.ui.geometry.Size(cell, cell),
                )
                x += cell
                column++
            }
            y += cell
            row++
        }
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color.copy(alpha = 1f))),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
        )
        val x = alpha * size.width
        drawCircle(Color.White, 7.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(2.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.55f), 9.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(1.dp.toPx()))
    }
}


@Composable
internal fun NotificationGraphSettingsPanel() {
    val context = LocalContext.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val preferences = remember { context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE) }
    var revision by remember { mutableStateOf(0) }
    var editingRole by remember { mutableStateOf<SugarliciousColorRole?>(null) }
    val palette = SugarliciousColorStore.load(preferences)
    val modePrefix = if (palette.isLight) "notification.color.light." else "notification.color.dark."
    fun key(role: SugarliciousColorRole) = "notification.color.override." + role.preferenceKey
    fun legacyModeKey(role: SugarliciousColorRole) = modePrefix + role.preferenceKey
    fun resolved(role: SugarliciousColorRole): Int =
        when {
            preferences.contains(key(role)) -> preferences.getInt(key(role), palette.argb(role))
            role == SugarliciousColorRole.RANGE_IN_RANGE -> palette.argb(role)
            preferences.contains(legacyModeKey(role)) -> preferences.getInt(legacyModeKey(role), palette.argb(role))
            else -> palette.argb(role)
        }
    var dotRadius by remember(revision) {
        mutableFloatStateOf(preferences.getFloat(PersistentBridgeService.PREFERENCE_NOTIFICATION_DOT_RADIUS, preferences.getFloat("cgm.dotRadiusDp", 2.4f)).coerceIn(1.5f, 6f))
    }
    var outlineEnabled by remember(revision) {
        mutableStateOf(preferences.getBoolean(PersistentBridgeService.PREFERENCE_NOTIFICATION_DOT_OUTLINE_ENABLED, preferences.getBoolean("cgm.dotOutlineEnabled", true)))
    }
    var outlineWidth by remember(revision) {
        mutableFloatStateOf(preferences.getFloat(PersistentBridgeService.PREFERENCE_NOTIFICATION_DOT_OUTLINE_WIDTH, preferences.getFloat("cgm.dotOutlineWidthDp", 0.95f)).coerceIn(0.25f, 3f))
    }
    val roles = listOf(
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
        Modifier.fillMaxWidth().background(SugarliciousColors.Surface, RoundedCornerShape(24.dp))
            .border(1.dp, SugarliciousColors.Border, RoundedCornerShape(24.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NOTIFICATION-GRAPH", color = SugarliciousColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("CGM-Dots & Farben", color = SugarliciousColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(if (palette.isLight) "Aktuelle Farbvariante: Hell" else "Aktuelle Farbvariante: Dunkel", color = SugarliciousColors.TextSecondary, fontSize = 9.sp)
            }
            TextButton(onClick = {
                preferences.edit().apply {
                    remove(PersistentBridgeService.PREFERENCE_NOTIFICATION_DOT_RADIUS)
                    remove(PersistentBridgeService.PREFERENCE_NOTIFICATION_DOT_OUTLINE_ENABLED)
                    remove(PersistentBridgeService.PREFERENCE_NOTIFICATION_DOT_OUTLINE_WIDTH)
                    roles.forEach {
                        remove(key(it))
                        remove(legacyModeKey(it))
                    }
                }.apply()
                revision++
            }) { Text("RESET", color = SugarliciousColors.Primary, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }
        Text(
            "Ohne eigenen Override übernimmt der Notification-Graph automatisch die Mobile-CGM-Graphfarben.",
            color = SugarliciousColors.TextSecondary,
            fontSize = 10.sp,
        )
        Button(
            onClick = {
                preferences.edit().apply {
                    roles.forEach { role -> putInt(key(role), palette.argb(role)) }
                    putFloat(PersistentBridgeService.PREFERENCE_NOTIFICATION_DOT_RADIUS, preferences.getFloat("cgm.dotRadiusDp", 2.4f))
                    putBoolean(PersistentBridgeService.PREFERENCE_NOTIFICATION_DOT_OUTLINE_ENABLED, preferences.getBoolean("cgm.dotOutlineEnabled", true))
                    putFloat(PersistentBridgeService.PREFERENCE_NOTIFICATION_DOT_OUTLINE_WIDTH, preferences.getFloat("cgm.dotOutlineWidthDp", 0.95f))
                }.apply()
                revision++
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = SugarliciousColors.Primary,
                contentColor = SugarliciousColors.OnPrimary,
            ),
        ) {
            Text("MIT MOBILE SYNCHRONISIEREN", fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        SugarliciousSettingSlider(
            "Punktgröße", "Nur die CGM-Dots in der Notification", dotRadius, 1.5f..6f,
            "${String.format(locale, "%.1f", dotRadius)} dp",
            { dotRadius = it },
            { preferences.edit().putFloat(PersistentBridgeService.PREFERENCE_NOTIFICATION_DOT_RADIUS, dotRadius).apply() },
        )
        GraphSettingSwitch("Kontur", "Kontur der Notification-CGM-Dots", outlineEnabled) {
            outlineEnabled = it
            preferences.edit().putBoolean(PersistentBridgeService.PREFERENCE_NOTIFICATION_DOT_OUTLINE_ENABLED, it).apply()
        }
        if (outlineEnabled) {
            SugarliciousSettingSlider(
                "Konturdicke", "Nur für Notification-CGM-Dots", outlineWidth, 0.25f..3f,
                "${String.format(locale, "%.2f", outlineWidth)} dp",
                { outlineWidth = it },
                { preferences.edit().putFloat(PersistentBridgeService.PREFERENCE_NOTIFICATION_DOT_OUTLINE_WIDTH, outlineWidth).apply() },
            )
        }
        roles.forEach { role ->
            ColorSettingRow(
                role = role,
                argb = resolved(role),
                isDefault = !preferences.contains(key(role)) && !preferences.contains(legacyModeKey(role)),
                onEdit = { editingRole = role },
                onReset = {
                    preferences.edit().remove(key(role)).remove(legacyModeKey(role)).apply()
                    revision++
                },
            )
        }
    }
    editingRole?.let { role ->
        ColorEditorDialog(
            role = role,
            label = role.label,
            initialArgb = resolved(role),
            onDismiss = { editingRole = null },
            onSave = { argb ->
                preferences.edit().putInt(key(role), argb).apply()
                revision++
                editingRole = null
            },
        )
    }
}

private fun toHex(argb: Int): String =
    String.format(
        "#%02X%02X%02X%02X",
        AndroidColor.alpha(argb),
        AndroidColor.red(argb),
        AndroidColor.green(argb),
        AndroidColor.blue(argb),
    )

private fun parseHex(value: String): Int? {
    val cleaned = value.removePrefix("#")
    return when (cleaned.length) {
        6 -> cleaned.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
        8 -> cleaned.toLongOrNull(16)?.toInt()
        else -> null
    }
}
