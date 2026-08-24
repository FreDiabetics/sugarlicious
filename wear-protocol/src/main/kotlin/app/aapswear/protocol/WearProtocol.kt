package app.aapswear.protocol

import app.aapswear.model.DiagnosticBatch
import app.aapswear.model.TherapyDisplayState
import app.aapswear.g7.CgmReading
import app.aapswear.model.DataSourceId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WearEnvelope(
    val protocolVersion: Int = CURRENT,
    val state: TherapyDisplayState,
) {
    companion object {
        const val CURRENT = 7
    }
}

@Serializable
enum class WatchGlucoseUnit {
    AAPS,
    MG_DL,
    MMOL_L,
}

@Serializable
enum class WatchDataSource {
    AUTOMATIC,
    PHONE,
    DEXCOM_G7_WATCH,
}

@Serializable
data class G7SetupCommand(
    val pairingCode: String,
    val sensorSerial: String? = null,
    val gtin: String? = null,
) {
    init {
        require(pairingCode.length == 4 && pairingCode.all(Char::isDigit))
    }
}

@Serializable
data class WatchGraphColors(
    val graphBackground: Int = 0xFF202020.toInt(),
    val rangeLow: Int = 0xFFFF5C69.toInt(),
    val rangeInRange: Int = 0xFF54DF30.toInt(),
    val rangeHigh: Int = 0xFFFFD040.toInt(),
    val cgmLow: Int = 0xFFFF5C69.toInt(),
    val cgmInRange: Int = 0xFF54DF30.toInt(),
    val cgmHigh: Int = 0xFFFFD040.toInt(),
    val divider: Int = 0xFF969696.toInt(),
    val outline: Int = 0xFF000000.toInt(),
    val predictionIob: Int = 0xFF52C1FF.toInt(),
    val predictionCob: Int = 0xFFF4DE00.toInt(),
    val predictionUam: Int = 0xFFFFAE1F.toInt(),
    val predictionZeroTemp: Int = 0xFF30DBDE.toInt(),
    val targetValue: Int = 0xFFF5F5F5.toInt(),
    val signalLoss: Int = 0x46FF5C69,
)

