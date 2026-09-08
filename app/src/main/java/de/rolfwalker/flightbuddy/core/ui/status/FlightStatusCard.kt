package de.rolfwalker.flightbuddy.core.ui.status

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.data.db.FlightEntity
import de.rolfwalker.flightbuddy.core.domain.airlineCodeForLogo
import de.rolfwalker.flightbuddy.core.domain.airlineInitials
import de.rolfwalker.flightbuddy.core.domain.airlineLogoUrl
import de.rolfwalker.flightbuddy.core.model.FlightStatus
import de.rolfwalker.flightbuddy.core.ui.Warning

private object StatusCardLogo {
    val maxHeight = 54.dp
    val maxWidth = 108.dp
}

private object StatusCardPlane {
    val size = 24.dp
}

/**
 * Google Flights status layout — same fields and hierarchy as the 4×2/5×2 widget.
 * Colors follow the in-app aviation-blue theme so dark/light stay consistent.
 */
@Composable
fun FlightStatusCard(
    flight: FlightEntity,
    dense: Boolean,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    val context = LocalContext.current
    val model = flightStatusCardModel(
        context = context,
        f = flight,
        now = now,
        showFreshness = true,
        showWeekday = false,
        showBaggage = true,
    )
    val pad = if (dense) 12.dp else 14.dp
    Column(
        modifier.padding(
            start = pad,
            top = pad + 8.dp,
            end = pad,
            bottom = pad,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    model.airlineFlight,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    model.cityRoute,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = if (dense) 15.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 20.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                )
                model.freshness?.let { fresh ->
                    Text(
                        fresh,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            StatusAirlineLogo(
                iata = airlineCodeForLogo(flight.airlineIata, flight.flightNumber),
                name = flight.airlineName,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusChip(model.chip)
            Text(
                model.countdown,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = statusAccent(model.chip),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.height(8.dp))
        RoutePlaneRow(
            from = model.fromIata,
            to = model.toIata,
            fraction = model.progress / 100f,
            showIata = model.showRouteIata,
            marker = model.progressMarker,
        )
        Spacer(Modifier.height(4.dp))
        BigTimesRow(model)
        if (!model.depStand.isNullOrBlank() || !model.arrStand.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    model.depStand.orEmpty(),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    model.arrStand.orEmpty(),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatusAirlineLogo(iata: String?, name: String?) {
    val url = airlineLogoUrl(iata)
    val initials = airlineInitials(iata, name)
    val desc = stringResource(R.string.a11y_airline_logo, name ?: iata ?: "?")
    Box(
        modifier = Modifier
            .height(StatusCardLogo.maxHeight)
            .widthIn(min = 40.dp, max = StatusCardLogo.maxWidth)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.TopEnd,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = desc,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(StatusCardLogo.maxHeight)
                    .widthIn(max = StatusCardLogo.maxWidth),
            )
        } else {
            Text(
                initials,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StatusChip(status: FlightStatus) {
    val label = flightStatusLabel(LocalContext.current, status)
    val (bg, fg) = when (status) {
        FlightStatus.DELAYED -> Warning.copy(alpha = 0.18f) to Warning
        FlightStatus.CANCELLED, FlightStatus.DIVERTED ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
    }
    Text(
        label,
        modifier = Modifier
            .background(bg, RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = label },
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        ),
        color = fg,
        maxLines = 1,
    )
}

@Composable
private fun RoutePlaneRow(
    from: String,
    to: String,
    fraction: Float,
    showIata: Boolean,
    marker: FlightProgressMarker,
) {
    val fill = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val pct = fraction.coerceIn(0f, 1f)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIata) {
            Text(
                from,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
        }
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(StatusCardPlane.size),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.Center)
                    .background(track, CircleShape),
            )
            if (pct > 0f) {
                Box(
                    Modifier
                        .width(maxWidth * pct)
                        .height(3.dp)
                        .align(Alignment.CenterStart)
                        .background(fill, CircleShape),
                )
            }
            Image(
                painter = painterResource(progressMarkerDrawable(marker)),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.Black),
                modifier = Modifier
                    .offset(x = (maxWidth - StatusCardPlane.size) * pct)
                    .size(StatusCardPlane.size)
                    .align(Alignment.CenterStart),
            )
        }
        if (showIata) {
            Spacer(Modifier.width(6.dp))
            Text(
                to,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BigTimesRow(model: FlightStatusCardModel) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(
                model.dep.effective,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = if (model.dep.delayed) Warning else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                scheduledUnder(context, model.dep.scheduled),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                model.arr.effective,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = if (model.arr.delayed) Warning else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                scheduledUnder(context, model.arr.scheduled),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun statusAccent(status: FlightStatus) = when (status) {
    FlightStatus.DELAYED -> Warning
    FlightStatus.CANCELLED, FlightStatus.DIVERTED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}
