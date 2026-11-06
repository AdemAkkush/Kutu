package com.kutu.chat.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChatEntity::class, MessageEntity::class, MemoryEntity::class, PersonaEntity::class, TemplateEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun personaDao(): PersonaDao
    abstract fun templateDao(): TemplateDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "kutu.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v1 → v2: ekler, yanıt sürümleri, kişilik alanı; kişilikler ve şablonlar tabloları. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chats` ADD COLUMN `persona` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `attachments` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `versions` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `activeVersion` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `personas` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `emoji` TEXT NOT NULL, `systemPrompt` TEXT NOT NULL, `model` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `templates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            }
        }
    }
}
