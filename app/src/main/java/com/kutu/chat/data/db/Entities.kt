package com.kutu.chat.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

const val DEFAULT_CHAT_TITLE = "Yeni sohbet"

const val ROLE_USER = "user"
const val ROLE_ASSISTANT = "assistant"

const val STATUS_DONE = 0
const val STATUS_STREAMING = 1
const val STATUS_ERROR = 2

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = DEFAULT_CHAT_TITLE,
    /** Kullanıcı yeniden adlandırdıysa ya da başlık üretildiyse otomatik başlık değişmez. */
    val titleLocked: Boolean = false,
    val model: String,
    /** Boşsa genel sistem istemi kullanılır. */
    val systemPrompt: String = "",
    /** Uzun sohbetlerde eski mesajların özeti. */
    val summary: String = "",
    /** Bu id'ye kadar (dahil) olan mesajlar [summary] içinde özetlenmiştir. */
    val summarizedUpTo: Long = 0,
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    /** Sohbet bir kişiyle başlatıldıysa görünen adı (örn. "💻 Kod asistanı"). */
    @ColumnInfo(defaultValue = "''") val persona: String = "",
    /** Modelin sağlayıcısı (bkz. [com.kutu.chat.domain.Providers]). */
    @ColumnInfo(defaultValue = "'nvidia'") val provider: String = "nvidia",
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chatId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String,
    val content: String,
    val reasoning: String = "",
    val model: String = "",
    val status: Int = STATUS_DONE,
    val error: String = "",
    val createdAt: Long,
    /** Ekler (resim / metin dosyası) JSON listesi, bkz. [com.kutu.chat.domain.Attachments]. */
    @ColumnInfo(defaultValue = "''") val attachments: String = "",
    /** Yanıt sürümleri (yeniden üretimler) JSON listesi; boşsa tek sürüm. */
    @ColumnInfo(defaultValue = "''") val versions: String = "",
    @ColumnInfo(defaultValue = "0") val activeVersion: Int = 0,
    /** Yanıtın toplam üretim süresi ve ilk parçanın gelme süresi (ms); 0 = bilinmiyor. */
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val firstTokenMs: Long = 0,
)

/** Tüm sohbetlerde geçerli uzun süreli hafıza kaydı. */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val enabled: Boolean = true,
    val sourceChatId: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Kullanıcının oluşturduğu asistan kişiliği (hazır olanlar kodda tanımlıdır). */
@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String = "🤖",
    val systemPrompt: String,
    /** Boşsa varsayılan model kullanılır. */
    val model: String = "",
    val createdAt: Long,
)

/** Giriş alanına tek dokunuşla eklenen hızlı komut şablonu. */
@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val text: String,
    val createdAt: Long,
)
