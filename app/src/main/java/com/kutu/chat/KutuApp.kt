package com.kutu.chat

import android.app.Application
import com.kutu.chat.data.AttachmentStore
import com.kutu.chat.data.BackupManager
import com.kutu.chat.data.Speaker
import com.kutu.chat.data.ModelsRepository
import com.kutu.chat.data.SettingsStore
import com.kutu.chat.data.api.NvidiaApi
import com.kutu.chat.data.db.AppDatabase
import com.kutu.chat.domain.ChatEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.create(app)
    val settings = SettingsStore(app)
    val api = NvidiaApi()
    val models = ModelsRepository(api, settings)
    val attachments = AttachmentStore(app)
    val engine = ChatEngine(db, api, settings, attachments, scope)
    val backup = BackupManager(db, attachments)
    val speaker by lazy { Speaker(app) }

    init {
        scope.launch { engine.recoverInterrupted() }
    }
}

class KutuApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
