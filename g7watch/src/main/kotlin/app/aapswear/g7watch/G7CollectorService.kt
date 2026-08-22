package app.aapswear.g7watch

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import app.aapswear.g7.CgmReadingStatus
import app.aapswear.g7.CollectorAlarmKind
import app.aapswear.g7.CollectorCycleClassification
import app.aapswear.g7.CollectorCycleTiming
import app.aapswear.g7.CollectorDiagnosticResult
import app.aapswear.g7.CollectorDiagnosticStage
import app.aapswear.g7.G7CollectorError
import app.aapswear.g7.G7ConnectionState
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7ProtocolState
import app.aapswear.g7.G7ReconnectScheduler
import app.aapswear.g7.G7SessionManager
import app.aapswear.g7.G7SessionState
import app.aapswear.g7.toCgm
import app.aapswear.model.DiagnosticSeverity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

internal fun shouldKeepG7RuntimeForeground(collectorEnabled: Boolean): Boolean = collectorEnabled

internal fun resetG7RuntimeForRestart(state: G7PersistedState): G7PersistedState =
    state.copy(
        connectionState = G7ConnectionState.DISCONNECTED,
        protocolState = G7ProtocolState.UNINITIALIZED,
        authenticationState = app.aapswear.g7.G7AuthenticationState.UNKNOWN,
        retryCount = 0,
        nextReconnectEpochMs = null,
        lastError = null,
        activeAttemptId = null,
        scanStartedAtEpochMs = null,
        scanTimeoutAtEpochMs = null,
    )

class G7CollectorService : Service() {
    private lateinit var store: G7SensorStateStore
    private lateinit var credentials: G7CredentialStore
    private lateinit var attemptStore: G7CollectorDiagnosticStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectionJob: Job? = null
    private var cycleWakeLock: PowerManager.WakeLock? = null
    private var cycleToken: Long = 0L

    override fun onCreate() {
        super.onCreate()
        store = G7SensorStateStore(this)
        credentials = G7CredentialStore(this)
        attemptStore = G7CollectorDiagnosticStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "G7 Direct to Watch", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Permanenter Dexcom G7 Watch Collector"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        G7ErrorNotifier.ensureChannel(this)
        G7CgmAlarmCoordinator.restore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serviceStartAt = System.currentTimeMillis()
        if (intent?.action == ACTION_STOP) {
            collectionJob?.cancel()
            store.save(G7SessionManager(store.read()).stop())
            cancelScheduledReconnect(this)
            G7SignalLossMonitor.cancel(this)
            G7ErrorNotifier.clearActive(this)
            G7CgmAlarmCoordinator.clearSuppressed(this)
            G7WakeHandoff.release()
            stopRuntimeForeground()
            return START_NOT_STICKY
        }

        val persisted = store.read()
        if (!persisted.collectorEnabled) {
            G7SignalLossMonitor.cancel(this)
            G7ErrorNotifier.clearActive(this)
            G7WakeHandoff.release()
            stopRuntimeForeground()
            return START_NOT_STICKY
        }

        G7SignalLossMonitor.scheduleFromState(this, persisted)
        startForegroundCollector(
            if (collectionJob?.isActive == true) "Collector aktiv" else "Dauerbetrieb aktiv",
        )
        val request = when (intent?.action) {
            ACTION_RESTART -> CycleRequest.RESTART
            ACTION_MANUAL_SCAN -> CycleRequest.MANUAL
            else -> CycleRequest.AUTOMATIC
        }
        val scheduledCycle =
            if (intent?.action == ACTION_RECONNECT) {
                attemptStore.consumeScheduledCycle(serviceStartAt)
            } else {
                null
            }

        if (request == CycleRequest.AUTOMATIC && collectionJob?.isActive == true) {
            scheduledCycle?.let { cycle ->
                val attempt = attemptStore.begin(false, false, cycle.copy(cycleEndedAt = serviceStartAt), serviceStartAt)
                attemptStore.setClassification(attempt.attemptId, CollectorCycleClassification.SERVICE_START_FAILED)
                attemptStore.record(
                    attempt.attemptId,
                    CollectorDiagnosticStage.ERROR,
                    CollectorDiagnosticResult.RECOVERABLE_ERROR,
                    "Geplanter Sensorzyklus konnte nicht starten, weil der vorherige Zyklus noch aktiv war",
                    nowEpochMs = serviceStartAt,
                )
            }
            G7WakeHandoff.release()
            return START_STICKY
        }
        launchCycle(request, scheduledCycle, serviceStartAt)
        return START_STICKY
    }

