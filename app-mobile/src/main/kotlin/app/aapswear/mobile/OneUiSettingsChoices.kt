package app.aapswear.mobile

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aapswear.model.AppearanceMode

internal fun deviceUsesDarkMode(context: Context): Boolean =
    context.applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

internal fun resolveSettingsAppearance(mode: DashboardThemeMode, deviceDark: Boolean): AppearanceMode =
    when (mode) {
        DashboardThemeMode.LIGHT -> AppearanceMode.LIGHT
        DashboardThemeMode.DARK -> AppearanceMode.DARK
        DashboardThemeMode.SYSTEM -> if (deviceDark) AppearanceMode.DARK else AppearanceMode.LIGHT
    }

internal fun settingsSystemMode(enabled: Boolean, deviceDark: Boolean): DashboardThemeMode =
    if (enabled) {
        DashboardThemeMode.SYSTEM
    } else if (deviceDark) {
        DashboardThemeMode.DARK
    } else {
        DashboardThemeMode.LIGHT
    }

@Composable
internal fun OneUiResetIcon(onClick: () -> Unit, modifier: Modifier = Modifier, description: String = "Zurücksetzen", enabled: Boolean = true) {
    IconButton(onClick, modifier.size(40.dp), enabled = enabled) {
        Icon(painterResource(R.drawable.ic_oneui_reset), description, tint = if (enabled) OneUiWidgetStyle.accent else OneUiWidgetStyle.secondary, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun OneUiSettingsModeSelector(mode: DashboardThemeMode, showSystemChoice: Boolean = true, onSelected: (DashboardThemeMode) -> Unit) {
    LocalConfiguration.current // Observe configuration; read actual device, not the forced menu ContextThemeWrapper.
    val deviceDark = deviceUsesDarkMode(LocalContext.current)
    val resolved = resolveSettingsAppearance(mode, deviceDark)
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf(DashboardThemeMode.LIGHT, DashboardThemeMode.DARK).forEach { option ->
                val selected = resolved == if (option == DashboardThemeMode.LIGHT) AppearanceMode.LIGHT else AppearanceMode.DARK
                Column(
                    Modifier.weight(1f).selectable(selected, role = Role.RadioButton) { onSelected(option) }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OneUiThemeIllustration(option == DashboardThemeMode.DARK)
                    Text(if (option == DashboardThemeMode.LIGHT) "Hell" else "Dunkel", color = if (selected) OneUiWidgetStyle.accent else OneUiWidgetStyle.secondary, fontSize = 18.sp)
                    RadioButton(selected, null)
                }
            }
        }
        if (showSystemChoice) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = OneUiWidgetStyle.secondary.copy(alpha = .2f))
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Geräteabhängig", color = OneUiWidgetStyle.primary, fontSize = 18.sp, modifier = Modifier.weight(1f))
                WidgetSettingsToggle(mode == DashboardThemeMode.SYSTEM, { onSelected(settingsSystemMode(it, deviceDark)) }, label = "Geräteabhängig")
            }
        }
    }
}

@Composable
private fun OneUiThemeIllustration(dark: Boolean) {
    val frame = if (dark) Color(0xFF30353D) else Color(0xFFE7E8EC)
    val card = if (dark) Color(0xFF5B606B) else Color.White
    val glyph = if (dark) Color(0xFFCACED5) else Color(0xFFC6CBD1)
    Canvas(Modifier.width(86.dp).height(166.dp)) {
        val sx = size.width / 86f
        val sy = size.height / 166f

        fun rect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Color) =
            drawRoundRect(color, Offset(x * sx, y * sy), Size(w * sx, h * sy), CornerRadius(radius * sx))
        rect(0f, 0f, 86f, 166f, 8f, frame)
        rect(7f, 10f, 72f, 17f, 9f, card)
        drawCircle(glyph, 6f * sx, Offset(71f * sx, 18.5f * sy))
        drawCircle(Color.White, 2f * sx, Offset(71f * sx, 16.5f * sy))
        rect(68f, 20f, 6f, 3f, 2f, Color.White)
        rect(7f, 33f, 72f, 29f, 6f, card)
        rect(7f, 68f, 72f, 56f, 6f, card)
        rect(7f, 130f, 72f, 29f, 6f, card)
        val dots = listOf(40f to Color(0xFF3880F9), 53f to Color(0xFF3880F9), 76f to Color(0xFF13C3C0), 89f to Color(0xFF7854FF), 102f to Color(0xFF7854FF), 115f to Color(0xFFEE6200), 138f to Color(0xFF9CD92C), 151f to Color(0xFF7FC753))
        dots.forEach { (y, color) -> drawCircle(color, 4f * sx, Offset(16f * sx, y * sy)) }
    }
}

@Composable
internal fun OneUiGlucoseUnitSelector(selected: DisplayUnitPreference, onSelected: (DisplayUnitPreference) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        val options = listOf(DisplayUnitPreference.AAPS to "Wie Datenquelle", DisplayUnitPreference.MG_DL to "mg/dL", DisplayUnitPreference.MMOL_L to "mmol/L")
        options.forEachIndexed { index, (option, label) ->
            if (index > 0) VerticalDivider(Modifier.height(45.dp).padding(top = 6.dp), color = OneUiWidgetStyle.secondary.copy(alpha = .25f))
            Column(
                Modifier.weight(1f).selectable(selected == option, role = Role.RadioButton) { onSelected(option) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(painterResource(R.drawable.settings_ic_glucose_units), null, Modifier.size(28.dp), tint = if (selected == option) OneUiWidgetStyle.accent else OneUiWidgetStyle.secondary)
                Text(label, color = if (selected == option) OneUiWidgetStyle.accent else OneUiWidgetStyle.secondary, fontSize = 14.sp)
                RadioButton(selected == option, null)
            }
        }
    }
}
