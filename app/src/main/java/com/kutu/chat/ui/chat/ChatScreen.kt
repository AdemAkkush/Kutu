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
import com.kutu.chat.domain.ATT_IMAGE
import com.kutu.chat.domain.Cap
import com.kutu.chat.domain.Capabilities
import com.kutu.chat.domain.EngineEvent
import com.kutu.chat.domain.ModelMeta
import com.kutu.chat.domain.Generation
import com.kutu.chat.domain.PersonaDef
import com.kutu.chat.ui.ChatViewModel
import com.kutu.chat.ui.components.AttachmentStrip
import com.kutu.chat.ui.components.ConfirmDialog
import com.kutu.chat.ui.components.ModelPickerSheet
import com.kutu.chat.ui.components.TextInputDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.kutu.chat.domain.ModelRef
import com.kutu.chat.domain.Providers
import com.kutu.chat.ui.components.KutuLogo
import com.kutu.chat.ui.components.ProviderDot
import com.kutu.chat.ui.components.describeModel
import com.kutu.chat.ui.components.describeModels
import com.kutu.chat.ui.theme.KutuBrand
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

private data class Suggestion(val icon: ImageVector, val title: String, val prompt: String)

private val SUGGESTIONS = listOf(
    Suggestion(Icons.Default.Code, "Kod yaz", "Bana bir Kotlin coroutine örneği göster"),
    Suggestion(Icons.Default.EditNote, "Metni düzelt", "Şu e-postayı daha profesyonel hale getir:\n\n"),
    Suggestion(Icons.Default.Lightbulb, "Fikir üret", "Bu hafta için verimli bir çalışma planı hazırla"),
    Suggestion(Icons.Default.Translate, "Çevir", "Şunu İngilizceye çevir:\n\n"),
)

