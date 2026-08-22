package app.aapswear.g7watch

import app.aapswear.g7.G7PersistedState
import app.aapswear.g7.G7ProtocolState
import app.aapswear.g7.G7SensorState
import app.aapswear.g7.G7SessionState

internal enum class G7UserStatusLevel { OK, WORKING, ATTENTION, ERROR, OFF }

internal data class G7UserStatus(
    val level: G7UserStatusLevel,
    val title: String,
    val phase: String,
    val status: String,
    val description: String,
    val action: String,
)

internal fun deriveG7UserStatus(
    state: G7PersistedState,
    credentialsPresent: Boolean,
    nowEpochMs: Long = System.currentTimeMillis(),
): G7UserStatus {
    if (!state.collectorEnabled) {
        return G7UserStatus(
            G7UserStatusLevel.OFF,
            "Collector inaktiv",
            "Aus",
            "Gestoppt",
            "Der direkte G7-Empfang ist deaktiviert.",
            "Zum direkten Empfang „Collector starten“ wählen.",
        )
    }
    if (state.sensor == null) {
        return problem(
            title = "Einrichtung erforderlich",
            phase = "Sensor einrichten",
            description = "Es ist kein G7-Sensor eingerichtet.",
            action = "Vierstelligen Sensorcode speichern und danach den Collector starten.",
        )
    }
    if (!credentialsPresent) {
        return problem(
            title = "Sensorcode fehlt",
            phase = "Einrichtung prüfen",
            description = "Die lokalen G7-Zugangsdaten sind nicht verfügbar.",
            action = "Sensorcode erneut eingeben. Bond oder Shared Key nicht manuell löschen.",
        )
    }

    val ageMs = state.lastReading?.timestampEpochMs?.let { (nowEpochMs - it).coerceAtLeast(0L) }
    val hardSessionProblem = state.sessionState in setOf(
        G7SessionState.REQUIRES_REBOND,
        G7SessionState.REQUIRES_FULL_HANDSHAKE,
        G7SessionState.USER_INTERVENTION_REQUIRED,
    )
    if (hardSessionProblem) {
        return problemFromError(
            state = state,
            title = "Eingriff erforderlich",
            phase = phaseName(state.protocolState),
        )
    }

    if (state.sensor?.state == G7SensorState.ERROR) {
        return G7UserStatus(
            G7UserStatusLevel.ATTENTION,
            "Sensorfehler",
            "Sensorstatus",
            "Kein gültiger G7-Wert",
            "Der Sensor hat einen Fehlerstatus gemeldet. Der letzte gültige Wert bleibt als veraltet erkennbar erhalten.",
            "Sensor und Reichweite prüfen und die automatische Wiederverbindung abwarten. Bond, Shared Key und Sensorcode nicht löschen.",
        )
    }

    if (ageMs != null && ageMs >= G7_SIGNAL_LOSS_AFTER_MS) {
        return G7UserStatus(
            G7UserStatusLevel.ATTENTION,
            "Signalverlust",
            phaseName(state.protocolState),
            "Kein aktueller G7-Wert",
            "Seit mindestens 16 Minuten wurde kein valider direkter G7-Wert empfangen.",
            actionForError(state.lastError?.code) ?: "Bluetooth und Sensorreichweite prüfen und die Uhr am Sensor lassen. Bond, Shared Key und Sensorcode nicht löschen.",
        )
    }

    return when (state.protocolState) {
        G7ProtocolState.SCANNING,
        G7ProtocolState.SENSOR_FOUND,
        G7ProtocolState.CONNECTING,
        G7ProtocolState.DISCOVERING,
        G7ProtocolState.DISCOVERING_SERVICES,
        G7ProtocolState.ENABLING_NOTIFICATIONS,
        G7ProtocolState.AUTHENTICATION_START,
        G7ProtocolState.AUTHENTICATION_ROUND_1,
        G7ProtocolState.AUTHENTICATION_ROUND_2,
        G7ProtocolState.AUTHENTICATION_ROUND_3,
        G7ProtocolState.CHALLENGE,
        G7ProtocolState.CERTIFICATE_EXCHANGE,
        G7ProtocolState.KEY_EXCHANGE,
        G7ProtocolState.BONDING,
        G7ProtocolState.AUTHENTICATING,
        G7ProtocolState.AUTHENTICATED,
        G7ProtocolState.REQUESTING_GLUCOSE,
        G7ProtocolState.RECEIVING_GLUCOSE,
        G7ProtocolState.BACKFILL,
        -> G7UserStatus(
            G7UserStatusLevel.WORKING,
            if (ageMs == null) "Verbindung wird aufgebaut" else "Verbunden",
            phaseName(state.protocolState),
            "Aktiv · Messzyklus läuft",
            "Der Collector verarbeitet das aktuelle G7-Sensorfenster.",
            "Kein Eingriff erforderlich.",
        )

        G7ProtocolState.WAITING_FOR_NEXT_READING,
        G7ProtocolState.IDLE,
        G7ProtocolState.DISCONNECTED,
        -> G7UserStatus(
            G7UserStatusLevel.OK,
            if (ageMs == null) "Bereit" else "Verbunden",
            "Bereit für nächsten Wert",
            "Aktiv · Datenfluss in Ordnung",
            ageMs?.let { "Der letzte valide G7-Wert ist ${formatAge(it)} alt. Der nächste Messzyklus wird automatisch zum Sensorfenster gestartet." }
                ?: "Der Collector ist aktiv und wartet auf den ersten validen G7-Wert.",
            "Kein Eingriff erforderlich.",
        )

        G7ProtocolState.RECOVERING -> G7UserStatus(
            G7UserStatusLevel.WORKING,
            "Automatische Wiederverbindung",
            "Nächstes Sensorfenster",
            if (ageMs == null) "Aktiv · Verbindung wird aufgebaut" else "Aktiv · letzter Wert ${formatAge(ageMs)} alt",
            "Ein Sensorfenster wurde verpasst oder Android BLE hat die Verbindung kurz unterbrochen. Sugarlicious versucht automatisch das nächste erwartete G7-Fenster.",
            "Nichts zurücksetzen. Uhr in Sensornähe lassen; erst ab 16 Minuten ohne Wert ist dies ein Signalverlust.",
        )

        G7ProtocolState.ERROR -> {
            val error = state.lastError
            if (error?.recoverable == true && (ageMs == null || ageMs < G7_SIGNAL_LOSS_AFTER_MS)) {
                G7UserStatus(
                    G7UserStatusLevel.WORKING,
                    "Automatische Wiederverbindung",
                    "Recovery",
                    "Aktiv · kein Benutzereingriff nötig",
                    error.safeMessage,
                    "Sugarlicious automatisch weiterarbeiten lassen. Bond, Shared Key und Sensorcode nicht löschen.",
                )
            } else {
                problemFromError(state, "Problem erkannt", "Fehler")
            }
        }

        G7ProtocolState.UNINITIALIZED -> problem(
            title = "Noch nicht initialisiert",
            phase = "Initialisierung",
            description = "Der Collector wurde noch nicht vollständig initialisiert.",
            action = "Sensor-Einrichtung prüfen und den Collector starten.",
        )
    }
}

