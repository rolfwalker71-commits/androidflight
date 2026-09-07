package de.rolfwalker.flightbuddy.feature.flights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import de.rolfwalker.flightbuddy.core.model.LatLon
import de.rolfwalker.flightbuddy.core.ui.AirlineLogo
import de.rolfwalker.flightbuddy.core.ui.StatusBadge
import de.rolfwalker.flightbuddy.core.ui.TonalCard
import de.rolfwalker.flightbuddy.feature.map.FlightMapView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightDetailScreen(tablet: Boolean, vm: FlightDetailViewModel, onBack: () -> Unit) {
    val flight by vm.flight.collectAsState()
    val photo by vm.photo.collectAsState()
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

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(displayFlightNumber(row.flightNumber)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.flight_delete_cancel)) }
            },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TonalCard(Modifier.fillMaxWidth().height(if (tablet) 280.dp else 220.dp)) {
                FlightMapView(listOf(row), followId = row.id, traffic = emptyList(), style = de.rolfwalker.flightbuddy.core.model.MapStyleId.DARK, modifier = Modifier.fillMaxSize())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AirlineLogo(row.airlineIata, row.airlineName, 56)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(row.airlineName.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Text("${row.fromIata.orEmpty()}–${row.toIata.orEmpty()} · ${row.fromCity.orEmpty()} → ${row.toCity.orEmpty()}")
                }
                StatusBadge(row.status, row.delayMinutes)
            }
            if (tablet && photo != null) {
                AsyncImage(photo!!.url, contentDescription = stringResource(R.string.aircraft_photo_alt, row.registration.orEmpty()), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(180.dp))
                Text(stringResource(R.string.aircraft_photo_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.flight_progress, (progress * 100).toInt()))
            ClockRow(row)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric(stringResource(R.string.flight_altitude), row.lastAltitudeFt?.let { "${it.toInt()} ft" } ?: "—")
                Metric(stringResource(R.string.flight_speed), row.lastVelocityKts?.let { "${it.toInt()} kt" } ?: "—")
                Metric(stringResource(R.string.flight_heading), row.lastHeading?.let { "${it.toInt()}°" } ?: "—")
                Metric(stringResource(R.string.flight_remaining), remaining?.let { "${it.toInt()} nm" } ?: "—")
            }
            if (isEmergencySquawk(row.lastSquawk)) {
                Text(stringResource(R.string.flight_squawk, row.lastSquawk!!), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
            }
            if (!row.seat.isNullOrBlank()) Text(stringResource(R.string.flight_seat, row.seat!!))
            Text(adsbCaption(row, interp.estimated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(seat, { seat = it; vm.updateMeta(it, notes, row.pushAlerts, row.trackDaily, row.inLogbook) }, label = { Text(stringResource(R.string.flight_seat_label)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it; vm.updateMeta(seat, it, row.pushAlerts, row.trackDaily, row.inLogbook) }, label = { Text(stringResource(R.string.flight_notes_label)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            ToggleRow(stringResource(R.string.flight_push_alerts), row.pushAlerts) { vm.updateMeta(seat, notes, it, row.trackDaily, row.inLogbook) }
            ToggleRow(stringResource(R.string.flight_track_daily), row.trackDaily) { vm.updateMeta(seat, notes, row.pushAlerts, it, row.inLogbook) }
            ToggleRow(stringResource(R.string.flight_in_logbook), row.inLogbook) { vm.updateMeta(seat, notes, row.pushAlerts, row.trackDaily, it) }
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
private fun ClockRow(row: FlightEntity) {
    val depZone = DateTimeFmt.airportZone(row.fromTimezone)
    val arrZone = DateTimeFmt.airportZone(row.toTimezone)
    Column {
        Text("${stringResource(R.string.flight_dep_scheduled)} ${DateTimeFmt.dateTime(row.scheduledDep, depZone)}")
        row.estimatedDep?.let { Text("${stringResource(R.string.flight_dep_estimated)} ${DateTimeFmt.dateTime(it, depZone)}") }
        row.actualDep?.let { Text("${stringResource(R.string.flight_dep_actual)} ${DateTimeFmt.dateTime(it, depZone)}") }
        Text("${stringResource(R.string.flight_arr_scheduled)} ${DateTimeFmt.dateTime(row.scheduledArr, arrZone)}")
        row.estimatedArr?.let { Text("${stringResource(R.string.flight_arr_estimated)} ${DateTimeFmt.dateTime(it, arrZone)}") }
        row.actualArr?.let { Text("${stringResource(R.string.flight_arr_actual)} ${DateTimeFmt.dateTime(it, arrZone)}") }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
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
