package app.aapswear.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import app.aapswear.model.CanonicalTreatments
import app.aapswear.model.TherapyDisplayState
import app.aapswear.model.TherapyEvent
import app.aapswear.model.TherapyEventKind
import app.aapswear.model.TherapyEventSource
import app.aapswear.storage.PhoneTherapyStateStore
import app.aapswear.storage.TherapyStateStore
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal enum class NightscoutAuthMode { API_SECRET, ACCESS_TOKEN }

internal data class NightscoutConfiguration(
    val enabled: Boolean,
    val baseUrl: String,
    val authMode: NightscoutAuthMode,
) {
    val normalizedBaseUrl: String get() = baseUrl.trim().trimEnd('/')
    fun isValid(): Boolean = runCatching {
        val uri = URI(normalizedBaseUrl)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null
    }.getOrDefault(false)
}

internal object NightscoutConfigurationStore {
    private const val PREFS = "nightscout_treatment_config"
    private const val ENABLED = "enabled"
    private const val URL = "url"
    private const val AUTH_MODE = "authMode"

    fun read(context: Context): NightscoutConfiguration {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return NightscoutConfiguration(
            enabled = preferences.getBoolean(ENABLED, false),
            baseUrl = preferences.getString(URL, "").orEmpty(),
            authMode = runCatching { NightscoutAuthMode.valueOf(preferences.getString(AUTH_MODE, NightscoutAuthMode.API_SECRET.name)!!) }.getOrDefault(NightscoutAuthMode.API_SECRET),
        )
    }

    fun save(context: Context, configuration: NightscoutConfiguration, secret: String?) {
        require(!configuration.enabled || configuration.isValid()) { "Nightscout-URL muss eine gültige HTTPS-Adresse sein" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(ENABLED, configuration.enabled)
            putString(URL, configuration.normalizedBaseUrl)
            putString(AUTH_MODE, configuration.authMode.name)
        }
        if (secret != null) NightscoutSecretStore.save(context, secret)
    }
}

/** Android-Keystore backed secret storage. Ciphertext is never included in SettingsBackup. */
internal object NightscoutSecretStore {
    private const val ALIAS = "sugarlicious_nightscout_auth_v1"
    private const val PREFS = "nightscout_treatment_secret"
    private const val VALUE = "value"
    private const val IV = "iv"

    fun save(context: Context, value: String) {
        if (value.isBlank()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(VALUE, android.util.Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP))
            putString(IV, android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
        }
    }

    fun read(context: Context): String? = runCatching {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = android.util.Base64.decode(preferences.getString(VALUE, null) ?: return null, android.util.Base64.NO_WRAP)
        val iv = android.util.Base64.decode(preferences.getString(IV, null) ?: return null, android.util.Base64.NO_WRAP)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(doFinal(encrypted), StandardCharsets.UTF_8)
        }
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}

