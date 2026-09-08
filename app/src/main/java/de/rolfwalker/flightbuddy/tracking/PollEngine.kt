package de.rolfwalker.flightbuddy.tracking

import de.rolfwalker.flightbuddy.core.data.FlightRepository
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.data.db.ObjectEventEntity
import de.rolfwalker.flightbuddy.core.data.db.PositionEntity
import de.rolfwalker.flightbuddy.core.data.prefs.KeysStore
import de.rolfwalker.flightbuddy.core.data.prefs.PrefsStore
import de.rolfwalker.flightbuddy.core.data.toLocalDate
import de.rolfwalker.flightbuddy.core.data.toPollInput
import de.rolfwalker.flightbuddy.core.domain.LIVE_FIX_STALE_MS
import de.rolfwalker.flightbuddy.core.domain.PREFLIGHT_WINDOW_MS
import de.rolfwalker.flightbuddy.core.domain.hasActuallyArrived
import de.rolfwalker.flightbuddy.core.domain.intervalForFlight
import de.rolfwalker.flightbuddy.core.domain.isEmergencySquawk
import de.rolfwalker.flightbuddy.core.domain.isTerminalStatus
import de.rolfwalker.flightbuddy.core.domain.mergeAeroFlightStatus
import de.rolfwalker.flightbuddy.core.domain.minPlausibleFlightMs
import de.rolfwalker.flightbuddy.core.domain.normalizeSquawk
import de.rolfwalker.flightbuddy.core.domain.resolveDisplayStatus
import de.rolfwalker.flightbuddy.core.domain.resolvePollPhase
import de.rolfwalker.flightbuddy.core.domain.shouldAlertEmergencySquawk
import de.rolfwalker.flightbuddy.core.domain.statusAfterGroundFix
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.model.PollPhase
import de.rolfwalker.flightbuddy.core.model.SearchReason
import de.rolfwalker.flightbuddy.core.network.LiveFix
import de.rolfwalker.flightbuddy.core.network.ProviderClients
import kotlinx.coroutines.flow.first
import java.time.ZoneOffset

data class PollOutcome(
    val flightId: String,
    val changed: Boolean,
    val status: FlightStatus,
)

