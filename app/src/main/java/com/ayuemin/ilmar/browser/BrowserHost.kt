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
internal fun BrowserHost() {
    val state by BrowserRuntime.state.collectAsState()
    var adapter by remember { mutableStateOf<AndroidBrowserPage?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    BackHandler(state.visible) { BrowserRuntime.hide() }
    // Keep an actual viewport for layout/snapshots even while the reader is hidden, as in Umnik.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(if (state.visible) Modifier.fillMaxSize() else Modifier.width(maxWidth).height(maxHeight).offset(x = maxWidth + 16.dp)) {
            Surface { Row(Modifier.fillMaxWidth().padding(8.dp)) {
                Text(state.url, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
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
