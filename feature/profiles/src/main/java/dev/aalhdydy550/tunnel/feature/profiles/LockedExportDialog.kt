package dev.aalhdydy550.tunnel.feature.profiles

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Locked-export dialog (item 4 of the "better app" round).
 *
 * Lets the user pick profiles, enter a lock password twice, lock the
 * selection into an `aytun://v1.…` text, and then save it as a
 * `.aytun` file, share the text, or show it as a QR code (one profile
 * only). The dialog itself never launches SAF or a share intent —
 * everything goes back to the parent through the callbacks.
 */
@Composable
fun LockedExportDialog(
    viewModel: LockedExportViewModel,
    onDismiss: () -> Unit,
    onSaveLocked: (String) -> Unit,
    onShareLocked: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    // The lock runs async: when the user asked to save/share, wait for
    // the locked text to arrive and then hand it to the parent.
    var pendingSave by remember { mutableStateOf(false) }
    var pendingShare by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.lockedText) {
        val locked = uiState.lockedText ?: return@LaunchedEffect
        if (pendingSave) {
            pendingSave = false
            onSaveLocked(locked)
        }
        if (pendingShare) {
            pendingShare = false
            onShareLocked(locked)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "تصدير مشفّر (.aytun)",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "اختر البروفايلات واكتب كلمة سر — يُقفل التصدير " +
                        "بالتشفير ولا يُفتح إلا بها.",
                    style = MaterialTheme.typography.bodySmall,
                )

                // Profile checklist.
                if (uiState.profiles.isEmpty()) {
                    Text(
                        text = "لا توجد بروفايلات بعد — استورد بروفايلًا أولًا.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = { viewModel.selectAll() }) {
                            Text("تحديد الكل")
                        }
                        TextButton(onClick = { viewModel.clearSelection() }) {
                            Text("إلغاء التحديد")
                        }
                    }
                    uiState.profiles.forEach { profile ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.toggleSelect(profile.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = profile.id in uiState.selectedIds,
                                onCheckedChange = { viewModel.toggleSelect(profile.id) },
                            )
                            Column {
                                Text(
                                    text = profile.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "${profile.type.name} — ${profile.server}:${profile.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = uiState.password,
                    onValueChange = { viewModel.setPassword(it) },
                    label = { Text("كلمة سر القفل") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = uiState.confirm,
                    onValueChange = { viewModel.setConfirm(it) },
                    label = { Text("تأكيد كلمة السر") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "لا توجد طريقة لاستعادة كلمة السر — احفظها في مكان آمن.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "هذا قفل تشفيري فقط — بلا انتهاء صلاحية وبلا حد " +
                        "استخدامات في هذا الإصدار.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "كلمة سر البروفايل الأصلية تعود نصًا عاديًا بعد الاستيراد.",
                    style = MaterialTheme.typography.bodySmall,
                )

                uiState.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (uiState.lockedText != null) {
                    Text(
                        text = "تم القفل — يمكنك الآن حفظ الملف أو مشاركة النص.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }

                Button(
                    onClick = {
                        val locked = uiState.lockedText
                        if (locked != null) {
                            onSaveLocked(locked)
                        } else {
                            pendingSave = true
                            viewModel.lockSelected()
                        }
                    },
                    enabled = !uiState.busy && uiState.profiles.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("قفل وحفظ ملف .aytun") }

                OutlinedButton(
                    onClick = {
                        val locked = uiState.lockedText
                        if (locked != null) {
                            onShareLocked(locked)
                        } else {
                            pendingShare = true
                            viewModel.lockSelected()
                        }
                    },
                    enabled = !uiState.busy && uiState.profiles.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("مشاركة النص") }

                // QR: one profile only.
                if (uiState.selectedIds.size == 1) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { viewModel.buildQr() },
                        enabled = !uiState.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("عرض QR") }
                    val pixels = uiState.qrPixels
                    val size = uiState.qrSize
                    if (pixels != null && size > 0) {
                        val bitmap = remember(pixels, size) {
                            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                                .also { it.setPixels(pixels, 0, size, 0, 0, size, size) }
                        }
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "رمز QR للبروفايل المقفول",
                            modifier = Modifier
                                .size(256.dp)
                                .align(Alignment.CenterHorizontally),
                        )
                        Text(
                            text = "امسح الرمز من جهاز آخر — يحمل بروفايلًا واحدًا فقط.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("إغلاق") }
            }
        }
    }
}
