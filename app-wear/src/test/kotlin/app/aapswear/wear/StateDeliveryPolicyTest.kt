package app.aapswear.wear

import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateDeliveryPolicyTest {
    @Test fun `newer message state is accepted`() {
        assertTrue(
            shouldAcceptPhoneState(
                previous = state(receivedAt = 10_000L, glucoseAt = 9_000L),
                incoming = state(receivedAt = 20_000L, glucoseAt = 19_000L),
            ),
        )
    }

    @Test fun `delayed durable state cannot roll watch backwards`() {
        assertFalse(
            shouldAcceptPhoneState(
                previous = state(receivedAt = 20_000L, glucoseAt = 19_000L),
                incoming = state(receivedAt = 10_000L, glucoseAt = 9_000L),
            ),
        )
    }

    @Test fun `newer glucose is accepted even with legacy receive timestamp`() {
        assertTrue(
            shouldAcceptPhoneState(
                previous = state(receivedAt = 20_000L, glucoseAt = 19_000L),
                incoming = state(receivedAt = 10_000L, glucoseAt = 21_000L),
            ),
        )
    }

    @Test fun `duplicate transport copy remains idempotently acceptable`() {
        val value = state(receivedAt = 20_000L, glucoseAt = 19_000L)
        assertTrue(shouldAcceptPhoneState(value, value))
    }

    @Test fun `second transport copy is not applied twice`() {
        val previous = state(receivedAt = 20_000L, glucoseAt = 19_000L)
        val secondTransportCopy = previous.copy(receivedAtEpochMs = 21_000L)

        assertFalse(hasMeaningfulPhoneStateChange(previous, secondTransportCopy))
    }

    @Test fun `therapy change with unchanged glucose is still applied`() {
        val previous = state(receivedAt = 20_000L, glucoseAt = 19_000L)
        val updated = previous.copy(receivedAtEpochMs = 21_000L, sourceContract = "therapy-update")

        assertTrue(hasMeaningfulPhoneStateChange(previous, updated))
    }

    private fun state(
        receivedAt: Long,
        glucoseAt: Long,
    ) = TherapyDisplayState(
        receivedAtEpochMs = receivedAt,
        glucose =
            GlucoseState(
                valueMgDl = 123.0,
                displayUnit = GlucoseUnit.MG_DL,
                measuredAtEpochMs = glucoseAt,
            ),
    )
}
