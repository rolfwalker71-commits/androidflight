package de.rolfwalker.flightbuddy.feature.airport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.rolfwalker.flightbuddy.core.data.prefs.KeysStore
import de.rolfwalker.flightbuddy.core.model.FlightSearchResult
import de.rolfwalker.flightbuddy.core.model.SearchReason
import de.rolfwalker.flightbuddy.core.network.ProviderClients
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class AirportBoardState(
    val code: String,
    val name: String? = null,
    val arrivals: Boolean,
    val day: LocalDate,
    val flights: List<FlightSearchResult> = emptyList(),
    val loading: Boolean = true,
    val reason: SearchReason? = null,
    val source: String? = null,
    val httpStatus: Int? = null,
    val remaining: Int? = null,
)

class AirportBoardViewModel(
    code: String,
    arrivals: Boolean,
    private val providers: ProviderClients,
    private val keys: KeysStore,
) : ViewModel() {
    private val iata = code.trim().uppercase()
    private val _state = MutableStateFlow(
        AirportBoardState(code = iata, arrivals = arrivals, day = LocalDate.now()),
    )
    val state: StateFlow<AirportBoardState> = _state

    init {
        viewModelScope.launch {
            val info = providers.fetchFr24Airport(keys.snapshot(), iata)
            val label = listOfNotNull(info?.name, info?.city).firstOrNull()
            if (!label.isNullOrBlank()) {
                _state.value = _state.value.copy(name = label)
            }
        }
        load()
    }

    fun reload() = load()

    fun setArrivals(arrivals: Boolean) {
        if (_state.value.arrivals == arrivals) return
        _state.value = _state.value.copy(arrivals = arrivals)
        load()
    }

    fun setDay(day: LocalDate) {
        if (_state.value.day == day) return
        _state.value = _state.value.copy(day = day)
        load()
    }

    private fun load() {
        val snap = _state.value
        _state.value = snap.copy(loading = true, reason = null)
        viewModelScope.launch {
            val lookup = providers.searchAirportBoard(
                keys.snapshot(),
                snap.code,
                snap.day,
                snap.arrivals,
            )
            val sorted = lookup.flights.sortedBy { row ->
                if (snap.arrivals) row.scheduledArr ?: row.estimatedArr ?: Long.MAX_VALUE
                else row.scheduledDep ?: row.estimatedDep ?: Long.MAX_VALUE
            }
            _state.value = _state.value.copy(
                flights = sorted,
                loading = false,
                reason = lookup.reason,
                source = sorted.map { it.source }.distinct().sorted().joinToString("+").ifBlank { null },
                httpStatus = lookup.httpStatus,
                remaining = providers.lastAeroRemaining,
            )
        }
    }
}
