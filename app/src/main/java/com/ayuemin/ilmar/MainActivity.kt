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
        setContent {
            var settings by remember { mutableStateOf(false) }
            MaterialTheme(colorScheme = darkColorScheme(
                background = Color(0xFF14151C), surface = Color(0xFF14151C),
                onBackground = Color(0xFFF2F0F8), onSurface = Color(0xFFF2F0F8)
            )) {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = Color(0xFF14151C)) {
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
