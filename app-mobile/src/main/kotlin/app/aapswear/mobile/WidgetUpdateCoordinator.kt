package app.aapswear.mobile

import android.content.Context
import app.aapswear.model.DiagnosticSeverity
import app.aapswear.model.TherapyDisplayState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class LatestWinsSurfaceCoordinator<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val render: suspend (K, V) -> Unit,
    private val onFailure: suspend (K, Long, Throwable) -> Unit = { _, _, _ -> },
) {
    private data class Request<V>(
        val revision: Long,
        val value: V,
    )

    private data class Waiter(
        val revision: Long,
        val completion: CompletableDeferred<Unit>,
    )

    private class Slot<V> {
        var runningRevision: Long? = null
        var pending: Request<V>? = null
        var completedRevision: Long = Long.MIN_VALUE
        val waiters = mutableListOf<Waiter>()
    }

    private val lock = Any()
    private val slots = mutableMapOf<K, Slot<V>>()

    fun submit(
        key: K,
        revision: Long,
        value: V,
    ) {
        val initial =
            synchronized(lock) {
                val slot = slots.getOrPut(key) { Slot() }
                val newestKnown = maxOf(slot.completedRevision, slot.runningRevision ?: Long.MIN_VALUE, slot.pending?.revision ?: Long.MIN_VALUE)
                if (revision < newestKnown) return
                val request = Request(revision, value)
                if (slot.runningRevision != null) {
                    slot.pending = request
                    null
                } else {
                    slot.runningRevision = revision
                    request
                }
            }
        if (initial != null) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { drain(key, initial) }
        }
    }

    suspend fun flush(
        key: K,
        revision: Long,
    ) {
        val completion =
            synchronized(lock) {
                val slot = slots.getOrPut(key) { Slot() }
                if (slot.completedRevision >= revision) return
                CompletableDeferred<Unit>().also { slot.waiters += Waiter(revision, it) }
            }
        completion.await()
    }

    private suspend fun drain(
        key: K,
        initial: Request<V>,
    ) {
        var current = initial
        while (true) {
            runCatching { render(key, current.value) }
                .onFailure { error -> onFailure(key, current.revision, error) }

            val next =
                synchronized(lock) {
                    val slot = checkNotNull(slots[key])
                    slot.completedRevision = maxOf(slot.completedRevision, current.revision)
                    val completed = slot.waiters.filter { it.revision <= slot.completedRevision }
                    slot.waiters.removeAll(completed.toSet())
                    completed.forEach { it.completion.complete(Unit) }
                    slot.pending?.also {
                        slot.pending = null
                        slot.runningRevision = it.revision
                    } ?: run {
                        slot.runningRevision = null
                        null
                    }
                } ?: return
            current = next
        }
    }
}

internal object WidgetSurfaceRevision {
    private val sequence = AtomicLong(System.currentTimeMillis())

    fun next(): Long = sequence.updateAndGet { previous -> maxOf(previous + 1L, System.currentTimeMillis()) }
}

internal sealed interface WidgetUpdateTarget {
    data class Family(val kind: WidgetKind) : WidgetUpdateTarget

    data class Instance(
        val appWidgetId: Int,
        val kind: ConfigurableWidgetKind,
    ) : WidgetUpdateTarget
}

internal data class WidgetRenderRequest(
    val context: Context,
    val state: TherapyDisplayState?,
)

internal object WidgetRenderStateCache {
    private val latest = AtomicReference<TherapyDisplayState?>()

    fun publish(state: TherapyDisplayState) {
        latest.set(state)
    }

    fun latest(): TherapyDisplayState? = latest.get()
}

internal object SugarliciousWidgetUpdateCoordinator {
    private val initialization = Mutex()
    private var coordinator: LatestWinsSurfaceCoordinator<WidgetUpdateTarget, WidgetRenderRequest>? = null

    private suspend fun instance(context: Context): LatestWinsSurfaceCoordinator<WidgetUpdateTarget, WidgetRenderRequest> {
        coordinator?.let { return it }
        return initialization.withLock {
            coordinator ?: create(context.applicationContext).also { coordinator = it }
        }
    }

    private fun create(context: Context) =
        LatestWinsSurfaceCoordinator<WidgetUpdateTarget, WidgetRenderRequest>(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            render = { target, request ->
                request.state?.let(WidgetRenderStateCache::publish)
                when (target) {
                    is WidgetUpdateTarget.Family -> SugarliciousWidgets.renderFamily(request.context, target.kind)
                    is WidgetUpdateTarget.Instance ->
                        SugarliciousWidgets.renderInstance(
                            request.context,
                            target.appWidgetId,
                            target.kind,
                        )
                }
            },
            onFailure = { target, revision, error ->
                context.recordMobileDiagnostic(
                    module = "WIDGET",
                    code = "WIDGET-RENDER-503",
                    message = "Widget render retained last-known-good surface",
                    severity = DiagnosticSeverity.WARNING,
                    metadata =
                        mapOf(
                            "target" to target.toString(),
                            "revision" to revision,
                            "error" to error.javaClass.simpleName,
                        ),
                )
            },
        )

    suspend fun updateAll(
        context: Context,
        state: TherapyDisplayState,
    ) = coroutineScope {
        val revision = WidgetSurfaceRevision.next()
        val coordinator = instance(context)
        val targets = WidgetKind.entries.map(WidgetUpdateTarget::Family)
        targets.forEach { target -> coordinator.submit(target, revision, WidgetRenderRequest(context.applicationContext, state)) }
        targets.forEach { target -> coordinator.flush(target, revision) }
    }

    suspend fun submitInstance(
        context: Context,
        appWidgetId: Int,
        kind: ConfigurableWidgetKind,
    ): Pair<WidgetUpdateTarget.Instance, Long> {
        val target = WidgetUpdateTarget.Instance(appWidgetId, kind)
        val revision = WidgetSurfaceRevision.next()
        instance(context).submit(target, revision, WidgetRenderRequest(context.applicationContext, null))
        return target to revision
    }

    suspend fun flush(
        context: Context,
        target: WidgetUpdateTarget,
        revision: Long,
    ) {
        instance(context).flush(target, revision)
    }
}
