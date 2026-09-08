package de.rolfwalker.flightbuddy.core.ui.status

import android.content.Context
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.domain.displayFlightNumber
import de.rolfwalker.flightbuddy.core.domain.flightProgress
import de.rolfwalker.flightbuddy.core.domain.haversineNm
import de.rolfwalker.flightbuddy.core.domain.hasActuallyArrived
import de.rolfwalker.flightbuddy.core.domain.resolveDisplayStatus
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.model.LatLon
import de.rolfwalker.flightbuddy.core.ui.isLegDelayed
import de.rolfwalker.flightbuddy.core.ui.resolveLegTimes
import kotlin.math.max

/** Pre-departure fill window: empty until T−4h, full at departure. */
const val PREFLIGHT_BAR_WINDOW_MS = 4L * 60 * 60 * 1000

enum class FlightStatusBarKind { PREFLIGHT, INFLIGHT, LANDED, HIDDEN }

/** Marker on the status bar. Preflight uses a process icon; airborne/landed keep the plane. */
enum class FlightProgressMarker { PLANE, LUGGAGE, SCHEDULE }

data class FlightStatusBarState(
    val kind: FlightStatusBarKind,
    val percent: Int,
    val remainingMs: Long? = null,
)

data class FlightStatusLegClock(
    val effective: String,
    val scheduled: String,
    val delayed: Boolean,
)

/**
 * Google Flights status card — shared by home list (Compose) and the 4×2/5×2 widget.
 */
data class FlightStatusCardModel(
    val airlineFlight: String,
    val cityRoute: String,
    val freshness: String?,
    val chip: FlightStatus,
    val countdown: String,
    val fromIata: String,
    val toIata: String,
    /** False while the 4h preflight bar is showing — no von/nach IATA beside the track. */
    val showRouteIata: Boolean,
    val progressMarker: FlightProgressMarker,
    val progress: Int,
    val dep: FlightStatusLegClock,
    val arr: FlightStatusLegClock,
    val weekday: String?,
    val depStand: String?,
    val arrStand: String?,
)

fun flightStatusCardModel(
    context: Context,
    f: FlightEntity,
    now: Long = System.currentTimeMillis(),
    showFreshness: Boolean,
    showWeekday: Boolean,
    showBaggage: Boolean,
): FlightStatusCardModel {
    val shown = displayFlightStatus(f, now)
    val chip = flightStatusChip(f, shown)
    val bar = statusBarForFlight(f, now)
    return FlightStatusCardModel(
        airlineFlight = airlineFlightLine(f),
        cityRoute = cityRouteTitle(context, f),
        freshness = if (showFreshness) freshnessLabel(context, f, now) else null,
        chip = chip,
        countdown = flightStatusCountdown(context, bar),
        fromIata = f.fromIata?.trim().orEmpty().ifBlank { "––" },
        toIata = f.toIata?.trim().orEmpty().ifBlank { "––" },
        showRouteIata = showsRouteIata(bar),
        progressMarker = progressMarkerFor(bar, chip),
        progress = bar.percent.coerceIn(0, 100),
        dep = legClock(f.scheduledDep, f.estimatedDep, f.actualDep, f.delayMinutes, shown),
        arr = legClock(f.scheduledArr, f.estimatedArr, f.actualArr, null, shown),
        weekday = if (showWeekday) DateTimeFmt.weekdayDate(f.scheduledDep) else null,
        depStand = standBits(context, f.terminal, f.gate),
        arrStand = arrStandLine(context, f, showBaggage),
    )
}

/**
 * Chip from **now vs times**, not a frozen API "Delayed" string.
 * Verspätet only while still on the ground. After takeoff: Gestartet / Unterwegs.
 * Delay stays on the orange clocks.
 */
fun flightStatusChip(f: FlightEntity, shown: FlightStatus = displayFlightStatus(f)): FlightStatus {
    return when (shown) {
        FlightStatus.LANDED,
        FlightStatus.CANCELLED,
        FlightStatus.DIVERTED,
        FlightStatus.DEPARTED,
        FlightStatus.EN_ROUTE,
        FlightStatus.BOARDING,
        -> shown
        else -> if (flightIsDelayed(f)) FlightStatus.DELAYED else shown
    }
}

