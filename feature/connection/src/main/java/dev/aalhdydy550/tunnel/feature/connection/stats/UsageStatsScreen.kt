package dev.aalhdydy550.tunnel.feature.connection.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aalhdydy550.tunnel.core.data.stats.ProfileUsage
import dev.aalhdydy550.tunnel.core.data.stats.SessionRecord
import dev.aalhdydy550.tunnel.core.data.stats.UsageTotals
import dev.aalhdydy550.tunnel.core.data.stats.formatBytes
import dev.aalhdydy550.tunnel.core.data.stats.formatDuration
import dev.aalhdydy550.tunnel.core.data.stats.formatSessionTime

/**
 * Usage statistics screen (§4-#8): session + daily/weekly/all-time
 * totals, per-profile breakdown, and the recent sessions list.
 *
 * The ViewModel is passed in (like the other screens in this app)
 * because the navigation host lives in the app module; wiring this
 * screen into the drawer is an integration step (see the delivery
 * summary). All strings are Arabic, matching the rest of the app.
 */
@Composable
fun UsageStatsScreen(
    viewModel: UsageStatsViewModel,
    modifier: Modifier = Modifier,
) {
    val snapshot by viewModel.snapshot.collectAsState()
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "إحصاءات الاستخدام",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        val current = snapshot
        if (current == null) {
            Text(text = "جارٍ تحميل الإحصاءات…")
            return@Column
        }
        current.currentSession?.let { live ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "الجلسة الحالية — ${live.profileName}",
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "↓ تنزيل: ${formatBytes(live.rxBytes)}")
                    Text(text = "↑ رفع: ${formatBytes(live.txBytes)}")
                }
            }
        }
        TotalsCard(title = "اليوم", totals = current.today)
        TotalsCard(title = "هذا الأسبوع", totals = current.week)
        TotalsCard(title = "كل الوقت", totals = current.allTime)

        if (current.byProfile.isNotEmpty()) {
            Text(text = "حسب الخادم", fontWeight = FontWeight.Bold)
            current.byProfile.forEach { profile ->
                ProfileRow(profile = profile)
            }
        }

        if (current.recentSessions.isNotEmpty()) {
            Text(text = "الجلسات الأخيرة", fontWeight = FontWeight.Bold)
            current.recentSessions.forEach { session ->
                SessionRow(session = session)
            }
        }

        if (current.allTime.sessions > 0) {
            OutlinedButton(
                onClick = { showClearConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "مسح كل الإحصاءات")
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("مسح الإحصاءات؟") },
            text = { Text("سيتم حذف كل الجلسات والإجماليات المحفوظة نهائيًا.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearAll()
                }) { Text("مسح") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("إلغاء") }
            },
        )
    }
}

@Composable
private fun TotalsCard(title: String, totals: UsageTotals) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "↓ تنزيل: ${formatBytes(totals.rxBytes)}")
            Text(text = "↑ رفع: ${formatBytes(totals.txBytes)}")
            Text(text = "الجلسات: ${totals.sessions}")
        }
    }
}

@Composable
private fun ProfileRow(profile: ProfileUsage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = profile.profileName,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(text = "↓ ${formatBytes(profile.rxBytes)}  ↑ ${formatBytes(profile.txBytes)}")
    }
}

@Composable
private fun SessionRow(session: SessionRecord) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = session.profileName,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            text = "${formatSessionTime(session.startedAtMs)} — " +
                formatDuration(session.durationMs),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "↓ ${formatBytes(session.rxBytes)}  ↑ ${formatBytes(session.txBytes)}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
