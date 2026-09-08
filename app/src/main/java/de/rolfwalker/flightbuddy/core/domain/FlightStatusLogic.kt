package de.rolfwalker.flightbuddy.core.domain

import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.model.LatLon
import de.rolfwalker.flightbuddy.core.model.PollPhase

val LIVE_STATUSES = setOf(FlightStatus.BOARDING, FlightStatus.DEPARTED, FlightStatus.EN_ROUTE)
val PAST_STATUSES = setOf(FlightStatus.LANDED, FlightStatus.CANCELLED, FlightStatus.DIVERTED)

fun isLiveStatus(status: FlightStatus) = status in LIVE_STATUSES
fun isPastStatus(status: FlightStatus) = status in PAST_STATUSES
fun isTerminalStatus(status: FlightStatus) =
    status == FlightStatus.LANDED || status == FlightStatus.CANCELLED || status == FlightStatus.DIVERTED

/** Home Kommend: not yet departed (includes Boarding / Verspätet on the ground). */
fun isUpcomingDisplay(status: FlightStatus) =
    status == FlightStatus.SCHEDULED ||
        status == FlightStatus.DELAYED ||
        status == FlightStatus.BOARDING ||
        status == FlightStatus.UNKNOWN

/** Home Live: departed, not arrived. */
fun isAirborneDisplay(status: FlightStatus) =
    status == FlightStatus.DEPARTED || status == FlightStatus.EN_ROUTE

/** First minutes after ATD/ETD — chip Gestartet, then Unterwegs. */
const val JUST_DEPARTED_MS = 20L * 60 * 1000

fun statusAfterGroundFix(current: FlightStatus): FlightStatus = when (current) {
    FlightStatus.CANCELLED -> current
    FlightStatus.DIVERTED -> FlightStatus.DIVERTED
    FlightStatus.EN_ROUTE, FlightStatus.DEPARTED, FlightStatus.BOARDING,
    FlightStatus.DELAYED, FlightStatus.UNKNOWN, FlightStatus.SCHEDULED -> FlightStatus.LANDED
    else -> current
}

fun mergeAeroFlightStatus(
    current: FlightStatus,
    aeroStatus: FlightStatus,
    destinationChanged: Boolean,
    actualArr: Long?,
    now: Long = System.currentTimeMillis(),
    scheduledDep: Long? = null,
    estimatedDep: Long? = null,
    actualDep: Long? = null,
    scheduledArr: Long? = null,
    estimatedArr: Long? = null,
    delayMinutes: Int? = null,
): FlightStatus {
    val arrived = if (scheduledDep != null) {
        hasActuallyArrived(
            status = aeroStatus,
            scheduledDep = scheduledDep,
            estimatedDep = estimatedDep,
            actualDep = actualDep,
            scheduledArr = scheduledArr,
            estimatedArr = estimatedArr,
            actualArr = actualArr,
            now = now,
        )
    } else {
        actualArr != null && actualArr <= now
    }
    if (aeroStatus == FlightStatus.CANCELLED) return FlightStatus.CANCELLED
    if (aeroStatus == FlightStatus.DIVERTED || destinationChanged) {
        if (arrived) return FlightStatus.DIVERTED
        if (aeroStatus == FlightStatus.LANDED && !arrived) {
            return if (current == FlightStatus.DEPARTED) FlightStatus.DEPARTED else FlightStatus.EN_ROUTE
        }
        if (aeroStatus == FlightStatus.DEPARTED) return FlightStatus.DEPARTED
        if (isLiveStatus(current) || current == FlightStatus.DELAYED) {
            return if (current == FlightStatus.DEPARTED) FlightStatus.DEPARTED else FlightStatus.EN_ROUTE
        }
        return FlightStatus.EN_ROUTE
    }
    if (aeroStatus == FlightStatus.LANDED && scheduledDep != null && !arrived) {
        return resolveDisplayStatus(
            status = current,
            scheduledDep = scheduledDep,
            estimatedDep = estimatedDep,
            actualDep = actualDep,
            scheduledArr = scheduledArr,
            estimatedArr = estimatedArr,
            actualArr = actualArr,
            delayMinutes = delayMinutes,
            now = now,
        )
    }
    if (aeroStatus != FlightStatus.UNKNOWN) return aeroStatus
    if (arrived && !isTerminalStatus(current)) return FlightStatus.LANDED
    return current
}