fun flightIsDelayed(f: FlightEntity): Boolean {
    if ((f.delayMinutes ?: 0) > 0) return true
    val dep = resolveLegTimes(f.scheduledDep, f.estimatedDep, f.actualDep)
    val arr = resolveLegTimes(f.scheduledArr, f.estimatedArr, f.actualArr)
    return isLegDelayed(dep, f.status == FlightStatus.DELAYED) ||
        isLegDelayed(arr, false)
}

fun airlineFlightLine(f: FlightEntity): String {
    val airline = f.airlineName?.trim()?.takeIf { it.isNotEmpty() }
        ?: f.airlineIata?.trim()?.takeIf { it.isNotEmpty() }
        ?: ""
    val number = displayFlightNumber(f.flightNumber)
    return if (airline.isNotEmpty()) "$airline · $number" else number
}

fun cityRouteTitle(context: Context, f: FlightEntity): String {
    val from = f.fromCity?.trim()?.takeIf { it.isNotEmpty() } ?: f.fromIata?.trim().orEmpty()
    val to = f.toCity?.trim()?.takeIf { it.isNotEmpty() } ?: f.toIata?.trim().orEmpty()
    if (from.isBlank() && to.isBlank()) return routeLine(f)
    return context.getString(R.string.widget_city_to, from.ifBlank { "––" }, to.ifBlank { "––" })
}

fun freshnessLabel(context: Context, f: FlightEntity, now: Long): String? {
    val at = listOfNotNull(f.lastPositionAt, f.updatedAt).maxOrNull() ?: return null
    val age = now - at
    if (age < 0) return null
    val minutes = (age / 60_000L).toInt()
    if (minutes <= 0) return context.getString(R.string.widget_updated_just)
    if (minutes < 60) return context.getString(R.string.widget_updated_min, minutes)
    val hours = minutes / 60
    return context.getString(R.string.widget_updated_hr, hours)
}

fun flightStatusCountdown(context: Context, bar: FlightStatusBarState): String = when (bar.kind) {
    FlightStatusBarKind.PREFLIGHT -> {
        val (hours, minutes) = hoursAndMinutes(bar.remainingMs ?: 0L)
        context.getString(R.string.widget_preflight_start, hours, minutes)
    }
    FlightStatusBarKind.INFLIGHT -> {
        val remaining = bar.remainingMs
        if (remaining != null) {
            val (hours, minutes) = hoursAndMinutes(remaining)
            context.getString(R.string.widget_arrival_in, hours, minutes)
        } else {
            context.getString(R.string.widget_progress, bar.percent)
        }
    }
    FlightStatusBarKind.LANDED -> context.getString(R.string.status_landed)
    FlightStatusBarKind.HIDDEN -> ""
}

fun flightStatusLabel(context: Context, status: FlightStatus): String = when (status) {
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

fun scheduledUnder(context: Context, planned: String): String =
    context.getString(R.string.widget_scheduled_under, planned)

fun standBits(context: Context, terminal: String?, gate: String?): String? {
    val parts = listOfNotNull(
        terminal?.trim()?.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.flight_terminal) + " " + it },
        gate?.trim()?.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.flight_gate) + " " + it },
    )
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
}

fun arrStandLine(context: Context, f: FlightEntity, showBaggage: Boolean): String? {
    val bits = listOfNotNull(
        standBits(context, f.arrivalTerminal, f.arrivalGate),
        if (showBaggage) {
            f.baggageBelt?.trim()?.takeIf { it.isNotEmpty() }?.let {
                context.getString(R.string.widget_baggage, it)
            }
        } else {
            null
        },
    )
    return bits.joinToString(" · ").takeIf { it.isNotEmpty() }
}

fun displayFlightStatus(f: FlightEntity, now: Long = System.currentTimeMillis()): FlightStatus =
    resolveDisplayStatus(
        status = f.status,
        scheduledDep = f.scheduledDep,
        estimatedDep = f.estimatedDep,
        actualDep = f.actualDep,
        scheduledArr = f.scheduledArr,
        estimatedArr = f.estimatedArr,
        actualArr = f.actualArr,
        delayMinutes = f.delayMinutes,
        now = now,
    )

fun statusClockOrDash(ms: Long?): String {
    if (ms == null) return "––"
    return DateTimeFmt.time(ms, DateTimeFmt.deviceZone())
}

