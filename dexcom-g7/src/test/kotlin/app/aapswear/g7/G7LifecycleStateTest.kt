package app.aapswear.g7

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class G7LifecycleStateTest {
    private val now = 1_800_000_000_000L

    @Test fun `recoverable technical disconnect keeps user enable state`() {
        val manager =
            G7SessionManager(
                G7PersistedState(
                    sensor = G7Sensor("sensor"),
                    collectorEnabled = true,
                ),
            )

        val failed =
            manager.failure(
                G7CollectorError(
                    code = "G7-BLE-111",
                    recoverable = true,
                    occurredAtEpochMs = now,
                    safeMessage = "Sensor temporarily unavailable",
                ),
            )

        assertTrue(failed.collectorEnabled)
    }

    @Test fun `non recoverable technical error still does not become user disable`() {
        val manager =
            G7SessionManager(
                G7PersistedState(
                    sensor = G7Sensor("sensor"),
                    collectorEnabled = true,
                ),
            )

        val failed =
            manager.failure(
                G7CollectorError(
                    code = "G7-PERM-401",
                    recoverable = false,
                    occurredAtEpochMs = now,
                    safeMessage = "Permission missing",
                ),
            )

        assertTrue(failed.collectorEnabled)
    }

    @Test fun `only explicit user stop disables collector`() {
        val manager =
            G7SessionManager(
                G7PersistedState(
                    sensor = G7Sensor("sensor"),
                    collectorEnabled = true,
                ),
            )

        assertFalse(manager.stop().collectorEnabled)
    }
}