private fun problemFromError(
    state: G7PersistedState,
    title: String,
    phase: String,
): G7UserStatus {
    val error = state.lastError
    return G7UserStatus(
        G7UserStatusLevel.ERROR,
        title,
        phase,
        error?.code ?: state.sessionState.name,
        error?.safeMessage ?: "Der Collector benötigt einen Benutzereingriff.",
        actionForError(error?.code) ?: "Bluetooth, Berechtigungen und Sensorstatus prüfen. Bond, Shared Key und Sensorcode nur bei ausdrücklicher Anweisung zurücksetzen.",
    )
}

private fun problem(
    title: String,
    phase: String,
    description: String,
    action: String,
): G7UserStatus =
    G7UserStatus(G7UserStatusLevel.ERROR, title, phase, "Eingriff erforderlich", description, action)

private fun actionForError(code: String?): String? = when {
    code == null -> null
    code == "G7-BLE-103" -> "Bluetooth auf der Uhr einschalten und die Uhr in Sensornähe lassen."
    code.startsWith("G7-PERM-") -> "Bluetooth- und Benachrichtigungsberechtigungen in der Collector-App freigeben."
    code.startsWith("G7-SETUP-") -> "Sensorcode und Sensoreinrichtung in der Collector-App prüfen."
    code.startsWith("G7-AUTH-") -> "Uhr in Sensornähe lassen und automatische Wiederverbindung abwarten. Bond, Shared Key und Sensorcode nicht löschen."
    code == "G7-GATT-133" || code == "G7-BLE-107" || code == "G7-BLE-111" -> "Nichts zurücksetzen. Sugarlicious versucht automatisch das nächste Sensorfenster."
    code == "G7-SIGNAL-LOSS" -> "Bluetooth, Sensorreichweite und Sensorstatus prüfen. Bond, Shared Key und Sensorcode nicht löschen."
    else -> null
}

private fun phaseName(state: G7ProtocolState): String = when (state) {
    G7ProtocolState.UNINITIALIZED -> "Nicht initialisiert"
    G7ProtocolState.IDLE -> "Bereit"
    G7ProtocolState.SCANNING -> "Sensor suchen"
    G7ProtocolState.SENSOR_FOUND -> "Sensor gefunden"
    G7ProtocolState.CONNECTING -> "Verbinden"
    G7ProtocolState.DISCOVERING,
    G7ProtocolState.DISCOVERING_SERVICES,
    -> "G7-Dienste prüfen"
    G7ProtocolState.ENABLING_NOTIFICATIONS -> "Datenkanäle öffnen"
    G7ProtocolState.AUTHENTICATION_START,
    G7ProtocolState.AUTHENTICATION_ROUND_1,
    G7ProtocolState.AUTHENTICATION_ROUND_2,
    G7ProtocolState.AUTHENTICATION_ROUND_3,
    G7ProtocolState.CHALLENGE,
    G7ProtocolState.CERTIFICATE_EXCHANGE,
    G7ProtocolState.KEY_EXCHANGE,
    G7ProtocolState.AUTHENTICATING,
    -> "Authentifizieren"
    G7ProtocolState.BONDING -> "Koppeln"
    G7ProtocolState.AUTHENTICATED -> "Authentifiziert"
    G7ProtocolState.REQUESTING_GLUCOSE -> "Wert anfordern"
    G7ProtocolState.RECEIVING_GLUCOSE -> "Wert empfangen"
    G7ProtocolState.BACKFILL -> "Historie nachladen"
    G7ProtocolState.WAITING_FOR_NEXT_READING -> "Bereit für nächsten Wert"
    G7ProtocolState.DISCONNECTED -> "Zwischen Sensorfenstern getrennt"
    G7ProtocolState.RECOVERING -> "Automatische Wiederverbindung"
    G7ProtocolState.ERROR -> "Fehler"
}

private fun formatAge(ageMs: Long): String {
    val seconds = ageMs / 1_000L
    return if (seconds < 90L) "$seconds s" else "${seconds / 60L} min"
}