private enum class PickerMode { SELECT, COMPARE }

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
    val haptic = LocalHapticFeedback.current
    val chat by vm.chat.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val generation by vm.generation.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modelsState by vm.modelsState.collectAsStateWithLifecycle()
    val activeRef by vm.activeRef.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val personas by vm.personas.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val pendingPersona by vm.pendingPersona.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val speakerError by vm.speakerError.collectAsStateWithLifecycle()
    val speakerPreparing by vm.speakerPreparing.collectAsStateWithLifecycle()

    // Resim eklenmişken seçili model görsel anlamıyorsa, aynı sağlayıcıdaki en iyi görsel modeli öner.
    val hasPendingImage = vm.pending.any { it.type == ATT_IMAGE }
    val visionSuggestion = remember(hasPendingImage, activeRef, modelsState, settings.unavailableModels, settings.visionConfirmed, settings.visionRejected) {
        if (!hasPendingImage || describeModel(activeRef, modelsState, settings).has(Cap.VISION)) null
        else Capabilities.best(
            Cap.VISION, describeModels(activeRef.provider, modelsState, settings),
            settings.unavailableModels.map { ModelRef.parse(it) }.filter { it.provider == activeRef.provider }.map { it.model }.toSet(),
        )
    }

    var pickerMode by remember { mutableStateOf<PickerMode?>(null) }
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
        uris.forEach(vm::addDocument)
    }
    // Kamera açıkken sistem etkinliği yeniden oluşturabilir; dosya yolu bu yüzden kalıcı durumda tutulur.
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraPath?.let(::File)
        if (file != null) { if (ok) vm.addCameraPhoto(file) else file.delete() }
        cameraPath = null
    }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::appendDictation)
        }
    }

    fun launchCamera() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("foto_", ".jpg", dir)
        cameraPath = file.path
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

    // Yanıt arka planda sürerken bildirim gösterilebilmesi için (Android 13+) ilk gönderimde bir kez izin istenir.
    // Reddedilse bile üretim ön plan servisiyle sürer; yalnızca bildirim görünmez.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.send() }
    val sendMessage: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val needs = android.os.Build.VERSION.SDK_INT >= 33 && !vm.notificationsAsked() &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needs) {
            vm.markNotificationsAsked()
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.send()
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
    // Yalnızca sorgu değişince en yeni eşleşmeye git; yanıt akarken (mesajlar güncellenirken) konum korunur.
    LaunchedEffect(vm.searchQuery, vm.searchOpen) { matchIndex = (matches.size - 1).coerceAtLeast(0) }
    val focusId = matches.getOrNull(matchIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0)))

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            if (vm.searchOpen) {
                SearchBar(
                    query = vm.searchQuery,
                    onQuery = { vm.searchQuery = it },
                    count = matches.size,
                    index = matchIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0)),
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
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                chat?.title ?: "Yeni sohbet",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val personaLabel = chat?.persona?.takeIf { it.isNotBlank() } ?: pendingPersona?.label
                            // Model hapı: dokununca model seçici açılır.
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.clip(CircleShape).clickable { pickerMode = PickerMode.SELECT },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    ProviderDot(activeRef.provider, 7.dp)
                                    Text(
                                        (if (personaLabel != null) "$personaLabel · " else "") + activeRef.display,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Model seç", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
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
                        hasKey = settings.configuredProviders.isNotEmpty(),
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
                        preparingSpeech = speakerPreparing,
                        matches = matches.toSet(),
                        focusId = focusId,
                        vm = vm,
                        onEdit = { id, text -> editing = id to text },
                        onShare = ::share,
                        onPickModel = { pickerMode = PickerMode.SELECT },
                        onCompare = { pickerMode = PickerMode.COMPARE },
                        onDeleteMessage = { deletingMessage = it },
                    )
                }
            }
            InputBar(
                vm = vm,
                templates = templates.map { it.title to it.text },
                generating = isGenerating,
                activeModelName = activeRef.display,
                visionSuggestion = visionSuggestion,
                onSwitchModel = { vm.selectModel(ModelRef(activeRef.provider, it)) },
                onGallery = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onCamera = ::launchCamera,
                onFile = { pickFiles.launch(arrayOf("*/*")) },
                onMic = ::launchDictation,
                onSend = sendMessage,
            )
        }
    }

    pickerMode?.let { mode ->
        ModelPickerSheet(
            current = activeRef,
            state = modelsState,
            settings = settings,
            title = if (mode == PickerMode.COMPARE) "Hangi modelle yanıtlansın?" else "Model seç",
            onSelect = { ref ->
                if (mode == PickerMode.COMPARE) vm.regenerateWith(ref) else vm.selectModel(ref)
                pickerMode = null
            },
            onToggleFavorite = vm::toggleFavorite,
            onRefresh = vm::refreshModels,
            onAddProvider = { pickerMode = null; onOpenSettings() },
            onDismiss = { pickerMode = null },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageList(
    messages: List<MessageEntity>,
    generation: Generation?,
    isGenerating: Boolean,
    speaking: Long?,
    preparingSpeech: Boolean,
    matches: Set<Long>,
    focusId: Long?,
    vm: ChatViewModel,
    onEdit: (Long, String) -> Unit,
    onShare: (String) -> Unit,
    onPickModel: () -> Unit,
    onCompare: () -> Unit,
    onDeleteMessage: (Long) -> Unit,
) {
    val reversed = remember(messages) { messages.asReversed() }
    val listState = rememberLazyListState()
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80 } }
    val scope = rememberCoroutineScope()
    // Son mesaj kullanıcınınsa (yanıt silinmiş ya da hiç gelmemişse) yanıt istemek için bir düğme gösterilir.
    val awaitingReply = !isGenerating && messages.lastOrNull()?.role == ROLE_USER

    // Yeni mesaj eklenince (gönderme / yanıt başlangıcı) en alta in.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && focusId == null) listState.animateScrollToItem(0)
    }
    // Arama sonucuna git.
    LaunchedEffect(focusId) {
        val idx = reversed.indexOfFirst { it.id == focusId }
        if (idx >= 0) listState.animateScrollToItem(idx + if (awaitingReply) 1 else 0)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (awaitingReply) {
                item(key = "reply") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        AssistChip(
                            onClick = vm::regenerate,
                            label = { Text("Yanıt al") },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            items(reversed, key = { it.id }) { m ->
                val isLast = m.id == messages.last().id
                val tint = when {
                    m.id == focusId -> MaterialTheme.colorScheme.tertiaryContainer
                    m.id in matches -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                    else -> Color.Transparent
                }
                Box(modifier = Modifier.fillMaxWidth().animateItem().background(tint, RoundedCornerShape(12.dp))) {
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
                            preparingSpeech = preparingSpeech,
                            canEdit = !isGenerating,
                            onRegenerate = vm::regenerate,
                            onRegenerateWith = onCompare,
                            onShare = onShare,
                            onPickModel = onPickModel,
                            onSpeak = { vm.toggleSpeak(m.id, it) },
                            onDelete = { onDeleteMessage(m.id) },
                            onVersion = { vm.switchVersion(m.id, it) },
                            onContinue = vm::continueMessage,
                        )
                    }
                }
            }
        }
        if (!atBottom) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            ) { Icon(Icons.Default.ArrowDownward, contentDescription = "En alta git") }
        }
    }
}

