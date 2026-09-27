package app.aapswear.storage

import android.content.Context
import app.aapswear.model.TherapyDisplayState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface CanonicalStateBackend {
    suspend fun readPhone(): TherapyDisplayState?

    suspend fun readDisplay(): TherapyDisplayState?

    suspend fun writePhone(value: TherapyDisplayState)

    suspend fun writeDisplay(value: TherapyDisplayState)
}

/** Restart-safe two-copy commit protocol for Mobile's raw and canonical state. */
class CanonicalStateStore(
    private val backend: CanonicalStateBackend,
) {
    constructor(context: Context) : this(AndroidCanonicalStateBackend(context.applicationContext))

    suspend fun reconcile(): TherapyDisplayState? = commitMutex.withLock { reconcileUnlocked() }

    suspend fun commit(value: TherapyDisplayState): TherapyDisplayState =
        commitMutex.withLock {
            val current = reconcileUnlocked()
            val nextRevision = maxOf(current?.canonicalRevision ?: 0L, value.canonicalRevision) + 1L
            val committed = value.copy(canonicalRevision = nextRevision)
            backend.writePhone(committed)
            backend.writeDisplay(committed)
            committed
        }

    private suspend fun reconcileUnlocked(): TherapyDisplayState? {
        val phone = backend.readPhone()
        val display = backend.readDisplay()
        val winner =
            when {
                phone == null -> display
                display == null -> phone
                phone.canonicalRevision >= display.canonicalRevision -> phone
                else -> display
            } ?: return null
        if (phone != winner) backend.writePhone(winner)
        if (display != winner) backend.writeDisplay(winner)
        return winner
    }

    private companion object {
        val commitMutex = Mutex()
    }
}

private class AndroidCanonicalStateBackend(
    context: Context,
) : CanonicalStateBackend {
    private val phone = PhoneTherapyStateStore(context)
    private val display = TherapyStateStore(context)

    override suspend fun readPhone(): TherapyDisplayState? = phone.state.first()

    override suspend fun readDisplay(): TherapyDisplayState? = display.state.first()

    override suspend fun writePhone(value: TherapyDisplayState) = phone.save(value)

    override suspend fun writeDisplay(value: TherapyDisplayState) = display.save(value)
}
