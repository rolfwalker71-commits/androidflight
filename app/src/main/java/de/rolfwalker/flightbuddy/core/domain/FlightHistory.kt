package de.rolfwalker.flightbuddy.core.domain

import de.rolfwalker.flightbuddy.core.model.FlightSearchResult
import de.rolfwalker.flightbuddy.core.network.Fr24Summary

data class HistoricLeg(
    val flightNumber: String?,
    val fromIata: String?,
    val toIata: String?,
    val at: Long?,
    val landedAt: Long?,
    val delayMin: Int?,
    val registration: String?,
    val callsign: String?,
    val taxiOutMin: Int?,
    val blockMin: Int?,
)

data class FlightProfile(
    val sample: Int,
    val onTimePct: Int?,
    val medianDelayMin: Int?,
    val medianTaxiOutMin: Int?,
    val medianBlockMin: Int?,
    val tails: List<Pair<String, Int>>,
    val legs: List<HistoricLeg>,
)

fun historicLegFromSummary(s: Fr24Summary): HistoricLeg {
    val block = s.flightTimeSec?.let { it / 60 }
        ?: if (s.takeoffAt != null && s.landedAt != null && s.landedAt > s.takeoffAt) {
            ((s.landedAt - s.takeoffAt) / 60_000L).toInt()
        } else {
            null
        }
    return HistoricLeg(
        flightNumber = s.flight,
        fromIata = s.origIata,
        toIata = s.destIataActual ?: s.destIata,
        at = s.takeoffAt ?: s.firstSeen,
        landedAt = s.landedAt ?: s.lastSeen,
        delayMin = null,
        registration = s.reg,
        callsign = s.callsign,
        taxiOutMin = taxiMinutes(s.firstSeen, s.takeoffAt),
        blockMin = block,
    )
}

fun buildFlightProfile(
    summaries: List<Fr24Summary>,
    aero: List<FlightSearchResult>,
): FlightProfile? {
    val aeroPast = aero.filter { it.scheduledDep != null }.sortedByDescending { it.scheduledDep }
    val delays = aeroPast.mapNotNull { it.delayMinutes }
    val onTime = delays.takeIf { it.isNotEmpty() }?.count { it <= 15 }
    val legs = if (summaries.isNotEmpty()) {
        summaries.map { historicLegFromSummary(it) }.sortedByDescending { it.at ?: 0L }
    } else {
        aeroPast.map { r ->
            HistoricLeg(
                flightNumber = r.flightNumber,
                fromIata = r.fromIata,
                toIata = r.toIata,
                at = r.actualDep ?: r.runwayDepAt ?: r.scheduledDep,
                landedAt = r.actualArr ?: r.runwayArrAt,
                delayMin = r.delayMinutes,
                registration = r.registration,
                callsign = r.callsign,
                taxiOutMin = null,
                blockMin = null,
            )
        }
    }
    val sample = maxOf(delays.size, summaries.size, legs.size)
    if (sample < 2) return null
    val tails = summaries.mapNotNull { it.reg?.uppercase() }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }
        .take(4)
        .map { it.key to it.value }
    val taxi = summaries.mapNotNull { taxiMinutes(it.firstSeen, it.takeoffAt) }
    val block = summaries.mapNotNull { s ->
        s.flightTimeSec?.let { it / 60 } ?: taxiMinutes(s.takeoffAt, s.landedAt)
    }
    return FlightProfile(
        sample = sample,
        onTimePct = if (delays.isNotEmpty() && onTime != null) (onTime * 100) / delays.size else null,
        medianDelayMin = medianInt(delays),
        medianTaxiOutMin = medianInt(taxi),
        medianBlockMin = medianInt(block),
        tails = tails,
        legs = legs.take(8),
    )
}
