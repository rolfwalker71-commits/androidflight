package de.rolfwalker.flightbuddy.feature.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.prefs.PrefsStore
import de.rolfwalker.flightbuddy.core.currentAppLanguage
import de.rolfwalker.flightbuddy.core.data.prefs.UserPrefs
import de.rolfwalker.flightbuddy.core.domain.displayFlightNumber
import de.rolfwalker.flightbuddy.core.ui.FlightBuddyTheme
import de.rolfwalker.flightbuddy.core.ui.LocalizedContent
import de.rolfwalker.flightbuddy.core.ui.TonalCard
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class WidgetConfigActivity : AppCompatActivity() {
    private val repo: FlightRepository by inject()
    private val updater: WidgetUpdater by inject()
    private val prefs: PrefsStore by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED)
        setContent {
            val userPrefs by prefs.flow.collectAsState(initial = UserPrefs(language = currentAppLanguage()))
            LocalizedContent(userPrefs.language) {
                FlightBuddyTheme(de.rolfwalker.flightbuddy.core.model.ThemeMode.SYSTEM) {
                    val flights by repo.observeFlights().collectAsState(initial = emptyList())
                    val scope = rememberCoroutineScope()
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Text(androidx.compose.ui.res.stringResource(de.rolfwalker.flightbuddy.R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                        Text(androidx.compose.ui.res.stringResource(de.rolfwalker.flightbuddy.R.string.widget_pick), style = MaterialTheme.typography.bodyMedium)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                            items(flights) { f ->
                                TonalCard(Modifier.fillMaxWidth().clickable {
                                    scope.launch {
                                        WidgetPins.save(this@WidgetConfigActivity, appWidgetId, f.id)
                                        val glanceId = runCatching {
                                            GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                                        }.getOrNull()
                                        if (glanceId != null) {
                                            updater.pinFlight(glanceId, f.id, appWidgetId)
                                        } else {
                                            updater.updateAll()
                                        }
                                        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                        setResult(RESULT_OK, result)
                                        finish()
                                    }
                                }) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(displayFlightNumber(f.flightNumber), style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            listOfNotNull(
                                                "${f.fromIata}–${f.toIata}",
                                                DateTimeFmt.dateTime(f.scheduledDep, DateTimeFmt.deviceZone()),
                                            ).joinToString(" · "),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
