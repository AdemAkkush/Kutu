package com.kutu.chat

import android.app.Application
import com.kutu.chat.data.ModelsRepository
import com.kutu.chat.data.SettingsStore
import com.kutu.chat.data.api.NvidiaApi
import com.kutu.chat.data.db.AppDatabase
import com.kutu.chat.domain.ChatEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.create(app)
    val settings = SettingsStore(app)
    val api = NvidiaApi()
    val models = ModelsRepository(api, settings)
    val engine = ChatEngine(db, api, settings, scope)

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
