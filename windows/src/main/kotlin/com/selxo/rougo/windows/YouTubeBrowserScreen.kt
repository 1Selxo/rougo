package com.selxo.rougo.windows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontWeight
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import netscape.javascript.JSObject
import java.awt.BorderLayout
import javax.swing.JPanel

private class DesktopYoutubeBrowserBridge(
    private val onOpenYoutubeUrl: (String) -> Unit
) {
    fun openVideo(url: String?) {
        openYoutubeUrl(url)
    }

    fun openYoutubeUrl(url: String?) {
        val nextUrl = url?.takeIf { it.isNotBlank() } ?: return
        onOpenYoutubeUrl(nextUrl)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeBrowserScreen(
    onBack: () -> Unit,
    onOpenYoutubeUrl: (String) -> Unit
) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    fun openYoutubeUrlIfSupported(rawUrl: String?): Boolean {
        val youtubeUrl = rawUrl?.let { youtubeBrowserOpenableUrl(it) } ?: return false
        onOpenYoutubeUrl(youtubeUrl)
        Platform.runLater { browser?.engine?.load("about:blank") }
        return true
    }

    fun installYoutubeBridge(engine: WebEngine?) {
        if (engine == null) return
        runCatching {
            val window = engine.executeScript("window") as JSObject
            window.setMember("RougoYoutube", DesktopYoutubeBrowserBridge { url -> openYoutubeUrlIfSupported(url) })
            engine.executeScript(youtubeBrowserInterceptScript())
        }.onFailure {
            CrashReporter.recordHandled("YouTubeBrowserScreen.installBridge", it)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Platform.runLater { browser?.engine?.load("about:blank") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("YouTube", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        val view = browser
                        if (view?.engine?.history?.currentIndex ?: -1 > 0) {
                            Platform.runLater { view?.engine?.history?.go(-1) }
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { Platform.runLater { browser?.engine?.load("https://m.youtube.com/") } }) {
                        Icon(Icons.Default.Home, contentDescription = "Home")
                    }
                    IconButton(onClick = {
                        Platform.runLater {
                            val engine = browser?.engine ?: return@runLater
                            if (isLoading) engine.load("about:blank") else engine.reload()
                        }
                    }) {
                        Icon(if (isLoading) Icons.Default.Close else Icons.Default.Refresh, contentDescription = if (isLoading) "Stop" else "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            SwingPanel(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    JPanel(BorderLayout()).apply {
                        val panel = JFXPanel()
                        add(panel, BorderLayout.CENTER)
                        Platform.runLater {
                            val webView = WebView()
                            browser = webView
                            val engine = webView.engine
                            engine.isJavaScriptEnabled = true
                            engine.locationProperty().addListener { _, _, newLocation ->
                                canGoBack = engine.history.currentIndex > 0
                                if (openYoutubeUrlIfSupported(newLocation)) return@addListener
                            }
                            engine.loadWorker.runningProperty().addListener { _, _, running ->
                                isLoading = running
                                canGoBack = engine.history.currentIndex > 0
                                if (!running) installYoutubeBridge(engine)
                            }
                            panel.scene = Scene(webView)
                            engine.load("https://m.youtube.com/")
                        }
                    }
                }
            )
            if (isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