/** Minimum airborne time before a "landed" flag is believable when block time is unknown. */
private const val FALLBACK_MIN_BLOCK_MS = 90L * 60 * 1000
private const val ABSOLUTE_MIN_BLOCK_MS = 20L * 60 * 1000

internal fun effectiveDepartureMs(
    scheduledDep: Long,
    estimatedDep: Long?,
    actualDep: Long?,
    delayMinutes: Int? = null,
): Long = actualDep
    ?: estimatedDep
    ?: delayMinutes?.takeIf { it > 0 }?.let { scheduledDep + it * 60_000L }
    ?: scheduledDep

internal fun effectiveArrivalMs(
    scheduledArr: Long?,
    estimatedArr: Long?,
    actualArr: Long?,
): Long? = actualArr ?: estimatedArr ?: scheduledArr

internal fun scheduledBlockMs(
    scheduledDep: Long,
    estimatedDep: Long?,
    actualDep: Long?,
    scheduledArr: Long?,
    estimatedArr: Long?,
): Long? {
    val dep = scheduledDep
    val arr = scheduledArr ?: estimatedArr
    val fromSched = arr?.let { it - dep }?.takeIf { it >= ABSOLUTE_MIN_BLOCK_MS }
    if (fromSched != null) return fromSched
    val effDep = effectiveDepartureMs(scheduledDep, estimatedDep, actualDep)
    return estimatedArr?.let { it - effDep }?.takeIf { it >= ABSOLUTE_MIN_BLOCK_MS }
}

internal fun minPlausibleFlightMs(
    scheduledDep: Long,
    estimatedDep: Long?,
    actualDep: Long?,
    scheduledArr: Long?,
    estimatedArr: Long?,
): Long {
    val block = scheduledBlockMs(scheduledDep, estimatedDep, actualDep, scheduledArr, estimatedArr)
    return block?.let { (it * 0.4).toLong().coerceAtLeast(ABSOLUTE_MIN_BLOCK_MS) }
        ?: FALLBACK_MIN_BLOCK_MS
}

/**
 * True only when the aircraft has really arrived.
 * Stale AeroDataBox/ADS-B "landed" is ignored when departure is still ahead
 * or just happened and arrival is still in the future.
 */
fun hasActuallyArrived(
    status: FlightStatus,
    scheduledDep: Long,
    estimatedDep: Long?,
    actualDep: Long?,
    scheduledArr: Long?,
    estimatedArr: Long?,
    actualArr: Long?,
    now: Long,
): Boolean {
    val dep = effectiveDepartureMs(scheduledDep, estimatedDep, actualDep)
    if (now < dep) return false
    val minBlock = minPlausibleFlightMs(
        scheduledDep, estimatedDep, actualDep, scheduledArr, estimatedArr,
    )
    if (now < dep + minBlock) {
        return actualArr != null && actualArr <= now && actualArr >= dep
    }
    if (actualArr != null && actualArr <= now && actualArr >= dep) return true
    val eta = effectiveArrivalMs(scheduledArr, estimatedArr, actualArr)
    if (eta != null && eta >= dep && now >= eta) return true
    if (status == FlightStatus.LANDED) {
        if (eta != null && now < eta) return false
        return true
    }
    return false
}

/**
 * Display / widget status from times + duration, not a stale landed flag.
 * Pill and progress bar must use this — never raw [FlightStatus.LANDED] alone.
 */
