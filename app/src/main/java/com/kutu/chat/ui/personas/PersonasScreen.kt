package com.kutu.chat.ui.personas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kutu.chat.data.db.PersonaEntity
import com.kutu.chat.data.db.TemplateEntity
import com.kutu.chat.domain.PersonaDef
import com.kutu.chat.domain.Personas
import com.kutu.chat.domain.toDef
import com.kutu.chat.ui.PersonasViewModel
import com.kutu.chat.ui.components.ConfirmDialog

private data class PersonaDraft(val id: Long, val name: String, val emoji: String, val prompt: String, val model: String)
private data class TemplateDraft(val id: Long, val title: String, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonasScreen(
    vm: PersonasViewModel,
    onBack: () -> Unit,
    onStart: (PersonaDef) -> Unit,
) {
    val personas by vm.personas.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var personaDraft by remember { mutableStateOf<PersonaDraft?>(null) }
    var templateDraft by remember { mutableStateOf<TemplateDraft?>(null) }
    var deletePersona by remember { mutableStateOf<PersonaEntity?>(null) }
    var deleteTemplate by remember { mutableStateOf<TemplateEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kişiler ve şablonlar") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (tab == 0) personaDraft = PersonaDraft(0, "", "🤖", "", "") else templateDraft = TemplateDraft(0, "", "")
            }) { Icon(Icons.Default.Add, contentDescription = "Ekle") }
        },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Kişiler") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Şablonlar") })
            }
            if (tab == 0) {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            "Kişilik, bir sohbete kendi sistem istemini (ve istersen modelini) verir. Seçip yeni bir sohbet başlatın.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item { Text("Benim kişilerim", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    if (personas.isEmpty()) {
                        item { Text("Henüz yok. + ile ekleyin ya da aşağıdaki hazır olanlardan birini kopyalayın.", style = MaterialTheme.typography.bodyMedium) }
                    }
                    items(personas, key = { "u${it.id}" }) { p ->
                        PersonaCard(
                            p.toDef(),
                            onStart = { onStart(p.toDef()) },
                            onEdit = { personaDraft = PersonaDraft(p.id, p.name, p.emoji, p.systemPrompt, p.model) },
                            onDelete = { deletePersona = p },
                            onCopy = null,
                        )
                    }
                    item { Text("Hazır kişiler", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                    items(Personas.builtIn, key = { "b${it.name}" }) { p ->
                        PersonaCard(
                            p,
                            onStart = { onStart(p) },
                            onEdit = null,
                            onDelete = null,
                            onCopy = { personaDraft = PersonaDraft(0, p.name + " (kopya)", p.emoji, p.systemPrompt, p.model) },
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            "Şablonlar, mesaj kutusundaki ✨ düğmesinden tek dokunuşla metin kutusuna eklenir.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item { Text("Benim şablonlarım", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    if (templates.isEmpty()) item { Text("Henüz yok. + ile ekleyin.", style = MaterialTheme.typography.bodyMedium) }
                    items(templates, key = { "t${it.id}" }) { t ->
                        Card {
                            Row(modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(t.title, style = MaterialTheme.typography.titleSmall)
                                    Text(t.text.trim(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { templateDraft = TemplateDraft(t.id, t.title, t.text) }) { Icon(Icons.Default.Edit, contentDescription = "Düzenle") }
                                IconButton(onClick = { deleteTemplate = t }) { Icon(Icons.Default.Delete, contentDescription = "Sil") }
                            }
                        }
                    }
                    item { Text("Hazır şablonlar", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                    items(Personas.builtInTemplates, key = { "bt${it.first}" }) { (title, text) ->
                        Card {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(title, style = MaterialTheme.typography.titleSmall)
                                Text(text.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    personaDraft?.let { d ->
        var name by remember(d) { mutableStateOf(d.name) }
        var emoji by remember(d) { mutableStateOf(d.emoji) }
        var prompt by remember(d) { mutableStateOf(d.prompt) }
        var model by remember(d) { mutableStateOf(d.model) }
        AlertDialog(
            onDismissRequest = { personaDraft = null },
            title = { Text(if (d.id == 0L) "Yeni kişilik" else "Kişiliği düzenle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(emoji, { emoji = it.take(4) }, label = { Text("Simge") }, singleLine = true, modifier = Modifier.weight(0.3f))
                        OutlinedTextField(name, { name = it }, label = { Text("Ad") }, singleLine = true, modifier = Modifier.weight(0.7f))
                    }
                    OutlinedTextField(prompt, { prompt = it }, label = { Text("Sistem istemi") }, minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(model, { model = it }, label = { Text("Model (isteğe bağlı)") }, singleLine = true,
                        placeholder = { Text("google/gemma-4-31b-it") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && prompt.isNotBlank(), onClick = {
                    vm.savePersona(d.id, name, emoji, prompt, model); personaDraft = null
                }) { Text("Kaydet") }
            },
            dismissButton = { TextButton(onClick = { personaDraft = null }) { Text("Vazgeç") } },
        )
    }
    templateDraft?.let { d ->
        var title by remember(d) { mutableStateOf(d.title) }
        var text by remember(d) { mutableStateOf(d.text) }
        AlertDialog(
            onDismissRequest = { templateDraft = null },
            title = { Text(if (d.id == 0L) "Yeni şablon" else "Şablonu düzenle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Başlık") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(text, { text = it }, label = { Text("Metin") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank() && text.isNotBlank(), onClick = {
                    vm.saveTemplate(d.id, title, text); templateDraft = null
                }) { Text("Kaydet") }
            },
            dismissButton = { TextButton(onClick = { templateDraft = null }) { Text("Vazgeç") } },
        )
    }
    deletePersona?.let { p ->
        ConfirmDialog("Kişilik silinsin mi?", "${p.emoji} ${p.name}", onConfirm = { vm.deletePersona(p.id); deletePersona = null }, onDismiss = { deletePersona = null })
    }
    deleteTemplate?.let { t ->
        ConfirmDialog("Şablon silinsin mi?", t.title, onConfirm = { vm.deleteTemplate(t.id); deleteTemplate = null }, onDismiss = { deleteTemplate = null })
    }
}

@Composable
private fun PersonaCard(
    p: PersonaDef,
    onStart: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onCopy: (() -> Unit)?,
) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.label, style = MaterialTheme.typography.titleMedium)
            Text(p.systemPrompt, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p.model.isNotBlank()) Text("Model: ${p.model}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart) { Text("Sohbet başlat") }
                if (onCopy != null) OutlinedButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.padding(end = 4.dp)); Text("Kopyala") }
                if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Düzenle") }
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Sil") }
            }
        }
    }
}
