package app.aapswear.g7watch

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.ArrayDeque

@RunWith(RobolectricTestRunner::class)
class G7ReceiverWorkDispatcherTest {
    @Test fun `boot recovery is deferred until the background dispatcher runs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queued = ArrayDeque<() -> Unit>()
        var recoveryRan = false
        var finished = false
        val dispatcher =
            G7ReceiverWorkDispatcher(
                launch = queued::addLast,
                recover = { _, action ->
                    assertEquals(Intent.ACTION_BOOT_COMPLETED, action)
                    recoveryRan = true
                },
            )

        dispatcher.dispatch(context, Intent.ACTION_BOOT_COMPLETED) { finished = true }

        assertFalse(recoveryRan)
        assertFalse(finished)
        assertEquals(1, queued.size)

        queued.removeFirst().invoke()

        assertTrue(recoveryRan)
        assertTrue(finished)
    }

    @Test fun `pending broadcast is always finished when recovery fails`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queued = ArrayDeque<() -> Unit>()
        var finished = false
        val dispatcher =
            G7ReceiverWorkDispatcher(
                launch = queued::addLast,
                recover = { _, _ -> error("broken recovery") },
            )

        dispatcher.dispatch(context, Intent.ACTION_MY_PACKAGE_REPLACED) { finished = true }
        runCatching { queued.removeFirst().invoke() }

        assertTrue(finished)
    }
}
