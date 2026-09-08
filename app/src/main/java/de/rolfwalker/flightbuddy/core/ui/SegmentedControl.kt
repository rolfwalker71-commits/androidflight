package de.rolfwalker.flightbuddy.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

data class SegmentItem<T>(
    val id: T,
    val label: String,
    val icon: ImageVector? = null,
)

@Composable
fun <T> SegmentedControl(
    items: List<SegmentItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val active = item.id == selected
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(item.id) }
                    .semantics {
                        role = Role.Tab
                        this.selected = active
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (item.icon != null) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp).size(16.dp),
                        tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
