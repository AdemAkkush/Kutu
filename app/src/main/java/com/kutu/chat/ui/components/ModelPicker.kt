package com.kutu.chat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kutu.chat.data.ModelsState
import com.kutu.chat.domain.Cap
import com.kutu.chat.domain.Capabilities
import com.kutu.chat.domain.ModelMeta
import java.time.LocalDate

/** Filtre çiplerinde gösterilen yetenekler (sohbet neredeyse hepsinde olduğu için çip yok). */
private val FILTER_CAPS = listOf(Cap.VISION, Cap.REASONING, Cap.CODE, Cap.TRANSLATE, Cap.DOCUMENT, Cap.FAST, Cap.AGENT)

/**
 * Gerçekten kullanılabilecek modeller. Çalışmayanlar seçicide hiç gösterilmez:
 *  - çağrıldığında 404/410 verenler ([unavailable]),
 *  - canlı katalogda olmayanlar (kaldırılmış),
 *  - kaldırılma tarihi geçmiş olanlar.
 * Katalog hiç alınamadıysa (inCatalog == null) hiçbir model elenmez.
 */
internal fun usableModels(
    models: List<ModelMeta>,
    unavailable: Set<String>,
    today: LocalDate = LocalDate.now(),
): List<ModelMeta> = models.filter {
    it.id !in unavailable && it.inCatalog != false && (it.deprecation == null || it.deprecation.isAfter(today))
}

/** Seçicideki sıra: favoriler, sonra popülerlik. */
internal fun pickerOrder(models: List<ModelMeta>, favorites: Set<String>): List<ModelMeta> = models.sortedWith(
    compareByDescending<ModelMeta> { it.id in favorites }
        .thenByDescending { it.popularity }
        .thenBy { it.id },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    current: String,
    state: ModelsState,
    favorites: Set<String>,
    unavailable: Set<String> = emptySet(),
    describe: (String) -> ModelMeta,
    /** Açılışta seçili gelecek yetenek filtresi (örn. resim eklenince görsel). */
    initialCap: Cap? = null,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var capFilter by remember { mutableStateOf(initialCap) }
    var onlyFavorites by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val metas = remember(state.models, state.catalog, describe) { state.models.map(describe) }
    val usable = remember(metas, unavailable) { usableModels(metas, unavailable) }
    val visible = remember(usable, showAll) { usable.filter { showAll || it.chatCapable } }
    val counts = remember(visible) { FILTER_CAPS.associateWith { c -> visible.count { c in it.caps } } }
    val hiddenCount = usable.size - visible.size   // gömme / güvenlik gibi sohbet dışı modeller
    // Seçili model çalışmıyorsa nedenini görebilmek için en üstte uyarıyla gösterilir.
    val brokenCurrent = remember(usable, current) {
        if (current.isNotBlank() && usable.none { it.id == current }) describe(current) else null
    }

    val filtered = remember(visible, query, capFilter, onlyFavorites, favorites) {
        pickerOrder(
            visible
                .filter { capFilter == null || capFilter in it.caps }
                .filter { !onlyFavorites || it.id in favorites }
                .filter { query.isBlank() || it.id.contains(query.trim(), ignoreCase = true) || it.display.contains(query.trim(), ignoreCase = true) },
            favorites,
        )
    }
    val typed = query.trim()
    val canUseTyped = typed.isNotEmpty() && typed.contains('/') && state.models.none { it == typed }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.88f)
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
                placeholder = { Text("Ara veya model kimliği yaz") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = capFilter == null && !onlyFavorites, onClick = { capFilter = null; onlyFavorites = false }, label = { Text("Tümü") })
                FilterChip(selected = onlyFavorites, onClick = { onlyFavorites = !onlyFavorites }, label = { Text("⭐ Favoriler") })
                FILTER_CAPS.forEach { cap ->
                    val n = counts[cap] ?: 0
                    if (n > 0 || capFilter == cap) {
                        FilterChip(
                            selected = capFilter == cap,
                            onClick = { capFilter = if (capFilter == cap) null else cap },
                            label = { Text("${cap.emoji} ${cap.label} $n") },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = showAll,
                    onClick = { showAll = !showAll },
                    label = { Text(if (hiddenCount > 0) "Sohbet dışı modelleri göster (+$hiddenCount)" else "Sohbet dışı modelleri göster") },
                )
                Text(
                    "${filtered.size} model",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.catalog.isEmpty() && state.models.isNotEmpty()) {
                Text(
                    "Yetenek bilgisi alınamadı; gruplar model adından tahmin ediliyor.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (brokenCurrent != null) {
                    item {
                        ModelRow(
                            title = brokenCurrent.display, subtitle = brokenCurrent.id.substringBefore('/', ""), selected = true,
                            favorite = false, dimmed = false,
                            warning = "Seçili model artık çalışmıyor; aşağıdan başka bir model seçin.",
                            badges = "", onClick = {}, onToggleFavorite = null,
                        )
                    }
                }
                if (canUseTyped) {
                    item {
                        ModelRow(
                            title = typed, subtitle = "Bu kimliği doğrudan kullan", selected = typed == current,
                            favorite = false, dimmed = false, warning = null, badges = "",
                            onClick = { onSelect(typed) }, onToggleFavorite = null,
                        )
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
                items(filtered, key = { it.id }) { meta ->
                    val warning = meta.deprecation?.let { "${Capabilities.formatDate(it)} tarihinde kaldırılacak" }
                    ModelRow(
                        title = meta.display,
                        subtitle = meta.id.substringBefore('/', ""),
                        selected = meta.id == current,
                        favorite = meta.id in favorites,
                        dimmed = false,
                        warning = warning,
                        badges = meta.caps.filter { it != Cap.CHAT }.joinToString(" ") { it.emoji },
                        onClick = { onSelect(meta.id) },
                        onToggleFavorite = { onToggleFavorite(meta.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    favorite: Boolean,
    dimmed: Boolean,
    warning: String?,
    badges: String,
    onClick: () -> Unit,
    onToggleFavorite: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.55f else 1f)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Default.Check, contentDescription = "Seçili", tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (badges.isNotBlank()) Text(badges, style = MaterialTheme.typography.labelMedium)
            }
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (warning != null) {
                Text(warning, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
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
