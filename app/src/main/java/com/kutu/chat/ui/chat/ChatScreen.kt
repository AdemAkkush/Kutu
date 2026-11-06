package com.kutu.chat.ui.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kutu.chat.data.db.MessageEntity
import com.kutu.chat.data.db.ROLE_USER
import com.kutu.chat.domain.EngineEvent
import com.kutu.chat.domain.Generation
import com.kutu.chat.domain.PersonaDef
import com.kutu.chat.ui.ChatViewModel
import com.kutu.chat.ui.components.AttachmentStrip
import com.kutu.chat.ui.components.ConfirmDialog
import com.kutu.chat.ui.components.ModelPickerSheet
import com.kutu.chat.ui.components.TextInputDialog
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

private val SUGGESTIONS = listOf(
    "Bana bir Kotlin coroutine örneği göster",
    "Bu hafta için çalışma planı hazırla",
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
    onOpenPersonas: () -> Unit,
) {
    val context = LocalContext.current
    val chat by vm.chat.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val generation by vm.generation.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modelsState by vm.modelsState.collectAsStateWithLifecycle()
    val activeModel by vm.activeModel.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val personas by vm.personas.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val pendingPersona by vm.pendingPersona.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val speakerError by vm.speakerError.collectAsStateWithLifecycle()

    var showModelPicker by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showSystemPrompt by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var deletingMessage by remember { mutableStateOf<Long?>(null) }

    val isGenerating = generation != null

    // ---- Sistem seçicileri ----
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        uris.forEach(vm::addImage)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(vm::addTextFile)
    }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraFile
        if (ok && file != null) vm.addImage(android.net.Uri.fromFile(file))
    }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::appendDictation)
        }
    }

    fun launchCamera() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("foto_", ".jpg", dir)
        cameraFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        try {
            takePhoto.launch(uri)
        } catch (e: ActivityNotFoundException) {
            vm.attachError = "Cihazda kamera uygulaması bulunamadı."
        }
    }

    fun launchDictation() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Konuşun…")
        }
        try {
            dictation.launch(intent)
        } catch (e: ActivityNotFoundException) {
            vm.attachError = "Cihazda sesle yazma (konuşma tanıma) desteği bulunamadı."
        }
    }

    // Hafıza güncellemelerini kullanıcıya göster.
    LaunchedEffect(Unit) {
        vm.memoryEvents.collect { event ->
            if (event is EngineEvent.MemoryChanged) {
                val msg = when {
                    event.added.isNotEmpty() -> "Hafızaya eklendi: ${event.added.first()}"
                    else -> "Hafıza güncellendi"
                }
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(msg, actionLabel = "Hafıza", duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) onOpenMemory()
            }
        }
    }
    LaunchedEffect(speakerError) {
        speakerError?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Long)
            vm.clearSpeakerError()
        }
    }

    fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Paylaş"))
    }

    // ---- Sohbet içi arama ----
    val matches = remember(messages, vm.searchQuery, vm.searchOpen) {
        val q = vm.searchQuery.trim()
        if (!vm.searchOpen || q.isEmpty()) emptyList()
        else messages.filter { it.content.contains(q, ignoreCase = true) }.map { it.id }
    }
    var matchIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(matches) { matchIndex = (matches.size - 1).coerceAtLeast(0) }
    val focusId = matches.getOrNull(matchIndex)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (vm.searchOpen) {
                SearchBar(
                    query = vm.searchQuery,
                    onQuery = { vm.searchQuery = it },
                    count = matches.size,
                    index = matchIndex,
                    onPrev = { if (matches.isNotEmpty()) matchIndex = (matchIndex - 1 + matches.size) % matches.size },
                    onNext = { if (matches.isNotEmpty()) matchIndex = (matchIndex + 1) % matches.size },
                    onClose = vm::closeSearch,
                )
            } else {
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
                            val personaLabel = chat?.persona?.takeIf { it.isNotBlank() } ?: pendingPersona?.label
                            Text(
                                (if (personaLabel != null) "$personaLabel · " else "") + activeModel.substringAfter('/') + "  ▾",
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
                                    DropdownMenuItem(text = { Text("Sohbette ara") }, onClick = { showMenu = false; vm.searchOpen = true })
                                    DropdownMenuItem(text = { Text("Yeniden adlandır") }, onClick = { showMenu = false; showRename = true })
                                    DropdownMenuItem(text = { Text("Bu sohbet için sistem istemi") }, onClick = { showMenu = false; showSystemPrompt = true })
                                    DropdownMenuItem(text = { Text("Sohbeti paylaş (Markdown)") }, onClick = { showMenu = false; share(vm.exportMarkdown()) })
                                    DropdownMenuItem(text = { Text("Sohbeti sil") }, onClick = { showMenu = false; showDelete = true })
                                }
                                DropdownMenuItem(text = { Text("Kişiler ve şablonlar") }, onClick = { showMenu = false; onOpenPersonas() })
                                DropdownMenuItem(text = { Text("Hafıza") }, onClick = { showMenu = false; onOpenMemory() })
                                DropdownMenuItem(text = { Text("Ayarlar") }, onClick = { showMenu = false; onOpenSettings() })
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
                        personas = personas,
                        selected = pendingPersona,
                        onPersona = { p -> if (pendingPersona == p) vm.clearPersona() else vm.pendingPersona.value = p },
                        onManagePersonas = onOpenPersonas,
                    )
                    else -> MessageList(
                        messages = messages,
                        generation = generation,
                        isGenerating = isGenerating,
                        speaking = speaking,
                        matches = matches.toSet(),
                        focusId = focusId,
                        vm = vm,
                        onEdit = { id, text -> editing = id to text },
                        onShare = ::share,
                        onPickModel = { showModelPicker = true },
                        onDeleteMessage = { deletingMessage = it },
                    )
                }
            }
            InputBar(
                vm = vm,
                templates = templates.map { it.title to it.text },
                generating = isGenerating,
                onGallery = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onCamera = ::launchCamera,
                onFile = { pickFiles.launch(arrayOf("*/*")) },
                onMic = ::launchDictation,
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
        ConfirmDialog("Sohbet silinsin mi?", "Bu sohbetteki tüm mesajlar ve ekler kalıcı olarak silinir.",
            onConfirm = { vm.deleteCurrent(); showDelete = false }, onDismiss = { showDelete = false })
    }
    deletingMessage?.let { id ->
        ConfirmDialog("Mesaj silinsin mi?", "Kullanıcı mesajını silerseniz hemen ardındaki yanıt da silinir.",
            onConfirm = { vm.deleteMessage(id); deletingMessage = null }, onDismiss = { deletingMessage = null })
    }
    editing?.let { (id, text) ->
        TextInputDialog(
            title = "Mesajı düzenle",
            initial = text,
            label = "Mesaj",
            confirmText = "Gönder",
            singleLine = false,
            allowEmpty = true,
            onConfirm = { vm.editAndResend(id, it); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    count: Int,
    index: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Aramayı kapat") } },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Sohbette ara") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        actions = {
            if (query.isNotBlank()) {
                Text(
                    if (count == 0) "0" else "${index + 1}/$count",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            IconButton(onClick = onPrev, enabled = count > 0) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Önceki sonuç") }
            IconButton(onClick = onNext, enabled = count > 0) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Sonraki sonuç") }
        },
    )
}

@Composable
private fun MessageList(
    messages: List<MessageEntity>,
    generation: Generation?,
    isGenerating: Boolean,
    speaking: Long?,
    matches: Set<Long>,
    focusId: Long?,
    vm: ChatViewModel,
    onEdit: (Long, String) -> Unit,
    onShare: (String) -> Unit,
    onPickModel: () -> Unit,
    onDeleteMessage: (Long) -> Unit,
) {
    val reversed = remember(messages) { messages.asReversed() }
    val listState = rememberLazyListState()
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80 } }
    val scope = rememberCoroutineScope()

    // Yeni mesaj eklenince (gönderme / yanıt başlangıcı) en alta in.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && focusId == null) listState.animateScrollToItem(0)
    }
    // Arama sonucuna git.
    LaunchedEffect(focusId) {
        val idx = reversed.indexOfFirst { it.id == focusId }
        if (idx >= 0) listState.animateScrollToItem(idx)
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
                val tint = when {
                    m.id == focusId -> MaterialTheme.colorScheme.tertiaryContainer
                    m.id in matches -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                    else -> Color.Transparent
                }
                Box(modifier = Modifier.fillMaxWidth().background(tint, RoundedCornerShape(12.dp))) {
                    if (m.role == ROLE_USER) {
                        UserMessage(
                            m,
                            enabled = !isGenerating,
                            onEdit = { onEdit(m.id, m.content) },
                            onDelete = { onDeleteMessage(m.id) },
                        )
                    } else {
                        AssistantMessage(
                            message = m,
                            generation = generation,
                            isLast = isLast && !isGenerating,
                            speaking = speaking == m.id,
                            canEdit = !isGenerating,
                            onRegenerate = vm::regenerate,
                            onShare = onShare,
                            onPickModel = onPickModel,
                            onSpeak = { vm.toggleSpeak(m.id, it) },
                            onDelete = { onDeleteMessage(m.id) },
                            onVersion = { vm.switchVersion(m.id, it) },
                        )
                    }
                }
            }
        }
        if (!atBottom) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            ) { Icon(Icons.Default.ArrowDownward, contentDescription = "En alta git") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(
    hasKey: Boolean,
    memoryOn: Boolean,
    personas: List<PersonaDef>,
    selected: PersonaDef?,
    onOpenSettings: () -> Unit,
    onSuggestion: (String) -> Unit,
    onPersona: (PersonaDef) -> Unit,
    onManagePersonas: () -> Unit,
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
            Text("Kişilik seç", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            ) {
                personas.forEach { p ->
                    FilterChip(selected = p == selected, onClick = { onPersona(p) }, label = { Text(p.label) })
                }
                FilterChip(selected = false, onClick = onManagePersonas, label = { Text("＋ Yeni") })
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SUGGESTIONS.forEach { s ->
                    androidx.compose.material3.AssistChip(onClick = { onSuggestion(s) }, label = { Text(s) })
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
    vm: ChatViewModel,
    templates: List<Pair<String, String>>,
    generating: Boolean,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onMic: () -> Unit,
) {
    var attachMenu by remember { mutableStateOf(false) }
    var templateMenu by remember { mutableStateOf(false) }
    val canSend = vm.input.isNotBlank() || vm.pending.isNotEmpty()

    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.attachError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (vm.pending.isNotEmpty() || vm.attaching) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AttachmentStrip(vm.pending.toList(), onRemove = vm::removePending)
                    if (vm.attaching) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box {
                    IconButton(onClick = { attachMenu = true }) { Icon(Icons.Default.Add, contentDescription = "Ek ekle") }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(text = { Text("Galeriden resim") }, leadingIcon = { Icon(Icons.Default.Image, null) },
                            onClick = { attachMenu = false; onGallery() })
                        DropdownMenuItem(text = { Text("Fotoğraf çek") }, leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                            onClick = { attachMenu = false; onCamera() })
                        DropdownMenuItem(text = { Text("Metin / kod dosyası") }, leadingIcon = { Icon(Icons.Default.Description, null) },
                            onClick = { attachMenu = false; onFile() })
                    }
                }
                Box {
                    IconButton(onClick = { templateMenu = true }) { Icon(Icons.Default.AutoAwesome, contentDescription = "Şablonlar") }
                    DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                        templates.forEach { (title, text) ->
                            DropdownMenuItem(text = { Text(title) }, onClick = { templateMenu = false; vm.insertTemplate(text) })
                        }
                    }
                }
                TextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    placeholder = { Text("Mesaj yaz…") },
                    maxLines = 8,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                when {
                    generating -> FilledIconButton(onClick = vm::stop) { Icon(Icons.Default.Stop, contentDescription = "Durdur") }
                    canSend -> FilledIconButton(onClick = vm::send, enabled = !vm.attaching) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gönder")
                    }
                    else -> FilledIconButton(onClick = onMic) { Icon(Icons.Default.Mic, contentDescription = "Sesle yaz") }
                }
            }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}
