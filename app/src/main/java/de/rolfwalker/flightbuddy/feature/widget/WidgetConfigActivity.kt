package de.rolfwalker.flightbuddy.feature.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.currentAppLanguage
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.data.prefs.PrefsStore
import de.rolfwalker.flightbuddy.core.data.prefs.UserPrefs
import de.rolfwalker.flightbuddy.core.domain.isAirborneDisplay
import de.rolfwalker.flightbuddy.core.domain.isPastStatus
import de.rolfwalker.flightbuddy.core.domain.isUpcomingDisplay
import de.rolfwalker.flightbuddy.core.model.ThemeMode
import de.rolfwalker.flightbuddy.core.ui.FlightBuddyTheme
import de.rolfwalker.flightbuddy.core.ui.LocalizedContent
import de.rolfwalker.flightbuddy.core.ui.TonalCard
import de.rolfwalker.flightbuddy.core.ui.status.airlineFlightLine
import de.rolfwalker.flightbuddy.core.ui.status.cityRouteTitle
import de.rolfwalker.flightbuddy.core.ui.status.displayFlightStatus
import de.rolfwalker.flightbuddy.core.ui.status.flightStatusChip
import de.rolfwalker.flightbuddy.core.ui.status.flightStatusLabel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

/**
 * Launcher configure for 4×2 / 5×2. Always finishes with [RESULT_OK] plus
 * [AppWidgetManager.EXTRA_APPWIDGET_ID] so the pin is never discarded.
 * Back without a tap binds [pickDefaultFlight] (first LIVE, else first upcoming).
 */
class WidgetConfigActivity : AppCompatActivity() {
    private val repo: FlightRepository by inject()
    private val updater: WidgetUpdater by inject()
    private val prefs: PrefsStore by inject()

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var completing = false
    private var bound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        publishOk()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    complete(flightId = null)
                }
            },
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            val userPrefs by prefs.flow.collectAsState(initial = UserPrefs(language = currentAppLanguage()))
            LocalizedContent(userPrefs.language) {
                FlightBuddyTheme(ThemeMode.SYSTEM) {
                    val flights by repo.observeFlights().collectAsState(initial = emptyList())
                    PickerScreen(
                        flights = flights,
                        language = userPrefs.language,
                        onPick = { complete(it.id) },
                    )
                }
            }
        }
    }

    override fun finish() {
        publishOk()
        if (!bound && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            bindDefaultBlocking()
        }
        super.finish()
    }

    private fun complete(flightId: String?) {
        if (completing) return
        completing = true
        lifecycleScope.launch {
            try {
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val id = flightId ?: pickDefaultFlight(repo.listFlights())?.id
                    if (id != null) {
                        updater.bindFlight(appWidgetId, id)
                        bound = true
                    } else {
                        RemoteFlightWidget.update(this@WidgetConfigActivity, appWidgetId)
                    }
                }
            } finally {
                publishOk()
                finish()
            }
        }
    }

    private fun bindDefaultBlocking() {
        runCatching {
            runBlocking {
                val id = pickDefaultFlight(repo.listFlights())?.id
                if (id != null) {
                    WidgetPins.save(this@WidgetConfigActivity, appWidgetId, id)
                    bound = true
                }
            }
        }
        RemoteFlightWidget.update(this, appWidgetId)
    }

    private fun publishOk() {
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )
    }
}

@Composable
private fun PickerScreen(
    flights: List<FlightEntity>,
    language: String,
    onPick: (FlightEntity) -> Unit,
) {
    val sections = groupedPickerFlights(flights)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(20.dp),
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.widget_pick),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (flights.isEmpty()) {
                Text(
                    stringResource(R.string.widget_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    sections.forEach { section ->
                        item(key = "h-${section.titleRes}") {
                            Text(
                                stringResource(section.titleRes),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(section.flights, key = { it.id }) { flight ->
                            FlightPickRow(flight, language, onPick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlightPickRow(
    flight: FlightEntity,
    language: String,
    onPick: (FlightEntity) -> Unit,
) {
    val context = LocalContext.current
    val shown = displayFlightStatus(flight)
    val chip = flightStatusChip(flight, shown)
    TonalCard(Modifier.fillMaxWidth().clickable { onPick(flight) }) {
        Column(Modifier.padding(16.dp)) {
            Text(airlineFlightLine(flight), style = MaterialTheme.typography.titleMedium)
            Text(
                cityRouteTitle(context, flight),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                listOf(
                    DateTimeFmt.weekdayDate(flight.scheduledDep, DateTimeFmt.deviceZone(), language),
                    flightStatusLabel(context, chip),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class PickerSection(val titleRes: Int, val flights: List<FlightEntity>)

private fun groupedPickerFlights(flights: List<FlightEntity>): List<PickerSection> {
    val upcoming = flights.filter { isUpcomingDisplay(displayFlightStatus(it)) }.sortedBy { it.scheduledDep }
    val live = flights.filter { isAirborneDisplay(displayFlightStatus(it)) }.sortedBy { it.scheduledDep }
    val past = flights.filter { isPastStatus(displayFlightStatus(it)) }.sortedByDescending { it.scheduledDep }
    return listOf(
        PickerSection(R.string.home_upcoming, upcoming),
        PickerSection(R.string.home_live, live),
        PickerSection(R.string.home_past, past),
    ).filter { it.flights.isNotEmpty() }
}
