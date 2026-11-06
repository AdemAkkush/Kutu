package com.kutu.chat.data

import com.kutu.chat.data.api.NvidiaApi
import com.kutu.chat.data.db.AppDatabase
import com.kutu.chat.data.db.ChatEntity
import com.kutu.chat.data.db.MemoryEntity
import com.kutu.chat.data.db.MessageEntity
import com.kutu.chat.data.db.PersonaEntity
import com.kutu.chat.data.db.TemplateEntity
import com.kutu.chat.domain.Attachments
import com.kutu.chat.domain.MemoryLogic
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long = 0,
    val chats: List<BackupChat> = emptyList(),
    val memories: List<BackupMemory> = emptyList(),
    val personas: List<BackupPersona> = emptyList(),
    val templates: List<BackupTemplate> = emptyList(),
) {
    companion object { const val FORMAT = "kutu-backup" }
}

@Serializable
data class BackupChat(
    val title: String,
    val titleLocked: Boolean,
    val model: String,
    val systemPrompt: String,
    val summary: String,
    /** Özetin kapsadığı son mesajın listedeki sırası (id'ler içe aktarmada değişir); -1 = özet yok. */
    val summarizedUpToIndex: Int,
    val pinned: Boolean,
    val persona: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<BackupMessage>,
)

@Serializable
data class BackupMessage(
    val role: String,
    val content: String,
    val reasoning: String = "",
    val model: String = "",
    val status: Int = 0,
    val error: String = "",
    val createdAt: Long,
    val attachments: String = "",
    val versions: String = "",
    val activeVersion: Int = 0,
)

@Serializable data class BackupMemory(val content: String, val enabled: Boolean, val createdAt: Long)
@Serializable data class BackupPersona(val name: String, val emoji: String, val systemPrompt: String, val model: String, val createdAt: Long)
@Serializable data class BackupTemplate(val title: String, val text: String, val createdAt: Long)

data class ImportResult(
    val chats: Int,
    val messages: Int,
    val memories: Int,
    val personas: Int,
    val templates: Int,
    val skippedChats: Int,
) {
    val summary: String get() = buildString {
        append("$chats sohbet ($messages mesaj), $memories hafıza kaydı, $personas kişilik, $templates şablon eklendi")
        if (skippedChats > 0) append("; $skippedChats sohbet zaten vardı, atlandı")
        append(".")
    }
}

class BackupException(message: String) : Exception(message)

/**
 * Tüm sohbetleri, hafızayı, kişilikleri, şablonları ve ek dosyalarını tek bir ZIP dosyasına yazar / geri yükler.
 * API anahtarı ve ayarlar yedeğe dahil edilmez.
 */
