package app.aapswear.g7watch

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import app.aapswear.g7.CgmReading
import app.aapswear.g7.CgmAlarmSettings
import app.aapswear.g7.CollectorDiagnosticAttempt
import app.aapswear.g7.CollectorDiagnosticResult
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7ProtocolState
import app.aapswear.g7.G7Sensor
import app.aapswear.g7.G7SessionManager
import app.aapswear.g7.G7SetupPayload
import app.aapswear.model.DiagnosticSeverity
import app.aapswear.model.Trend
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class G7WatchActivity : ComponentActivity() {
    private enum class Screen {
        MAIN,
        SYSTEM,
        SENSOR,
        APP,
        ALARMS,
        READINGS,
        COMMUNICATION,
        ATTEMPT,
        DIAGNOSIS,
        SETUP,
    }

    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uiHandler = Handler(Looper.getMainLooper())
    private val navigation = ArrayDeque<Screen>()
    private var screen = Screen.MAIN
    private var selectedAttemptId: Long? = null
    private var readings: List<CgmReading> = emptyList()
    private var attempts: List<CollectorDiagnosticAttempt> = emptyList()
    private var loading = false
    private var resumed = false
    private var batteryRequestPending = false
    private var liveStatusView: TextView? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            render()
        }

    private val uiTicker =
        object : Runnable {
            override fun run() {
                if (!resumed) return
                liveStatusView?.text = scanDetail(G7SensorStateStore(this@G7WatchActivity).read())
                uiHandler.postDelayed(this, UI_TICK_MS)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = navigateBack()
            },
        )
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handleBatterySettingsResult()
        render()
        refreshCaches()
        uiHandler.removeCallbacks(uiTicker)
        uiHandler.post(uiTicker)
    }

    override fun onPause() {
        resumed = false
        uiHandler.removeCallbacks(uiTicker)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
        refreshCaches()
    }

    private fun refreshCaches() {
        if (loading) return
        loading = true
        workScope.launch {
            val loadedReadings =
                G7ReadingDatabase(applicationContext).let { database ->
                    try {
                        database.query(limit = MAX_HISTORY_ROWS)
                    } finally {
                        database.close()
                    }
                }
            val loadedAttempts = G7CollectorDiagnosticStore(applicationContext).snapshot()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                readings = loadedReadings
                attempts = loadedAttempts
                loading = false
                render()
            }
        }
    }

    private fun render() {
        liveStatusView = null
        val state = G7SensorStateStore(this).read()
        val credentials = G7CredentialStore(this).read()
        val status = deriveG7UserStatus(state, credentials != null)
        val content = container()
        when (screen) {
            Screen.MAIN -> mainScreen(content, state, status)
            Screen.SYSTEM -> systemScreen(content, state, status)
            Screen.SENSOR -> sensorScreen(content, state, credentials != null, credentials?.sharedKey != null)
            Screen.APP -> appScreen(content, state)
            Screen.ALARMS -> alarmScreen(content, state)
            Screen.READINGS -> readingsScreen(content)
            Screen.COMMUNICATION -> communicationScreen(content, state)
            Screen.ATTEMPT -> attemptScreen(content)
            Screen.DIAGNOSIS -> diagnosisScreen(content, state, status)
            Screen.SETUP -> setupScreen(content, state.sensor != null)
        }
        setContentView(
            ScrollView(this).apply {
                isFillViewport = true
                setBackgroundColor(BACKGROUND)
                addView(content)
            },
        )
    }

    private fun container() =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(22.dp, 22.dp, 22.dp, 38.dp)
            setBackgroundColor(BACKGROUND)
        }

    private fun mainScreen(content: LinearLayout, state: G7PersistedState, status: G7UserStatus) {
        content.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_g7_sensor)
                contentDescription = "G7 Sensor"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            },
            LinearLayout.LayoutParams(76.dp, 76.dp),
        )
        content.addView(label("G7 Direct to Watch", 20f, TEXT_PRIMARY, true))
        content.addView(label("by Sugarlicious", 11f, TEXT_SECONDARY, true).apply { letterSpacing = 0.08f })

        val reading = state.lastReading
        val tile = g7TilePresentation(reading, G7GraphColorStore(this).read(), System.currentTimeMillis())
        content.addView(
            card(tile.background, tile.background).apply {
                setPadding(16.dp, 20.dp, 16.dp, 18.dp)
                addView(label(tile.value, 35f, tile.foreground, true))
                addView(label(tile.meta, 14f, tile.foreground, true))
                addView(label(tile.age, 11f, tile.foreground))
            },
            cardParams(),
        )
        content.addView(statusPill(status, displayStatus(state, status)))
        content.addView(scanCard(state), cardParams())
        content.addView(
            card().apply {
                addView(section("3-STUNDEN-VERLAUF"))
                addView(
                    G7GlucoseChart(this@G7WatchActivity).apply {
                        contentDescription = "G7 Glukoseverlauf der letzten drei Stunden"
                        update(currentG7SessionReadings(readings, state.sensor))
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 132.dp).apply { topMargin = 6.dp },
                )
            },
            cardParams(),
        )
        content.addView(navCard("◎", "Systemstatus", "Collector, Sensor, Empfang und Diagnose") { navigate(Screen.SYSTEM) }, cardParams())
        content.addView(
            navCard(
                "⌁",
                if (state.sensor == null) "Sensor koppeln" else "Sensor neu koppeln",
                "Geschützten Pairing-Flow öffnen",
            ) { navigate(Screen.SETUP) },
            cardParams(),
        )
        content.addView(
            actionButton(if (state.collectorEnabled) "Collector stoppen" else "Collector starten", !state.collectorEnabled) {
                if (state.collectorEnabled) G7CollectorService.stop(this) else G7CollectorService.start(this)
                scheduleRefresh()
            },
            buttonParams(),
        )
        content.addView(label("Nur einen direkten G7-Collector gleichzeitig verwenden.", 9f, TEXT_SECONDARY))
    }

    private fun systemScreen(content: LinearLayout, state: G7PersistedState, status: G7UserStatus) {
        header(content, "Systemstatus")
        val summary = summary()
        content.addView(
            rowsCard(
                "Collector" to if (state.collectorEnabled) "Aktiviert" else "Gestoppt",
                "FGS" to if (foregroundVisible()) "Aktiv" else "Nicht sichtbar",
                "Status" to displayStatus(state, status),
                "Sensor" to (state.sensor?.state?.name ?: "Nicht eingerichtet"),
                "BLE" to state.connectionState.name,
                "Letzter Wert" to timestamp(state.lastReading?.timestampEpochMs),
                "Nächster Versuch" to timestamp(state.nextReconnectEpochMs),
                "Letzter Scan" to timestamp(state.lastScanAtEpochMs),
                "Letzter Connect" to timestamp(state.lastSuccessfulConnectionEpochMs),
                "Verpasste Fenster" to summary.missedExpectedWindows.toString(),
                "Letzter Fehler" to (state.lastError?.code ?: "Keiner"),
            ),
            cardParams(),
        )
        content.addView(scanCard(state), cardParams())
        content.addView(navCard("⌁", "Sensor", "Kopplung, Session und sichere Dokumentation") { navigate(Screen.SENSOR) }, cardParams())
        content.addView(navCard("⚙", "App-Betrieb", "FGS, Berechtigungen, Akku und Alarme") { navigate(Screen.APP) }, cardParams())
        content.addView(navCard("▦", "Empfangene Werte", "Verlauf, Lücken und Validierung") { navigate(Screen.READINGS) }, cardParams())
        content.addView(navCard("⇄", "Kommunikation", "Letzte 50 Collection-Versuche") { navigate(Screen.COMMUNICATION) }, cardParams())
        content.addView(navCard("⊙", "Diagnose", "Strukturierter Zustand ohne Logcat") { navigate(Screen.DIAGNOSIS) }, cardParams())
        content.addView(actionButton("↻  Collector neu starten", false) { restartCollector(state) }, buttonParams())
        content.addView(actionButton("⌕  Jetzt nach Sensor suchen", false) { manualScan(state) }, buttonParams())
        content.addView(actionButton("⊘  Sensor entkoppeln", false) { confirmUnlink() }, buttonParams())
    }

    private fun sensorScreen(
        content: LinearLayout,
        state: G7PersistedState,
        credentialsPresent: Boolean,
        sharedKeyPresent: Boolean,
    ) {
        header(content, "Sensor")
        val sensor = state.sensor
        val reading = state.lastReading
        content.addView(
            card().apply {
                addView(section("SICHERE SENSOR-DOKUMENTATION"))
                addRows(
                    "Sensorcode" to if (credentialsPresent) "Gespeichert / verschlüsselt" else "Nicht gespeichert",
                    "Shared Key" to if (sharedKeyPresent) "Gespeichert / verschlüsselt" else "Noch nicht vorhanden",
                    "Sensor-ID" to safeId(sensor?.sensorId ?: reading?.sensorId),
                    "Session-ID" to safeId(sensor?.sessionId ?: reading?.sessionId),
                    "BLE-Name" to (sensor?.deviceName ?: "—"),
                    "BLE-Adresse" to maskBluetoothAddress(sensor?.deviceAddress),
                    "Sensorstatus" to (sensor?.state?.name ?: "—"),
                    "Abgeleiteter Start" to timestamp(sensor?.sensorStartEpochMs ?: reading?.sensorStartEpochMs),
                    "Reguläres Ende" to timestamp(sensor?.sensorEndEpochMs ?: reading?.sensorEndEpochMs),
                    "Kulanzende" to timestamp(sensor?.graceEndEpochMs ?: reading?.graceEndEpochMs),
                    "Letzte Sequenz" to (reading?.sequenceNumber?.toString() ?: "—"),
                    "Quelle" to (reading?.source?.name ?: "—"),
                )
                addView(divider())
                addView(
                    label(
                        "Pairing-Code, Authentifizierungsdaten und Schlüssel werden niemals angezeigt. Sie bleiben lokal im Android Keystore.",
                        9f,
                        TEXT_SECONDARY,
                    ).apply { gravity = Gravity.START },
                )
            },
            cardParams(),
        )
        content.addView(actionButton(if (sensor == null) "Sensor koppeln" else "Sensor neu koppeln", true) { navigate(Screen.SETUP) }, buttonParams())
    }

    private fun appScreen(content: LinearLayout, state: G7PersistedState) {
        header(content, "App-Betrieb")
        val nearby = nearbyAllowed()
        val notifications = notificationsAllowed()
        val battery = isBatteryUnrestricted()
        val exact = exactAlarmAllowed()
        content.addView(
            rowsCard(
                "Collector aktiviert" to yesNo(state.collectorEnabled),
                "Foreground Service" to if (foregroundVisible()) "Aktiv" else "Nicht sichtbar",
                "Geräte in der Nähe" to if (nearby) "Erlaubt" else "Freigeben",
                "Bluetooth" to if (bluetoothEnabled()) "Ein" else "Aus",
                "Benachrichtigungen" to if (notifications) "Erlaubt" else "Freigeben",
                "Akku-Optimierung" to if (battery) "Uneingeschränkt" else "Optimiert",
                "Präzise Sensor-Abfragen" to if (exact) "Erlaubt" else "Freigeben",
                "Doze" to if (deviceIdle()) "Aktiv" else "Inaktiv",
                "Alarmmodus" to if (G7AlertPolicyStore.alarmsEnabled(this)) "Watch Direct aktiv" else "kanonische Mobile-Quelle aktiv",
            ),
            cardParams(),
        )
        if (!nearby || !notifications) {
            content.addView(actionButton("Berechtigungen freigeben", false) { requestMissingPermissions() }, buttonParams())
        }
        if (!battery) content.addView(actionButton("Dauerbetrieb freigeben", false) { requestBatteryExemption() }, buttonParams())
        if (!exact) content.addView(actionButton("Präzise Sensor-Abfragen freigeben", false) { requestExactAlarmAccess() }, buttonParams())
        content.addView(navCard("!", "G7-Alarme", "Grenzwerte, Signalverlust, Ton und Wiederholung") { navigate(Screen.ALARMS) }, cardParams())
        content.addView(
            card().apply {
                addView(
                    label(
                        "Begrenzte Sensorfenster, kein Dauerscan und kein permanenter WakeLock. Ein manueller Scan endet spätestens nach 90 Sekunden.",
                        9f,
                        TEXT_SECONDARY,
                    ).apply { gravity = Gravity.START },
                )
            },
            cardParams(),
        )
    }

    private fun alarmScreen(content: LinearLayout, state: G7PersistedState) {
        header(content, "G7-Alarme")
        val settings = G7AlarmSettingsStore.read(this)
        content.addView(
            rowsCard(
                "Alarmmodus" to if (G7AlertPolicyStore.alarmsEnabled(this)) "Watch Direct aktiv" else "Durch kanonische Mobile-Quelle unterdrückt",
                "Sehr hoch" to alarmValue(settings.veryHighEnabled, "≥ ${settings.veryHighThreshold.toInt()} mg/dL"),
                "Hoch" to alarmValue(settings.highEnabled, "≥ ${settings.highThreshold.toInt()} mg/dL"),
                "Tief" to alarmValue(settings.lowEnabled, "≤ ${settings.lowThreshold.toInt()} mg/dL"),
                "Sehr tief" to alarmValue(settings.veryLowEnabled, "≤ 40 mg/dL · fest"),
                "Schnell steigend" to alarmValue(settings.rapidRiseEnabled, "≥ ${decimal(settings.rapidRiseThreshold)} mg/dL/min"),
                "Schnell fallend" to alarmValue(settings.rapidFallEnabled, "≤ −${decimal(settings.rapidFallThreshold)} mg/dL/min"),
                "Signalverlust" to alarmValue(settings.signalLossEnabled, "ab 16 Minuten · fest"),
                "Sensorfehler" to yesNo(settings.sensorErrorEnabled),
                "Ton" to yesNo(settings.soundEnabled),
                "Vibration" to yesNo(settings.vibrationEnabled),
                "Wiederholung" to alarmValue(settings.repeatEnabled, "alle ${settings.repeatIntervalMinutes} Minuten"),
            ),
            cardParams(),
        )
        content.addView(section("ALARMARTEN"), cardParams())
        content.addView(toggleAlarmButton("Sehr hoch", settings.veryHighEnabled) { settings.copy(veryHighEnabled = it) }, buttonParams())
        content.addView(toggleAlarmButton("Hoch", settings.highEnabled) { settings.copy(highEnabled = it) }, buttonParams())
        content.addView(toggleAlarmButton("Tief", settings.lowEnabled) { settings.copy(lowEnabled = it) }, buttonParams())
        content.addView(toggleAlarmButton("Sehr tief · 40 mg/dL", settings.veryLowEnabled) { settings.copy(veryLowEnabled = it) }, buttonParams())
        content.addView(toggleAlarmButton("Schnell steigend", settings.rapidRiseEnabled) { settings.copy(rapidRiseEnabled = it) }, buttonParams())
        content.addView(toggleAlarmButton("Schnell fallend", settings.rapidFallEnabled) { settings.copy(rapidFallEnabled = it) }, buttonParams())
        content.addView(toggleAlarmButton("Signalverlust · 16 Minuten", settings.signalLossEnabled) { settings.copy(signalLossEnabled = it) }, buttonParams())
        content.addView(toggleAlarmButton("Sensorfehler", settings.sensorErrorEnabled) { settings.copy(sensorErrorEnabled = it) }, buttonParams())

        content.addView(section("GRENZWERTE"), cardParams())
        content.addView(actionButton("Sehr hoch · ${settings.veryHighThreshold.toInt()} mg/dL", false) {
            editAlarmNumber("Sehr-hoch-Grenze", settings.veryHighThreshold, "mg/dL", settings.highThreshold + 1.0, 500.0, false) {
                updateAlarmSettings(state, settings.copy(veryHighThreshold = it))
            }
        }, buttonParams())
        content.addView(actionButton("Hoch · ${settings.highThreshold.toInt()} mg/dL", false) {
            editAlarmNumber("Hoch-Grenze", settings.highThreshold, "mg/dL", settings.lowThreshold + 1.0, settings.veryHighThreshold - 1.0, false) {
                updateAlarmSettings(state, settings.copy(highThreshold = it))
            }
        }, buttonParams())
        content.addView(actionButton("Tief · ${settings.lowThreshold.toInt()} mg/dL", false) {
            editAlarmNumber("Tief-Grenze", settings.lowThreshold, "mg/dL", 41.0, settings.highThreshold - 1.0, false) {
                updateAlarmSettings(state, settings.copy(lowThreshold = it))
            }
        }, buttonParams())
        content.addView(actionButton("Anstieg · ${decimal(settings.rapidRiseThreshold)} mg/dL/min", false) {
            editAlarmNumber("Schneller Anstieg", settings.rapidRiseThreshold, "mg/dL/min", 0.5, 10.0, true) {
                updateAlarmSettings(state, settings.copy(rapidRiseThreshold = it))
            }
        }, buttonParams())
        content.addView(actionButton("Abfall · ${decimal(settings.rapidFallThreshold)} mg/dL/min", false) {
            editAlarmNumber("Schneller Abfall", settings.rapidFallThreshold, "mg/dL/min", 0.5, 10.0, true) {
                updateAlarmSettings(state, settings.copy(rapidFallThreshold = it))
            }
        }, buttonParams())

        content.addView(section("AUSGABE"), cardParams())
        content.addView(toggleAlarmButton("Ton", settings.soundEnabled) { settings.copy(soundEnabled = it) }, buttonParams())
        content.addView(toggleAlarmButton("Vibration", settings.vibrationEnabled) { settings.copy(vibrationEnabled = it) }, buttonParams())
        content.addView(toggleAlarmButton("Wiederholen", settings.repeatEnabled) { settings.copy(repeatEnabled = it) }, buttonParams())
        content.addView(actionButton("Wiederholung · ${settings.repeatIntervalMinutes} Minuten", false) {
            editAlarmNumber("Wiederholungsintervall", settings.repeatIntervalMinutes.toDouble(), "Minuten", 5.0, 120.0, false) {
                updateAlarmSettings(state, settings.copy(repeatIntervalMinutes = it.toInt()))
            }
        }, buttonParams())
        content.addView(
            label(
                "Im Modus Automatisch werden recoverable Collector-Alarme unterdrückt, solange eine gültige kanonische Mobile-Quelle besteht. Sehr tief bleibt fest bei 40 mg/dL; Signalverlust beginnt fest nach 16 Minuten.",
                9f,
                TEXT_SECONDARY,
            ).apply { gravity = Gravity.START },
            cardParams(),
        )
    }

    private fun toggleAlarmButton(
        title: String,
        enabled: Boolean,
        transform: (Boolean) -> CgmAlarmSettings,
    ) = actionButton("$title · ${if (enabled) "Ein" else "Aus"}", enabled) {
        updateAlarmSettings(G7SensorStateStore(this).read(), transform(!enabled))
    }

    private fun updateAlarmSettings(state: G7PersistedState, settings: CgmAlarmSettings) {
        G7AlarmSettingsStore.write(this, settings)
        G7CgmAlarmCoordinator.onSignalLoss(this, state.lastReading)
        G7CgmAlarmCoordinator.restore(this)
        Toast.makeText(this, "Alarmeinstellung gespeichert", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun editAlarmNumber(
        title: String,
        current: Double,
        unit: String,
        minimum: Double,
        maximum: Double,
        allowDecimal: Boolean,
        onSave: (Double) -> Unit,
    ) {
        val input = EditText(this).apply {
            setText(if (allowDecimal) decimal(current) else current.toInt().toString())
            inputType = InputType.TYPE_CLASS_NUMBER or
                (if (allowDecimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("$unit · erlaubt: ${decimal(minimum)} bis ${decimal(maximum)}")
            .setView(input)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern") { _, _ ->
                val value = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (value == null || value !in minimum..maximum) {
                    Toast.makeText(this, "Ungültiger Wert", Toast.LENGTH_LONG).show()
                } else {
                    onSave(value)
                }
            }
            .show()
    }

    private fun alarmValue(enabled: Boolean, detail: String) = if (enabled) "Ein · $detail" else "Aus"

    private fun decimal(value: Double) = String.format(Locale.GERMANY, "%.1f", value)

    private fun readingsScreen(content: LinearLayout) {
        header(content, "Empfangene Werte")
        val ordered = readings.sortedBy(CgmReading::timestampEpochMs)
        val summary = summary()
        content.addView(
            rowsCard(
                "Gespeicherte Werte" to summary.count.toString(),
                "Heute" to summary.todayCount.toString(),
                "Ältester Wert" to timestamp(summary.oldestEpochMs),
                "Letzter Erfolg" to timestamp(summary.latestEpochMs),
                "Verpasste Fenster" to summary.missedExpectedWindows.toString(),
            ),
            cardParams(),
        )
        if (ordered.isEmpty()) {
            content.addView(label(if (loading) "Historie wird geladen …" else "Noch keine lokalen G7-Werte gespeichert.", 11f, TEXT_SECONDARY), cardParams())
            return
        }
        val visible = ordered.takeLast(MAX_VISIBLE_READINGS)
        if (visible.size < ordered.size) {
            content.addView(label("Neueste $MAX_VISIBLE_READINGS von ${ordered.size} gespeicherten Werten.", 9f, TEXT_SECONDARY), cardParams())
        }
        visible.forEachIndexed { index, reading ->
            if (index > 0) addGap(content, visible[index - 1], reading)
            content.addView(readingCard(reading), cardParams())
        }
    }

    private fun addGap(content: LinearLayout, before: CgmReading, after: CgmReading) {
        if (before.sensorId != after.sensorId || before.sessionId != after.sessionId) return
        val interval = after.timestampEpochMs - before.timestampEpochMs
        if (interval <= EXPECTED_INTERVAL_MS + WINDOW_TOLERANCE_MS) return
        val missed = (interval / EXPECTED_INTERVAL_MS - 1L).coerceAtLeast(1L).toInt()
        val matching =
            attempts.filter {
                it.startedAtEpochMs <= after.timestampEpochMs &&
                    (it.completedAtEpochMs ?: it.startedAtEpochMs) >= before.timestampEpochMs
            }.sortedBy(CollectorDiagnosticAttempt::startedAtEpochMs)
        content.addView(
            card(Color.rgb(55, 42, 28), WARNING).apply {
                addView(section("DATENLÜCKE · $missed FENSTER"))
                addView(label("${time(before.timestampEpochMs)} → ${time(after.timestampEpochMs)}", 11f, TEXT_PRIMARY, true))
                if (matching.isEmpty()) {
                    addView(label("Kein gespeicherter Collection-Versuch in diesem Zeitraum.", 9f, TEXT_SECONDARY))
                } else {
                    matching.takeLast(5).forEach {
                        addView(label("#${it.attemptId} ${time(it.startedAtEpochMs)} · ${it.summary}", 9f, TEXT_SECONDARY).apply { gravity = Gravity.START })
                    }
                }
            },
            cardParams(),
        )
    }

    private fun readingCard(reading: CgmReading) =
        card().apply {
            addView(label("${timestamp(reading.timestampEpochMs)} · ${reading.glucoseMgDl.toInt()} mg/dL", 13f, TEXT_PRIMARY, true).apply { gravity = Gravity.START })
            addRows(
                "Empfangen" to timestamp(reading.receivedAtEpochMs),
                "Trend" to "${trendGlyph(reading.trend)}  ${reading.trend.name}",
                "Delta" to (reading.deltaMgDl?.let { String.format(Locale.US, "%+.0f mg/dL", it) } ?: "—"),
                "Sequence" to (reading.sequenceNumber?.toString() ?: "—"),
                "Sensor" to safeId(reading.sensorId),
                "Session" to safeId(reading.sessionId),
                "Source" to reading.source.name,
                "Validierung" to reading.status.name,
            )
        }

    private fun communicationScreen(content: LinearLayout, state: G7PersistedState) {
        header(content, "Kommunikation")
        content.addView(scanCard(state), cardParams())
        if (attempts.isEmpty()) {
            content.addView(label(if (loading) "Versuche werden geladen …" else "Noch keine Collection-Versuche gespeichert.", 11f, TEXT_SECONDARY), cardParams())
            return
        }
        attempts.take(MAX_ATTEMPTS).forEach { attempt ->
            val elapsed = (attempt.completedAtEpochMs ?: System.currentTimeMillis()) - attempt.startedAtEpochMs
            val result = when (attempt.result) {
                CollectorDiagnosticResult.SUCCESS -> "✓"
                CollectorDiagnosticResult.RECOVERABLE_ERROR -> "⚠"
                CollectorDiagnosticResult.FATAL_ERROR -> "✕"
                CollectorDiagnosticResult.CANCELLED -> "○"
                else -> "…"
            }
            val trigger = if (attempt.restart) "R" else if (attempt.manual) "M" else "A"
            content.addView(
                navCard(
                    trigger,
                    "#${attempt.attemptId}  ${time(attempt.startedAtEpochMs)}  $result",
                    "${attempt.summary} · ${duration(elapsed)}",
                ) {
                    selectedAttemptId = attempt.attemptId
                    navigate(Screen.ATTEMPT)
                },
                cardParams(),
            )
        }
    }

    private fun attemptScreen(content: LinearLayout) {
        val attempt = attempts.firstOrNull { it.attemptId == selectedAttemptId }
        header(content, attempt?.let { "Versuch #${it.attemptId}" } ?: "Versuch")
        if (attempt == null) {
            content.addView(label("Dieser Versuch ist nicht mehr im begrenzten Verlauf.", 11f, TEXT_SECONDARY), cardParams())
            return
        }
        content.addView(
            rowsCard(
                "Start" to timestamp(attempt.startedAtEpochMs),
                "Ende" to timestamp(attempt.completedAtEpochMs),
                "Auslöser" to when {
                    attempt.restart -> "Collector-Neustart"
                    attempt.manual -> "Manueller Scan"
                    else -> "Automatisch"
                },
                "Ergebnis" to attempt.result.name,
                "Dauer" to duration((attempt.completedAtEpochMs ?: System.currentTimeMillis()) - attempt.startedAtEpochMs),
            ),
            cardParams(),
        )
        attempt.events.sortedBy { it.timestampEpochMs }.forEach { event ->
            content.addView(
                card().apply {
                    addView(label("${timeSeconds(event.timestampEpochMs)}  ${event.stage.name}", 11f, TEXT_PRIMARY, true).apply { gravity = Gravity.START })
                    addView(label("${event.result.name} · ${sanitizeDiagnosticText(event.message)}", 9f, TEXT_SECONDARY).apply { gravity = Gravity.START })
                    event.errorCode?.let { addView(row("Fehlercode", it)) }
                    event.sequence?.let { addView(row("Sequence", it.toString())) }
                    event.sensorId?.let { addView(row("Sensor", safeId(it))) }
                    event.durationMs?.let { addView(row("Dauer", duration(it))) }
                },
                cardParams(),
            )
        }
    }

    private fun diagnosisScreen(content: LinearLayout, state: G7PersistedState, status: G7UserStatus) {
        header(content, "Diagnose")
        content.addView(
            rowsCard(
                "Collector-Status" to displayStatus(state, status),
                "Protocol" to state.protocolState.name,
                "Session" to state.sessionState.name,
                "Connection" to state.connectionState.name,
                "Owner" to state.collectorOwner.name,
                "Aktiver Versuch" to (state.activeAttemptId?.let { "#$it" } ?: "Keiner"),
                "Retry" to state.retryCount.toString(),
                "Scanstart" to timestamp(state.scanStartedAtEpochMs),
                "Scan-Timeout" to timestamp(state.scanTimeoutAtEpochMs),
                "Letzter Abschluss" to timestamp(state.lastAttemptCompletedAtEpochMs),
                "FGS" to if (foregroundVisible()) "Aktiv" else "Nicht sichtbar",
                "Exact Alarm" to yesNo(exactAlarmAllowed()),
                "Akku uneingeschränkt" to yesNo(isBatteryUnrestricted()),
                "Bluetooth" to if (bluetoothEnabled()) "Ein" else "Aus",
            ),
            cardParams(),
        )
        state.lastError?.let { error ->
            content.addView(
                card(Color.rgb(53, 31, 34), ERROR).apply {
                    addView(section("LETZTER FEHLER"))
                    addRows(
                        "Code" to error.code,
                        "Zeit" to timestamp(error.occurredAtEpochMs),
                        "Recoverable" to yesNo(error.recoverable),
                    )
                    addView(label(sanitizeDiagnosticText(error.safeMessage), 10f, TEXT_PRIMARY).apply { gravity = Gravity.START })
                },
                cardParams(),
            )
        }
        content.addView(
            card().apply {
                addView(label("Nur strukturierte Diagnoseereignisse. Kein Logcat, keine vollständige Bluetooth-Adresse und keine Auth-Secrets.", 9f, TEXT_SECONDARY).apply { gravity = Gravity.START })
            },
            cardParams(),
        )
    }

    private fun setupScreen(content: LinearLayout, configured: Boolean) {
        header(content, if (configured) "Sensor neu koppeln" else "Sensor koppeln")
        content.addView(
            card().apply {
                addView(section(if (configured) "GESCHÜTZTEN PAIRING-FLOW STARTEN" else "SENSOR EINRICHTEN"))
                addView(label("Vierstelliger Code vom G7-Applikator", 12f, TEXT_SECONDARY).apply { gravity = Gravity.START })
                val input =
                    EditText(this@G7WatchActivity).apply {
                        hint = "0000"
                        inputType = InputType.TYPE_CLASS_NUMBER
                        filters = arrayOf(InputFilter.LengthFilter(4))
                        setTextColor(TEXT_PRIMARY)
                        setHintTextColor(TEXT_SECONDARY)
                        textSize = 20f
                        gravity = Gravity.CENTER
                        setPadding(14.dp, 9.dp, 14.dp, 9.dp)
                        background = rounded(FIELD, BORDER, 16f)
                    }
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp })
                addView(
                    actionButton("Sensorcode verschlüsselt speichern", true) {
                        val payload = runCatching { G7SetupPayload(input.text?.toString().orEmpty()) }.getOrNull()
                        if (payload == null) {
                            input.error = "4 Ziffern erforderlich"
                            return@actionButton
                        }
                        G7CredentialStore(this@G7WatchActivity).saveSetup(payload)
                        val sensorId = "G7-${UUID.randomUUID().toString().take(8)}"
                        val store = G7SensorStateStore(this@G7WatchActivity)
                        store.save(G7SessionManager(store.read()).prepareInitialSetup(G7Sensor(sensorId, sensorId, "Dexcom G7")))
                        input.text?.clear()
                        Toast.makeText(this@G7WatchActivity, "Sensorcode geschützt gespeichert", Toast.LENGTH_SHORT).show()
                        navigation.clear()
                        screen = Screen.MAIN
                        scheduleRefresh()
                    },
                    buttonParams(10),
                )
                addView(label("Das Speichern erzwingt keinen Sensorwert. Der Sensor wird nur in begrenzten BLE-Fenstern gesucht.", 9f, TEXT_SECONDARY).apply { gravity = Gravity.START })
            },
            cardParams(),
        )
    }

    private fun scanCard(state: G7PersistedState) =
        card().apply {
            addView(section("LIVE COLLECTORSTATUS"))
            addView(label(phaseTitle(state.protocolState), 14f, TEXT_PRIMARY, true))
            val detail = label(scanDetail(state), 10f, TEXT_SECONDARY)
            liveStatusView = detail
            addView(detail)
        }

    private fun phaseTitle(protocol: G7ProtocolState): String =
        when (protocol) {
            G7ProtocolState.SCANNING -> "Suche nach Sensor …"
            G7ProtocolState.SENSOR_FOUND -> "Sensor gefunden"
            G7ProtocolState.CONNECTING, G7ProtocolState.DISCOVERING, G7ProtocolState.DISCOVERING_SERVICES -> "Verbindung wird aufgebaut …"
            G7ProtocolState.AUTHENTICATION_START, G7ProtocolState.AUTHENTICATING,
            G7ProtocolState.AUTHENTICATION_ROUND_1, G7ProtocolState.AUTHENTICATION_ROUND_2,
            G7ProtocolState.AUTHENTICATION_ROUND_3, G7ProtocolState.CHALLENGE,
            G7ProtocolState.CERTIFICATE_EXCHANGE, G7ProtocolState.KEY_EXCHANGE -> "Authentifizierung …"
            G7ProtocolState.REQUESTING_GLUCOSE, G7ProtocolState.RECEIVING_GLUCOSE -> "Glukosedaten werden gelesen …"
            G7ProtocolState.RECOVERING -> "Recovery"
            G7ProtocolState.ERROR -> "Signalverlust / Fehler"
            G7ProtocolState.WAITING_FOR_NEXT_READING, G7ProtocolState.IDLE, G7ProtocolState.DISCONNECTED -> "Warte auf nächstes Sensorfenster"
            else -> "Bereit"
        }

    private fun scanDetail(state: G7PersistedState, now: Long = System.currentTimeMillis()): String {
        val started = state.scanStartedAtEpochMs
        if (state.protocolState == G7ProtocolState.SCANNING && started != null) {
            return buildString {
                append(duration((now - started).coerceAtLeast(0L)))
                append("\nGestartet: ")
                append(timeSeconds(started))
                state.scanTimeoutAtEpochMs?.let {
                    append(" · Timeout: ")
                    append(timeSeconds(it))
                }
            }
        }
        return when (state.protocolState) {
            G7ProtocolState.WAITING_FOR_NEXT_READING, G7ProtocolState.IDLE, G7ProtocolState.DISCONNECTED ->
                state.nextReconnectEpochMs?.let { "Nächster Versuch: ${timeSeconds(it)}" } ?: "Bereit für das nächste Sensorfenster"
            G7ProtocolState.SENSOR_FOUND -> "Sensor gefunden – Verbindung wird aufgebaut."
            G7ProtocolState.CONNECTING, G7ProtocolState.DISCOVERING, G7ProtocolState.DISCOVERING_SERVICES -> "Verbindung wird aufgebaut …"
            G7ProtocolState.AUTHENTICATION_START, G7ProtocolState.AUTHENTICATING,
            G7ProtocolState.AUTHENTICATION_ROUND_1, G7ProtocolState.AUTHENTICATION_ROUND_2,
            G7ProtocolState.AUTHENTICATION_ROUND_3, G7ProtocolState.CHALLENGE,
            G7ProtocolState.CERTIFICATE_EXCHANGE, G7ProtocolState.KEY_EXCHANGE -> "Authentifizierung …"
            G7ProtocolState.REQUESTING_GLUCOSE, G7ProtocolState.RECEIVING_GLUCOSE -> "Glukosedaten werden gelesen …"
            G7ProtocolState.RECOVERING -> "Recovery – nächster begrenzter Versuch wird geplant."
            G7ProtocolState.ERROR -> state.lastError?.safeMessage?.let(::sanitizeDiagnosticText) ?: "Collectorfehler"
            else -> state.lastReading?.let { "Wert empfangen · ${it.glucoseMgDl.toInt()} mg/dL" } ?: "Bereit"
        }
    }

    private fun displayStatus(state: G7PersistedState, status: G7UserStatus): String =
        when {
            !state.collectorEnabled -> "Collector inaktiv"
            status.level == G7UserStatusLevel.ERROR -> status.title
            status.title == "Signalverlust" -> "Signalverlust"
            state.protocolState == G7ProtocolState.RECOVERING -> "Recovery"
            state.protocolState == G7ProtocolState.SCANNING -> "Suche nach Sensor"
            state.lastReading != null -> "Verbunden"
            else -> "Bereit"
        }

    private fun restartCollector(state: G7PersistedState) {
        if (!manualActionAllowed(state)) return
        G7CollectorService.restart(this)
        Toast.makeText(this, "Collector wird kontrolliert neu gestartet und scannt erneut", Toast.LENGTH_LONG).show()
        scheduleRefresh()
    }

    private fun manualScan(state: G7PersistedState) {
        if (!manualActionAllowed(state)) return
        G7CollectorService.scanNow(this)
        Toast.makeText(this, "Begrenzte Sensorsuche gestartet · maximal 90 Sekunden", Toast.LENGTH_LONG).show()
        scheduleRefresh()
    }

    private fun manualActionAllowed(state: G7PersistedState): Boolean {
        val allowed = state.collectorEnabled && state.sensor != null && G7CredentialStore(this).read() != null
        if (!allowed) Toast.makeText(this, "Zuerst Sensor koppeln und Collector starten", Toast.LENGTH_LONG).show()
        return allowed
    }

    private fun confirmUnlink() {
        AlertDialog.Builder(this)
            .setTitle("Sensor entkoppeln?")
            .setMessage("Bond und geschützte Sensor-/Auth-Zuordnung werden entfernt. Verlauf, Graphfarben und Alarmsettings bleiben erhalten.")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Entkoppeln") { _, _ ->
                val result = unlinkG7Sensor(this)
                val message = when {
                    result.bondRemovalRequested -> "Sensor und Bond wurden entkoppelt"
                    result.bondRemovalAttempted -> "Sensorzuordnung entfernt; Bond-Entfernung konnte nicht bestätigt werden"
                    else -> "Sensorzuordnung entfernt; kein gespeicherter Bond vorhanden"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                navigation.clear()
                screen = Screen.MAIN
                scheduleRefresh()
            }
            .show()
    }

    private fun navigate(target: Screen) {
        navigation.addLast(screen)
        screen = target
        render()
        if (target == Screen.READINGS || target == Screen.COMMUNICATION || target == Screen.SYSTEM) refreshCaches()
    }

    private fun navigateBack() {
        if (navigation.isEmpty()) {
            finish()
        } else {
            screen = navigation.removeLast()
            render()
        }
    }

    private fun header(content: LinearLayout, title: String) {
        content.addView(
            actionButton("‹  Zurück", false) { navigateBack() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START
                bottomMargin = 8.dp
            },
        )
        content.addView(label(title, 20f, TEXT_PRIMARY, true))
    }

    private fun navCard(icon: String, title: String, subtitle: String, action: () -> Unit) =
        card().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            addView(
                LinearLayout(this@G7WatchActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(icon, 21f, ACCENT, true), LinearLayout.LayoutParams(38.dp, ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(
                        LinearLayout(this@G7WatchActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(label(title, 13f, TEXT_PRIMARY, true).apply { gravity = Gravity.START })
                            addView(label(subtitle, 9f, TEXT_SECONDARY).apply { gravity = Gravity.START })
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(label("›", 22f, TEXT_SECONDARY, true))
                },
            )
        }

    private fun rowsCard(vararg rows: Pair<String, String>) = card().apply { addRows(*rows) }

    private fun LinearLayout.addRows(vararg rows: Pair<String, String>) {
        rows.forEach { (title, value) -> addView(row(title, value)) }
    }

    private fun row(title: String, value: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title, 10f, TEXT_SECONDARY).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                label(value, 10f, TEXT_PRIMARY, true).apply {
                    gravity = Gravity.END
                    maxLines = 4
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f),
            )
        }

    private fun statusPill(status: G7UserStatus, title: String): TextView {
        val color = when (status.level) {
            G7UserStatusLevel.OK, G7UserStatusLevel.WORKING -> ACCENT
            G7UserStatusLevel.ATTENTION -> WARNING
            G7UserStatusLevel.ERROR -> ERROR
            G7UserStatusLevel.OFF -> TEXT_SECONDARY
        }
        val marker = if (status.level == G7UserStatusLevel.OFF) "○" else "●"
        return label("$marker  ${title.uppercase(Locale.GERMANY)}", 11f, color, true).apply {
            background = rounded(Color.argb(36, Color.red(color), Color.green(color), Color.blue(color)), color, 18f)
            setPadding(14.dp, 6.dp, 14.dp, 6.dp)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8.dp
                bottomMargin = 4.dp
            }
        }
    }

    private fun card(fill: Int = SURFACE, stroke: Int = BORDER) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            background = rounded(fill, stroke, 22f)
        }

    private fun divider() =
        View(this).apply {
            setBackgroundColor(BORDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp).apply {
                setMargins(0, 8.dp, 0, 8.dp)
            }
        }

    private fun section(value: String) = label(value, 10f, ACCENT, true).apply {
        gravity = Gravity.START
        letterSpacing = 0.11f
    }

    private fun actionButton(value: String, primary: Boolean, action: () -> Unit) =
        Button(this).apply {
            text = value
            isAllCaps = false
            textSize = 12f
            minHeight = 44.dp
            minimumHeight = 44.dp
            setPadding(12.dp, 9.dp, 12.dp, 9.dp)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (primary) Color.rgb(9, 25, 15) else TEXT_PRIMARY)
            backgroundTintList = ColorStateList.valueOf(if (primary) ACCENT else SURFACE_HIGH)
            setOnClickListener { action() }
        }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(3.dp, 3.dp, 3.dp, 3.dp)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke(1.dp, stroke)
            cornerRadius = radiusDp * resources.displayMetrics.density
        }

    private fun cardParams() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 8.dp, 0, 0)
        }

    private fun buttonParams(top: Int = 8) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, top.dp, 0, 0)
        }

    private fun requestMissingPermissions() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_SCAN)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun nearbyAllowed() =
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun notificationsAllowed() =
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun isBatteryUnrestricted() = G7BackgroundAccess.isBatteryUnrestricted(this)

    private fun exactAlarmAllowed() =
        getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun foregroundVisible() =
        runCatching {
            getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == G7CollectorService.NOTIFICATION_ID }
        }.getOrDefault(false)

    private fun bluetoothEnabled() =
        runCatching { getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false)

    private fun deviceIdle() = getSystemService(PowerManager::class.java).isDeviceIdleMode

    private fun requestBatteryExemption() {
        if (isBatteryUnrestricted()) {
            Toast.makeText(this, "Dauerbetrieb ist bereits uneingeschränkt", Toast.LENGTH_SHORT).show()
            return
        }
        batteryRequestPending = true
        if (G7BackgroundAccess.openBatterySettings(this)) return
        batteryRequestPending = false
        Toast.makeText(this, "Akku-Einstellungen konnten nicht geöffnet werden", Toast.LENGTH_LONG).show()
        recordBackgroundDiagnostic("G7-BG-404", "Battery optimization settings could not be opened for G7 Watch Collector", DiagnosticSeverity.ERROR)
    }

    private fun handleBatterySettingsResult() {
        if (!batteryRequestPending) return
        batteryRequestPending = false
        val allowed = isBatteryUnrestricted()
        Toast.makeText(this, if (allowed) "Dauerbetrieb ist uneingeschränkt" else "Akkuoptimierung ist weiterhin aktiv", Toast.LENGTH_LONG).show()
        recordBackgroundDiagnostic(
            if (allowed) "G7-BG-200" else "G7-BG-403",
            if (allowed) "Battery optimization exemption granted for G7 Watch Collector" else "Battery optimization exemption was not granted for G7 Watch Collector",
            if (allowed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
        )
    }

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:$packageName")))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }
        }
    }

    private fun recordBackgroundDiagnostic(code: String, message: String, severity: DiagnosticSeverity) {
        workScope.launch { applicationContext.recordG7Diagnostic(code, message, severity) }
    }

    private fun scheduleRefresh() {
        uiHandler.postDelayed(
            {
                render()
                refreshCaches()
            },
            ACTION_REFRESH_DELAY_MS,
        )
    }

    private fun summary() = summarizeG7Readings(readings, startOfToday())

    private fun startOfToday() =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun timestamp(value: Long?) =
        value?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "—"

    private fun time(value: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(value))

    private fun timeSeconds(value: Long) = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date(value))

    private fun duration(value: Long): String {
        val seconds = value.coerceAtLeast(0L) / 1_000L
        return String.format(Locale.GERMANY, "%02d:%02d", seconds / 60L, seconds % 60L)
    }

    private fun safeId(value: String?) =
        value?.takeIf(String::isNotBlank)?.let { if (it.length <= 18) it else "${it.take(10)}…${it.takeLast(5)}" } ?: "—"

    private fun yesNo(value: Boolean) = if (value) "Ja" else "Nein"

    private fun trendGlyph(trend: Trend) =
        when (trend) {
            Trend.DOUBLE_DOWN -> "⇊"
            Trend.SINGLE_DOWN -> "↓"
            Trend.FORTY_FIVE_DOWN -> "↘"
            Trend.FLAT -> "→"
            Trend.FORTY_FIVE_UP -> "↗"
            Trend.SINGLE_UP -> "↑"
            Trend.DOUBLE_UP -> "⇈"
            Trend.UNKNOWN -> "·"
        }

    override fun onDestroy() {
        resumed = false
        uiHandler.removeCallbacksAndMessages(null)
        workScope.cancel()
        super.onDestroy()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_HISTORY_ROWS = 2_000
        const val MAX_VISIBLE_READINGS = 100
        const val MAX_ATTEMPTS = 50
        const val EXPECTED_INTERVAL_MS = 5L * 60_000L
        const val WINDOW_TOLERANCE_MS = 90_000L
        const val UI_TICK_MS = 1_000L
        const val ACTION_REFRESH_DELAY_MS = 500L
        val BACKGROUND = Color.rgb(24, 24, 24)
        val SURFACE = Color.rgb(36, 36, 36)
        val SURFACE_HIGH = Color.rgb(48, 48, 48)
        val FIELD = Color.rgb(30, 30, 30)
        val BORDER = Color.rgb(64, 64, 64)
        val TEXT_PRIMARY = Color.rgb(245, 245, 245)
        val TEXT_SECONDARY = Color.rgb(181, 181, 181)
        val ACCENT = Color.rgb(109, 232, 146)
        val WARNING = Color.rgb(255, 193, 7)
        val ERROR = Color.rgb(255, 92, 105)
    }
}
