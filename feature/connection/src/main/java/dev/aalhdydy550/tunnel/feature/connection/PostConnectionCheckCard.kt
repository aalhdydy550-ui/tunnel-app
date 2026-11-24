package dev.aalhdydy550.tunnel.feature.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Post-connection check card (#7): shows whether traffic really
 * flows through the tunnel, the public IP the outside world sees,
 * and the probe latency.
 *
 * The card is rendered by [ConnectionPanel] only while connected (or
 * while a previous result exists); [onCheckNow] is null until the
 * screen wires [PostConnectionCheckViewModel] in, in which case the
 * button is hidden.
 */
@Composable
fun PostConnectionCheckCard(
    connected: Boolean,
    result: PostConnectionChecker.Result?,
    checking: Boolean,
    onCheckNow: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "فحص ما بعد الاتصال",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            when {
                checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            text = "  جارٍ فحص الاتصال…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                result == null -> {
                    Text(
                        text = if (connected) {
                            "لم يُفحص بعد — سيُفحص تلقائيًا عند الاتصال."
                        } else {
                            "يُفحص بعد الاتصال فقط."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                result.success -> {
                    Text(
                        text = "✓ ${result.detail}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF009966),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "الـIP العام: ${result.publicIp ?: "غير معروف"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "زمن الاستجابة: ${result.latencyMs}ms",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> {
                    Text(
                        text = "✗ ${result.detail}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (result != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "آخر فحص: ${formatCheckTime(result.checkedAtMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onCheckNow != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCheckNow,
                    enabled = connected && !checking,
                ) {
                    Text("إعادة الفحص")
                }
            }
        }
    }
}

private fun formatCheckTime(timeMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timeMs))
