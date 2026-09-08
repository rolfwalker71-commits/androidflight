package de.rolfwalker.flightbuddy.feature.flights

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.domain.arrZone
import de.rolfwalker.flightbuddy.core.domain.depZone
import de.rolfwalker.flightbuddy.core.domain.parseTimeline
import de.rolfwalker.flightbuddy.core.domain.wetLeaseLine
import de.rolfwalker.flightbuddy.core.model.Units
import de.rolfwalker.flightbuddy.core.ui.TonalCard

@Composable
fun FlightOpsCards(row: FlightEntity, units: Units, language: String) {
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
    if (row.punctualitySample != null && row.punctualityMedianMin != null) {
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
