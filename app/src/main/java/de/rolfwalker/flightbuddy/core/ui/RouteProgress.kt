package de.rolfwalker.flightbuddy.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp

@Composable
fun RouteProgress(progress: Double, modifier: Modifier = Modifier) {
    val pct = progress.toFloat().coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    BoxWithConstraints(modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .align(Alignment.Center)
                .background(track, CircleShape),
        )
        Box(
            Modifier
                .width(maxWidth * pct)
                .height(2.dp)
                .align(Alignment.CenterStart)
                .background(fill, CircleShape),
        )
        val plane = 16.dp
        Icon(
            Icons.Outlined.Flight,
            contentDescription = null,
            tint = fill,
            modifier = Modifier
                .offset(x = (maxWidth - plane) * pct)
                .size(plane)
                .rotate(90f)
                .align(Alignment.CenterStart),
        )
    }
}
