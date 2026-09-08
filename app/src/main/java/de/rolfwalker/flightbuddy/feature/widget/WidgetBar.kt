package de.rolfwalker.flightbuddy.feature.widget

import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.ui.resolveLegTimes

/** Pre-departure fill window: empty until T−4h, full at departure. */
internal const val PREFLIGHT_BAR_WINDOW_MS = 4L * 60 * 60 * 1000

internal enum class WidgetBarKind { PREFLIGHT, INFLIGHT, LANDED, HIDDEN }

internal data class WidgetBarState(
    val kind: WidgetBarKind,
    val percent: Int,
    val remainingMs: Long? = null,
)

internal fun hasWidgetDeparted(
    status: FlightStatus,
    actualDep: Long?,
    now: Long,
): Boolean {
    if (status == FlightStatus.DEPARTED ||
        status == FlightStatus.EN_ROUTE ||
        status == FlightStatus.DIVERTED
    ) {
        return true
    }
    if (status == FlightStatus.LANDED) return true
    return actualDep != null && actualDep <= now
}

internal fun hoursAndMinutes(remainingMs: Long): Pair<Int, Int> {
    val totalMin = (remainingMs.coerceAtLeast(0L) / 60_000L).toInt()
    return totalMin / 60 to totalMin % 60
}

internal fun resolveWidgetBar(
    status: FlightStatus,
    scheduledDep: Long,
    estimatedDep: Long?,
    actualDep: Long?,
    scheduledArr: Long?,
    estimatedArr: Long?,
    actualArr: Long?,
    flightPct: Int?,
    now: Long,
): WidgetBarState {
    if (status == FlightStatus.CANCELLED) {
        return WidgetBarState(WidgetBarKind.HIDDEN, 0)
    }
    val arrived = status == FlightStatus.LANDED || (actualArr != null && actualArr <= now)
    if (arrived) {
        return WidgetBarState(WidgetBarKind.LANDED, 100)
    }
    if (hasWidgetDeparted(status, actualDep, now)) {
        val eta = estimatedArr ?: actualArr ?: scheduledArr
        return WidgetBarState(
            kind = WidgetBarKind.INFLIGHT,
            percent = (flightPct ?: 0).coerceIn(0, 100),
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
    return WidgetBarState(WidgetBarKind.PREFLIGHT, percent, remaining.coerceAtLeast(0L))
}
