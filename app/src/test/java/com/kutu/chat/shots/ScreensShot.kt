package com.kutu.chat.shots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.kutu.chat.KutuApp
import com.kutu.chat.data.ThemeMode
import com.kutu.chat.data.db.ChatEntity
import com.kutu.chat.data.db.MessageEntity
import com.kutu.chat.ui.ChatListViewModel
import com.kutu.chat.ui.ChatViewModel
import com.kutu.chat.ui.SettingsViewModel
import com.kutu.chat.ui.chat.ChatScreen
import com.kutu.chat.ui.chatlist.DrawerContent
import com.kutu.chat.ui.settings.SettingsScreen
import com.kutu.chat.ui.theme.KutuTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Görsel kontrol: gerçek ekranları örnek veriyle çizer, app/build/shots altına PNG yazar. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = KutuApp::class, qualifiers = "w411dp-h891dp-xhdpi")
class ScreensShot {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val container get() = ApplicationProvider.getApplicationContext<KutuApp>().container

    private val answer = """
## Paralel istekler

`async` ile iki isteği **aynı anda** başlatıp `await()` ile bekleyebilirsin:

```kotlin
suspend fun load() = coroutineScope {
    val a = async { api.user() }   // paralel
    val b = async { api.posts() }
    a.await() to b.await()
}
```

- Hata olursa *iki iş de* iptal olur
- Sonuçlar sırayla döner

| Yöntem | Süre |
|---|---|
| Sıralı | 2 sn |
| `async` | 1 sn |
""".trimIndent()

    private fun seed(): Long = runBlocking {
        container.settings.update { it.copy(apiKey = "nvapi-test") }
        val dao = container.db.chatDao()
        listOf("Tatil planı önerileri", "E-posta taslağı", "Python hata ayıklama").forEachIndexed { i, t ->
            dao.insert(ChatEntity(title = t, model = "google/gemma-4-31b-it", createdAt = 1, updatedAt = System.currentTimeMillis() - (i + 1) * 90_000_000L, titleLocked = true, pinned = i == 0))
        }
        val id = dao.insert(ChatEntity(title = "Kotlin coroutine örneği", model = "google/gemma-4-31b-it", createdAt = 1, updatedAt = System.currentTimeMillis(), titleLocked = true))
        val m = container.db.messageDao()
        m.insert(MessageEntity(chatId = id, role = "user", content = "Bana coroutine ile paralel istek örneği gösterir misin?", createdAt = 1))
        m.insert(MessageEntity(chatId = id, role = "assistant", model = "google/gemma-4-31b-it", createdAt = 2, reasoning = "Kullanıcı coroutine istiyor.", content = answer))
        id
    }

    private fun shot(name: String, dark: Boolean, wait: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            KutuTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.onAllNodes(hasText(wait, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onRoot().captureRoboImage("build/shots/$name.png")
    }

    private fun chat(dark: Boolean, name: String) {
        seed()
        val vm = ChatViewModel(container)
        shot(name, dark, "Paralel istekler") {
            ChatScreen(vm, remember { SnackbarHostState() }, {}, {}, {}, {})
        }
    }

    @Test fun chatLight() = chat(false, "chat_light")
    @Test fun chatDark() = chat(true, "chat_dark")

    @Test fun emptyDark() {
        container.settings.update { it.copy(apiKey = "nvapi-test") }
        val vm = ChatViewModel(container)
        shot("empty_dark", true, "Mesaj yaz") { ChatScreen(vm, remember { SnackbarHostState() }, {}, {}, {}, {}) }
    }

    @Test fun drawerLight() {
        val id = seed()
        val vm = ChatListViewModel(container)
        shot("drawer_light", false, "Tatil planı") { DrawerContent(vm, id, {}, {}, {}, {}, {}, {}) }
    }

    @Test fun settingsDark() {
        val vm = SettingsViewModel(container)
        shot("settings_dark", true, "Ayarlar") { SettingsScreen(vm, {}, {}) }
    }
}
