package com.kutu.chat.ui.chat

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kutu.chat.data.db.ROLE_USER
import com.kutu.chat.domain.EngineEvent
import com.kutu.chat.ui.ChatViewModel
import com.kutu.chat.ui.components.ConfirmDialog
import com.kutu.chat.ui.components.ModelPickerSheet
import com.kutu.chat.ui.components.TextInputDialog
import kotlinx.coroutines.launch

private val SUGGESTIONS = listOf(
    "Bana bir Kotlin coroutine örneği göster",
    "Bu hafta için çalışma planı hazırla",
    "Şu metni özetle ve ana fikirlerini çıkar",
    "Bir e-postayı daha profesyonel hale getir",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    snackbar: SnackbarHostState,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMemory: () -> Unit,
) {
    val context = LocalContext.current
    val chat by vm.chat.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val generation by vm.generation.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modelsState by vm.modelsState.collectAsStateWithLifecycle()
    val activeModel by vm.activeModel.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()

    var showModelPicker by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showSystemPrompt by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<Long, String>?>(null) }

    val isGenerating = generation != null

    // Hafıza güncellemelerini kullanıcıya göster.
    LaunchedEffect(Unit) {
        vm.memoryEvents.collect { event ->
            if (event is EngineEvent.MemoryChanged) {
                val msg = when {
                    event.added.isNotEmpty() -> "Hafızaya eklendi: ${event.added.first()}"
                    else -> "Hafıza güncellendi"
                }
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(msg, actionLabel = "Hafıza", duration = androidx.compose.material3.SnackbarDuration.Short)
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenMemory()
            }
        }
    }

    fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Paylaş"))
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Default.Menu, contentDescription = "Menü") }
                },
                title = {
                    Column(modifier = Modifier.clickableNoRipple { showModelPicker = true }) {
                        Text(
                            chat?.title ?: "Yeni sohbet",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            activeModel.substringAfter('/') + "  ▾",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::newChat) {
                        Icon(Icons.Outlined.AddComment, contentDescription = "Yeni sohbet")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Diğer")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            if (chat != null) {
                                DropdownMenuItem(text = { Text("Yeniden adlandır") }, onClick = { showMenu = false; showRename = true })
                                DropdownMenuItem(text = { Text("Bu sohbet için sistem istemi") }, onClick = { showMenu = false; showSystemPrompt = true })
                                DropdownMenuItem(text = { Text("Sohbeti paylaş (Markdown)") }, onClick = { showMenu = false; share(vm.exportMarkdown()) })
                                DropdownMenuItem(text = { Text("Sohbeti sil") }, onClick = { showMenu = false; showDelete = true })
                            }
                            DropdownMenuItem(text = { Text("Hafıza") }, onClick = { showMenu = false; onOpenMemory() })
                            DropdownMenuItem(text = { Text("Ayarlar") }, onClick = { showMenu = false; onOpenSettings() })
                        }
                    }
                },
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !ready -> Unit
                    messages.isEmpty() -> EmptyState(
                        hasKey = settings.apiKey.isNotBlank(),
                        onOpenSettings = onOpenSettings,
                        onSuggestion = { vm.input = it },
                        memoryOn = settings.memoryEnabled,
                    )
                    else -> MessageList(
                        vm = vm,
                        generation = generation,
                        isGenerating = isGenerating,
                        onEdit = { id, text -> editing = id to text },
                        onShare = ::share,
                        onPickModel = { showModelPicker = true },
                    )
                }
            }
            InputBar(
                value = vm.input,
                onValueChange = { vm.input = it },
                generating = isGenerating,
                onSend = vm::send,
                onStop = vm::stop,
            )
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            current = activeModel,
            state = modelsState,
            favorites = settings.favoriteModels,
            unavailable = settings.unavailableModels,
            onSelect = { vm.selectModel(it); showModelPicker = false },
            onToggleFavorite = vm::toggleFavorite,
            onRefresh = vm::refreshModels,
            onDismiss = { showModelPicker = false },
        )
    }
    if (showRename) {
        TextInputDialog("Sohbeti yeniden adlandır", chat?.title.orEmpty(), "Başlık",
            onConfirm = { vm.rename(it); showRename = false }, onDismiss = { showRename = false })
    }
    if (showSystemPrompt) {
        TextInputDialog(
            title = "Bu sohbet için sistem istemi",
            initial = chat?.systemPrompt.orEmpty(),
            label = "Boş bırakırsanız genel sistem istemi kullanılır",
            singleLine = false,
            allowEmpty = true,
            onConfirm = { vm.setChatSystemPrompt(it); showSystemPrompt = false },
            onDismiss = { showSystemPrompt = false },
        )
    }
    if (showDelete) {
        ConfirmDialog("Sohbet silinsin mi?", "Bu sohbetteki tüm mesajlar kalıcı olarak silinir.",
            onConfirm = { vm.deleteCurrent(); showDelete = false }, onDismiss = { showDelete = false })
    }
    editing?.let { (id, text) ->
        TextInputDialog(
            title = "Mesajı düzenle",
            initial = text,
            label = "Mesaj",
            confirmText = "Gönder",
            singleLine = false,
            onConfirm = { vm.editAndResend(id, it); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun MessageList(
    vm: ChatViewModel,
    generation: com.kutu.chat.domain.Generation?,
    isGenerating: Boolean,
    onEdit: (Long, String) -> Unit,
    onShare: (String) -> Unit,
    onPickModel: () -> Unit,
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val reversed = remember(messages) { messages.asReversed() }
    val listState = rememberLazyListState()
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80 } }

    // Yeni mesaj eklenince (gönderme / yanıt başlangıcı) en alta in.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(reversed, key = { it.id }) { m ->
                val isLast = m.id == messages.last().id
                if (m.role == ROLE_USER) {
                    UserMessage(m, enabled = !isGenerating, onEdit = { onEdit(m.id, m.content) })
                } else {
                    AssistantMessage(
                        message = m,
                        generation = generation,
                        isLast = isLast && !isGenerating,
                        onRegenerate = vm::regenerate,
                        onShare = onShare,
                        onPickModel = onPickModel,
                    )
                }
            }
        }
        if (!atBottom) {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            ) { Icon(Icons.Default.ArrowDownward, contentDescription = "En alta git") }
        }
    }
}

@Composable
private fun EmptyState(
    hasKey: Boolean,
    memoryOn: Boolean,
    onOpenSettings: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Merhaba 👋", style = MaterialTheme.typography.headlineLarge)
        Text(
            "NVIDIA Build modelleriyle sohbet et. Sohbetlerini ve hakkında öğrendiklerimi hatırlarım.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        if (!hasKey) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Başlamak için API anahtarı gerekli", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "build.nvidia.com adresinden ücretsiz bir API anahtarı (nvapi-…) oluşturup Ayarlar'a yapıştırın.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onOpenSettings) { Text("Ayarlar'ı aç") }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SUGGESTIONS.forEach { s ->
                    AssistChip(onClick = { onSuggestion(s) }, label = { Text(s) })
                }
            }
            if (!memoryOn) {
                Text(
                    "Hafıza kapalı. Ayarlar'dan açabilirsiniz.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    generating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Mesaj yaz…") },
                maxLines = 8,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            if (generating) {
                FilledIconButton(onClick = onStop) { Icon(Icons.Default.Stop, contentDescription = "Durdur") }
            } else {
                FilledIconButton(onClick = onSend, enabled = value.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gönder")
                }
            }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}
