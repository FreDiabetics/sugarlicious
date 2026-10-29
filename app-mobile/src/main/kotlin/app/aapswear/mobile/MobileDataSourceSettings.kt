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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.aapswear.datasource.dexcom.DexcomBroadcastAdapter

/** Embedded in General, sharing its scroll and header instead of launching another window. */
@Composable internal fun MobileDataSourceSettingsPanel(expandedCards: MutableMap<String, Boolean>) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE) }
    val diagnostics = remember { context.getSharedPreferences("source_diagnostics", Context.MODE_PRIVATE) }
    var configuration by remember { mutableStateOf(MobileSourceConfigurationStore.read(context)) }
    var status by remember { mutableStateOf(diagnostics.getString("dexcom_share", "").orEmpty()) }
    var nightscoutStatus by remember { mutableStateOf(diagnostics.getString("nightscout_cgm", "").orEmpty()) }
    DisposableEffect(prefs, diagnostics) {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                configuration = MobileSourceConfigurationStore.read(context)
                status = diagnostics.getString("dexcom_share", "").orEmpty()
                nightscoutStatus = diagnostics.getString("nightscout_cgm", "").orEmpty()
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
            if (granted) MobileSourceConfigurationStore.setDexcom(context, MobileGlucoseSource.DEXCOM_BROADCAST, true)
        }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (configuration.enabledGlucoseSources.size > 1) {
            Surface(color = OneUiWidgetStyle.card, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(app.aapswear.uishared.R.drawable.ic_alert), "Warnung", tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(28.dp))
                    Text("Mehrere Glukosequellen aktiv: " + configuration.enabledGlucoseSources.sortedBy { it.ordinal }.joinToString { it.label } + ". Verwendet wird der frischeste gültige Messwert.", color = OneUiWidgetStyle.primary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        val aapsActive = MobileGlucoseSource.ANDROID_APS in configuration.enabledGlucoseSources || configuration.aapsTherapyEnabled
        SourceCard("AndroidAPS", R.drawable.ic_source_androidaps, aapsActive, { MobileSourceConfigurationStore.setAndroidApsEnabled(context, it) }, expandedCards) {
            WidgetToggleSetting("Glukosewerte", MobileGlucoseSource.ANDROID_APS in configuration.enabledGlucoseSources) { MobileSourceConfigurationStore.setGlucoseEnabled(context, MobileGlucoseSource.ANDROID_APS, it) }
            WidgetToggleSetting("Therapiedaten", configuration.aapsTherapyEnabled) { MobileSourceConfigurationStore.setTherapy(context, it) }
        }
        val dexcom = MobileSourceConfigurationStore.preferredDexcom(context)
        val dexcomActive = configuration.enabledGlucoseSources.any { it == MobileGlucoseSource.DEXCOM_BROADCAST || it == MobileGlucoseSource.DEXCOM_SHARE }

        fun selectDexcom(source: MobileGlucoseSource, enabled: Boolean) {
            if (enabled && source == MobileGlucoseSource.DEXCOM_BROADCAST && ContextCompat.checkSelfPermission(context, DexcomBroadcastAdapter.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(DexcomBroadcastAdapter.PERMISSION)
            } else {
                MobileSourceConfigurationStore.setDexcom(context, source, enabled)
            }
        }
        SourceCard("BYODA", if (dexcom == MobileGlucoseSource.DEXCOM_SHARE) R.drawable.ic_dexcomfollow else R.drawable.ic_dexcomapp, dexcomActive, { selectDexcom(dexcom, it) }, expandedCards) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(MobileGlucoseSource.DEXCOM_BROADCAST, MobileGlucoseSource.DEXCOM_SHARE).forEach { source ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painterResource(if (source == MobileGlucoseSource.DEXCOM_SHARE) R.drawable.ic_dexcomfollow else R.drawable.ic_dexcomapp), null, tint = OneUiWidgetStyle.primary, modifier = Modifier.size(36.dp))
                        Text(source.label, color = OneUiWidgetStyle.primary, fontSize = 14.sp)
                        RadioButton(selected = dexcom == source, onClick = { selectDexcom(source, true) })
                    }
                }
            }
            if (dexcom == MobileGlucoseSource.DEXCOM_SHARE) ShareCredentialsFields(status)
        }
        val nightscout = NightscoutConfigurationStore.read(context)
        val nightscoutGlucose = MobileGlucoseSource.NIGHTSCOUT in configuration.enabledGlucoseSources
        SourceCard("Nightscout", R.drawable.ic_source_nightscout, nightscoutGlucose || nightscout.enabled, { MobileSourceConfigurationStore.setNightscoutEnabled(context, it) }, expandedCards) {
            WidgetToggleSetting("Glukosewerte", nightscoutGlucose) { MobileSourceConfigurationStore.setGlucoseEnabled(context, MobileGlucoseSource.NIGHTSCOUT, it) }
            WidgetToggleSetting("Therapiedaten", nightscout.enabled, enabled = nightscout.isValid() || nightscout.enabled) { NightscoutConfigurationStore.save(context, nightscout.copy(enabled = it), null) }
            Text("Nightscout ergänzt Boli und Kohlenhydrate. IOB, COB und Basal liefert weiterhin AndroidAPS.", color = OneUiWidgetStyle.secondary, fontSize = 13.sp)
            NightscoutAccessFields(if (nightscoutGlucose) nightscoutStatus else "")
        }
        SourceCard("xDrip+", R.drawable.ic_source_xdrip, MobileGlucoseSource.XDRIP_PLUS in configuration.enabledGlucoseSources, { MobileSourceConfigurationStore.setGlucoseEnabled(context, MobileGlucoseSource.XDRIP_PLUS, it) }, expandedCards, originalIconColors = true) {
            Text("In xDrip+ unter Inter-App-Einstellungen »Lokal senden« und »Kompatibler Broadcast« aktivieren. Als Empfänger app.aapswear eintragen oder das Empfängerfeld leer lassen.", color = OneUiWidgetStyle.secondary, fontSize = 14.sp)
        }
        SourceCard("Libre patched app", R.drawable.ic_librepatched, MobileGlucoseSource.LIBRE_PATCHED in configuration.enabledGlucoseSources, { MobileSourceConfigurationStore.setGlucoseEnabled(context, MobileGlucoseSource.LIBRE_PATCHED, it) }, expandedCards) {
            Text("Empfängt berechnete Scanwerte und den dabei mitgelieferten Verlauf der gepatchten Libre-App. Für kontinuierliche, aufbereitete Werte xDrip+ verwenden; ungeglättete Live-Rohwerte werden nicht übernommen.", color = OneUiWidgetStyle.secondary, fontSize = 14.sp)
        }
    }
}

