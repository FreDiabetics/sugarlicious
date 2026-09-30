package app.aapswear.wear

import app.aapswear.model.GlucoseState
import app.aapswear.model.GlucoseUnit
import app.aapswear.model.TherapyDisplayState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

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

    @Test fun `duplicate committed revision is rejected before processing`() {
        val value = state(receivedAt = 20_000L, glucoseAt = 19_000L).copy(canonicalRevision = 4L)
        assertFalse(shouldAcceptPhoneState(value, value))
    }

    @Test fun `newer committed revision accepts therapy-only change with unchanged timestamps`() {
        val previous = state(receivedAt = 20_000L, glucoseAt = 19_000L).copy(canonicalRevision = 4L)
        val incoming = previous.copy(canonicalRevision = 5L, sourceContract = "therapy-update")

        assertTrue(shouldAcceptPhoneState(previous, incoming))
    }

    @Test fun `older committed revision cannot overwrite newer state despite later legacy timestamp`() {
        val previous = state(receivedAt = 20_000L, glucoseAt = 19_000L).copy(canonicalRevision = 5L)
        val incoming = state(receivedAt = 30_000L, glucoseAt = 29_000L).copy(canonicalRevision = 4L)

        assertFalse(shouldAcceptPhoneState(previous, incoming))
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

    @Test fun `identical second transport payload is rejected before JSON decoding`() {
        val payload = ByteArray(90_000) { index -> (index % 251).toByte() }
        val committed = statePayloadFingerprint(payload)

        assertTrue(isCommittedStatePayload(payload, committed))
        assertFalse(isCommittedStatePayload(payload.copyOf().also { it[it.lastIndex]++ }, committed))
        val expected = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        assertTrue(committed.sha256Hex == expected)
    }

    @Test fun `bursted state deliveries retain only the newest pending payload`() {
        val deliveries = conflatedStateDeliveryChannel()
        deliveries.trySend(PendingStateDelivery(byteArrayOf(1), "message"))
        deliveries.trySend(PendingStateDelivery(byteArrayOf(2), "data_item"))
        deliveries.trySend(PendingStateDelivery(byteArrayOf(3), "message"))

        val pending = deliveries.tryReceive().getOrThrow()

        assertTrue(pending.payload.contentEquals(byteArrayOf(3)))
        assertTrue(pending.transport == "message")
        assertTrue(deliveries.tryReceive().isFailure)
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
