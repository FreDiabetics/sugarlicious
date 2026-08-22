package app.aapswear.g7watch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import app.aapswear.g7.G7AuthenticationSession
import app.aapswear.g7.G7GattProfile
import app.aapswear.g7.G7GlucosePacketParser
import app.aapswear.g7.G7ProtocolState
import app.aapswear.g7.G7Reading
import app.aapswear.g7.G7Scanner
import app.aapswear.g7.G7Sensor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val G7_INITIAL_PAIRING_SCAN_TIMEOUT_MS = 30 * 60_000L
internal const val G7_RECONNECT_SCAN_TIMEOUT_MS = 90_000L
internal const val G7_GATT_133_ERROR_CODE = "G7-GATT-133"

internal fun g7ScanTimeoutMs(sensor: G7Sensor): Long =
    if (sensor.deviceAddress.isNullOrBlank()) G7_INITIAL_PAIRING_SCAN_TIMEOUT_MS else G7_RECONNECT_SCAN_TIMEOUT_MS

internal fun knownG7AddressMatches(knownAddress: String?, candidateAddress: String): Boolean? =
    knownAddress?.takeIf { it.isNotBlank() }?.equals(candidateAddress, ignoreCase = true)

internal fun isConnectableG7Advertisement(connectable: Boolean): Boolean = connectable

internal enum class G7WriteCallbackDisposition {
    EXPECTED_SUCCESS,
    EXPECTED_FAILURE,
    STALE_SUCCESS,
    STALE_FAILURE,
}

internal fun classifyG7WriteCallback(
    expectedUuid: UUID,
    actualUuid: UUID,
    status: Int,
): G7WriteCallbackDisposition = when {
    actualUuid == expectedUuid && status == BluetoothGatt.GATT_SUCCESS -> G7WriteCallbackDisposition.EXPECTED_SUCCESS
    actualUuid == expectedUuid -> G7WriteCallbackDisposition.EXPECTED_FAILURE
    status == BluetoothGatt.GATT_SUCCESS -> G7WriteCallbackDisposition.STALE_SUCCESS
    else -> G7WriteCallbackDisposition.STALE_FAILURE
}

internal fun interface G7DeviceMatcher {
    fun matches(device: BluetoothDevice, advertisedName: String?, knownSensor: G7Sensor?): Boolean
}

internal fun isG7AdvertisedName(name: String?): Boolean =
    name?.uppercase()?.matches(Regex("^DX(?:CM|01|02)[A-Z0-9]{0,8}$")) == true

internal class KnownG7DeviceMatcher : G7DeviceMatcher {
    @SuppressLint("MissingPermission")
    override fun matches(device: BluetoothDevice, advertisedName: String?, knownSensor: G7Sensor?): Boolean {
        knownG7AddressMatches(knownSensor?.deviceAddress, device.address)?.let { return it }
        val name = advertisedName ?: runCatching { device.name }.getOrNull() ?: return false
        return isG7AdvertisedName(name)
    }
}