    private fun launchCycle(
        request: CycleRequest,
        scheduledCycle: CollectorCycleTiming?,
        serviceStartAt: Long,
    ) {
        if (request != CycleRequest.AUTOMATIC) cancelScheduledReconnect(this)
        val previous = collectionJob
        val token = ++cycleToken
        collectionJob = scope.launch {
            previous?.cancelAndJoin()
            if (token != cycleToken) return@launch
            val wakeAt = acquireCycleWakeLock(request)
            G7WakeHandoff.release()
            val cycle = scheduledCycle?.copy(
                serviceOnStartCommandAt = serviceStartAt,
                wakeLockAcquiredAt = wakeAt,
            )
            applicationContext.recordG7Diagnostic(
                "G7-COLLECT-100",
                when (request) {
                    CycleRequest.AUTOMATIC -> "Automatic collector cycle started"
                    CycleRequest.MANUAL -> "Bounded manual sensor scan started"
                    CycleRequest.RESTART -> "Collector runtime restarted with retained sensor/session state"
                },
                metadata = mapOf(
                    "scheduled" to (cycle != null),
                    "alarmLatenessMs" to cycle?.alarmLatenessMs,
                    "serviceStartLatenessMs" to cycle?.serviceStartLatenessMs,
                ),
            )
            collectOnce(token, request, cycle)
        }
    }

