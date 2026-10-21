package com.ayuemin.ilmar

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuemin.ilmar.chat.ChatViewModel
import androidx.compose.runtime.saveable.rememberSaveable
import com.ayuemin.ilmar.chat.ChatScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import com.ayuemin.ilmar.browser.BrowserRuntime
import com.ayuemin.ilmar.browser.BrowserHost
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK))
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            val controller: ChatViewModel = viewModel()
            var settings by rememberSaveable { mutableStateOf(false) }
            var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
            MaterialTheme(colorScheme = darkColorScheme(
                background = Color(0xFF000000), surface = Color(0xFF181818),
                surfaceContainerLow = Color(0xFF1B1B1B), surfaceContainer = Color(0xFF282828),
                primary = Color(0xFFF1F1F1), onPrimary = Color(0xFF151515),
                outline = Color(0xFF626262), outlineVariant = Color(0xFF383838),
                onSurfaceVariant = Color(0xFFA3A3A3),
                onBackground = Color(0xFFF2F2F2), onSurface = Color(0xFFF2F2F2)
            )) {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = Color(0xFF000000)) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    val chatState by controller.state.collectAsState()
                    val browserState by BrowserRuntime.state.collectAsState()
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().zIndex(if (browserState.visible) 1f else 0f).alpha(if (browserState.visible) 1f else 0f)) {
                        BrowserHost(onResumeTask = controller::resumeBrowserTask, screenAllowed = !settings && browserState.chat == chatState.selectedId)
                    }
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().zIndex(if (browserState.visible) 0f else 1f)) {
                    if (settings) SettingsScreen(onBack = { settings = false }, initialPage = settingsPage)
                    else ChatScreen(onSettings = { settingsPage = null; settings = true }, controller = controller,
                        onConnectionSettings = { settingsPage = "Подключения"; settings = true },
                        onSettingsPage = { settingsPage = it; settings = true })
                    }
                    com.ayuemin.ilmar.browser.BrowserPromptDialog(chatState.selectedId)
                    }
                }
            }
        }
    }
}

