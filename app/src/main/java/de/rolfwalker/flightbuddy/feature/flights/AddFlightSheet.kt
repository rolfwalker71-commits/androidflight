package de.rolfwalker.flightbuddy.feature.flights

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.domain.displayFlightNumber
import de.rolfwalker.flightbuddy.core.model.SearchReason
import de.rolfwalker.flightbuddy.core.ui.AirlineLogo
import de.rolfwalker.flightbuddy.core.ui.FlightSearchTheme
import de.rolfwalker.flightbuddy.core.ui.StatusBadge
import de.rolfwalker.flightbuddy.core.ui.TonalCard
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFlightSheet(onDone: () -> Unit, onCancel: () -> Unit, vm: AddFlightViewModel = koinViewModel()) {
    FlightSearchTheme {
        AddFlightSearchContent(onDone = onDone, onCancel = onCancel, vm = vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFlightSearchContent(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    vm: AddFlightViewModel,
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    var showDate by remember { mutableStateOf(false) }
    val pickDateLabel = stringResource(R.string.flight_pick_date)
    LaunchedEffect(ui.saved) { if (ui.saved) onDone() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(stringResource(R.string.flight_add_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = ui.query,
            onValueChange = { q -> vm.update { it.copy(query = q) } },
            label = { Text(stringResource(R.string.flight_query)) },
            supportingText = { Text(stringResource(R.string.flight_query_hint)) },
            placeholder = null,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = DateTimeFmt.date(ui.date),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.flight_date)) },
                trailingIcon = {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = pickDateLabel)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(onClick = { showDate = true })
                    .semantics {
                        role = Role.Button
                        contentDescription = pickDateLabel
                    },
            )
        }
        if (showDate) {
            val picker = rememberDatePickerState(
                initialSelectedDateMillis = ui.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { showDate = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val picked = picker.selectedDateMillis?.let { ms ->
                                Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                            }
                            vm.setDate(picked ?: ui.date)
                            showDate = false
                        },
                    ) { Text(stringResource(android.R.string.ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDate = false }) {
                        Text(stringResource(R.string.flight_delete_cancel))
                    }
                },
            ) { DatePicker(state = picker) }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = vm::search, enabled = !ui.loading, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.flight_find))
        }
        if (ui.loading) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        }
        ui.reason?.takeIf { ui.results.isEmpty() }?.let { reason ->
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(
                    when (reason) {
                        SearchReason.UNKNOWN_QUERY -> R.string.flight_unknown_query
                        SearchReason.UNCONFIGURED -> R.string.flight_no_api
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
                        SearchReason.EMPTY -> R.string.flight_no_results
                        SearchReason.OK -> R.string.flight_no_results
                    },
                    ui.query,
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.flight_track_daily))
                Text(stringResource(R.string.flight_track_daily_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ui.trackDaily, onCheckedChange = { v -> vm.update { it.copy(trackDaily = v, inLogbook = if (v) false else it.inLogbook) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.flight_in_logbook))
                Text(stringResource(R.string.flight_in_logbook_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ui.inLogbook, onCheckedChange = { v -> vm.update { it.copy(inLogbook = v) } })
        }
        ui.results.forEach { result ->
            Spacer(Modifier.height(8.dp))
            TonalCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AirlineLogo(result.airlineIata, result.airlineName)
                        Spacer(Modifier.padding(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(displayFlightNumber(result.flightNumber.ifBlank { "—" }), style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    "${result.fromIata.orEmpty()}–${result.toIata.orEmpty()}",
                                    result.fromCity,
                                    result.scheduledDep?.let { DateTimeFmt.dateTime(it, DateTimeFmt.deviceZone()) },
                                ).joinToString(" · "),
                            )
                        }
                        StatusBadge(result.status, result.delayMinutes)
                    }
                    if (result.source == "local") Text(stringResource(R.string.flight_route_hint), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.save(result, context) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(stringResource(R.string.flight_save))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.flight_manual), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(ui.manualNumber, { v -> vm.update { it.copy(manualNumber = v) } }, label = { Text(stringResource(R.string.flight_number)) }, modifier = Modifier.fillMaxWidth())
        Row {
            OutlinedTextField(ui.manualFrom, { v -> vm.update { it.copy(manualFrom = v.uppercase()) } }, label = { Text(stringResource(R.string.flight_from)) }, modifier = Modifier.weight(1f))
            Spacer(Modifier.padding(4.dp))
            OutlinedTextField(ui.manualTo, { v -> vm.update { it.copy(manualTo = v.uppercase()) } }, label = { Text(stringResource(R.string.flight_to)) }, modifier = Modifier.weight(1f))
        }
        Button(
            onClick = {
                vm.save(
                    de.rolfwalker.flightbuddy.core.model.FlightSearchResult(
                        flightNumber = ui.manualNumber,
                        fromIata = ui.manualFrom.ifBlank { null },
                        toIata = ui.manualTo.ifBlank { null },
                        scheduledDep = ui.date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    ),
                    context,
                )
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(stringResource(R.string.flight_save)) }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.flight_delete_cancel)) }
    }
}