    private suspend fun collectOnce(
        token: Long,
        request: CycleRequest,
        scheduledCycle: CollectorCycleTiming?,
    ) {
        val startedAt = System.currentTimeMillis()
        val attempt = attemptStore.begin(
            manual = request == CycleRequest.MANUAL,
            restart = request == CycleRequest.RESTART,
            cycle = scheduledCycle,
            nowEpochMs = startedAt,
        )
        val attemptId = attempt.attemptId
        val persisted =
            store.read().let { state ->
                if (request == CycleRequest.RESTART) resetG7RuntimeForRestart(state) else state
            }
        store.save(persisted.copy(activeAttemptId = attemptId))
        val configuredSensor = persisted.sensor
        if (!persisted.collectorEnabled) {
            attemptStore.setClassification(attemptId, CollectorCycleClassification.CANCELLED)
            attemptStore.record(
                attemptId,
                CollectorDiagnosticStage.COMPLETE,
                CollectorDiagnosticResult.CANCELLED,
                "Collector ist deaktiviert",
            )
            store.save(store.read().copy(activeAttemptId = null, lastAttemptCompletedAtEpochMs = System.currentTimeMillis()))
            finishCycle(token)
            return
        }
        if (configuredSensor == null) {
            fail(
                persisted,
                G7CollectorError("G7-SETUP-001", false, System.currentTimeMillis(), "Sensor muss zuerst eingerichtet und der Collector gestartet werden"),
                attemptId,
                startedAt,
            )
            finishCycle(token)
            return
        }
        val storedCredentials = credentials.read()
        if (storedCredentials == null) {
            fail(
                persisted,
                G7CollectorError("G7-SETUP-002", false, System.currentTimeMillis(), "Sensorcode fehlt oder ist nicht mehr lesbar"),
                attemptId,
                startedAt,
            )
            finishCycle(token)
            return
        }

        try {
            val collector = AndroidG7Collector(this)
            val boundedScanTimeout =
                if (request == CycleRequest.AUTOMATIC) null else G7_RECONNECT_SCAN_TIMEOUT_MS
            val result = collector.collect(
                initialSensor = configuredSensor,
                credentials = storedCredentials,
                onState = { protocolState ->
                    val current = store.read()
                    val now = System.currentTimeMillis()
                    val next = current.copy(
                        protocolState = protocolState,
                        connectionState = protocolState.toConnectionState(),
                        sessionState = protocolState.toSessionState(),
                        scanStartedAtEpochMs =
                            if (protocolState == G7ProtocolState.SCANNING) now else current.scanStartedAtEpochMs,
                        scanTimeoutAtEpochMs =
                            if (protocolState == G7ProtocolState.SCANNING) {
                                now + (boundedScanTimeout ?: g7ScanTimeoutMs(configuredSensor))
                            } else {
                                current.scanTimeoutAtEpochMs
                            },
                        lastScanAtEpochMs =
                            if (protocolState == G7ProtocolState.SCANNING) now else current.lastScanAtEpochMs,
                    )
                    store.save(next)
                    updateAttemptCycleForProtocolState(attemptId, protocolState, now)
                    updateForeground(protocolState.label())
                    recordAttemptProtocolState(attemptId, protocolState, configuredSensor.sensorId)
                    scope.launch {
                        applicationContext.recordG7Diagnostic(
                            protocolState.diagnosticCode(),
                            protocolState.label(),
                            metadata = mapOf("protocolState" to protocolState.name),
                        )
                    }
                },
                onSharedKey = credentials::saveSharedKey,
                scanTimeoutMsOverride = boundedScanTimeout,
            )
            attemptStore.record(
                attemptId,
                CollectorDiagnosticStage.GATT_CLOSE,
                CollectorDiagnosticResult.SUCCESS,
                "GATT-Verbindung sauber geschlossen",
                sensorId = result.sensor.sensorId,
            )
            result.sharedKey?.let { key -> result.sensor.deviceAddress?.let { credentials.saveSharedKey(it, key) } }

            val now = System.currentTimeMillis()
            val previousValid = G7ReadingDatabase(this).let { database ->
                try {
                    database.getLatestValid()
                } finally {
                    database.close()
                }
            }
            val reading = result.reading.toCgm(previousValid)
            attemptStore.updateCycle(attemptId) {
                it.copy(
                    glucosePacketReceivedAt = it.glucosePacketReceivedAt ?: now,
                    sensorAgeSeconds = reading.sensorAgeSeconds,
                    measurementTimestamp = reading.timestampEpochMs,
                    sequenceNumber = reading.sequenceNumber,
                )
            }
            attemptStore.record(
                attemptId,
                CollectorDiagnosticStage.VALIDATION,
                if (reading.status == CgmReadingStatus.INVALID) CollectorDiagnosticResult.RECOVERABLE_ERROR else CollectorDiagnosticResult.SUCCESS,
                when (reading.status) {
                    CgmReadingStatus.VALID -> "Glukosewert validiert"
                    CgmReadingStatus.SENSOR_ERROR -> "Sensorfehlerstatus validiert"
                    CgmReadingStatus.INVALID -> "Ungültiger Glukosewert verworfen"
                },
                sensorId = reading.sensorId,
                sequence = reading.sequenceNumber,
            )

            val inserted = try {
                G7ReadingDatabase(this).let { database ->
                    try {
                        database.insertOrIgnore(reading)
                    } finally {
                        database.close()
                    }
                }
            } catch (error: Throwable) {
                throw G7BleException("G7-STORE-500", "Lokaler G7-Wert konnte nicht gespeichert werden", true, error)
            }
            val storedAt = System.currentTimeMillis()
            attemptStore.updateCycle(attemptId) { it.copy(storeCompletedAt = storedAt) }
            attemptStore.record(
                attemptId,
                CollectorDiagnosticStage.STORE,
                CollectorDiagnosticResult.SUCCESS,
                when {
                    !inserted -> "Bereits gespeichertes Collector-Ereignis dedupliziert"
                    reading.status == CgmReadingStatus.VALID -> "Glukosewert lokal auf der Watch gespeichert"
                    else -> "Collector-Status lokal zur Diagnose gespeichert"
                },
                sensorId = reading.sensorId,
                sequence = reading.sequenceNumber,
                nowEpochMs = storedAt,
            )

            val documentedSensor = result.sensor.copy(
                sensorStartEpochMs = result.reading.sensorStartEpochMs,
                sensorEndEpochMs = result.reading.sensorEndEpochMs,
                graceEndEpochMs = result.reading.graceEndEpochMs,
            )
            val fresh = isFreshG7CycleReading(reading, storedAt)
            val manager = G7SessionManager(store.read().copy(sensor = documentedSensor))
            manager.authenticationSucceeded()
            val next = manager.readingReceived(reading, fresh = fresh, nowEpochMs = storedAt).copy(
                sensor = documentedSensor.copy(state = result.reading.sensorState),
                connectionState = G7ConnectionState.DISCONNECTED,
                protocolState = G7ProtocolState.WAITING_FOR_NEXT_READING,
                lastSuccessfulConnectionEpochMs = storedAt,
                activeAttemptId = null,
                lastAttemptCompletedAtEpochMs = storedAt,
            )
            store.save(next)

            attemptStore.record(
                attemptId,
                CollectorDiagnosticStage.SYNC,
                CollectorDiagnosticResult.INFO,
                "Direkter G7-Wert bleibt Watch-lokal; Tiles und Complications werden lokal aktualisiert",
                sensorId = reading.sensorId,
                sequence = reading.sequenceNumber,
            )
            applicationContext.recordG7Diagnostic(
                "G7-DATA-200",
                when {
                    reading.status == CgmReadingStatus.VALID && fresh -> "Fresh validated G7 reading stored locally on Watch"
                    reading.status == CgmReadingStatus.VALID -> "Aged validated G7 reading stored locally on Watch"
                    reading.status == CgmReadingStatus.SENSOR_ERROR -> "Validated G7 sensor-error status stored locally on Watch"
                    else -> "Invalid G7 glucose stored for diagnostics only"
                },
                metadata = mapOf(
                    "sequence" to reading.sequenceNumber,
                    "sensorState" to result.reading.sensorState,
                    "sensorClockSeconds" to reading.rawSourceTimestamp,
                    "sensorAgeSeconds" to reading.sensorAgeSeconds,
                    "measurementTimestamp" to reading.timestampEpochMs,
                    "freshCycle" to fresh,
                    "mobileBackfill" to false,
                ),
            )

            if (fresh) {
                G7ErrorNotifier.markRecovered(this)
                G7CgmAlarmCoordinator.onReading(this, reading)
            }
            // For aged/invalid packets next.lastReading still points to the last fresh value, so
            // signal loss remains anchored to real current data rather than receive time.
            G7SignalLossMonitor.scheduleFromState(this, next)
            scheduleReconnect(next)
            next.nextReconnectEpochMs?.let { reconnectAt ->
                attemptStore.record(
                    attemptId,
                    CollectorDiagnosticStage.WAITING_FOR_WINDOW,
                    CollectorDiagnosticResult.INFO,
                    "Nächstes Sensorfenster geplant",
                    durationMs = (reconnectAt - System.currentTimeMillis()).coerceAtLeast(0L),
                )
            }

            val classification = when {
                reading.status == CgmReadingStatus.INVALID -> CollectorCycleClassification.INVALID_PACKET
                reading.status == CgmReadingStatus.VALID && fresh -> CollectorCycleClassification.SUCCESS_FRESH
                reading.status == CgmReadingStatus.VALID -> CollectorCycleClassification.SUCCESS_AGED
                else -> CollectorCycleClassification.INVALID_PACKET
            }
            attemptStore.setClassification(attemptId, classification)
            val ageMinutes = ((storedAt - reading.timestampEpochMs).coerceAtLeast(0L) / 60_000L)
            attemptStore.record(
                attemptId,
                CollectorDiagnosticStage.COMPLETE,
                if (classification == CollectorCycleClassification.SUCCESS_FRESH) CollectorDiagnosticResult.SUCCESS else CollectorDiagnosticResult.RECOVERABLE_ERROR,
                when (classification) {
                    CollectorCycleClassification.SUCCESS_FRESH -> "SUCCESS_FRESH · ${reading.glucoseMgDl.toInt()} mg/dL"
                    CollectorCycleClassification.SUCCESS_AGED -> "SUCCESS_AGED · Empfangen jetzt · Messwert $ageMinutes min alt"
                    else -> "${classification.name} · kein frischer aktueller CGM-Wert"
                },
                sensorId = reading.sensorId,
                sequence = reading.sequenceNumber,
                durationMs = storedAt - startedAt,
                nowEpochMs = storedAt,
            )
            updateForeground(
                when (classification) {
                    CollectorCycleClassification.SUCCESS_FRESH -> "${reading.glucoseMgDl.toInt()} mg/dL · Verbunden"
                    CollectorCycleClassification.SUCCESS_AGED -> "Messwert empfangen · $ageMinutes min alt · Signalverlust bleibt aktiv"
                    else -> "Kein frischer G7-Wert · nächster Sensorzyklus geplant"
                },
            )
        } catch (error: G7BleException) {
            attemptStore.record(attemptId, CollectorDiagnosticStage.GATT_CLOSE, CollectorDiagnosticResult.INFO, "BLE-/GATT-Ressourcen geschlossen")
            fail(store.read(), G7CollectorError(error.errorCode, error.recoverable, System.currentTimeMillis(), error.message), attemptId, startedAt)
        } catch (_: TimeoutCancellationException) {
            attemptStore.record(attemptId, CollectorDiagnosticStage.GATT_CLOSE, CollectorDiagnosticResult.INFO, "Scan-/GATT-Zeitfenster beendet")
            fail(store.read(), G7CollectorError("G7-BLE-111", true, System.currentTimeMillis(), "Sensor aktuell nicht erreichbar – nächster automatischer Versuch folgt"), attemptId, startedAt)
        } catch (_: CancellationException) {
            attemptStore.setClassification(attemptId, CollectorCycleClassification.CANCELLED)
            attemptStore.record(
                attemptId,
                CollectorDiagnosticStage.COMPLETE,
                CollectorDiagnosticResult.CANCELLED,
                "CANCELLED · Collection-Versuch kontrolliert beendet",
                durationMs = System.currentTimeMillis() - startedAt,
            )
            store.save(store.read().copy(activeAttemptId = null, lastAttemptCompletedAtEpochMs = System.currentTimeMillis()))
        } catch (_: SecurityException) {
            fail(store.read(), G7CollectorError("G7-PERM-401", false, System.currentTimeMillis(), "Bluetooth-Berechtigung fehlt"), attemptId, startedAt)
        } catch (_: Throwable) {
            fail(store.read(), G7CollectorError("G7-INT-500", true, System.currentTimeMillis(), "Unerwarteter Collector-Fehler"), attemptId, startedAt)
        } finally {
            finishCycle(token)
        }
    }

