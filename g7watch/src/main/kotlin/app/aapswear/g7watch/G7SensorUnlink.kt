package app.aapswear.g7watch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import app.aapswear.g7.G7PersistedState

internal data class G7UnlinkResult(
    val bondRemovalAttempted: Boolean,
    val bondRemovalRequested: Boolean,
)

/** Destructive only for the explicit sensor/auth association; history and user settings remain. */
internal fun unlinkG7Sensor(context: Context): G7UnlinkResult {
    val app = context.applicationContext
    val stateStore = G7SensorStateStore(app)
    val address = stateStore.read().sensor?.deviceAddress
    val bondResult = removeG7Bond(app, address)

    G7CollectorService.stop(app)
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