class BackupManager(
    private val db: AppDatabase,
    private val store: AttachmentStore,
) {
    suspend fun export(out: OutputStream) {
        val chats = db.chatDao().listAll()
        val used = LinkedHashSet<String>()
        val backupChats = chats.map { chat ->
            val msgs = db.messageDao().list(chat.id)
            msgs.forEach { m -> Attachments.parse(m.attachments).forEach { used.add(it.file) } }
            BackupChat(
                title = chat.title, titleLocked = chat.titleLocked, model = chat.model, systemPrompt = chat.systemPrompt,
                summary = chat.summary, summarizedUpToIndex = msgs.indexOfLast { it.id <= chat.summarizedUpTo && chat.summary.isNotEmpty() },
                pinned = chat.pinned, persona = chat.persona, createdAt = chat.createdAt, updatedAt = chat.updatedAt,
                messages = msgs.map {
                    BackupMessage(it.role, it.content, it.reasoning, it.model, it.status, it.error, it.createdAt, it.attachments, it.versions, it.activeVersion)
                },
            )
        }
        val file = BackupFile(
            exportedAt = System.currentTimeMillis(),
            chats = backupChats,
            memories = db.memoryDao().list().map { BackupMemory(it.content, it.enabled, it.createdAt) },
            personas = db.personaDao().list().map { BackupPersona(it.name, it.emoji, it.systemPrompt, it.model, it.createdAt) },
            templates = db.templateDao().list().map { BackupTemplate(it.title, it.text, it.createdAt) },
        )
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_ENTRY))
            zip.write(NvidiaApi.JSON.encodeToString(file).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (name in used) {
                val f = File(store.dir, name)
                if (!f.isFile) continue
                zip.putNextEntry(ZipEntry("$ATT_DIR$name"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    suspend fun import(input: InputStream): ImportResult {
        var json: String? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == JSON_ENTRY -> json = zip.readBytes().toString(Charsets.UTF_8)
                    entry.name.startsWith(ATT_DIR) && !entry.isDirectory -> {
                        // Yol geçişine karşı yalnızca dosya adı kullanılır.
                        val name = File(entry.name).name
                        val target = File(store.dir, name)
                        if (name.isNotBlank() && !target.exists()) target.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        val raw = json ?: throw BackupException("Bu bir Kutu yedek dosyası değil ($JSON_ENTRY bulunamadı).")
        val file = runCatching { NvidiaApi.JSON.decodeFromString<BackupFile>(raw) }
            .getOrElse { throw BackupException("Yedek dosyası okunamadı: ${it.message}") }
        if (file.format != BackupFile.FORMAT) throw BackupException("Bu bir Kutu yedek dosyası değil.")
        if (file.version > 1) throw BackupException("Bu yedek daha yeni bir sürümle alınmış; uygulamayı güncelleyin.")

        var chatCount = 0; var msgCount = 0; var skipped = 0
        val existing = db.chatDao().listAll().map { it.createdAt to it.title }.toMutableSet()
        for (c in file.chats) {
            if (!existing.add(c.createdAt to c.title)) { skipped++; continue }
            val chatId = db.chatDao().insert(
                ChatEntity(
                    title = c.title, titleLocked = c.titleLocked, model = c.model, systemPrompt = c.systemPrompt,
                    summary = c.summary, summarizedUpTo = 0, pinned = c.pinned, createdAt = c.createdAt,
                    updatedAt = c.updatedAt, persona = c.persona,
                ),
            )
            val ids = c.messages.map {
                db.messageDao().insert(
                    MessageEntity(
                        chatId = chatId, role = it.role, content = it.content, reasoning = it.reasoning, model = it.model,
                        status = if (it.status == 1) 0 else it.status, error = it.error, createdAt = it.createdAt,
                        attachments = it.attachments, versions = it.versions, activeVersion = it.activeVersion,
                    ),
                )
            }
            if (c.summary.isNotEmpty() && c.summarizedUpToIndex in ids.indices) {
                db.chatDao().setSummary(chatId, c.summary, ids[c.summarizedUpToIndex])
            }
            chatCount++; msgCount += ids.size
        }

        val knownMemories = db.memoryDao().list().map { MemoryLogic.normalize(it.content) }.toMutableSet()
        var memCount = 0
        for (m in file.memories) {
            if (!knownMemories.add(MemoryLogic.normalize(m.content))) continue
            db.memoryDao().insert(MemoryEntity(content = m.content, enabled = m.enabled, createdAt = m.createdAt, updatedAt = m.createdAt))
            memCount++
        }
        val knownPersonas = db.personaDao().list().map { it.name to it.systemPrompt }.toMutableSet()
        var personaCount = 0
        for (p in file.personas) {
            if (!knownPersonas.add(p.name to p.systemPrompt)) continue
            db.personaDao().insert(PersonaEntity(name = p.name, emoji = p.emoji, systemPrompt = p.systemPrompt, model = p.model, createdAt = p.createdAt))
            personaCount++
        }
        val knownTemplates = db.templateDao().list().map { it.title to it.text }.toMutableSet()
        var templateCount = 0
        for (t in file.templates) {
            if (!knownTemplates.add(t.title to t.text)) continue
            db.templateDao().insert(TemplateEntity(title = t.title, text = t.text, createdAt = t.createdAt))
            templateCount++
        }
        return ImportResult(chatCount, msgCount, memCount, personaCount, templateCount, skipped)
    }

    private companion object {
        const val JSON_ENTRY = "backup.json"
        const val ATT_DIR = "attachments/"
    }
}