@Serializable
data class WatchColorSync(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val graphColors: WatchGraphColors,
    val sentAtEpochMs: Long,
) {
    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

@Serializable
data class G7ReadingBatch(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val batchId: String,
    val readings: List<CgmReading>,
    val sentAtEpochMs: Long,
) {
    companion object {
        const val CURRENT_SCHEMA = 1
        const val MAX_READINGS = 100
    }
}

@Serializable
data class G7ReadingAck(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val batchId: String,
    val acknowledgedIds: Set<String>,
    val acknowledgedAtEpochMs: Long,
) {
    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

@Serializable
data class WatchUiColors(
    val background: Int = 0xFF181818.toInt(),
    val tileBackground: Int = 0xFF242424.toInt(),
    val tileBorder: Int = 0xFF404040.toInt(),
    val textPrimary: Int = 0xFFF5F5F5.toInt(),
    val textSecondary: Int = 0xFFB5B5B5.toInt(),
    val accent: Int = 0xFF6DE892.toInt(),
    val glucoseLow: Int = 0xFFFF5C69.toInt(),
    val glucoseInRange: Int = 0xFFF5F5F5.toInt(),
    val glucoseHigh: Int = 0xFFFFD040.toInt(),
    val iob: Int = 0xFF64BFFF.toInt(),
    val cob: Int = 0xFFFF9D18.toInt(),
    val basal: Int = 0xFF19D7E8.toInt(),
)

@Serializable
data class WatchGraphStyle(
    val cgmDotRadiusDp: Float = 2.4f,
    val cgmDotOutlineEnabled: Boolean = true,
    val cgmDotOutlineWidthDp: Float = 0.95f,
)

@Serializable
data class WatchRuntimeStatus(
    val activeSugarliciousFaceIndex: Int? = null,
    val activeComplicationIds: List<Int> = emptyList(),
    val sentAtEpochMs: Long = 0L,
)

@Serializable
data class WatchConfig(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val graphHours: Int = 3,
    val showPredictions: Boolean = false,
    val glucoseUnit: WatchGlucoseUnit = WatchGlucoseUnit.AAPS,
    val dataSource: WatchDataSource = WatchDataSource.AUTOMATIC,
    val showTherapyStats: Boolean = true,
    val graphColors: WatchGraphColors = WatchGraphColors(),
    val graphStyle: WatchGraphStyle = WatchGraphStyle(),
    val uiColors: WatchUiColors = WatchUiColors(),
    val sentAtEpochMs: Long = 0L,
) {
    companion object {
        const val CURRENT_SCHEMA = 7
    }
}

object WearProtocol {
    const val STATE_PATH = "/aaps-display/v1/state"
    const val REQUEST_PATH = "/aaps-display/v1/request"
    const val WATCH_CONFIG_PATH = "/aaps-display/v1/watch-config"
    const val WATCH_CONFIG_REQUEST_PATH = "/aaps-display/v1/watch-config-request"
    const val COMPLICATION_PRESET_PATH = "/aaps-display/v1/complication-preset"
    const val WATCH_FACE_APPLY_PATH = "/aaps-display/v1/watchface-apply"
    const val WATCH_FACE_STATUS_PATH = "/aaps-display/v1/watchface-status"
    const val WATCH_RUNTIME_STATUS_PATH = "/aaps-display/v1/watch-runtime-status"
    const val WATCH_RUNTIME_REQUEST_PATH = "/aaps-display/v1/watch-runtime-request"
    const val G7_SETUP_PATH = "/aaps-display/v1/g7-setup"
    const val G7_READING_PATH = "/aaps-display/v1/g7-reading"
    const val G7_READING_BATCH_PATH = "/aaps-display/v1/g7-reading-batch"
    const val G7_READING_ACK_PATH = "/aaps-display/v1/g7-reading-ack"
    const val G7_SYNC_REQUEST_PATH = "/aaps-display/v1/g7-sync-request"
    const val WATCH_COLOR_SYNC_PATH = "/aaps-display/v1/watch-color-sync"
    const val DIAGNOSTICS_REQUEST_PATH = "/aaps-display/v1/diagnostics-request"
    const val DIAGNOSTICS_BATCH_PATH = "/aaps-display/v1/diagnostics-batch"
    const val SUGARLICIOUS_WATCH_FACE_MAX_INDEX = 5

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(state: TherapyDisplayState): ByteArray =
        json.encodeToString(WearEnvelope(state = state)).encodeToByteArray()

    fun decode(bytes: ByteArray): TherapyDisplayState {
        val envelope = json.decodeFromString<WearEnvelope>(bytes.decodeToString())
        require(envelope.protocolVersion in 1..WearEnvelope.CURRENT)
        return migrate(envelope.state)
    }

    fun encodeConfig(config: WatchConfig): ByteArray =
        json.encodeToString(config).encodeToByteArray()

    fun encodeRuntimeStatus(status: WatchRuntimeStatus): ByteArray =
        json.encodeToString(status).encodeToByteArray()

    fun encodeG7Setup(command: G7SetupCommand): ByteArray =
        json.encodeToString(command).encodeToByteArray()

    fun encodeG7ReadingBatch(batch: G7ReadingBatch): ByteArray =
        json.encodeToString(batch).encodeToByteArray()

    fun decodeG7ReadingBatch(bytes: ByteArray): G7ReadingBatch {
        val decoded = json.decodeFromString<G7ReadingBatch>(bytes.decodeToString())
        require(decoded.schemaVersion in 1..G7ReadingBatch.CURRENT_SCHEMA)
        require(decoded.batchId.isNotBlank() && decoded.batchId.length <= 80)
        require(decoded.readings.size <= G7ReadingBatch.MAX_READINGS)
        require(decoded.readings.all { it.source == DataSourceId.DEXCOM_G7_WATCH })
        return decoded.copy(readings = decoded.readings.distinctBy(CgmReading::id))
    }

    fun encodeG7ReadingAck(ack: G7ReadingAck): ByteArray =
        json.encodeToString(ack).encodeToByteArray()

    fun decodeG7ReadingAck(bytes: ByteArray): G7ReadingAck {
        val decoded = json.decodeFromString<G7ReadingAck>(bytes.decodeToString())
        require(decoded.schemaVersion in 1..G7ReadingAck.CURRENT_SCHEMA)
        require(decoded.batchId.isNotBlank() && decoded.batchId.length <= 80)
        require(decoded.acknowledgedIds.size <= G7ReadingBatch.MAX_READINGS)
        return decoded.copy(acknowledgedIds = decoded.acknowledgedIds.filter { it.isNotBlank() }.toSet())
    }

    fun encodeWatchColorSync(sync: WatchColorSync): ByteArray =
        json.encodeToString(sync).encodeToByteArray()

    fun decodeWatchColorSync(bytes: ByteArray): WatchColorSync {
        val decoded = json.decodeFromString<WatchColorSync>(bytes.decodeToString())
        require(decoded.schemaVersion in 1..WatchColorSync.CURRENT_SCHEMA)
        return decoded
    }

    fun decodeG7Setup(bytes: ByteArray): G7SetupCommand =
        json.decodeFromString<G7SetupCommand>(bytes.decodeToString())

    fun encodeDiagnostics(batch: DiagnosticBatch): ByteArray =
        json.encodeToString(batch).encodeToByteArray()

    fun decodeDiagnostics(bytes: ByteArray): DiagnosticBatch {
        val decoded = json.decodeFromString<DiagnosticBatch>(bytes.decodeToString())
        return decoded.copy(events = decoded.events.takeLast(1_000))
    }

    fun decodeRuntimeStatus(bytes: ByteArray): WatchRuntimeStatus {
        val decoded = json.decodeFromString<WatchRuntimeStatus>(bytes.decodeToString())
        return decoded.copy(
            activeSugarliciousFaceIndex =
                decoded.activeSugarliciousFaceIndex?.coerceIn(0, SUGARLICIOUS_WATCH_FACE_MAX_INDEX),
            activeComplicationIds = decoded.activeComplicationIds.distinct().take(12),
        )
    }

    fun decodeConfig(bytes: ByteArray): WatchConfig {
        val decoded = json.decodeFromString<WatchConfig>(bytes.decodeToString())
        return decoded.copy(
            graphHours = decoded.graphHours.takeIf { it in listOf(1, 2, 3, 6, 12, 24) } ?: 3,
            graphStyle = decoded.graphStyle.copy(
                cgmDotRadiusDp = decoded.graphStyle.cgmDotRadiusDp.coerceIn(1.5f, 6.0f),
                cgmDotOutlineWidthDp = decoded.graphStyle.cgmDotOutlineWidthDp.coerceIn(0.25f, 3.0f),
            ),
        )
    }

    private fun migrate(state: TherapyDisplayState): TherapyDisplayState {
        if (state.schemaVersion >= TherapyDisplayState.CURRENT_SCHEMA) return state

        val legacyContract =
            state.sourceContract
                ?: state.sourceVersion?.takeIf { it.startsWith("AAPS_") }
        val actualSourceVersion =
            state.sourceVersion?.takeUnless { it.startsWith("AAPS_") }

        return state.copy(
            schemaVersion = TherapyDisplayState.CURRENT_SCHEMA,
            sourceVersion = actualSourceVersion,
            sourceContract = legacyContract,
        )
    }
}