internal class AndroidG7Scanner(
    private val context: Context,
    private val matcher: G7DeviceMatcher = KnownG7DeviceMatcher(),
) : G7Scanner {
    @SuppressLint("MissingPermission")
    override suspend fun findKnownSensor(sensor: G7Sensor?, timeoutMs: Long): G7Sensor? {
        requirePermission(Manifest.permission.BLUETOOTH_SCAN, "G7-BLE-101")
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            ?: throw G7BleException("G7-BLE-102", "Bluetooth ist auf der Uhr nicht verfügbar", false)
        if (!adapter.isEnabled) throw G7BleException("G7-BLE-103", "Bluetooth ist ausgeschaltet", true)
        val scanner = adapter.bluetoothLeScanner
            ?: throw G7BleException("G7-BLE-104", "Bluetooth-Suche ist nicht verfügbar", true)

        return suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val handler = android.os.Handler(context.mainLooper)
            var timeoutCallback: Runnable? = null
            lateinit var callback: ScanCallback
            fun finish(result: G7Sensor?, error: Throwable? = null) {
                if (!finished.compareAndSet(false, true)) return
                timeoutCallback?.let(handler::removeCallbacks)
                timeoutCallback = null
                runCatching { scanner.stopScan(callback) }
                if (!continuation.isActive) return
                if (error != null) continuation.resumeWithException(error) else continuation.resume(result)
            }
            callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (!isConnectableG7Advertisement(result.isConnectable)) return
                    val advertisedName = result.scanRecord?.deviceName
                    if (!matcher.matches(result.device, advertisedName, sensor)) return
                    val name = advertisedName ?: runCatching { result.device.name }.getOrNull() ?: sensor?.deviceName
                    val sensorId = sensor?.sensorId ?: name ?: "Dexcom-G7"
                    finish(
                        G7Sensor(
                            sensorId = sensorId,
                            sessionId = sensor?.sessionId ?: sensorId,
                            deviceName = name ?: "Dexcom G7",
                            deviceAddress = result.device.address,
                            sensorStartEpochMs = sensor?.sensorStartEpochMs,
                            state = sensor?.state ?: app.aapswear.g7.G7SensorState.UNKNOWN,
                        ),
                    )
                }

                override fun onScanFailed(errorCode: Int) {
                    finish(null, G7BleException("G7-BLE-105", "Sensorsuche fehlgeschlagen ($errorCode)", true))
                }
            }
            runCatching {
                scanner.startScan(
                    null,
                    ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .build(),
                    callback,
                )
            }.onFailure { finish(null, G7BleException("G7-BLE-106", "Sensorsuche konnte nicht gestartet werden", true, it)) }
            if (!finished.get()) {
                timeoutCallback = Runnable { finish(null) }
                handler.postDelayed(
                    requireNotNull(timeoutCallback),
                    timeoutMs.coerceIn(5_000L, G7_INITIAL_PAIRING_SCAN_TIMEOUT_MS),
                )
            }
            continuation.invokeOnCancellation { finish(null) }
        }
    }

    private fun requirePermission(permission: String, code: String) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw G7BleException(code, "Bluetooth-Berechtigung fehlt", false)
        }
    }
}

internal data class G7CollectionResult(
    val sensor: G7Sensor,
    val reading: G7Reading,
    val sharedKey: ByteArray?,
)

internal class G7BleException(
    val errorCode: String,
    override val message: String,
    val recoverable: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)

internal class AndroidG7Collector(
    private val context: Context,
    private val scanner: G7Scanner = AndroidG7Scanner(context),
    private val packetParser: G7GlucosePacketParser = G7GlucosePacketParser(),
) {
    suspend fun collect(
        initialSensor: G7Sensor,
        credentials: StoredG7Credentials,
        onState: (G7ProtocolState) -> Unit,
        onSharedKey: (String, ByteArray) -> Unit = { _, _ -> },
        scanTimeoutMsOverride: Long? = null,
    ): G7CollectionResult {
        var sensor = initialSensor
        var sharedKey = credentials.sharedKey?.takeIf {
            credentials.sharedKeyAddress == null || credentials.sharedKeyAddress.equals(sensor.deviceAddress, true)
        }
        var bondReconnectAttempts = 0
        var gatt133Retries = 0
        var pendingGatt133: G7BleException? = null

        while (true) {
            onState(G7ProtocolState.SCANNING)
            val discovered = scanner.findKnownSensor(
                sensor,
                scanTimeoutMsOverride?.coerceIn(5_000L, G7_RECONNECT_SCAN_TIMEOUT_MS)
                    ?: g7ScanTimeoutMs(sensor),
            )
            if (discovered == null) {
                pendingGatt133?.let { throw it }
                throw G7BleException("G7-BLE-107", "Kein sendender Dexcom-G7-Sensor gefunden", true)
            }
            sensor = discovered
            onState(G7ProtocolState.SENSOR_FOUND)
            if (sharedKey != null && credentials.sharedKeyAddress != null &&
                !credentials.sharedKeyAddress.equals(sensor.deviceAddress, ignoreCase = true)
            ) {
                sharedKey = null
            }

            val connection = G7GattConnection(context, sensor)
            try {
                val outcome = withTimeout(SESSION_TIMEOUT_MS) {
                    connection.collect(
                        G7AuthenticationSession(credentials.pairingCode, credentials.gKey, sharedKey),
                        packetParser,
                        onState,
                        onSharedKey = {
                            sharedKey = it
                            sensor.deviceAddress?.let { address -> onSharedKey(address, it) }
                        },
                    )
                }
                return G7CollectionResult(sensor, outcome, sharedKey)
            } catch (rebond: G7BondReconnectRequired) {
                sharedKey = rebond.sharedKey
                bondReconnectAttempts += 1
                if (bondReconnectAttempts >= MAX_BOND_RECONNECT_ATTEMPTS) {
                    throw G7BleException("G7-AUTH-207", "Sensor wurde gekoppelt, die erneute Verbindung schlug aber fehl", true, rebond)
                }
                pendingGatt133 = null
                onState(G7ProtocolState.RECOVERING)
                delay(BOND_RECONNECT_DELAY_MS)
            } catch (error: G7BleException) {
                if (error.errorCode != G7_GATT_133_ERROR_CODE || gatt133Retries >= MAX_GATT_133_RETRIES_PER_CYCLE) {
                    throw error
                }
                pendingGatt133 = error
                gatt133Retries += 1
                onState(G7ProtocolState.RECOVERING)
                delay(GATT_133_STACK_SETTLE_DELAY_MS)
            } finally {
                connection.close()
            }
        }
    }

    private companion object {
        const val SESSION_TIMEOUT_MS = 75_000L
        const val MAX_BOND_RECONNECT_ATTEMPTS = 2
        const val BOND_RECONNECT_DELAY_MS = 1_500L
        const val MAX_GATT_133_RETRIES_PER_CYCLE = 3
        const val GATT_133_STACK_SETTLE_DELAY_MS = 1_500L
    }
}

