package de.rolfwalker.flightbuddy.tracking

import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.data.prefs.ApiKeys
import de.rolfwalker.flightbuddy.core.data.toLocalDate
import de.rolfwalker.flightbuddy.core.domain.buildTimeline
import de.rolfwalker.flightbuddy.core.domain.encodeTimeline
import de.rolfwalker.flightbuddy.core.domain.medianInt
import de.rolfwalker.flightbuddy.core.domain.taxiMinutes
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.network.Fr24Summary
import de.rolfwalker.flightbuddy.core.network.ProviderClients
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class InsightEngine(
    private val repo: FlightRepository,
    private val providers: ProviderClients,
) {
    suspend fun enrich(row: FlightEntity, keys: ApiKeys, force: Boolean = false): FlightEntity {
        var next = row
        next = fillAirports(next, keys)
        val now = System.currentTimeMillis()
        val stale = next.insightUpdatedAt == null || now - next.insightUpdatedAt!! > INSIGHT_MIN_MS
        if (!force && !stale && !needsOps(next)) return next
        next = mergeWeatherAndDelay(next, keys)
        next = mergeAircraft(next, keys)
        next = mergeFr24Ops(next, keys)
        next = mergePunctuality(next, keys)
        return next.copy(insightUpdatedAt = now)
    }

    private fun needsOps(row: FlightEntity): Boolean {
        if (row.status == FlightStatus.CANCELLED) return false
        if (row.fr24Id == null && !row.registration.isNullOrBlank()) return true
        if (row.firstSeenAt == null && row.status != FlightStatus.SCHEDULED) return true
        return false
    }

    private suspend fun fillAirports(row: FlightEntity, keys: ApiKeys): FlightEntity {
        var next = row
        next = fillOneAirport(next, keys, departure = true)
        next = fillOneAirport(next, keys, departure = false)
        return next
    }

    private suspend fun fillOneAirport(row: FlightEntity, keys: ApiKeys, departure: Boolean): FlightEntity {
        val iata = if (departure) row.fromIata else row.toIata
        if (iata.isNullOrBlank()) return row
        val missingTz = if (departure) row.fromTimezone.isNullOrBlank() else row.toTimezone.isNullOrBlank()
        val missingCoord = if (departure) row.fromLat == null || row.fromLon == null else row.toLat == null || row.toLon == null
        if (!missingTz && !missingCoord) {
            val stored = repo.getAirport(iata)
            if (stored?.timezone != null) {
                return if (departure && row.fromTimezone == null) row.copy(fromTimezone = stored.timezone) else row
            }
            return row
        }
        val info = providers.fetchAeroAirport(keys, iata) ?: providers.fetchFr24Airport(keys, iata)
        if (info != null) {
            repo.upsertAirport(
                iata = info.iata ?: iata,
                city = info.city,
                tz = info.timezone,
                lat = info.lat,
                lon = info.lon,
                icao = info.icao,
                name = info.name,
                country = info.country,
                elevationFt = info.elevationFt,
            )
            return if (departure) {
                row.copy(
                    fromTimezone = row.fromTimezone ?: info.timezone,
                    fromLat = row.fromLat ?: info.lat,
                    fromLon = row.fromLon ?: info.lon,
                    fromCity = row.fromCity ?: info.city,
                    fromCountry = row.fromCountry ?: info.country,
                )
            } else {
                row.copy(
                    toTimezone = row.toTimezone ?: info.timezone,
                    toLat = row.toLat ?: info.lat,
                    toLon = row.toLon ?: info.lon,
                    toCity = row.toCity ?: info.city,
                    toCountry = row.toCountry ?: info.country,
                )
            }
        }
        return row
    }

    private suspend fun mergeWeatherAndDelay(row: FlightEntity, keys: ApiKeys): FlightEntity {
        val depDay = row.scheduledDep.toLocalDate(de.rolfwalker.flightbuddy.core.DateTimeFmt.zoneOrDevice(row.fromTimezone))
        val arrDay = (row.scheduledArr ?: row.scheduledDep).toLocalDate(de.rolfwalker.flightbuddy.core.DateTimeFmt.zoneOrDevice(row.toTimezone))
        val depWx = row.fromIata?.let { providers.fetchAeroWeather(keys, it, depDay) }
        val arrWx = row.toIata?.let { providers.fetchAeroWeather(keys, it, arrDay) }
        val depDelay = row.fromIata?.let { providers.fetchAeroDelayIndex(keys, it, depDay) }
        val arrDelay = row.toIata?.let { providers.fetchAeroDelayIndex(keys, it, arrDay) }
        return row.copy(
            depMetar = depWx?.metar ?: row.depMetar,
            arrMetar = arrWx?.metar ?: row.arrMetar,
            depAirportDelayMin = depDelay?.delayMinutes ?: row.depAirportDelayMin,
            arrAirportDelayMin = arrDelay?.delayMinutes ?: row.arrAirportDelayMin,
        )
    }

    private suspend fun mergeAircraft(row: FlightEntity, keys: ApiKeys): FlightEntity {
        val reg = row.registration ?: return row
        if (row.aircraftAgeYears != null && row.aircraftOperator != null) return row
        val info = providers.fetchAeroAircraft(keys, reg) ?: return row
        return row.copy(
            aircraftType = row.aircraftType ?: info.type,
            aircraftAgeYears = info.ageYears ?: row.aircraftAgeYears,
            aircraftOperator = info.operator ?: row.aircraftOperator,
        )
    }

    private suspend fun mergeFr24Ops(row: FlightEntity, keys: ApiKeys): FlightEntity {
        if (keys.fr24Token.isBlank() || !keys.fr24Enabled) return row
        val from = iso(row.scheduledDep - 18L * 60 * 60 * 1000)
        val to = iso((row.scheduledArr ?: row.scheduledDep) + 18L * 60 * 60 * 1000)
        val mine = providers.fetchFr24Summaries(
            keys,
            flights = row.flightNumber,
            fromIso = from,
            toIso = to,
            limit = 8,
        ).minByOrNull { kotlin.math.abs((it.takeoffAt ?: it.firstSeen ?: 0L) - row.scheduledDep) }
        var next = if (mine != null) applySummary(row, mine) else row
        next = mergeInbound(next, keys)
        if (!next.fr24Id.isNullOrBlank()) {
            val events = providers.fetchFr24Events(keys, next.fr24Id!!)
            val extra = events.map { (type, at) ->
                de.rolfwalker.flightbuddy.core.domain.TimelineEvent(normalizeEvent(type), at, at <= System.currentTimeMillis())
            }
            next = next.copy(
                timelineJson = encodeTimeline(
                    buildTimeline(next.firstSeenAt, next.runwayDepAt, next.runwayArrAt, next.lastSeenAtFr24, System.currentTimeMillis(), extra),
                ),
            )
        } else {
            next = next.copy(
                timelineJson = encodeTimeline(
                    buildTimeline(next.firstSeenAt, next.runwayDepAt, next.runwayArrAt, next.lastSeenAtFr24, System.currentTimeMillis()),
                ),
            )
        }
        return next
    }

    private suspend fun mergeInbound(row: FlightEntity, keys: ApiKeys): FlightEntity {
        val reg = row.registration ?: return row
        val origin = row.fromIata ?: return row
        val from = iso(row.scheduledDep - 36L * 60 * 60 * 1000)
        val to = iso(row.scheduledDep + 30L * 60 * 1000)
        val legs = providers.fetchFr24Summaries(keys, registrations = reg, fromIso = from, toIso = to, limit = 12)
        val inbound = legs
            .filter { (it.destIataActual ?: it.destIata).equals(origin, true) }
            .filter { (it.landedAt ?: it.lastSeen ?: 0L) <= row.scheduledDep + 2L * 60 * 60 * 1000 }
            .maxByOrNull { it.landedAt ?: it.lastSeen ?: 0L }
            ?: return row
        val arr = inbound.landedAt ?: inbound.lastSeen
        val late = if (arr != null) ((arr - row.scheduledDep) / 60_000L).toInt() else null
        return row.copy(
            inboundFlight = inbound.flight ?: inbound.callsign ?: row.inboundFlight,
            inboundArrAt = arr ?: row.inboundArrAt,
            inboundDelayMin = late?.takeIf { it > 0 } ?: row.inboundDelayMin,
        )
    }

    private suspend fun mergePunctuality(row: FlightEntity, keys: ApiKeys): FlightEntity {
        if (row.punctualitySample != null && row.insightUpdatedAt != null &&
            System.currentTimeMillis() - row.insightUpdatedAt!! < 12L * 60 * 60 * 1000
        ) {
            return row
        }
        val day = row.scheduledDep.toLocalDate(ZoneOffset.UTC)
        val aero = providers.searchAeroRange(keys, row.flightNumber, day.minusDays(14), day)
        val delays = aero.flights.mapNotNull { it.delayMinutes }.filter { it >= 0 }
        if (delays.size >= 3) {
            return row.copy(punctualityMedianMin = medianInt(delays), punctualitySample = delays.size)
        }
        val from = iso(row.scheduledDep - 14L * 24 * 60 * 60 * 1000)
        val to = iso(row.scheduledDep + 6L * 60 * 60 * 1000)
        val hist = providers.fetchFr24Summaries(keys, flights = row.flightNumber, fromIso = from, toIso = to, limit = 40)
        if (hist.size < 3) return row
        val taxi = hist.mapNotNull { s -> taxiMinutes(s.firstSeen, s.takeoffAt) }
        val median = medianInt(taxi)?.let { (it - 15).coerceAtLeast(0) } ?: return row
        return row.copy(punctualityMedianMin = median, punctualitySample = hist.size)
    }

    private fun applySummary(row: FlightEntity, s: Fr24Summary): FlightEntity {
        val destActual = s.destIataActual ?: s.destIata
        val diverted = destActual != null && row.toIata != null && !destActual.equals(row.toIata, true)
        return row.copy(
            fr24Id = s.fr24Id ?: row.fr24Id,
            paintedAs = s.paintedAs ?: row.paintedAs,
            operatingAs = s.operatingAs ?: row.operatingAs,
            registration = s.reg ?: row.registration,
            aircraftType = row.aircraftType ?: s.type,
            destIataActual = destActual ?: row.destIataActual,
            runwayDep = s.runwayTakeoff ?: row.runwayDep,
            runwayArr = s.runwayLanded ?: row.runwayArr,
            runwayDepAt = s.takeoffAt ?: row.runwayDepAt,
            runwayArrAt = s.landedAt ?: row.runwayArrAt,
            firstSeenAt = s.firstSeen ?: row.firstSeenAt,
            lastSeenAtFr24 = s.lastSeen ?: row.lastSeenAtFr24,
            taxiOutMin = taxiMinutes(s.firstSeen, s.takeoffAt) ?: row.taxiOutMin,
            taxiInMin = taxiMinutes(s.landedAt, s.lastSeen) ?: row.taxiInMin,
            actualDistanceKm = s.actualDistanceKm ?: row.actualDistanceKm,
            circleDistanceKm = s.circleDistanceKm ?: row.circleDistanceKm,
            flightTimeSec = s.flightTimeSec ?: row.flightTimeSec,
            status = if (diverted && row.status != FlightStatus.CANCELLED) FlightStatus.DIVERTED else row.status,
            callsign = s.callsign ?: row.callsign,
        )
    }

    private fun normalizeEvent(raw: String): String {
        val s = raw.lowercase()
        return when {
            s.contains("gate_departure") || s.contains("gate-out") -> "gate_out"
            s.contains("takeoff") -> "takeoff"
            s.contains("cruis") -> "cruise"
            s.contains("descent") -> "descent"
            s.contains("land") -> "landed"
            s.contains("gate_arrival") || s.contains("gate-in") -> "gate_in"
            else -> s
        }
    }

    private fun iso(ms: Long): String =
        Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).format(ISO)

    companion object {
        private const val INSIGHT_MIN_MS = 15L * 60 * 1000
        private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
