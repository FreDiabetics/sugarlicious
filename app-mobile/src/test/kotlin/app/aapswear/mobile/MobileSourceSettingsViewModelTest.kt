package app.aapswear.mobile

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MobileSourceSettingsViewModelTest {
    private class Store : MobileSourceSettingsStore {
        override val state = MutableStateFlow(MobileSourceSettingsSnapshot())
        var savedAccount = ""
        var savedPassword = ""
        var nightscoutSecret: String? = null

        override fun setSource(source: MobileGlucoseSource, enabled: Boolean) = Unit

        override fun setAndroidAps(enabled: Boolean) = Unit

        override fun setDexcom(source: MobileGlucoseSource, enabled: Boolean) = Unit

        override fun setPredictions(enabled: Boolean) = Unit

        override fun setTherapy(enabled: Boolean) = Unit

        override fun setNightscout(enabled: Boolean) = Unit

        override fun setNightscoutTherapy(enabled: Boolean) = Unit

        override fun saveShare(account: String, password: String, region: String) {
            savedAccount = account
            savedPassword = password
        }

        override fun saveNightscout(configuration: NightscoutConfiguration, secret: String?) {
            nightscoutSecret = secret
        }

        override fun hasNightscoutSecret() = false
    }

    @Test fun `credential validation refuses incomplete drafts and successful save clears password`() =
        runTest {
            val store = Store()
            val model = MobileSourceSettingsViewModel(store, backgroundScope, kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
            model.saveShare()
            runCurrent()
            assertEquals("", store.savedAccount)
            assertTrue(model.share.value.editing)
            model.updateShare(account = "alice", password = "private")
            model.saveShare()
            runCurrent()
            assertEquals("alice", store.savedAccount)
            assertEquals("private", store.savedPassword)
            assertEquals("", model.share.value.password)
            assertFalse(model.share.value.editing)
        }

    @Test fun `cancel clears in memory secrets without writing repository`() =
        runTest {
            val store = Store()
            val model = MobileSourceSettingsViewModel(store, backgroundScope, kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
            model.updateShare(password = "private")
            model.cancelShare()
            model.updateNightscout(secret = "private")
            model.cancelNightscout()
            assertEquals("", model.share.value.password)
            assertEquals("", model.nightscout.value.secret)
            assertEquals("", store.savedPassword)
            assertNull(store.nightscoutSecret)
        }

    @Test fun `invalid nightscout URL never reaches persistence`() =
        runTest {
            val store = Store()
            val model = MobileSourceSettingsViewModel(store, backgroundScope, kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
            model.updateNightscout(url = "http://unsafe", secret = "private")
            model.saveNightscout()
            runCurrent()
            assertNull(store.nightscoutSecret)
            assertTrue(model.nightscout.value.editing)
            assertTrue(
                model.nightscout.value.feedback
                    .contains("HTTPS"),
            )
        }

    @Test fun `source observation stops when the settings panel is not visible`() =
        runTest {
            val store = Store()
            val model = MobileSourceSettingsViewModel(store, backgroundScope, kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
            runCurrent()
            assertEquals(0, store.state.subscriptionCount.value)
            model.setVisible(true)
            runCurrent()
            assertEquals(1, store.state.subscriptionCount.value)
            model.updateShare(password = "private")
            model.setVisible(false)
            runCurrent()
            assertEquals(0, store.state.subscriptionCount.value)
            model.setVisible(true)
            runCurrent()
            assertEquals("private", model.share.value.password)
        }
}
