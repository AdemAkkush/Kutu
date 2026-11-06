package com.kutu.chat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kutu.chat.data.ModelsRepository
import com.kutu.chat.data.ModelsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    current: String,
    state: ModelsState,
    favorites: Set<String>,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filtered = remember(state.models, query, showAll, favorites) {
        state.models
            .filter { showAll || ModelsRepository.isChatModel(it) }
            .filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareByDescending<String> { it in favorites }.thenBy { it })
    }
    val typed = query.trim()
    val canUseTyped = typed.isNotEmpty() && typed.contains('/') && state.models.none { it == typed }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Model seç", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Listeyi yenile") }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Ara veya model kimliği yaz (ör. meta/llama-3.3-70b-instruct)") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = showAll,
                    onClick = { showAll = !showAll },
                    label = { Text("Tüm modeller") },
                )
                Text(
                    "${filtered.size} model",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (canUseTyped) {
                    item {
                        ModelRow(typed, selected = typed == current, favorite = false, subtitle = "Bu kimliği doğrudan kullan",
                            onClick = { onSelect(typed) }, onToggleFavorite = null)
                    }
                }
                if (state.models.isEmpty() && !state.loading) {
                    item {
                        Text(
                            "Model listesi boş. API anahtarınızı Ayarlar'dan ekleyip yenileyin ya da model kimliğini elle yazın.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
                items(filtered, key = { it }) { id ->
                    ModelRow(
                        id,
                        selected = id == current,
                        favorite = id in favorites,
                        onClick = { onSelect(id) },
                        onToggleFavorite = { onToggleFavorite(id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    id: String,
    selected: Boolean,
    favorite: Boolean,
    subtitle: String? = null,
    onClick: () -> Unit,
    onToggleFavorite: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Default.Check, contentDescription = "Seçili", tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f)) {
            val owner = id.substringBefore('/', "")
            val name = id.substringAfter('/')
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle ?: owner,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onToggleFavorite != null) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (favorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (favorite) "Favoriden çıkar" else "Favorilere ekle",
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