class PollEngine(
    private val repo: FlightRepository,
    private val providers: ProviderClients,
    private val keysStore: KeysStore,
    private val prefsStore: PrefsStore,
    private val alerts: AlertDispatcher,
    private val insights: InsightEngine,
) {
    suspend fun pollDueFlights(forceAllActive: Boolean = false): List<PollOutcome> {
        val now = System.currentTimeMillis()
        val rows = repo.listFlights().filter { !isTerminalStatus(it.status) }
        val due = rows.filter { forceAllActive || it.nextPollAt == null || it.nextPollAt <= now + 2_000 }
        return due.map { pollFlight(it) }
    }

    suspend fun pollFlight(row: FlightEntity): PollOutcome {
        val keys = keysStore.snapshot()
        val prefs = prefsStore.flow.first()
        val prev = row.copy()
        var next = row
        val phase = resolvePollPhase(row.toPollInput())

        if (phase == PollPhase.INACTIVE || phase == PollPhase.PREFLIGHT || phase == PollPhase.AIRBORNE) {
            val date = row.scheduledDep.toLocalDate(ZoneOffset.UTC)
            val aero = providers.searchAeroNumber(keys, row.flightNumber, date, user = false)
            if (aero.reason == SearchReason.OK) {
                val match = aero.flights
                    .filter { candidate ->
                        (row.fromIata == null || candidate.fromIata == row.fromIata) &&
                            (row.toIata == null || candidate.toIata == row.toIata || candidate.toIata != null)
                    }
                    .minByOrNull { candidate ->
                        kotlin.math.abs((candidate.scheduledDep ?: Long.MAX_VALUE) - row.scheduledDep)
                    }
                    ?.takeIf { candidate ->
                        val dep = candidate.scheduledDep
                        dep == null || kotlin.math.abs(dep - row.scheduledDep) < 12 * 60 * 60 * 1000
                    }
                    ?: aero.flights.firstOrNull { candidate ->
                        val dep = candidate.scheduledDep
                        dep != null && kotlin.math.abs(dep - row.scheduledDep) < 12 * 60 * 60 * 1000
                    }
                if (match != null) {
                    val destChanged = match.toIata != null && row.toIata != null && match.toIata != row.toIata
                    val merged = mergeAeroFlightStatus(
                        current = next.status,
                        aeroStatus = match.status,
                        destinationChanged = destChanged,
                        actualArr = match.actualArr ?: next.actualArr,
                        scheduledDep = next.scheduledDep,
                        estimatedDep = match.estimatedDep ?: next.estimatedDep,
                        actualDep = match.actualDep ?: next.actualDep,
                        scheduledArr = next.scheduledArr ?: match.scheduledArr,
                        estimatedArr = match.estimatedArr ?: next.estimatedArr,
                        delayMinutes = match.delayMinutes ?: next.delayMinutes,
                    )
                    if (destChanged && match.toIata != null) {
                        repo.upsertAirport(match.toIata, match.toCity, match.toTimezone, match.toLat, match.toLon)
                    }
                    next = next.copy(
                        status = merged,
                        gate = match.gate ?: next.gate,
                        terminal = match.terminal ?: next.terminal,
                        arrivalGate = match.arrivalGate ?: next.arrivalGate,
                        arrivalTerminal = match.arrivalTerminal ?: next.arrivalTerminal,
                        baggageBelt = match.baggageBelt ?: next.baggageBelt,
                        checkInDesk = match.checkInDesk ?: next.checkInDesk,
                        delayMinutes = match.delayMinutes ?: next.delayMinutes,
                        arrivalDelayMinutes = match.arrivalDelayMinutes ?: next.arrivalDelayMinutes,
                        codeshares = match.codeshares ?: next.codeshares,
                        isCargo = match.isCargo ?: next.isCargo,
                        estimatedDep = match.estimatedDep ?: next.estimatedDep,
                        estimatedArr = match.estimatedArr ?: next.estimatedArr,
                        actualDep = match.actualDep ?: next.actualDep,
                        actualArr = match.actualArr ?: next.actualArr,
                        runwayDepAt = match.runwayDepAt ?: next.runwayDepAt,
                        runwayArrAt = match.runwayArrAt ?: next.runwayArrAt,
                        scheduledArr = next.scheduledArr ?: match.scheduledArr,
                        fromTimezone = match.fromTimezone ?: next.fromTimezone,
                        toTimezone = match.toTimezone ?: next.toTimezone,
                        fromLat = next.fromLat ?: match.fromLat,
                        fromLon = next.fromLon ?: match.fromLon,
                        toIata = if (destChanged) match.toIata else next.toIata,
                        toCity = if (destChanged) match.toCity else next.toCity,
                        toLat = match.toLat ?: next.toLat,
                        toLon = match.toLon ?: next.toLon,
                        aircraftType = match.aircraftType ?: next.aircraftType,
                        registration = match.registration ?: next.registration,
                        icao24 = match.icao24 ?: next.icao24,
                        callsign = match.callsign ?: next.callsign,
                        lastStatusSource = "aerodatabox",
                    )
                }
            }
        }

        val airborne = resolvePollPhase(next.toPollInput()) == PollPhase.AIRBORNE
        var gotFix = false
        if (airborne) {
            val states = providers.fetchOpenSky(keys, icao24 = next.icao24)
            val state = states.firstOrNull { it.icao24 == next.icao24?.lowercase() }
                ?: states.firstOrNull { cs ->
                    val call = cs.callsign?.replace(" ", "")?.uppercase()
                    val want = listOfNotNull(next.callsign, next.flightNumber).map { it.replace(" ", "").uppercase() }
                    call != null && want.any { call.startsWith(it) || it.startsWith(call) }
                }
            if (state != null) {
                val squawk = normalizeSquawk(state.squawk)
                val status = if (state.onGround) {
                    groundStatusOrKeep(next)
                } else {
                    FlightStatus.EN_ROUTE
                }
                persistFix(
                    next.id,
                    LiveFix(
                        lat = state.lat, lon = state.lon, altitudeFt = state.altitudeFt,
                        velocityKts = state.velocityKts, heading = state.heading,
                        verticalRateFpm = state.verticalRateFpm, onGround = state.onGround,
                        icao24 = state.icao24, callsign = state.callsign, squawk = squawk, source = "opensky",
                    ),
                )
                next = next.copy(
                    status = status,
                    icao24 = state.icao24,
                    callsign = state.callsign ?: next.callsign,
                    lastLat = state.lat,
                    lastLon = state.lon,
                    lastAltitudeFt = state.altitudeFt,
                    lastVelocityKts = state.velocityKts,
                    lastHeading = state.heading,
                    lastVerticalRateFpm = state.verticalRateFpm ?: next.lastVerticalRateFpm,
                    lastOnGround = state.onGround,
                    lastPositionAt = System.currentTimeMillis(),
                    lastSquawk = squawk ?: next.lastSquawk,
                    lastStatusSource = "opensky",
                )
                gotFix = true
            } else {
                val live = providers.lookupAeroLive(keys, next.flightNumber, next.scheduledDep.toLocalDate(ZoneOffset.UTC))
                val liveLat = live?.liveLat ?: live?.fromLat
                val liveLon = live?.liveLon ?: live?.fromLon
                if (liveLat != null && liveLon != null) {
                    persistFix(
                        next.id,
                        LiveFix(lat = liveLat, lon = liveLon, source = "aerodatabox", icao24 = live?.icao24, callsign = live?.callsign),
                    )
                    next = next.copy(
                        status = if (!isTerminalStatus(next.status)) FlightStatus.EN_ROUTE else next.status,
                        lastLat = liveLat,
                        lastLon = liveLon,
                        lastPositionAt = System.currentTimeMillis(),
                        icao24 = live?.icao24 ?: next.icao24,
                        lastStatusSource = "aerodatabox",
                    )
                    gotFix = true
                }
            }
            val stale = next.lastPositionAt == null || System.currentTimeMillis() - next.lastPositionAt!! > LIVE_FIX_STALE_MS
            if (!gotFix && stale) {
                val fr24 = providers.fetchFr24(keys, next.flightNumber, next.callsign, next.icao24, next.lastLat, next.lastLon)
                if (fr24 != null) {
                    persistFix(next.id, fr24)
                    next = next.copy(
                        status = if (fr24.onGround) groundStatusOrKeep(next) else FlightStatus.EN_ROUTE,
                        lastLat = fr24.lat,
                        lastLon = fr24.lon,
                        lastAltitudeFt = fr24.altitudeFt,
                        lastVelocityKts = fr24.velocityKts,
                        lastHeading = fr24.heading,
                        lastVerticalRateFpm = fr24.verticalRateFpm ?: next.lastVerticalRateFpm,
                        lastOnGround = fr24.onGround,
                        lastPositionAt = fr24.observedAt,
                        icao24 = fr24.icao24 ?: next.icao24,
                        callsign = fr24.callsign ?: next.callsign,
                        lastSquawk = normalizeSquawk(fr24.squawk) ?: next.lastSquawk,
                        lastStatusSource = "fr24",
                        paintedAs = fr24.paintedAs ?: next.paintedAs,
                        operatingAs = fr24.operatingAs ?: next.operatingAs,
                        fr24Id = fr24.fr24Id ?: next.fr24Id,
                        fr24Eta = fr24.eta ?: next.fr24Eta,
                        registration = next.registration ?: fr24.registration,
                        aircraftType = next.aircraftType ?: fr24.aircraftType,
                        destIataActual = fr24.destIata ?: next.destIataActual,
                    )
                }
            }
            if (!isTerminalStatus(next.status)) {
                val now = System.currentTimeMillis()
                val arrived = hasActuallyArrived(
                    status = next.status,
                    scheduledDep = next.scheduledDep,
                    estimatedDep = next.estimatedDep,
                    actualDep = next.actualDep,
                    scheduledArr = next.scheduledArr,
                    estimatedArr = next.estimatedArr,
                    actualArr = next.actualArr,
                    now = now,
                )
                if (arrived) {
                    next = next.copy(status = if (next.toIata != prev.toIata) FlightStatus.DIVERTED else FlightStatus.LANDED)
                } else if (next.lastOnGround == true && landingWindowOpen(next, now)) {
                    next = next.copy(status = statusAfterGroundFix(next.status))
                }
            }
        }

        next = runCatching { insights.enrich(next, keys) }.getOrDefault(next)
        val interval = intervalForFlight(next.toPollInput())
        next = next.copy(
            pollPhase = resolvePollPhase(next.toPollInput()),
            nextPollAt = interval?.let { System.currentTimeMillis() + it },
            updatedAt = System.currentTimeMillis(),
        )
        repo.upsertFlight(next)
        alerts.dispatchFlightChanges(prev, next, prefs)
        alerts.dispatchTimeReminders(next, prefs)
        return PollOutcome(next.id, prev.status != next.status || prev.gate != next.gate || prev.lastSquawk != next.lastSquawk, next.status)
    }

    private fun landingWindowOpen(row: FlightEntity, now: Long): Boolean {
        val dep = row.actualDep ?: row.estimatedDep ?: row.scheduledDep
        if (now < dep) return false
        val minBlock = minPlausibleFlightMs(
            row.scheduledDep, row.estimatedDep, row.actualDep, row.scheduledArr, row.estimatedArr,
        )
        return now >= dep + minBlock
    }

    /** On-ground at the gate before / just after dep is not a landing. */
    private fun groundStatusOrKeep(row: FlightEntity): FlightStatus {
        val now = System.currentTimeMillis()
        if (!landingWindowOpen(row, now)) {
            return resolveDisplayStatus(
                status = row.status,
                scheduledDep = row.scheduledDep,
                estimatedDep = row.estimatedDep,
                actualDep = row.actualDep,
                scheduledArr = row.scheduledArr,
                estimatedArr = row.estimatedArr,
                actualArr = row.actualArr,
                delayMinutes = row.delayMinutes,
                now = now,
            )
        }
        return statusAfterGroundFix(row.status)
    }

    private suspend fun persistFix(flightId: String, fix: LiveFix) {
        repo.savePosition(
            PositionEntity(
                flightId = flightId,
                lat = fix.lat,
                lon = fix.lon,
                altitudeFt = fix.altitudeFt,
                velocityKts = fix.velocityKts,
                heading = fix.heading,
                onGround = fix.onGround,
                source = fix.source,
                recordedAt = fix.observedAt,
            ),
        )
    }

    suspend fun pollTrackedObjects() {
        val keys = keysStore.snapshot()
        val prefs = prefsStore.flow.first()
        for (obj in repo.listObjects()) {
            val states = if (!obj.icao24.isNullOrBlank()) {
                providers.fetchOpenSky(keys, icao24 = obj.icao24)
            } else emptyList()
            val state = states.firstOrNull() ?: continue
            val airborne = !state.onGround
            val wasAirborne = obj.lastOnGround == false
            val wasGround = obj.lastOnGround == true || obj.lastOnGround == null
            var starts = obj.starts
            var landings = obj.landings
            if (wasGround && airborne) {
                starts += 1
                repo.insertObjectEvent(ObjectEventEntity(objectId = obj.id, kind = "airborne", at = System.currentTimeMillis(), lat = state.lat, lon = state.lon))
                if (prefs.objectAlerts) alerts.notifyObject(obj.callsign, airborne = true)
            } else if (wasAirborne && state.onGround) {
                landings += 1
                repo.insertObjectEvent(ObjectEventEntity(objectId = obj.id, kind = "landed", at = System.currentTimeMillis(), lat = state.lat, lon = state.lon))
                if (prefs.objectAlerts) alerts.notifyObject(obj.callsign, airborne = false)
            }
            val squawk = normalizeSquawk(state.squawk)
            if (prefs.squawkAlerts && shouldAlertEmergencySquawk(null, squawk) && isEmergencySquawk(squawk)) {
                alerts.notifyObjectSquawk(obj.callsign, squawk!!)
            }
            repo.upsertObject(
                obj.copy(
                    lastLat = state.lat,
                    lastLon = state.lon,
                    lastAltitudeFt = state.altitudeFt,
                    lastOnGround = state.onGround,
                    lastSeenAt = System.currentTimeMillis(),
                    starts = starts,
                    landings = landings,
                    icao24 = state.icao24,
                ),
            )
        }
    }

    suspend fun shouldKeepTracking(): Boolean {
        val flights = repo.listFlights()
        val active = flights.any { !isTerminalStatus(it.status) }
        val upcomingSoon = flights.any {
            !isTerminalStatus(it.status) && it.scheduledDep - System.currentTimeMillis() < 48L * 60 * 60 * 1000
        }
        return active || upcomingSoon || repo.listObjects().isNotEmpty()
    }

    suspend fun nextWakeDelayMs(): Long {
        val now = System.currentTimeMillis()
        val actives = repo.listFlights().filter { !isTerminalStatus(it.status) }
        if (actives.isEmpty()) return if (repo.listObjects().isEmpty()) 15 * 60_000L else 90_000L
        val next = actives.minOf { it.nextPollAt ?: now }
        val hot = actives.any {
            val phase = resolvePollPhase(it.toPollInput())
            phase == PollPhase.AIRBORNE || phase == PollPhase.PREFLIGHT ||
                it.scheduledDep - now <= PREFLIGHT_WINDOW_MS
        }
        val wait = (next - now).coerceAtLeast(if (hot) 10_000L else 60_000L)
        return wait.coerceAtMost(if (hot) 3 * 60_000L else 30 * 60_000L)
    }
}
