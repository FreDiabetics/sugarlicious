package app.aapswear.mobile

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aapswear.datasource.dexcom.ShareCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class MobileSourceSettingsSnapshot(
    val configuration: MobileSourceConfiguration = MobileSourceConfiguration(),
    val preferredDexcom: MobileGlucoseSource = MobileGlucoseSource.DEXCOM_BROADCAST,
    val share: ShareCredentials? = null,
    val nightscout: NightscoutConfiguration = NightscoutConfiguration(false, "", NightscoutAuthMode.API_SECRET),
    val shareStatus: String = "",
    val nightscoutStatus: String = "",
    val loaded: Boolean = true,
    val observationError: String = "",
)

internal interface MobileSourceSettingsStore {
    val state: StateFlow<MobileSourceSettingsSnapshot>

    fun setSource(source: MobileGlucoseSource, enabled: Boolean)

    fun setAndroidAps(enabled: Boolean)

    fun setDexcom(source: MobileGlucoseSource, enabled: Boolean)

    fun setPredictions(enabled: Boolean)

    fun setTherapy(enabled: Boolean)

    fun setNightscout(enabled: Boolean)

    fun setNightscoutTherapy(enabled: Boolean)

    fun saveShare(account: String, password: String, region: String)

    fun saveNightscout(configuration: NightscoutConfiguration, secret: String?)

    fun hasNightscoutSecret(): Boolean
}

/** Existing stores and service entry points remain authoritative. Listener lifetime follows the feature. */
internal class MobileSourceSettingsRepository(
    context: Context,
    scope: CoroutineScope,
) : MobileSourceSettingsStore {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("dashboard_ui", Context.MODE_PRIVATE)
    private val diagnostics = app.getSharedPreferences("source_diagnostics", Context.MODE_PRIVATE)
    private val nightscoutPrefs = app.getSharedPreferences("nightscout_treatment_config", Context.MODE_PRIVATE)
    override val state =
        callbackFlow {
            fun snapshot() =
                MobileSourceSettingsSnapshot(
                    configuration = MobileSourceConfigurationStore.read(app),
                    preferredDexcom = MobileSourceConfigurationStore.preferredDexcom(app),
                    share = DexcomShareCredentialsStore.read(app),
                    nightscout = NightscoutConfigurationStore.read(app),
                    shareStatus = diagnostics.getString("dexcom_share", "").orEmpty(),
                    nightscoutStatus = diagnostics.getString("nightscout_cgm", "").orEmpty(),
                )
            // Preference callbacks may arrive on Main; read encrypted stores only on this IO collector.
            val changes = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changes.trySend(Unit) }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            diagnostics.registerOnSharedPreferenceChangeListener(listener)
            nightscoutPrefs.registerOnSharedPreferenceChangeListener(listener)
            val reader = launch { for (ignored in changes) send(snapshot()) }
            changes.trySend(Unit)
            awaitClose {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
                diagnostics.unregisterOnSharedPreferenceChangeListener(listener)
                nightscoutPrefs.unregisterOnSharedPreferenceChangeListener(listener)
                changes.close()
                reader.cancel()
            }
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(MobileSourceSettingsSnapshot(loaded = false, observationError = "Datenquellen-Einstellungen konnten nicht gelesen werden"))
        }.flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.WhileSubscribed(0), MobileSourceSettingsSnapshot(loaded = false))

    override fun setSource(source: MobileGlucoseSource, enabled: Boolean) = MobileSourceConfigurationStore.setGlucoseEnabled(app, source, enabled)

    override fun setAndroidAps(enabled: Boolean) = MobileSourceConfigurationStore.setAndroidApsEnabled(app, enabled)

    override fun setDexcom(source: MobileGlucoseSource, enabled: Boolean) = MobileSourceConfigurationStore.setDexcom(app, source, enabled)

    override fun setPredictions(enabled: Boolean) = MobileSourceConfigurationStore.setPredictions(app, enabled)

    override fun setTherapy(enabled: Boolean) = MobileSourceConfigurationStore.setTherapy(app, enabled)

    override fun setNightscout(enabled: Boolean) = MobileSourceConfigurationStore.setNightscoutEnabled(app, enabled)

    override fun setNightscoutTherapy(enabled: Boolean) = NightscoutConfigurationStore.save(app, NightscoutConfigurationStore.read(app).copy(enabled = enabled), null)

    override fun saveShare(account: String, password: String, region: String) = DexcomShareCredentialsStore.save(app, account, password, region)

    override fun saveNightscout(configuration: NightscoutConfiguration, secret: String?) = NightscoutConfigurationStore.save(app, configuration, secret)

    override fun hasNightscoutSecret() = !NightscoutSecretStore.read(app).isNullOrBlank()
}

