package com.kutu.chat.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kutu.chat.data.db.MemoryEntity
import com.kutu.chat.ui.MemoryViewModel
import com.kutu.chat.ui.components.ConfirmDialog
import com.kutu.chat.ui.components.TextInputDialog
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(vm: MemoryViewModel, onBack: () -> Unit) {
    val memories by vm.memories.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var deleting by remember { mutableStateOf<MemoryEntity?>(null) }
    var clearing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hafıza") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri") } },
                actions = {
                    if (memories.isNotEmpty()) {
                        IconButton(onClick = { clearing = true }) { Icon(Icons.Default.DeleteSweep, contentDescription = "Tümünü sil") }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Default.Add, contentDescription = "Ekle") }
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Burada, sohbetlerde öğrenilen ve tüm yeni sohbetlerde modelin bildiği kalıcı bilgiler tutulur. " +
                                "“Şunu hatırla: …” diyerek de ekletebilirsiniz.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        SwitchRow("Hafızayı sohbetlerde kullan", settings.memoryEnabled, vm::setMemoryEnabled)
                        SwitchRow("Sohbetlerden otomatik öğren", settings.autoMemory && settings.memoryEnabled, vm::setAutoMemory, enabled = settings.memoryEnabled)
                    }
                }
            }
            if (memories.isEmpty()) {
                item {
                    Text(
                        "Henüz kayıtlı bir şey yok. Sohbet ettikçe kendiniz hakkında paylaştıklarınız burada görünecek.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(memories, key = { it.id }) { m ->
                Card {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                m.content,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (m.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m.createdAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = m.enabled, onCheckedChange = { vm.setEnabled(m, it) })
                        IconButton(onClick = { editing = m }) { Icon(Icons.Default.Edit, contentDescription = "Düzenle") }
                        IconButton(onClick = { deleting = m }) { Icon(Icons.Default.Delete, contentDescription = "Sil") }
                    }
                }
            }
        }
    }

    if (adding) {
        TextInputDialog("Hafızaya ekle", "", "Örn. Adım Adem, İstanbul'da yaşıyorum", singleLine = false,
            onConfirm = { vm.add(it); adding = false }, onDismiss = { adding = false })
    }
    editing?.let { m ->
        TextInputDialog("Hafızayı düzenle", m.content, "Bilgi", singleLine = false,
            onConfirm = { vm.edit(m, it); editing = null }, onDismiss = { editing = null })
    }
    deleting?.let { m ->
        ConfirmDialog("Silinsin mi?", m.content, onConfirm = { vm.delete(m); deleting = null }, onDismiss = { deleting = null })
    }
    if (clearing) {
        ConfirmDialog("Tüm hafıza silinsin mi?", "Kayıtlı ${memories.size} bilgi kalıcı olarak silinecek.", "Hepsini sil",
            onConfirm = { vm.clearAll(); clearing = false }, onDismiss = { clearing = false })
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