internal object NightscoutTreatmentParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(payload: String): List<TherapyEvent> {
        val array = runCatching { json.parseToJsonElement(payload).jsonArray }.getOrElse { return emptyList() }
        return array.flatMap { element -> parseObject(runCatching { element.jsonObject }.getOrNull() ?: return@flatMap emptyList()) }
            .distinctBy(TherapyEvent::id)
            .sortedBy(TherapyEvent::timestampEpochMs)
    }

    private fun parseObject(item: JsonObject): List<TherapyEvent> {
        val timestamp = timestamp(item) ?: return emptyList()
        val sourceId = text(item, "_id") ?: text(item, "identifier") ?: text(item, "id")
        val eventType = text(item, "eventType")?.trim().orEmpty()
        val enteredBy = text(item, "enteredBy")
        val notes = text(item, "notes").orEmpty()
        val insulin = number(item, "insulin")?.takeIf { it > 0.0 }
        val carbs = number(item, "carbs")?.takeIf { it > 0.0 }
        val duration = integer(item, "duration")?.takeIf { it > 0 }
        val explicitSmb = boolean(item, "isSMB") == true || eventType.contains("SMB", true) || notes.contains("SMB", true)
        val eCarb = duration != null || eventType.contains("eCarb", true) || notes.contains("eCarb", true)
        val baseId = sourceId ?: sha256("$timestamp|$eventType|$insulin|$carbs|$duration|$enteredBy")
        return buildList {
            insulin?.let {
                val kind = when {
                    explicitSmb -> TherapyEventKind.SMB
                    eventType.contains("meal", true) || carbs != null -> TherapyEventKind.MEAL_BOLUS
                    eventType.contains("correction", true) || eventType.contains("bolus", true) -> TherapyEventKind.MANUAL_CORRECTION
                    else -> null
                }
                if (kind != null) add(event("$baseId:insulin", baseId, kind, timestamp, it, insulin, carbs, duration, enteredBy, eventType))
            }
            carbs?.let {
                val kind = if (eCarb) TherapyEventKind.ECARBS else TherapyEventKind.MEAL_CARBS
                add(event("$baseId:carbs", baseId, kind, timestamp, it, insulin, carbs, duration, enteredBy, eventType))
            }
        }
    }

    private fun event(id: String, sourceId: String, kind: TherapyEventKind, timestamp: Long, amount: Double, insulin: Double?, carbs: Double?, duration: Int?, enteredBy: String?, eventType: String) =
        TherapyEvent(id, kind, timestamp, amount, TherapyEventSource.NIGHTSCOUT_ONLY, sourceId, insulin, carbs, duration, enteredBy, eventType, validated = true)

    private fun timestamp(item: JsonObject): Long? {
        val direct = listOf("date", "timestamp").firstNotNullOfOrNull { key -> item[key]?.jsonPrimitive?.longOrNull }?.let(::normalizeEpoch)
        if (direct != null) return direct
        return listOf("created_at", "dateString").firstNotNullOfOrNull { key -> text(item, key)?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } }
    }
    private fun normalizeEpoch(value: Long) = if (value in 1..9_999_999_999L) value * 1_000L else value
    private fun text(item: JsonObject, key: String) = item[key]?.jsonPrimitive?.contentOrNull
    private fun number(item: JsonObject, key: String) = item[key]?.jsonPrimitive?.doubleOrNull
    private fun integer(item: JsonObject, key: String) = item[key]?.jsonPrimitive?.intOrNull
    private fun boolean(item: JsonObject, key: String) = item[key]?.jsonPrimitive?.booleanOrNull
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

@Serializable private data class StoredTreatments(val values: List<TherapyEvent> = emptyList())

internal object NightscoutTreatmentStore {
    private const val PREFS = "nightscout_treatment_history"
    private const val DATA = "events"
    private const val RETENTION_MS = 7L * 24 * 60 * 60_000L
    private val json = Json { ignoreUnknownKeys = true }

    fun read(context: Context, now: Long = System.currentTimeMillis()): List<TherapyEvent> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DATA, null) ?: return emptyList()
        return runCatching { json.decodeFromString(StoredTreatments.serializer(), raw).values }.getOrDefault(emptyList())
            .filter { it.timestampEpochMs >= now - RETENTION_MS }
    }

    fun merge(context: Context, incoming: List<TherapyEvent>, now: Long): List<TherapyEvent> {
        val values = (read(context, now) + incoming).filter { it.timestampEpochMs in (now - RETENTION_MS)..(now + 5 * 60_000L) }.distinctBy(TherapyEvent::id).sortedBy(TherapyEvent::timestampEpochMs)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(DATA, json.encodeToString(StoredTreatments.serializer(), StoredTreatments(values))) }
        return values
    }
}

internal data class NightscoutSyncResult(val success: Boolean, val count: Int, val message: String)

internal object NightscoutTreatmentSync {
    private const val DIAGNOSTICS = "diagnostics"
    private const val WINDOW_MS = 24L * 60 * 60_000L
    private const val MIN_SYNC_INTERVAL_MS = 15L * 60_000L

    suspend fun syncIfDue(context: Context, now: Long = System.currentTimeMillis()): NightscoutSyncResult? {
        val configuration = NightscoutConfigurationStore.read(context)
        if (!configuration.enabled) return null
        val diagnostics = context.getSharedPreferences(DIAGNOSTICS, Context.MODE_PRIVATE)
        if (now - diagnostics.getLong("nightscoutTreatmentLastAttempt", 0L) < MIN_SYNC_INTERVAL_MS) return null
        diagnostics.edit { putLong("nightscoutTreatmentLastAttempt", now) }
        return sync(context, now)
    }

