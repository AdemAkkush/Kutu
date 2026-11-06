package com.kutu.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.MaterialTheme
import com.kutu.chat.ui.ChatListViewModel
import com.kutu.chat.ui.ChatViewModel
import com.kutu.chat.ui.MemoryViewModel
import com.kutu.chat.ui.PersonasViewModel
import com.kutu.chat.ui.SettingsViewModel
import com.kutu.chat.ui.personas.PersonasScreen
import com.kutu.chat.ui.chat.ChatScreen
import com.kutu.chat.ui.chatlist.DrawerContent
import com.kutu.chat.ui.memory.MemoryScreen
import com.kutu.chat.ui.settings.SettingsScreen
import com.kutu.chat.ui.theme.KutuTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as KutuApp).container
        val factory = viewModelFactory {
            initializer { ChatViewModel(container) }
            initializer { ChatListViewModel(container) }
            initializer { MemoryViewModel(container) }
            initializer { SettingsViewModel(container) }
            initializer { PersonasViewModel(container) }
        }
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            KutuTheme(settings.theme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppRoot(factory)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(factory: androidx.lifecycle.ViewModelProvider.Factory) {
    val nav = rememberNavController()
    val chatVm: ChatViewModel = viewModel(factory = factory)

    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            val listVm: ChatListViewModel = viewModel(factory = factory)
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            val snackbar = remember { SnackbarHostState() }
            val currentId by chatVm.currentChatId.collectAsStateWithLifecycle()

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    DrawerContent(
                        vm = listVm,
                        selectedId = currentId,
                        onNewChat = { chatVm.newChat(); scope.launch { drawerState.close() } },
                        onOpenChat = { chatVm.openChat(it); scope.launch { drawerState.close() } },
                        onChatDeleted = { if (currentId == it) chatVm.newChat() },
                        onOpenMemory = { scope.launch { drawerState.close() }; nav.navigate("memory") },
                        onOpenPersonas = { scope.launch { drawerState.close() }; nav.navigate("personas") },
                        onOpenSettings = { scope.launch { drawerState.close() }; nav.navigate("settings") },
                    )
                },
            ) {
                ChatScreen(
                    vm = chatVm,
                    snackbar = snackbar,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenMemory = { nav.navigate("memory") },
                    onOpenPersonas = { nav.navigate("personas") },
                )
            }
        }
        composable("personas") {
            val vm: PersonasViewModel = viewModel(factory = factory)
            PersonasScreen(
                vm,
                onBack = { nav.popBackStack() },
                onStart = { persona -> chatVm.startChatWith(persona); nav.popBackStack() },
            )
        }
        composable("memory") {
            val vm: MemoryViewModel = viewModel(factory = factory)
            MemoryScreen(vm, onBack = { nav.popBackStack() })
        }
        composable("settings") {
            val vm: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(vm, onBack = { nav.popBackStack() }, onOpenMemory = { nav.navigate("memory") })
        }
    }
}
