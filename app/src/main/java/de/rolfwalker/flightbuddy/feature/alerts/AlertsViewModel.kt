package de.rolfwalker.flightbuddy.feature.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlertsViewModel(private val repo: FlightRepository) : ViewModel() {
    val alerts = repo.observeAlerts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun markRead(id: String) { viewModelScope.launch { repo.markAlertRead(id) } }
    fun markAll() { viewModelScope.launch { repo.markAllAlertsRead() } }
}
