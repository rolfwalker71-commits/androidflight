package de.rolfwalker.flightbuddy.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.db.ApiLogDao
import de.rolfwalker.flightbuddy.core.data.prefs.ApiKeys
import de.rolfwalker.flightbuddy.core.data.prefs.KeysStore
import de.rolfwalker.flightbuddy.core.data.prefs.redactProviderError
import de.rolfwalker.flightbuddy.core.data.prefs.PrefsStore
import de.rolfwalker.flightbuddy.core.applyAppLanguage
import de.rolfwalker.flightbuddy.core.data.prefs.UserPrefs
import de.rolfwalker.flightbuddy.core.model.ProviderStatus
import de.rolfwalker.flightbuddy.core.network.ProviderClients
import de.rolfwalker.flightbuddy.core.network.hasAeroDataBox
import de.rolfwalker.flightbuddy.tracking.LiveFlightNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUi(
    val prefs: UserPrefs = UserPrefs(),
    val keys: ApiKeys = ApiKeys(),
    val aero: ProviderStatus = ProviderStatus(false),
    val opensky: ProviderStatus = ProviderStatus(false),
    val fr24: ProviderStatus = ProviderStatus(false),
)

class SettingsViewModel(
    private val prefsStore: PrefsStore,
    private val keysStore: KeysStore,
    private val providers: ProviderClients,
    private val logs: ApiLogDao,
) : ViewModel() {
    private fun liveNotif(): LiveFlightNotification =
        org.koin.java.KoinJavaComponent.get(LiveFlightNotification::class.java)

    val objects = org.koin.java.KoinJavaComponent.get<FlightRepository>(FlightRepository::class.java)
        .observeObjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val refresh = MutableStateFlow(0)

    val state = combine(prefsStore.flow, keysStore.keys, refresh) { prefs, keys, _ ->
        val aeroLog = logs.last("aerodatabox")
        val osLog = logs.last("opensky")
        val frLog = logs.last("fr24")
        SettingsUi(
            prefs = prefs,
            keys = keys,
            aero = ProviderStatus(
                configured = keys.hasAeroDataBox(),
                lastError = redactProviderError(providers.lastAeroError ?: aeroLog?.error),
                lastCallAt = aeroLog?.at,
                lastStatusCode = aeroLog?.statusCode,
                remaining = providers.lastAeroRemaining ?: aeroLog?.remaining,
            ),
            opensky = ProviderStatus(
                configured = keys.openSkyUsername.isNotBlank(),
                lastError = redactProviderError(providers.lastOpenSkyError ?: osLog?.error),
                lastCallAt = osLog?.at,
                lastStatusCode = osLog?.statusCode,
                remaining = providers.lastOpenSkyRemaining ?: osLog?.remaining,
            ),
            fr24 = ProviderStatus(
                configured = keys.fr24Token.isNotBlank(),
                enabled = keys.fr24Enabled,
                lastError = redactProviderError(providers.lastFr24Error ?: frLog?.error),
                lastCallAt = frLog?.at,
                lastStatusCode = frLog?.statusCode,
                remaining = providers.lastFr24Remaining ?: frLog?.remaining,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    fun updatePrefs(block: (UserPrefs) -> UserPrefs) {
        viewModelScope.launch { prefsStore.update(block) }
    }

    fun setLiveNotification(enabled: Boolean) {
        viewModelScope.launch {
            prefsStore.update { it.copy(liveNotification = enabled) }
            runCatching { liveNotif().onToggleChanged() }
        }
    }

    fun setLanguage(tag: String) {
        viewModelScope.launch {
            prefsStore.update { it.copy(language = tag) }
            applyAppLanguage(tag)
        }
    }

    fun updateKeys(block: (ApiKeys) -> ApiKeys) {
        viewModelScope.launch {
            keysStore.save(block(keysStore.snapshot()))
            refresh.value++
        }
    }

    fun untrack(id: String) {
        viewModelScope.launch {
            org.koin.java.KoinJavaComponent.get<FlightRepository>(FlightRepository::class.java).untrackObject(id)
        }
    }
}
