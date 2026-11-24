package dev.aalhdydy550.tunnel.feature.connection.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.aalhdydy550.tunnel.core.data.repository.ProxyMode
import dev.aalhdydy550.tunnel.feature.connection.TcpNoDelayViewModel

/**
 * Settings screen (drawer → «الإعدادات»):
 *
 *   local proxy port (field + save button) → connection mode
 *   (VPN / proxy-only switch) → LAN sharing switch with
 *   its warning → allowInsecure switch with its warning → TCP No
 *   Delay switch.
 *
 * The port and the switches only change what the next connection
 * uses; the screen says so. Everything is saved through the
 * ViewModels — the port on «حفظ البورت», the switches immediately.
 */
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    tcpNoDelayViewModel: TcpNoDelayViewModel,
) {
    val state by settingsViewModel.uiState.collectAsState()
    val tcpNoDelayEnabled by tcpNoDelayViewModel.enabled.collectAsState()

    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "الإعدادات",
            style = MaterialTheme.typography.titleLarge,
        )

        // Local proxy port.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "البروكسي المحلي",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.portInput,
                    onValueChange = { settingsViewModel.onPortInputChange(it) },
                    label = { Text("البورت") },
                    placeholder = { Text("10808") },
                    singleLine = true,
                    isError = state.portError != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                val portError = state.portError
                if (portError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = portError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { settingsViewModel.onSavePort() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("حفظ البورت")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "الافتراضي 10808 — يسري على الاتصال التالي",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Connection mode: VPN (whole-device tunnel) or proxy-only.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "وضع البروكسي فقط",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.proxyMode == ProxyMode.PROXY,
                        onCheckedChange = {
                            settingsViewModel.onProxyModeChange(
                                if (it) ProxyMode.PROXY else ProxyMode.VPN,
                            )
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "وضع البروكسي لا يوجّه ترافيك أي تطبيق تلقائيًا — " +
                        "يعمل فقط للتطبيقات التي تضبط بروكسي يدويًا على " +
                        "127.0.0.1:10808؛ وبلا per-app وبلا kill-switch " +
                        "وبلا التقاط DNS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "يسري على الاتصال التالي",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // LAN sharing.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "مشاركة الاتصال مع أجهزة أخرى",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.allowOtherDevices,
                        onCheckedChange = {
                            settingsViewModel.onAllowOtherDevicesChange(it)
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "أجهزة أخرى تستطيع الاتصال بالبروكسي عبر عنوان " +
                        "IP الخاص بهاتفك (socks/http) — فعّل هذا فقط في " +
                        "شبكة موثوقة لتجنب الاتصالات غير المصرّح بها.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Allow insecure TLS.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "allowInsecure",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.allowInsecure,
                        onCheckedChange = {
                            settingsViewModel.onAllowInsecureChange(it)
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "يتخطى التحقق من شهادة TLS — يُضعف الأمان، " +
                        "فعّله فقط إن كنت تعرف ما تفعله.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // TCP No Delay.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "TCP No Delay",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = tcpNoDelayEnabled,
                        onCheckedChange = { tcpNoDelayViewModel.setEnabled(it) },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "يسري على الاتصال التالي",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
