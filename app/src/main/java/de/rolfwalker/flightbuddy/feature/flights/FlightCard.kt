package de.rolfwalker.flightbuddy.feature.flights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.data.toPollInput
import de.rolfwalker.flightbuddy.core.domain.displayFlightNumber
import de.rolfwalker.flightbuddy.core.domain.flightProgress
import de.rolfwalker.flightbuddy.core.domain.interpolateAirbornePosition
import de.rolfwalker.flightbuddy.core.model.ConnectionInfo
import de.rolfwalker.flightbuddy.core.model.ConnectionLevel
import de.rolfwalker.flightbuddy.core.model.LatLon
import de.rolfwalker.flightbuddy.core.ui.AirlineLogo
import de.rolfwalker.flightbuddy.core.ui.StatusBadge
import de.rolfwalker.flightbuddy.core.ui.TonalCard

@Composable
fun FlightCard(
    flight: FlightEntity,
    connection: ConnectionInfo?,
    dense: Boolean,
    showGate: Boolean,
    onClick: () -> Unit,
) {
    val progress = flightProgress(
        origin = flight.fromLat?.let { LatLon(it, flight.fromLon ?: return@let null) },
        dest = flight.toLat?.let { LatLon(it, flight.toLon ?: return@let null) },
        current = interpolateAirbornePosition(
            flight.toPollInput(),
            if (flight.lastLat != null && flight.lastLon != null) LatLon(flight.lastLat, flight.lastLon) else null,
            System.currentTimeMillis(),
        ).position,
        scheduledDep = flight.scheduledDep,
        scheduledArr = flight.scheduledArr,
        estimatedDep = flight.estimatedDep,
        estimatedArr = flight.estimatedArr,
        actualDep = flight.actualDep,
        actualArr = flight.actualArr,
    )
    TonalCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(if (dense) 14.dp else 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AirlineLogo(flight.airlineIata, flight.airlineName)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(displayFlightNumber(flight.flightNumber), style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(flight.airlineName, DateTimeFmt.date(flight.scheduledDep, DateTimeFmt.airportZone(flight.fromTimezone))).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Clip,
                    )
                }
                StatusBadge(flight.status, flight.delayMinutes)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("${flight.fromIata.orEmpty()}–${flight.toIata.orEmpty()}", style = MaterialTheme.typography.titleLarge)
                    Text("${flight.fromCity.orEmpty()} → ${flight.toCity.orEmpty()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(DateTimeFmt.dateTime(flight.estimatedDep ?: flight.scheduledDep, DateTimeFmt.airportZone(flight.fromTimezone)), style = MaterialTheme.typography.bodyMedium)
                Text(DateTimeFmt.dateTime(flight.estimatedArr ?: flight.scheduledArr, DateTimeFmt.airportZone(flight.toTimezone)), style = MaterialTheme.typography.bodyMedium)
            }
            if (showGate && (!flight.gate.isNullOrBlank() || !flight.terminal.isNullOrBlank())) {
                Text(
                    listOfNotNull(flight.terminal?.let { stringResource(R.string.flight_terminal) + " " + it }, flight.gate?.let { stringResource(R.string.flight_gate) + " " + it }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (flight.trackDaily) Text(stringResource(R.string.flight_daily), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                connection?.let {
                    val label = when (it.level) {
                        ConnectionLevel.MISSED -> stringResource(R.string.trip_missed)
                        ConnectionLevel.TIGHT -> stringResource(R.string.trip_tight)
                        ConnectionLevel.OK -> stringResource(R.string.trip_ok)
                        ConnectionLevel.COMFORTABLE -> stringResource(R.string.trip_comfortable)
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
