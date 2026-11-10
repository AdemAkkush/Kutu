package com.kutu.chat.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import com.kutu.chat.domain.Providers
import com.kutu.chat.ui.components.ProviderDot
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kutu.chat.data.DEFAULT_BASE_URL
import com.kutu.chat.data.DEFAULT_SYSTEM_PROMPT
import com.kutu.chat.data.ThemeMode
import com.kutu.chat.data.VoiceProcessor
import com.kutu.chat.domain.Capabilities
import com.kutu.chat.domain.MAGPIE_SPEAKERS
import com.kutu.chat.domain.TtsEngine
import com.kutu.chat.domain.TtsModel
import com.kutu.chat.ui.SettingsViewModel
import com.kutu.chat.ui.components.ModelPickerSheet
import kotlin.math.roundToInt

private enum class PickerTarget { DEFAULT, BACKGROUND }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit, onOpenMemory: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modelsState by vm.modelsState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    var systemPrompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var editingProvider by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri") } },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsCard("Sağlayıcılar", Icons.Default.Hub) {
                Text(
                    "Kutu, sohbet API'si OpenAI biçimiyle uyumlu sağlayıcılarla çalışır. Kullanmak istediklerinize API anahtarı ekleyin; " +
                        "model seçicide sağlayıcılar arasında geçiş yapabilirsiniz. Anahtarlar cihazda şifreli saklanır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Providers.presets.forEach { def ->
                    val connected = def.id in settings.configuredProviders
                    val count = modelsState.modelsOf(def.id).size
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { editingProvider = def.id }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ProviderDot(def.id, 12.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(def.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when {
                                    def.id in modelsState.loadingProviders -> "Bağlanıyor…"
                                    modelsState.errors[def.id] != null && connected -> "Hata: bağlantıyı sınayın"
                                    connected && count > 0 -> "Bağlı · $count model"
                                    connected -> "Anahtar girildi"
                                    else -> def.note
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (connected && modelsState.errors[def.id] == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        Text(if (connected) "Düzenle" else "Ekle", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            SettingsCard("Modeller", Icons.Default.SmartToy) {
                ModelField("Varsayılan sohbet modeli", Providers.name(settings.defaultProvider) + " · " + settings.defaultModel) { picker = PickerTarget.DEFAULT }
                ModelField("Arka plan modeli (başlık, özet, hafıza)", Providers.name(settings.memoryProvider) + " · " + settings.memoryModel) { picker = PickerTarget.BACKGROUND }
                Text(
                    "Çalışmayan modeller (kaldırılmış ya da hata veren) model seçicide gösterilmez.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.unavailableModels.isNotEmpty()) {
                    OutlinedButton(onClick = { vm.update { it.copy(unavailableModels = emptySet()) } }) {
                        Text("Hata nedeniyle gizlenen ${settings.unavailableModels.size} modeli yeniden göster")
                    }
                }
                Text(
                    "Arka plan işleri için küçük ve hızlı bir model seçmek hem hızlı hem ucuzdur. Bu model kullanılamazsa sohbetin kendi modeli kullanılır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsCard("Hafıza", Icons.Default.Psychology) {
                ToggleRow("Hafızayı sohbetlerde kullan", settings.memoryEnabled) { v -> vm.update { it.copy(memoryEnabled = v) } }
                ToggleRow("Sohbetlerden otomatik öğren", settings.autoMemory, enabled = settings.memoryEnabled) { v -> vm.update { it.copy(autoMemory = v) } }
                OutlinedButton(onClick = onOpenMemory) { Text("Kayıtlı hafızayı yönet") }
            }
            SettingsCard("Üretim", Icons.Default.Tune) {
                LabeledSlider("Yaratıcılık (temperature)", "%.1f".format(settings.temperature), settings.temperature, 0f..1.5f, 14) { v ->
                    vm.update { it.copy(temperature = (v * 10).roundToInt() / 10f) }
                }
                LabeledSlider("Maksimum yanıt uzunluğu", "${settings.maxTokens} token", settings.maxTokens.toFloat(), 512f..16384f, 30) { v ->
                    vm.update { it.copy(maxTokens = (v / 256).roundToInt() * 256) }
                }
                LabeledSlider("Bağlam bütçesi", "${settings.contextTokens} token", settings.contextTokens.toFloat(), 4000f..100000f, 23) { v ->
                    vm.update { it.copy(contextTokens = (v / 1000).roundToInt() * 1000) }
                }
                Text(
                    "Sohbet bu bütçeyi aşınca eski mesajlar otomatik özetlenir; sohbet sınırsız devam eder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsCard("Sistem istemi", Icons.Default.Notes) {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it; vm.update { s -> s.copy(systemPrompt = it) } },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { vm.update { it.copy(systemPrompt = DEFAULT_SYSTEM_PROMPT) } }) { Text("Varsayılana döndür") }
            }
            SettingsCard("Seslendirme", Icons.Default.RecordVoiceOver) {
                Text(
                    "Yanıtların altındaki hoparlör düğmesi yanıtı sesli okur. NVIDIA'nın yapay zekâ sesleri (Chatterbox, Magpie) " +
                        "cihaz sesinden çok daha doğaldır. Hata olursa otomatik olarak cihaz sesine geçilir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val engines = listOf(TtsEngine.NVIDIA to "NVIDIA yapay zekâ", TtsEngine.DEVICE to "Cihaz sesi")
                    engines.forEachIndexed { index, (engine, label) ->
                        SegmentedButton(
                            selected = settings.ttsEngine == engine,
                            onClick = { vm.update { it.copy(ttsEngine = engine) } },
                            shape = SegmentedButtonDefaults.itemShape(index, engines.size),
                        ) { Text(label) }
                    }
                }
                if (settings.ttsEngine == TtsEngine.NVIDIA) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val models = TtsModel.entries
                        models.forEachIndexed { index, model ->
                            SegmentedButton(
                                selected = settings.ttsModel == model,
                                onClick = { vm.update { it.copy(ttsModel = model) } },
                                shape = SegmentedButtonDefaults.itemShape(index, models.size),
                            ) { Text(model.label) }
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Hangi dilde kaç ses var?", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "• Türkçe (Chatterbox): tek erkek ses; konuşmacı seçilemez, ses klonlama da kapalı.\n" +
                                    "• İngilizce, İspanyolca, Fransızca, Almanca, İtalyanca, Portekizce, Çince, Japonca, Korece, Arapça, Hintçe, Vietnamca (Magpie): " +
                                    "10 konuşmacı arasından seçilir.\n" +
                                    "• Türkçede farklı ses (örn. kadın sesi) istiyorsanız aşağıdaki “Türkçeyi cihaz sesiyle oku” seçeneğini açın.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    ToggleRow("Türkçeyi cihaz sesiyle oku (daha çok ses seçeneği)", settings.ttsTurkishOnDevice) { v -> vm.update { it.copy(ttsTurkishOnDevice = v) } }
                    if (settings.ttsModel != TtsModel.CHATTERBOX) {
                        Text("Magpie konuşmacısı (Türkçe dışındaki diller için)", style = MaterialTheme.typography.labelLarge)
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MAGPIE_SPEAKERS.forEach { name ->
                                androidx.compose.material3.FilterChip(
                                    selected = settings.ttsVoice == name,
                                    onClick = { vm.update { it.copy(ttsVoice = name) } },
                                    label = { Text(name) },
                                )
                            }
                        }
                    }
                }

                LabeledSlider("Konuşma hızı", "%.2fx".format(settings.ttsSpeed), settings.ttsSpeed, VoiceProcessor.MIN_SPEED..VoiceProcessor.MAX_SPEED, 29) { v ->
                    vm.update { it.copy(ttsSpeed = (Math.round(v * 20) / 20f)) }
                }
                LabeledSlider("Ses tonu", if (settings.ttsPitch < 0.995f) "daha kalın" else if (settings.ttsPitch > 1.005f) "daha ince" else "normal", settings.ttsPitch, VoiceProcessor.MIN_PITCH..VoiceProcessor.MAX_PITCH, 13) { v ->
                    vm.update { it.copy(ttsPitch = (Math.round(v * 20) / 20f)) }
                }
                if (settings.ttsSpeed != 1f || settings.ttsPitch != 1f) {
                    TextButton(onClick = { vm.update { it.copy(ttsSpeed = 1f, ttsPitch = 1f) } }) { Text("Hız ve tonu sıfırla") }
                }
                Text(
                    "Hız ve ton, hangi motor seçili olursa olsun uygulanır. Hız değişince sesin tonu bozulmaz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val usesDeviceVoice = settings.ttsEngine == TtsEngine.DEVICE || settings.ttsTurkishOnDevice
                var showDeviceVoices by remember { mutableStateOf(false) }
                if (usesDeviceVoice) {
                    OutlinedButton(onClick = { vm.loadDeviceVoices(); showDeviceVoices = true }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("Cihaz sesi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(settings.ttsDeviceVoice.ifBlank { "Varsayılan (değiştirmek için dokunun)" }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (showDeviceVoices) {
                    DeviceVoiceDialog(vm, settings.ttsDeviceVoice, onSelect = { name -> vm.update { it.copy(ttsDeviceVoice = name) } }, onDismiss = { vm.stopSpeaking(); showDeviceVoices = false })
                }

                val speaking by vm.speaking.collectAsStateWithLifecycle()
                val preparing by vm.speakerPreparing.collectAsStateWithLifecycle()
                val speakerError by vm.speakerError.collectAsStateWithLifecycle()
                val canSpeak = settings.ttsEngine == TtsEngine.DEVICE || settings.apiKey.isNotBlank()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = canSpeak, onClick = { vm.speakSample(-1, "Merhaba, ben Kutu. Bugün sana yardımcı olmak için buradayım.") }) {
                        Text(if (speaking == -1L) (if (preparing) "Hazırlanıyor…" else "Durdur") else "Türkçe dene")
                    }
                    OutlinedButton(enabled = canSpeak, onClick = { vm.speakSample(-2, "Hello, I am Kutu. I am here to help you today.") }) {
                        Text(if (speaking == -2L) (if (preparing) "Hazırlanıyor…" else "Durdur") else "İngilizce dene")
                    }
                }
                speakerError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            SettingsCard("Arka planda çalışma", Icons.Default.BatterySaver) {
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                var resumes by remember { mutableStateOf(0) }
                androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumes++ }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                val unrestricted = remember(resumes) {
                    (context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(context.packageName)
                }
                Text(
                    "Yanıt hazırlanırken başka uygulamaya geçseniz de üretim bildirimli bir servisle sürer ve bağlantı koparsa otomatik yeniden denenir. " +
                        "Bazı telefonlar (Xiaomi, Samsung, Huawei…) pil tasarrufu için uygulamayı yine de durdurabilir; öyleyse Kutu'yu pil kısıtlamasından muaf tutun.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (unrestricted) "Pil kısıtlaması: yok (önerilen)" else "Pil kısıtlaması: açık",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unrestricted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (!unrestricted) {
                    OutlinedButton(onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        runCatching { context.startActivity(intent) }.onFailure {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.parse("package:" + context.packageName)),
                            )
                        }
                    }) { Text("Pil ayarlarını aç") }
                }
            }
            SettingsCard("Yedekleme", Icons.Default.Backup) {
                Text(
                    "Tüm sohbetleri, eklerini, hafızayı, kişilikleri ve şablonları tek bir ZIP dosyasına aktarın; " +
                        "yeni telefonda içe aktarın. API anahtarı yedeğe dahil edilmez.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !vm.backupBusy, onClick = {
                        exportLauncher.launch("kutu-yedek-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + ".zip")
                    }) { Text("Dışa aktar") }
                    OutlinedButton(enabled = !vm.backupBusy, onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                        Text("İçe aktar")
                    }
                }
                if (vm.backupBusy) Text("İşleniyor…", style = MaterialTheme.typography.bodySmall)
                vm.backupStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("Hata")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            }
            SettingsCard("Görünüm", Icons.Default.Palette) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val items = listOf(ThemeMode.SYSTEM to "Sistem", ThemeMode.LIGHT to "Açık", ThemeMode.DARK to "Koyu")
                    items.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.theme == mode,
                            onClick = { vm.update { it.copy(theme = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, items.size),
                        ) { Text(label) }
                    }
                }
                ToggleRow("Renkleri duvar kâğıdından al (Android 12+)", settings.dynamicColor) { v -> vm.update { it.copy(dynamicColor = v) } }
                Text(
                    "API anahtarlarınız cihazda Android Keystore ile şifrelenerek saklanır ve yalnızca ilgili sağlayıcıya gönderilir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
            }
        }
    }

    picker?.let { target ->
        ModelPickerSheet(
            current = if (target == PickerTarget.DEFAULT) settings.defaultRef else settings.memoryRef,
            state = modelsState,
            settings = settings,
            title = if (target == PickerTarget.DEFAULT) "Varsayılan sohbet modeli" else "Arka plan modeli",
            onSelect = { ref ->
                vm.update {
                    if (target == PickerTarget.DEFAULT) it.copy(defaultProvider = ref.provider, defaultModel = ref.model)
                    else it.copy(memoryProvider = ref.provider, memoryModel = ref.model)
                }
                picker = null
            },
            onToggleFavorite = vm::toggleFavorite,
            onRefresh = vm::refreshModels,
            onAddProvider = { picker = null },
            onDismiss = { picker = null },
        )
    }
    editingProvider?.let { id -> ProviderDialog(id, vm, onDismiss = { editingProvider = null }) }
}

/** Bir sağlayıcının anahtarını (ve özel sunucu için adresini) düzenler; bağlantıyı sınar. */
@Composable
private fun ProviderDialog(id: String, vm: SettingsViewModel, onDismiss: () -> Unit) {
    val def = Providers.byId(id) ?: return
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modelsState by vm.modelsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showKey by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf(false) }
    val key = settings.keyFor(id)
    fun setKey(v: String) = vm.update {
        if (id == Providers.NVIDIA) it.copy(apiKey = v) else it.copy(providerKeys = if (v.isBlank()) it.providerKeys - id else it.providerKeys + (id to v))
    }
    val loading = id in modelsState.loadingProviders
    val canTest = if (id == Providers.CUSTOM) settings.customBaseUrl.isNotBlank() else key.isNotBlank()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { ProviderDot(id, 12.dp); Text(def.name) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(def.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (id == Providers.CUSTOM) {
                    OutlinedTextField(
                        value = settings.customBaseUrl,
                        onValueChange = { v -> vm.update { it.copy(customBaseUrl = v.trim()) } },
                        label = { Text("Sunucu adresi") },
                        placeholder = { Text("http://192.168.1.10:11434/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (settings.customBaseUrl.startsWith("http://")) {
                        Text(
                            "Bu adres şifresiz (http). Yalnızca kendi yerel ağınızdaki sunucular için kullanın; mesajlarınız ağda açık gider.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { v -> setKey(v.trim()) },
                    label = { Text(if (id == Providers.CUSTOM) "API anahtarı (isteğe bağlı)" else "API anahtarı (${def.keyHint})") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Göster/gizle")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (def.keyUrl.isNotBlank()) {
                        OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(def.keyUrl))) } }) { Text("Anahtar al") }
                    }
                    Button(onClick = { tested = true; vm.refreshModels(id) }, enabled = canTest && !loading) {
                        Text(if (loading) "Sınanıyor…" else "Bağlantıyı sına")
                    }
                }
                if (tested && !loading) {
                    val error = modelsState.errors[id]
                    if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    else Text("Bağlantı başarılı: ${modelsState.modelsOf(id).size} model bulundu.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tamam") } },
        dismissButton = {
            if (key.isNotBlank() || (id == Providers.CUSTOM && settings.customBaseUrl.isNotBlank())) {
                TextButton(onClick = { setKey(""); if (id == Providers.CUSTOM) vm.update { it.copy(customBaseUrl = "") }; onDismiss() }) { Text("Kaldır") }
            }
        },
    )
}

