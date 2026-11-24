package dev.aalhdydy550.tunnel.feature.profiles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.aalhdydy550.tunnel.core.data.backup.LockedExport
import dev.aalhdydy550.tunnel.core.data.profile.SubscriptionFetcher

/**
 * Import screen: paste links (one per line), paste from the clipboard,
 * or pick a file (link list, WireGuard `.conf`, OpenVPN `.ovpn`,
 * sing-box JSON, or a JSON backup from this app). A locked export
 * (`.aytun` file, `aytun://v1` text, or QR) is unlocked with its
 * password in the "locked import" card below the file picker.
 * Subscriptions — user-provided URLs that serve a link list — are
 * added below and refresh daily.
 *
 * Supported links: vless / vmess / trojan / ss / hysteria2 / tuic /
 * ssh. ZIVPN links are recognized and rejected with a Phase 2 note.
 */
@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    var countryCode by remember { mutableStateOf("") }
    var subscriptionUrl by remember { mutableStateOf("") }
    var lockedPassword by remember { mutableStateOf("") }
    val outcome by viewModel.outcome.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val pendingLockedText by viewModel.pendingLockedText.collectAsState()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) viewModel.importUri(uri)
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "استيراد خوادم",
            style = MaterialTheme.typography.titleLarge,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; viewModel.clearOutcome() },
            label = { Text("الصق الروابط هنا (رابط في كل سطر)") },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            maxLines = 12,
        )
        OutlinedTextField(
            value = countryCode,
            onValueChange = { countryCode = it.uppercase().take(2) },
            label = { Text("رمز الدولة (اختياري — مثال: DE)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { viewModel.importText(text, countryCode) },
                enabled = text.isNotBlank() && !importing,
                modifier = Modifier.weight(1f),
            ) { Text(if (importing) "جارٍ الاستيراد…" else "استيراد") }
            OutlinedButton(
                onClick = {
                    viewModel.pasteFromClipboard()?.let { text = it }
                },
                modifier = Modifier.weight(1f),
            ) { Text("لصق من الحافظة") }
        }
        OutlinedButton(
            onClick = { filePicker.launch("*/*") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("اختيار ملف (روابط / WireGuard .conf / OpenVPN .ovpn / JSON)") }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "استيراد مقفول",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "ملف .aytun أو نص aytun://v1 — أدخل كلمة السر " +
                        "التي قُفل بها التصدير. الصق النص في الحقل " +
                        "الرئيسي بالأعلى أو اختر ملفًا أو امسح رمز QR " +
                        "أدناه، ثم أدخل كلمة السر هنا.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = lockedPassword,
                    onValueChange = { lockedPassword = it },
                    label = { Text("كلمة سر التصدير المقفول") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Button(
                    onClick = {
                        val locked = pendingLockedText ?: text.trim()
                        viewModel.importLockedText(
                            locked,
                            lockedPassword,
                            countryCode,
                        )
                        lockedPassword = ""
                    },
                    enabled = (pendingLockedText != null ||
                        text.trim().startsWith("aytun://")) &&
                        lockedPassword.isNotEmpty() && !importing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("فتح الاستيراد") }
                if (pendingLockedText != null) {
                    Text(
                        text = "يوجد تصدير مقفول بانتظار كلمة السر — " +
                            "أدخلها ثم اضغط «فتح الاستيراد».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                QrImportSection(
                    onQrText = { qrText ->
                        val trimmed = qrText.trim()
                        when {
                            LockedExport.isLockedText(trimmed) ->
                                viewModel.importText(trimmed, "")

                            SubscriptionFetcher.isAllowedSubscriptionUrl(trimmed) ->
                                viewModel.importSubscription(trimmed)

                            else -> viewModel.importText(trimmed, countryCode)
                        }
                    },
                )
            }
        }
        Text(
            text = "الاشتراكات",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "رابط اشتراك (من مزودك) يُجلب كل يوم تلقائيًا. التحديث " +
                "يستبدل خوادم الاشتراك فقط — خوادمك اليدوية لا تُمسّ، " +
                "والفشل في الجلب يُبقي الخوادم القديمة.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = subscriptionUrl,
            onValueChange = { subscriptionUrl = it; viewModel.clearOutcome() },
            label = { Text("رابط الاشتراك (https://…)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = {
                viewModel.importSubscription(subscriptionUrl)
                subscriptionUrl = ""
            },
            enabled = subscriptionUrl.isNotBlank() && !importing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("إضافة اشتراك وتحديثه الآن") }
        subscriptions.forEach { subscription ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = subscription.name,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = subscription.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (subscription.lastFetchedAt > 0) {
                            "آخر تحديث: " +
                                formatTime(subscription.lastFetchedAt) +
                                " — ${subscription.lastProfileCount} خادم."
                        } else {
                            "لم يُجلب بعد."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (subscription.lastError.isNotBlank()) {
                        Text(
                            text = subscription.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.refreshSubscription(subscription.id)
                            },
                            enabled = !importing,
                        ) { Text("تحديث") }
                        OutlinedButton(
                            onClick = {
                                viewModel.deleteSubscription(subscription.id)
                            },
                        ) { Text("حذف") }
                    }
                }
            }
        }
        outcome?.let { result ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (result.imported > 0) {
                            "تم استيراد ${result.imported} خادم."
                        } else {
                            "لم يتم استيراد أي خادم."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (result.errors.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        result.errors.forEach { error ->
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = "المدعوم: vless، vmess، trojan، shadowsocks، hysteria2، " +
                "tuic، ssh، وملف WireGuard .conf، وملف OpenVPN .ovpn، ورابط اشتراك، " +
                "والاستيراد المقفول .aytun (نص aytun://v1 أو ملف أو QR). روابط " +
                "ZIVPN ستأتي في المرحلة 2. مفاتيح SSH الخاصة تُستورد عبر " +
                "ملف JSON لـsing-box وليس عبر رابط.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTime(epochMs: Long): String {
    val format = java.text.SimpleDateFormat(
        "yyyy-MM-dd HH:mm",
        java.util.Locale.getDefault(),
    )
    return format.format(java.util.Date(epochMs))
}
