package com.kutu.chat.ui.chatlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kutu.chat.data.db.ChatEntity
import com.kutu.chat.ui.ChatListViewModel
import com.kutu.chat.ui.components.ConfirmDialog
import com.kutu.chat.ui.components.KutuLogo
import com.kutu.chat.ui.components.TextInputDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Sohbetleri "Sabitlenenler / Bugün / Dün / Son 7 gün / Son 30 gün / Daha eski" başlıkları altında gruplar. */
fun groupChats(chats: List<ChatEntity>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, List<ChatEntity>>> {
    fun label(c: ChatEntity): String {
        if (c.pinned) return "Sabitlenenler"
        val day = Instant.ofEpochMilli(c.updatedAt).atZone(zone).toLocalDate()
        val age = java.time.temporal.ChronoUnit.DAYS.between(day, today)
        return when {
            age <= 0 -> "Bugün"
            age == 1L -> "Dün"
            age <= 7 -> "Son 7 gün"
            age <= 30 -> "Son 30 gün"
            else -> "Daha eski"
        }
    }
    val order = listOf("Sabitlenenler", "Bugün", "Dün", "Son 7 gün", "Son 30 gün", "Daha eski")
    return chats.groupBy(::label).toList().sortedBy { order.indexOf(it.first) }
}

@Composable
fun DrawerContent(
    vm: ChatListViewModel,
    selectedId: Long?,
    onNewChat: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onChatDeleted: (Long) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenPersonas: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<ChatEntity?>(null) }
    var deleting by remember { mutableStateOf<ChatEntity?>(null) }
    val groups = remember(chats) { groupChats(chats) }

    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KutuLogo(40.dp)
                Column {
                    Text("Kutu", style = MaterialTheme.typography.titleLarge)
                    Text("Yapay zekâ sohbet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            FilledTonalButton(onClick = onNewChat, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Yeni sohbet", modifier = Modifier.padding(start = 8.dp))
            }

            TextField(
                value = query,
                onValueChange = { vm.query.value = it },
                placeholder = { Text("Sohbetlerde ara") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, contentDescription = "Temizle") }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )

            LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                if (chats.isEmpty()) {
                    item {
                        Text(
                            if (query.isBlank()) "Henüz sohbet yok." else "Sonuç bulunamadı.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                groups.forEach { (label, list) ->
                    item(key = "h:$label") {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { it.id }) { chat ->
                        ChatRow(
                            chat = chat,
                            selected = chat.id == selectedId,
                            onClick = { onOpenChat(chat.id) },
                            onPin = { vm.togglePin(chat) },
                            onRename = { renaming = chat },
                            onDelete = { deleting = chat },
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                DrawerLink(Icons.Default.Face, "Kişiler ve şablonlar", onOpenPersonas)
                DrawerLink(Icons.Default.Psychology, "Hafıza", onOpenMemory)
                DrawerLink(Icons.Default.Settings, "Ayarlar", onOpenSettings)
            }
        }
    }

    renaming?.let { chat ->
        TextInputDialog("Sohbeti yeniden adlandır", chat.title, "Başlık",
            onConfirm = { vm.rename(chat.id, it); renaming = null }, onDismiss = { renaming = null })
    }
    deleting?.let { chat ->
        ConfirmDialog("Sohbet silinsin mi?", "“${chat.title}” kalıcı olarak silinecek.",
            onConfirm = { vm.delete(chat.id) { onChatDeleted(chat.id) }; deleting = null }, onDismiss = { deleting = null })
    }
}

@Composable
private fun DrawerLink(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: ChatEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chat.pinned) {
            Icon(Icons.Default.PushPin, contentDescription = "Sabit", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Box(modifier = Modifier.size(6.dp))
        }
        Text(
            chat.title,
            style = MaterialTheme.typography.bodyLarge,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreHoriz, contentDescription = "Seçenekler", tint = fg.copy(alpha = 0.6f), modifier = Modifier.size(20.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (chat.pinned) "Sabitlemeyi kaldır" else "Sabitle") }, onClick = { menu = false; onPin() })
                DropdownMenuItem(text = { Text("Yeniden adlandır") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Sil") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
