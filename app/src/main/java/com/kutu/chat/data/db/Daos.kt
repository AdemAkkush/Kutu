package com.kutu.chat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query(
        "SELECT DISTINCT c.* FROM chats c LEFT JOIN messages m ON m.chatId = c.id " +
            "WHERE c.title LIKE '%' || :query || '%' OR m.content LIKE '%' || :query || '%' " +
            "ORDER BY c.pinned DESC, c.updatedAt DESC",
    )
    fun search(query: String): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observe(id: Long): Flow<ChatEntity?>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun get(id: Long): ChatEntity?

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): ChatEntity?

    @Insert
    suspend fun insert(chat: ChatEntity): Long

    @Update
    suspend fun update(chat: ChatEntity)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE chats SET updatedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)

    @Query("UPDATE chats SET title = :title, titleLocked = :locked WHERE id = :id")
    suspend fun setTitle(id: Long, title: String, locked: Boolean)

    @Query("UPDATE chats SET model = :model WHERE id = :id")
    suspend fun setModel(id: Long, model: String)

    @Query("UPDATE chats SET systemPrompt = :prompt WHERE id = :id")
    suspend fun setSystemPrompt(id: Long, prompt: String)

    @Query("UPDATE chats SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE chats SET summary = :summary, summarizedUpTo = :upTo WHERE id = :id")
    suspend fun setSummary(id: Long, summary: String, upTo: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id")
    fun observe(chatId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id")
    suspend fun list(chatId: Long): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
    suspend fun count(chatId: Long): Int

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    /** [fromId] ve sonrasındaki tüm mesajları siler. */
    @Query("DELETE FROM messages WHERE chatId = :chatId AND id >= :fromId")
    suspend fun deleteFrom(chatId: Long, fromId: Long)

    @Query("SELECT * FROM messages WHERE status = $STATUS_STREAMING")
    suspend fun streaming(): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    suspend fun list(): List<MemoryEntity>

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Update
    suspend fun update(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}
