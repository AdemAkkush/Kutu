package com.kutu.chat.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
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

    var showKey by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    var systemPrompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var tested by remember { mutableStateOf(false) }

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("NVIDIA Build bağlantısı")
            OutlinedTextField(
                value = settings.apiKey,
                onValueChange = { v -> vm.update { it.copy(apiKey = v.trim()) } },
                label = { Text("API anahtarı (nvapi-…)") },
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
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://build.nvidia.com/settings/api-keys")))
                }) { Text("Anahtar al") }
                Button(onClick = { tested = true; vm.refreshModels() }, enabled = settings.apiKey.isNotBlank() && !modelsState.loading) {
                    Text(if (modelsState.loading) "Sınanıyor…" else "Bağlantıyı sına")
                }
            }
            if (tested && !modelsState.loading) {
                if (modelsState.error != null) {
                    Text(modelsState.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Bağlantı başarılı: ${modelsState.models.size} model bulundu.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedTextField(
                value = settings.baseUrl,
                onValueChange = { v -> vm.update { it.copy(baseUrl = v.trim()) } },
                label = { Text("API adresi") },
                singleLine = true,
                supportingText = { Text("Varsayılan: $DEFAULT_BASE_URL") },
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            Section("Modeller")
            ModelField("Varsayılan sohbet modeli", settings.defaultModel) { picker = PickerTarget.DEFAULT }
            ModelField("Arka plan modeli (başlık, özet, hafıza)", settings.memoryModel) { picker = PickerTarget.BACKGROUND }
            Text(
                "Arka plan işleri için küçük ve hızlı bir model seçmek hem hızlı hem ucuzdur.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Section("Hafıza")
            ToggleRow("Hafızayı sohbetlerde kullan", settings.memoryEnabled) { v -> vm.update { it.copy(memoryEnabled = v) } }
            ToggleRow("Sohbetlerden otomatik öğren", settings.autoMemory, enabled = settings.memoryEnabled) { v -> vm.update { it.copy(autoMemory = v) } }
            OutlinedButton(onClick = onOpenMemory) { Text("Kayıtlı hafızayı yönet") }

            HorizontalDivider()
            Section("Üretim")
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

            HorizontalDivider()
            Section("Sistem istemi")
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it; vm.update { s -> s.copy(systemPrompt = it) } },
                minLines = 4,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { vm.update { it.copy(systemPrompt = DEFAULT_SYSTEM_PROMPT) } }) { Text("Varsayılana döndür") }

            HorizontalDivider()
            Section("Görünüm")
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
            Text(
                "API anahtarınız cihazda Android Keystore ile şifrelenerek saklanır ve yalnızca NVIDIA'ya gönderilir.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }

    picker?.let { target ->
        val current = if (target == PickerTarget.DEFAULT) settings.defaultModel else settings.memoryModel
        ModelPickerSheet(
            current = current,
            state = modelsState,
            favorites = settings.favoriteModels,
            onSelect = { id ->
                vm.update { if (target == PickerTarget.DEFAULT) it.copy(defaultModel = id) else it.copy(memoryModel = id) }
                picker = null
            },
            onToggleFavorite = vm::toggleFavorite,
            onRefresh = vm::refreshModels,
            onDismiss = { picker = null },
        )
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
