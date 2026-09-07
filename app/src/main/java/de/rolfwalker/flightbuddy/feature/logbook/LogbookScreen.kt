package de.rolfwalker.flightbuddy.feature.logbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.ui.TonalCard
import java.time.LocalDate

@Composable
fun LogbookScreen(vm: LogbookViewModel) {
    val (year, stats) = vm.state.collectAsState().value
    val thisYear = LocalDate.now().year
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.logbook_title), style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = year == thisYear, onClick = { vm.setYear(thisYear) }, label = { Text(stringResource(R.string.logbook_year, thisYear)) }, shape = CircleShape)
            FilterChip(selected = year == null, onClick = { vm.setYear(null) }, label = { Text(stringResource(R.string.logbook_all_time)) }, shape = CircleShape)
        }
        if (stats.flights == 0) {
            TonalCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.logbook_empty), modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(stringResource(R.string.logbook_flights), stats.flights.toString(), Modifier.weight(1f))
                Stat(stringResource(R.string.logbook_airtime), "${stats.minutes / 60}h ${stats.minutes % 60}m", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(stringResource(R.string.logbook_distance), "${stats.miles.toInt()} mi", Modifier.weight(1f))
                Stat(stringResource(R.string.logbook_countries), stats.countries.toString(), Modifier.weight(1f))
            }
            TonalCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.logbook_top_airports), style = MaterialTheme.typography.titleMedium)
                    stats.topAirports.forEach { (code, n) -> Text("$code · $n") }
                }
            }
            TonalCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.logbook_top_airlines), style = MaterialTheme.typography.titleMedium)
                    stats.topAirlines.forEach { (name, n) -> Text("$name · $n") }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    TonalCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
