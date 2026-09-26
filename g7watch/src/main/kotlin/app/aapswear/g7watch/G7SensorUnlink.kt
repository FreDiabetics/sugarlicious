package app.aapswear.g7watch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7Sensor
import app.aapswear.g7.G7SessionManager
import app.aapswear.g7.G7SetupPayload

internal data class G7UnlinkResult(
    val bondRemovalStatus: G7BondRemovalStatus,
) {
    val bondRemovalAttempted: Boolean get() = bondRemovalStatus != G7BondRemovalStatus.NOT_REQUIRED
    val bondRemovalRequested: Boolean get() = bondRemovalStatus == G7BondRemovalStatus.REMOVED
    val detached: Boolean get() = bondRemovalStatus != G7BondRemovalStatus.FAILED
}

internal enum class G7BondRemovalStatus { NOT_REQUIRED, REMOVED, FAILED }

internal fun interface G7BondRemover {
    fun remove(
        context: Context,
        address: String?,
    ): G7BondRemovalStatus
}

internal data class G7DetachSemantics(
    val stopsCollector: Boolean = true,
    val clearsLocalSession: Boolean = true,
    val preservesHistory: Boolean = true,
    val sendsSensorEndCommand: Boolean = false,
)

internal fun g7DetachSemantics() = G7DetachSemantics()

internal fun shouldClearG7Association(status: G7BondRemovalStatus): Boolean = status != G7BondRemovalStatus.FAILED

/** Stops this watch as BLE owner without deleting sensor identity, credentials or history. */
internal fun releaseG7CollectorOwnership(context: Context) {
    val app = context.applicationContext
    val stateStore = G7SensorStateStore(app)
    val current = stateStore.read()
    stateStore.save(current.copy(health = G7CollectorReliability.releasePending(current.health, System.currentTimeMillis())))
    G7CollectorRuntimeRegistry.cancelLiveCycle()
    AndroidG7Scanner.forceCleanup()
    G7CollectorService.stop(app)
}

/**
 * Prepares this watch to take over the receiver slot. The previous watch still has to release its
 * own bond locally; watches paired to different phones cannot modify each other's Bluetooth store.
 */
internal fun moveG7SensorToThisWatch(
    context: Context,
    pairingCode: String,
    bondRemover: G7BondRemover = G7BondRemover(::removeG7Bond),
): G7Sensor {
    val payload = G7SetupPayload(pairingCode)
    check(unlinkG7Sensor(context, bondRemover).detached) {
        "Der bisherige Sensor ist noch per Bluetooth gebondet"
    }
    G7CredentialStore(context.applicationContext).saveSetup(payload)
    val sensorId = "G7-${java.util.UUID.randomUUID().toString().take(8)}"
    val sensor = G7Sensor(sensorId = sensorId, sessionId = sensorId, deviceName = "Dexcom G7")
    val stateStore = G7SensorStateStore(context.applicationContext)
    stateStore.save(G7SessionManager(stateStore.read()).prepareInitialSetup(sensor))
    G7CollectorService.start(context.applicationContext)
    return sensor
}

/** Destructive only for the explicit sensor/auth association; history and user settings remain. */
internal fun unlinkG7Sensor(
    context: Context,
    bondRemover: G7BondRemover = G7BondRemover(::removeG7Bond),
): G7UnlinkResult {
    val app = context.applicationContext
    val stateStore = G7SensorStateStore(app)
    val beforeRelease = stateStore.read()
    val address = beforeRelease.sensor?.deviceAddress
    stateStore.save(beforeRelease.copy(health = G7CollectorReliability.releasePending(beforeRelease.health, System.currentTimeMillis())))
    // Cancel the live BLE owner before removing the Android bond. stopService() alone is
    // asynchronous and previously allowed the old watch to remain connected during takeover.
    releaseG7CollectorOwnership(app)
    val bondRemovalStatus = bondRemover.remove(app, address)
    if (!shouldClearG7Association(bondRemovalStatus)) {
        return G7UnlinkResult(bondRemovalStatus)
    }
    G7ExpectedWindowLedger(app).markSessionEnded(
        beforeRelease.sensor?.sensorId,
        beforeRelease.sensor?.sessionId ?: beforeRelease.sensor?.sensorId,
        System.currentTimeMillis(),
    )
    G7CredentialStore(app).clearAll()
    stateStore.save(G7PersistedState())
    G7CgmAlarmCoordinator.clearSuppressed(app)
    // State changed but the retained reading rows did not. Explicitly invalidate both provider
    // views so Vigil switches to its detached status without requiring a new glucose insert.
    app.contentResolver.notifyChange(G7ReadingProvider.STATE_URI, null)
    app.contentResolver.notifyChange(G7ReadingProvider.CONTENT_URI, null)
    app.sendBroadcast(Intent(G7ReadingDatabase.ACTION_G7_READING_UPDATED).setPackage("app.aapswear"))
    return G7UnlinkResult(bondRemovalStatus)
}

@SuppressLint("MissingPermission", "DiscouragedPrivateApi")
private fun removeG7Bond(
    context: Context,
    address: String?,
): G7BondRemovalStatus {
    if (address.isNullOrBlank()) return G7BondRemovalStatus.NOT_REQUIRED
    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        return G7BondRemovalStatus.FAILED
    }
    return runCatching {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return@runCatching G7BondRemovalStatus.FAILED
        val device = adapter.getRemoteDevice(address)
        if (device.bondState == BluetoothDevice.BOND_NONE) return@runCatching G7BondRemovalStatus.REMOVED
        val method = device.javaClass.getMethod("removeBond")
        val requested = method.invoke(device) as? Boolean ?: false
        if (!requested) return@runCatching G7BondRemovalStatus.FAILED
        if (awaitG7BondRemoval(readBondState = { device.bondState })) {
            G7BondRemovalStatus.REMOVED
        } else {
            G7BondRemovalStatus.FAILED
        }
    }.getOrDefault(G7BondRemovalStatus.FAILED)
}

internal fun awaitG7BondRemoval(
    timeoutMs: Long = 5_000L,
    pollMs: Long = 100L,
    readBondState: () -> Int,
    sleep: (Long) -> Unit = Thread::sleep,
): Boolean {
    require(timeoutMs >= 0L)
    require(pollMs > 0L)
    var elapsed = 0L
    while (true) {
        if (readBondState() == BluetoothDevice.BOND_NONE) return true
        if (elapsed >= timeoutMs) return false
        val delay = minOf(pollMs, timeoutMs - elapsed)
        sleep(delay)
        elapsed += delay
    }
}
