package app.aapswear.g7watch

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.aapswear.g7.CollectorCycleTiming
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7Sensor
import app.aapswear.g7.G7SessionManager
import app.aapswear.g7.G7SetupPayload
import app.aapswear.model.DiagnosticSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class G7SystemStatusActivity : Activity() {
    private val diagnosticScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var batteryRequestPending = false
    private var showPairingEditor = false
    private var observerRegistered = false
    private var scrollView: ScrollView? = null
    private val readingObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (!isFinishing && !isDestroyed) render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        registerObserver()
        if (batteryRequestPending) {
            batteryRequestPending = false
            val unrestricted = G7BackgroundAccess.isBatteryUnrestricted(this)
            Toast.makeText(
                this,
                if (unrestricted) "Dauerbetrieb ist uneingeschränkt" else "Akkuoptimierung ist weiterhin aktiv",
                Toast.LENGTH_LONG,
            ).show()
            recordBackgroundDiagnostic(
                if (unrestricted) "G7-BG-200" else "G7-BG-403",
                if (unrestricted) "Battery optimization exemption granted" else "Battery optimization exemption not granted",
                if (unrestricted) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
            )
        }
        render()
    }

    override fun onPause() {
        unregisterObserver()
        super.onPause()
    }

    private fun render() {
        val oldScrollY = scrollView?.scrollY ?: 0
        val palette = G7AppearanceStore(this).load()
        val background = palette.argb(G7AppearanceRole.MENU_BACKGROUND)
        window.statusBarColor = background
        window.navigationBarColor = background

        val state = G7SensorStateStore(this).read()
        val credentials = G7CredentialStore(this).read()
        val userStatus = deriveG7UserStatus(state, credentials != null)
        val diagnostics = G7CollectorDiagnosticStore(this)
        val attempt = diagnostics.snapshot().firstOrNull()
        val cycle = attempt?.cycle ?: diagnostics.pendingScheduledCycle()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18.dp, 8.dp, 18.dp, 30.dp)
            setBackgroundColor(background)
            addView(TextView(this@G7SystemStatusActivity).apply {
                text = "←  Systemstatus"
                textSize = 17f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY))
                setOnClickListener { finish() }
                contentDescription = "Zurück zur Übersicht"
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dp))

            addView(group("SENSOR", palette).apply {
                addView(row("Sensorstatus", state.sensor?.state?.name ?: "—", palette))
                addView(row("Session", state.sensor?.sessionId ?: state.lastReading?.sessionId ?: "—", palette))
                addView(row("Sensorcode", credentials?.pairingCode ?: "—", palette))
                addView(row("GTIN", credentials?.gtin ?: "—", palette))
                addView(row("Seriennummer", credentials?.sensorSerial ?: "—", palette))
                addView(row("Letzter Wert", state.lastReading?.let { "${it.glucoseMgDl.toInt()} · ${formatTimestamp(it.timestampEpochMs)}" } ?: "—", palette))
                addView(row("Sensoralter", state.lastReading?.sensorAgeSeconds?.let(::formatDurationSeconds) ?: "—", palette))
                addView(row("Trendrate", state.lastReading?.trendRateMgDlPerMinute?.let { "%.1f mg/dL/min".format(it) } ?: "—", palette))
                addView(row("Sensor-ID", state.sensor?.sensorId ?: state.lastReading?.sensorId ?: "—", palette))
                addView(row("Sequenz", state.lastReading?.sequenceNumber?.toString() ?: "—", palette))
                addView(row("BLE-Name", state.sensor?.deviceName ?: "—", palette))
                addView(row("Sensorstart", formatTimestamp(state.sensor?.sensorStartEpochMs ?: state.lastReading?.sensorStartEpochMs), palette))
                addView(row("Sensorende", formatTimestamp(state.sensor?.sensorEndEpochMs ?: state.lastReading?.sensorEndEpochMs), palette))
                addView(row("Kulanzende", formatTimestamp(state.sensor?.graceEndEpochMs ?: state.lastReading?.graceEndEpochMs), palette))
            }, cardParams())

            addView(group("VERBINDUNG", palette).apply {
                addView(row("Zustand", userStatus.title, palette))
                addView(row("Status", userStatus.status, palette))
                addView(row("Verbindung", state.connectionState.name, palette))
                addView(row("Collector", if (state.collectorEnabled) "Aktiviert" else "Gestoppt", palette))
                addView(row("Phase", userStatus.phase, palette))
                addView(row("Protokoll", state.protocolState.name, palette))
                addView(row("Letzter Connect", formatTimestamp(state.lastSuccessfulConnectionEpochMs), palette))
                addView(row("Bekannte Adresse", state.sensor?.deviceAddress ?: "Nicht vorhanden", palette))
                addView(row("Reconnect-Strategie", G7ReconnectStrategyStore.read(this@G7SystemStatusActivity).name, palette))
                addView(row("Letzter Fehler", state.lastError?.let { "${it.code} · ${it.safeMessage}" } ?: "—", palette))
                addView(row("Hinweis", userStatus.description, palette))
                addView(row("Empfohlene Aktion", userStatus.action, palette))
            }, cardParams())

            addView(group("ZEITPLANUNG", palette).apply {
                addView(row("Nächster Wert", formatTimestamp(cycle?.expectedReadingEpoch), palette))
                addView(row("Nächster Reconnect", formatTimestamp(state.nextReconnectEpochMs ?: cycle?.requestedReconnectEpoch), palette))
                addView(row("Alarm", cycle?.alarmKind?.name ?: "—", palette))
                addView(row("Exact Alarm", if (canScheduleExactReconnects()) "Erlaubt" else "Nicht erlaubt", palette))
                addView(row("Akkuoptimierung", if (G7BackgroundAccess.isBatteryUnrestricted(this@G7SystemStatusActivity)) "Uneingeschränkt" else "Optimiert", palette))
                addView(row("Geräte in der Nähe", if (hasNearbyPermission()) "Erlaubt" else "Nicht erlaubt", palette))
                addView(row("Benachrichtigungen", if (hasNotificationPermission()) "Erlaubt" else "Nicht erlaubt", palette))
                addView(row("Retry", state.retryCount.toString(), palette))
            }, cardParams())

            addView(group("KOMMUNIKATION", palette).apply {
                addCycleRows(this, cycle, palette)
            }, cardParams())

            addView(group("DIAGNOSE", palette).apply {
                val lastEvent = attempt?.events?.maxByOrNull { it.timestampEpochMs }
                addView(row("Aktiver Attempt", state.activeAttemptId?.toString() ?: "—", palette))
                addView(row("Attempt-Alter", attempt?.takeIf { it.completedAtEpochMs == null }?.let { formatDurationMs(System.currentTimeMillis() - it.startedAtEpochMs) } ?: "—", palette))
                addView(row("Letztes Ergebnis", attempt?.result?.name ?: "—", palette))
                addView(row("Klassifikation", attempt?.classification?.name ?: "—", palette))
                addView(row("Letzte Stufe", lastEvent?.stage?.name ?: "—", palette))
                addView(row("Fehlercode", lastEvent?.errorCode ?: state.lastError?.code ?: "—", palette))
                addView(row("Letzte Meldung", lastEvent?.message ?: userStatus.description, palette))
                addView(row("Slot-Strategie", cycle?.slotStrategy?.name ?: "—", palette))
                addView(row("Radio-Fehlerfolge", cycle?.radioFailureStreak?.toString() ?: "0", palette))
                addView(row("Radio-Cluster", if (cycle?.radioDegradedCluster == true) "Aktiv" else "Nein", palette))
            }, cardParams())

            addView(group("AKTIONEN", palette).apply {
                addActionRows(this, state, palette)
            }, cardParams())

            addView(label(
                "Nur einen direkten G7-Collector gleichzeitig verwenden. Juggluco oder xDrip vorher beenden.",
                9f,
                palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY),
            ).apply { setPadding(8.dp, 12.dp, 8.dp, 0) })
            addView(label(
                "Der Collector verbindet sich nur im erwarteten Sensorfenster und beendet die Verbindung danach wieder.",
                9f,
                palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY),
            ).apply { setPadding(8.dp, 6.dp, 8.dp, 0) })
        }

        scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(background)
            addView(content)
        }
        setContentView(scrollView)
        scrollView?.post { scrollView?.scrollTo(0, oldScrollY) }
    }

    private fun addCycleRows(target: LinearLayout, cycle: CollectorCycleTiming?, palette: G7AppearancePalette) {
        target.addView(row("Letzter Versuch", formatTimestamp(cycle?.receiverReceivedAt ?: cycle?.serviceOnStartCommandAt), palette))
        target.addView(row("Advertisement", formatTimestamp(cycle?.advertisementFoundAt), palette))
        target.addView(row("RSSI", cycle?.advertisementRssi?.let { "$it dBm" } ?: "—", palette))
        target.addView(row("GATT Start", formatTimestamp(cycle?.connectGattStartedAt), palette))
        target.addView(row("Direct-Ergebnis", cycle?.directConnectResult?.name ?: "—", palette))
        target.addView(row("Direct-Versuche", cycle?.directConnectAttempts?.toString() ?: "—", palette))
        target.addView(row("Direct-Status", cycle?.directConnectStatus?.toString() ?: "—", palette))
        target.addView(row("Scan beendet", formatTimestamp(cycle?.scanEndedAt), palette))
        target.addView(row("Scan-Ergebnisse", cycle?.scanTotalResults?.toString() ?: "—", palette))
        target.addView(row("G7 / bekannte Adresse", "${cycle?.scanNamedG7Results ?: "—"} / ${cycle?.scanExactAddressResults ?: "—"}", palette))
        target.addView(row("Scan-RSSI", cycle?.scanMinRssi?.let { "$it..${cycle.scanMaxRssi ?: it} dBm" } ?: "—", palette))
        target.addView(row("GATT verbunden", formatTimestamp(cycle?.gattConnectedAt), palette))
        target.addView(row("Auth Start", formatTimestamp(cycle?.authStartedAt), palette))
        target.addView(row("Auth Erfolg", formatTimestamp(cycle?.authSucceededAt), palette))
        target.addView(row("Glukose empfangen", formatTimestamp(cycle?.glucosePacketReceivedAt), palette))
        target.addView(row("Zyklusende", formatTimestamp(cycle?.cycleEndedAt), palette))
    }

    private fun addActionRows(target: LinearLayout, state: G7PersistedState, palette: G7AppearancePalette) {
        val nearbyAllowed = hasNearbyPermission()
        val notificationsAllowed = hasNotificationPermission()
        if (!nearbyAllowed || !notificationsAllowed) target.addView(pill("Berechtigungen freigeben", palette) { requestMissingPermissions() }, buttonParams())
        if (!G7BackgroundAccess.isBatteryUnrestricted(this)) target.addView(pill("Dauerbetrieb freigeben", palette) { requestBatteryExemption() }, buttonParams())
        if (!canScheduleExactReconnects()) target.addView(pill("Präzise Sensor-Abfragen freigeben", palette) { requestExactAlarmAccess() }, buttonParams())
        target.addView(pill(if (state.sensor == null) "Sensor einrichten" else "Sensor neu koppeln", palette) {
            showPairingEditor = !showPairingEditor
            render()
        }, buttonParams())
        if (showPairingEditor || state.sensor == null) target.addView(pairingEditor(palette), buttonParams())
        target.addView(pill(if (state.collectorEnabled) "Collector stoppen" else "Collector starten", palette, danger = state.collectorEnabled) {
            if (state.collectorEnabled) G7CollectorService.stop(this) else G7CollectorService.start(this)
            Handler(Looper.getMainLooper()).postDelayed({ render() }, 350L)
        }, buttonParams())
    }

    private fun hasNearbyPermission(): Boolean =
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun pairingEditor(palette: G7AppearancePalette) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(9.dp, 9.dp, 9.dp, 9.dp)
        background = rounded(palette.argb(G7AppearanceRole.MENU_BACKGROUND), palette.argb(G7AppearanceRole.MENU_BORDER), 16f)
        val input = EditText(this@G7SystemStatusActivity).apply {
            hint = "0000"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY))
            setHintTextColor(palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY))
            textSize = 19f
            gravity = Gravity.CENTER
            background = rounded(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_BORDER), 999f)
        }
        addView(label("Vierstelliger Code vom G7-Applikator", 10f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY)))
        addView(input, buttonParams())
        addView(pill("Sensorcode speichern", palette) {
            val payload = runCatching { G7SetupPayload(input.text?.toString().orEmpty()) }.getOrNull()
            if (payload == null) {
                input.error = "4 Ziffern erforderlich"
                return@pill
            }
            G7CredentialStore(this@G7SystemStatusActivity).saveSetup(payload)
            val sensorId = "G7-${UUID.randomUUID().toString().take(8)}"
            val sensor = G7Sensor(sensorId, sensorId, "Dexcom G7")
            G7SensorStateStore(this@G7SystemStatusActivity).save(
                G7SessionManager(G7SensorStateStore(this@G7SystemStatusActivity).read()).prepareInitialSetup(sensor),
            )
            showPairingEditor = false
            render()
        }, buttonParams())
    }

    private fun group(title: String, palette: G7AppearancePalette) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        background = rounded(palette.argb(G7AppearanceRole.MENU_SURFACE), palette.argb(G7AppearanceRole.MENU_BORDER), 22f)
        addView(label(title, 9.5f, palette.argb(G7AppearanceRole.MENU_PRIMARY), true).apply {
            gravity = Gravity.START
            letterSpacing = 0.10f
        })
    }

    private fun row(title: String, value: String, palette: G7AppearancePalette) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(title, 9.5f, palette.argb(G7AppearanceRole.MENU_TEXT_SECONDARY)).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label(value, 9.5f, palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY), true).apply {
            gravity = Gravity.END
            maxLines = 4
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f))
    }

    private fun pill(text: String, palette: G7AppearancePalette, danger: Boolean = false, action: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 11f
        gravity = Gravity.CENTER
        minHeight = 40.dp
        setPadding(13.dp, 8.dp, 13.dp, 8.dp)
        setTypeface(typeface, Typeface.BOLD)
        val color = if (danger) palette.argb(G7AppearanceRole.GLUCOSE_ERROR) else palette.argb(G7AppearanceRole.MENU_TEXT_PRIMARY)
        setTextColor(color)
        background = rounded(
            if (danger) withAlpha(color, 36) else palette.argb(G7AppearanceRole.MENU_SURFACE),
            if (danger) color else palette.argb(G7AppearanceRole.MENU_BORDER),
            999f,
        )
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(1.dp, stroke)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun requestMissingPermissions() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_SCAN)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) render()
    }

    private fun canScheduleExactReconnects(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun requestBatteryExemption() {
        if (G7BackgroundAccess.isBatteryUnrestricted(this)) return
        batteryRequestPending = true
        if (G7BackgroundAccess.openBatterySettings(this)) return
        batteryRequestPending = false
        Toast.makeText(this, "Akku-Einstellungen konnten nicht geöffnet werden", Toast.LENGTH_LONG).show()
        recordBackgroundDiagnostic("G7-BG-404", "Battery optimization settings could not be opened", DiagnosticSeverity.ERROR)
    }

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:$packageName"))) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) } }
    }

    private fun recordBackgroundDiagnostic(code: String, message: String, severity: DiagnosticSeverity) {
        diagnosticScope.launch { applicationContext.recordG7Diagnostic(code, message, severity) }
    }

    private fun registerObserver() {
        if (observerRegistered) return
        contentResolver.registerContentObserver(G7ReadingProvider.CONTENT_URI, true, readingObserver)
        observerRegistered = true
    }

    private fun unregisterObserver() {
        if (!observerRegistered) return
        contentResolver.unregisterContentObserver(readingObserver)
        observerRegistered = false
    }

    override fun onDestroy() {
        unregisterObserver()
        diagnosticScope.cancel()
        super.onDestroy()
    }

    private fun formatTimestamp(value: Long?): String =
        value?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "—"

    private fun formatDurationSeconds(value: Long): String = formatDurationMs(value * 1_000L)

    private fun formatDurationMs(value: Long): String {
        val seconds = value.coerceAtLeast(0L) / 1_000L
        return if (seconds < 120L) "$seconds s" else "${seconds / 60L} min"
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    private fun cardParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 7.dp }
    private fun buttonParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 7.dp }
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val PERMISSION_REQUEST = 17
    }
}
