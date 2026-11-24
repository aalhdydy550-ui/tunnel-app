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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aalhdydy550.tunnel.feature.connection.TunnelState
import dev.aalhdydy550.tunnel.feature.connection.injector.payload.InjectorMode
import dev.aalhdydy550.tunnel.feature.connection.injector.payload.ScanStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Free internet" screen (drawer → «نت مجاني»):
 *
 *   warning card (verbatim) → transport mode (SNI / HTTP inject) →
 *   HTTP inject settings (payload template + custom template + proxy,
 *   only in HTTP mode) → SIM carrier card (detected + manual choice)
 *   → SSH profile picker (or the import guidance when there is none)
 *   → connect/disconnect → probe result → host scanner (manual tap
 *   only) → candidate networks (name + SNI host + carrier + status
 *   badge).
 *
 * Status rule, stated on every row: the default badge is
 * «جرّب على شريحتك — غير موثقة»; only a network this phone verified
 * itself (successful connection + passing probe) shows
 * «موثّقة على شريحتك ✓» with its date. No network is ever shown as
 * "working" before that. A scanner SUCCESS only means "the proxy
 * answered 200 for this host" — never free data.
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
    val injectorMode by viewModel.injectorMode.collectAsState()
    val proxyHost by viewModel.proxyHost.collectAsState()
    val allTemplates by viewModel.allTemplates.collectAsState()
    val selectedTemplate by viewModel.selectedTemplate.collectAsState()
    val extraScanHost by viewModel.extraScanHost.collectAsState()
    val scanResults by viewModel.scanResults.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val actionError by viewModel.actionError.collectAsState()

    // Custom-template editor (transient input; saved via the ViewModel).
    var customName by remember { mutableStateOf("") }
    var customText by remember { mutableStateOf("") }

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
    val canConnect = selectedNetwork != null && selectedSsh != null && !busy &&
        (injectorMode == InjectorMode.SNI || proxyHost.isNotBlank())

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

        // Transport mode: SNI (current) or HTTP inject (item 3).
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "طريقة الاتصال",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectMode(InjectorMode.SNI) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = injectorMode == InjectorMode.SNI,
                            onClick = { viewModel.selectMode(InjectorMode.SNI) },
                        )
                        Column {
                            Text(
                                text = "SNI عبر TLS",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "الطريقة الحالية — TLS باسم الشبكة.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectMode(InjectorMode.HTTP) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = injectorMode == InjectorMode.HTTP,
                            onClick = { viewModel.selectMode(InjectorMode.HTTP) },
                        )
                        Column {
                            Text(
                                text = "حقن HTTP",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "CONNECT عبر بروكسي الشريحة — تجريبي، غير موثّق.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (injectorMode == InjectorMode.HTTP) {
                        Text(
                            text = "الحقن عبر HTTP تقنية تجريبية غير موثّقة — " +
                                "قد تخالف شروط شركة الاتصالات، ولا ضمان أنها " +
                                "تعطي نتًا مجانيًا. الحكم اختبار ميداني " +
                                "بعدّاد الباقة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // HTTP inject settings: template + custom template + proxy.
        if (injectorMode == InjectorMode.HTTP) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "قالب الحمولة",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        for (template in allTemplates) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectTemplate(template.id) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedTemplate.id == template.id,
                                    onClick = { viewModel.selectTemplate(template.id) },
                                )
                                Text(
                                    text = template.nameAr +
                                        if (template.isCustom) " (مخصص)" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (template.isCustom) {
                                    TextButton(
                                        onClick = { viewModel.deleteCustomTemplate(template.id) },
                                    ) {
                                        Text(text = "حذف")
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "قالب مخصص جديد",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        OutlinedTextField(
                            value = customName,
                            onValueChange = { customName = it },
                            label = { Text(text = "اسم القالب") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = customText,
                            onValueChange = { customText = it },
                            label = { Text(text = "نص الطلب — استخدم {host} و{port} و{sni}") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (viewModel.saveCustomTemplate(customName, customText)) {
                                    customName = ""
                                    customText = ""
                                }
                            },
                        ) {
                            Text(text = "حفظ القالب المخصص")
                        }
                    }
                }
            }
        }

        // Carrier-proxy address: used by the HTTP inject connect AND
        // by the host scanner, so it is shown in both modes.
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "بروكسي الشريحة",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = { viewModel.setProxyHost(it) },
                        label = { Text(text = "عنوان البروكسي") },
                        placeholder = { Text(text = "host أو host:port") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "من إعدادات APN أو من شركة الاتصالات — " +
                            "لا تُخترع قيمة. المنفذ الافتراضي 80 عند عدم كتابته. " +
                            "يستخدمه الحقن HTTP وفاحص الهوستات.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                        injectorMode == InjectorMode.HTTP -> "اتصال بالحقن HTTP"
                        else -> "اتصال بالنت المجاني"
                    },
                )
            }
            if (actionError != null) {
                Text(
                    text = actionError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
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

        // Host scanner: checks the selected carrier's SNI hosts
        // through the user-entered proxy (manual tap only).
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "فاحص الهوستات",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "يفحص هوستات شبكات الشركة المختارة عبر " +
                            "البروكسي أعلاه: هل يرد البروكسي 200 لهذا الهوست؟ " +
                            "النجاح هنا يعني الرد فقط — لا يعني نتًا مجانيًا.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = extraScanHost,
                        onValueChange = { viewModel.setExtraScanHost(it) },
                        label = { Text(text = "هوست إضافي للفحص (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.scanHosts() },
                        enabled = scanState !is ScanState.Running && proxyHost.isNotBlank(),
                    ) {
                        Text(text = "افحص الهوستات")
                    }
                    when (val state = scanState) {
                        ScanState.Idle -> Unit
                        ScanState.Running -> Text(
                            text = "جارٍ فحص الهوستات…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        is ScanState.Done -> Text(
                            text = "فُحص ${state.checked} هوست — نجح ${state.succeeded}.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        is ScanState.Failed -> Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    val candidateHosts = candidates.map { it.sniHost.lowercase(Locale.US) }
                    for (result in scanResults) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = result.host,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = when (result.status) {
                                            ScanStatus.SUCCESS ->
                                                "ناجح ✓ — البروكسي رد 200" +
                                                    (result.latencyMs?.let { " (${it}ms)" } ?: "") +
                                                    " — ${formatDate(result.checkedAt)}"
                                            ScanStatus.FAILED ->
                                                "فشل — البروكسي رفض أو لم يصل — ${formatDate(result.checkedAt)}"
                                            ScanStatus.TIMEOUT ->
                                                "مهلة — البروكسي قبل الاتصال ولم يرد — ${formatDate(result.checkedAt)}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (result.status == ScanStatus.SUCCESS) {
                                            MaterialTheme.colorScheme.secondary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                                if (result.status == ScanStatus.SUCCESS &&
                                    result.host.lowercase(Locale.US) in candidateHosts
                                ) {
                                    TextButton(
                                        onClick = {
                                            viewModel.selectNetworkBySniHost(result.host)
                                        },
                                    ) {
                                        Text(text = "اختر هذه الشبكة")
                                    }
                                }
                            }
                        }
                    }
                }
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