    suspend fun sync(context: Context, now: Long = System.currentTimeMillis()): NightscoutSyncResult {
        val app = context.applicationContext
        val configuration = NightscoutConfigurationStore.read(app)
        if (!configuration.enabled) return NightscoutSyncResult(true, 0, "Deaktiviert")
        if (!configuration.isValid()) return failure(app, "Ungültige HTTPS-URL")
        val secret = NightscoutSecretStore.read(app)?.takeIf(String::isNotBlank) ?: return failure(app, "Authentifizierung fehlt")
        app.getSharedPreferences(DIAGNOSTICS, Context.MODE_PRIVATE).edit { putLong("nightscoutTreatmentLastAttempt", now) }
        return runCatching {
            val since = java.time.Instant.ofEpochMilli(now - WINDOW_MS).toString()
            val tokenSuffix = if (configuration.authMode == NightscoutAuthMode.ACCESS_TOKEN) "&token=${encode(secret)}" else ""
            val url = "${configuration.normalizedBaseUrl}/api/v1/treatments.json?find%5Bcreated_at%5D%5B%24gte%5D=${encode(since)}&count=1000$tokenSuffix"
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            if (configuration.authMode == NightscoutAuthMode.API_SECRET) connection.setRequestProperty("api-secret", sha1(secret))
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            val fetched = NightscoutTreatmentParser.parse(payload)
            val persisted = NightscoutTreatmentStore.merge(app, fetched, now)
            enrichPersistedState(app, persisted)
            app.getSharedPreferences(DIAGNOSTICS, Context.MODE_PRIVATE).edit {
                putLong("nightscoutTreatmentLastSuccess", now)
                putString("nightscoutTreatmentStatus", "ok")
                putInt("nightscoutTreatmentCount", persisted.size)
                remove("nightscoutTreatmentError")
            }
            NightscoutSyncResult(true, fetched.size, "Verbunden · ${fetched.size} Treatments")
        }.getOrElse { error -> failure(app, error.message?.take(80) ?: error.javaClass.simpleName) }
    }

    suspend fun applyConfigurationState(context: Context) {
        val app = context.applicationContext
        if (NightscoutConfigurationStore.read(app).enabled) return
        val phoneStore = PhoneTherapyStateStore(app)
        val displayStore = TherapyStateStore(app)
        val current = phoneStore.state.first() ?: displayStore.state.first() ?: return
        val aapsOnly = current.copy(therapyEvents = current.therapyEvents.mapNotNull { event ->
            when (event.source) {
                TherapyEventSource.NIGHTSCOUT_ONLY -> null
                TherapyEventSource.AAPS_ENRICHED_BY_NIGHTSCOUT -> event.copy(source = TherapyEventSource.AAPS_ONLY)
                TherapyEventSource.AAPS_ONLY -> event
            }
        })
        phoneStore.save(aapsOnly)
        displayStore.save(aapsOnly)
    }

    private suspend fun enrichPersistedState(context: Context, nightscout: List<TherapyEvent>) {
        val phoneStore = PhoneTherapyStateStore(context)
        val displayStore = TherapyStateStore(context)
        val current = phoneStore.state.first() ?: displayStore.state.first() ?: return
        val aaps = current.therapyEvents.filter { it.source != TherapyEventSource.NIGHTSCOUT_ONLY }
        val enriched = current.copy(therapyEvents = CanonicalTreatments.merge(aaps, nightscout))
        phoneStore.save(enriched)
        displayStore.save(enriched)
    }

    private fun failure(context: Context, error: String): NightscoutSyncResult {
        context.getSharedPreferences(DIAGNOSTICS, Context.MODE_PRIVATE).edit {
            putString("nightscoutTreatmentStatus", "unavailable")
            putString("nightscoutTreatmentError", error)
        }
        return NightscoutSyncResult(false, 0, error)
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun sha1(value: String) = MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal fun TherapyDisplayState.withNightscoutTreatments(context: Context): TherapyDisplayState {
    if (!NightscoutConfigurationStore.read(context).enabled) {
        return copy(therapyEvents = therapyEvents.mapNotNull { event ->
            when (event.source) {
                TherapyEventSource.NIGHTSCOUT_ONLY -> null
                TherapyEventSource.AAPS_ENRICHED_BY_NIGHTSCOUT -> event.copy(source = TherapyEventSource.AAPS_ONLY)
                TherapyEventSource.AAPS_ONLY -> event
            }
        })
    }
    val enrichment = NightscoutTreatmentStore.read(context, receivedAtEpochMs)
    if (enrichment.isEmpty()) return this
    val aaps = therapyEvents.filter { it.source != TherapyEventSource.NIGHTSCOUT_ONLY }
    return copy(therapyEvents = CanonicalTreatments.merge(aaps, enrichment))
}
