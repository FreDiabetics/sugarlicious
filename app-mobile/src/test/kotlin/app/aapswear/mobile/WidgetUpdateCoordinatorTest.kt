package app.aapswear.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class WidgetUpdateCoordinatorTest {
    @Test
    fun `durable dispatcher completes update after activity scope is cancelled`() =
        runBlocking {
            val activityScope = CoroutineScope(coroutineContext + SupervisorJob())
            val processScope = CoroutineScope(coroutineContext + SupervisorJob())
            val rendered = CompletableDeferred<Int>()
            val dispatcher =
                DurableUpdateDispatcher<Int>(processScope) { value ->
                    delay(1)
                    rendered.complete(value)
                }

            dispatcher.request(42)
            activityScope.cancel()

            assertEquals(42, rendered.await())
            processScope.cancel()
        }

    @Test
    fun `active render is followed only by newest pending revision`() =
        runBlocking {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val rendered = Collections.synchronizedList(mutableListOf<Long>())
            val coordinator =
                LatestWinsSurfaceCoordinator<String, Long>(
                    scope = CoroutineScope(coroutineContext),
                    render = { _, revision ->
                        rendered += revision
                        if (revision == 1L) {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                        }
                    },
                )

            coordinator.submit("widget", 1L, 1L)
            firstStarted.await()
            coordinator.submit("widget", 2L, 2L)
            coordinator.submit("widget", 3L, 3L)
            releaseFirst.complete(Unit)
            coordinator.flush("widget", 3L)

            assertEquals(listOf(1L, 3L), rendered)
        }

    @Test
    fun `flush waits until requested revision is rendered`() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val coordinator =
                LatestWinsSurfaceCoordinator<String, Long>(
                    scope = CoroutineScope(coroutineContext),
                    render = { _, _ -> release.await() },
                )

            coordinator.submit("widget", 7L, 7L)
            val flush = async { coordinator.flush("widget", 7L) }
            assertFalse(flush.isCompleted)
            release.complete(Unit)
            flush.await()
            assertTrue(flush.isCompleted)
        }

    @Test
    fun `different widget families render independently`() =
        runBlocking {
            val graphStarted = CompletableDeferred<Unit>()
            val releaseGraph = CompletableDeferred<Unit>()
            val glucoseFinished = CompletableDeferred<Unit>()
            val coordinator =
                LatestWinsSurfaceCoordinator<String, Long>(
                    scope = CoroutineScope(coroutineContext),
                    render = { key, _ ->
                        if (key == "graph") {
                            graphStarted.complete(Unit)
                            releaseGraph.await()
                        } else {
                            glucoseFinished.complete(Unit)
                        }
                    },
                )

            coordinator.submit("graph", 1L, 1L)
            graphStarted.await()
            coordinator.submit("glucose", 1L, 1L)

            assertTrue(glucoseFinished.isCompleted)
            releaseGraph.complete(Unit)
            Unit
        }
}
