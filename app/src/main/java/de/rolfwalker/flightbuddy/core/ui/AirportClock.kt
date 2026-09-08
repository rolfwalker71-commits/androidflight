package de.rolfwalker.flightbuddy.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.DateTimeFmt
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import java.time.ZoneId

/**
 * Two-column ABFLUG / ANKUNFT clocks with shared row heights (PWA grid).
 * Both columns always render GEPLANT + EFFEKTIV so those rows stay aligned.
 * Visible times use the phone’s [ZoneId.systemDefault], not airport TZ.
 */
@Composable
fun AirportClockPair(
    depScheduled: Long?,
    depEstimated: Long?,
    depActual: Long?,
    arrScheduled: Long?,
    arrEstimated: Long?,
    arrActual: Long?,
    status: FlightStatus,
    language: String,
    variant: ClockVariant = ClockVariant.COMPACT,
    modifier: Modifier = Modifier,
) {
    val zone = DateTimeFmt.deviceZone()
    val dep = clockColumn(
        scheduled = depScheduled,
        estimated = depEstimated,
        actual = depActual,
        zone = zone,
        status = status,
        role = ClockRole.DEP,
    )
    val arr = clockColumn(
        scheduled = arrScheduled,
        estimated = arrEstimated,
        actual = arrActual,
        zone = zone,
        status = status,
        role = ClockRole.ARR,
    )
    val plannedStyle = if (variant == ClockVariant.DETAIL) {
        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
    } else {
        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    }
    val effectiveStyle = if (variant == ClockVariant.DETAIL) {
        MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
    } else {
        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val on = MaterialTheme.colorScheme.onSurface
    val delayedColor = MaterialTheme.colorScheme.error

    Column(modifier.fillMaxWidth()) {
        ClockGridRow {
            MutedLabel(dep.header, TextAlign.Start, muted, 0.6.sp, Modifier.weight(1f))
            MutedLabel(arr.header, TextAlign.End, muted, 0.6.sp, Modifier.weight(1f))
        }
        ClockGridRow(Modifier.padding(top = 6.dp)) {
            MutedLabel(stringResource(R.string.flight_scheduled).uppercase(), TextAlign.Start, muted, modifier = Modifier.weight(1f))
            MutedLabel(stringResource(R.string.flight_scheduled).uppercase(), TextAlign.End, muted, modifier = Modifier.weight(1f))
        }
        ClockGridRow {
            ClockFace(dep.planned, dep.zone, plannedStyle, on, muted, Alignment.Start, Modifier.weight(1f))
            ClockFace(arr.planned, arr.zone, plannedStyle, on, muted, Alignment.End, Modifier.weight(1f))
        }
        ClockGridRow(Modifier.padding(top = 10.dp)) {
            MutedLabel(dep.effectiveLabel, TextAlign.Start, muted, modifier = Modifier.weight(1f))
            MutedLabel(arr.effectiveLabel, TextAlign.End, muted, modifier = Modifier.weight(1f))
        }
        ClockGridRow {
            ClockFace(
                at = dep.effective,
                zone = dep.zone,
                timeStyle = effectiveStyle,
                timeColor = if (dep.delayed) delayedColor else on,
                zoneColor = muted,
                align = Alignment.Start,
                modifier = Modifier.weight(1f),
            )
            ClockFace(
                at = arr.effective,
                zone = arr.zone,
                timeStyle = effectiveStyle,
                timeColor = if (arr.delayed) delayedColor else on,
                zoneColor = muted,
                align = Alignment.End,
                modifier = Modifier.weight(1f),
            )
        }
        ClockGridRow {
            ClockDate(dep.effective ?: dep.planned, dep.zone, TextAlign.Start, muted, Modifier.weight(1f))
            ClockDate(arr.effective ?: arr.planned, arr.zone, TextAlign.End, muted, Modifier.weight(1f))
        }
    }
}

@Composable
fun AirportClock(
    scheduled: Long?,
    estimated: Long?,
    actual: Long?,
    status: FlightStatus,
    language: String,
    role: ClockRole,
    variant: ClockVariant = ClockVariant.COMPACT,
    alignEnd: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val col = clockColumn(scheduled, estimated, actual, DateTimeFmt.deviceZone(), status, role)
    val plannedStyle = if (variant == ClockVariant.DETAIL) {
        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
    } else {
        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    }
    val effectiveStyle = if (variant == ClockVariant.DETAIL) {
        MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
    } else {
        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val on = MaterialTheme.colorScheme.onSurface
    val delayedColor = MaterialTheme.colorScheme.error
    val align = if (alignEnd) Alignment.End else Alignment.Start
    val textAlign = if (alignEnd) TextAlign.End else TextAlign.Start

    Column(modifier, horizontalAlignment = align) {
        MutedLabel(col.header, textAlign, muted, 0.6.sp)
        MutedLabel(stringResource(R.string.flight_scheduled).uppercase(), textAlign, muted)
        ClockFace(col.planned, col.zone, plannedStyle, on, muted, align)
        MutedLabel(col.effectiveLabel, textAlign, muted)
        ClockFace(
            at = col.effective,
            zone = col.zone,
            timeStyle = effectiveStyle,
            timeColor = if (col.delayed) delayedColor else on,
            zoneColor = muted,
            align = align,
        )
        ClockDate(col.effective ?: col.planned, col.zone, textAlign, muted)
    }
}

@Composable
private fun clockColumn(
    scheduled: Long?,
    estimated: Long?,
    actual: Long?,
    zone: ZoneId,
    status: FlightStatus,
    role: ClockRole,
): ClockColumnModel {
    val times = resolveLegTimes(scheduled, estimated, actual)
    val delayed = status == FlightStatus.DELAYED || isLegDelayed(times, status == FlightStatus.DELAYED)
    val planned = times.planned
    val effective = times.effective ?: times.planned
    return ClockColumnModel(
        header = stringResource(if (role == ClockRole.DEP) R.string.flight_dep else R.string.flight_arr).uppercase(),
        planned = planned,
        effective = effective,
        zone = zone,
        delayed = delayed,
        effectiveLabel = effectiveLabel(times.effectiveKind),
    )
}

private data class ClockColumnModel(
    val header: String,
    val planned: Long?,
    val effective: Long?,
    val zone: ZoneId,
    val delayed: Boolean,
    val effectiveLabel: String,
)

@Composable
private fun ClockGridRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, content = content)
}

