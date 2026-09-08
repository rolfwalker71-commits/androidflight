package de.rolfwalker.flightbuddy.feature.widget

import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.ui.status.FlightStatusBarKind
import de.rolfwalker.flightbuddy.core.ui.status.FlightStatusBarState
import de.rolfwalker.flightbuddy.core.ui.status.PREFLIGHT_BAR_WINDOW_MS as SHARED_PREFLIGHT_BAR_WINDOW_MS
import de.rolfwalker.flightbuddy.core.ui.status.displayFlightStatus
import de.rolfwalker.flightbuddy.core.ui.status.hasStatusDeparted
import de.rolfwalker.flightbuddy.core.ui.status.hoursAndMinutes as sharedHoursAndMinutes
import de.rolfwalker.flightbuddy.core.ui.status.resolveStatusBar
import de.rolfwalker.flightbuddy.core.ui.status.statusBarForFlight

internal const val PREFLIGHT_BAR_WINDOW_MS = SHARED_PREFLIGHT_BAR_WINDOW_MS

internal typealias WidgetBarKind = FlightStatusBarKind
internal typealias WidgetBarState = FlightStatusBarState

internal fun displayStatus(f: FlightEntity, now: Long = System.currentTimeMillis()): FlightStatus =
    displayFlightStatus(f, now)

internal fun hasWidgetDeparted(
    status: FlightStatus,
    actualDep: Long?,
    now: Long,
    scheduledDep: Long? = null,
    estimatedDep: Long? = null,
    scheduledArr: Long? = null,
    estimatedArr: Long? = null,
    actualArr: Long? = null,
): Boolean = hasStatusDeparted(
    status = status,
    actualDep = actualDep,
    now = now,
    scheduledDep = scheduledDep,
    estimatedDep = estimatedDep,
    scheduledArr = scheduledArr,
    estimatedArr = estimatedArr,
    actualArr = actualArr,
)

internal fun hoursAndMinutes(remainingMs: Long): Pair<Int, Int> = sharedHoursAndMinutes(remainingMs)

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
    delayMinutes: Int? = null,
): WidgetBarState = resolveStatusBar(
    status = status,
    scheduledDep = scheduledDep,
    estimatedDep = estimatedDep,
    actualDep = actualDep,
    scheduledArr = scheduledArr,
    estimatedArr = estimatedArr,
    actualArr = actualArr,
    flightPct = flightPct,
    now = now,
    delayMinutes = delayMinutes,
)

internal fun widgetBarForFlight(f: FlightEntity, now: Long = System.currentTimeMillis()): WidgetBarState =
    statusBarForFlight(f, now)
