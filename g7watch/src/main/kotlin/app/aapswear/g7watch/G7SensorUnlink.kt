package app.aapswear.g7watch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7Sensor
import app.aapswear.g7.G7SessionManager
import app.aapswear.g7.G7SetupPayload

internal data class G7UnlinkResult(
    val bondRemovalAttempted: Boolean,
    val bondRemovalRequested: Boolean,
)

internal data class G7DetachSemantics(
    val stopsCollector: Boolean = true,
    val clearsLocalSession: Boolean = true,
    val preservesHistory: Boolean = true,
    val sendsSensorEndCommand: Boolean = false,
)

internal fun g7DetachSemantics() = G7DetachSemantics()

/**
 * Prepares this watch to take over the receiver slot. The previous watch still has to release its
 * own bond locally; watches paired to different phones cannot modify each other's Bluetooth store.
 */
internal fun moveG7SensorToThisWatch(context: Context, pairingCode: String): G7Sensor {
    val payload = G7SetupPayload(pairingCode)
    unlinkG7Sensor(context)
    G7CredentialStore(context.applicationContext).saveSetup(payload)
    val sensorId = "G7-${java.util.UUID.randomUUID().toString().take(8)}"
    val sensor = G7Sensor(sensorId = sensorId, sessionId = sensorId, deviceName = "Dexcom G7")
    val stateStore = G7SensorStateStore(context.applicationContext)
    stateStore.save(G7SessionManager(stateStore.read()).prepareInitialSetup(sensor))
    G7CollectorService.start(context.applicationContext)
    return sensor
}

/** Destructive only for the explicit sensor/auth association; history and user settings remain. */
internal fun unlinkG7Sensor(context: Context): G7UnlinkResult {
    val app = context.applicationContext
    val stateStore = G7SensorStateStore(app)
    val address = stateStore.read().sensor?.deviceAddress
    // Cancel the live BLE owner before removing the Android bond. stopService() alone is
    // asynchronous and previously allowed the old watch to remain connected during takeover.
    G7CollectorRuntimeRegistry.cancelLiveCycle()
    G7CollectorService.stop(app)
    val bondResult = removeG7Bond(app, address)
    G7CredentialStore(app).clearAll()
    stateStore.save(G7PersistedState())
    G7CgmAlarmCoordinator.clearSuppressed(app)
    return G7UnlinkResult(
        bondRemovalAttempted = bondResult != null,
        bondRemovalRequested = bondResult == true,
    )
}

@SuppressLint("MissingPermission", "DiscouragedPrivateApi")
private fun removeG7Bond(context: Context, address: String?): Boolean? {
    if (address.isNullOrBlank()) return null
    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
    return runCatching {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return@runCatching false
        val device = adapter.getRemoteDevice(address)
        if (device.bondState == BluetoothDevice.BOND_NONE) return@runCatching true
        val method = device.javaClass.getMethod("removeBond")
        method.invoke(device) as? Boolean ?: false
    }.getOrDefault(false)
}
