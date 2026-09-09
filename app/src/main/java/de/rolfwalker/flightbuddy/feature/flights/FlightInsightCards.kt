package de.rolfwalker.flightbuddy.feature.flights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.domain.FlightProfile
import de.rolfwalker.flightbuddy.core.domain.HistoricLeg
import de.rolfwalker.flightbuddy.core.domain.arrZone
import de.rolfwalker.flightbuddy.core.domain.depZone
import de.rolfwalker.flightbuddy.core.domain.displayFlightNumber
import de.rolfwalker.flightbuddy.core.domain.parseTimeline
import de.rolfwalker.flightbuddy.core.domain.wetLeaseLine
import de.rolfwalker.flightbuddy.core.model.Units
import de.rolfwalker.flightbuddy.core.ui.TonalCard

@Composable
fun FlightOpsCards(row: FlightEntity, units: Units, language: String, hidePunctuality: Boolean = false) {
    val reasons = delayReasons(row)
    if (reasons.isNotEmpty()) {
        InsightCard(stringResource(R.string.insight_delay_title)) {
            reasons.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    val timeline = parseTimeline(row.timelineJson)
    if (timeline.any { it.at != null }) {
        InsightCard(stringResource(R.string.insight_timeline_title)) {
            timeline.forEach { ev ->
                val label = timelineLabel(ev.id)
                val zone = if (ev.id == "landed" || ev.id == "gate_in" || ev.id == "descent") row.arrZone() else row.depZone()
                val clock = ev.at?.let { DateTimeFmt.time(it, zone) } ?: "—"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = if (ev.done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(clock, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    val ops = opsLines(row, units)
    if (ops.isNotEmpty()) {
        InsightCard(stringResource(R.string.insight_ops_title)) {
            ops.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    wetLeaseLine(row.paintedAs, row.operatingAs)?.let { op ->
        InsightCard(stringResource(R.string.insight_operator_title)) {
            Text(stringResource(R.string.insight_operated_by, op), style = MaterialTheme.typography.bodyMedium)
            row.codeshares?.let { Text(stringResource(R.string.insight_codeshares, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    } ?: row.codeshares?.let { shares ->
        InsightCard(stringResource(R.string.insight_operator_title)) {
            Text(stringResource(R.string.insight_codeshares, shares), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (!hidePunctuality && row.punctualitySample != null && row.punctualityMedianMin != null) {
        InsightCard(stringResource(R.string.insight_punctuality_title)) {
            Text(
                stringResource(R.string.insight_punctuality_body, row.punctualitySample!!, row.punctualityMedianMin!!),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    val wx = listOfNotNull(
        row.fromIata?.let { iata -> row.depMetar?.let { metarLine(iata, it) } },
        row.toIata?.let { iata -> row.arrMetar?.let { metarLine(iata, it) } },
    )
    if (wx.isNotEmpty()) {
        InsightCard(stringResource(R.string.insight_weather_title)) {
            wx.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    val planeBits = listOfNotNull(
        row.aircraftAgeYears?.let { stringResource(R.string.insight_aircraft_age, it) },
        row.aircraftOperator,
        if (row.isCargo == true) stringResource(R.string.insight_cargo) else null,
    )
    if (planeBits.isNotEmpty()) {
        InsightCard(stringResource(R.string.insight_aircraft_title)) {
            planeBits.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
fun FlightProfileCard(profile: FlightProfile, onAirport: (String, Boolean) -> Unit) {
    InsightCard(stringResource(R.string.profile_title)) {
        Text(
            stringResource(R.string.profile_sample, profile.sample),
            style = MaterialTheme.typography.bodyMedium,
        )
        val stats = listOfNotNull(
            profile.onTimePct?.let { stringResource(R.string.profile_ontime, it) },
            profile.medianDelayMin?.let { stringResource(R.string.profile_delay, it) },
            profile.medianTaxiOutMin?.let { stringResource(R.string.profile_taxi, it) },
            profile.medianBlockMin?.let { stringResource(R.string.profile_block, it) },
        )
        stats.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (profile.tails.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.profile_tails,
                    profile.tails.joinToString(", ") { (reg, n) -> "$reg · ${n}×" },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (profile.legs.isNotEmpty()) {
            Text(
                stringResource(R.string.profile_legs),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            profile.legs.forEach { HistoricLegRow(it, onAirport) }
        }
    }
}

@Composable
fun AircraftHistoryCard(
    registration: String?,
    legs: List<HistoricLeg>,
    onAirport: (String, Boolean) -> Unit,
) {
    if (legs.isEmpty()) return
    InsightCard(
        if (registration.isNullOrBlank()) stringResource(R.string.aircraft_history_title)
        else stringResource(R.string.aircraft_history_reg, registration),
    ) {
        legs.forEach { HistoricLegRow(it, onAirport) }
    }
}

@Composable
private fun HistoricLegRow(leg: HistoricLeg, onAirport: (String, Boolean) -> Unit) {
    val number = leg.flightNumber?.let { displayFlightNumber(it) } ?: "—"
    val whenText = leg.at?.let { DateTimeFmt.date(it) } ?: "—"
    val extras = listOfNotNull(
        leg.delayMin?.let { stringResource(R.string.profile_leg_delay, it) },
        leg.taxiOutMin?.let { stringResource(R.string.insight_taxi_out, it) },
        leg.registration?.takeIf { it.isNotBlank() },
        leg.callsign?.takeIf { !it.equals(leg.flightNumber, true) },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("$whenText · $number", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AirportLink(leg.fromIata, arrivals = false, onAirport)
                Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                AirportLink(leg.toIata, arrivals = true, onAirport)
            }
            if (extras.isNotEmpty()) {
                Text(
                    extras.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun AirportLink(code: String?, arrivals: Boolean, onAirport: (String, Boolean) -> Unit) {
    val iata = code?.trim()?.uppercase().orEmpty()
    if (iata.length !in 3..4) {
        Text(iata.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val label = stringResource(R.string.airport_open, iata)
    Text(
        iata,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable { onAirport(iata, arrivals) }
            .semantics {
                role = Role.Button
                contentDescription = label
            },
    )
}

@Composable
fun SourceChip(source: String?, estimated: Boolean, onGround: Boolean = false) {
    val label = when {
        source == "opensky" -> stringResource(R.string.flight_source_adsb)
        source == "fr24" -> stringResource(R.string.flight_source_fr24)
        onGround -> stringResource(R.string.flight_source_ground)
        estimated -> stringResource(R.string.flight_source_estimate)
        source == "aerodatabox" -> stringResource(R.string.flight_source_aero)
        source.isNullOrBlank() -> stringResource(R.string.flight_source_none)
        else -> source
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    if (estimated) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    CircleShape,
                ),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InsightCard(title: String, content: @Composable () -> Unit) {
    TonalCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun delayReasons(row: FlightEntity): List<String> {
    val out = mutableListOf<String>()
    val delay = row.delayMinutes ?: 0
    if (delay > 0) out += stringResource(R.string.insight_delay_flight, delay)
    row.arrivalDelayMinutes?.takeIf { it > 0 }?.let { out += stringResource(R.string.insight_delay_arrival, it) }
    row.depAirportDelayMin?.takeIf { it >= 15 }?.let {
        out += stringResource(R.string.insight_delay_airport, row.fromIata ?: "—", it)
    }
    row.arrAirportDelayMin?.takeIf { it >= 15 }?.let {
        out += stringResource(R.string.insight_delay_airport, row.toIata ?: "—", it)
    }
    row.inboundFlight?.let { inbound ->
        val late = row.inboundDelayMin
        out += if (late != null && late > 0) {
            stringResource(R.string.insight_delay_inbound_late, inbound, late)
        } else {
            stringResource(R.string.insight_delay_inbound, inbound)
        }
    }
    return out
}

@Composable
private fun opsLines(row: FlightEntity, units: Units): List<String> {
    val out = mutableListOf<String>()
    row.taxiOutMin?.let { out += stringResource(R.string.insight_taxi_out, it) }
    row.taxiInMin?.let { out += stringResource(R.string.insight_taxi_in, it) }
    row.runwayDep?.let { out += stringResource(R.string.insight_runway_dep, it) }
    row.runwayArr?.let { out += stringResource(R.string.insight_runway_arr, it) }
    row.flightTimeSec?.let { sec ->
        val h = sec / 3600
        val m = (sec % 3600) / 60
        out += stringResource(R.string.insight_block, h, m)
    }
    val actual = row.actualDistanceKm
    val circle = row.circleDistanceKm
    if (actual != null) {
        val shown = if (units == Units.IMPERIAL) actual * 0.621371 to stringResource(R.string.insight_mi) else actual to stringResource(R.string.insight_km)
        out += if (circle != null && circle > 0) {
            val extra = ((actual / circle - 1.0) * 100).toInt()
            stringResource(R.string.insight_distance_extra, shown.first.toInt(), shown.second, extra)
        } else {
            stringResource(R.string.insight_distance, shown.first.toInt(), shown.second)
        }
    }
    row.destIataActual?.takeIf { it.isNotBlank() && !it.equals(row.toIata, true) }?.let {
        out += stringResource(R.string.insight_diverted_to, it)
    }
    return out
}

@Composable
private fun timelineLabel(id: String): String = when (id) {
    "gate_out" -> stringResource(R.string.insight_event_gate_out)
    "takeoff" -> stringResource(R.string.insight_event_takeoff)
    "cruise" -> stringResource(R.string.insight_event_cruise)
    "descent" -> stringResource(R.string.insight_event_descent)
    "landed" -> stringResource(R.string.insight_event_landed)
    "gate_in" -> stringResource(R.string.insight_event_gate_in)
    else -> id
}

private fun metarLine(iata: String, metar: String): String {
    val short = metar.replace(Regex("\\s+"), " ").trim().take(120)
    return "$iata · $short"
}
