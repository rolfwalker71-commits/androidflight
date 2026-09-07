package de.rolfwalker.flightbuddy.feature.flights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.data.prefs.PrefsStore
import de.rolfwalker.flightbuddy.core.data.prefs.UserPrefs
import de.rolfwalker.flightbuddy.core.domain.connectionBetween
import de.rolfwalker.flightbuddy.core.domain.isLiveStatus
import de.rolfwalker.flightbuddy.core.domain.isPastStatus
import de.rolfwalker.flightbuddy.core.model.AircraftPhoto
import de.rolfwalker.flightbuddy.core.model.ConnectionInfo
import de.rolfwalker.flightbuddy.core.model.FlightSearchResult
import de.rolfwalker.flightbuddy.core.model.SearchReason
import de.rolfwalker.flightbuddy.core.network.ProviderClients
import de.rolfwalker.flightbuddy.tracking.TrackerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class HomeTab { UPCOMING, LIVE, PAST }

data class HomeState(
    val flights: List<FlightEntity> = emptyList(),
    val visible: List<FlightEntity> = emptyList(),
    val unread: Int = 0,
    val tab: HomeTab = HomeTab.UPCOMING,
    val connections: Map<String, ConnectionInfo> = emptyMap(),
    val prefs: UserPrefs = UserPrefs(),
)

class HomeViewModel(
    private val repo: FlightRepository,
    prefs: PrefsStore,
) : ViewModel() {
    private val tab = MutableStateFlow(HomeTab.UPCOMING)

    val state = combine(repo.observeFlights(), repo.observeUnread(), tab, prefs.flow) { flights, unread, t, p ->
        val connections = inferConnections(flights)
        HomeState(
            flights = flights,
            visible = flights.filter {
                when (t) {
                    HomeTab.LIVE -> isLiveStatus(it.status)
                    HomeTab.PAST -> isPastStatus(it.status)
                    HomeTab.UPCOMING -> !isLiveStatus(it.status) && !isPastStatus(it.status)
                }
            }.sortedBy { it.scheduledDep },
            unread = unread,
            tab = t,
            connections = connections,
            prefs = p,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun setTab(t: HomeTab) { tab.value = t }
}

fun inferConnections(flights: List<FlightEntity>): Map<String, ConnectionInfo> {
    val upcoming = flights.filter { !isPastStatus(it.status) }.sortedBy { it.scheduledDep }
    val out = mutableMapOf<String, ConnectionInfo>()
    for (i in 0 until upcoming.lastIndex) {
        val a = upcoming[i]
        val b = upcoming[i + 1]
        if (a.toIata != null && a.toIata == b.fromIata) {
            val arr = a.actualArr ?: a.estimatedArr ?: a.scheduledArr
            val dep = b.actualDep ?: b.estimatedDep ?: b.scheduledDep
            connectionBetween(arr, dep, a.toIata, b.fromIata)?.let { out[a.id] = it }
        }
    }
    return out
}

class AddFlightViewModel(private val repo: FlightRepository) : ViewModel() {
    data class Ui(
        val query: String = "LH441",
        val date: LocalDate = LocalDate.now(),
        val loading: Boolean = false,
        val results: List<FlightSearchResult> = emptyList(),
        val reason: SearchReason? = null,
        val error: String? = null,
        val trackDaily: Boolean = false,
        val inLogbook: Boolean = true,
        val manualNumber: String = "",
        val manualFrom: String = "",
        val manualTo: String = "",
        val saved: Boolean = false,
    )
    val ui = MutableStateFlow(Ui())

    fun update(block: (Ui) -> Ui) { ui.value = block(ui.value) }

    fun search() {
        viewModelScope.launch {
            update { it.copy(loading = true, error = null, reason = null) }
            try {
                val out = repo.search(ui.value.query, ui.value.date)
                update { it.copy(loading = false, results = out.flights, reason = if (out.flights.isEmpty()) out.reason else SearchReason.OK) }
            } catch (e: Exception) {
                update { it.copy(loading = false, error = e.message, reason = SearchReason.HTTP_ERROR) }
            }
        }
    }

    fun save(result: FlightSearchResult, context: android.content.Context) {
        viewModelScope.launch {
            val s = ui.value
            val toSave = result.copy(
                flightNumber = result.flightNumber.ifBlank { s.manualNumber },
                fromIata = result.fromIata ?: s.manualFrom.ifBlank { null },
                toIata = result.toIata ?: s.manualTo.ifBlank { null },
                scheduledDep = result.scheduledDep ?: s.date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            )
            if (toSave.flightNumber.isBlank() || toSave.scheduledDep == null) {
                update { it.copy(error = "need") }
                return@launch
            }
            repo.saveSearch(toSave, s.trackDaily, s.inLogbook)
            TrackerController.sync(context)
            update { it.copy(saved = true) }
        }
    }
}

class FlightDetailViewModel(
    private val id: String,
    private val repo: FlightRepository,
    private val providers: ProviderClients,
) : ViewModel() {
    val flight = repo.observeFlight(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val photo = MutableStateFlow<AircraftPhoto?>(null)

    init {
        viewModelScope.launch {
            repo.getFlight(id)?.registration?.let { photo.value = providers.planespottersPhoto(it) }
        }
    }

    fun updateMeta(seat: String?, notes: String?, alerts: Boolean, daily: Boolean, logbook: Boolean) {
        viewModelScope.launch { repo.updateMeta(id, seat, notes, alerts, daily, logbook) }
    }

    fun delete(done: () -> Unit) {
        viewModelScope.launch {
            repo.delete(id)
            done()
        }
    }
}