internal data class ShareSettingsDraft(
    val account: String = "",
    val password: String = "",
    val region: String = "ous",
    val editing: Boolean = true,
    val feedback: String = "",
    val saving: Boolean = false,
)

internal data class NightscoutSettingsDraft(
    val url: String = "",
    val secret: String = "",
    val authMode: NightscoutAuthMode = NightscoutAuthMode.API_SECRET,
    val editing: Boolean = true,
    val feedback: String = "",
    val saving: Boolean = false,
)

/** Drafts live only in memory; no SavedStateHandle, Bundle or logs contain access credentials. */
internal class MobileSourceSettingsViewModel(
    private val repository: MobileSourceSettingsStore,
    externalScope: CoroutineScope? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope = externalScope ?: viewModelScope
    val state = repository.state
    private val mutableShare = MutableStateFlow(ShareSettingsDraft())
    val share = mutableShare.asStateFlow()
    private val mutableNightscout = MutableStateFlow(NightscoutSettingsDraft())
    val nightscout = mutableNightscout.asStateFlow()
    private val visible = MutableStateFlow(false)
    private val commands = Mutex()
    private val mutableOperationError = MutableStateFlow("")
    val operationError = mutableOperationError.asStateFlow()

    fun setVisible(value: Boolean) {
        visible.value = value
    }

    private var shareInitialized = false
    private var nightscoutInitialized = false

    init {
        scope.launch {
            visible.collectLatest { active ->
                if (!active) return@collectLatest
                state.collect { snapshot ->
                    if (snapshot.loaded) {
                        if (!shareInitialized) {
                            shareInitialized = true
                            mutableShare.value = ShareSettingsDraft(account = snapshot.share?.account.orEmpty(), region = snapshot.share?.region ?: "ous", editing = snapshot.share == null)
                        }
                        if (!nightscoutInitialized) {
                            nightscoutInitialized = true
                            mutableNightscout.value = NightscoutSettingsDraft(url = snapshot.nightscout.baseUrl, authMode = snapshot.nightscout.authMode, editing = !snapshot.nightscout.isValid())
                        }
                    }
                }
            }
        }
    }

    private fun command(action: () -> Unit) {
        scope.launch {
            try {
                commands.withLock { withContext(io) { action() } }
                mutableOperationError.value = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                java.util.logging.Logger
                    .getLogger("MobileSourceSettings")
                    .warning(failure.javaClass.simpleName)
                mutableOperationError.value = "Einstellung konnte nicht gespeichert werden"
            }
        }
    }

    fun setSource(source: MobileGlucoseSource, enabled: Boolean) = command { repository.setSource(source, enabled) }

    fun setAndroidAps(enabled: Boolean) = command { repository.setAndroidAps(enabled) }

    fun setDexcom(source: MobileGlucoseSource, enabled: Boolean) = command { repository.setDexcom(source, enabled) }

    fun setPredictions(enabled: Boolean) = command { repository.setPredictions(enabled) }

    fun setTherapy(enabled: Boolean) = command { repository.setTherapy(enabled) }

    fun setNightscout(enabled: Boolean) = command { repository.setNightscout(enabled) }

    fun setNightscoutTherapy(enabled: Boolean) = command { repository.setNightscoutTherapy(enabled) }

    fun updateShare(account: String = share.value.account, password: String = share.value.password, region: String = share.value.region) {
        if (share.value.saving) return
        shareInitialized = true
        mutableShare.update { it.copy(account = account, password = password, region = region) }
    }

    fun editShare() {
        mutableShare.update { it.copy(editing = true) }
    }

    fun cancelShare() {
        if (share.value.saving) return
        val saved = state.value.share
        mutableShare.value = ShareSettingsDraft(account = saved?.account.orEmpty(), region = saved?.region ?: "ous", editing = false)
    }

    fun saveShare() {
        val draft = share.value
        if (draft.saving) return
        val secret =
            draft.password.ifBlank {
                state.value.share
                    ?.takeIf { it.account == draft.account.trim() }
                    ?.password
                    .orEmpty()
            }
        if (draft.account.isBlank() || secret.isBlank()) {
            mutableShare.update { it.copy(feedback = "Benutzername und Passwort fehlen") }
            return
        }
        mutableShare.update { it.copy(saving = true) }
        scope.launch {
            try {
                withContext(io) { repository.saveShare(draft.account, secret, draft.region) }
                mutableShare.update { it.copy(password = "", editing = false, feedback = "Zugang gespeichert", saving = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                java.util.logging.Logger
                    .getLogger("MobileSourceSettings")
                    .warning(failure.javaClass.simpleName)
                mutableShare.update { it.copy(feedback = "Zugang konnte nicht sicher gespeichert werden", saving = false) }
            }
        }
    }

    fun updateNightscout(url: String = nightscout.value.url, secret: String = nightscout.value.secret, authMode: NightscoutAuthMode = nightscout.value.authMode) {
        if (nightscout.value.saving) return
        nightscoutInitialized = true
        mutableNightscout.update { it.copy(url = url, secret = secret, authMode = authMode) }
    }

    fun editNightscout() {
        mutableNightscout.update { it.copy(editing = true) }
    }

    fun cancelNightscout() {
        if (nightscout.value.saving) return
        val saved = state.value.nightscout
        mutableNightscout.value = NightscoutSettingsDraft(url = saved.baseUrl, authMode = saved.authMode, editing = false)
    }

    fun saveNightscout() {
        val draft = nightscout.value
        if (draft.saving) return
        val saved = state.value.nightscout
        val candidate = saved.copy(baseUrl = draft.url, authMode = draft.authMode)
        if (!candidate.isValid()) {
            mutableNightscout.update { it.copy(feedback = "G\u00fcltige HTTPS-Adresse ohne Zugangsdaten in der URL erforderlich") }
            return
        }
        mutableNightscout.update { it.copy(saving = true) }
        scope.launch {
            try {
                withContext(io) {
                    require((draft.authMode == saved.authMode && candidate.normalizedBaseUrl == saved.normalizedBaseUrl) || draft.secret.isNotBlank() || !repository.hasNightscoutSecret())
                    repository.saveNightscout(candidate, draft.secret.takeIf(String::isNotBlank))
                }
                mutableNightscout.update { it.copy(secret = "", editing = false, feedback = "Zugang gespeichert", saving = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                java.util.logging.Logger
                    .getLogger("MobileSourceSettings")
                    .warning(failure.javaClass.simpleName)
                mutableNightscout.update { it.copy(feedback = "Zugang konnte nicht sicher gespeichert werden", saving = false) }
            }
        }
    }

    class Factory(
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            // Repository observation and operations share the lifecycle-owned feature scope.
            val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
            return MobileSourceSettingsViewModel(MobileSourceSettingsRepository(context, owner), owner).also { it.repositoryScope = owner } as T
        }
    }

    private var repositoryScope: CoroutineScope? = null

    override fun onCleared() {
        repositoryScope?.let { it.cancel() }
        super.onCleared()
    }
}
