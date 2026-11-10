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
import androidx.compose.material3.Button
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
import com.kutu.chat.data.AppSettings
import com.kutu.chat.data.ModelsState
import com.kutu.chat.domain.Cap
import com.kutu.chat.domain.Capabilities
import com.kutu.chat.domain.ModelMeta
import com.kutu.chat.domain.ModelRef
import com.kutu.chat.domain.Providers
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
    keyOf: (ModelMeta) -> String = { it.id },
): List<ModelMeta> = models.filter {
    keyOf(it) !in unavailable && it.inCatalog != false && (it.deprecation == null || it.deprecation.isAfter(today))
}

/** Seçicideki sıra: favoriler, sonra popülerlik. */
internal fun pickerOrder(models: List<ModelMeta>, favorites: Set<String>, keyOf: (ModelMeta) -> String = { it.id }): List<ModelMeta> =
    models.sortedWith(
        compareByDescending<ModelMeta> { keyOf(it) in favorites }
            .thenByDescending { it.popularity }
            .thenBy { it.id },
    )

/** Bir sağlayıcının model listesini yetenek bilgisiyle birleştirir (katalog yalnızca NVIDIA için vardır). */
fun describeModels(provider: String, state: ModelsState, settings: AppSettings): List<ModelMeta> =
    state.modelsOf(provider).map { describeModel(ModelRef(provider, it), state, settings) }

fun describeModel(ref: ModelRef, state: ModelsState, settings: AppSettings): ModelMeta = Capabilities.describe(
    ref.model,
    if (ref.provider == Providers.NVIDIA) state.catalog else emptyMap(),
    settings.visionConfirmed, settings.visionRejected, key = ref.key,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    current: ModelRef,
    state: ModelsState,
    settings: AppSettings,
    title: String = "Model seç",
    onSelect: (ModelRef) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRefresh: (String) -> Unit,
    onAddProvider: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var capFilter by remember { mutableStateOf<Cap?>(null) }
    var onlyFavorites by remember { mutableStateOf(false) }
    val configured = settings.configuredProviders
    var provider by remember { mutableStateOf(if (current.provider in configured || configured.isEmpty()) current.provider else configured.first()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val favorites = settings.favoriteModels
    val unavailable = settings.unavailableModels
    val keyOf: (ModelMeta) -> String = { ModelRef(provider, it.id).key }

    val metas = remember(provider, state, settings.visionConfirmed, settings.visionRejected) { describeModels(provider, state, settings) }
    val usable = remember(metas, unavailable, provider) { usableModels(metas, unavailable, keyOf = keyOf) }
    val visible = remember(usable, showAll) { usable.filter { showAll || it.chatCapable } }
    val counts = remember(visible) { FILTER_CAPS.associateWith { c -> visible.count { c in it.caps } } }
    val hiddenCount = usable.size - visible.size
    val brokenCurrent = current.provider == provider && current.model.isNotBlank() && metas.isNotEmpty() && usable.none { it.id == current.model }

    val filtered = remember(visible, query, capFilter, onlyFavorites, favorites, provider) {
        pickerOrder(
            visible
                .filter { capFilter == null || capFilter in it.caps }
                .filter { !onlyFavorites || keyOf(it) in favorites }
                .filter { query.isBlank() || it.id.contains(query.trim(), ignoreCase = true) || it.display.contains(query.trim(), ignoreCase = true) },
            favorites, keyOf,
        )
    }
    val typed = query.trim()
    val canUseTyped = typed.length >= 3 && metas.none { it.id == typed }
    val loading = provider in state.loadingProviders
    val hasKey = provider in configured

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxHeight(0.9f).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (loading) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { onRefresh(provider) }) { Icon(Icons.Default.Refresh, contentDescription = "Listeyi yenile") }
            }
            // Sağlayıcılar
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (configured.ifEmpty { listOf(Providers.NVIDIA) }).forEach { id ->
                    FilterChip(
                        selected = provider == id,
                        onClick = { provider = id; capFilter = null; onlyFavorites = false },
                        label = { Text(Providers.name(id)) },
                        leadingIcon = { ProviderDot(id) },
                    )
                }
                FilterChip(selected = false, onClick = onAddProvider, label = { Text("＋ Sağlayıcı ekle") })
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Ara veya model kimliği yaz") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
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
                if (hiddenCount > 0 || showAll) {
                    FilterChip(selected = showAll, onClick = { showAll = !showAll }, label = { Text("Sohbet dışı modeller (+$hiddenCount)") })
                }
                Text("${filtered.size} model", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (provider != Providers.NVIDIA && metas.isNotEmpty()) {
                    Text("· rozetler adından tahmin", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (provider == Providers.NVIDIA && state.catalog.isEmpty() && state.models.isNotEmpty()) {
                Text(
                    "Yetenek bilgisi alınamadı; gruplar model adından tahmin ediliyor.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            state.errors[provider]?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (!hasKey) {
                    item {
                        Column(modifier = Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${Providers.name(provider)} için API anahtarı girilmemiş.", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = onAddProvider) { Text("Anahtar ekle") }
                        }
                    }
                }
                if (brokenCurrent) {
                    item {
                        ModelRow(
                            title = current.display, subtitle = Providers.name(provider), selected = true, favorite = false,
                            warning = "Seçili model artık çalışmıyor; aşağıdan başka bir model seçin.",
                            badges = "", onClick = {}, onToggleFavorite = null,
                        )
                    }
                }
                if (canUseTyped) {
                    item {
                        ModelRow(
                            title = typed, subtitle = "Bu kimliği doğrudan kullan", selected = current == ModelRef(provider, typed),
                            favorite = false, warning = null, badges = "",
                            onClick = { onSelect(ModelRef(provider, typed)) }, onToggleFavorite = null,
                        )
                    }
                }
                if (hasKey && metas.isEmpty() && !loading) {
                    item {
                        Text(
                            "Model listesi boş. Yenile düğmesine basın ya da model kimliğini yukarıya yazın.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
                items(filtered, key = { it.id }) { meta ->
                    val ref = ModelRef(provider, meta.id)
                    ModelRow(
                        title = meta.display,
                        subtitle = meta.id.substringBeforeLast('/', "").ifBlank { Providers.name(provider) },
                        selected = ref == current,
                        favorite = ref.key in favorites,
                        warning = meta.deprecation?.let { "${Capabilities.formatDate(it)} tarihinde kaldırılacak" },
                        badges = meta.caps.filter { it != Cap.CHAT }.joinToString(" ") { it.emoji },
                        onClick = { onSelect(ref) },
                        onToggleFavorite = { onToggleFavorite(ref.key) },
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
    warning: String?,
    badges: String,
    onClick: () -> Unit,
    onToggleFavorite: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
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
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (warning != null) Text(warning, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        if (onToggleFavorite != null) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (favorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (favorite) "Favoriden çıkar" else "Favorilere ekle",
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha(if (favorite) 1f else 0.6f),
                )
            }
        }
    }
}
