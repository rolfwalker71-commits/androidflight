package de.rolfwalker.flightbuddy.feature.flights

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
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.domain.isLiveStatus
import de.rolfwalker.flightbuddy.core.ui.TonalCard
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
    val hour = LocalTime.now().hour
    val greet = when {
        hour < 12 -> stringResource(R.string.greeting_morning)
        hour < 18 -> stringResource(R.string.greeting_afternoon)
        else -> stringResource(R.string.greeting_evening)
    }
    val live = state.flights.filter { isLiveStatus(it.status) }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column {
                    Text(greet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!tablet) {
                        val alertsLabel = stringResource(R.string.nav_alerts)
                        IconButton(onClick = onAlerts, modifier = Modifier.semantics { contentDescription = alertsLabel }) {
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
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeTab.entries.forEach { tab ->
                    FilterChip(
                        selected = state.tab == tab,
                        onClick = { vm.setTab(tab) },
                        label = {
                            Text(
                                when (tab) {
                                    HomeTab.UPCOMING -> stringResource(R.string.home_upcoming)
                                    HomeTab.LIVE -> stringResource(R.string.home_live)
                                    HomeTab.PAST -> stringResource(R.string.home_past)
                                },
                            )
                        },
                        shape = CircleShape,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
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
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                    items(state.visible, key = { it.id }) { flight ->
                        FlightCard(
                            flight = flight,
                            connection = state.connections[flight.id],
                            dense = tablet,
                            showGate = tablet,
                            onClick = { onOpen(flight.id) },
                        )
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
        if (tablet) {
            Box(Modifier.weight(1.1f).padding(16.dp)) {
                HomeHeroMap(live.take(4), onOpen)
            }
        }
    }
}
