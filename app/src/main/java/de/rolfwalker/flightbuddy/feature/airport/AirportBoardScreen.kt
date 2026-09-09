package de.rolfwalker.flightbuddy.feature.airport

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.domain.displayFlightNumber
import de.rolfwalker.flightbuddy.core.model.FlightSearchResult
import de.rolfwalker.flightbuddy.core.model.SearchReason
import de.rolfwalker.flightbuddy.core.ui.AirlineLogo
import de.rolfwalker.flightbuddy.core.ui.StatusBadge
import de.rolfwalker.flightbuddy.core.ui.TonalCard
import java.time.LocalDate

@Composable
fun AirportBoardScreen(vm: AirportBoardViewModel, onBack: () -> Unit) {
    val ui by vm.state.collectAsState()
    val today = LocalDate.now()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.flight_delete_cancel))
            }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    stringResource(R.string.airport_board_title, ui.code),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                ui.name?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !ui.arrivals,
                onClick = { vm.setArrivals(false) },
                label = { Text(stringResource(R.string.airport_departures)) },
                shape = CircleShape,
            )
            FilterChip(
                selected = ui.arrivals,
                onClick = { vm.setArrivals(true) },
                label = { Text(stringResource(R.string.airport_arrivals)) },
                shape = CircleShape,
            )
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = ui.day == today.minusDays(1),
                onClick = { vm.setDay(today.minusDays(1)) },
                label = { Text(stringResource(R.string.airport_yesterday)) },
                shape = CircleShape,
            )
            FilterChip(
                selected = ui.day == today,
                onClick = { vm.setDay(today) },
                label = { Text(stringResource(R.string.airport_today)) },
                shape = CircleShape,
            )
            FilterChip(
                selected = ui.day == today.plusDays(1),
                onClick = { vm.setDay(today.plusDays(1)) },
                label = { Text(stringResource(R.string.airport_tomorrow)) },
                shape = CircleShape,
            )
        }
        when {
            ui.loading -> {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
            }
            ui.flights.isEmpty() -> {
                TonalCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(boardMessage(ui.reason)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        ui.httpStatus?.let {
                            Text(
                                stringResource(R.string.airport_aero_http, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ui.remaining?.let {
                            Text(
                                stringResource(R.string.settings_remaining, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (ui.reason != null && ui.reason != SearchReason.EMPTY && ui.reason != SearchReason.OK) {
                            TextButton(onClick = { vm.reload() }) {
                                Text(stringResource(R.string.flight_refresh))
                            }
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ui.source?.let { src ->
                        item {
                            Text(
                                stringResource(boardSource(src)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(ui.flights, key = { boardKey(it, ui.arrivals) }) { row ->
                        BoardRow(row, ui.arrivals)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardRow(row: FlightSearchResult, arrivals: Boolean) {
    val zone = DateTimeFmt.zoneOrDevice(if (arrivals) row.toTimezone else row.fromTimezone)
    val scheduled = if (arrivals) row.scheduledArr else row.scheduledDep
    val shown = if (arrivals) {
        row.actualArr ?: row.estimatedArr ?: scheduled
    } else {
        row.actualDep ?: row.estimatedDep ?: scheduled
    }
    val other = if (arrivals) row.fromIata else row.toIata
    val city = if (arrivals) row.fromCity else row.toCity
    val stand = if (arrivals) {
        listOfNotNull(
            row.arrivalGate?.let { stringResource(R.string.airport_gate, it) },
            row.arrivalTerminal?.let { stringResource(R.string.flight_terminal) + " " + it },
            row.baggageBelt?.let { stringResource(R.string.airport_belt, it) },
        )
    } else {
        listOfNotNull(
            row.gate?.let { stringResource(R.string.airport_gate, it) },
            row.terminal?.let { stringResource(R.string.flight_terminal) + " " + it },
        )
    }
    TonalCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AirlineLogo(
                de.rolfwalker.flightbuddy.core.domain.airlineCodeForLogo(row.airlineIata, row.flightNumber),
                row.airlineName,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    displayFlightNumber(row.flightNumber.ifBlank { "—" }),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    listOfNotNull(other, city).joinToString(" · ").ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (stand.isNotEmpty()) {
                    Text(
                        stand.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    DateTimeFmt.time(shown, zone),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                if (scheduled != null && shown != null && scheduled != shown) {
                    Text(
                        DateTimeFmt.time(scheduled, zone),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(row.status, if (arrivals) row.arrivalDelayMinutes ?: row.delayMinutes else row.delayMinutes)
            }
        }
    }
}

private fun boardKey(row: FlightSearchResult, arrivals: Boolean): String {
    val at = if (arrivals) row.scheduledArr else row.scheduledDep
    return listOf(row.flightNumber, row.fromIata, row.toIata, at?.toString().orEmpty()).joinToString("|")
}

private fun boardMessage(reason: SearchReason?): Int = when (reason) {
    SearchReason.UNCONFIGURED -> R.string.airport_need_providers
    SearchReason.RATE_LIMITED -> R.string.flight_rate_limited
    SearchReason.MONTHLY_QUOTA -> R.string.flight_monthly_quota
    SearchReason.NOT_SUBSCRIBED -> R.string.flight_not_subscribed
    SearchReason.INVALID_API_KEY -> R.string.flight_invalid_api_key
    SearchReason.NETWORK_ERROR -> R.string.flight_network_error
    SearchReason.HTTP_ERROR -> R.string.flight_api_error
    SearchReason.TIMEOUT -> R.string.flight_timeout
    SearchReason.UNKNOWN_HOST -> R.string.flight_unknown_host
    SearchReason.SSL_ERROR -> R.string.flight_ssl_error
    SearchReason.BAD_URL -> R.string.flight_bad_url
    else -> R.string.airport_empty
}

private fun boardSource(source: String): Int = when {
    source.contains("fr24") && source.contains("opensky") -> R.string.airport_source_mixed
    source.contains("opensky") -> R.string.airport_source_opensky
    else -> R.string.airport_live_fr24
}
