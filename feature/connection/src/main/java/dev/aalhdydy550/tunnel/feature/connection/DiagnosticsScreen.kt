package dev.aalhdydy550.tunnel.feature.connection

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Diagnostics screen (#9): the [TunnelEventLog] ring buffer as a
 * list — time + kind + message per event — with a kind filter, a
 * copy button, and a clear button.
 *
 * Performance on a 2 GB phone: the engine caps the log at 100 events
 * and the list is a [LazyColumn], so only visible rows are composed.
 * The log lives in memory only; clearing it here does not touch the
 * Logcat mirror.
 */
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    modifier: Modifier = Modifier,
) {
    val events by viewModel.events.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val totalCount by viewModel.totalCount.collectAsState()
    val copied by viewModel.copied.collectAsState()
    val clipboard = LocalClipboardManager.current
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "سجل الاتصال والتشخيص",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "آخر $totalCount حدثًا (بحد أقصى 100، في الذاكرة فقط). " +
                "سبب كل انقطاع يظهر هنا بنص واضح.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Kind filter chips (Button = selected, OutlinedButton = not).
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterButton(
                label = "الكل",
                selected = filter == null,
                onClick = { viewModel.setFilter(null) },
            )
            for (kind in DiagnosticLogKind.entries) {
                FilterButton(
                    label = kind.label,
                    selected = filter == kind,
                    onClick = { viewModel.setFilter(kind) },
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(viewModel.formattedLogText()))
                    viewModel.markCopied()
                },
                enabled = events.isNotEmpty(),
            ) {
                Text("نسخ السجل")
            }
            OutlinedButton(
                onClick = { showClearConfirm = true },
                enabled = totalCount > 0,
            ) {
                Text("مسح السجل")
            }
            OutlinedButton(onClick = { viewModel.refresh() }) {
                Text("تحديث")
            }
        }
        if (copied) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "تم نسخ السجل إلى الحافظة.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF009966),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        if (events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (totalCount == 0) {
                        "لا توجد أحداث بعد — ستظهر هنا أحداث الاتصال والانقطاع."
                    } else {
                        "لا توجد أحداث من هذا النوع."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(events) { row ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "${row.timeText} — ${row.kind.label}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = row.message,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("مسح سجل التشخيص؟") },
            text = {
                Text(
                    "سيُمسح السجل الظاهر هنا (في الذاكرة فقط). " +
                        "نسخة الأحداث في Logcat تبقى كما هي.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearLog()
                        showClearConfirm = false
                    },
                ) { Text("مسح") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("إلغاء") }
            },
        )
    }
}

@Composable
private fun FilterButton(
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
