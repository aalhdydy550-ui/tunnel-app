package dev.aalhdydy550.tunnel.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A single server row, Hamo-style: a white card (60dp) with the
 * country flag, the name, the protocol, and the address + latency.
 * [selected] draws a yellow border + light-yellow container.
 */
@Composable
fun ProfileRow(
    name: String,
    typeLabel: String,
    address: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    /** Flag emoji for the profile's country code ("" when unknown). */
    flagEmoji: String = "",
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else if (isSystemInDarkTheme()) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                Color.White
            },
        ),
        border = if (selected) {
            BorderStroke(2.dp, Color(0xFFF8CE29))
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (flagEmoji.isNotBlank()) {
                Text(
                    text = flagEmoji,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = typeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "حذف",
                    )
                }
            }
        }
    }
}
