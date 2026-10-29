package app.aapswear.mobile

import android.content.Context
import androidx.core.content.edit
import app.aapswear.datasource.external.ExternalCgmAdapters
import app.aapswear.model.DataSourceId
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/** Shared authenticated transport; redirects never forward credentials to a different host. */
internal object NightscoutHttp {
    fun get(configuration: NightscoutConfiguration, secret: String?, endpoint: String): String {
        require(configuration.isValid())
        val suffix =
            if (configuration.authMode == NightscoutAuthMode.ACCESS_TOKEN && !secret.isNullOrBlank()) {
                (if ('?' in endpoint) "&" else "?") + "token=" + URLEncoder.encode(secret, "UTF-8")
            } else {
                ""
            }
        val connection = URI(configuration.normalizedBaseUrl + endpoint + suffix).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            if (configuration.authMode == NightscoutAuthMode.API_SECRET && !secret.isNullOrBlank()) {
                val digest = MessageDigest.getInstance("SHA-1").digest(secret.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                connection.setRequestProperty("api-secret", digest)
            }
            val status = connection.responseCode
            check(status in 200..299) { "HTTP $status" }
            val bytes =
                connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 2_000_000) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 2_000_001 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            check(bytes.size <= 2_000_000) { "Antwort zu groß" }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }
}

internal class NightscoutCgmSource(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var signature: String? = null

    fun refresh() {
        val source = MobileSourceConfigurationStore.read(context)
        val (config, secret) = NightscoutConfigurationStore.access(context)
        val cgmEnabled = MobileGlucoseSource.NIGHTSCOUT in source.enabledGlucoseSources
        val enabled = cgmEnabled || config.enabled
        val next = "$cgmEnabled:${config.enabled}:${config.normalizedBaseUrl}:${config.authMode}:${source.nightscoutRevision}"
        if (signature == next) return
        close()
        signature = next
        if (!enabled) return
        if (!config.isValid()) {
            status("HTTPS-Adresse fehlt")
            return
        }
        val identity = MessageDigest.getInstance("SHA-256").digest("${config.normalizedBaseUrl}:${config.authMode}:${secret.orEmpty()}".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        job =
            scope.launch(Dispatchers.IO) {
                MobileCanonicalStateCoordinator.activateRemoteAccount(context, DataSourceId.NIGHTSCOUT, identity)
                val diagnostics = context.getSharedPreferences("source_diagnostics", Context.MODE_PRIVATE)
                var backfilled = diagnostics.getString("nightscout_backfill_identity", null) == identity
                var failures = 0
                while (isActive) {
                    var waitMs = if (cgmEnabled) 300_000L else 900_000L
                    try {
                        val now = System.currentTimeMillis()
                        if (config.enabled) NightscoutTreatmentSync.syncIfDue(context, now)
                        if (cgmEnabled) {
                            val cached = MobileSourceInputStore(context).read()[DataSourceId.NIGHTSCOUT]
                            val times =
                                cached
                                    ?.glucoseHistory
                                    .orEmpty()
                                    .map { it.measuredAtEpochMs }
                                    .sorted()

                            suspend fun readWindow(since: Long, before: Long? = null): app.aapswear.model.TherapyDisplayState? {
                                val upper = before?.let { "&find%5Bdate%5D%5B%24lt%5D=$it" }.orEmpty()
                                val payload = NightscoutHttp.get(config, secret, "/api/v1/entries/sgv.json?find%5Bdate%5D%5B%24gt%5D=$since$upper&count=1440")
                                ensureActive()
                                val currentConfig = MobileSourceConfigurationStore.read(context)
                                if (currentConfig.nightscoutRevision != source.nightscoutRevision || MobileGlucoseSource.NIGHTSCOUT !in currentConfig.enabledGlucoseSources) throw CancellationException("Source changed")
                                val array =
                                    kotlinx.serialization.json.Json
                                        .parseToJsonElement(payload) as? kotlinx.serialization.json.JsonArray ?: error("Ungültige Antwort")
                                val receivedAt = System.currentTimeMillis()
                                val received = ExternalCgmAdapters.nightscout(payload, receivedAt)
                                check(array.isEmpty() || received != null) { "Keine gültigen Messwerte" }
                                if (received != null) MobileCanonicalStateCoordinator.savePhoneInput(context, received.copy(sourceVersion = identity), receivedAt)
                                return received
                            }
                            val since = if (backfilled) times.lastOrNull() ?: now - 86_400_000L else now - 86_400_000L
                            val state = readWindow(maxOf(since, now - 86_400_000L))
                            val gap = times.zipWithNext().firstOrNull { (a, b) -> b - a > 450_000L }
                            if (backfilled && gap != null) {
                                val gapKey = "$identity:${gap.first}:${gap.second}"
                                val alreadyChecked = diagnostics.getString("nightscout_gap", null) == gapKey && now - diagnostics.getLong("nightscout_gap_at", 0L) < 1_800_000L
                                if (!alreadyChecked) {
                                    // Backfill only the missing interval, not the already cached suffix.
                                    readWindow(gap.first, gap.second)
                                    diagnostics.edit {
                                        putString("nightscout_gap", gapKey)
                                        putLong("nightscout_gap_at", now)
                                    }
                                }
                            }
                            ensureActive()
                            backfilled = true
                            diagnostics.edit { putString("nightscout_backfill_identity", identity) }
                            failures = 0
                            status(if (state?.glucose?.measuredAtEpochMs?.let { now - it <= 900_000L } == true) "Empfang aktiv" else "Keine neueren Werte")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failures++
                        status("Nightscout-Abruf fehlgeschlagen – Adresse und Zugang prüfen")
                        waitMs = (60_000L * (1L shl failures.coerceAtMost(4))).coerceAtMost(900_000L)
                    }
                    delay(waitMs)
                }
            }
    }

    private fun status(value: String) = context.getSharedPreferences("source_diagnostics", Context.MODE_PRIVATE).edit { putString("nightscout_cgm", value) }

    fun close() {
        job?.cancel()
        job = null
        signature = null
    }
}