    private fun fail(
        state: G7PersistedState,
        error: G7CollectorError,
        attemptId: Long,
        startedAtEpochMs: Long,
    ) {
        val managed = G7SessionManager(state).failure(error)
        val softWindowFailure = error.recoverable && error.code in SOFT_WINDOW_ERRORS
        val next = managed.copy(
            connectionState = G7ConnectionState.DISCONNECTED,
            protocolState = if (softWindowFailure) G7ProtocolState.RECOVERING else G7ProtocolState.ERROR,
            activeAttemptId = null,
            lastAttemptCompletedAtEpochMs = System.currentTimeMillis(),
        )
        store.save(next)
        scheduleReconnect(next)
        next.nextReconnectEpochMs?.let { reconnectAt ->
            attemptStore.record(
                attemptId,
                CollectorDiagnosticStage.WAITING_FOR_WINDOW,
                CollectorDiagnosticResult.INFO,
                "Nächstes Sensorfenster geplant",
                errorCode = error.code,
                durationMs = (reconnectAt - System.currentTimeMillis()).coerceAtLeast(0L),
            )
        }
        G7SignalLossMonitor.scheduleFromState(this, next)
        updateForeground(
            if (softWindowFailure) {
                "Dauerbetrieb aktiv · nächstes Sensorfenster wird abgewartet"
            } else {
                "${error.code}: ${error.safeMessage}"
            },
        )

        val cycle = attemptStore.snapshot().firstOrNull { it.attemptId == attemptId }?.cycle
        val classification = when {
            error.code.startsWith("G7-SETUP-") || error.code.startsWith("G7-PERM-") -> CollectorCycleClassification.SERVICE_START_FAILED
            error.code == "G7-STORE-500" -> CollectorCycleClassification.STORE_FAILED
            else -> classifyG7CycleFailure(error.code, cycle)
        }
        attemptStore.setClassification(attemptId, classification)
        attemptStore.record(
            attemptId,
            CollectorDiagnosticStage.ERROR,
            if (error.recoverable) CollectorDiagnosticResult.RECOVERABLE_ERROR else CollectorDiagnosticResult.FATAL_ERROR,
            "${classification.name} · ${error.safeMessage}",
            errorCode = error.code,
            sensorId = next.sensor?.sensorId,
            durationMs = System.currentTimeMillis() - startedAtEpochMs,
        )

        val alarmsEnabled = G7AlertPolicyStore.alarmsEnabled(this)
        if (shouldPostImmediateCollectorAlert(alarmsEnabled, error, next.sessionState)) {
            G7ErrorNotifier.show(this, error)
        }
        scope.launch {
            applicationContext.recordG7Diagnostic(
                error.code,
                error.safeMessage,
                if (error.recoverable) DiagnosticSeverity.WARNING else DiagnosticSeverity.ERROR,
                mapOf(
                    "recoverable" to error.recoverable,
                    "classification" to classification.name,
                    "canonicalWatchAlerts" to alarmsEnabled,
                    "userAlertPosted" to shouldPostImmediateCollectorAlert(alarmsEnabled, error, next.sessionState),
                    "retryCount" to next.retryCount,
                    "nextReconnectEpochMs" to next.nextReconnectEpochMs,
                    "sessionState" to next.sessionState.name,
                ),
            )
        }
    }

