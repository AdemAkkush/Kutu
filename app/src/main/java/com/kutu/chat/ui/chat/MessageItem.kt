package com.kutu.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kutu.chat.data.db.MessageEntity
import com.kutu.chat.data.db.STATUS_ERROR
import com.kutu.chat.domain.Attachments
import com.kutu.chat.domain.Generation
import com.kutu.chat.domain.ModelRef
import com.kutu.chat.domain.Providers
import com.kutu.chat.domain.Versions
import com.kutu.chat.domain.splitThinking
import com.kutu.chat.ui.components.AttachmentStrip
import com.kutu.chat.ui.components.KutuLogo
import com.kutu.chat.ui.components.MarkdownView
import com.kutu.chat.ui.components.TypingDots

/** "3,2 sn" biçiminde süre. */
fun formatSeconds(ms: Long): String = "%.1f sn".format(ms / 1000.0).replace('.', ',')

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessage(
    message: MessageEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean,
) {
    val attachments = remember(message.attachments) { Attachments.parse(message.attachments) }
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
    val brush = remember(scheme.primaryContainer, scheme.tertiaryContainer) {
        Brush.linearGradient(listOf(scheme.primaryContainer, lerp(scheme.primaryContainer, scheme.tertiaryContainer, 0.55f)))
    }
    Row(modifier = Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.End) {
        Box {
            Box(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .clip(shape)
                    .background(brush)
                    .combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                CompositionLocalProvider(LocalContentColor provides scheme.onPrimaryContainer) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (attachments.isNotEmpty()) AttachmentStrip(attachments)
                        if (message.content.isNotBlank()) Text(text = message.content, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Kopyala") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    onClick = { clipboard.setText(AnnotatedString(message.content)); menu = false },
                )
                if (enabled) {
                    DropdownMenuItem(
                        text = { Text("Düzenle ve yeniden gönder") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Sil") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
fun AssistantMessage(
    message: MessageEntity,
    generation: Generation?,
    isLast: Boolean,
    speaking: Boolean,
    preparingSpeech: Boolean,
    canEdit: Boolean,
    onRegenerate: () -> Unit,
    onRegenerateWith: () -> Unit,
    onShare: (String) -> Unit,
    onPickModel: () -> Unit,
    onSpeak: (String) -> Unit,
    onDelete: () -> Unit,
    onVersion: (Int) -> Unit,
    onContinue: () -> Unit,
) {
    val versionCount = remember(message.versions) { Versions.count(message) }
    var overflow by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val streaming = generation != null && generation.messageId == message.id
    val rawText = if (streaming) generation!!.text else message.content
    val reasoning = if (streaming) generation!!.reasoning else message.reasoning
    val split = remember(rawText, reasoning) { splitThinking(rawText, reasoning) }
    val waiting = streaming && split.answer.isBlank() && split.thinking.isBlank()
    val ref = remember(message.model) { ModelRef.parse(message.model) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Başlık: logo + model + süre
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KutuLogo(22.dp)
            Text(
                ref.display.ifBlank { "Asistan" },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (ref.provider != Providers.NVIDIA) {
                Text(Providers.name(ref.provider), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (!streaming && message.durationMs > 0) {
                Text(
                    "· " + formatSeconds(message.durationMs),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }

        if (split.thinking.isNotBlank()) {
            ThinkingBlock(split.thinking, active = streaming && split.answer.isBlank())
        }

        if (streaming && generation!!.notice.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(generation.notice, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }

        if (waiting) TypingDots(modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp))

        if (split.answer.isNotBlank()) {
            SelectionContainer { MarkdownView(split.answer) }
        }

        if (message.status == STATUS_ERROR && message.error.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
                    Text(message.error, style = MaterialTheme.typography.bodyMedium)
                    if (isLast) {
                        Row(modifier = Modifier.offset(x = (-10).dp)) {
                            if (message.content.isNotBlank()) TextButton(onClick = onContinue) { Text("Devam et") }
                            TextButton(onClick = onRegenerate) { Text(if (message.content.isNotBlank()) "Baştan üret" else "Tekrar dene") }
                            TextButton(onClick = onPickModel) { Text("Model değiştir") }
                        }
                    }
                }
            }
        }

        if (!streaming && split.answer.isNotBlank()) {
            Row(modifier = Modifier.offset(x = (-8).dp), verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(Icons.Default.ContentCopy, "Kopyala") { clipboard.setText(AnnotatedString(split.answer)) }
                if (speaking && preparingSpeech) {
                    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) }
                } else {
                    ActionIcon(if (speaking) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp, if (speaking) "Okumayı durdur" else "Sesli oku") { onSpeak(split.answer) }
                }
                if (isLast) ActionIcon(Icons.Default.Refresh, "Yeniden üret", onClick = onRegenerate)
                if (versionCount > 1 && canEdit) {
                    val active = message.activeVersion.coerceIn(0, versionCount - 1)
                    ActionIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Önceki sürüm", enabled = active > 0) { onVersion(active - 1) }
                    Text("${active + 1}/$versionCount", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ActionIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Sonraki sürüm", enabled = active < versionCount - 1) { onVersion(active + 1) }
                }
                if (canEdit) {
                    Box {
                        ActionIcon(Icons.Default.MoreHoriz, "Diğer") { overflow = true }
                        DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                            if (isLast) DropdownMenuItem(text = { Text("Başka modelle yanıtla") }, onClick = { overflow = false; onRegenerateWith() })
                            DropdownMenuItem(text = { Text("Paylaş") }, onClick = { overflow = false; onShare(split.answer) })
                            DropdownMenuItem(text = { Text("Bu yanıtı sil") }, onClick = { overflow = false; onDelete() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(38.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.35f))
    }
}

@Composable
private fun ThinkingBlock(text: String, active: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                Text(
                    if (active) "Düşünüyor…" else "Düşünce süreci",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                if (active) TypingDots(color = MaterialTheme.colorScheme.tertiary)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