fun resolveDisplayStatus(
    status: FlightStatus,
    scheduledDep: Long,
    estimatedDep: Long?,
    actualDep: Long?,
    scheduledArr: Long?,
    estimatedArr: Long?,
    actualArr: Long?,
    delayMinutes: Int? = null,
    now: Long = System.currentTimeMillis(),
): FlightStatus {
    if (status == FlightStatus.CANCELLED) return FlightStatus.CANCELLED
    val arrived = hasActuallyArrived(
        status = status,
        scheduledDep = scheduledDep,
        estimatedDep = estimatedDep,
        actualDep = actualDep,
        scheduledArr = scheduledArr,
        estimatedArr = estimatedArr,
        actualArr = actualArr,
        now = now,
    )
    if (arrived) {
        return if (status == FlightStatus.DIVERTED) FlightStatus.DIVERTED else FlightStatus.LANDED
    }
    if (status == FlightStatus.DIVERTED) return FlightStatus.DIVERTED
    val dep = effectiveDepartureMs(scheduledDep, estimatedDep, actualDep, delayMinutes)
    val departed = (actualDep != null && actualDep <= now) || now >= dep
    if (departed) {
        return if (now - dep < JUST_DEPARTED_MS) FlightStatus.DEPARTED else FlightStatus.EN_ROUTE
    }
    if (status == FlightStatus.BOARDING) return FlightStatus.BOARDING
    val delayed = status == FlightStatus.DELAYED ||
        (delayMinutes ?: 0) > 0 ||
        ((estimatedDep ?: actualDep)?.let { it > scheduledDep } == true)
    if (delayed) return FlightStatus.DELAYED
    if (status == FlightStatus.LANDED || status == FlightStatus.UNKNOWN) return FlightStatus.SCHEDULED
    return status
}

fun mapProviderStatus(raw: String?): FlightStatus {
    if (raw.isNullOrBlank()) return FlightStatus.UNKNOWN
    val s = raw.lowercase()
    if (s.contains("cancel")) return FlightStatus.CANCELLED
    if (s.contains("divert")) return FlightStatus.DIVERTED
    if (s.contains("approach") || s.contains("taxi")) return FlightStatus.EN_ROUTE
    if (s.contains("landed") || s.contains("arrived")) return FlightStatus.LANDED
    if (s.contains("enroute") || s.contains("en route") || s.contains("airborne") || s.contains("active")) {
        return FlightStatus.EN_ROUTE
    }
    if (s.contains("depart") || s.contains("takeoff") || s.contains("gate departure")) return FlightStatus.DEPARTED
    if (s.contains("board")) return FlightStatus.BOARDING
    if (s.contains("delay")) return FlightStatus.DELAYED
    if (s.contains("schedul") || s.contains("expected") || s.contains("on time")) return FlightStatus.SCHEDULED
    return FlightStatus.UNKNOWN
}

data class PollInput(
    val status: FlightStatus,
    val scheduledDep: Long,
    val actualDep: Long? = null,
    val estimatedDep: Long? = null,
    val scheduledArr: Long? = null,
    val estimatedArr: Long? = null,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val lastPositionAt: Long? = null,
    val actualArr: Long? = null,
    val origin: LatLon? = null,
    val dest: LatLon? = null,
)

private const val INACTIVE_MS = 8L * 60 * 60 * 1000
private const val PREFLIGHT_FAR_MS = 15L * 60 * 1000
private const val PREFLIGHT_CLOSE_MS = 3L * 60 * 1000
private const val PREFLIGHT_CLOSE_WINDOW_MS = 45L * 60 * 1000
private const val CLIMB_HOT_WINDOW_MS = 10L * 60 * 1000
private const val CLIMB_EDGE_WINDOW_MS = 20L * 60 * 1000
private const val APPROACH_HOT_WINDOW_MS = 10L * 60 * 1000
private const val APPROACH_EDGE_WINDOW_MS = 20L * 60 * 1000
private const val CRUISE_MS = 3L * 60 * 1000
private const val AIRBORNE_HOT_MS = 10_000L
private const val AIRBORNE_EDGE_MS = 30_000L
const val PREFLIGHT_WINDOW_MS = 2L * 60 * 60 * 1000
const val LIVE_FIX_STALE_MS = 3L * 60 * 1000

fun resolvePollPhase(input: PollInput, now: Long = System.currentTimeMillis()): PollPhase {
    if (isTerminalStatus(input.status)) return PollPhase.COMPLETE
    val actualDep = input.actualDep
    val scheduledDep = input.scheduledDep
    if (input.status == FlightStatus.EN_ROUTE ||
        input.status == FlightStatus.DEPARTED ||
        (actualDep != null && actualDep <= now) ||
        scheduledDep <= now
    ) {
        return PollPhase.AIRBORNE
    }
    if (scheduledDep - now <= PREFLIGHT_WINDOW_MS) return PollPhase.PREFLIGHT
    return PollPhase.INACTIVE
}

