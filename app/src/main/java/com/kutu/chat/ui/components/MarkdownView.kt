package com.kutu.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kutu.chat.data.DocumentExtractor

@Composable
fun MarkdownView(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val styles = InlineStyles(
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
        linkColor = MaterialTheme.colorScheme.primary,
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 24.sp
                        2 -> 21.sp
                        3 -> 18.sp
                        else -> 16.sp
                    }
                    Text(
                        text = buildInline(block.text, styles),
                        color = color,
                        fontSize = size,
                        fontWeight = FontWeight.Bold,
                        lineHeight = size * 1.3f,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                is MdBlock.Paragraph -> Text(
                    text = buildInline(block.text, styles),
                    color = color,
                    style = MaterialTheme.typography.bodyLarge,
                )
                is MdBlock.ListItem -> Row(modifier = Modifier.padding(start = (12 * block.indent).dp)) {
                    Text(
                        text = block.marker,
                        color = color,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.width(if (block.marker.length > 2) 30.dp else 22.dp),
                    )
                    Text(
                        text = buildInline(block.text, styles),
                        color = color,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                is MdBlock.Quote -> Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                    )
                    Text(
                        text = buildInline(block.text, styles),
                        color = color.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                is MdBlock.Code -> CodeBlock(block.lang, block.code)
                is MdBlock.Table -> TableBlock(block, styles, color)
                MdBlock.Rule -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
fun CodeBlock(lang: String, code: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = lang.ifBlank { "kod" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(code))
                copied = true
            }) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "Kodu kopyala",
                    modifier = Modifier.width(18.dp),
                )
            }
        }
        Text(
            text = code,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
    }
}

/** Tabloyu Excel'in doğru okuyacağı CSV'ye çevirir (UTF-8 BOM'lu, Türkçe karakterler bozulmaz). */
fun tableToCsv(table: MdBlock.Table): String {
    fun cell(s: String) = DocumentExtractor.csv(s.replace(Regex("[*_`]+"), "").trim())
    val cols = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    fun row(r: List<String>) = (0 until cols).joinToString(",") { cell(r.getOrElse(it) { "" }) }
    return "\uFEFF" + (listOf(row(table.header)) + table.rows.map(::row)).joinToString("\r\n")
}

private fun shareCsv(context: android.content.Context, table: MdBlock.Table) {
    val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600 * 1000L }?.forEach { it.delete() }
    val file = java.io.File(dir, "tablo-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".csv")
    file.writeText(tableToCsv(table))
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Tabloyu paylaş (CSV / Excel)"))
}

@Composable
private fun TableBlock(table: MdBlock.Table, styles: InlineStyles, color: Color) {
    val border = MaterialTheme.colorScheme.outlineVariant
    val cols = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    val context = androidx.compose.ui.platform.LocalContext.current
    Column {
    Column(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp)),
    ) {
        fun cell(text: String, bold: Boolean, bg: Color?): @Composable () -> Unit = {
            Text(
                text = buildInline(text, styles),
                color = color,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .width(150.dp)
                    .then(if (bg != null) Modifier.background(bg) else Modifier)
                    .padding(8.dp),
            )
        }
        Row {
            for (c in 0 until cols) cell(table.header.getOrElse(c) { "" }, true, MaterialTheme.colorScheme.surfaceContainerHigh)()
        }
        table.rows.forEach { row ->
            HorizontalDivider(color = border)
            Row {
                for (c in 0 until cols) cell(row.getOrElse(c) { "" }, false, null)()
            }
        }
    }
    androidx.compose.material3.TextButton(onClick = { shareCsv(context, table) }) {
        Text("CSV / Excel olarak paylaş", style = MaterialTheme.typography.labelMedium)
    }
    }
}
