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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.domain.displayFlightNumber
import de.rolfwalker.flightbuddy.core.domain.isLiveStatus
import de.rolfwalker.flightbuddy.core.ui.SegmentItem
import de.rolfwalker.flightbuddy.core.ui.SegmentedControl
import de.rolfwalker.flightbuddy.core.ui.TonalCard
import de.rolfwalker.flightbuddy.core.ui.deviceFirstName
import de.rolfwalker.flightbuddy.core.ui.initialsOf
import de.rolfwalker.flightbuddy.feature.map.HomeHeroMap
import java.time.LocalTime

@Composable
fun HomeScreen(
    tablet: Boolean,
    vm: HomeViewModel,
    onAdd: () -> Unit,
    onAlerts: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val firstName = remember(context) { deviceFirstName(context) }
    val hour = LocalTime.now().hour
    val greetBase = when {
        hour < 12 -> stringResource(R.string.greeting_morning)
        hour < 18 -> stringResource(R.string.greeting_afternoon)
        else -> stringResource(R.string.greeting_evening)
    }
    val greet = if (firstName != null) stringResource(R.string.greeting_named, greetBase, firstName) else greetBase
    val live = state.flights.filter { isLiveStatus(it.status) }
    var pendingDelete by remember { mutableStateOf<FlightEntity?>(null) }
    val language = state.prefs.language

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(greet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!tablet) {
                        val alertsLabel = stringResource(R.string.nav_alerts)
                        IconButton(
                            onClick = onAlerts,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .semantics { contentDescription = alertsLabel },
                        ) {
                            BadgedBox(badge = { if (state.unread > 0) Badge() }) {
                                Icon(Icons.Outlined.Notifications, contentDescription = stringResource(R.string.nav_alerts))
                            }
                        }
                    } else {
                        FilledTonalButton(onClick = onAdd) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Text(stringResource(R.string.home_add))
                        }
                    }
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(initialsOf(firstName), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            SegmentedControl(
                items = listOf(
                    SegmentItem(HomeTab.UPCOMING, stringResource(R.string.home_upcoming), Icons.Outlined.Flight),
                    SegmentItem(HomeTab.LIVE, stringResource(R.string.home_live)),
                    SegmentItem(HomeTab.PAST, stringResource(R.string.home_past)),
                ),
                selected = state.tab,
                onSelect = vm::setTab,
            )
            if (!tablet) {
                Spacer(Modifier.height(12.dp))
                StatsRow(state.stats.flights, state.stats.hours, state.stats.countries)
            }
            Spacer(Modifier.height(12.dp))
            if (state.visible.isEmpty()) {
                TonalCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.Start) {
                        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.home_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onAdd) { Text(stringResource(R.string.home_add_first)) }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(if (tablet) 10.dp else 12.dp), modifier = Modifier.fillMaxSize()) {
                    items(state.visible, key = { it.id }) { flight ->
                        FlightCard(
                            flight = flight,
                            connection = state.connections[flight.id],
                            dense = tablet,
                            showGate = tablet,
                            language = language,
                            onClick = { onOpen(flight.id) },
                            onToggleDaily = { vm.setTrackDaily(flight.id, it) },
                            onDelete = { pendingDelete = flight },
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
        if (tablet) {
            Column(Modifier.weight(1.1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    HomeHeroMap(live.take(4), state.prefs.mapStyle, onOpen)
                }
                StatsRow(state.stats.flights, state.stats.hours, state.stats.countries)
                TonalCard(Modifier.fillMaxWidth().clickable(onClick = onAlerts)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.alerts_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (state.unread > 0) stringResource(R.string.home_unread, state.unread) else stringResource(R.string.home_no_alerts),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.flight_delete_title)) },
            text = { Text(stringResource(R.string.flight_delete_body, displayFlightNumber(row.flightNumber))) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteFlight(row.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.flight_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.flight_delete_cancel)) } },
        )
    }
}

@Composable
private fun StatsRow(flights: Int, hours: Int, countries: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatChip(flights.toString(), stringResource(R.string.home_stat_flights), Modifier.weight(1f))
        StatChip("${hours}h", stringResource(R.string.home_stat_airtime), Modifier.weight(1f))
        StatChip(countries.toString(), stringResource(R.string.home_stat_countries), Modifier.weight(1f))
    }
}

@Composable
private fun StatChip(value: String, label: String, modifier: Modifier = Modifier) {
    TonalCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
