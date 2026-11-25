package dev.aalhdydy550.tunnel.feature.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import dev.aalhdydy550.tunnel.core.data.groups.ConnectionGroup
import dev.aalhdydy550.tunnel.core.data.groups.GroupMode
import dev.aalhdydy550.tunnel.core.data.model.Profile

/**
 * Connection groups screen (drawer → «المجموعات», item 6b):
 *
 *   create / rename / delete a group → pick its auto-select mode
 *   (fastest / random / round-robin; fastest is the default) →
 *   add / remove servers → set the active group (the one the
 *   connect button and the random-connect button draw from).
 *
 * Everything is Arabic; the active group is the only one whose
 * members take part in the automatic pick and in the failover.
 */
@Composable
fun GroupsScreen(
    viewModel: GroupsViewModel,
    modifier: Modifier = Modifier,
) {
    val groups by viewModel.groups.collectAsState()
    val activeGroupId by viewModel.activeGroupId.collectAsState()
    val profiles by viewModel.profiles.collectAsState()

    var newGroupName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ConnectionGroup?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ConnectionGroup?>(null) }
    // Which group's "add server" list is open (one at a time).
    var expandedAddFor by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }
        item {
            Text(
                text = "المجموعات",
                style = MaterialTheme.typography.titleLarge,
            )
        }
        item {
            Text(
                text = "مجموعة خوادم يختار منها التطبيق تلقائيًا عند " +
                    "الاتصال — وعند فشل خادم يجرّب بديلًا من المجموعة " +
                    "نفسها (محاولتان كحد أقصى). المجموعة النشطة هي " +
                    "التي يُؤخذ منها الاختيار والتجاوز.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "مجموعة جديدة",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        label = { Text("اسم المجموعة") },
                        placeholder = { Text("مثلًا: خوادم أوروبا") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.createGroup(newGroupName)
                            newGroupName = ""
                        },
                        enabled = newGroupName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("إنشاء مجموعة")
                    }
                }
            }
        }
        if (groups.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "لا توجد مجموعات بعد — أنشئ مجموعة ثم " +
                            "أضف إليها خوادم من القائمة.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        items(groups, key = { it.id }) { group ->
            GroupCard(
                group = group,
                isActive = group.id == activeGroupId,
                profiles = profiles,
                addExpanded = expandedAddFor == group.id,
                onToggleAdd = {
                    expandedAddFor = if (expandedAddFor == group.id) null else group.id
                },
                onSetActive = { viewModel.setActiveGroup(group.id) },
                onClearActive = { viewModel.setActiveGroup(null) },
                onSetMode = { viewModel.setMode(group.id, it) },
                onAddProfile = { viewModel.addProfile(group.id, it) },
                onRemoveProfile = { viewModel.removeProfile(group.id, it) },
                onRename = {
                    renameTarget = group
                    renameText = group.name
                },
                onDelete = { deleteTarget = group },
            )
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    val renameGroup = renameTarget
    if (renameGroup != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("تسمية المجموعة") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("اسم المجموعة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameGroup(renameGroup.id, renameText)
                        renameTarget = null
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("إلغاء") }
            },
        )
    }

    val deleteGroup = deleteTarget
    if (deleteGroup != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("حذف المجموعة؟") },
            text = {
                Text("ستُحذف مجموعة «${deleteGroup.name}» نهائيًا. " +
                    "الخوادم نفسها تبقى في القائمة.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteGroup(deleteGroup.id)
                    deleteTarget = null
                }) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("إلغاء") }
            },
        )
    }
}

private fun modeDescription(mode: GroupMode): String = when (mode) {
    GroupMode.FASTEST -> "يختار الأقل زمن استجابة — الافتراضي."
    GroupMode.RANDOM -> "اختيار عشوائي يفضّل الخادم الناجح حديثًا."
    GroupMode.ROUND_ROBIN -> "يتناوب على خوادم المجموعة بالترتيب."
}

@Composable
private fun GroupCard(
    group: ConnectionGroup,
    isActive: Boolean,
    profiles: List<Profile>,
    addExpanded: Boolean,
    onToggleAdd: () -> Unit,
    onSetActive: () -> Unit,
    onClearActive: () -> Unit,
    onSetMode: (GroupMode) -> Unit,
    onAddProfile: (Long) -> Unit,
    onRemoveProfile: (Long) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val members = profiles.filter { it.id in group.profileIds }
    val nonMembers = profiles.filter { it.id !in group.profileIds }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRename) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "تسمية المجموعة",
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "حذف المجموعة",
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (isActive) {
                Text(
                    text = "المجموعة النشطة ✓",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onClearActive) {
                    Text("إلغاء التنشيط")
                }
            } else {
                OutlinedButton(onClick = onSetActive) {
                    Text("تعيين نشطة")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "وضع الاختيار",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in GroupMode.entries) {
                    if (mode == group.mode) {
                        Button(onClick = { /* already selected */ }) {
                            Text(mode.arabicLabel)
                        }
                    } else {
                        OutlinedButton(onClick = { onSetMode(mode) }) {
                            Text(mode.arabicLabel)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = modeDescription(group.mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "الخوادم (${members.size})",
                style = MaterialTheme.typography.titleSmall,
            )
            if (members.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "بلا خوادم بعد — أضف خوادم من الأسفل.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (member in members) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${member.flagEmoji} ${member.name}".trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onRemoveProfile(member.id) }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "حذف ${member.name} من المجموعة",
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onToggleAdd) {
                Text(if (addExpanded) "إغلاق قائمة الإضافة" else "إضافة خادم")
            }
            if (addExpanded) {
                if (nonMembers.isEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "كل الخوادم في المجموعة بالفعل.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (profile in nonMembers) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${profile.flagEmoji} ${profile.name}".trim(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onAddProfile(profile.id) }) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "إضافة ${profile.name}",
                            )
                        }
                    }
                }
            }
        }
    }
}
