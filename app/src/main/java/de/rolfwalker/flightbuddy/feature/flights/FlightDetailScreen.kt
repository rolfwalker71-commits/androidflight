package de.rolfwalker.flightbuddy.feature.flights

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.data.toPollInput
import de.rolfwalker.flightbuddy.core.domain.LIVE_FIX_STALE_MS
import de.rolfwalker.flightbuddy.core.domain.displayFlightNumber
import de.rolfwalker.flightbuddy.core.domain.flightProgress
import de.rolfwalker.flightbuddy.core.domain.haversineNm
import de.rolfwalker.flightbuddy.core.domain.interpolateAirbornePosition
import de.rolfwalker.flightbuddy.core.domain.isEmergencySquawk
import de.rolfwalker.flightbuddy.core.domain.isLiveStatus
import de.rolfwalker.flightbuddy.core.model.LatLon
import de.rolfwalker.flightbuddy.core.model.Units
import de.rolfwalker.flightbuddy.core.ui.AirlineLogo
import de.rolfwalker.flightbuddy.core.ui.AirportClockPair
import de.rolfwalker.flightbuddy.core.ui.ClockVariant
import de.rolfwalker.flightbuddy.core.ui.RouteProgress
import de.rolfwalker.flightbuddy.core.ui.StatusBadge
import de.rolfwalker.flightbuddy.core.ui.TelemetryKpiGrid
import de.rolfwalker.flightbuddy.core.ui.TonalCard
import de.rolfwalker.flightbuddy.core.ui.flightTelemetryKpis
import de.rolfwalker.flightbuddy.feature.map.FlightMapView

