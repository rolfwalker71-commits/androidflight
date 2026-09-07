package de.rolfwalker.flightbuddy.feature.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.ui.TonalCard

@Composable
fun AlertsScreen(vm: AlertsViewModel, onOpen: (String?) -> Unit) {
    val alerts by vm.alerts.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(stringResource(R.string.alerts_title), style = MaterialTheme.typography.headlineSmall)
        if (alerts.isNotEmpty()) {
            TextButton(onClick = { vm.markAll() }) { Text(stringResource(R.string.alerts_mark_all)) }
        }
        if (alerts.isEmpty()) {
            TonalCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(stringResource(R.string.alerts_empty), modifier = Modifier.padding(24.dp))
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                items(alerts, key = { it.id }) { a ->
                    TonalCard(Modifier.fillMaxWidth().clickable {
                        vm.markRead(a.id)
                        onOpen(a.flightId)
                    }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(a.title, style = MaterialTheme.typography.titleMedium, color = if (a.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            Text(a.body, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                DateTimeFmt.dateTime(a.createdAt),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
