package com.kutu.chat

import android.app.Application
import com.kutu.chat.data.AiSpeech
import com.kutu.chat.data.AttachmentStore
import com.kutu.chat.data.RivaTtsService
import com.kutu.chat.data.BackupManager
import com.kutu.chat.data.Speaker
import com.kutu.chat.data.ModelsRepository
import com.kutu.chat.data.SettingsStore
import com.kutu.chat.data.api.NvidiaApi
import com.kutu.chat.data.db.AppDatabase
import com.kutu.chat.domain.ChatEngine
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    val speaker by lazy { Speaker(app, settings, scope, AiSpeech(RivaTtsService(), settings)) }

    init {
        scope.launch { engine.recoverInterrupted() }
        // Üretim/arka plan işi sürerken ön plan servisini aç, bitince (kısa bir bekleyişle) kapat.
        scope.launch {
            engine.activeJobs.map { it > 0 }.distinctUntilChanged().collectLatest { busy ->
                if (busy) {
                    runCatching { ContextCompat.startForegroundService(app, Intent(app, GenerationService::class.java)) }
                } else {
                    delay(SERVICE_LINGER_MS)
                    app.stopService(Intent(app, GenerationService::class.java))
                }
            }
        }
    }

    private companion object {
        const val SERVICE_LINGER_MS = 2_500L
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
