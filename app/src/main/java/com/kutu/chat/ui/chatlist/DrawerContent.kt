package com.kutu.chat.ui.chatlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kutu.chat.data.db.ChatEntity
import com.kutu.chat.ui.ChatListViewModel
import com.kutu.chat.ui.components.ConfirmDialog
import com.kutu.chat.ui.components.TextInputDialog

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawerContent(
    vm: ChatListViewModel,
    selectedId: Long?,
    onNewChat: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onChatDeleted: (Long) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<ChatEntity?>(null) }
    var deleting by remember { mutableStateOf<ChatEntity?>(null) }

    ModalDrawerSheet {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Kutu", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp))
            Text(
                "NVIDIA Build ile sohbet",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )

            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                label = { Text("Yeni sohbet") },
                selected = false,
                onClick = onNewChat,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )

            OutlinedTextField(
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
                items(chats, key = { it.id }) { chat ->
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

            HorizontalDivider()
            NavigationDrawerItem(
                icon = { Icon(Icons.Default.Psychology, contentDescription = null) },
                label = { Text("Hafıza") },
                selected = false,
                onClick = onOpenMemory,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text("Ayarlar") },
                selected = false,
                onClick = onOpenSettings,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
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
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chat.pinned) {
            Icon(Icons.Default.PushPin, contentDescription = "Sabit", modifier = Modifier.size(14.dp).padding(end = 0.dp), tint = MaterialTheme.colorScheme.primary)
            Box(modifier = Modifier.size(6.dp))
        }
        Text(
            chat.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Seçenekler") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (chat.pinned) "Sabitlemeyi kaldır" else "Sabitle") }, onClick = { menu = false; onPin() })
                DropdownMenuItem(text = { Text("Yeniden adlandır") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Sil") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
