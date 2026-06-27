package com.selxo.rougo.windows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent as AwtKeyEvent
import java.awt.event.MouseEvent
import java.util.UUID

private const val VK_BROWSER_BACK = 0xA6

@Composable
fun CrashReportDialog() {
    val clipboard = LocalClipboardManager.current
    var crash by remember { mutableStateOf(CrashReporter.readLastCrash()) }
    val scrollState = rememberScrollState()
    val report = crash ?: return
    AlertDialog(
        onDismissRequest = {
            CrashReporter.clearLastCrash()
            crash = null
        },
        title = { Text("Rougo crashed last time") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(scrollState)) {
                Text("The crash report was saved so this can be debugged.", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(report.take(4000), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        },
        confirmButton = {
            Button(onClick = {
                clipboard.setText(AnnotatedString(report))
                CrashReporter.clearLastCrash()
                crash = null
            }) { Text("Copy") }
        },
        dismissButton = {
            OutlinedButton(onClick = {
                CrashReporter.clearLastCrash()
                crash = null
            }) { Text("Done") }
        }
    )
}

@Composable
fun UpdateNotificationDialog() {
    val prefs = Prefs.app
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val lastChecked = prefs.getLong("last_update_check", 0L)
        if (System.currentTimeMillis() - lastChecked > 3_600_000L) {
            val info = checkForUpdates()
            if (info != null && isUpdateAvailable(info, APP_VERSION)) {
                updateInfo = info
                showDialog = true
                DesktopNotifier.info("Update available", info.tagName)
            }
            prefs.putLong("last_update_check", System.currentTimeMillis())
        }
    }

    val info = updateInfo
    if (showDialog && info != null) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Update Available (${info.tagName})", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("A new version of Rougo is available. Update now to access new features and bug fixes.")
                    if (info.body.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(info.body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showDialog = false
                    openUpdateDownload(info)
                }) { Text("Update Now") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Later") }
            }
        )
    }
}

