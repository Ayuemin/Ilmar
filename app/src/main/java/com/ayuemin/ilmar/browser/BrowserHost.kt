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
internal fun BrowserHost(onResumeTask: (Long) -> Unit) {
    val state by BrowserRuntime.state.collectAsState()
    var adapter by remember { mutableStateOf<AndroidBrowserPage?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    BackHandler(state.visible) { BrowserRuntime.hide() }
    // Keep WebView laid out for agent snapshots; MainActivity layers it below opaque chat while hidden.
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Surface { Row(Modifier.fillMaxWidth().padding(8.dp)) {
                Text(state.url, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                if (state.waitingUser) TextButton(onClick = { state.chat?.let(onResumeTask) }) { Text("Готово, вернуть модели") }
                TextButton(onClick = { BrowserRuntime.hide() }) { Text("В чат") }
            } }
            AndroidView(factory = { context -> WebView(context).also { view ->
                webView = view
                adapter = AndroidBrowserPage(view).also { BrowserRuntime.attach(it) }
            } }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
    DisposableEffect(Unit) { onDispose {
        adapter?.let(BrowserRuntime::detach); webView?.destroy()
    } }
}
