package de.rolfwalker.flightbuddy.feature.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.ui.status.FlightStatusBarState
import de.rolfwalker.flightbuddy.core.ui.status.FlightStatusCardModel
import de.rolfwalker.flightbuddy.core.ui.status.FlightStatusLegClock
import de.rolfwalker.flightbuddy.core.ui.status.airlineFlightLine as sharedAirlineFlightLine
import de.rolfwalker.flightbuddy.core.ui.status.arrStandLine as sharedArrStandLine
import de.rolfwalker.flightbuddy.core.ui.status.cityRouteTitle as sharedCityRouteTitle
import de.rolfwalker.flightbuddy.core.ui.status.displayFlightStatus
import de.rolfwalker.flightbuddy.core.ui.status.flightIsDelayed as sharedFlightIsDelayed
import de.rolfwalker.flightbuddy.core.ui.status.flightStatusCardModel
import de.rolfwalker.flightbuddy.core.ui.status.flightStatusChip
import de.rolfwalker.flightbuddy.core.ui.status.flightStatusCountdown
import de.rolfwalker.flightbuddy.core.ui.status.freshnessLabel as sharedFreshnessLabel
import de.rolfwalker.flightbuddy.core.ui.status.scheduledUnder as sharedScheduledUnder
import de.rolfwalker.flightbuddy.core.ui.status.standBits as sharedStandBits

internal typealias WidgetLegClock = FlightStatusLegClock
internal typealias WidgetCardModel = FlightStatusCardModel

internal fun widgetCardModel(
    context: Context,
    f: FlightEntity,
    now: Long = System.currentTimeMillis(),
    showFreshness: Boolean,
    showWeekday: Boolean,
    showBaggage: Boolean,
): WidgetCardModel = flightStatusCardModel(
    context = context,
    f = f,
    now = now,
    showFreshness = showFreshness,
    showWeekday = showWeekday,
    showBaggage = showBaggage,
)

/** Google Flights chip: Verspätet while delayed, never Gelandet until real arrival. */
internal fun widgetChipStatus(f: FlightEntity, shown: FlightStatus = displayStatus(f)): FlightStatus =
    flightStatusChip(f, shown)

internal fun flightIsDelayed(f: FlightEntity): Boolean = sharedFlightIsDelayed(f)

internal fun airlineFlightLine(f: FlightEntity): String = sharedAirlineFlightLine(f)

internal fun cityRouteTitle(context: Context, f: FlightEntity): String = sharedCityRouteTitle(context, f)

internal fun freshnessLabel(context: Context, f: FlightEntity, now: Long): String? =
    sharedFreshnessLabel(context, f, now)

internal fun widgetCountdown(context: Context, bar: FlightStatusBarState): String =
    flightStatusCountdown(context, bar)

internal fun widgetBarLabel(context: Context, bar: FlightStatusBarState): String = widgetCountdown(context, bar)

internal fun scheduledUnder(context: Context, planned: String): String = sharedScheduledUnder(context, planned)

internal fun standBits(context: Context, terminal: String?, gate: String?): String? =
    sharedStandBits(context, terminal, gate)

internal fun arrStandLine(context: Context, f: FlightEntity, showBaggage: Boolean): String? =
    sharedArrStandLine(context, f, showBaggage)

internal fun chipColorRes(status: FlightStatus): Int = when (status) {
    FlightStatus.DELAYED -> R.color.widget_delay
    FlightStatus.CANCELLED, FlightStatus.DIVERTED -> R.color.widget_error
    else -> R.color.widget_accent
}

internal fun chipBackgroundRes(status: FlightStatus): Int = when (status) {
    FlightStatus.DELAYED -> R.drawable.widget_pill_delay
    FlightStatus.CANCELLED, FlightStatus.DIVERTED -> R.drawable.widget_pill_error
    else -> R.drawable.widget_pill_bg
}

internal fun clockColorRes(delayed: Boolean): Int =
    if (delayed) R.color.widget_delay else R.color.widget_text

/** Plane / suitcase / clock: black on a light widget, white when the host is night. */
internal fun widgetMarkerColor(context: Context): Int {
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    return if (night) Color.WHITE else Color.BLACK
}
