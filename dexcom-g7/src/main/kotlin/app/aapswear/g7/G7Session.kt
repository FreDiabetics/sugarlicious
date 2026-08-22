package app.aapswear.g7

import kotlin.math.min

data class G7ReconnectPlan(val nextReconnectEpochMs: Long, val retryCount: Int, val delayMs: Long)

object G7ReconnectScheduler {
    const val EXPECTED_READING_INTERVAL_MS = 5 * 60_000L
    const val PRECONNECT_LEAD_MS = 30_000L
    private const val MAX_BACKOFF_MS = 15 * 60_000L
    private const val GATT_133_INITIAL_RETRY_MS = 15_000L
    private const val MIN_WINDOW_LEAD_MS = 1_000L

    fun afterReading(readingEpochMs: Long): G7ReconnectPlan =
        G7ReconnectPlan(readingEpochMs + EXPECTED_READING_INTERVAL_MS - PRECONNECT_LEAD_MS, 0, EXPECTED_READING_INTERVAL_MS - PRECONNECT_LEAD_MS)

    fun afterFailure(nowEpochMs: Long, retryCount: Int): G7ReconnectPlan {
        val count = (retryCount + 1).coerceAtMost(10)
        val delay = min(15_000L * (1L shl count.coerceAtMost(6)), MAX_BACKOFF_MS)
        return G7ReconnectPlan(nowEpochMs + delay, count, delay)
    }

    /**
     * GATT 133 and a missed advertisement window are transient BLE/cadence failures, not an
     * authentication/session failure. After bounded in-window work is exhausted, wait for the next
     * expected Dexcom advertising window instead of spinning generic exponential retries between
     * the sensor's five-minute transmit opportunities.
     */
    fun afterExpectedWindowMiss(nowEpochMs: Long, lastReadingEpochMs: Long?): G7ReconnectPlan {
        if (lastReadingEpochMs == null) {
            return G7ReconnectPlan(nowEpochMs + GATT_133_INITIAL_RETRY_MS, 0, GATT_133_INITIAL_RETRY_MS)
        }
        val firstWindow = lastReadingEpochMs + EXPECTED_READING_INTERVAL_MS - PRECONNECT_LEAD_MS
        val minimumTrigger = nowEpochMs + MIN_WINDOW_LEAD_MS
        val trigger = if (firstWindow > minimumTrigger) {
            firstWindow
        } else {
            val elapsed = minimumTrigger - firstWindow
            val cycles = elapsed / EXPECTED_READING_INTERVAL_MS + 1L
            firstWindow + cycles * EXPECTED_READING_INTERVAL_MS
        }
        return G7ReconnectPlan(trigger, 0, trigger - nowEpochMs)
    }

    fun afterGatt133(nowEpochMs: Long, lastReadingEpochMs: Long?): G7ReconnectPlan =
        afterExpectedWindowMiss(nowEpochMs, lastReadingEpochMs)
}

object G7RecoveryChain {
    val steps = G7RecoveryStep.entries
    fun stepForFailure(failureCount: Int): G7RecoveryStep = steps[failureCount.coerceIn(0, steps.lastIndex)]
}

class G7SessionManager(initial: G7PersistedState = G7PersistedState()) {
    var state: G7PersistedState = initial
        private set

    /**
     * Stores a newly entered sensor without enabling BLE collection. Sensor setup and collector
     * activation are deliberately separate user actions so merely entering/syncing a code never
     * starts an active scan.
     */
    fun prepareInitialSetup(sensor: G7Sensor): G7PersistedState = transition(
        resetForSensor(sensor).copy(
            collectorEnabled = false,
            connectionState = G7ConnectionState.DISCONNECTED,
            protocolState = G7ProtocolState.IDLE,
            sessionState = G7SessionState.UNINITIALIZED,
        ),
    )

    fun beginInitialSetup(sensor: G7Sensor): G7PersistedState = transition(
        resetForSensor(sensor).copy(
            collectorEnabled = true,
            connectionState = G7ConnectionState.SCANNING,
            protocolState = G7ProtocolState.SCANNING,
            sessionState = G7SessionState.INITIAL_SETUP,
        ),
    )

    /** Enables an already configured collector only after an explicit user/source-selection start. */
    fun startCollector(): G7PersistedState {
        if (state.sensor == null) return state
        if (state.collectorEnabled) return state
        return transition(
            state.copy(
                collectorEnabled = true,
                collectorOwner = CollectorOwner.WATCH,
                connectionState = G7ConnectionState.DISCONNECTED,
                protocolState = G7ProtocolState.IDLE,
                sessionState = if (state.lastReading == null) G7SessionState.INITIAL_SETUP else G7SessionState.READY_FOR_RECONNECT,
                nextReconnectEpochMs = null,
                retryCount = 0,
                lastError = null,
            ),
        )
    }

    fun authenticationStarted(reconnect: Boolean): G7PersistedState = transition(
        state.copy(sessionState = if (reconnect) G7SessionState.REAUTHENTICATING else G7SessionState.AUTHENTICATING, authenticationState = G7AuthenticationState.AUTHENTICATING, protocolState = G7ProtocolState.AUTHENTICATING),
    )

