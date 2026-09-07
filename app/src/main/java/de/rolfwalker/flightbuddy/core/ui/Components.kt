package de.rolfwalker.flightbuddy.core.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.domain.airlineInitials
import de.rolfwalker.flightbuddy.core.domain.airlineLogoUrl
import de.rolfwalker.flightbuddy.core.domain.isLiveStatus
import de.rolfwalker.flightbuddy.core.model.FlightStatus

@Composable
fun AirlineLogo(iata: String?, name: String?, size: Int = 40, modifier: Modifier = Modifier) {
    val url = airlineLogoUrl(iata)
    val initials = airlineInitials(iata, name)
    val desc = stringResource(R.string.a11y_airline_logo, name ?: iata ?: "?")
    Surface(
        modifier = modifier.size(size.dp).semantics { contentDescription = desc },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 0.dp,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = desc, contentScale = ContentScale.Fit, modifier = Modifier.padding(4.dp))
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(initials, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun StatusBadge(status: FlightStatus, delayMinutes: Int? = null) {
    val live = isLiveStatus(status)
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a",
    )
    val (bg, fg, label) = when (status) {
        FlightStatus.EN_ROUTE, FlightStatus.DEPARTED -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, stringResource(if (status == FlightStatus.EN_ROUTE) R.string.status_en_route else R.string.status_departed))
        FlightStatus.DELAYED -> Triple(Warning.copy(alpha = 0.2f), Warning, if (delayMinutes != null) stringResource(R.string.status_delayed_by, delayMinutes) else stringResource(R.string.status_delayed))
        FlightStatus.CANCELLED, FlightStatus.DIVERTED -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, stringResource(if (status == FlightStatus.CANCELLED) R.string.status_cancelled else R.string.status_diverted))
        FlightStatus.LANDED -> Triple(Success.copy(alpha = 0.18f), Success, stringResource(R.string.status_landed))
        FlightStatus.BOARDING -> Triple(Success.copy(alpha = 0.18f), Success, stringResource(R.string.status_boarding))
        FlightStatus.SCHEDULED -> Triple(Success.copy(alpha = 0.18f), Success, stringResource(R.string.status_on_time))
        FlightStatus.UNKNOWN -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.status_unknown))
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    Box(Modifier.size(6.dp).alpha(pulse).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Text("  ")
                }
                Text(label)
            }
        },
        colors = AssistChipDefaults.assistChipColors(disabledContainerColor = bg, disabledLabelColor = fg),
        border = null,
        modifier = Modifier.semantics { contentDescription = label },
    )
}

@Composable
fun Wordmark() {
    Row {
        Text("Flight", style = MaterialTheme.typography.titleLarge)
        Text("Buddy", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun TonalCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        content = content,
    )
}
