package com.kutu.chat.data.db

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
