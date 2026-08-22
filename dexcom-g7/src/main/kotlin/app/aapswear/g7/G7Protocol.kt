package app.aapswear.g7

import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder

object G7GattProfile {
    val serviceUuid: UUID = UUID.fromString("f8083532-849e-531c-c594-30f1f86a4ea5")
    val controlUuid: UUID = UUID.fromString("f8083534-849e-531c-c594-30f1f86a4ea5")
    val authenticationUuid: UUID = UUID.fromString("f8083535-849e-531c-c594-30f1f86a4ea5")
    val backfillUuid: UUID = UUID.fromString("f8083536-849e-531c-c594-30f1f86a4ea5")
    val extraDataUuid: UUID = UUID.fromString("f8083538-849e-531c-c594-30f1f86a4ea5")
    val clientConfigurationUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

}

interface G7PacketParser {
    fun parse(packet: ByteArray, sensor: G7Sensor, receivedAtEpochMs: Long): G7Reading
}

class G7GlucosePacketParser : G7PacketParser {
    override fun parse(packet: ByteArray, sensor: G7Sensor, receivedAtEpochMs: Long): G7Reading {
        require(packet.size >= MIN_PACKET_SIZE) { "G7 glucose packet is too short" }
        val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        require(data.get() == OPCODE) { "Unexpected G7 glucose opcode" }
        val protocolStatus = data.get().toInt() and 0xff
        val sensorClockSeconds = data.int.toLong() and 0xffff_ffffL
        val sequence = data.short.toLong() and 0xffffL
        val reserved = data.short.toInt() and 0xffff
        val ageSeconds = data.short.toLong() and 0xffffL
        val glucoseField = data.short.toInt() and 0xffff
        val glucose = glucoseField and 0x0fff
        val displayOnly = glucoseField and 0xf000 != 0
        val stateCode = data.get().toInt() and 0xff
        val trendRaw = data.get().toInt()
        val predictedRaw = data.short.toInt() and 0x03ff
        require(glucose in 14..1000) { "G7 packet does not contain a usable glucose value" }
        require(ageSeconds <= MAX_READING_AGE_SECONDS) { "G7 packet reading age is invalid" }
        val measuredAt = receivedAtEpochMs - ageSeconds * 1_000L
        val sensorStart = receivedAtEpochMs - sensorClockSeconds * 1_000L
        return G7Reading(
            sensorId = sensor.sensorId,
            sessionId = sensor.sessionId ?: sensor.sensorId,
            sequenceNumber = sequence,
            glucoseMgDl = glucose.toDouble(),
            sensorTimestampEpochMs = measuredAt,
            receivedAtEpochMs = receivedAtEpochMs,
            trendRateMgDlPerMinute = trendRaw.takeUnless { it == 127 }?.div(10.0),
            predictedMgDl = predictedRaw.takeUnless { it == 0x03ff }?.toDouble(),
            sensorAgeSeconds = ageSeconds,
            sensorState = stateCode.toSensorState(),
            displayOnly = displayOnly,
            sensorClockSeconds = sensorClockSeconds,
            sensorStartEpochMs = sensorStart,
            sensorEndEpochMs = sensorStart + G7_SENSOR_LIFETIME_MS,
            graceEndEpochMs = sensorStart + G7_SENSOR_LIFETIME_MS + G7_GRACE_PERIOD_MS,
            protocolStatusCode = protocolStatus,
            calibrationStateCode = stateCode,
            reservedField = reserved,
        )
    }

    private fun Int.toSensorState(): G7SensorState = when (this) {
        0x02, 0xc1 -> G7SensorState.WARMUP
        0x06, 0x07 -> G7SensorState.ACTIVE
        0x0f, 0x18, 0x1a, 0xc2 -> G7SensorState.ENDED
        in 0x0b..0x17, 0x19, in 0x1b..0x1e -> G7SensorState.ERROR
        else -> G7SensorState.UNKNOWN
    }

    private companion object {
        const val OPCODE: Byte = 0x4e
        const val MIN_PACKET_SIZE = 19
        const val MAX_READING_AGE_SECONDS = 30 * 60L
        const val G7_SENSOR_LIFETIME_MS = 10L * 24L * 60L * 60_000L
        const val G7_GRACE_PERIOD_MS = 12L * 60L * 60_000L
    }
}

interface G7Scanner { suspend fun findKnownSensor(sensor: G7Sensor?, timeoutMs: Long): G7Sensor? }
interface G7ConnectionManager { suspend fun collectNextReading(sensor: G7Sensor): G7Reading }
interface G7BackfillManager { suspend fun requestBackfill(sensor: G7Sensor, gaps: List<CgmGap>): List<G7Reading> }

interface G7WatchSyncTransport {
    suspend fun sendReadings(readings: List<CgmReading>): G7SyncDispatch
}

data class G7SyncDispatch(
    val batchId: String,
    val readingIds: Set<String>,
)

class G7ReadingSyncManager(private val repository: CgmReadingRepository, private val transport: G7WatchSyncTransport) {
    suspend fun sendPending(batchSize: Int = 100): G7SyncDispatch? {
        val pending = repository.getUnsynced(batchSize)
        if (pending.isEmpty()) return null
        return transport.sendReadings(pending)
    }

    suspend fun acknowledge(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        repository.markSynced(ids)
        return ids.size
    }
}
