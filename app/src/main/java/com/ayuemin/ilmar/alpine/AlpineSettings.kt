package com.ayuemin.ilmar.alpine

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable
internal fun AlpineSettings() {
    val app = LocalContext.current
    val runtime = remember { AlpineRuntime(app) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(runtime.installed()) }
    var hasFiles by remember { mutableStateOf(runtime.hasFiles()) }
    var operation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var status by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("cat /etc/alpine-release; uname -m; apk --version") }
    Text("Alpine Linux · ARM64", style = MaterialTheme.typography.titleMedium)
    Text("Окружение Linux через PRoot по референсу Agora, без root и отдельного ярлыка. Rootfs скачивается отдельно, проверяется SHA-256, затем проходит пробный запуск. Одна файловая система общая для всех чатов; файлы сохраняются между командами. Сеть доступна; пакеты устанавливаются через apk add.")
    Text("PRoot работает с правами Ilmar и не является защитной sandbox. Выполняй доверенные команды. Ключи API не подставляются в окружение. Каждая команда агента требует подтверждения. Терминал неинтерактивный, вывод до 64 КиБ, время до 300 секунд.")
    if (!runtime.supported()) Text("Этот APK/устройство не поддерживает ARM64 PRoot.")
    Button(enabled = runtime.supported() && !installed && !busy, onClick = { operation = scope.launch {
        busy = true; status = "Скачивание и проверка Alpine…"
        try { status = runtime.install(); installed = runtime.installed() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { status = "Установка остановлена: ${e.message?.take(300)}" }
        finally { busy = false; installed = runtime.installed(); hasFiles = runtime.hasFiles() }
    } }) { Text("Установить Alpine ${AlpineRuntime.VERSION}") }
    OutlinedTextField(command, { command = it }, label = { Text("Команда Alpine") }, minLines = 2)
    TextButton(enabled = installed && !busy && command.isNotBlank(), onClick = { operation = scope.launch {
        busy = true
        try { status = runtime.execute(command) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { status = "Команда остановлена: ${e.message?.take(300)}" }
        finally { busy = false }
    } }) { Text("Выполнить команду") }
    TextButton(enabled = hasFiles && !busy, onClick = { removing = true }) { Text("Удалить окружение и его файлы") }
    if (busy) {
        LinearProgressIndicator()
        TextButton(onClick = { operation?.cancel(); status = "Операция отменена" }) { Text("Отменить") }
    }
    if (status.isNotEmpty()) Text(status.take(12000), style = MaterialTheme.typography.bodySmall)
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text("Удалить Alpine?") },
        text = { Text("Все файлы и пакеты общего окружения будут удалены. Чаты сохранятся.") },
        confirmButton = { TextButton(onClick = { removing = false; operation = scope.launch {
            busy = true
            try { runtime.remove(); status = "Окружение удалено" }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { status = "Не удалось полностью удалить окружение" }
            finally { busy = false; installed = runtime.installed(); hasFiles = runtime.hasFiles() }
        } }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("Отмена") } })
    Text("Лицензии PRoot (GPL v2+) и talloc (LGPL v3+), закреплённые исходники и сборка: см. docs/github-and-alpine.md в репозитории Ilmar. Соответствующие исходники публикуются вместе с APK.")
}
