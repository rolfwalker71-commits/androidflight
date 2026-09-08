package de.rolfwalker.flightbuddy.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.data.db.TrackedObjectEntity
import de.rolfwalker.flightbuddy.core.data.prefs.KeysStore
import de.rolfwalker.flightbuddy.core.data.prefs.PrefsStore
import de.rolfwalker.flightbuddy.core.data.prefs.UserPrefs
import de.rolfwalker.flightbuddy.core.domain.resolveTrafficAirline
import de.rolfwalker.flightbuddy.core.model.LatLon
import de.rolfwalker.flightbuddy.core.network.ProviderClients
import de.rolfwalker.flightbuddy.core.network.TrafficState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MapUi(
    val flights: List<FlightEntity> = emptyList(),
    val objects: List<TrackedObjectEntity> = emptyList(),
    val traffic: List<TrafficState> = emptyList(),
    val selectedId: String? = null,
    val follow: Boolean = false,
    val trafficOn: Boolean = false,
    val trafficZoom: Boolean = false,
    val trafficError: String? = null,
    val tracks: Map<String, List<LatLon>> = emptyMap(),
    val prefs: UserPrefs = UserPrefs(),
)

private data class ViewportBox(
    val lamin: Double,
    val lamax: Double,
    val lomin: Double,
    val lomax: Double,
)

/** Same cap as the PWA — OpenSky charges extra above this. */
private const val VIEWPORT_MAX_AREA_SQ_DEG = 400.0

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
    private val trafficZoom = MutableStateFlow(false)
    private val trafficError = MutableStateFlow<String?>(null)
    private var lastBox: ViewportBox? = null
    private var loadJob: Job? = null

    private val tracks = repo.observeAllPositions().map { rows ->
        rows.groupBy { it.flightId }.mapValues { (_, pts) -> pts.map { LatLon(it.lat, it.lon) } }
    }

    val state = combine(
        combine(repo.observeFlights(), repo.observeObjects(), selected, follow) { f, o, s, fol ->
            Quad(f, o, s, fol)
        },
        combine(trafficOn, traffic, trafficError, prefs.flow) { on, tr, err, p ->
            Quad(on, tr, err, p)
        },
        combine(trafficZoom, tracks) { zoom, trk -> zoom to trk },
    ) { a, b, extra ->
        MapUi(
            flights = a.a,
            objects = a.b,
            selectedId = a.c,
            follow = a.d,
            trafficOn = b.a,
            traffic = b.b,
            trafficError = b.c,
            trafficZoom = extra.first,
            tracks = extra.second,
            prefs = b.d,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUi())

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(90_000)
                if (trafficOn.value) lastBox?.let { requestTraffic(it, debounceMs = 0) }
            }
        }
    }

    fun select(id: String?) { selected.value = id }
    fun toggleFollow() { follow.value = !follow.value }

    fun setTraffic(on: Boolean) {
        trafficOn.value = on
        if (!on) {
            traffic.value = emptyList()
            trafficError.value = null
            trafficZoom.value = false
        } else {
            lastBox?.let { requestTraffic(it, debounceMs = 0) }
        }
    }

    fun loadTraffic(lamin: Double, lamax: Double, lomin: Double, lomax: Double) {
        val box = ViewportBox(lamin, lamax, lomin, lomax)
        lastBox = box
        if (!trafficOn.value) return
        requestTraffic(box, debounceMs = 250)
    }

    private fun requestTraffic(box: ViewportBox, debounceMs: Long) {
        if (!isValidBox(box)) return
        val lonSpan = if (box.lomax >= box.lomin) {
            box.lomax - box.lomin
        } else {
            (180.0 - box.lomin) + (box.lomax + 180.0)
        }
        val area = (box.lamax - box.lamin) * lonSpan
        if (area > VIEWPORT_MAX_AREA_SQ_DEG) {
            trafficZoom.value = true
            traffic.value = emptyList()
            trafficError.value = null
            return
        }
        trafficZoom.value = false
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val rows = providers.fetchOpenSkyBbox(
                keys.snapshot(),
                lamin = box.lamin,
                lamax = box.lamax,
                lomin = box.lomin,
                lomax = box.lomax,
            )
            if (rows != null) {
                val at = System.currentTimeMillis()
                traffic.value = rows.map { ac ->
                    val (iata, name) = resolveTrafficAirline(ac.callsign)
                    ac.copy(airlineIata = iata, airlineName = name, observedAt = at)
                }
            }
            trafficError.value = providers.lastOpenSkyError
        }
    }

    private fun isValidBox(box: ViewportBox): Boolean {
        if (!listOf(box.lamin, box.lamax, box.lomin, box.lomax).all { it.isFinite() }) return false
        if (box.lamin < -91 || box.lamax > 91 || box.lamin >= box.lamax) return false
        if (box.lomin == box.lomax) return false
        return true
    }

    fun track(icao24: String?, callsign: String) {
        viewModelScope.launch { repo.trackObject(icao24, callsign, null) }
    }

    fun untrack(id: String) {
        viewModelScope.launch { repo.untrackObject(id) }
    }
}
