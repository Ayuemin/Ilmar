package com.ayuemin.ilmar.browser

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun BrowserHost(onResumeTask: (Long) -> Unit, screenAllowed: Boolean = true) {
    val state by BrowserRuntime.state.collectAsState()
    var adapter by remember { mutableStateOf<AndroidBrowserPage?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val visible = state.visible && screenAllowed
    BackHandler(visible) { BrowserRuntime.hide() }
    // Keep a stable viewport and instance. Move the entire hidden hit area off-screen.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().offset(x = if (visible) 0.dp else maxWidth + 16.dp)) {
            Box(Modifier.fillMaxWidth().height(56.dp)) {
                if (visible) Surface { Row(Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(state.url, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    if (state.waitingUser) TextButton(onClick = { state.chat?.let(onResumeTask) }) { Text("Готово, вернуть модели") }
                    TextButton(onClick = { BrowserRuntime.hide() }) { Text("В чат") }
                } }
            }
            AndroidView(factory = { context -> WebView(context).also { view ->
                webView = view
                adapter = AndroidBrowserPage(view).also { BrowserRuntime.attach(it) }
            } }, update = { view ->
                BrowserViewVisibility.apply(view, visible)
            }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
    DisposableEffect(Unit) { onDispose {
        adapter?.let(BrowserRuntime::detach); webView?.destroy()
    } }
}
