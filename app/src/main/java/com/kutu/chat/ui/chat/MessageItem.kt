package com.kutu.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.kutu.chat.data.db.MessageEntity
import com.kutu.chat.data.db.ROLE_USER
import com.kutu.chat.data.db.STATUS_ERROR
import com.kutu.chat.domain.Generation
import com.kutu.chat.domain.splitThinking
import com.kutu.chat.ui.components.MarkdownView

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessage(
    message: MessageEntity,
    onEdit: () -> Unit,
    enabled: Boolean,
) {
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .combinedClickable(onClick = {}, onLongClick = { menu = true }),
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Kopyala") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    onClick = {
                        clipboard.setText(AnnotatedString(message.content))
                        menu = false
                    },
                )
                if (enabled) {
                    DropdownMenuItem(
                        text = { Text("Düzenle ve yeniden gönder") },
                        onClick = {
                            menu = false
                            onEdit()
                        },
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
    onRegenerate: () -> Unit,
    onShare: (String) -> Unit,
    onPickModel: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val streaming = generation != null && generation.messageId == message.id
    val rawText = if (streaming) generation!!.text else message.content
    val reasoning = if (streaming) generation!!.reasoning else message.reasoning
    val split = remember(rawText, reasoning) { splitThinking(rawText, reasoning) }
    val waiting = streaming && split.answer.isBlank() && split.thinking.isBlank()

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (message.model.isNotBlank()) {
            Text(
                message.model.substringAfter('/'),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (split.thinking.isNotBlank()) {
            ThinkingBlock(split.thinking, active = streaming && split.answer.isBlank())
        }

        if (waiting) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Yanıt hazırlanıyor…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (split.answer.isNotBlank()) {
            SelectionContainer { MarkdownView(split.answer) }
        }

        if (message.status == STATUS_ERROR && message.error.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(message.error, style = MaterialTheme.typography.bodyMedium)
                    if (isLast) {
                        Row {
                            TextButton(onClick = onRegenerate) { Text("Tekrar dene") }
                            TextButton(onClick = onPickModel) { Text("Model değiştir") }
                        }
                    }
                }
            }
        }

        if (!streaming && split.answer.isNotBlank()) {
            Row {
                IconButton(onClick = { clipboard.setText(AnnotatedString(split.answer)) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Kopyala", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = { onShare(split.answer) }) {
                    Icon(Icons.Default.Share, contentDescription = "Paylaş", modifier = Modifier.size(18.dp))
                }
                if (isLast) {
                    IconButton(onClick = onRegenerate) {
                        Icon(Icons.Default.Refresh, contentDescription = "Yeniden üret", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingBlock(text: String, active: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    if (active) "Düşünüyor…" else "Düşünce süreci",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (active) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
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
