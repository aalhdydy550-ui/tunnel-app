package dev.aalhdydy550.tunnel.feature.connection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.net.SpeedTester
import kotlinx.coroutines.delay

/**
 * Hamo-style connection panel, reimplemented in Compose:
 *
 *   status row (down/up speeds at the sides, status text centered)
 *   → selected-server card (white, flag + name + protocol, chevron)
 *   → "Connection time" + timer
 *   → big round connect button (dark navy circle, yellow ring,
 *     power mark drawn with Canvas, status word inside) with a
 *     yellow progress ring while connecting
 *   → speed-test button + result
 *
 * No ad banner, no copied assets: the power mark is drawn here,
 * the icon is our own logo, and every string is ours.
 */
@Composable
fun ConnectionPanel(
    state: TunnelState,
    speed: SpeedMonitor.Speed,
    selectedProfile: Profile?,
    speedTestResult: SpeedTester.Result?,
    speedTestRunning: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSpeedTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = state is TunnelState.Started
    val busy = state is TunnelState.Starting || state is TunnelState.Reconnecting

    // Connection timer: counts up while connected, resets otherwise.
    var startedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        if (connected && startedAt == 0L) {
            startedAt = System.currentTimeMillis()
        } else if (!connected) {
            startedAt = 0L
        }
    }
    LaunchedEffect(connected) {
        while (connected) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsedMs = if (connected && startedAt > 0L) now - startedAt else 0L

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Status row: down speed | status | up speed (Hamo's top row).
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "↓ ${formatRate(speed.rxBytesPerSec)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
            )
            Text(
                text = statusText(state),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor(state),
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "↑ ${formatRate(speed.txBytesPerSec)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Selected-server card (white, 60dp, like the original's card).
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardColor()),
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedProfile?.flagEmoji?.ifBlank { "🌐" } ?: "🌐",
                    fontSize = 25.sp,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedProfile?.name ?: "لم يتم اختيار خادم بعد",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = selectedProfile?.type?.name ?: "اختر خادمًا من القائمة أدناه",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    text = "‹",
                    fontSize = 26.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Connection time (Hamo shows it in yellow above the button).
        Text(
            text = "Connection time",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatElapsed(elapsedMs),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF8A6D00),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Big round button (150dp) + yellow progress ring (181dp).
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(181.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(181.dp),
                    color = Color(0xFFF8CE29),
                    strokeWidth = 10.dp,
                )
            }
            val buttonColor = when {
                connected -> Color(0xFF009966)
                state is TunnelState.Error -> MaterialTheme.colorScheme.error
                else -> Color(0xFF1B1140)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(150.dp)
                    .clip(CircleShape)
                    .background(buttonColor)
                    .border(2.dp, Color(0xFFF8CE29), CircleShape)
                    .clickable {
                        when {
                            connected -> onDisconnect()
                            busy -> Unit // ignore taps while busy
                            else -> onConnect()
                        }
                    },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PowerMark(
                        color = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = buttonLabel(state),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (state is TunnelState.Reconnecting) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "السبب: ${state.lastReason}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state is TunnelState.Error) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onSpeedTest,
            enabled = connected && !speedTestRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (speedTestRunning) "جارٍ القياس…" else "اختبار السرعة")
        }
        speedTestResult?.let { result ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "سرعة التحميل: ${formatRate(result.bytesPerSecond)}" +
                    " (${result.bytesDownloaded / 1024} ك.ب في ${result.elapsedMs} م.ث)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Power symbol drawn with Canvas (our own drawing, no copied asset). */
@Composable
private fun PowerMark(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = size.minDimension * 0.10f, cap = StrokeCap.Round)
        val r = size.minDimension * 0.36f
        val c = Offset(size.width / 2f, size.height / 2f + size.minDimension * 0.04f)
        // Arc with a gap at the TOP (300° sweep starting at 300°:
        // Compose angles: 0° = 3 o'clock, positive = clockwise, so
        // 270° is 12 o'clock; the gap 240°–300° sits at the top and
        // the vertical line enters through it).
        drawArc(
            color = color,
            startAngle = 300f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2),
            style = stroke,
        )
        // Vertical line through the gap.
        val topY = c.y - r - size.minDimension * 0.14f
        drawLine(
            color = color,
            start = Offset(c.x, topY),
            end = Offset(c.x, c.y + r * 0.15f),
            strokeWidth = size.minDimension * 0.10f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun cardColor(): Color =
    if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceVariant else Color.White

private fun statusText(state: TunnelState): String = when (state) {
    TunnelState.Stopped -> "غير متصل"
    is TunnelState.Starting -> "جارٍ الاتصال…"
    is TunnelState.Started -> "متصل"
    is TunnelState.Stopping -> "جارٍ قطع الاتصال…"
    is TunnelState.Reconnecting ->
        "جارٍ إعادة الاتصال… (المحاولة ${state.attempt})"
    is TunnelState.Error -> "تعذّر الاتصال"
}

private fun buttonLabel(state: TunnelState): String = when (state) {
    TunnelState.Stopped -> "غير متصل"
    is TunnelState.Starting -> "جارٍ الاتصال…"
    is TunnelState.Started -> "متصل"
    is TunnelState.Stopping -> "جارٍ قطع الاتصال…"
    is TunnelState.Reconnecting -> "إعادة اتصال…"
    is TunnelState.Error -> "أعد المحاولة"
}

@Composable
private fun statusColor(state: TunnelState): Color = when (state) {
    is TunnelState.Started -> Color(0xFF009966)
    is TunnelState.Error -> MaterialTheme.colorScheme.error
    TunnelState.Stopped -> MaterialTheme.colorScheme.error
    else -> Color(0xFF8A6D00)
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

/** Human-readable rate: B/s → KB/s → MB/s, one decimal. */
fun formatRate(bytesPerSecond: Long): String = when {
    bytesPerSecond < 1024 -> "$bytesPerSecond ب/ث"
    bytesPerSecond < 1024 * 1024 -> "%.1f ك.ب/ث".format(bytesPerSecond / 1024.0)
    else -> "%.1f م.ب/ث".format(bytesPerSecond / (1024.0 * 1024.0))
}
