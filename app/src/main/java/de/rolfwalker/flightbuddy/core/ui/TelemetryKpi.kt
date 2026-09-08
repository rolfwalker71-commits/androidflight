package de.rolfwalker.flightbuddy.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.model.Units

data class TelemetryKpi(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val secondary: String? = null,
)

@Composable
fun flightTelemetryKpis(
    altitudeFt: Double?,
    velocityKts: Double?,
    heading: Double?,
    remainingNm: Double?,
    language: String,
    units: Units,
    verticalRateFpm: Double? = null,
): List<TelemetryKpi> {
    val altitude = formatAltitudePair(altitudeFt, language)
    val speed = formatSpeedPair(velocityKts, language)
    val vrate = verticalRateFpm?.takeIf { it.isFinite() }?.let { fpm ->
        val climb = if (fpm > 80) stringResource(R.string.flight_climb) else if (fpm < -80) stringResource(R.string.flight_descent) else stringResource(R.string.flight_level)
        String.format(java.util.Locale.US, "%+.0f fpm", fpm) to climb
    }
    return listOfNotNull(
        TelemetryKpi(Icons.Outlined.Terrain, stringResource(R.string.flight_altitude), altitude.first, altitude.second),
        TelemetryKpi(Icons.Outlined.Speed, stringResource(R.string.flight_speed), speed.first, speed.second),
        TelemetryKpi(Icons.Outlined.Explore, stringResource(R.string.flight_heading), formatHeading(heading, language)),
        TelemetryKpi(Icons.Outlined.Route, stringResource(R.string.flight_remaining), formatDistanceNm(remainingNm, language, units)),
        vrate?.let { TelemetryKpi(Icons.Outlined.NorthEast, stringResource(R.string.flight_vrate), it.first, it.second) },
    )
}

@Composable
fun TelemetryKpiGrid(
    tiles: List<TelemetryKpi>,
    columns: Int,
    modifier: Modifier = Modifier,
    hideIfAllEmpty: Boolean = false,
    hideEmptyTiles: Boolean = false,
) {
    val visible = if (hideEmptyTiles) tiles.filter { it.value != "—" } else tiles
    if (visible.isEmpty()) return
    if (hideIfAllEmpty && visible.all { it.value == "—" }) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        visible.chunked(columns).forEach { rowTiles ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowTiles.forEach { tile ->
                    TelemetryKpiCard(tile, Modifier.weight(1f))
                }
                repeat(columns - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TelemetryKpiCard(spec: TelemetryKpi, modifier: Modifier = Modifier) {
    val empty = spec.value == "—"
    val secondary = spec.secondary
    val showSecondary = !empty && !secondary.isNullOrBlank() && secondary != "—"
    TonalCard(modifier) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                spec.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    spec.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 12.sp,
                )
                if (!empty) {
                    Text(
                        spec.value,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp,
                    )
                    if (showSecondary) {
                        Text(
                            secondary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 12.sp,
                        )
                    }
                } else {
                    Text(
                        "—",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 14.sp,
                    )
                }
            }
        }
    }
}
