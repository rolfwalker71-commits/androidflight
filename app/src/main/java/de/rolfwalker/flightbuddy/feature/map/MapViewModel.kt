package de.rolfwalker.flightbuddy.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.data.db.TrackedObjectEntity
import de.rolfwalker.flightbuddy.core.data.prefs.KeysStore
import de.rolfwalker.flightbuddy.core.data.prefs.PrefsStore
import de.rolfwalker.flightbuddy.core.data.prefs.UserPrefs
import de.rolfwalker.flightbuddy.core.network.ProviderClients
import de.rolfwalker.flightbuddy.core.network.TrafficState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MapUi(
    val flights: List<FlightEntity> = emptyList(),
    val objects: List<TrackedObjectEntity> = emptyList(),
    val traffic: List<TrafficState> = emptyList(),
    val selectedId: String? = null,
    val follow: Boolean = false,
    val trafficOn: Boolean = false,
    val trafficError: String? = null,
    val prefs: UserPrefs = UserPrefs(),
)

class MapViewModel(
    private val repo: FlightRepository,
    private val providers: ProviderClients,
    prefs: PrefsStore,
) : ViewModel() {
    private val keys: KeysStore = org.koin.java.KoinJavaComponent.get(KeysStore::class.java)
    private val selected = MutableStateFlow<String?>(null)
    private val follow = MutableStateFlow(false)
    private val trafficOn = MutableStateFlow(false)
    private val traffic = MutableStateFlow<List<TrafficState>>(emptyList())
    private val trafficError = MutableStateFlow<String?>(null)

    val state = combine(
        combine(repo.observeFlights(), repo.observeObjects(), selected, follow) { f, o, s, fol ->
            Quad(f, o, s, fol)
        },
        combine(trafficOn, traffic, trafficError, prefs.flow) { on, tr, err, p ->
            Quad(on, tr, err, p)
        },
    ) { a, b ->
        MapUi(
            flights = a.a,
            objects = a.b,
            selectedId = a.c,
            follow = a.d,
            trafficOn = b.a,
            traffic = b.b,
            trafficError = b.c,
            prefs = b.d,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUi())

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    fun select(id: String?) { selected.value = id }
    fun toggleFollow() { follow.value = !follow.value }
    fun setTraffic(on: Boolean) { trafficOn.value = on }

    fun loadTraffic(lamin: Double, lamax: Double, lomin: Double, lomax: Double) {
        if (!trafficOn.value) return
        viewModelScope.launch {
            val rows = providers.fetchOpenSky(keys.snapshot(), lamin = lamin, lamax = lamax, lomin = lomin, lomax = lomax)
            traffic.value = rows
            trafficError.value = providers.lastOpenSkyError
        }
    }

    fun track(icao24: String?, callsign: String) {
        viewModelScope.launch { repo.trackObject(icao24, callsign, null) }
    }

    fun untrack(id: String) {
        viewModelScope.launch { repo.untrackObject(id) }
    }
}