    fun authenticationSucceeded(): G7PersistedState = transition(
        state.copy(sessionState = G7SessionState.AUTHENTICATED, authenticationState = G7AuthenticationState.AUTHENTICATED),
    )

    /**
     * A packet may arrive now while describing an older sensor measurement. Only a fresh valid
     * packet advances the displayable current reading and clears the fresh-slot cadence. Aged valid
     * packets remain diagnostic/history data and schedule the next attempt from the prior fresh
     * cadence rather than pretending the missed current slot succeeded.
     */
    fun readingReceived(
        reading: CgmReading,
        fresh: Boolean = true,
        nowEpochMs: Long = reading.receivedAtEpochMs,
    ): G7PersistedState {
        val acceptedFresh = fresh && reading.status == CgmReadingStatus.VALID
        val plan =
            if (acceptedFresh) {
                G7ReconnectScheduler.afterReading(reading.timestampEpochMs)
            } else {
                G7ReconnectScheduler.afterExpectedWindowMiss(nowEpochMs, state.lastReading?.timestampEpochMs)
            }
        return transition(
            state.copy(
                lastReading = if (acceptedFresh) reading else state.lastReading,
                sessionState = G7SessionState.WAITING_FOR_NEXT_READING,
                protocolState = G7ProtocolState.RECEIVING_GLUCOSE,
                nextReconnectEpochMs = plan.nextReconnectEpochMs,
                retryCount = if (acceptedFresh) 0 else state.retryCount,
                lastError = if (acceptedFresh) null else state.lastError,
            ),
        )
    }

    fun failure(error: G7CollectorError): G7PersistedState {
        if (!error.recoverable) {
            return transition(
                state.copy(
                    sessionState = G7SessionState.USER_INTERVENTION_REQUIRED,
                    nextReconnectEpochMs = null,
                    lastError = error,
                ),
            )
        }
        if (error.code == GATT_133_ERROR_CODE || error.code == NO_ADVERTISEMENT_ERROR_CODE) {
            val plan = G7ReconnectScheduler.afterExpectedWindowMiss(error.occurredAtEpochMs, state.lastReading?.timestampEpochMs)
            return transition(
                state.copy(
                    sessionState = G7SessionState.RECOVERING,
                    connectionState = G7ConnectionState.DISCONNECTED,
                    protocolState = G7ProtocolState.RECOVERING,
                    retryCount = 0,
                    nextReconnectEpochMs = plan.nextReconnectEpochMs,
                    lastError = error,
                ),
            )
        }
        val step = G7RecoveryChain.stepForFailure(state.retryCount)
        val plan = G7ReconnectScheduler.afterFailure(error.occurredAtEpochMs, state.retryCount)
        val session = when (step) {
            G7RecoveryStep.SESSION_REAUTH -> G7SessionState.REAUTHENTICATING
            G7RecoveryStep.SESSION_RESET -> G7SessionState.REQUIRES_FULL_HANDSHAKE
            G7RecoveryStep.REBOND -> G7SessionState.REQUIRES_REBOND
            G7RecoveryStep.FULL_HANDSHAKE -> G7SessionState.REQUIRES_FULL_HANDSHAKE
            G7RecoveryStep.USER_INTERVENTION_REQUIRED -> G7SessionState.USER_INTERVENTION_REQUIRED
            else -> G7SessionState.RECOVERING
        }
        return transition(state.copy(sessionState = session, authenticationState = if (error.code.startsWith("AUTH")) G7AuthenticationState.FAILED else state.authenticationState, retryCount = plan.retryCount, nextReconnectEpochMs = plan.nextReconnectEpochMs, lastError = error))
    }

    fun stop(): G7PersistedState = transition(
        state.copy(
            collectorEnabled = false,
            sessionState = G7SessionState.UNINITIALIZED,
            connectionState = G7ConnectionState.DISCONNECTED,
            protocolState = G7ProtocolState.IDLE,
            nextReconnectEpochMs = null,
            retryCount = 0,
        ),
    )

    private fun resetForSensor(sensor: G7Sensor): G7PersistedState =
        state.copy(
            sensor = sensor,
            collectorOwner = CollectorOwner.WATCH,
            authenticationState = G7AuthenticationState.REQUIRED,
            lastReading = null,
            lastSuccessfulConnectionEpochMs = null,
            nextReconnectEpochMs = null,
            retryCount = 0,
            lastError = null,
        )

    private fun transition(next: G7PersistedState): G7PersistedState = next.also { state = it }

    private companion object {
        const val GATT_133_ERROR_CODE = "G7-GATT-133"
        const val NO_ADVERTISEMENT_ERROR_CODE = "G7-BLE-107"
    }
}

class CollectorOwnershipManager(initial: CollectorOwner = CollectorOwner.UNKNOWN) {
    var owner: CollectorOwner = initial
        private set
    fun requestWatch(): CollectorOwner = CollectorOwner.TRANSITION_TO_WATCH.also { owner = it }
    fun confirmWatchAfterValidReading(): CollectorOwner = CollectorOwner.WATCH.also { owner = it }
    fun requestPhone(): CollectorOwner = CollectorOwner.TRANSITION_TO_PHONE.also { owner = it }
    fun confirmPhone(): CollectorOwner = CollectorOwner.PHONE.also { owner = it }
    fun phoneDisconnected(): CollectorOwner = owner
}