@Composable
fun MainAppFlow(
    sharedUrl: String?,
    onSharedUrlProcessed: () -> Unit,
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    accentColor: String,
    onAccentColorChanged: (String) -> Unit,
    systemDark: Boolean
) {
    val libraryManager = remember { LibraryManager() }
    var currentScreen by remember { mutableStateOf(AppScreen.Library) }
    var items by remember { mutableStateOf(libraryManager.getItems()) }
    var selectedItem by remember { mutableStateOf<LibraryItem?>(null) }
    var pendingYoutubeUrl by remember { mutableStateOf<String?>(null) }
    var collapsedFolderIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var playerCommandSerial by remember { mutableIntStateOf(0) }
    var playerCommandEvent by remember { mutableStateOf<DesktopPlayerCommandEvent?>(null) }

    fun refresh() {
        items = libraryManager.getItems()
    }

    fun navigateBack(): Boolean {
        return when (currentScreen) {
            AppScreen.Player -> {
                selectedItem = null
                playerCommandEvent = null
                refresh()
                currentScreen = AppScreen.Library
                true
            }
            AppScreen.Settings -> {
                currentScreen = AppScreen.Library
                true
            }
            AppScreen.DictionarySettings -> {
                currentScreen = AppScreen.Settings
                true
            }
            AppScreen.YoutubeBrowser -> {
                currentScreen = AppScreen.Library
                true
            }
            AppScreen.Library -> false
        }
    }

    fun sendPlayerCommand(command: DesktopPlayerCommand) {
        if (currentScreen != AppScreen.Player) return
        playerCommandSerial += 1
        playerCommandEvent = DesktopPlayerCommandEvent(playerCommandSerial, command)
    }

    fun handleDesktopKey(event: AwtKeyEvent): Boolean {
        if (event.id != AwtKeyEvent.KEY_PRESSED) return false
        val altBack = event.isAltDown && event.keyCode == AwtKeyEvent.VK_LEFT
        val browserBack = event.keyCode == VK_BROWSER_BACK
        if (event.keyCode == AwtKeyEvent.VK_ESCAPE || altBack || browserBack) {
            return navigateBack()
        }
        if (event.isControlDown) {
            when (event.keyCode) {
                AwtKeyEvent.VK_1 -> {
                    playerCommandEvent = null
                    selectedItem = null
                    refresh()
                    currentScreen = AppScreen.Library
                    return true
                }
                AwtKeyEvent.VK_2 -> {
                    playerCommandEvent = null
                    currentScreen = AppScreen.YoutubeBrowser
                    return true
                }
                AwtKeyEvent.VK_3 -> {
                    playerCommandEvent = null
                    currentScreen = AppScreen.Settings
                    return true
                }
            }
        }
        if (currentScreen == AppScreen.Player) {
            when (event.keyCode) {
                AwtKeyEvent.VK_SPACE, AwtKeyEvent.VK_K -> sendPlayerCommand(DesktopPlayerCommand.TogglePlayPause)
                AwtKeyEvent.VK_LEFT, AwtKeyEvent.VK_J -> sendPlayerCommand(DesktopPlayerCommand.SeekBackward)
                AwtKeyEvent.VK_RIGHT, AwtKeyEvent.VK_L -> sendPlayerCommand(DesktopPlayerCommand.SeekForward)
                AwtKeyEvent.VK_S -> sendPlayerCommand(DesktopPlayerCommand.Stop)
                AwtKeyEvent.VK_HOME -> sendPlayerCommand(DesktopPlayerCommand.SeekStart)
                AwtKeyEvent.VK_END -> sendPlayerCommand(DesktopPlayerCommand.SeekEnd)
                else -> return false
            }
            return true
        }
        return false
    }

    DisposableEffect(currentScreen) {
        val listener = AWTEventListener { event ->
            when (event) {
                is AwtKeyEvent -> {
                    if (event.id == AwtKeyEvent.KEY_PRESSED) {
                        if (handleDesktopKey(event)) event.consume()
                    }
                }
                is MouseEvent -> {
                    if (event.id == MouseEvent.MOUSE_PRESSED && event.button == 4) {
                        if (navigateBack()) event.consume()
                    }
                }
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK)
        onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
    }

    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null) {
            pendingYoutubeUrl = sharedUrl
            onSharedUrlProcessed()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (currentScreen) {
            AppScreen.Library -> LibraryScreen(
                items = items,
                onRefresh = { refresh() },
                onItemClick = {
                    selectedItem = it
                    playerCommandEvent = null
                    currentScreen = AppScreen.Player
                },
                onDelete = {
                    libraryManager.deleteItem(it.id)
                    refresh()
                },
                onOpenSettings = { currentScreen = AppScreen.Settings },
                onOpenYoutubeBrowser = { currentScreen = AppScreen.YoutubeBrowser },
                onAddLink = { pendingYoutubeUrl = it },
                collapsedFolderIds = collapsedFolderIds,
                onCollapsedFolderIdsChanged = { collapsedFolderIds = it }
            )

            AppScreen.Player -> selectedItem?.let { item ->
                PlayerScreen(item, playerCommandEvent.takeIf { currentScreen == AppScreen.Player }) { updated ->
                    libraryManager.saveItem(updated)
                    refresh()
                    selectedItem = null
                    playerCommandEvent = null
                    currentScreen = AppScreen.Library
                }
            } ?: run { currentScreen = AppScreen.Library }

            AppScreen.Settings -> SettingsScreen(
                onBack = { currentScreen = AppScreen.Library },
                onNavigateToDictionaries = { currentScreen = AppScreen.DictionarySettings },
                themeMode = themeMode,
                onThemeModeChanged = onThemeModeChanged,
                accentColor = accentColor,
                onAccentColorChanged = onAccentColorChanged,
                systemDark = systemDark
            )

            AppScreen.DictionarySettings -> DictionarySettingsScreen(onBack = { currentScreen = AppScreen.Settings })

            AppScreen.YoutubeBrowser -> YouTubeBrowserScreen(
                onBack = { currentScreen = AppScreen.Library },
                onOpenYoutubeUrl = {
                    pendingYoutubeUrl = it
                    currentScreen = AppScreen.Library
                }
            )
        }
    }

    pendingYoutubeUrl?.let { url ->
        if (isYoutubePlaylistUrl(url)) {
            PlaylistImportDialog(
                url = url,
                onDismiss = { pendingYoutubeUrl = null },
                onComplete = { importedItems ->
                    libraryManager.saveItems(importedItems)
                    refresh()
                    pendingYoutubeUrl = null
                    currentScreen = AppScreen.Library
                }
            )
        } else {
            YtStreamDialog(
                url = url,
                onDismiss = { pendingYoutubeUrl = null },
                onComplete = { item ->
                    libraryManager.saveItem(item)
                    refresh()
                    selectedItem = item
                    pendingYoutubeUrl = null
                    currentScreen = AppScreen.Player
                }
            )
        }
    }
}