fun routeLine(f: FlightEntity): String {
    val from = f.fromIata?.trim().orEmpty().ifBlank { "––" }
    val to = f.toIata?.trim().orEmpty().ifBlank { "––" }
    return "$from → $to"
}

fun hoursAndMinutes(remainingMs: Long): Pair<Int, Int> {
    val totalMin = (remainingMs.coerceAtLeast(0L) / 60_000L).toInt()
    return totalMin / 60 to totalMin % 60
}

/** Hide von/nach IATA while the aircraft is still on the 4h preflight bar. */
fun showsRouteIata(bar: FlightStatusBarState): Boolean =
    bar.kind != FlightStatusBarKind.PREFLIGHT

/**
 * Boarding → suitcase. Other on-ground preflight (Pünktlich, Verspätet) → clock.
 * After takeoff / landed → plane. The marker rides [FlightStatusBarState.percent].
 */
fun progressMarkerFor(bar: FlightStatusBarState, chip: FlightStatus): FlightProgressMarker {
    if (bar.kind != FlightStatusBarKind.PREFLIGHT) return FlightProgressMarker.PLANE
    return when (chip) {
        FlightStatus.BOARDING -> FlightProgressMarker.LUGGAGE
        else -> FlightProgressMarker.SCHEDULE
    }
}

fun progressMarkerDrawable(marker: FlightProgressMarker): Int = when (marker) {
    FlightProgressMarker.PLANE -> R.drawable.widget_plane
    FlightProgressMarker.LUGGAGE -> R.drawable.widget_luggage
    FlightProgressMarker.SCHEDULE -> R.drawable.widget_schedule
}

fun hasStatusDeparted(
    status: FlightStatus,
    actualDep: Long?,
    now: Long,
    scheduledDep: Long? = null,
    estimatedDep: Long? = null,
    scheduledArr: Long? = null,
    estimatedArr: Long? = null,
    actualArr: Long? = null,
): Boolean {
    if (status == FlightStatus.CANCELLED) return false
    if (scheduledDep != null &&
        hasActuallyArrived(
            status = status,
            scheduledDep = scheduledDep,
            estimatedDep = estimatedDep,
            actualDep = actualDep,
            scheduledArr = scheduledArr,
            estimatedArr = estimatedArr,
            actualArr = actualArr,
            now = now,
        )
    ) {
        return true
    }
    if (status == FlightStatus.DEPARTED ||
        status == FlightStatus.EN_ROUTE ||
        status == FlightStatus.DIVERTED
    ) {
        return true
    }
    if (status == FlightStatus.LANDED) return false
    if (actualDep != null && actualDep <= now) return true
    val dep = actualDep ?: estimatedDep ?: scheduledDep ?: return false
    return now >= dep
}

fun resolveStatusBar(
    status: FlightStatus,
    scheduledDep: Long,
    estimatedDep: Long?,
    actualDep: Long?,
    scheduledArr: Long?,
    estimatedArr: Long?,
    actualArr: Long?,
    flightPct: Int?,
    now: Long,
    delayMinutes: Int? = null,
): FlightStatusBarState {
    if (status == FlightStatus.CANCELLED) {
        return FlightStatusBarState(FlightStatusBarKind.HIDDEN, 0)
    }
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
        return FlightStatusBarState(FlightStatusBarKind.LANDED, 100)
    }
    val shown = resolveDisplayStatus(
        status = status,
        scheduledDep = scheduledDep,
        estimatedDep = estimatedDep,
        actualDep = actualDep,
        scheduledArr = scheduledArr,
        estimatedArr = estimatedArr,
        actualArr = actualArr,
        delayMinutes = delayMinutes,
        now = now,
    )
    if (hasStatusDeparted(
            status = shown,
            actualDep = actualDep,
            now = now,
            scheduledDep = scheduledDep,
            estimatedDep = estimatedDep,
            scheduledArr = scheduledArr,
            estimatedArr = estimatedArr,
            actualArr = actualArr,
        )
    ) {
        val eta = estimatedArr ?: actualArr ?: scheduledArr
        return FlightStatusBarState(
            kind = FlightStatusBarKind.INFLIGHT,
            percent = (flightPct ?: 0).coerceIn(0, 99),
            remainingMs = eta?.let { it - now },
        )
    }
    val times = resolveLegTimes(scheduledDep, estimatedDep, actualDep)
    val dep = times.effective ?: times.planned ?: scheduledDep
    val remaining = dep - now
    val percent = when {
        remaining >= PREFLIGHT_BAR_WINDOW_MS -> 0
        remaining <= 0L -> 100
        else -> {
            val filled = PREFLIGHT_BAR_WINDOW_MS - remaining
            ((filled.toDouble() / PREFLIGHT_BAR_WINDOW_MS) * 100.0).toInt().coerceIn(0, 100)
        }
    }
    return FlightStatusBarState(FlightStatusBarKind.PREFLIGHT, percent, remaining.coerceAtLeast(0L))
}

