package de.rolfwalker.flightbuddy.feature.logbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.domain.LogbookStats
import de.rolfwalker.flightbuddy.core.domain.computeLogbookStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class LogbookViewModel(repo: FlightRepository) : ViewModel() {
    private val yearOnly = MutableStateFlow<Int?>(null)

    val state = combine(repo.observeFlights(), yearOnly) { flights, year ->
        year to computeLogbookStats(flights, year)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to LogbookStats())

    fun setYear(year: Int?) { yearOnly.value = year }
}