@Composable
private fun MutedLabel(
    text: String,
    textAlign: TextAlign,
    color: Color,
    letterSpacing: TextUnit = 0.5.sp,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        letterSpacing = letterSpacing,
        textAlign = textAlign,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ClockFace(
    at: Long?,
    zone: ZoneId,
    timeStyle: TextStyle,
    timeColor: Color,
    zoneColor: Color,
    align: Alignment.Horizontal,
    modifier: Modifier = Modifier,
) {
    val time = DateTimeFmt.time(at, zone)
    val offset = if (at != null) DateTimeFmt.offsetLabel(zone) else null
    val offsetStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = (timeStyle.fontSize.value * 0.55f).coerceAtLeast(10f).sp,
        fontWeight = FontWeight.Normal,
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (align == Alignment.End) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            time,
            style = timeStyle,
            color = timeColor,
            maxLines = 1,
            softWrap = false,
        )
        if (!offset.isNullOrBlank()) {
            Text(
                offset,
                style = offsetStyle,
                color = zoneColor,
                modifier = Modifier.padding(start = 4.dp),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ClockDate(
    at: Long?,
    zone: ZoneId,
    textAlign: TextAlign,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        at?.let { DateTimeFmt.date(it, zone) }.orEmpty(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        textAlign = textAlign,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun effectiveLabel(kind: EffectiveKind?): String {
    val eff = stringResource(R.string.flight_effective)
    val q = when (kind) {
        EffectiveKind.ACTUAL -> stringResource(R.string.flight_actual)
        EffectiveKind.ESTIMATED -> stringResource(R.string.flight_estimated)
        null -> null
    }
    return if (q != null) "$eff • $q".uppercase() else eff.uppercase()
}

enum class ClockRole { DEP, ARR }
enum class ClockVariant { COMPACT, DETAIL }