@Composable private fun SourceCard(title: String, icon: Int, active: Boolean, onActive: (Boolean) -> Unit, expandedCards: MutableMap<String, Boolean>, originalIconColors: Boolean = false, content: (@Composable () -> Unit)? = null) {
    val expanded = expandedCards[title] == true
    val nameInteraction = remember { MutableInteractionSource() }
    val namePressed by nameInteraction.collectIsPressedAsState()
    Surface(color = OneUiWidgetStyle.card, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), null, tint = if (originalIconColors) androidx.compose.ui.graphics.Color.Unspecified else OneUiWidgetStyle.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    Text(title, color = OneUiWidgetStyle.primary, fontSize = 16.sp, modifier = Modifier.background(if (namePressed) OneUiWidgetStyle.primary.copy(alpha = .10f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(4.dp)).semantics { stateDescription = if (expanded) "Geöffnet" else "Geschlossen" }.clickable(interactionSource = nameInteraction, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (expanded) "Zuklappen" else "Aufklappen") { expandedCards[title] = !expanded })
                }
                WidgetSettingsToggle(active, {
                    expandedCards[title] = it
                    onActive(it)
                }, true, title)
            }
            AnimatedVisibility(expanded && content != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
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

@Composable private fun NightscoutAccessFields(status: String) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(NightscoutConfigurationStore.read(context)) }
    var url by remember { mutableStateOf(saved.baseUrl) }
    var secret by remember { mutableStateOf("") }
    var authMode by remember { mutableStateOf(saved.authMode) }
    var editing by remember { mutableStateOf(!saved.isValid()) }
    var feedback by remember { mutableStateOf("") }
    if (!editing) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nightscout-Server", color = OneUiWidgetStyle.primary, fontSize = 16.sp)
                Text(saved.normalizedBaseUrl, color = OneUiWidgetStyle.secondary, fontSize = 14.sp)
            }
            TextButton(onClick = { editing = true }) { Text("Ändern") }
        }
        if (status.isNotBlank()) Text(status, color = OneUiWidgetStyle.secondary, fontSize = 13.sp)
        return
    }
    val colors = TextFieldDefaults.colors(focusedContainerColor = OneUiWidgetStyle.secondary.copy(alpha = .08f), unfocusedContainerColor = OneUiWidgetStyle.secondary.copy(alpha = .08f), focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, focusedTextColor = OneUiWidgetStyle.primary, unfocusedTextColor = OneUiWidgetStyle.primary)
    TextField(url, { url = it }, label = { Text("HTTPS-Adresse") }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = colors, modifier = Modifier.fillMaxWidth())
    OneUiWidgetChoiceRow("Zugang", authMode, NightscoutAuthMode.entries, { if (it == NightscoutAuthMode.API_SECRET) "API-Secret" else "Zugriffstoken" }) { authMode = it }
    TextField(secret, { secret = it }, label = { Text("Zugang gespeichert / neu eingeben") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, shape = RoundedCornerShape(16.dp), colors = colors, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = {
            url = saved.baseUrl
            secret = ""
            authMode = saved.authMode
            editing = false
        }) { Text("Abbrechen") }
        TextButton(onClick = {
            val candidate = NightscoutConfigurationStore.read(context).copy(baseUrl = url, authMode = authMode)
            feedback =
                if (!candidate.isValid()) {
                    "Gültige HTTPS-Adresse ohne Zugangsdaten in der URL erforderlich"
                } else {
                    runCatching {
                        require((authMode == saved.authMode && candidate.normalizedBaseUrl == saved.normalizedBaseUrl) || secret.isNotBlank() || NightscoutSecretStore.read(context).isNullOrBlank()) { "Für den geänderten Server neuen Zugang eingeben" }
                        NightscoutConfigurationStore.save(context, candidate, secret.takeIf { it.isNotBlank() })
                        saved = candidate
                        secret = ""
                        editing = false
                        "Zugang gespeichert"
                    }.getOrDefault("Zugang konnte nicht sicher gespeichert werden")
                }
        }) { Text("Speichern", fontWeight = FontWeight.Bold) }
    }
    if (feedback.isNotBlank()) Text(feedback, color = OneUiWidgetStyle.secondary, fontSize = 13.sp)
}
