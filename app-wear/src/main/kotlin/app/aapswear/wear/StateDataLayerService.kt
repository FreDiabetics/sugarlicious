package app.aapswear.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import app.aapswear.complications.ActiveComplicationRegistry
import app.aapswear.complications.AllProviders
import app.aapswear.complications.ComplicationUpdatePlanner
import app.aapswear.complications.G7LocalReadingResolver
import app.aapswear.model.CanonicalCgmHistory
import app.aapswear.model.SugarliciousComplicationIds
import app.aapswear.model.DiagnosticSeverity
import app.aapswear.model.GlucoseSample
import app.aapswear.model.TherapyDisplayState
import app.aapswear.protocol.WatchRuntimeStatus
import app.aapswear.protocol.WearProtocol
import app.aapswear.storage.PersistentPredictionCache
import app.aapswear.storage.TherapyStateStore
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

internal fun shouldAcceptPhoneState(
    previous: TherapyDisplayState?,
    incoming: TherapyDisplayState,
): Boolean {
    if (previous == null) return true
    if (incoming.receivedAtEpochMs >= previous.receivedAtEpochMs) return true

    val previousGlucoseAt = previous.glucose?.measuredAtEpochMs ?: Long.MIN_VALUE
    val incomingGlucoseAt = incoming.glucose?.measuredAtEpochMs ?: Long.MIN_VALUE
    return incomingGlucoseAt > previousGlucoseAt
}

class StateDataLayerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateSyncMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        ensureRuntimeChannel()
        startForegroundRuntime()
        scope.launch {
            if (!WearBackgroundAccess.isBatteryUnrestricted(applicationContext)) {
                applicationContext.recordWatchDiagnostic(
                    "RUNTIME",
                    "WATCH-BG-201",
                    "Wear runtime is active while battery optimization is still enabled",
                    DiagnosticSeverity.WARNING,
                )
            }
            runCatching {
                requestLatestState(this@StateDataLayerService)
            }
                .onSuccess { applicationContext.recordWatchDiagnostic("SYNC", "SYNC-PHONE-100", "Requested latest state from phone") }
                .onFailure { error ->
                    applicationContext.recordWatchDiagnostic(
                        "SYNC",
                        "SYNC-PHONE-503",
                        "Could not request latest state from phone",
                        DiagnosticSeverity.WARNING,
                        mapOf("error" to error.javaClass.simpleName),
                    )
                }
            runCatching { G7BackfillSync.sendPending(this@StateDataLayerService) }
        }
    }

    override fun onPeerConnected(peer: Node) {
        super.onPeerConnected(peer)
        scope.launch {
            runCatching { G7BackfillSync.sendPending(this@StateDataLayerService, peer.id) }
                .onSuccess { dispatch ->
                    applicationContext.recordWatchDiagnostic(
                        "G7-SYNC",
                        if (dispatch == null) "G7-SYNC-204" else "G7-SYNC-101",
                        if (dispatch == null) "Mobile connected with no pending G7 history" else "Pending G7 history sent after Mobile reconnect",
                        metadata = mapOf("batchId" to dispatch?.batchId, "readingCount" to dispatch?.readingIds?.size),
                    )
                }
                .onFailure { error ->
                    applicationContext.recordWatchDiagnostic(
                        "G7-SYNC",
                        "G7-SYNC-503",
                        "Pending G7 history could not be sent after Mobile reconnect",
                        DiagnosticSeverity.WARNING,
                        mapOf("error" to error.javaClass.simpleName),
                    )
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundRuntime()
        return START_STICKY
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach

            when (event.dataItem.uri.path) {
                WearProtocol.COMPLICATION_PRESET_PATH ->
                    persistComplicationPreset(event)

                WearProtocol.WATCH_CONFIG_PATH ->
                    persistWatchConfig(event)

                WearProtocol.WATCH_COLOR_SYNC_PATH ->
                    persistWatchColors(event)

                WearProtocol.STATE_PATH ->
                    persistTherapyState(event)
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearProtocol.STATE_PATH ->
                persistTherapyState(event.data, "message")

            WearProtocol.WATCH_FACE_APPLY_PATH ->
                applyWatchFace(event)

            WearProtocol.WATCH_RUNTIME_REQUEST_PATH ->
                scope.launch {
                    sendRuntimeStatus(
                        applicationContext,
                        event.sourceNodeId,
                    )
                }
            WearProtocol.G7_SETUP_PATH -> configureG7Collector(event)
            WearProtocol.G7_SYNC_REQUEST_PATH ->
                scope.launch {
                    runCatching { G7BackfillSync.sendPending(this@StateDataLayerService, event.sourceNodeId) }
                }
            WearProtocol.G7_READING_ACK_PATH ->
                scope.launch {
                    val ack = runCatching { WearProtocol.decodeG7ReadingAck(event.data) }.getOrNull()
                    if (ack == null) {
                        applicationContext.recordWatchDiagnostic(
                            "G7-SYNC",
                            "G7-SYNC-401",
                            "Invalid Mobile acknowledgement rejected",
                            DiagnosticSeverity.WARNING,
                        )
                        return@launch
                    }
                    val count = G7BackfillSync.acknowledge(this@StateDataLayerService, ack)
                    applicationContext.recordWatchDiagnostic(
                        "G7-SYNC",
                        "G7-SYNC-200",
                        "Mobile acknowledgement forwarded to G7 database",
                        metadata = mapOf("batchId" to ack.batchId, "acknowledged" to count),
                    )
                }
            WearProtocol.DIAGNOSTICS_REQUEST_PATH ->
                scope.launch {
                    runCatching { sendWatchDiagnostics(applicationContext, event.sourceNodeId) }
                        .onSuccess { applicationContext.recordWatchDiagnostic("DIAGNOSTICS", "DIAG-SYNC-200", "Diagnostics sent to phone") }
                        .onFailure { error ->
                            applicationContext.recordWatchDiagnostic(
                                "DIAGNOSTICS",
                                "DIAG-SYNC-503",
                                "Diagnostics could not be sent to phone",
                                DiagnosticSeverity.WARNING,
                                mapOf("error" to error.javaClass.simpleName),
                            )
                        }
                }
        }
    }

    private fun configureG7Collector(event: MessageEvent) {
        val command = runCatching { WearProtocol.decodeG7Setup(event.data) }.getOrNull()
        if (command == null) {
            scope.launch { applicationContext.recordWatchDiagnostic("G7", "G7-SETUP-401", "Invalid G7 setup command", DiagnosticSeverity.WARNING) }
            return
        }
        val intent = Intent("app.aapswear.g7watch.CONFIGURE")
            .setComponent(
                ComponentName(
                    "app.aapswear.g7watch",
                    "app.aapswear.g7watch.G7SetupReceiver",
                ),
            )
            .putExtra("pairing_code", command.pairingCode)
            .putExtra("sensor_serial", command.sensorSerial)
            .putExtra("gtin", command.gtin)
        sendBroadcast(intent, "app.aapswear.g7watch.permission.CONFIGURE_G7")
        scope.launch {
            applicationContext.recordWatchDiagnostic(
                "G7",
                "G7-SETUP-200",
                "G7 setup forwarded to collector",
                metadata = mapOf("serialAvailable" to !command.sensorSerial.isNullOrBlank(), "gtinAvailable" to !command.gtin.isNullOrBlank()),
            )
        }
    }

    private fun applyWatchFace(event: MessageEvent) {
        val index =
            event.data
                .decodeToString()
                .toIntOrNull()
                ?.coerceAtLeast(0)
                ?: return

        val appContext = applicationContext
        val sourceNodeId = event.sourceNodeId

        watchFacePushScope.launch {
            watchFacePushMutex.withLock {
                val status =
                    SugarliciousWatchFacePush.apply(
                        appContext,
                        index,
                    )
                val activated = status.equals("Watchface aktiv", ignoreCase = true)

                applicationContext.recordWatchDiagnostic(
                    "WATCHFACE",
                    if (activated) "WATCHFACE-APPLY-200" else "WATCHFACE-APPLY-409",
                    status,
                    if (activated) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                    mapOf("index" to index),
                )

                runCatching {
                    Wearable
                        .getMessageClient(appContext)
                        .sendMessage(
                            sourceNodeId,
                            WearProtocol.WATCH_FACE_STATUS_PATH,
                            status.encodeToByteArray(),
                        )
                        .await()
                }

                runCatching {
                    sendRuntimeStatus(
                        appContext,
                        sourceNodeId,
                    )
                }
            }
        }
    }

    private fun persistComplicationPreset(event: DataEvent) {
        val dataMap =
            runCatching {
                DataMapItem.fromDataItem(event.dataItem).dataMap
            }.getOrNull() ?: return
        val ids =
            dataMap
                .getIntegerArrayList("ids")
                .orEmpty()
                .filter { it in SugarliciousComplicationIds.all }
                .distinct()
                .take(MAX_PRESET_ITEMS)
        val graphHours =
            dataMap
                .getInt("graphHours", 3)
                .takeIf { it in WearDisplayPreferences.allowedGraphHours }
                ?: 3

        getSharedPreferences(
            COMPLICATION_SETUP_PREFS,
            Context.MODE_PRIVATE,
        )
            .edit()
            .putString(
                COMPLICATION_PRESET_KEY,
                ids.joinToString(","),
            )
            .putInt(
                COMPLICATION_GRAPH_HOURS_KEY,
                graphHours,
            )
            .apply()

        getSharedPreferences(
            WearDisplayPreferences.PREFS,
            Context.MODE_PRIVATE,
        )
            .edit()
            .putInt(
                "complication_graph_hours",
                graphHours,
            )
            .apply()

        requestAllComplicationUpdates()
        scope.launch {
            applicationContext.recordWatchDiagnostic(
                "COMPLICATION",
                "COMP-CONFIG-200",
                "Complication preset saved",
                metadata = mapOf("count" to ids.size, "graphHours" to graphHours),
            )
        }
    }

    private fun persistWatchConfig(event: DataEvent) {
        val config =
            runCatching {
                WearProtocol.decodeConfig(
                    event.dataItem.data ?: return,
                )
            }.getOrNull()
        if (config == null) {
            scope.launch { applicationContext.recordWatchDiagnostic("CONFIG", "CONFIG-401", "Invalid Watch configuration", DiagnosticSeverity.WARNING) }
            return
        }

        WearDisplayPreferences.save(
            this,
            config,
        )
        scope.launch {
            applicationContext.recordWatchDiagnostic(
                "CONFIG",
                "CONFIG-200",
                "Watch configuration saved",
                metadata = mapOf("graphHours" to config.graphHours, "showPredictions" to config.showPredictions, "dataSource" to config.dataSource),
            )
        }
    }

    private fun persistWatchColors(event: DataEvent) {
        val payload = event.dataItem.data ?: return
        val sync = runCatching { WearProtocol.decodeWatchColorSync(payload) }.getOrNull()
        if (sync == null) {
            scope.launch {
                applicationContext.recordWatchDiagnostic(
                    "CONFIG",
                    "COLOR-SYNC-401",
                    "Invalid Mobile graph color payload rejected",
                    DiagnosticSeverity.WARNING,
                )
            }
            return
        }
        WearDisplayPreferences.applySyncedColors(this, sync)
        requestAllComplicationUpdates()
        requestSugarliciousTileUpdates(this)
        sendBroadcast(
            Intent("app.aapswear.g7watch.APPLY_GRAPH_COLORS")
                .setComponent(
                    ComponentName(
                        "app.aapswear.g7watch",
                        "app.aapswear.g7watch.G7ColorSyncReceiver",
                    ),
                )
                .putExtra("color_payload", payload),
            "app.aapswear.g7watch.permission.CONFIGURE_G7",
        )
        scope.launch {
            applicationContext.recordWatchDiagnostic(
                "CONFIG",
                "COLOR-SYNC-200",
                "Mobile graph colors applied on Wear and forwarded to G7 Collector",
                metadata = mapOf("schemaVersion" to sync.schemaVersion, "sentAtEpochMs" to sync.sentAtEpochMs),
            )
        }
    }

    private fun persistTherapyState(event: DataEvent) {
        persistTherapyState(event.dataItem.data, "data_item")
    }

    private fun persistTherapyState(payload: ByteArray?, transport: String) {
        val incoming =
            runCatching {
                WearProtocol.decode(payload ?: return)
            }.getOrNull()
        if (incoming == null) {
            scope.launch {
                applicationContext.recordWatchDiagnostic(
                    "SOURCE",
                    "SRC-PHONE-401",
                    "Invalid phone state payload",
                    DiagnosticSeverity.WARNING,
                    mapOf("transport" to transport),
                )
            }
            return
        }

        scope.launch {
            stateSyncMutex.withLock {
                val store =
                    TherapyStateStore(
                        this@StateDataLayerService,
                    )
                val old = store.state.first()
                val now = System.currentTimeMillis()

                // MessageClient and DataClient intentionally carry the same state. They are
                // independent transports, so a delayed durable DataItem can arrive after a newer
                // low-latency message. Never let that delayed copy roll the Watch backwards.
                if (!shouldAcceptPhoneState(old, incoming)) {
                    applicationContext.recordWatchDiagnostic(
                        "SYNC",
                        "SYNC-PHONE-202",
                        "Older phone state ignored on Watch",
                        metadata = mapOf(
                            "transport" to transport,
                            "incomingReceivedAt" to incoming.receivedAtEpochMs,
                            "storedReceivedAt" to (old?.receivedAtEpochMs ?: 0L),
                        ),
                    )
                    return@withLock
                }

                val historyInputs =
                    buildList {
                        addAll(old?.glucoseHistory.orEmpty())
                        addAll(incoming.glucoseHistory)
                        incoming.glucose?.let {
                            add(
                                GlucoseSample(
                                    valueMgDl = it.valueMgDl,
                                    measuredAtEpochMs = it.measuredAtEpochMs,
                                    source = it.source,
                                    sensorId = it.sensorId,
                                    sessionId = it.sessionId,
                                    sequenceNumber = it.sequenceNumber,
                                    receivedAtEpochMs = it.receivedAtEpochMs,
                                    quality = it.quality,
                                ),
                            )
                        }
                    }

                val history =
                    CanonicalCgmHistory.merge(
                        samples = historyInputs,
                        nowEpochMs = now,
                        preferredSource = incoming.source,
                        windowMs = HISTORY_WINDOW_MS,
                        futureToleranceMs = FUTURE_TOLERANCE_MS,
                        maxPoints = MAX_HISTORY_POINTS,
                    )

                val merged =
                    PersistentPredictionCache.merge(
                        previous = old,
                        incoming = incoming.copy(glucoseHistory = history),
                        nowEpochMs = now,
                    )
                val selectedSource = WearDisplayPreferences.read(this@StateDataLayerService).dataSource
                val canonicalForAlerts =
                    G7LocalReadingResolver.resolve(
                        context = this@StateDataLayerService,
                        fallback = merged,
                        nowEpochMs = now,
                        dataSource = selectedSource,
                    )
                publishG7AlertMode(this@StateDataLayerService, selectedSource, canonicalForAlerts)
                applicationContext.recordWatchDiagnostic(
                    "PREDICTION",
                    if (incoming.glucosePredictions.isEmpty() && merged.glucosePredictions.isNotEmpty()) "PRED-CACHE-203" else "PRED-DATA-200",
                    if (incoming.glucosePredictions.isEmpty() && merged.glucosePredictions.isNotEmpty()) "Cached predictions retained on Watch" else "Phone state merged on Watch",
                    metadata = mapOf(
                        "incomingPredictions" to incoming.glucosePredictions.size,
                        "displayPredictions" to merged.glucosePredictions.size,
                        "historyCount" to history.size,
                        "transport" to transport,
                    ),
                )
                val meaningfulState =
                    old?.copy(receivedAtEpochMs = merged.receivedAtEpochMs)
                if (meaningfulState == merged) return@withLock

                store.save(merged)
                requestComplicationUpdates(
                    ComplicationUpdatePlanner.affectedProviders(old, merged),
                )
                requestSugarliciousTileUpdates(this@StateDataLayerService)
                applicationContext.recordWatchDiagnostic(
                    "SYNC",
                    if (transport == "message") "SYNC-PHONE-201" else "SYNC-PHONE-200",
                    if (transport == "message") "Immediate phone state applied on Watch" else "Durable phone state applied on Watch",
                    metadata = mapOf("transport" to transport),
                )
            }
        }
    }

    private suspend fun sendRuntimeStatus(
        context: Context,
        nodeId: String,
    ) {
        val status =
            WatchRuntimeStatus(
                activeSugarliciousFaceIndex =
                    SugarliciousWatchFacePush
                        .activeFaceIndex(context),
                activeComplicationIds =
                    ActiveComplicationRegistry
                        .activeCatalogIds(context),
                sentAtEpochMs =
                    System.currentTimeMillis(),
            )

        Wearable
            .getMessageClient(context)
            .sendMessage(
                nodeId,
                WearProtocol.WATCH_RUNTIME_STATUS_PATH,
                WearProtocol.encodeRuntimeStatus(status),
            )
            .await()
    }

    private fun requestAllComplicationUpdates() {
        requestComplicationUpdates(AllProviders.classes)
    }

    private fun requestComplicationUpdates(providers: List<Class<*>>) {
        providers.forEach { provider ->
            ComplicationDataSourceUpdateRequester
                .create(
                    this,
                    ComponentName(this, provider),
                )
                .requestUpdateAll()
        }
    }

    private fun ensureRuntimeChannel() {
        val channel = NotificationChannel(
            RUNTIME_CHANNEL,
            "Sugarlicious Wear Dauerbetrieb",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Permanenter Sugarlicious Wear Datenempfang"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundRuntime() {
        val notification = runtimeNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                RUNTIME_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(RUNTIME_NOTIFICATION_ID, notification)
        }
    }

    internal fun runtimeNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WearActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val batteryUnrestricted = WearBackgroundAccess.isBatteryUnrestricted(this)
        return Notification.Builder(this, RUNTIME_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Sugarlicious Wear")
            .setContentText(
                if (batteryUnrestricted) {
                    "Dauerbetrieb aktiv"
                } else {
                    "Akkuoptimierung aktiv – Dauerbetrieb freigeben"
                },
            )
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_RUNTIME = "app.aapswear.wear.START_RUNTIME"
        internal const val RUNTIME_CHANNEL = "sugarlicious_wear_runtime"
        internal const val RUNTIME_NOTIFICATION_ID = 6101

        fun start(context: Context) {
            val app = context.applicationContext
            app.startForegroundService(
                Intent(app, StateDataLayerService::class.java)
                    .setAction(ACTION_START_RUNTIME),
            )
        }

        private val watchFacePushScope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.IO,
            )
        private val watchFacePushMutex = Mutex()

        private const val HISTORY_WINDOW_MS =
            24 * 60 * 60_000L
        private const val FUTURE_TOLERANCE_MS =
            5 * 60_000L
        private const val MAX_HISTORY_POINTS = 300

        private const val COMPLICATION_SETUP_PREFS =
            "complication_setup"
        private const val COMPLICATION_PRESET_KEY =
            "selected_ids"
        private const val COMPLICATION_GRAPH_HOURS_KEY =
            "graph_hours"
        private const val MAX_PRESET_ITEMS = 4
    }
}

suspend fun requestLatestState(
    context: Context,
): Int {
    val nodes =
        Wearable
            .getNodeClient(context)
            .connectedNodes
            .await()

    nodes.forEach { node ->
        runCatching {
            Wearable
                .getMessageClient(context)
                .sendMessage(
                    node.id,
                    WearProtocol.REQUEST_PATH,
                    byteArrayOf(),
                )
                .await()
        }

        runCatching {
            Wearable
                .getMessageClient(context)
                .sendMessage(
                    node.id,
                    WearProtocol.WATCH_CONFIG_REQUEST_PATH,
                    byteArrayOf(),
                )
                .await()
        }
    }

    return nodes.size
}