/** Ayarlar ekranındaki bir bölüm: simgeli başlık + kart. */
@Composable
private fun SettingsCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ModelField(label: String, value: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.ifBlank { "Seçilmedi" }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun DeviceVoiceDialog(vm: SettingsViewModel, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val voices by vm.deviceVoices.collectAsStateWithLifecycle()
    var onlyTurkish by remember { mutableStateOf(true) }
    val shown = voices.filter { !onlyTurkish || it.language.startsWith("tr") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cihaz sesi seç") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selected = onlyTurkish, onClick = { onlyTurkish = true }, label = { Text("Türkçe") })
                    androidx.compose.material3.FilterChip(selected = !onlyTurkish, onClick = { onlyTurkish = false }, label = { Text("Tüm diller") })
                }
                if (shown.isEmpty()) {
                    Text(
                        if (voices.isEmpty()) "Sesler yükleniyor… Cihazda metin okuma motoru yoksa Ayarlar > Metin okuma bölümünden kurun."
                        else "Bu dil için cihazda ses yok. Ayarlar > Metin okuma > Ses verisi bölümünden Türkçe sesleri indirin.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(shown, key = { _, v -> v.name }) { index, v ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = v.name == selected, onClick = { onSelect(v.name) })
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Ses ${index + 1} · " + java.util.Locale.forLanguageTag(v.language).displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    v.name + if (v.network) " · internet gerekir" else " · çevrimdışı",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                vm.previewDeviceVoice(v.name, if (v.language.startsWith("tr")) "Merhaba, ben Kutu. Bugün sana yardımcı olmak için buradayım." else "Hello, I am Kutu. I am here to help you today.")
                            }) { Text("Dinle") }
                        }
                    }
                }
                TextButton(onClick = { onSelect("") }) { Text("Varsayılan sesi kullan") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tamam") } },
    )
}
