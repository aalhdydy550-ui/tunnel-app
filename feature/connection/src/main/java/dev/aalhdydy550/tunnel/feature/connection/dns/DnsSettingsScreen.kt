package dev.aalhdydy550.tunnel.feature.connection.dns

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aalhdydy550.tunnel.core.data.net.DnsPreset

/**
 * DNS switcher screen (drawer → «DNS»):
 *
 *   note card (AUTO is the default and keeps the fastest-resolver
 *   probe) → resolver list (AUTO + Cloudflare / Google / Quad9 /
 *   AliDNS / custom) → custom host field + save button → current
 *   effective server.
 *
 * The screen only displays and changes the choice; the engine
 * ([DnsSelector] + the connect-time probe) is untouched, and the
 * choice applies at the next connect.
 */
@Composable
fun DnsSettingsScreen(
    viewModel: DnsSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // Default-behaviour note.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Text(
                text = "الافتراضي «تلقائي»: عند كل اتصال يفحص التطبيق " +
                    "خوادم DNS العامة ويختار الأسرع. اختيارك هنا يثبّت " +
                    "خادمًا واحدًا بدل الفحص، ويسري على الاتصال التالي.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }

        // Resolver list.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "خادم DNS",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                for (preset in DnsPreset.entries) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onPresetSelected(preset) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.preset == preset,
                            onClick = { viewModel.onPresetSelected(preset) },
                        )
                        Column {
                            Text(
                                text = preset.arabicName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (state.preset == preset) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                },
                            )
                            val subtitle = when (preset) {
                                DnsPreset.AUTO -> "يفحص الأسرع عند الاتصال"
                                DnsPreset.CUSTOM -> "خادم تدخله بنفسك"
                                else -> preset.address
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Custom host field (only for CUSTOM).
        if (state.preset == DnsPreset.CUSTOM) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "خادم DNS مخصّص",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.customInput,
                        onValueChange = { viewModel.onCustomInputChange(it) },
                        label = { Text("عنوان IP أو اسم نطاق") },
                        placeholder = { Text("مثال: 9.9.9.9") },
                        singleLine = true,
                        isError = state.customError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.customError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.customError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.onSaveCustom() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("حفظ الخادم المخصّص")
                    }
                }
            }
        }

        // Current effective server.
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "الخادم الفعّال الآن: " + (
                    state.effectiveDotAddress
                        ?: "تلقائي (يُحدَّد عند الاتصال)"
                    ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
