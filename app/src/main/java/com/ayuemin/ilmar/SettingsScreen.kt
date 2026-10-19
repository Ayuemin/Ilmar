package com.ayuemin.ilmar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("← К чату") }
        Text("Настройки Ilmar", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Text("Модель и провайдер", style = MaterialTheme.typography.titleLarge)
        Text("Подключение API пока не реализовано. Ключи не вводятся и не сохраняются.")
        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        Text("Чаты и память", style = MaterialTheme.typography.titleLarge)
        Text("История сохраняется локально в SQLite. Удаление и экспорт появятся позже.")
        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        Text("Инструменты и разрешения", style = MaterialTheme.typography.titleLarge)
        Text("Управление инструментами и фоновыми разрешениями в разработке.")
        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        Text("О приложении", style = MaterialTheme.typography.titleLarge)
        Text("Ilmar · 0.1.0-dev")
    }
}
