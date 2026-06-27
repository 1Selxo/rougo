package com.selxo.rougo.windows

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main(args: Array<String>) {
    CrashReporter.install()
    configureVlcForProcess()

    val initialSharedUrl = args.asSequence()
        .flatMap { extractAllUrls(it).asSequence() }
        .firstOrNull { isSupportedVideoLink(it) }

    application {
        var sharedUrl by remember { mutableStateOf(initialSharedUrl) }
        val prefs = Prefs.app
        var themeMode by remember { mutableStateOf(readThemeModePreference(prefs)) }
        var accentColor by remember { mutableStateOf(prefs.getString(PREF_ACCENT_COLOR, "purple") ?: "purple") }
        val systemDark = isSystemInDarkTheme()

        LaunchedEffect(Unit) {
            launch(Dispatchers.IO) {
                DictionaryEngine.instance.loadDictionaries()
                if (DictionaryEngine.instance.getInstalledDictionaries().none { it.contains("JMdict", ignoreCase = true) }) {
                    DictionaryEngine.instance.downloadJmdict { }
                }
            }
            launch(Dispatchers.IO) {
                MediaTools.ensureReady { }
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            icon = painterResource("icons/rougo.png"),
            title = "朗語",
        ) {
            MaterialTheme(colorScheme = rougoColorScheme(themeMode, accentColor, systemDark)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        MainAppFlow(
                            sharedUrl = sharedUrl,
                            onSharedUrlProcessed = { sharedUrl = null },
                            themeMode = themeMode,
                            onThemeModeChanged = { mode ->
                                themeMode = mode
                                writeThemeModePreference(prefs, mode)
                            },
                            accentColor = accentColor,
                            onAccentColorChanged = { accent ->
                                accentColor = accent
                                prefs.putString(PREF_ACCENT_COLOR, accent)
                            },
                            systemDark = systemDark
                        )
                        CrashReportDialog()
                        UpdateNotificationDialog()
                    }
                }
            }
        }
    }
}

private fun configureVlcForProcess() {
    val vlcDir = MediaTools.vlcNativeDir() ?: return
    System.setProperty("jna.library.path", vlcDir.absolutePath)
    System.setProperty("VLC_PLUGIN_PATH", java.io.File(vlcDir, "plugins").absolutePath)
}
