package de.rolfwalker.flightbuddy.feature.logbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.domain.haversineMiles
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.model.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId

data class LogbookStats(
    val flights: Int = 0,
    val minutes: Int = 0,
    val miles: Double = 0.0,
    val countries: Int = 0,
    val topAirports: List<Pair<String, Int>> = emptyList(),
    val topAirlines: List<Pair<String, Int>> = emptyList(),
)

class LogbookViewModel(repo: FlightRepository) : ViewModel() {
    private val yearOnly = MutableStateFlow<Int?>(null)

    val state = combine(repo.observeFlights(), yearOnly) { flights, year ->
        year to compute(flights.filter { it.inLogbook }, year)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to LogbookStats())

    fun setYear(year: Int?) { yearOnly.value = year }

    private fun compute(rows: List<FlightEntity>, year: Int?): LogbookStats {
        val eligible = rows.filter {
            it.status == FlightStatus.LANDED || it.status == FlightStatus.EN_ROUTE || it.status == FlightStatus.DEPARTED
        }.filter { row ->
            if (year == null) true
            else Instant.ofEpochMilli(row.scheduledDep).atZone(ZoneId.systemDefault()).year == year
        }
        val airports = mutableMapOf<String, Int>()
        val airlines = mutableMapOf<String, Int>()
        val countries = mutableSetOf<String>()
        var miles = 0.0
        var minutes = 0
        for (f in eligible) {
            f.fromIata?.let { airports[it] = (airports[it] ?: 0) + 1 }
            f.toIata?.let { airports[it] = (airports[it] ?: 0) + 1 }
            f.fromCountry?.let { countries += it }
            f.toCountry?.let { countries += it }
            f.airlineName?.let { airlines[it] = (airlines[it] ?: 0) + 1 }
            val o = if (f.fromLat != null && f.fromLon != null) LatLon(f.fromLat, f.fromLon) else null
            val d = if (f.toLat != null && f.toLon != null) LatLon(f.toLat, f.toLon) else null
            if (o != null && d != null) miles += haversineMiles(o, d)
            val start = f.actualDep ?: f.estimatedDep ?: f.scheduledDep
            val end = f.actualArr ?: f.estimatedArr ?: f.scheduledArr
            if (end != null && end > start) minutes += ((end - start) / 60_000L).toInt()
        }
        return LogbookStats(
            flights = eligible.size,
            minutes = minutes,
            miles = miles,
            countries = countries.size,
            topAirports = airports.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            topAirlines = airlines.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
        )
    }
}