private class G7BondReconnectRequired(val sharedKey: ByteArray) : Exception()

private class G7GattConnection(
    private val context: Context,
    private val sensor: G7Sensor,
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val connectionEvents = Channel<Pair<Int, Int>>(Channel.UNLIMITED)
    private val serviceEvents = Channel<Int>(Channel.UNLIMITED)
    private val descriptorEvents = Channel<Pair<UUID, Int>>(Channel.UNLIMITED)
    private val writeEvents = Channel<Pair<UUID, Int>>(Channel.UNLIMITED)
    private val notifications = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
    @Volatile private var connected = false
    private var gatt: BluetoothGatt? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            connected = status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED
            connectionEvents.trySend(status to newState)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            serviceEvents.trySend(status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorEvents.trySend(descriptor.characteristic.uuid to status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeEvents.trySend(characteristic.uuid to status)
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            notifications.trySend(characteristic.uuid to characteristic.value.copyOf())
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            notifications.trySend(characteristic.uuid to value.copyOf())
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun collect(
        authentication: G7AuthenticationSession,
        parser: G7GlucosePacketParser,
        onState: (G7ProtocolState) -> Unit,
        onSharedKey: (ByteArray) -> Unit,
    ): G7Reading {
        requirePermission(Manifest.permission.BLUETOOTH_CONNECT, "G7-BLE-108")
        val address = sensor.deviceAddress
            ?: throw G7BleException("G7-BLE-109", "Sensoradresse fehlt", true)
        val adapter = manager.adapter ?: throw G7BleException("G7-BLE-102", "Bluetooth ist nicht verfügbar", false)
        val device = runCatching { adapter.getRemoteDevice(address) }
            .getOrElse { throw G7BleException("G7-BLE-110", "Sensoradresse ist ungültig", false, it) }

        onState(G7ProtocolState.CONNECTING)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val (connectStatus, connectState) = withTimeout(CONNECTION_TIMEOUT_MS) { connectionEvents.receive() }
        if (connectStatus != BluetoothGatt.GATT_SUCCESS || connectState != BluetoothProfile.STATE_CONNECTED) {
            if (connectStatus == GATT_ERROR_133) {
                throw G7BleException(G7_GATT_133_ERROR_CODE, "Temporärer BLE-Verbindungsfehler (133)", true)
            }
            throw G7BleException("G7-GATT-201", "Verbindung zum Sensor fehlgeschlagen ($connectStatus)", true)
        }

        onState(G7ProtocolState.DISCOVERING_SERVICES)
        val current = requireNotNull(gatt)
        if (!current.discoverServices()) throw G7BleException("G7-GATT-202", "Dienstsuche konnte nicht gestartet werden", true)
        val discoveryStatus = withTimeout(OPERATION_TIMEOUT_MS) { serviceEvents.receive() }
        if (discoveryStatus != BluetoothGatt.GATT_SUCCESS) {
            throw G7BleException("G7-GATT-203", "G7-Dienste konnten nicht gelesen werden ($discoveryStatus)", true)
        }
        val service = current.getService(G7GattProfile.serviceUuid)
            ?: throw G7BleException("G7-GATT-204", "Dexcom-G7-Dienst fehlt", true)
        val authenticationCharacteristic = service.requireCharacteristic(G7GattProfile.authenticationUuid, "G7-GATT-205")
        val extraCharacteristic = service.requireCharacteristic(G7GattProfile.extraDataUuid, "G7-GATT-206")
        val controlCharacteristic = service.requireCharacteristic(G7GattProfile.controlUuid, "G7-GATT-207")

        onState(G7ProtocolState.ENABLING_NOTIFICATIONS)
        enable(current, extraCharacteristic, indication = false)
        enable(current, authenticationCharacteristic, indication = true)

        onState(G7ProtocolState.AUTHENTICATION_START)
        authentication.connected()
        sendNext(current, authenticationCharacteristic, extraCharacteristic, authentication, onState)

        while (true) {
            val (uuid, payload) = notifications.receive()
            when (uuid) {
                G7GattProfile.extraDataUuid -> {
                    if (runAuthentication("G7-AUTH-202") { authentication.onExtraData(payload) }) {
                        sendNext(current, authenticationCharacteristic, extraCharacteristic, authentication, onState)
                    }
                }

                G7GattProfile.authenticationUuid -> {
                    if (authentication.shouldBond(payload)) {
                        val key = authentication.sharedKey()
                            ?: throw G7BleException("G7-AUTH-203", "Sitzungsschlüssel konnte nicht erzeugt werden", true)
                        onSharedKey(key)
                        onState(G7ProtocolState.BONDING)
                        ensureBonded(device)
                        if (!connected) throw G7BondReconnectRequired(key)
                        enable(current, controlCharacteristic, indication = true)
                        onState(G7ProtocolState.REQUESTING_GLUCOSE)
                        write(current, controlCharacteristic, GLUCOSE_REQUEST, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    }
                    if (runAuthentication("G7-AUTH-204") { authentication.onAuthenticationData(payload) }) {
                        val next = authentication.next()
                        if (next != null && next.size == 1 && next[0]?.contentEquals(GLUCOSE_REQUEST) == true) {
                            authentication.sharedKey()?.let(onSharedKey)
                            enable(current, controlCharacteristic, indication = true)
                            onState(G7ProtocolState.AUTHENTICATED)
                            onState(G7ProtocolState.REQUESTING_GLUCOSE)
                            write(current, controlCharacteristic, GLUCOSE_REQUEST, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        } else {
                            sendNext(current, authenticationCharacteristic, extraCharacteristic, next, onState)
                        }
                    }
                }

                G7GattProfile.controlUuid -> {
                    onState(G7ProtocolState.RECEIVING_GLUCOSE)
                    return runCatching { parser.parse(payload, sensor, System.currentTimeMillis()) }
                        .getOrElse { throw G7BleException("G7-DATA-301", "Ungültiges Glukosepaket empfangen", true, it) }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun ensureBonded(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        if (!device.createBond()) throw G7BleException("G7-AUTH-206", "Bluetooth-Kopplung konnte nicht gestartet werden", false)
        withTimeout(BOND_TIMEOUT_MS) {
            while (device.bondState == BluetoothDevice.BOND_BONDING) delay(250L)
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            throw G7BleException("G7-AUTH-209", "Bluetooth-Kopplung wurde nicht bestätigt", false)
        }
    }

    private suspend fun sendNext(
        gatt: BluetoothGatt,
        authCharacteristic: BluetoothGattCharacteristic,
        extraCharacteristic: BluetoothGattCharacteristic,
        authentication: G7AuthenticationSession,
        onState: (G7ProtocolState) -> Unit,
    ) = sendNext(gatt, authCharacteristic, extraCharacteristic, authentication.next(), onState)

    private suspend fun sendNext(
        gatt: BluetoothGatt,
        authCharacteristic: BluetoothGattCharacteristic,
        extraCharacteristic: BluetoothGattCharacteristic,
        packets: Array<ByteArray?>?,
        onState: (G7ProtocolState) -> Unit,
    ) {
        if (packets == null) throw G7BleException("G7-AUTH-210", "Authentifizierung lieferte keinen nächsten Schritt", true)
        if (packets.size == 3) throw G7BleException("G7-AUTH-211", "Gespeicherte Kopplung ist ungültig; Sensor in Bluetooth entfernen und neu einrichten", false)
        if (packets.size == 1) return
        require(packets.size == 2) { "Unexpected G7 authentication packet count" }
        onState(G7ProtocolState.AUTHENTICATING)
        packets[1]?.let { data ->
            data.asList().chunked(EXTRA_CHUNK_BYTES).forEach { chunk ->
                write(
                    gatt,
                    extraCharacteristic,
                    chunk.toByteArray(),
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                    awaitCallback = false,
                )
                delay(EXTRA_CHUNK_DELAY_MS)
            }
            delay(EXTRA_SETTLE_DELAY_MS)
        }
        packets[0]?.let { command ->
            write(gatt, authCharacteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun enable(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, indication: Boolean) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            throw G7BleException("G7-GATT-208", "G7-Benachrichtigung konnte nicht aktiviert werden", true)
        }
        val descriptor = characteristic.getDescriptor(G7GattProfile.clientConfigurationUuid)
            ?: throw G7BleException("G7-GATT-209", "G7-Benachrichtigungsdeskriptor fehlt", true)
        val value = if (indication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (gatt.writeDescriptor(descriptor, value) != BluetoothStatusCodes.SUCCESS) {
            throw G7BleException("G7-GATT-210", "G7-Benachrichtigung konnte nicht konfiguriert werden", true)
        }
        val (uuid, status) = withTimeout(OPERATION_TIMEOUT_MS) { descriptorEvents.receive() }
        if (uuid != characteristic.uuid || status != BluetoothGatt.GATT_SUCCESS) {
            throw G7BleException("G7-GATT-211", "G7-Benachrichtigung wurde abgelehnt ($status)", true)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun write(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
        awaitCallback: Boolean = true,
    ) {
        if (gatt.writeCharacteristic(characteristic, value, writeType) != BluetoothStatusCodes.SUCCESS) {
            throw G7BleException("G7-GATT-212", "G7-Daten konnten nicht gesendet werden", true)
        }
        if (!awaitCallback) return
        withTimeout(OPERATION_TIMEOUT_MS) {
            while (true) {
                val (uuid, status) = writeEvents.receive()
                when (classifyG7WriteCallback(characteristic.uuid, uuid, status)) {
                    G7WriteCallbackDisposition.EXPECTED_SUCCESS -> return@withTimeout
                    G7WriteCallbackDisposition.EXPECTED_FAILURE ->
                        throw G7BleException("G7-GATT-213", "G7-Daten wurden abgelehnt ($status)", true)
                    G7WriteCallbackDisposition.STALE_SUCCESS -> Unit
                    G7WriteCallbackDisposition.STALE_FAILURE ->
                        throw G7BleException("G7-GATT-214", "Vorheriger G7-Datentransfer ist fehlgeschlagen ($status)", true)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        val current = gatt
        gatt = null
        connected = false
        runCatching { current?.disconnect() }
        runCatching { current?.close() }
        connectionEvents.close()
        serviceEvents.close()
        descriptorEvents.close()
        writeEvents.close()
        notifications.close()
    }

    private fun requirePermission(permission: String, code: String) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw G7BleException(code, "Bluetooth-Berechtigung fehlt", false)
        }
    }

    private fun BluetoothGattService.requireCharacteristic(uuid: UUID, code: String): BluetoothGattCharacteristic =
        getCharacteristic(uuid) ?: throw G7BleException(code, "Erforderliche G7-Eigenschaft fehlt", true)

    private inline fun <T> runAuthentication(code: String, block: () -> T): T = try {
        block()
    } catch (error: SecurityException) {
        throw G7BleException(code, "Sensor hat die Authentifizierung abgelehnt", false, error)
    } catch (error: IllegalArgumentException) {
        throw G7BleException(code, "G7-Schlüsselmaterial wurde abgelehnt", false, error)
    }

    private companion object {
        val GLUCOSE_REQUEST = byteArrayOf(0x4e)
        const val GATT_ERROR_133 = 133
        const val CONNECTION_TIMEOUT_MS = 20_000L
        const val OPERATION_TIMEOUT_MS = 15_000L
        const val BOND_TIMEOUT_MS = 35_000L
        const val EXTRA_CHUNK_BYTES = 20
        const val EXTRA_CHUNK_DELAY_MS = 40L
        const val EXTRA_SETTLE_DELAY_MS = 500L
    }
}
