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
        window.statusBarColor = android.graphics.Color.rgb(5, 4, 4)
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.rgb(5, 4, 4)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            var settings by remember { mutableStateOf(false) }
            MaterialTheme(colorScheme = darkColorScheme(
                background = Color(0xFF050404), surface = Color(0xFF080605),
                surfaceContainerLow = Color(0xFF1B100D), surfaceContainer = Color(0xFF291A15),
                primary = Color(0xFFF0B198), onPrimary = Color(0xFF21120E),
                outline = Color(0xFF8D6257), outlineVariant = Color(0xFF60443C),
                onSurfaceVariant = Color(0xFFC9A99C),
                onBackground = Color(0xFFF6EDE9), onSurface = Color(0xFFF6EDE9)
            )) {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = Color(0xFF050404)) {
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
