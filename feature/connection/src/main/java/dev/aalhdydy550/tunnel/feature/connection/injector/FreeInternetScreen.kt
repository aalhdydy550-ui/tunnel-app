package dev.aalhdydy550.tunnel.feature.connection.injector

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aalhdydy550.tunnel.feature.connection.TunnelState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Free internet" screen (drawer → «نت مجاني»):
 *
 *   warning card (verbatim) → SIM carrier card (detected + manual
 *   choice) → SSH profile picker (or the import guidance when there is
 *   none) → candidate networks (name + SNI host + carrier + status
 *   badge) → connect/disconnect → probe result.
 *
 * Status rule, stated on every row: the default badge is
 * «جرّب على شريحتك — غير موثقة»; only a network this phone verified
 * itself (successful connection + passing probe) shows
 * «موثّقة على شريحتك ✓» with its date. No network is ever shown as
 * "working" before that.
 */
@Composable
fun FreeInternetScreen(
    viewModel: FreeInternetViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val candidates by viewModel.candidates.collectAsState()
    val detectedCarrier by viewModel.detectedCarrier.collectAsState()
    val selectedCarrier by viewModel.selectedCarrier.collectAsState()
    val sshProfiles by viewModel.sshProfiles.collectAsState()
    val excludedSshCount by viewModel.excludedSshCount.collectAsState()
    val selectedSsh by viewModel.selectedSshProfile.collectAsState()
    val selectedNetwork by viewModel.selectedNetwork.collectAsState()
    val tunnelState by viewModel.tunnelState.collectAsState()
    val probeResult by viewModel.probeResult.collectAsState()
    val verifiedDates by viewModel.verifiedDates.collectAsState()

    // VPN permission: the system screen, then start the injector tunnel.
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onVpnPermissionGranted()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.vpnPermissionRequest.collect {
            VpnService.prepare(context)?.let { vpnPermissionLauncher.launch(it) }
        }
    }

    val connected = tunnelState is TunnelState.Started
    val busy = tunnelState is TunnelState.Starting ||
        tunnelState is TunnelState.Reconnecting
    val canConnect = selectedNetwork != null && selectedSsh != null && !busy

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }

        // Warning card — verbatim, at the top of the screen.
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    text = "شبكات تجريبية من قائمة مرجعية قديمة — قد لا تعمل " +
                        "الآن. جرّب على شريحتك، وتأكد من رصيد باقتك قبل " +
                        "وبعد الاتصال؛ ولا تستخدم هذا الوضع لتسجيل الدخول " +
                        "أو في البنوك.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // SIM carrier card: detected carrier + manual override.
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "شركة الشريحة",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (detectedCarrier == Carrier.UNKNOWN) {
                            "لم تُكتشف شريحة — اختر شركتك يدويًا أدناه."
                        } else {
                            "المكتشفة من الشريحة: ${detectedCarrier.arabicName}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (carrier in MANUAL_CARRIERS) {
                            OutlinedButton(
                                onClick = { viewModel.selectCarrier(carrier) },
                            ) {
                                Text(
                                    text = carrier.arabicName,
                                    fontWeight = if (selectedCarrier == carrier) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // SSH profile picker (the user's own server, e.g. Oracle VPS).
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "سيرفر SSH الخاص بك",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (sshProfiles.isEmpty()) {
                        Text(
                            text = "لا يوجد بروفايل SSH بعد — أضف سيرفر SSH " +
                                "الخاص بك (مثل سيرفر Oracle VPS) من شاشة " +
                                "«استيراد» برابط مثل:\n" +
                                "ssh://user:password@host:port",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        for (option in sshProfiles) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectSshProfile(option) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedSsh?.profile?.id == option.profile.id,
                                    onClick = { viewModel.selectSshProfile(option) },
                                )
                                Column {
                                    Text(
                                        text = option.profile.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = "${option.host}:${option.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (excludedSshCount > 0) {
                        Text(
                            text = "تم تجاهل $excludedSshCount بروفايل SSH ناقص " +
                                "(يلزم server وserver_port وuser وpassword).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // Connect / disconnect.
        item {
            Button(
                onClick = {
                    if (connected) viewModel.disconnect() else viewModel.connect()
                },
                enabled = connected || canConnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        connected -> "قطع الاتصال"
                        busy -> "جارٍ الاتصال…"
                        else -> "اتصال بالنت المجاني"
                    },
                )
            }
            if (tunnelState is TunnelState.Error) {
                Text(
                    text = (tunnelState as TunnelState.Error).message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Probe result.
        item {
            when (val result = probeResult) {
                ProbeResult.Idle -> Unit
                ProbeResult.Checking -> Text(
                    text = "جارٍ فحص مرور الترافيك عبر النفق…",
                    style = MaterialTheme.typography.bodySmall,
                )
                ProbeResult.Success -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        text = "الفحص نجح ✓ — الترافيك يمر عبر النفق، " +
                            "والشبكة الآن موثّقة على شريحتك.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                is ProbeResult.Failed -> Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Candidate networks.
        item {
            Text(
                text = "الشبكات المرشحة (${candidates.size})",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (candidates.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "لا توجد شبكات مرشحة بعد — تُحمَّل من القائمة " +
                            "المرجعية عند فتح التطبيق.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        items(candidates, key = { it.id }) { candidate ->
            val verifiedAt = verifiedDates[candidate.id]
            val selected = selectedNetwork?.id == candidate.id
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectNetwork(candidate.id) }
                        .padding(12.dp),
                ) {
                    Text(
                        text = candidate.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        text = "SNI: ${candidate.sniHost}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "الشركة: ${candidate.carrier.arabicName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (verifiedAt != null) {
                            "موثّقة على شريحتك ✓ — ${formatDate(verifiedAt)}"
                        } else {
                            "جرّب على شريحتك — غير موثقة"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (verifiedAt != null) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

private val MANUAL_CARRIERS = listOf(
    Carrier.WE,
    Carrier.VODAFONE,
    Carrier.ETISALAT,
    Carrier.ORANGE,
    Carrier.OTHER,
)

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMillis))