    private fun startForegroundCollector(message: String) {
        val notification = notification(message)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForeground(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    internal fun notification(message: String): Notification {
        val openIntent = Intent(this, G7WatchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_g7_notification)
            .setColor(0xFF6DE892.toInt())
            .setContentTitle("G7 Direct to Watch")
            .setContentText(message)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun scheduleReconnect(state: G7PersistedState) {
        if (!state.collectorEnabled) return
        val requestedAt = state.nextReconnectEpochMs ?: return
        val triggerAt = maxOf(requestedAt, System.currentTimeMillis() + MIN_RECONNECT_LEAD_MS)
        val pending = reconnectPendingIntent(this)
        val alarmManager = getSystemService(AlarmManager::class.java)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        val exactScheduled = if (exactAllowed) {
            runCatching {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                true
            }.getOrElse {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                false
            }
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            false
        }
        val power = getSystemService(PowerManager::class.java)
        val cycle = CollectorCycleTiming(
            expectedReadingEpoch = requestedAt + G7ReconnectScheduler.PRECONNECT_LEAD_MS,
            requestedReconnectEpoch = triggerAt,
            alarmKind = if (exactScheduled) CollectorAlarmKind.EXACT else CollectorAlarmKind.INEXACT,
            canScheduleExactAlarms = exactAllowed,
            batteryUnrestricted = G7BackgroundAccess.isBatteryUnrestricted(this),
            deviceIdleMode = power.isDeviceIdleMode,
            isInteractive = power.isInteractive,
            charging = runCatching { getSystemService(BatteryManager::class.java).isCharging }.getOrNull(),
        )
        attemptStore.stageScheduledCycle(cycle)
        scope.launch {
            applicationContext.recordG7Diagnostic(
                if (exactScheduled) "G7-SCHED-200" else "G7-SCHED-201",
                if (exactScheduled) "Exact G7 reconnect scheduled" else "DEGRADED BACKGROUND RELIABILITY · exact alarm unavailable; inexact fallback scheduled",
                if (exactScheduled) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                mapOf(
                    "expectedReadingEpoch" to cycle.expectedReadingEpoch,
                    "requestedReconnectEpoch" to cycle.requestedReconnectEpoch,
                    "alarmKind" to cycle.alarmKind.name,
                    "canScheduleExactAlarms" to exactAllowed,
                    "batteryUnrestricted" to cycle.batteryUnrestricted,
                    "deviceIdleMode" to cycle.deviceIdleMode,
                    "isInteractive" to cycle.isInteractive,
                    "charging" to cycle.charging,
                ),
            )
        }
    }

    private fun acquireCycleWakeLock(request: CycleRequest): Long {
        if (cycleWakeLock?.isHeld == true) return System.currentTimeMillis()
        val initialPairing = store.read().sensor?.deviceAddress.isNullOrBlank()
        val timeout = if (initialPairing && request == CycleRequest.AUTOMATIC) INITIAL_PAIRING_WAKE_LOCK_TIMEOUT_MS else NORMAL_CYCLE_WAKE_LOCK_TIMEOUT_MS
        cycleWakeLock =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:G7Collection")
                .apply {
                    setReferenceCounted(false)
                    acquire(timeout)
                }
        return System.currentTimeMillis()
    }

    private fun releaseCycleWakeLock() {
        cycleWakeLock?.let { wakeLock ->
            if (wakeLock.isHeld) wakeLock.release()
        }
        cycleWakeLock = null
    }

    private fun finishCycle(token: Long) {
        releaseCycleWakeLock()
        G7WakeHandoff.release()
        if (token == cycleToken) collectionJob = null
        val current = store.read()
        if (shouldKeepG7RuntimeForeground(current.collectorEnabled)) {
            val message = when (current.protocolState) {
                G7ProtocolState.WAITING_FOR_NEXT_READING -> "Dauerbetrieb aktiv · wartet auf nächstes Sensorfenster"
                G7ProtocolState.RECOVERING -> "Dauerbetrieb aktiv · automatische Wiederverbindung"
                G7ProtocolState.ERROR -> current.lastError?.let { "${it.code}: ${it.safeMessage}" } ?: "Collector prüfen"
                else -> "Dauerbetrieb aktiv"
            }
            updateForeground(message)
        } else {
            stopRuntimeForeground()
        }
    }

    private fun stopRuntimeForeground() {
        releaseCycleWakeLock()
        G7WakeHandoff.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseCycleWakeLock()
        G7WakeHandoff.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "app.aapswear.g7watch.START"
        const val ACTION_STOP = "app.aapswear.g7watch.STOP"
        const val ACTION_RESTART = "app.aapswear.g7watch.RESTART"
        const val ACTION_MANUAL_SCAN = "app.aapswear.g7watch.MANUAL_SCAN"
        const val ACTION_RECONNECT = "app.aapswear.g7watch.RECONNECT"
        internal const val CHANNEL = "g7_collector"
        internal const val NOTIFICATION_ID = 7001
        private const val NORMAL_CYCLE_WAKE_LOCK_TIMEOUT_MS = 3L * 60_000L
        private const val INITIAL_PAIRING_WAKE_LOCK_TIMEOUT_MS = 31L * 60_000L
        private const val MIN_RECONNECT_LEAD_MS = 1_000L
        private val SOFT_WINDOW_ERRORS = setOf(G7_GATT_133_ERROR_CODE, "G7-BLE-107", "G7-BLE-111")

        fun start(context: Context) {
            val app = context.applicationContext
            val stateStore = G7SensorStateStore(app)
            val current = stateStore.read()
            if (current.sensor == null || G7CredentialStore(app).read() == null) return
            if (!current.collectorEnabled) {
                stateStore.save(G7SessionManager(current).startCollector())
            }
            G7SignalLossMonitor.scheduleFromState(app, stateStore.read())
            app.startForegroundService(
                Intent(app, G7CollectorService::class.java).setAction(ACTION_START),
            )
        }

        fun startScheduledReconnect(context: Context) {
            val app = context.applicationContext
            val current = G7SensorStateStore(app).read()
            if (!current.collectorEnabled || current.sensor == null || G7CredentialStore(app).read() == null) {
                G7WakeHandoff.release()
                return
            }
            app.startForegroundService(
                Intent(app, G7CollectorService::class.java).setAction(ACTION_RECONNECT),
            )
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            val stateStore = G7SensorStateStore(app)
            stateStore.save(G7SessionManager(stateStore.read()).stop())
            cancelScheduledReconnect(app)
            G7SignalLossMonitor.cancel(app)
            G7ErrorNotifier.clearActive(app)
            G7CgmAlarmCoordinator.clearSuppressed(app)
            G7WakeHandoff.release()
            app.stopService(Intent(app, G7CollectorService::class.java))
            app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }

        fun restart(context: Context) {
            val app = context.applicationContext
            val current = G7SensorStateStore(app).read()
            if (!current.collectorEnabled || current.sensor == null || G7CredentialStore(app).read() == null) return
            app.startForegroundService(Intent(app, G7CollectorService::class.java).setAction(ACTION_RESTART))
        }

        fun scanNow(context: Context) {
            val app = context.applicationContext
            val current = G7SensorStateStore(app).read()
            if (!current.collectorEnabled || current.sensor == null || G7CredentialStore(app).read() == null) return
            app.startForegroundService(Intent(app, G7CollectorService::class.java).setAction(ACTION_MANUAL_SCAN))
        }

        private fun reconnectPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, G7ReconnectReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun cancelScheduledReconnect(context: Context) {
            val pending = reconnectPendingIntent(context)
            context.getSystemService(AlarmManager::class.java).cancel(pending)
            pending.cancel()
        }
    }
}

private enum class CycleRequest { AUTOMATIC, MANUAL, RESTART }

private fun G7CollectorService.updateAttemptCycleForProtocolState(
    attemptId: Long,
    state: G7ProtocolState,
    nowEpochMs: Long,
) {
    G7CollectorDiagnosticStore(this).updateCycle(attemptId) { cycle ->
        when (state) {
            G7ProtocolState.SCANNING -> cycle.copy(scanStartedAt = cycle.scanStartedAt ?: nowEpochMs)
            G7ProtocolState.SENSOR_FOUND -> cycle.copy(advertisementFoundAt = cycle.advertisementFoundAt ?: nowEpochMs)
            G7ProtocolState.CONNECTING -> cycle.copy(connectGattStartedAt = cycle.connectGattStartedAt ?: nowEpochMs)
            G7ProtocolState.DISCOVERING_SERVICES -> cycle.copy(
                gattConnectedAt = cycle.gattConnectedAt ?: nowEpochMs,
                serviceDiscoveryAt = cycle.serviceDiscoveryAt ?: nowEpochMs,
            )
            G7ProtocolState.AUTHENTICATION_START -> cycle.copy(authStartedAt = cycle.authStartedAt ?: nowEpochMs)
            G7ProtocolState.AUTHENTICATED -> cycle.copy(authSucceededAt = cycle.authSucceededAt ?: nowEpochMs)
            G7ProtocolState.RECEIVING_GLUCOSE -> cycle.copy(glucosePacketReceivedAt = cycle.glucosePacketReceivedAt ?: nowEpochMs)
            else -> cycle
        }
    }
}

private fun G7CollectorService.recordAttemptProtocolState(
    attemptId: Long,
    state: G7ProtocolState,
    sensorId: String,
) {
    val store = G7CollectorDiagnosticStore(this)
    fun record(
        stage: CollectorDiagnosticStage,
        result: CollectorDiagnosticResult,
        message: String,
    ) = store.record(attemptId, stage, result, message, sensorId = sensorId)

    when (state) {
        G7ProtocolState.SCANNING -> {
            record(CollectorDiagnosticStage.SCAN_START, CollectorDiagnosticResult.STARTED, "Scan gestartet")
            record(CollectorDiagnosticStage.SCANNING, CollectorDiagnosticResult.INFO, "Suche nach Sensor")
        }
        G7ProtocolState.SENSOR_FOUND ->
            record(CollectorDiagnosticStage.ADVERTISEMENT_FOUND, CollectorDiagnosticResult.SUCCESS, "G7 Advertisement erkannt · connectable=true")
        G7ProtocolState.CONNECTING ->
            record(CollectorDiagnosticStage.CONNECT_REQUEST, CollectorDiagnosticResult.STARTED, "GATT-Verbindung angefordert")
        G7ProtocolState.DISCOVERING_SERVICES -> {
            record(CollectorDiagnosticStage.GATT_CONNECTED, CollectorDiagnosticResult.SUCCESS, "GATT verbunden")
            record(CollectorDiagnosticStage.SERVICE_DISCOVERY, CollectorDiagnosticResult.STARTED, "Service Discovery")
        }
        G7ProtocolState.ENABLING_NOTIFICATIONS ->
            record(CollectorDiagnosticStage.SERVICE_READY, CollectorDiagnosticResult.SUCCESS, "Dexcom Service gefunden")
        G7ProtocolState.AUTHENTICATION_START ->
            record(CollectorDiagnosticStage.AUTH_START, CollectorDiagnosticResult.STARTED, "Authentifizierung gestartet")
        G7ProtocolState.AUTHENTICATING -> {
            record(CollectorDiagnosticStage.AUTH_CHALLENGE, CollectorDiagnosticResult.INFO, "Authentifizierungs-Challenge empfangen")
            record(CollectorDiagnosticStage.AUTH_RESPONSE, CollectorDiagnosticResult.INFO, "Authentifizierungs-Response gesendet")
        }
        G7ProtocolState.AUTHENTICATED ->
            record(CollectorDiagnosticStage.AUTH_SUCCESS, CollectorDiagnosticResult.SUCCESS, "Authentifizierung erfolgreich")
        G7ProtocolState.REQUESTING_GLUCOSE ->
            record(CollectorDiagnosticStage.GLUCOSE_REQUEST, CollectorDiagnosticResult.STARTED, "Glukose Request")
        G7ProtocolState.RECEIVING_GLUCOSE ->
            record(CollectorDiagnosticStage.GLUCOSE_RECEIVED, CollectorDiagnosticResult.SUCCESS, "Glukose Packet empfangen")
        G7ProtocolState.RECOVERING -> {
            record(CollectorDiagnosticStage.RETRY, CollectorDiagnosticResult.RECOVERABLE_ERROR, "Begrenzter Retry")
            record(CollectorDiagnosticStage.RECOVERY, CollectorDiagnosticResult.INFO, "Recovery aktiv")
        }
        else -> Unit
    }
}

private fun G7ProtocolState.toConnectionState(): G7ConnectionState = when (this) {
    G7ProtocolState.SCANNING -> G7ConnectionState.SCANNING
    G7ProtocolState.CONNECTING -> G7ConnectionState.CONNECTING
    G7ProtocolState.DISCOVERING,
    G7ProtocolState.DISCOVERING_SERVICES,
    G7ProtocolState.ENABLING_NOTIFICATIONS,
    -> G7ConnectionState.DISCOVERING
    G7ProtocolState.AUTHENTICATION_START,
    G7ProtocolState.AUTHENTICATING,
    G7ProtocolState.AUTHENTICATED,
    G7ProtocolState.BONDING,
    G7ProtocolState.REQUESTING_GLUCOSE,
    G7ProtocolState.RECEIVING_GLUCOSE,
    -> G7ConnectionState.CONNECTED
    else -> G7ConnectionState.DISCONNECTED
}

private fun G7ProtocolState.toSessionState(): G7SessionState = when (this) {
    G7ProtocolState.AUTHENTICATED,
    G7ProtocolState.REQUESTING_GLUCOSE,
    G7ProtocolState.RECEIVING_GLUCOSE,
    -> G7SessionState.ACTIVE
    G7ProtocolState.AUTHENTICATION_START,
    G7ProtocolState.AUTHENTICATING,
    G7ProtocolState.BONDING,
    -> G7SessionState.AUTHENTICATING
    G7ProtocolState.WAITING_FOR_NEXT_READING -> G7SessionState.WAITING_FOR_NEXT_READING
    G7ProtocolState.RECOVERING,
    G7ProtocolState.ERROR,
    -> G7SessionState.RECOVERING
    else -> G7SessionState.INITIAL_SETUP
}

private fun G7ProtocolState.label(): String = when (this) {
    G7ProtocolState.SCANNING -> "Sensor wird gesucht"
    G7ProtocolState.CONNECTING -> "Sensor wird verbunden"
    G7ProtocolState.DISCOVERING_SERVICES -> "G7-Dienste werden geprüft"
    G7ProtocolState.ENABLING_NOTIFICATIONS -> "G7-Datenkanäle werden geöffnet"
    G7ProtocolState.AUTHENTICATION_START,
    G7ProtocolState.AUTHENTICATING,
    -> "Sensor wird authentifiziert"
    G7ProtocolState.BONDING -> "Sensor wird gekoppelt"
    G7ProtocolState.AUTHENTICATED -> "Sensor ist authentifiziert"
    G7ProtocolState.REQUESTING_GLUCOSE -> "Glukosewert wird angefordert"
    G7ProtocolState.RECEIVING_GLUCOSE -> "Glukosewert wird geprüft"
    G7ProtocolState.RECOVERING -> "Nächstes Sensorfenster wird abgewartet"
    else -> name.replace('_', ' ')
}

private fun G7ProtocolState.diagnosticCode(): String = when (this) {
    G7ProtocolState.SCANNING,
    G7ProtocolState.CONNECTING,
    G7ProtocolState.DISCOVERING,
    G7ProtocolState.DISCOVERING_SERVICES,
    G7ProtocolState.ENABLING_NOTIFICATIONS,
    -> "G7-BLE-110"
    G7ProtocolState.RECOVERING -> "G7-BLE-133"
    G7ProtocolState.AUTHENTICATION_START,
    G7ProtocolState.AUTHENTICATING,
    G7ProtocolState.BONDING,
    G7ProtocolState.AUTHENTICATED,
    -> "G7-AUTH-110"
    G7ProtocolState.REQUESTING_GLUCOSE,
    G7ProtocolState.RECEIVING_GLUCOSE,
    G7ProtocolState.WAITING_FOR_NEXT_READING,
    -> "G7-DATA-110"
    else -> "G7-STATE-100"
}
