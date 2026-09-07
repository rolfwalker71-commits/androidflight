package de.rolfwalker.flightbuddy.feature.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.domain.airlineInitials
import de.rolfwalker.flightbuddy.core.domain.airlineLogoUrl
import de.rolfwalker.flightbuddy.core.domain.displayFlightNumber
import de.rolfwalker.flightbuddy.core.domain.isEmergencySquawk
import de.rolfwalker.flightbuddy.core.domain.isLiveStatus
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.ui.MainActivity
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

val WIDGET_FLIGHT_ID = stringPreferencesKey("flight_id")
val PARAM_FLIGHT_ID = ActionParameters.Key<String>("flight_id")

class FlightBuddyWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = object : KoinComponent { val r: FlightRepository by inject() }.r
        val flightId = androidx.glance.appwidget.state.getAppWidgetState(
            context,
            androidx.glance.state.PreferencesGlanceStateDefinition,
            id,
        )[WIDGET_FLIGHT_ID]
        val flight = flightId?.let { repo.getFlight(it) }
        val logo = loadAirlineLogo(flight?.airlineIata)
        provideContent {
            GlanceTheme {
                WidgetBody(context, flight, logo)
            }
        }
    }
}

@Composable
private fun WidgetBody(context: Context, flight: FlightEntity?, logo: Bitmap?) {
    val size = LocalSize.current
    val wide = size.width >= 180.dp
    val tall = size.height >= 110.dp
    val extra = size.height >= 180.dp
    val bg = ColorProvider(Color(0xFF231F2B))
    val on = ColorProvider(Color(0xFFE6E0EC))
    val muted = ColorProvider(Color(0xFFCAC4D0))
    val accent = ColorProvider(Color(0xFFD0BCFF))
    val click = if (flight != null) {
        actionStartActivity<MainActivity>(actionParametersOf(PARAM_FLIGHT_ID to flight.id))
    } else {
        actionStartActivity<MainActivity>()
    }
    Column(
        modifier = GlanceModifier.fillMaxSize().background(bg).padding(12.dp).clickable(click),
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        if (flight == null) {
            Text(context.getString(R.string.widget_empty), style = TextStyle(color = muted, fontSize = 12.sp))
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
            LogoBox(flight.airlineIata, flight.airlineName, logo)
            Spacer(GlanceModifier.width(8.dp))
            Column {
                Text(
                    displayFlightNumber(flight.flightNumber),
                    style = TextStyle(color = on, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    "${flight.fromIata.orEmpty()}–${flight.toIata.orEmpty()}",
                    style = TextStyle(color = muted, fontSize = 12.sp),
                )
            }
            Text(statusShort(context, flight), style = TextStyle(color = accent, fontSize = 11.sp, fontWeight = FontWeight.Medium))
        }
        if (wide) {
            Spacer(GlanceModifier.padding(top = 6.dp))
            Text(clockLine(flight), style = TextStyle(color = on, fontSize = 12.sp))
        }
        if (tall) {
            val hint = hintText(context, flight)
            if (hint != null) {
                Spacer(GlanceModifier.padding(top = 4.dp))
                Text(hint, style = TextStyle(color = accent, fontSize = 11.sp))
            }
            if (!flight.gate.isNullOrBlank()) {
                Text(context.getString(R.string.flight_gate) + " " + flight.gate, style = TextStyle(color = muted, fontSize = 11.sp))
            }
        }
        if (extra && flight.delayMinutes != null && flight.delayMinutes > 0) {
            Text(context.getString(R.string.status_delayed_by, flight.delayMinutes), style = TextStyle(color = ColorProvider(Color(0xFFFFB74D)), fontSize = 11.sp))
        }
    }
}

@Composable
private fun LogoBox(iata: String?, name: String?, logo: Bitmap?) {
    val initials = airlineInitials(iata, name)
    androidx.glance.layout.Box(
        modifier = GlanceModifier.size(36.dp).background(ColorProvider(Color(0xFF4A4458))),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            Image(provider = ImageProvider(logo), contentDescription = name ?: iata ?: "logo", modifier = GlanceModifier.size(32.dp))
        } else {
            Text(initials, style = TextStyle(color = ColorProvider(Color(0xFFD0BCFF)), fontSize = 12.sp, fontWeight = FontWeight.Bold))
        }
    }
}

private fun loadAirlineLogo(iata: String?): Bitmap? {
    val url = airlineLogoUrl(iata) ?: return null
    return runCatching {
        java.net.URL(url).openStream().use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}

private fun statusShort(context: Context, f: FlightEntity): String = when (f.status) {
    FlightStatus.EN_ROUTE -> context.getString(R.string.status_en_route)
    FlightStatus.DELAYED -> context.getString(R.string.status_delayed)
    FlightStatus.BOARDING -> context.getString(R.string.status_boarding)
    FlightStatus.DEPARTED -> context.getString(R.string.status_departed)
    FlightStatus.LANDED -> context.getString(R.string.status_landed)
    FlightStatus.CANCELLED -> context.getString(R.string.status_cancelled)
    FlightStatus.DIVERTED -> context.getString(R.string.status_diverted)
    FlightStatus.SCHEDULED -> context.getString(R.string.status_on_time)
    FlightStatus.UNKNOWN -> context.getString(R.string.status_unknown)
}

private fun hintText(context: Context, f: FlightEntity): String? {
    if (isEmergencySquawk(f.lastSquawk)) return context.getString(R.string.flight_squawk, f.lastSquawk!!)
    if (f.status == FlightStatus.DIVERTED) return context.getString(R.string.status_diverted)
    if (f.status == FlightStatus.CANCELLED) return context.getString(R.string.status_cancelled)
    if ((f.delayMinutes ?: 0) > 0) return context.getString(R.string.status_delayed_by, f.delayMinutes!!)
    if (isLiveStatus(f.status)) return context.getString(R.string.flight_live)
    return null
}

private fun clockLine(f: FlightEntity): String {
    val dep = DateTimeFmt.dateTime(f.estimatedDep ?: f.scheduledDep, DateTimeFmt.airportZone(f.fromTimezone))
    val arr = DateTimeFmt.dateTime(f.estimatedArr ?: f.scheduledArr, DateTimeFmt.airportZone(f.toTimezone))
    return "$dep → $arr"
}

class FlightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = FlightBuddyWidget()
}

class WidgetUpdater(private val context: Context) : KoinComponent {
    private val repo: FlightRepository by inject()

    suspend fun updateAll(changedFlightIds: Set<String> = emptySet()) {
        val manager = GlanceAppWidgetManager(context)
        val widget = FlightBuddyWidget()
        manager.getGlanceIds(FlightBuddyWidget::class.java).forEach { id ->
            val state = androidx.glance.appwidget.state.getAppWidgetState(
                context,
                androidx.glance.state.PreferencesGlanceStateDefinition,
                id,
            )
            val pinned = state[WIDGET_FLIGHT_ID]
            if (changedFlightIds.isEmpty() || pinned == null || pinned in changedFlightIds) {
                widget.update(context, id)
            }
        }
    }

    suspend fun pinFlight(glanceId: GlanceId, flightId: String) {
        updateAppWidgetState(context, glanceId) { it[WIDGET_FLIGHT_ID] = flightId }
        FlightBuddyWidget().update(context, glanceId)
    }
}
