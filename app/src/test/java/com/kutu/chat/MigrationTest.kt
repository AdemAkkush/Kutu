package com.kutu.chat

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.kutu.chat.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migrate1To2KeepsExistingChatsAndMessages() {
        helper.createDatabase("migtest", 1).apply {
            execSQL("INSERT INTO chats (id,title,titleLocked,model,systemPrompt,summary,summarizedUpTo,pinned,createdAt,updatedAt) VALUES (7,'Eski sohbet',1,'m','','özet',3,1,10,20)")
            execSQL("INSERT INTO messages (id,chatId,role,content,reasoning,model,status,error,createdAt) VALUES (1,7,'user','merhaba','','',0,'',11)")
            execSQL("INSERT INTO memories (id,content,enabled,sourceChatId,createdAt,updatedAt) VALUES (1,'Adem',1,7,1,1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migtest", 2, true, AppDatabase.MIGRATION_1_2)

        db.query("SELECT title, summary, pinned, persona FROM chats WHERE id = 7").use {
            it.moveToFirst()
            assertEquals("Eski sohbet", it.getString(0))
            assertEquals("özet", it.getString(1))
            assertEquals(1, it.getInt(2))
            assertEquals("", it.getString(3))
        }
        db.query("SELECT content, attachments, versions, activeVersion FROM messages WHERE id = 1").use {
            it.moveToFirst()
            assertEquals("merhaba", it.getString(0))
            assertEquals("", it.getString(1))
            assertEquals("", it.getString(2))
            assertEquals(0, it.getInt(3))
        }
        db.query("SELECT content FROM memories").use { it.moveToFirst(); assertEquals("Adem", it.getString(0)) }
        db.execSQL("INSERT INTO personas (name,emoji,systemPrompt,model,createdAt) VALUES ('a','b','c','d',1)")
        db.execSQL("INSERT INTO templates (title,text,createdAt) VALUES ('a','b',1)")
    }
}
