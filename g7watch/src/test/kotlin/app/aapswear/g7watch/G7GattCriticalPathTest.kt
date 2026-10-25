package app.aapswear.g7watch

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import androidx.test.core.app.ApplicationProvider
import app.aapswear.g7.G7AuthenticationSession
import app.aapswear.g7.G7DefaultGKey
import app.aapswear.g7.G7GlucosePacketParser
import app.aapswear.g7.G7Sensor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class G7GattCriticalPathTest {
    @Test fun `radio starts before diagnostic persistence and callback returns without persistence`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("AA:BB:CC:DD:EE:01")
            var insideCallback = false
            var completed = false
            val connection =
                G7GattConnection(context, G7Sensor("sensor", deviceAddress = device.address), { event ->
                    when (event) {
                        is G7DirectConnectStarted -> assertTrue(shadowOf(device).bluetoothGatts.isNotEmpty())
                        is G7DirectConnectCompleted -> {
                            assertFalse(insideCallback)
                            completed = true
                        }
                        is G7GattCleanupTelemetry -> assertTrue(shadowOf(shadowOf(device).bluetoothGatts.single()).isClosed)
                        else -> Unit
                    }
                }, G7GattGenerationRegistry.acquire(900))
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        connection.collect(G7AuthenticationSession("1234", G7DefaultGKey.parts, null), G7GlucosePacketParser(), {}, {}, null, {})
                    }
                }
            try {
                val gatt = shadowOf(device).bluetoothGatts.single()
                insideCallback = true
                shadowOf(gatt).gattCallback.onConnectionStateChange(gatt, BluetoothGatt.GATT_FAILURE, BluetoothProfile.STATE_DISCONNECTED)
                insideCallback = false
                assertTrue(result.await().isFailure)
                assertTrue(completed)
            } finally {
                insideCallback = false
                connection.close()
            }
        }
}
