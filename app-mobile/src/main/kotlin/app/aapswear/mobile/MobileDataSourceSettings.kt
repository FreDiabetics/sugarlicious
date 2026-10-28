package app.aapswear.mobile

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.aapswear.datasource.dexcom.DexcomBroadcastAdapter

/** Embedded in General, sharing its scroll and header instead of launching another window. */
@Composable internal fun MobileDataSourceSettingsPanel() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE) }
    val diagnostics = remember { context.getSharedPreferences("source_diagnostics", Context.MODE_PRIVATE) }
    var configuration by remember { mutableStateOf(MobileSourceConfigurationStore.read(context)) }
    var status by remember { mutableStateOf(diagnostics.getString("dexcom_share", "").orEmpty()) }
    DisposableEffect(prefs, diagnostics) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                configuration = MobileSourceConfigurationStore.read(context)
                status = diagnostics.getString("dexcom_share", "").orEmpty()
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        diagnostics.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            diagnostics.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) MobileSourceConfigurationStore.setGlucoseEnabled(context, MobileGlucoseSource.DEXCOM_BROADCAST, true)
        }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (configuration.enabledGlucoseSources.size > 1) {
            Text("Mehrere Glukosequellen aktiv: " + configuration.enabledGlucoseSources.sortedBy { it.ordinal }.joinToString { it.label } + ". Verwendet wird der frischeste gültige Messwert.", color = OneUiWidgetStyle.primary, fontSize = 14.sp)
        }
        val aapsActive = MobileGlucoseSource.ANDROID_APS in configuration.enabledGlucoseSources || configuration.aapsTherapyEnabled
        SourceCard("AndroidAPS", R.drawable.ic_source_androidaps, aapsActive, { MobileSourceConfigurationStore.setAndroidApsEnabled(context, it) }) {
            WidgetToggleSetting("Glukosewerte", MobileGlucoseSource.ANDROID_APS in configuration.enabledGlucoseSources) { MobileSourceConfigurationStore.setGlucoseEnabled(context, MobileGlucoseSource.ANDROID_APS, it) }
            WidgetToggleSetting("Therapiedaten", configuration.aapsTherapyEnabled) { MobileSourceConfigurationStore.setTherapy(context, it) }
        }
        SourceCard("Dexcom G7 Broadcast", R.drawable.ic_health_glucose, MobileGlucoseSource.DEXCOM_BROADCAST in configuration.enabledGlucoseSources, { enabled ->
            if (enabled && ContextCompat.checkSelfPermission(context, DexcomBroadcastAdapter.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(DexcomBroadcastAdapter.PERMISSION)
            } else {
                MobileSourceConfigurationStore.setGlucoseEnabled(context, MobileGlucoseSource.DEXCOM_BROADCAST, enabled)
            }
        })
        SourceCard("Dexcom Share", R.drawable.ic_source_share, MobileGlucoseSource.DEXCOM_SHARE in configuration.enabledGlucoseSources, { MobileSourceConfigurationStore.setGlucoseEnabled(context, MobileGlucoseSource.DEXCOM_SHARE, it) }) {
            ShareCredentialsFields(status)
        }
    }
}

@Composable private fun SourceCard(title: String, icon: Int, active: Boolean, onActive: (Boolean) -> Unit, content: (@Composable () -> Unit)? = null) {
    Surface(color = OneUiWidgetStyle.card, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), null, tint = OneUiWidgetStyle.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(title, color = OneUiWidgetStyle.primary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                WidgetSettingsToggle(active, onActive, true, title)
            }
            AnimatedVisibility(active && content != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = OneUiWidgetStyle.secondary.copy(alpha = .16f))
                    content?.invoke()
                }
            }
        }
    }
}

@Composable private fun ShareCredentialsFields(status: String) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(DexcomShareCredentialsStore.read(context)) }
    var account by remember { mutableStateOf(saved?.account.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(saved?.region ?: "ous") }
    var feedback by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(saved == null) }
    if (!editing) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Dexcom-Konto", color = OneUiWidgetStyle.primary, fontSize = 16.sp)
                Text(saved?.account.orEmpty(), color = OneUiWidgetStyle.secondary, fontSize = 14.sp)
            }
            TextButton(onClick = { editing = true }) { Text("Ändern") }
        }
        Text(if (saved?.region == "us") "USA" else "International", color = OneUiWidgetStyle.secondary, fontSize = 14.sp)
        if (status.isNotBlank()) Text(status, color = OneUiWidgetStyle.secondary, fontSize = 13.sp)
        return
    }
    val fieldColor = OneUiWidgetStyle.secondary.copy(alpha = .08f)
    val colors =
        TextFieldDefaults.colors(
            focusedContainerColor = fieldColor,
            unfocusedContainerColor = fieldColor,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedTextColor = OneUiWidgetStyle.primary,
            unfocusedTextColor = OneUiWidgetStyle.primary,
            focusedLabelColor = OneUiWidgetStyle.secondary,
            unfocusedLabelColor = OneUiWidgetStyle.secondary,
        )
    TextField(
        account,
        { account = it },
        label = { Text("Benutzername", fontSize = 13.sp) },
        textStyle =
            androidx.compose.ui.text
                .TextStyle(fontSize = 16.sp),
        shape = RoundedCornerShape(16.dp),
        singleLine = true,
        colors = colors,
        modifier = Modifier.fillMaxWidth(),
    )
    TextField(
        password,
        { password = it },
        label = { Text(if (saved == null) "Passwort" else "Passwort gespeichert", fontSize = 13.sp) },
        textStyle =
            androidx.compose.ui.text
                .TextStyle(fontSize = 16.sp),
        shape = RoundedCornerShape(16.dp),
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        colors = colors,
        modifier = Modifier.fillMaxWidth(),
    )
    OneUiWidgetChoiceRow("Region", region, listOf("ous", "us"), { if (it == "us") "USA" else "International" }) { region = it }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = {
            account = saved?.account.orEmpty()
            password = ""
            region = saved?.region ?: "ous"
            editing = false
        }) { Text("Abbrechen") }
        TextButton(onClick = {
            val secret = password.ifBlank { saved?.takeIf { it.account == account.trim() }?.password.orEmpty() }
            feedback =
                if (account.isBlank() || secret.isBlank()) {
                    "Benutzername und Passwort fehlen"
                } else {
                    runCatching {
                        DexcomShareCredentialsStore.save(context, account, secret, region)
                        saved = DexcomShareCredentialsStore.read(context)
                        password = ""
                        editing = false
                        "Zugang gespeichert"
                    }.getOrDefault("Zugang konnte nicht sicher gespeichert werden")
                }
        }) { Text("Speichern", fontWeight = FontWeight.Bold) }
    }
    if (feedback.isNotBlank()) Text(feedback, color = OneUiWidgetStyle.secondary, fontSize = 13.sp)
}
