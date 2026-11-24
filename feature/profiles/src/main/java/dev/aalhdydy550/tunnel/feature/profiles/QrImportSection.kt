package dev.aalhdydy550.tunnel.feature.profiles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aalhdydy550.tunnel.core.data.backup.LockedExport
import dev.aalhdydy550.tunnel.core.data.profile.QrCodeDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * QR import buttons for the import screen — camera scan + gallery
 * image. Batch 1, item #1 (§4-#1 in the hamo-like-apps report).
 *
 * No CameraX: it is not in the project, and adding a CameraX preview
 * pipeline would cost memory and a new dependency on a 2 GB phone
 * for a job a single photo does. The camera path is the system camera
 * app via [ActivityResultContracts.TakePicturePreview] — it needs no
 * CAMERA permission of its own (the camera app holds it) and returns
 * a small bitmap, which is exactly what QR decoding needs. Limitation
 * to know: the preview bitmap is a thumbnail, so the QR code must
 * fill most of the frame; a full-resolution capture (TakePicture +
 * FileProvider) is a later refinement, not a blocker.
 *
 * This composable is deliberately ViewModel-free: it reports the
 * scanned text through [onQrText] and the caller routes it through
 * the existing import path — a proxy link via
 * `ImportViewModel.importText`, or a subscription URL via
 * `ImportViewModel.importSubscription` when
 * `SubscriptionFetcher.isAllowedSubscriptionUrl` says so. The text is
 * validated here with [QrCodeDecoder.isImportableQrText] first, so a
 * QR holding a website or plain text never reaches the ViewModel.
 *
 * Not yet wired into [ImportScreen]: that file is owned by the
 * removal coordinator in this batch — see the integration steps in
 * the batch summary. Wiring is two buttons + one callback.
 */
@Composable
fun QrImportSection(
    onQrText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun handleDecoded(text: String?) {
        busy = false
        when {
            text.isNullOrBlank() -> {
                status = "لم يُعثر على رمز QR في الصورة — قرّب الرمز واملأ به الإطار"
            }
            !LockedExport.isLockedText(text) && !QrCodeDecoder.isImportableQrText(text) -> {
                status = "رمز QR لا يحمل رابط خادم أو اشتراك أو تصديرًا مقفولًا صالحًا"
            }
            else -> {
                status = null
                onQrText(text.trim())
            }
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch(Dispatchers.Default) {
            val text = runCatching {
                QrCodeBitmapDecoder.decodeUri(context, uri)
            }.getOrNull()
            withContext(Dispatchers.Main) { handleDecoded(text) }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview(),
    ) { bitmap ->
        if (bitmap == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch(Dispatchers.Default) {
            val text = runCatching {
                QrCodeBitmapDecoder.decodeBitmap(bitmap)
            }.getOrNull()
            withContext(Dispatchers.Main) { handleDecoded(text) }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { cameraLauncher.launch(null) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("مسح QR بالكاميرا") }
            OutlinedButton(
                onClick = { galleryPicker.launch("image/*") },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("QR من صورة") }
        }
        if (busy) {
            Text(
                text = "جارٍ قراءة الرمز…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
