package dev.aalhdydy550.tunnel.feature.profiles

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.hilt.navigation.compose.hiltViewModel
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.model.SpeedTestResult
import dev.aalhdydy550.tunnel.core.data.net.LatencyTester
import dev.aalhdydy550.tunnel.core.data.repository.ProxyMode
import dev.aalhdydy550.tunnel.core.data.profile.OvpnConfParser
import dev.aalhdydy550.tunnel.core.data.routing.RoutingDataState
import dev.aalhdydy550.tunnel.core.ui.R as CoreUiR
import dev.aalhdydy550.tunnel.core.ui.components.ProfileRow
import dev.aalhdydy550.tunnel.feature.connection.ConnectionPanel
import dev.aalhdydy550.tunnel.feature.connection.ConnectionViewModel
import dev.aalhdydy550.tunnel.feature.connection.TunnelState
import dev.aalhdydy550.tunnel.feature.connection.settings.SettingsViewModel
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
    onOpenFreeInternet: (() -> Unit)? = null,
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
    val latency by profilesViewModel.latency.collectAsState()
    val latencyTesting by profilesViewModel.latencyTesting.collectAsState()
    val selectingFastest by profilesViewModel.selectingFastest.collectAsState()
    val autoStartOnBoot by profilesViewModel.autoStartOnBoot.collectAsState()
    val killSwitchGuided by profilesViewModel.killSwitchGuided.collectAsState()
    val adsBlocking by profilesViewModel.adsBlocking.collectAsState()
    val routingStatus by profilesViewModel.routingStatus.collectAsState()
    val routingDecision by profilesViewModel.routingDecision.collectAsState()
    val batteryExempt by profilesViewModel.batteryExempt.collectAsState()

    // Groups (item 6b): chips above the server list + the active group.
    val groups by profilesViewModel.groups.collectAsState()
    val activeGroupId by profilesViewModel.activeGroupId.collectAsState()
    // Last measured speed per server (item 6a), shown in the row.
    val speedResults by profilesViewModel.speedResults.collectAsState()
    val speedTestRunning by profilesViewModel.speedTestRunning.collectAsState()
    val speedTestProgress by profilesViewModel.speedTestProgress.collectAsState()
    val speedTestError by profilesViewModel.speedTestError.collectAsState()
    // Failover gave up: every candidate in the group failed.
    val failoverNotice by connectionViewModel.failoverNotice.collectAsState()

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

    // OpenVPN (external app) results: app not installed, invalid
    // config, launcher failure — shown as a dismissible notice
    // under the connection panel (a successful start shows
    // nothing: the external app shows its own notification).
    var openVpnNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        connectionViewModel.openVpnMessage.collect { openVpnNotice = it }
    }

    // Random connect (item 6b): "no servers" is a local notice here.
    var randomNotice by remember { mutableStateOf<String?>(null) }
    // Speed test (item 6a): confirm the data cost before starting.
    var showSpeedTestConfirm by remember { mutableStateOf(false) }

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

    // Kill switch: same activity-scoped SettingsViewModel instance
    // the Settings screen uses (MainActivity composes both screens
    // in one activity with no NavHost), so the toggle here and the
    // one in Settings stay in sync through the same store Flow.
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val settingsState by settingsViewModel.uiState.collectAsState()

    // Locked export (.aytun): same SAF pattern, octet-stream.
    val lockedExportViewModel: LockedExportViewModel = hiltViewModel()
    var showLockedExport by remember { mutableStateOf(false) }
    var pendingLockedExport by remember { mutableStateOf<String?>(null) }
    val lockedExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val locked = pendingLockedExport ?: return@rememberLauncherForActivityResult
        pendingLockedExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(locked.toByteArray(Charsets.UTF_8))
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
                    // A new attempt clears a stale OpenVPN notice
                    // (e.g. "not installed" shown before the user
                    // installed the external app).
                    openVpnNotice = null
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
        // Group failover gave up (item 6b): every candidate failed —
        // a dismissible notice, same pattern as the OpenVPN one.
        val failoverMessage = failoverNotice
        if (failoverMessage != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = failoverMessage,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { connectionViewModel.clearFailoverNotice() },
                        ) { Text("إغلاق") }
                    }
                }
            }
        }
        // Random connect (item 6b): picks from the active group when
        // one is set, otherwise from all servers — preferring
        // recently successful ones, never blind. Disabled while a
        // tunnel is up: it is a connect action, not a switch.
        val randomConnectEnabled = profiles.isNotEmpty() &&
            (state is TunnelState.Stopped || state is TunnelState.Error)
        item {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val target = profilesViewModel.pickRandom()
                        if (target == null) {
                            randomNotice = "لا توجد خوادم للاتصال العشوائي — " +
                                "استورد خوادم أولًا."
                        } else {
                            randomNotice = null
                            openVpnNotice = null
                            profilesViewModel.select(target.id)
                            connectionViewModel.connect(target)
                        }
                    }
                },
                enabled = randomConnectEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("اتصال عشوائي") }
        }
        val randomConnectMessage = randomNotice
        if (randomConnectMessage != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = randomConnectMessage,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { randomNotice = null }) {
                            Text("إغلاق")
                        }
                    }
                }
            }
        }
        // Speed test (item 6a): only on the connected server — the
        // button never appears while disconnected, and the test never
        // starts without a tunnel (see ProfilesViewModel.runSpeedTest).
        val connectedProfile = selectedProfile
        if (state is TunnelState.Started && connectedProfile != null) {
            item {
                SpeedTestCard(
                    profileName = connectedProfile.name,
                    lastResult = speedResults[connectedProfile.id],
                    running = speedTestRunning,
                    progress = speedTestProgress,
                    error = speedTestError,
                    onTestClick = { showSpeedTestConfirm = true },
                    onCancel = { profilesViewModel.cancelSpeedTest() },
                    onDismissError = { profilesViewModel.clearSpeedTestError() },
                )
            }
        }
        // OpenVPN notice (see above): shown only when the last
        // OpenVPN attempt reported something the user must see.
        // A message carrying a link (the Play Store install link)
        // gets an install button that opens it.
        val openVpnMessage = openVpnNotice
        if (openVpnMessage != null) {
            item {
                val playUrl = Regex("https://\\S+").find(openVpnMessage)?.value
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = openVpnMessage,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (playUrl != null) {
                                Button(onClick = {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(playUrl)),
                                    )
                                }) { Text("تثبيت من متجر Play") }
                            }
                            OutlinedButton(onClick = { openVpnNotice = null }) {
                                Text("إغلاق")
                            }
                        }
                    }
                }
            }
        }
        // Free internet (SNI + SSH injector): entry point from the
        // servers screen. Shown only when the caller wires the
        // navigation (MainActivity passes it); the drawer entry is
        // the other way in. The card says "try it on your SIM" —
        // no network is marked as working before a real field test.
        if (onOpenFreeInternet != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "نت مجاني",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "جرّب النت المجاني عبر شبكات SNI + نفق SSH " +
                                "باستخدام سيرفر SSH الخاص بك — غير موثّق حتى " +
                                "تجرّبه على شريحتك.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { onOpenFreeInternet() }) {
                            Text("فتح «نت مجاني»")
                        }
                    }
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
                        text = "لا توجد خوادم بعد — استورد روابط أو أضف " +
                            "اشتراكًا من شاشة «استيراد».",
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
            // Group chips (item 6b): pick the active group — the one
            // the connect button and the random-connect button draw
            // from. "الكل" clears the choice (plain fastest pick).
            if (groups.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "المجموعات",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GroupChip(
                                label = "الكل",
                                selected = activeGroupId == null,
                                onClick = { profilesViewModel.setActiveGroup(null) },
                            )
                            for (group in groups) {
                                GroupChip(
                                    label = group.name,
                                    selected = group.id == activeGroupId,
                                    onClick = {
                                        profilesViewModel.setActiveGroup(group.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            items(profiles, key = { it.id }) { profile ->
                val ms = latency[profile.id]
                // A profile that was never probed (UDP-only transport)
                // shows no suffix at all — it was not tested, so
                // "لا يرد" would be a lie. Only a probed profile
                // that did not answer shows "لا يرد".
                val latencySuffix = when {
                    ms != null -> " — ${ms}م.ث"
                    LatencyTester.isUdpOnly(profile.type) -> ""
                    latency.containsKey(profile.id) -> " — لا يرد"
                    else -> ""
                }
                // Last measured speed (item 6a) in the same line:
                // flag + latency + last speed, Hamo-like, without
                // copying its design. No result yet → no suffix.
                val speedSuffix = speedResults[profile.id]?.let {
                    " — ${formatMbps(it.downloadMbps)}"
                } ?: ""
                ProfileRow(
                    name = profile.name,
                    typeLabel = if (profile.type == ProfileType.OPENVPN) {
                        "OpenVPN (${OvpnConfParser.protocolLabel(profile.outboundJson)})"
                    } else {
                        profile.type.name
                    },
                    address = "${profile.server}:${profile.port}$latencySuffix$speedSuffix",
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
                killSwitchEnabled = settingsState.killSwitchEnabled,
                proxyMode = settingsState.proxyMode,
                onKillSwitchChange = { settingsViewModel.onKillSwitchChange(it) },
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
                onExportLocked = { showLockedExport = true },
            )
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    if (showLockedExport) {
        LockedExportDialog(
            viewModel = lockedExportViewModel,
            onDismiss = { showLockedExport = false },
            onSaveLocked = { text ->
                pendingLockedExport = text
                val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .format(Date())
                lockedExportLauncher.launch("ayman-tunnel-locked-$stamp.aytun")
            },
            onShareLocked = { text ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(
                    Intent.createChooser(intent, "مشاركة التصدير المقفول"),
                )
            },
        )
    }

    // Speed test confirmation (item 6a): the test eats real data —
    // at least ~5MB, up to ~25MB when the connection is fast, within
    // a 10s cap (SpeedTest.INITIAL_BYTES / MAX_BYTES / TIMEOUT_MS).
    // Nothing starts without this tap.
    if (showSpeedTestConfirm) {
        val profile = selectedProfile
        AlertDialog(
            onDismissRequest = { showSpeedTestConfirm = false },
            title = { Text("اختبار السرعة") },
            text = {
                Text(
                    "سيستهلك الاختبار نحو 5 ميجا بايت على الأقل من " +
                        "باقتك، وقد يصل إلى 25 ميجا بايت إن كان الاتصال " +
                        "سريعًا (بحد أقصى 10 ثوانٍ). يقيس سرعة التنزيل " +
                        "عبر النفق المتصل حاليًا.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSpeedTestConfirm = false
                    if (profile != null) {
                        profilesViewModel.runSpeedTest(
                            profileId = profile.id,
                            isTunnelConnected = state is TunnelState.Started,
                        )
                    }
                }) { Text("ابدأ الاختبار") }
            },
            dismissButton = {
                TextButton(onClick = { showSpeedTestConfirm = false }) {
                    Text("إلغاء")
                }
            },
        )
    }
}

@Composable
private fun SettingsSection(
    batteryExempt: Boolean?,
    onRequestBatteryExemption: () -> Unit,
    killSwitchGuided: Boolean,
    onOpenVpnSettings: () -> Unit,
    killSwitchEnabled: Boolean,
    proxyMode: ProxyMode,
    onKillSwitchChange: (Boolean) -> Unit,
    autoStartOnBoot: Boolean,
    onAutoStartChange: (Boolean) -> Unit,
    adsBlocking: Boolean,
    onAdsBlockingChange: (Boolean) -> Unit,
    routingStatus: dev.aalhdydy550.tunnel.core.data.routing.RoutingDataStatus?,
    showRoutingNotice: Boolean,
    onRetryRoutingDownload: () -> Unit,
    onExportBackup: () -> Unit,
    onExportLocked: () -> Unit,
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

        // Kill switch: a real in-app toggle (the same setting as in
        // the Settings screen). When enabled, the TUN stays open
        // while the tunnel is down, so no traffic leaves the device.
        // The button below still opens the system VPN settings for
        // the stronger Always-on cover, which survives the death of
        // the whole process.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "مفتاح القتل (Kill Switch)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = killSwitchEnabled,
                        enabled = proxyMode != ProxyMode.PROXY,
                        onCheckedChange = onKillSwitchChange,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "عند تفعيله لن يخرج أي ترافيك من الجهاز إطلاقًا " +
                        "أثناء انقطاع النفق — حتى الإشعارات — إلى أن يعود " +
                        "الاتصال أو توقفه يدويًا",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "لا ينجو من موت العملية — إن قتل النظام التطبيق " +
                        "كله عاد الإنترنت مباشرة؛ والغطاء الأقوى يبقى " +
                        "Always-on في النظام.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (proxyMode == ProxyMode.PROXY) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "لا يعمل في وضع البروكسي — بلا TUN لا يوجد " +
                            "ما يُبقيه مفتوحًا.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "يسري على الاتصال التالي",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        OutlinedButton(
            onClick = onExportLocked,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("تصدير مشفّر (.aytun)") }

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
private fun GroupChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

/** "12.5 ميجابت/ث" — the single speed format for rows and cards. */
private fun formatMbps(mbps: Double): String =
    "%.1f ميجابت/ث".format(Locale.US, mbps)

private fun formatTestDate(testedAt: Long): String =
    SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(testedAt))

/**
 * Speed test card (item 6a), shown only for the connected server:
 * last result (if any) + live progress while running + a clear
 * message on failure (never a fake number). The test itself starts
 * only from the confirm dialog in [ServersScreen].
 */
@Composable
private fun SpeedTestCard(
    profileName: String,
    lastResult: SpeedTestResult?,
    running: Boolean,
    progress: SpeedTestProgress?,
    error: String?,
    onTestClick: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "اختبار السرعة",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "الخادم المتصل: $profileName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val last = lastResult
            if (last != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "آخر نتيجة: ${formatMbps(last.downloadMbps)} — " +
                        "${formatTestDate(last.testedAt)} " +
                        "(${"%.1f".format(Locale.US, last.bytesDownloaded / 1_000_000.0)} " +
                        "ميجا في ${last.durationMs} م.ث)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val currentProgress = progress
            if (running) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (currentProgress != null) {
                        "جارٍ التنزيل… ${formatMbps(currentProgress.mbps)} — " +
                            "${"%.1f".format(Locale.US, currentProgress.downloadedBytes / 1_000_000.0)} ميجا"
                    } else {
                        "جارٍ التنزيل…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val errorMessage = error
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onDismissError) { Text("إغلاق") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    OutlinedButton(onClick = onCancel) { Text("إلغاء الاختبار") }
                } else {
                    Button(onClick = onTestClick) { Text("اختبر السرعة") }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "يقيس سرعة التنزيل عبر النفق المتصل — بضغطتك " +
                    "فقط، أبدًا تلقائيًا.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