@Composable
fun PlaylistImportDialog(url: String, onDismiss: () -> Unit, onComplete: (List<LibraryItem>) -> Unit) {
    var status by remember(url) { mutableStateOf("Importing playlist...") }
    var isProcessing by remember(url) { mutableStateOf(true) }

    LaunchedEffect(url) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val playlist = fetchYoutubePlaylistImportData(url) ?: error("Playlist import failed.")
                val plan = buildPlaylistImportPlan(
                    playlistTitle = playlist.title,
                    playlistUrl = url,
                    entries = playlist.entries,
                    nextId = { UUID.randomUUID().toString() }
                )
                listOf(plan.group) + plan.children
            }
        }
        val importedItems = result.getOrElse { t ->
            status = "Failed: ${t.localizedMessage.orEmpty()}"
            emptyList()
        }
        if (importedItems.isNotEmpty()) {
            onComplete(importedItems)
        } else {
            isProcessing = false
            delay(3000)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        title = { Text("Import Playlist", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                if (isProcessing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            if (isProcessing) {
                Button(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun YtStreamDialog(url: String, onDismiss: () -> Unit, onComplete: (LibraryItem) -> Unit) {
    val scope = rememberCoroutineScope()
    val prefs = Prefs.app
    val sourceLabel = remember(url) { streamSourceLabel(url) }
    val isYoutubeSource = remember(url) { isYoutubeUrl(url) }
    val downloadBeforePlayback = remember(url) { isBilibiliUrl(url) || isNiconicoUrl(url) }
    var status by remember(url) { mutableStateOf("Fetching $sourceLabel stream...") }
    var setupData by remember(url) { mutableStateOf<YoutubeSetupData?>(null) }
    var selectedFormat by remember(url) { mutableStateOf<YoutubeStreamFormat?>(null) }
    var selectedSubtitle by remember(url) { mutableStateOf<YoutubeSubtitleChoice?>(null) }
    var isBusy by remember(url) { mutableStateOf(true) }
    var fastOpenAttempted by remember(url) { mutableStateOf(false) }

    fun completeWith(format: YoutubeStreamFormat, subtitlePath: String?) {
        val data = setupData ?: return
        onComplete(createYoutubeLibraryItem(data, format, subtitlePath, url))
    }

    LaunchedEffect(url) {
        MediaTools.ensureReady { status = it.ifBlank { status } }
        val preferred = prefs.getString(PREF_YOUTUBE_RESOLUTION, DEFAULT_YOUTUBE_RESOLUTION) ?: DEFAULT_YOUTUBE_RESOLUTION
        val autoSubs = prefs.getBoolean(PREF_YOUTUBE_AUTO_SUBTITLES, true)
        val subtitleLang = prefs.getString(PREF_YOUTUBE_SUBTITLE_LANGUAGE, DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE) ?: DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE

        if (downloadBeforePlayback) {
            isBusy = true
            status = "Downloading $sourceLabel video..."
            val downloadedItem = withContext(Dispatchers.IO) { downloadVideoLinkToLibraryItem(url) { status = it } }
            if (downloadedItem != null) {
                onComplete(downloadedItem)
            } else {
                status = "$sourceLabel download failed."
                delay(3000)
                onDismiss()
            }
            return@LaunchedEffect
        }

        if (preferred != YOUTUBE_RESOLUTION_ASK && !fastOpenAttempted && isYoutubeSource) {
            fastOpenAttempted = true
            val fastItem = withContext(Dispatchers.IO) {
                fetchFastYoutubeStream(url, preferred)?.let { stream -> createFastYoutubeLibraryItem(stream, url) }
            }
            if (fastItem != null) {
                onComplete(fastItem)
                return@LaunchedEffect
            }
            status = "Preferred quality unavailable. Pick another format."
        }

        setupData = withContext(Dispatchers.IO) { runCatching { fetchYoutubeSetupData(url) }.getOrNull() }
        selectedSubtitle = if (isYoutubeSource && autoSubs) {
            setupData?.subtitleChoices?.let { selectPreferredYoutubeSubtitle(it, subtitleLang) }
        } else {
            null
        }
        if (preferred != YOUTUBE_RESOLUTION_ASK) {
            val format = setupData?.formats?.let { selectPreferredYoutubeFormat(it, preferred) }
            if (format != null) {
                isBusy = true
                status = "Opening ${YOUTUBE_RESOLUTION_OPTIONS.firstOrNull { it.key == preferred }?.label ?: preferred}..."
                onComplete(createYoutubeLibraryItem(setupData!!, format, null, url))
                return@LaunchedEffect
            }
            status = "Preferred quality unavailable. Pick another format."
        }
        selectedFormat = null
        isBusy = false
        if (status.startsWith("Fetching")) status = ""
    }

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = { Text("${streamSourceLabel(url)} Setup") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (isBusy) {
                    Text(status)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    return@Column
                }
                val data = setupData
                if (data == null) {
                    Text("Failed: could not fetch stream data.")
                    return@Column
                }
                Text(data.title, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                if (isYoutubeSource) {
                    Text("1. Select Subtitles (Optional):", fontWeight = FontWeight.Medium)
                    if (data.subtitleChoices.isEmpty()) {
                        Text("No captions found", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    } else {
                        LazyColumn(Modifier.heightIn(max = 150.dp)) {
                            items(data.subtitleChoices) { choice ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        selectedSubtitle = if (selectedSubtitle == choice) null else choice
                                    }.padding(vertical = 4.dp)
                                ) {
                                    RadioButton(selected = selectedSubtitle == choice, onClick = null)
                                    Text(choice.label, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Text(if (isYoutubeSource) "2. Select Quality:" else "Select Quality:", fontWeight = FontWeight.Medium)
                LazyColumn(Modifier.height(190.dp)) {
                    val formats = data.formats
                        .filter { ((it.vcodec != "none" && it.acodec != "none") || (it.vcodec == "none" && it.acodec != "none")) && it.bestPlaybackUrlForUi() != null }
                        .distinctBy { it.formatId ?: "${it.height}-${it.ext}-${it.vcodec}-${it.acodec}" }
                        .sortedWith(
                            compareByDescending<YoutubeStreamFormat> { if (it.vcodec == "none") 0 else it.height }
                                .thenByDescending { if (it.isVlcFriendlyVideoFormat() || it.isVlcFriendlyAudioFormat()) 1 else 0 }
                                .thenByDescending { it.tbr }
                        )
                    items(formats) { format ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedFormat = format
                                isBusy = true
                                val subtitleChoice = selectedSubtitle
                                status = if (subtitleChoice != null) "Fetching subtitles..." else "Opening stream..."
                                scope.launch {
                                    val subtitlePath = withContext(Dispatchers.IO) {
                                        subtitleChoice?.let { downloadYoutubeSubtitle(url, it.languageCode, it.isAutoGenerated) }
                                    }
                                    completeWith(format, subtitlePath)
                                }
                            }.padding(vertical = 4.dp)
                        ) {
                            RadioButton(selected = selectedFormat == format, onClick = null)
                            Text(formatLabel(format), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (setupData == null || isBusy) {
                Button(onClick = onDismiss) { Text("Cancel") }
            }
        },
        dismissButton = {
            if (!isBusy && setupData != null) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

private fun formatLabel(format: YoutubeStreamFormat): String {
    val isAudioOnly = format.vcodec == "none"
    val resolutionText = if (isAudioOnly) {
        "Audio Only"
    } else {
        if (format.height > 0) "${format.height}p" else format.formatNote ?: "Standard"
    }
    return "$resolutionText - ${format.ext.orEmpty().ifBlank { "stream" }}"
}

private fun YoutubeStreamFormat.bestPlaybackUrlForUi(): String? =
    url?.takeIf { it.startsWith("http", ignoreCase = true) }
        ?: manifestUrl?.takeIf { it.startsWith("http", ignoreCase = true) }
