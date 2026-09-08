package de.rolfwalker.flightbuddy.feature.flights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.data.toPollInput
import de.rolfwalker.flightbuddy.core.domain.displayFlightNumber
import de.rolfwalker.flightbuddy.core.domain.flightProgress
import de.rolfwalker.flightbuddy.core.domain.interpolateAirbornePosition
import de.rolfwalker.flightbuddy.core.domain.isLiveStatus
import de.rolfwalker.flightbuddy.core.model.ConnectionInfo
import de.rolfwalker.flightbuddy.core.model.ConnectionLevel
import de.rolfwalker.flightbuddy.core.model.LatLon
import de.rolfwalker.flightbuddy.core.ui.AirlineLogo
import de.rolfwalker.flightbuddy.core.ui.RouteProgress
import de.rolfwalker.flightbuddy.core.ui.StatusBadge
import de.rolfwalker.flightbuddy.core.ui.TonalCard
import de.rolfwalker.flightbuddy.core.ui.formatDuration

@Composable
fun FlightCard(
    flight: FlightEntity,
    connection: ConnectionInfo?,
    dense: Boolean,
    showGate: Boolean,
    language: String,
    onClick: () -> Unit,
    onToggleDaily: (Boolean) -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val now = System.currentTimeMillis()
    val origin = flight.fromLat?.let { LatLon(it, flight.fromLon ?: return@let null) }
    val dest = flight.toLat?.let { LatLon(it, flight.toLon ?: return@let null) }
    val interp = interpolateAirbornePosition(
        flight.toPollInput(),
        if (flight.lastLat != null && flight.lastLon != null) LatLon(flight.lastLat, flight.lastLon) else null,
        now,
    )
    val progress = flightProgress(
        origin = origin,
        dest = dest,
        current = interp.position,
        scheduledDep = flight.scheduledDep,
        scheduledArr = flight.scheduledArr,
        estimatedDep = flight.estimatedDep,
        estimatedArr = flight.estimatedArr,
        actualDep = flight.actualDep,
        actualArr = flight.actualArr,
        now = now,
    )
    val live = isLiveStatus(flight.status)
    val eta = flight.actualArr ?: flight.estimatedArr ?: flight.scheduledArr
    val remainingMin = eta?.let { (it - now) / 60_000.0 }
    val durationMin = flight.scheduledArr?.let { (it - flight.scheduledDep) / 60_000.0 }
    val caption = if (live) {
        "${formatDuration(remainingMin, language)} · ${stringResource(R.string.flight_live)}"
    } else {
        val stand = listOfNotNull(
            flight.gate?.let { stringResource(R.string.flight_gate) + " " + it },
            flight.terminal?.let { stringResource(R.string.flight_terminal) + " " + it },
        ).joinToString(" · ").ifBlank { null }
        stand ?: formatDuration(durationMin, language)
    }
    val aircraft = listOfNotNull(flight.aircraftType, flight.registration).joinToString(" · ")
    val dateLabel = DateTimeFmt.date(flight.scheduledDep, DateTimeFmt.deviceZone())
    val pad = if (dense) 12.dp else 14.dp
    val iataSize = if (dense) 26.sp else 24.sp
    val deleteLabel = stringResource(R.string.flight_delete)

    SwipeToDeleteBox(
        onDelete = onDelete,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(deleteLabel) {
                        onDelete()
                        true
                    },
                )
            },
    ) {
        TonalCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            Column(Modifier.padding(pad)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            displayFlightNumber(flight.flightNumber),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                        if (!flight.airlineName.isNullOrBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                flight.airlineName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    StatusBadge(flight.status, flight.delayMinutes)
                    AirlineLogo(flight.airlineIata, flight.airlineName, if (dense) 40 else 36)
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        aircraft,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        dateLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Box(
                        Modifier
                            .size(28.dp)
                            .clickable { onToggleDaily(!flight.trackDaily) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Repeat,
                            contentDescription = stringResource(R.string.flight_track_daily),
                            tint = if (flight.trackDaily) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                connection?.let {
                    val label = when (it.level) {
                        ConnectionLevel.MISSED -> stringResource(R.string.trip_missed)
                        ConnectionLevel.TIGHT -> stringResource(R.string.trip_tight)
                        ConnectionLevel.OK -> stringResource(R.string.trip_ok)
                        ConnectionLevel.COMFORTABLE -> stringResource(R.string.trip_comfortable)
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }

                Spacer(Modifier.height(if (dense) 10.dp else 12.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        flight.fromIata.orEmpty().ifBlank { "—" },
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = iataSize, fontWeight = FontWeight.Bold),
                    )
                    RouteProgress(progress, Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text(
                        flight.toIata.orEmpty().ifBlank { "—" },
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = iataSize, fontWeight = FontWeight.Bold),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        flight.fromCity.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                    Text(
                        caption,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    Text(
                        flight.toCity.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }

                if (showGate && (!flight.gate.isNullOrBlank() || !flight.terminal.isNullOrBlank() || !flight.arrivalGate.isNullOrBlank() || !flight.arrivalTerminal.isNullOrBlank())) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            listOfNotNull(
                                flight.terminal?.let { stringResource(R.string.flight_terminal) + " " + it },
                                flight.gate?.let { stringResource(R.string.flight_gate) + " " + it },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            listOfNotNull(
                                flight.arrivalTerminal?.let { stringResource(R.string.flight_terminal) + " " + it },
                                flight.arrivalGate?.let { stringResource(R.string.flight_gate) + " " + it },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteBox(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentOnDelete by rememberUpdatedState(onDelete)
    val actionLabel = stringResource(R.string.flight_delete_confirm)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                currentOnDelete()
            }
            false
        },
        positionalThreshold = { distance -> distance * 0.35f },
    )
    Box(modifier.clip(MaterialTheme.shapes.large)) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            backgroundContent = {
                Row(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.error)
                        .clickable(onClick = currentOnDelete)
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onError,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        actionLabel,
                        color = MaterialTheme.colorScheme.onError,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            },
        ) {
            content()
        }
    }
}