@Composable
fun FlightDetailScreen(tablet: Boolean, vm: FlightDetailViewModel, onBack: () -> Unit) {
    val flight by vm.flight.collectAsState()
    val photo by vm.photo.collectAsState()
    val prefs by vm.prefs.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val row = flight ?: return
    var seat by remember(row.id, row.seat) { mutableStateOf(row.seat.orEmpty()) }
    var notes by remember(row.id, row.notes) { mutableStateOf(row.notes.orEmpty()) }
    val origin = row.fromLat?.let { LatLon(it, row.fromLon ?: return@let null) }
    val dest = row.toLat?.let { LatLon(it, row.toLon ?: return@let null) }
    val interp = interpolateAirbornePosition(
        row.toPollInput(),
        if (row.lastLat != null && row.lastLon != null) LatLon(row.lastLat, row.lastLon) else null,
        System.currentTimeMillis(),
    )
    val progress = flightProgress(origin, dest, interp.position, row.scheduledDep, row.scheduledArr, row.estimatedDep, row.estimatedArr, row.actualDep, row.actualArr)
    val remaining = if (interp.position != null && dest != null) haversineNm(interp.position, dest) else null
    val language = prefs.language
    val units = prefs.units
    val aircraft = listOfNotNull(row.aircraftType, row.registration).joinToString(" · ")
    val live = isLiveStatus(row.status)
    val gap = if (tablet) 10.dp else 8.dp

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
            Text(
                displayFlightNumber(row.flightNumber),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Box(Modifier.size(44.dp))
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            TonalCard(Modifier.fillMaxWidth().height(if (tablet) 280.dp else 208.dp)) {
                FlightMapView(listOf(row), followId = row.id, traffic = emptyList(), style = prefs.mapStyle, modifier = Modifier.fillMaxSize())
            }
            TonalCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(if (tablet) 16.dp else 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.airlineName ?: row.airlineIata ?: "—",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            )
                            if (aircraft.isNotBlank()) {
                                Text(aircraft, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (live) {
                                Text(
                                    stringResource(R.string.aircraft_inbound),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (tablet && photo != null) {
                            AsyncImage(
                                photo!!.url,
                                contentDescription = stringResource(R.string.aircraft_photo_alt, row.registration.orEmpty()),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.weight(1f).height(96.dp).padding(horizontal = 8.dp).clip(MaterialTheme.shapes.medium),
                            )
                        }
                        AirlineLogo(row.airlineIata, row.airlineName, if (tablet) 65 else 43)
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.fromIata.orEmpty().ifBlank { "—" },
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = if (tablet) 36.sp else 30.sp),
                            )
                            Text(row.fromCity.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            standLine(row.gate, row.terminal)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text(
                                row.toIata.orEmpty().ifBlank { "—" },
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = if (tablet) 36.sp else 30.sp),
                            )
                            Text(row.toCity.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            standLine(row.arrivalGate, row.arrivalTerminal)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    RouteProgress(progress)
                    Text(
                        buildString {
                            append(stringResource(R.string.flight_progress, (progress * 100).toInt()))
                            if (interp.estimated) {
                                append(" · ")
                                append(stringResource(R.string.flight_estimate))
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(10.dp))
                    AirportClockPair(
                        depScheduled = row.scheduledDep,
                        depEstimated = row.estimatedDep,
                        depActual = row.actualDep,
                        arrScheduled = row.scheduledArr,
                        arrEstimated = row.estimatedArr,
                        arrActual = row.actualArr,
                        status = row.status,
                        language = language,
                        variant = ClockVariant.DETAIL,
                    )

                    Spacer(Modifier.height(8.dp))
                    TelemetryKpiGrid(
                        tiles = flightTelemetryKpis(
                            altitudeFt = row.lastAltitudeFt,
                            velocityKts = row.lastVelocityKts,
                            heading = row.lastHeading,
                            remainingNm = remaining,
                            language = language,
                            units = units,
                        ),
                        columns = if (tablet) 4 else 2,
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusBadge(row.status, row.delayMinutes)
                            if (isEmergencySquawk(row.lastSquawk)) {
                                Text(stringResource(R.string.flight_squawk, row.lastSquawk!!), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Text(
                            stringResource(R.string.flight_seat, seat.ifBlank { "—" }),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            TonalCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        seat,
                        { seat = it; vm.updateMeta(it, notes, row.pushAlerts, row.trackDaily, row.inLogbook) },
                        label = { Text(stringResource(R.string.flight_seat_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        notes,
                        { notes = it; vm.updateMeta(seat, it, row.pushAlerts, row.trackDaily, row.inLogbook) },
                        label = { Text(stringResource(R.string.flight_notes_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 3,
                    )
                    ToggleRow(stringResource(R.string.flight_push_alerts), row.pushAlerts) { vm.updateMeta(seat, notes, it, row.trackDaily, row.inLogbook) }
                    ToggleRow(stringResource(R.string.flight_track_daily), row.trackDaily) { vm.updateMeta(seat, notes, row.pushAlerts, it, row.inLogbook) }
                    ToggleRow(stringResource(R.string.flight_in_logbook), row.inLogbook) { vm.updateMeta(seat, notes, row.pushAlerts, row.trackDaily, it) }
                }
            }
            Text(adsbCaption(row, interp.estimated), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.flight_delete), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.flight_delete_title)) },
            text = { Text(stringResource(R.string.flight_delete_body, displayFlightNumber(row.flightNumber))) },
            confirmButton = { TextButton(onClick = { vm.delete(onBack) }) { Text(stringResource(R.string.flight_delete_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.flight_delete_cancel)) } },
        )
    }
}

@Composable
private fun standLine(gate: String?, terminal: String?): String? {
    val parts = listOfNotNull(
        gate?.let { stringResource(R.string.flight_gate) + " " + it },
        terminal?.let { stringResource(R.string.flight_terminal) + " " + it },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

@Composable
private fun adsbCaption(row: FlightEntity, estimated: Boolean): String {
    val last = row.lastPositionAt
    return when {
        last != null && System.currentTimeMillis() - last <= LIVE_FIX_STALE_MS -> stringResource(R.string.flight_adsb_live, "90s")
        estimated && last != null -> stringResource(R.string.flight_adsb_estimated_last, DateTimeFmt.dateTime(last))
        estimated -> stringResource(R.string.flight_adsb_estimated)
        last != null -> stringResource(R.string.flight_adsb_last, DateTimeFmt.dateTime(last))
        else -> stringResource(R.string.flight_adsb_none)
    }
}
