package dev.aalhdydy550.tunnel.feature.connection.perapp

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.aalhdydy550.tunnel.core.data.routing.PerAppProxyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-app proxy screen (drawer → «بروكسي لكل تطبيق»):
 *
 *   mode card (all / selected-only / except-selected) → search field
 *   → select/clear-all for the visible filter → app list (icon +
 *   name + package + checkbox).
 *
 * The choice is saved automatically on every tap and applies at the
 * next connect — the screen says so. Icons load lazily, one row at a
 * time and off the main thread, so a few hundred installed apps do
 * not freeze a 2 GB phone.
 */
@Composable
fun PerAppProxyScreen(
    viewModel: PerAppProxyViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }

        // Mode card.
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "وضع التوجيه",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    for (mode in PerAppProxyMode.entries) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.onModeChange(mode) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.mode == mode,
                                onClick = { viewModel.onModeChange(mode) },
                            )
                            Column {
                                Text(
                                    text = mode.arabicName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (state.mode == mode) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                )
                                Text(
                                    text = mode.arabicDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Applies-at-next-connect note.
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(
                    text = "يُحفظ اختيارك تلقائيًا، ويسري على الاتصال التالي — " +
                        "إن كان النفق يعمل الآن أعد الاتصال.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // Empty-selection warning in INCLUDE_ONLY mode: with nothing
        // selected, no app would go through the tunnel at all.
        if (state.mode == PerAppProxyMode.INCLUDE_ONLY && state.selectedPackages.isEmpty()) {
            item {
                Text(
                    text = "لم تختر أي تطبيق بعد — في هذا الوضع لن يمر أي " +
                        "تطبيق عبر النفق.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Search field.
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = { viewModel.onQueryChange(it) },
                label = { Text("بحث باسم التطبيق أو الحزمة") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Count + select/clear-all for the visible filter.
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "التطبيقات: ${state.filteredApps.size} • المحدد: " +
                        state.selectedCount,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { viewModel.onSelectAllFiltered() },
                    enabled = state.filteredApps.isNotEmpty(),
                ) { Text("تحديد الكل") }
                TextButton(
                    onClick = { viewModel.onClearFiltered() },
                    enabled = state.filteredApps.isNotEmpty(),
                ) { Text("إلغاء الكل") }
            }
        }

        if (state.isLoading) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (state.filteredApps.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (state.apps.isEmpty()) {
                            "لا توجد تطبيقات مثبتة ظاهرة — تأكد من إذن " +
                                "عرض التطبيقات."
                        } else {
                            "لا توجد نتائج مطابقة لبحثك."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            items(state.filteredApps, key = { it.packageName }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.onToggleApp(app.packageName) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(
                        viewModel = viewModel,
                        app = app,
                        modifier = Modifier.size(40.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Checkbox(
                        checked = app.packageName in state.selectedPackages,
                        onCheckedChange = { viewModel.onToggleApp(app.packageName) },
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

/**
 * One row's icon, loaded lazily off the main thread and cached by
 * composition while the row is visible — opening the screen never
 * decodes hundreds of icons at once.
 */
@Composable
private fun AppIcon(
    viewModel: PerAppProxyViewModel,
    app: InstalledApp,
    modifier: Modifier = Modifier,
) {
    val drawable by produceState<Drawable?>(initialValue = null, app.packageName) {
        value = withContext(Dispatchers.IO) { viewModel.loadIcon(app.packageName) }
    }
    // Fixed 96px decode: a vector/adaptive icon can report an
    // intrinsic size of -1, and the no-arg toBitmap() would crash on
    // it; an explicit size never does.
    val bitmap = remember(drawable) {
        runCatching { drawable?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier,
        )
    } else {
        // First-letter placeholder until (or if) the icon loads.
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.label.firstOrNull()?.uppercase().orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