private enum class AirborneTier { HOT, EDGE, CRUISE }

fun intervalForFlight(input: PollInput, now: Long = System.currentTimeMillis()): Long? {
    return when (val phase = resolvePollPhase(input, now)) {
        PollPhase.COMPLETE -> null
        PollPhase.INACTIVE -> INACTIVE_MS
        PollPhase.PREFLIGHT -> {
            if (input.scheduledDep - now <= PREFLIGHT_CLOSE_WINDOW_MS) PREFLIGHT_CLOSE_MS else PREFLIGHT_FAR_MS
        }
        PollPhase.AIRBORNE -> when (airborneTier(input, now)) {
            AirborneTier.HOT -> AIRBORNE_HOT_MS
            AirborneTier.EDGE -> AIRBORNE_EDGE_MS
            AirborneTier.CRUISE -> CRUISE_MS
        }
    }
}

private fun airborneTier(flight: PollInput, now: Long): AirborneTier {
    val origin = flight.origin
    val dest = flight.dest
    val lastFix = if (flight.lastLat != null && flight.lastLon != null) {
        LatLon(flight.lastLat, flight.lastLon)
    } else null
    val current = interpolateAirbornePosition(flight, lastFix, now).position
    val progress = flightProgress(
        origin, dest, current,
        flight.scheduledDep, flight.scheduledArr,
        flight.estimatedDep, flight.estimatedArr,
        flight.actualDep, flight.actualArr, now,
    )
    val dep = flight.actualDep ?: flight.estimatedDep ?: flight.scheduledDep
    val msSinceDep = now - dep
    val eta = flight.estimatedArr ?: flight.scheduledArr
    val msToEta = eta?.let { it - now }

    var climb = AirborneTier.CRUISE
    if (msSinceDep >= 0) {
        climb = when {
            msSinceDep <= CLIMB_HOT_WINDOW_MS -> AirborneTier.HOT
            msSinceDep <= CLIMB_EDGE_WINDOW_MS -> AirborneTier.EDGE
            else -> AirborneTier.CRUISE
        }
    } else if (progress < 0.1) climb = AirborneTier.HOT
    else if (progress < 0.2) climb = AirborneTier.EDGE

    var approach = AirborneTier.CRUISE
    if (msToEta != null) {
        approach = when {
            msToEta <= APPROACH_HOT_WINDOW_MS -> AirborneTier.HOT
            msToEta <= APPROACH_EDGE_WINDOW_MS -> AirborneTier.EDGE
            else -> AirborneTier.CRUISE
        }
    } else if (progress >= 0.9) approach = AirborneTier.HOT
    else if (progress >= 0.8) approach = AirborneTier.EDGE

    return if (climb == AirborneTier.HOT || approach == AirborneTier.HOT) AirborneTier.HOT
    else if (climb == AirborneTier.EDGE || approach == AirborneTier.EDGE) AirborneTier.EDGE
    else AirborneTier.CRUISE
}

data class Interpolated(val position: LatLon?, val estimated: Boolean)

