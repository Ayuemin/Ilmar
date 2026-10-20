package com.ayuemin.ilmar

import android.os.Bundle
import com.ayuemin.ilmar.chat.ChatScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.rgb(0, 0, 0)
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.rgb(0, 0, 0)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            var settings by remember { mutableStateOf(false) }
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
                    if (settings) SettingsScreen(onBack = { settings = false })
                    else ChatScreen(onSettings = { settings = true })
                }
            }
        }
    }
}

@Composable
fun IlmarApp() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold { insets ->
            Surface(modifier = Modifier.fillMaxSize().padding(insets)) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Ilmar", style = MaterialTheme.typography.headlineLarge)
                    Text("Чат в разработке", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
