package dev.aalhdydy550.tunnel.feature.profiles

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.routing.RoutingDataState
import dev.aalhdydy550.tunnel.core.ui.R as CoreUiR
import dev.aalhdydy550.tunnel.core.ui.components.ProfileRow
import dev.aalhdydy550.tunnel.feature.connection.ConnectionPanel
import dev.aalhdydy550.tunnel.feature.connection.ConnectionViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The main screen: connection panel, server list with latency, and the
 * settings section (battery exemption, kill switch, auto-start,
 * ad blocking + routing-data status, backup export, About).
 *
 * The app name is the top app bar in MainActivity — visible the moment
 * the app opens — and again in the About card below.
 */
@Composable
fun ServersScreen(
    connectionViewModel: ConnectionViewModel,
    profilesViewModel: ProfilesViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by connectionViewModel.state.collectAsState()
    val speed by connectionViewModel.speed.collectAsState()
    val selectedProfile by connectionViewModel.selectedProfile.collectAsState()

    // The server list is hidden until the user taps the server card
    // (manual choice) or the latency test — the main screen stays
    // clean like the original's.
    var showServers by remember { mutableStateOf(false) }

    val profiles by profilesViewModel.profiles.collectAsState()
    val selectedId by profilesViewModel.selectedProfileId.collectAsState()
    val hasPublicFreeServers by profilesViewModel.hasPublicFreeServers.collectAsState()
    val latency by profilesViewModel.latency.collectAsState()
    val latencyTesting by profilesViewModel.latencyTesting.collectAsState()
    val selectingFastest by profilesViewModel.selectingFastest.collectAsState()
    val autoStartOnBoot by profilesViewModel.autoStartOnBoot.collectAsState()
    val killSwitchGuided by profilesViewModel.killSwitchGuided.collectAsState()
    val adsBlocking by profilesViewModel.adsBlocking.collectAsState()
    val routingStatus by profilesViewModel.routingStatus.collectAsState()
    val routingDecision by profilesViewModel.routingDecision.collectAsState()
    val batteryExempt by profilesViewModel.batteryExempt.collectAsState()

    // VPN permission: the system screen, then start the tunnel.
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            connectionViewModel.onVpnPermissionGranted()
        }
    }
    LaunchedEffect(Unit) {
        connectionViewModel.vpnPermissionRequest.collect {
            VpnService.prepare(context)?.let { vpnPermissionLauncher.launch(it) }
        }
    }

    // Battery exemption: after the system screen, refresh the status.
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { profilesViewModel.refreshBatteryExempt() }

    // Backup export (scoped storage: the user picks the location).
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val json = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            }
        }
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }
        item {
            // Hamo-style hero banner: a dark band with our logo centered
            // (the original shows its app icon on a tinted banner).
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black),
            ) {
                Image(
                    painter = painterResource(CoreUiR.drawable.ic_app_logo),
                    contentDescription = "شعار Ayman Tunnel Plus",
                    modifier = Modifier.size(120.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        item {
            ConnectionPanel(
                state = state,
                speed = speed,
                selectedProfile = selectedProfile,
                selectingFastest = selectingFastest,
                onConnect = {
                    // Pressing connect first picks the fastest server,
                    // then connects — unless the user just picked one
                    // by hand, in which case that server is used.
                    scope.launch {
                        val target = if (profilesViewModel.consumeManualSelection()) {
                            profiles.firstOrNull { it.id == selectedId }
                        } else {
                            profilesViewModel.selectFastestAndWait()
                        }
                        connectionViewModel.connect(target)
                    }
                },
                onDisconnect = { connectionViewModel.disconnect() },
                onServerCardClick = {
                    // Tapping the server card shows the list — and
                    // measures latencies automatically, so the numbers
                    // are there for a manual pick without any button.
                    showServers = !showServers
                    if (showServers) profilesViewModel.testLatency()
                },
            )
        }
        // The list contains public free servers (default subscription):
        // say so plainly — slow/unstable, never for logins or banking.
        if (hasPublicFreeServers) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = "تنبيه: بعض الخوادم هنا من الاشتراك المجاني " +
                            "الافتراضي — " +
                            "خوادم عامة مجانية قد تكون بطيئة أو غير مستقرة، " +
                            "ولا تستخدمها لتسجيل الدخول أو في البنوك.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        // While a latency measurement is running (it starts
        // automatically when the server list opens), show a hint.
        item {
            if (latencyTesting) {
                Text(
                    text = "جارٍ اختبار الخوادم…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (profiles.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "لا توجد خوادم بعد — الاشتراك المجاني الافتراضي " +
                            "يُضاف ويُحدَّث تلقائيًا عند توفر الإنترنت، أو " +
                            "استورد رابطًا من شاشة «استيراد».",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        // The server list only appears for a manual choice: tap the
        // server card (or the latency test) to show it, tap a server
        // to pick it — the list then hides again.
        if (showServers) {
            items(profiles, key = { it.id }) { profile ->
                val ms = latency[profile.id]
                val latencySuffix = when {
                    ms == null && latency.containsKey(profile.id) -> " — لا يرد"
                    ms != null -> " — ${ms}م.ث"
                    else -> ""
                }
                ProfileRow(
                    name = profile.name,
                    typeLabel = profile.type.name,
                    address = "${profile.server}:${profile.port}$latencySuffix",
                    selected = profile.id == selectedId,
                    onClick = {
                        profilesViewModel.selectManually(profile.id)
                        showServers = false
                    },
                    onDelete = { profilesViewModel.delete(profile) },
                    flagEmoji = profile.flagEmoji,
                )
            }
        }
        item {
            SettingsSection(
                batteryExempt = batteryExempt,
                onRequestBatteryExemption = {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    )
                    batteryLauncher.launch(intent)
                },
                killSwitchGuided = killSwitchGuided,
                onOpenVpnSettings = {
                    profilesViewModel.markKillSwitchGuided()
                    context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                },
                autoStartOnBoot = autoStartOnBoot,
                onAutoStartChange = { profilesViewModel.setAutoStartOnBoot(it) },
                adsBlocking = adsBlocking,
                onAdsBlockingChange = { profilesViewModel.setAdsBlocking(it) },
                routingStatus = routingStatus,
                showRoutingNotice = routingDecision?.showStaleNotice == true,
                onRetryRoutingDownload = { profilesViewModel.retryRoutingDownload() },
                onExportBackup = {
                    scope.launch {
                        pendingExport = profilesViewModel.exportBackup()
                        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            .format(Date())
                        exportLauncher.launch("ayman-tunnel-backup-$stamp.json")
                    }
                },
            )
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun SettingsSection(
    batteryExempt: Boolean?,
    onRequestBatteryExemption: () -> Unit,
    killSwitchGuided: Boolean,
    onOpenVpnSettings: () -> Unit,
    autoStartOnBoot: Boolean,
    onAutoStartChange: (Boolean) -> Unit,
    adsBlocking: Boolean,
    onAdsBlockingChange: (Boolean) -> Unit,
    routingStatus: dev.aalhdydy550.tunnel.core.data.routing.RoutingDataStatus?,
    showRoutingNotice: Boolean,
    onRetryRoutingDownload: () -> Unit,
    onExportBackup: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "الإعدادات",
            style = MaterialTheme.typography.titleMedium,
        )

        // Battery exemption: without it, Doze kills the tunnel.
        if (batteryExempt == false) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "تحسين البطارية يقتل النفق",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "أندرويد يوقف التطبيقات في وضع Doze أثناء النوم، " +
                            "فيقطع الاتصال في المنتصف. أضف التطبيق للقائمة " +
                            "البيضاء حتى يبقى النفق شغالًا.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onRequestBatteryExemption) {
                        Text("إضافة للقائمة البيضاء")
                    }
                }
            }
        } else if (batteryExempt == true) {
            Text(
                text = "✓ استثناء البطارية مفعّل — النفق لن يُقتل أثناء النوم.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        // Kill switch: Android's Always-on VPN + "Block connections
        // without VPN". The OS enforces it; the app can only guide the
        // user to the system setting.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "مفتاح القتل (Kill Switch)",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "لمنع أي تسريب أثناء إعادة الاتصال، فعّل من إعدادات " +
                        "أندرويد: VPN الدائم + «حظر الاتصالات بدون VPN».",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenVpnSettings) {
                    Text(
                        if (killSwitchGuided) "فتح إعدادات VPN مجددًا"
                        else "فتح إعدادات VPN",
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SwitchRow(
                    label = "التشغيل التلقائي عند الإقلاع",
                    description = "يعيد النفق تلقائيًا بعد إعادة تشغيل الهاتف.",
                    checked = autoStartOnBoot,
                    onChange = onAutoStartChange,
                )
                SwitchRow(
                    label = "حظر الإعلانات",
                    description = "يحجب نطاقات الإعلانات بقائمة محدّثة صغيرة " +
                        "(تُنزَّل مرة واحدة وتعمل دون إنترنت بعدها).",
                    checked = adsBlocking,
                    onChange = onAdsBlockingChange,
                )
                val statusText = when (routingStatus?.state) {
                    RoutingDataState.READY -> "بيانات التوجيه محدّثة."
                    RoutingDataState.FAILED -> "تعذّر تنزيل بيانات التوجيه."
                    RoutingDataState.NOT_DOWNLOADED -> "بيانات التوجيه غير منزّلة بعد."
                    null -> "جارٍ فحص بيانات التوجيه…"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (showRoutingNotice) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "بيانات التوجيه غير محدّثة — التطبيق يعمل بالوضع " +
                            "الآمن (كل الترافيك عبر النفق) حتى تُنزَّل.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onRetryRoutingDownload) {
                        Text("إعادة محاولة التنزيل")
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onExportBackup,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("تصدير نسخة احتياطية (JSON)") }

        // About: the name, inside the app, as the user asked.
        // Community links point to OUR repo only — never to v2rayNG's
        // or the original app's channels (hosts analysis §4 item 5).
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Ayman Tunnel Plus",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "الإصدار 0.1.0 — النواة sing-box 1.14.4 (GPL-3.0)\n" +
                        "بناء نظيف: بلا إعلانات، بلا تتبّع، بلا بيانات " +
                        "دخول مدمجة.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                    "https://github.com/aalhdydy550-ui/tunnel-app",
                                ),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("المستودع على GitHub (الدعم والإبلاغ عن مشاكل)") }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