private fun greeting(hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) = when (hour) {
    in 5..11 -> "Günaydın"
    in 12..17 -> "İyi günler"
    in 18..22 -> "İyi akşamlar"
    else -> "İyi geceler"
}

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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        KutuLogo(60.dp)
        Spacer(Modifier.height(20.dp))
        Text(greeting(), style = MaterialTheme.typography.headlineLarge)
        Text(
            "Bugün ne yapalım?",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        if (!hasKey) {
            Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Başlamak için bir sağlayıcı ekleyin", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "NVIDIA Build, OpenRouter, Groq, Gemini, OpenAI ve diğerlerinden birinin API anahtarını Ayarlar'a yapıştırın. " +
                            "Birçoğunun ücretsiz katmanı var.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenSettings) { Text("Sağlayıcı ekle") }
                }
            }
        } else {
            // Öneri kartları (2 sütun)
            SUGGESTIONS.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    row.forEach { s ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.large,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.weight(1f).clip(MaterialTheme.shapes.large).clickable { onSuggestion(s.prompt) },
                        ) {
                            Column(modifier = Modifier.padding(14.dp).heightIn(min = 76.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(s.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                Text(s.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    s.prompt.trim().trimEnd(':'),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Kişilik", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                personas.forEach { p ->
                    FilterChip(selected = p == selected, onClick = { onPersona(p) }, label = { Text(p.label) })
                }
                FilterChip(selected = false, onClick = onManagePersonas, label = { Text("＋ Yeni") })
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
    activeModelName: String,
    visionSuggestion: ModelMeta?,
    onSwitchModel: (String) -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
) {
    var attachMenu by remember { mutableStateOf(false) }
    var templateMenu by remember { mutableStateOf(false) }
    val canSend = vm.input.isNotBlank() || vm.pending.isNotEmpty()

    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.attachError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
        }
        if (visionSuggestion != null) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                Row(modifier = Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "👁 $activeModelName görselleri anlamayabilir.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onSwitchModel(visionSuggestion.id) }) { Text(visionSuggestion.display + " ile dene") }
                }
            }
        }
        // Yüzen giriş kutusu
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                if (vm.pending.isNotEmpty() || vm.attaching) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AttachmentStrip(vm.pending.toList(), onRemove = vm::removePending)
                        if (vm.attaching) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Box {
                        IconButton(onClick = { attachMenu = true }) { Icon(Icons.Default.Add, contentDescription = "Ek ekle") }
                        DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            DropdownMenuItem(text = { Text("Galeriden resim") }, leadingIcon = { Icon(Icons.Default.Image, null) },
                                onClick = { attachMenu = false; onGallery() })
                            DropdownMenuItem(text = { Text("Fotoğraf çek") }, leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                                onClick = { attachMenu = false; onCamera() })
                            DropdownMenuItem(text = { Text("Belge (PDF, Excel, Word, metin…)") }, leadingIcon = { Icon(Icons.Default.Description, null) },
                                onClick = { attachMenu = false; onFile() })
                        }
                    }
                    Box {
                        IconButton(onClick = { templateMenu = true }) { Icon(Icons.Default.AutoAwesome, contentDescription = "Şablonlar", tint = MaterialTheme.colorScheme.primary) }
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
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Box(modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)) {
                        when {
                            generating -> RoundAction(Icons.Default.Stop, "Durdur", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, onClick = vm::stop)
                            canSend -> Box(
                                modifier = Modifier.size(44.dp).clip(CircleShape).background(KutuBrand.gradient)
                                    .clickable(enabled = !vm.attaching, onClick = onSend),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Default.ArrowUpward, contentDescription = "Gönder", tint = Color.White) }
                            else -> RoundAction(Icons.Default.Mic, "Sesle yaz", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface, onClick = onMic)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, description: String, container: Color, content: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).background(container).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = content) }
}