fun interpolateAirbornePosition(
    flight: PollInput,
    lastFix: LatLon?,
    now: Long,
): Interpolated {
    val origin = flight.origin
    val dest = flight.dest
    if (isTerminalStatus(flight.status)) {
        return Interpolated(dest ?: lastFix, false)
    }
    val lastAt = flight.lastPositionAt
    if (lastFix != null && lastAt != null && now - lastAt <= LIVE_FIX_STALE_MS) {
        return Interpolated(lastFix, false)
    }
    if (origin == null || dest == null) return Interpolated(lastFix, lastFix != null)
    val start = flight.actualDep ?: flight.estimatedDep ?: flight.scheduledDep
    val end = flight.actualArr ?: flight.estimatedArr ?: flight.scheduledArr ?: (start + 2 * 60 * 60 * 1000)
    val span = (end - start).coerceAtLeast(1)
    val f = ((now - start).toDouble() / span).coerceIn(0.0, 0.98)
    val from = lastFix ?: origin
    val remaining = if (lastFix != null && lastAt != null) {
        val leftover = ((end - lastAt).toDouble() / (end - start).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val advanced = 1.0 - leftover * (1.0 - ((now - lastAt).toDouble() / (end - lastAt).coerceAtLeast(1)).coerceIn(0.0, 1.0))
        advanced.coerceIn(0.0, 0.98)
    } else f
    return Interpolated(pointAlongGreatCircle(from, dest, remaining), true)
}

fun parseFlightQuery(raw: String): FlightQuery {
    val q = raw.trim().uppercase().replace(Regex("\\s+"), "")
    val route = Regex("^([A-Z]{3})[-–>]([A-Z]{3})$").find(q)
    if (route != null) return FlightQuery.Route(route.groupValues[1], route.groupValues[2])
    val flight = Regex("^([A-Z0-9]{2}|[A-Z]{3})[-–]?(\\d{1,4}[A-Z]?)$").find(q)
    if (flight != null) {
        val number = flight.groupValues[2].replace(Regex("^0+(?=\\d)"), "")
        return FlightQuery.Number(flight.groupValues[1] + number, flight.groupValues[1])
    }
    return FlightQuery.Unknown(q)
}

sealed class FlightQuery {
    data class Number(val flightNumber: String, val airline: String) : FlightQuery()
    data class Route(val from: String, val to: String) : FlightQuery()
    data class Unknown(val raw: String) : FlightQuery()
}

fun displayFlightNumber(code: String): String {
    val m = Regex("^([A-Z]{2,3})(\\d.*)$").find(code.uppercase())
    return if (m != null) "${m.groupValues[1]} ${m.groupValues[2]}" else code.uppercase()
}

fun airlineLogoUrl(iata: String?): String? {
    val code = iata?.trim()?.uppercase().orEmpty()
    if (code.length !in 2..3) return null
    // Kiwi square mark (tail / brand icon). Not the wide pics.avs.io wordmark.
    return "https://images.kiwi.com/airlines/128x128/$code.png"
}

/** IATA for the airline mark URL — stored code, else 2-letter prefix of the flight number. */
fun airlineCodeForLogo(iata: String?, flightNumber: String?): String? {
    val fromIata = iata?.trim()?.uppercase().orEmpty()
    if (fromIata.length in 2..3) return fromIata
    val number = flightNumber?.trim()?.uppercase().orEmpty()
    return Regex("^([A-Z]{2})\\d").find(number)?.groupValues?.get(1)
}

fun airlineInitials(iata: String?, name: String?): String {
    val code = iata?.trim()?.uppercase().orEmpty()
    if (code.length >= 2) return code.take(2)
    if (!name.isNullOrBlank()) {
        return name.trim().split(Regex("\\s+")).take(2).joinToString("") { it.firstOrNull()?.uppercase() ?: "" }
    }
    return "?"
}

fun normalizeSquawk(value: String?): String? {
    val raw = value?.trim()?.replace(Regex("\\s+"), "") ?: return null
    return if (Regex("^[0-7]{4}$").matches(raw)) raw else null
}

fun isEmergencySquawk(code: String?) = code == "7500" || code == "7600" || code == "7700"

fun shouldAlertEmergencySquawk(previous: String?, next: String?) =
    isEmergencySquawk(next) && previous != next

fun connectionBetween(arriveAt: Long?, departAt: Long?, fromIata: String?, toIata: String?): de.rolfwalker.flightbuddy.core.model.ConnectionInfo? {
    if (arriveAt == null || departAt == null) return null
    val layoverMin = ((departAt - arriveAt) / 60_000L).toInt()
    val level = when {
        layoverMin < 0 -> de.rolfwalker.flightbuddy.core.model.ConnectionLevel.MISSED
        layoverMin < 45 -> de.rolfwalker.flightbuddy.core.model.ConnectionLevel.TIGHT
        layoverMin < 90 -> de.rolfwalker.flightbuddy.core.model.ConnectionLevel.OK
        else -> de.rolfwalker.flightbuddy.core.model.ConnectionLevel.COMFORTABLE
    }
    return de.rolfwalker.flightbuddy.core.model.ConnectionInfo(layoverMin, level, fromIata, toIata)
}
