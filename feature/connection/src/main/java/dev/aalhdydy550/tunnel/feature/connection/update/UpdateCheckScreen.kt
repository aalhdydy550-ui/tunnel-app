package dev.aalhdydy550.tunnel.feature.connection.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aalhdydy550.tunnel.core.data.stats.formatBytes

/**
 * Manual update-check screen (drawer → «التحقق من التحديثات»,
 * top-bar icon): shows the installed build, checks GitHub Releases
 * on tap, downloads the release APK straight to the cache, and hands
 * it to the system installer.
 *
 * The screen owns the install intents (it has the activity context);
 * the ViewModel owns every state before that. No check runs on
 * opening the screen — the check is manual only.
 */
@Composable
fun UpdateCheckScreen(
    viewModel: UpdateCheckViewModel,
    modifier: Modifier = Modifier,
) {
    // Plain local (not a `by` delegate): the `when` below smart-casts
    // it per branch, which the compiler does not do for delegated
    // properties.
    val state = viewModel.uiState.collectAsState().value
    val context = LocalContext.current

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // Installed build — always visible.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "الإصدار المثبَّت: ${state.installed.versionName}",
                    fontWeight = FontWeight.Bold,
                )
                Text(text = "رمز البناء: ${state.installed.versionCode}")
                if (state is UpdateCheckUiState.StoreManaged) {
                    Text(
                        text = "مثبَّت من ${state.installed.installSource.arabicName} — " +
                            "التحديثات تأتي من المتجر نفسه.",
                    )
                }
            }
        }

        when (state) {
            is UpdateCheckUiState.Idle -> {
                Text(
                    text = "يفحص آخر إصدار منشور على GitHub Releases وينزّل ملف " +
                        "APK مباشرة على هاتفك. الفحص يدوي فقط — لا فحص تلقائي " +
                        "ولا إشعارات تحديث.",
                )
                Button(
                    onClick = { viewModel.checkForUpdates() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("التحقق من التحديثات") }
            }

            is UpdateCheckUiState.Checking -> {
                CircularProgressIndicator()
                Text(text = "جارٍ فحص آخر إصدار…")
            }

            is UpdateCheckUiState.StoreManaged -> {
                Text(
                    text = "هذه النسخة مثبَّتة من " +
                        "${state.installed.installSource.arabicName}، فتحديثها " +
                        "يأتي من المتجر نفسه — الفحص من GitHub غير متاح لها.",
                )
            }

            is UpdateCheckUiState.UpToDate -> {
                Text(text = "التطبيق محدَّث ✓ — آخر إصدار منشور: ${state.releaseTag}")
                OutlinedButton(
                    onClick = { viewModel.checkForUpdates() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("إعادة الفحص") }
            }

            is UpdateCheckUiState.UpdateAvailable -> {
                val release = state.release
                Text(
                    text = "يوجد تحديث جديد: ${release.tag}",
                    fontWeight = FontWeight.Bold,
                )
                release.name?.let { Text(text = it) }
                release.apkAsset?.let { asset ->
                    Text(text = "حجم ملف APK: ${formatBytes(asset.sizeBytes)}")
                }
                release.publishedDate?.let { Text(text = "تاريخ النشر: $it") }
                Button(
                    onClick = { viewModel.downloadUpdate() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("تنزيل التحديث") }
                OutlinedButton(
                    onClick = { viewModel.checkForUpdates() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("إعادة الفحص") }
            }

            is UpdateCheckUiState.Downloading -> {
                val downloaded = state.downloadedBytes
                val total = state.totalBytes
                Text(text = "جارٍ تنزيل التحديث…", fontWeight = FontWeight.Bold)
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { downloaded.toFloat() / total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${formatBytes(downloaded)} من ${formatBytes(total)} " +
                            "(${(downloaded * 100 / total)}٪)",
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(text = "تم تنزيل ${formatBytes(downloaded)}…")
                }
                OutlinedButton(
                    onClick = { viewModel.cancelDownload() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("إلغاء التنزيل") }
            }

            is UpdateCheckUiState.Downloaded -> {
                Text(
                    text = "اكتمل التنزيل — التحديث ${state.release.tag} جاهز للتثبيت.",
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "على أندرويد 8+ يحتاج التثبيت من خارج المتجر تفعيل " +
                        "إذن «التثبيت من مصادر غير معروفة» لهذا التطبيق مرة واحدة.",
                )
                Button(
                    onClick = {
                        if (UpdateInstaller.canInstall(context)) {
                            context.startActivity(
                                UpdateInstaller.installIntent(context, state.apkFile),
                            )
                        } else {
                            context.startActivity(
                                UpdateInstaller.unknownSourcesSettingsIntent(context),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("تثبيت التحديث") }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            UpdateInstaller.unknownSourcesSettingsIntent(context),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("إعدادات التثبيت من مصادر غير معروفة") }
            }

            is UpdateCheckUiState.CheckFailed -> {
                Text(text = state.errorMessage)
                Button(
                    onClick = { viewModel.checkForUpdates() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("إعادة المحاولة") }
            }

            is UpdateCheckUiState.DownloadFailed -> {
                Text(text = state.errorMessage)
                Button(
                    onClick = { viewModel.downloadUpdate() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("إعادة التنزيل") }
                OutlinedButton(
                    onClick = { viewModel.resetToIdle() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("رجوع") }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