fun flightProgressPercent(f: FlightEntity, now: Long = System.currentTimeMillis()): Int? {
    val shown = displayFlightStatus(f, now)
    if (shown == FlightStatus.CANCELLED) return null
    if (shown == FlightStatus.LANDED) return 100
    val origin = f.fromLat?.let { lat -> f.fromLon?.let { lon -> LatLon(lat, lon) } }
    val dest = f.toLat?.let { lat -> f.toLon?.let { lon -> LatLon(lat, lon) } }
    val lastFix = f.lastLat?.let { lat -> f.lastLon?.let { lon -> LatLon(lat, lon) } }
    val timeRaw = flightProgress(
        origin = origin,
        dest = dest,
        current = null,
        scheduledDep = f.scheduledDep,
        scheduledArr = f.scheduledArr,
        estimatedDep = f.estimatedDep,
        estimatedArr = f.estimatedArr,
        actualDep = f.actualDep,
        actualArr = f.actualArr,
        now = now,
    )
    val geoRaw = airborneGeoProgress(origin, dest, lastFix, f, now)
    val airborne = shown == FlightStatus.DEPARTED ||
        shown == FlightStatus.EN_ROUTE ||
        shown == FlightStatus.DIVERTED
    val raw = if (airborne) max(geoRaw ?: 0.0, timeRaw) else timeRaw
    return (raw * 100).toInt().coerceIn(0, 99)
}

/** Use a live fix only when it has actually left the origin — otherwise time moves the plane. */
private fun airborneGeoProgress(
    origin: LatLon?,
    dest: LatLon?,
    lastFix: LatLon?,
    f: FlightEntity,
    now: Long,
): Double? {
    if (origin == null || dest == null || lastFix == null) return null
    val total = haversineNm(origin, dest)
    if (total <= 1.0) return null
    val gone = haversineNm(origin, lastFix)
    if (gone / total < 0.03) return null
    return flightProgress(
        origin = origin,
        dest = dest,
        current = lastFix,
        scheduledDep = f.scheduledDep,
        scheduledArr = f.scheduledArr,
        estimatedDep = f.estimatedDep,
        estimatedArr = f.estimatedArr,
        actualDep = f.actualDep,
        actualArr = f.actualArr,
        now = now,
    )
}

fun statusBarForFlight(f: FlightEntity, now: Long = System.currentTimeMillis()): FlightStatusBarState {
    val shown = displayFlightStatus(f, now)
    return resolveStatusBar(
        status = shown,
        scheduledDep = f.scheduledDep,
        estimatedDep = f.estimatedDep,
        actualDep = f.actualDep,
        scheduledArr = f.scheduledArr,
        estimatedArr = f.estimatedArr,
        actualArr = f.actualArr,
        flightPct = flightProgressPercent(f, now),
        now = now,
        delayMinutes = f.delayMinutes,
    )
}

private fun legClock(
    scheduled: Long?,
    estimated: Long?,
    actual: Long?,
    delayMinutes: Int?,
    shown: FlightStatus,
): FlightStatusLegClock {
    val times = resolveLegTimes(scheduled, estimated, actual)
    val delayed = (delayMinutes ?: 0) > 0 ||
        shown == FlightStatus.DELAYED ||
        isLegDelayed(times, shown == FlightStatus.DELAYED)
    return FlightStatusLegClock(
        effective = statusClockOrDash(times.effective ?: times.planned),
        scheduled = statusClockOrDash(times.planned),
        delayed = delayed,
    )
}
