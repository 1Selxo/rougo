package com.selxo.rougo

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var crashText by remember { mutableStateOf(CrashReporter.readLastCrash(context)) }
    val scrollState = rememberScrollState()
    val crashReportClipboardLabel = stringResource(R.string.dictionary_clipboard_crash_report_label)
    val crashReportCopiedToast = stringResource(R.string.crash_report_copied_toast)

    if (crashText != null) {
        AlertDialog(
            onDismissRequest = {
                CrashReporter.clearLastCrash(context)
                crashText = null
            },
            title = { Text(stringResource(R.string.crash_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(scrollState)) {
                    Text(
                        stringResource(R.string.crash_dialog_body),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        crashText.orEmpty().take(4000),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    copyTextToClipboard(context, crashReportClipboardLabel, crashText.orEmpty())
                    Toast.makeText(context, crashReportCopiedToast, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.common_copy)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    CrashReporter.clearLastCrash(context)
                    crashText = null
                }) { Text(stringResource(R.string.common_clear)) }
            }
        )
    }
}
private fun copyTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
}
@Composable
fun UpdateNotificationDialog() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val lastChecked = prefs.getLong("last_update_check", 0L)
        if (System.currentTimeMillis() - lastChecked > 3600000L) { // Check every hour
            val info = checkForUpdates()
            if (info != null) {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val currentVersion = pInfo.versionName
                if (isUpdateAvailable(info, currentVersion, pInfo.lastUpdateTime)) {
                    updateInfo = info
                    showDialog = true
                }
            }
            prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
        }
    }

    if (showDialog && updateInfo != null) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.update_available_title, updateInfo?.tagName.orEmpty()), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(stringResource(R.string.update_available_body))
                    if (updateInfo?.body?.isNotEmpty() == true) {
                        Spacer(Modifier.height(8.dp))
                        Text(updateInfo!!.body, fontSize = 12.sp, color = Color.Gray)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showDialog = false
                    downloadAndInstallUpdate(context, updateInfo!!.downloadUrl)
                }) { Text(stringResource(R.string.settings_update_now)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDialog = false
                }) { Text(stringResource(R.string.common_later)) }
            }
        )
    }
}
@Composable
fun HelpDialog(showDialog: Boolean, onDismiss: () -> Unit) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.welcome_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.welcome_body_add_media))
                    Text(stringResource(R.string.welcome_body_headphones))
                    Text(stringResource(R.string.welcome_body_dictionaries))
                    Text(stringResource(R.string.welcome_body_anki))
                    Text(stringResource(R.string.welcome_body_shadowing))
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
            }
        )
    }
}
enum class AppScreen { Library, Player, Settings, DictionarySettings, YoutubeBrowser }
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
    val context = LocalContext.current
    val libraryManager = remember { LibraryManager(context) }
    var currentScreen by remember { mutableStateOf(AppScreen.Library) }
    var activeItem by remember { mutableStateOf<LibraryItem?>(null) }
    var libraryItems by remember { mutableStateOf(libraryManager.getItems()) }
    var manualVideoUrl by remember { mutableStateOf<String?>(null) }
    var collapsedLibraryFolderIds by remember { mutableStateOf(emptySet<String>()) }

    val pendingVideoUrl = sharedUrl ?: manualVideoUrl
    fun clearPendingVideoUrl() {
        if (sharedUrl != null) onSharedUrlProcessed() else manualVideoUrl = null
    }
    if (pendingVideoUrl != null) {
        if (isYoutubePlaylistUrl(pendingVideoUrl)) {
            PlaylistImportDialog(
                url = pendingVideoUrl,
                onDismiss = { clearPendingVideoUrl() },
                onComplete = { importedItems ->
                    libraryManager.saveItems(importedItems)
                    libraryItems = libraryManager.getItems()
                    clearPendingVideoUrl()
                    currentScreen = AppScreen.Library
                }
            )
        } else {
            YtStreamDialog(
                url = pendingVideoUrl,
                onDismiss = { clearPendingVideoUrl() },
                onComplete = { newItem ->
                    libraryManager.saveItem(newItem)
                    libraryItems = libraryManager.getItems()
                    clearPendingVideoUrl()
                    activeItem = newItem
                    currentScreen = AppScreen.Player
                }
            )
        }
    }

    if (currentScreen == AppScreen.Library) {
        LibraryScreen(
            items = libraryItems,
            onRefresh = { libraryItems = libraryManager.getItems() },
            onItemClick = { item -> activeItem = item; currentScreen = AppScreen.Player },
            onDelete = { item ->
                libraryManager.deleteItem(item.id)
                libraryItems = libraryManager.getItems()
            },
            onOpenSettings = { currentScreen = AppScreen.Settings },
            onOpenYoutubeBrowser = { currentScreen = AppScreen.YoutubeBrowser },
            onAddLink = { url -> manualVideoUrl = url },
            collapsedFolderIds = collapsedLibraryFolderIds,
            onCollapsedFolderIdsChanged = { collapsedLibraryFolderIds = it }
        )
    } else if (currentScreen == AppScreen.YoutubeBrowser) {
        YouTubeBrowserScreen(
            onBack = { currentScreen = AppScreen.Library },
            onOpenYoutubeUrl = { url -> manualVideoUrl = url }
        )
    } else if (currentScreen == AppScreen.Settings) {
        BackHandler { currentScreen = AppScreen.Library }
        SettingsScreen(
            onBack = { currentScreen = AppScreen.Library },
            onNavigateToDictionaries = { currentScreen = AppScreen.DictionarySettings },
            themeMode = themeMode,
            onThemeModeChanged = onThemeModeChanged,
            accentColor = accentColor,
            onAccentColorChanged = onAccentColorChanged,
            systemDark = systemDark
        )
    } else if (currentScreen == AppScreen.DictionarySettings) {
        BackHandler { currentScreen = AppScreen.Settings }
        DictionarySettingsScreen(
            onBack = { currentScreen = AppScreen.Settings }
        )
    } else {
        activeItem?.let { item ->
            PlayerScreen(
                initialLibraryItem = item,
                onBack = { updatedItem ->
                    libraryManager.saveItem(updatedItem)
                    libraryItems = libraryManager.getItems()
                    currentScreen = AppScreen.Library
                    activeItem = null
                }
            )
        }
    }
}
@Composable
fun PlaylistImportDialog(url: String, onDismiss: () -> Unit, onComplete: (List<LibraryItem>) -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(context.getString(R.string.library_importing_playlist)) }
    var isProcessing by remember { mutableStateOf(true) }

    LaunchedEffect(url) {
        try {
            val importedItems = withContext(Dispatchers.IO) {
                val playlist = fetchYoutubePlaylistImportData(context, url)
                    ?: error(context.getString(R.string.library_playlist_import_failed_toast))
                val plan = buildPlaylistImportPlan(
                    playlistTitle = playlist.title,
                    playlistUrl = url,
                    entries = playlist.entries,
                    nextId = { java.util.UUID.randomUUID().toString() }
                )
                listOf(plan.group) + plan.children
            }
            onComplete(importedItems)
        } catch (e: Exception) {
            isProcessing = false
            status = context.getString(R.string.stream_failed_status, e.localizedMessage.orEmpty())
            delay(3000)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        title = { Text(stringResource(R.string.library_import_playlist_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                if (isProcessing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (isProcessing) {
                Button(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    )
}
@Composable
fun YtStreamDialog(url: String, onDismiss: () -> Unit, onComplete: (LibraryItem) -> Unit) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val sourceLabel = remember(url) { streamSourceLabel(context, url) }
    val isYoutubeSource = remember(url) { isYoutubeUrl(url) }
    val downloadBeforePlayback = remember(url) { isBilibiliUrl(url) || isNiconicoUrl(url) }
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val preferredResolution = remember {
        prefs.getString(PREF_YOUTUBE_RESOLUTION, DEFAULT_YOUTUBE_RESOLUTION) ?: DEFAULT_YOUTUBE_RESOLUTION
    }
    val shouldAutoDownloadSubtitles = remember {
        prefs.getBoolean(PREF_YOUTUBE_AUTO_SUBTITLES, true)
    }
    val preferredSubtitleLanguage = remember {
        prefs.getString(PREF_YOUTUBE_SUBTITLE_LANGUAGE, DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE) ?: DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE
    }
    var status by remember(sourceLabel) { mutableStateOf(context.getString(R.string.stream_fetching_status, sourceLabel)) }
    var setupData by remember { mutableStateOf<YoutubeSetupData?>(null) }
    var subtitleChoices by remember { mutableStateOf<List<YoutubeSubtitleChoice>>(emptyList()) }
    var selectedFormat by remember { mutableStateOf<YoutubeStreamFormat?>(null) }
    var selectedSubtitleKey by remember { mutableStateOf<String?>(null) }
    var isAutoSub by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        try {
            if (downloadBeforePlayback) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPlayerNotificationPermission(context)) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                isProcessing = true
                status = context.getString(R.string.stream_downloading_status, sourceLabel)
                val downloadedItem = withContext(Dispatchers.IO) {
                    downloadVideoLinkToLibraryItem(context, url)
                }
                if (downloadedItem != null) {
                    onComplete(downloadedItem)
                } else {
                    status = context.getString(R.string.stream_download_failed_status, sourceLabel)
                    delay(3000)
                    onDismiss()
                }
                return@LaunchedEffect
            }

            if (preferredResolution != YOUTUBE_RESOLUTION_ASK) {
                isProcessing = true
                status = context.getString(R.string.stream_opening_quality_status, youtubeResolutionLabel(context, preferredResolution))
                val fastItem = withContext(Dispatchers.IO) {
                    fetchFastYoutubeStream(context, url, preferredResolution)
                        ?.let { createFastYoutubeLibraryItem(it, url) }
                }
                if (fastItem != null) {
                    onComplete(fastItem)
                    return@LaunchedEffect
                }
                status = context.getString(R.string.stream_fetching_status, sourceLabel)
            }

            val fetchedSetupData = withContext(Dispatchers.IO) {
                fetchYoutubeSetupData(context, url)
            }
            setupData = fetchedSetupData
            subtitleChoices = if (isYoutubeSource) fetchedSetupData.subtitleChoices else emptyList()
            if (isYoutubeSource && shouldAutoDownloadSubtitles && selectedSubtitleKey == null) {
                selectPreferredYoutubeSubtitle(fetchedSetupData.subtitleChoices, preferredSubtitleLanguage)?.let { choice ->
                    selectedSubtitleKey = choice.languageCode
                    isAutoSub = choice.isAutoGenerated
                }
            }

            if (preferredResolution != YOUTUBE_RESOLUTION_ASK) {
                val format = selectPreferredYoutubeFormat(fetchedSetupData.formats, preferredResolution)
                if (format != null) {
                    isProcessing = true
                    status = context.getString(R.string.stream_opening_quality_status, youtubeResolutionLabel(context, preferredResolution))
                    onComplete(createYoutubeLibraryItem(context, fetchedSetupData, format, null, url))
                } else {
                    status = context.getString(R.string.stream_preferred_quality_unavailable)
                    isProcessing = false
                }
            }
        } catch (e: Exception) {
            status = context.getString(R.string.stream_failed_status, e.localizedMessage.orEmpty())
            delay(3000)
            onDismiss()
        }
    }

    fun openSelectedFormat(format: YoutubeStreamFormat, subtitleUri: String?) {
        uiScope.launch {
            onComplete(createYoutubeLibraryItem(context, setupData, format, subtitleUri, url))
        }
    }

    suspend fun resolveSelectedSubtitle(): String? {
        val selectedSubtitleLanguage = selectedSubtitleKey
        return if (isYoutubeSource && selectedSubtitleLanguage != null) {
            withContext(Dispatchers.IO) {
                downloadYoutubeSubtitle(context, url, selectedSubtitleLanguage, isAutoSub)
            }
        } else {
            null
        }
    }

    LaunchedEffect(selectedFormat) {
        val format = selectedFormat
        if (format != null) {
            isProcessing = true
            val selectedSubtitleLanguage = selectedSubtitleKey
            status = if (selectedSubtitleLanguage != null) {
                context.getString(R.string.stream_fetching_subtitles_status)
            } else {
                context.getString(R.string.stream_opening_status)
            }

            val subtitleUri = resolveSelectedSubtitle()

            if (selectedSubtitleLanguage != null && subtitleUri == null) {
                Toast.makeText(context, context.getString(R.string.stream_subtitle_download_failed_opening_toast), Toast.LENGTH_SHORT).show()
            }
            openSelectedFormat(format, subtitleUri)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isProcessing && setupData == null) onDismiss() },
        title = { Text(stringResource(R.string.stream_setup_title, sourceLabel), fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (setupData == null || isProcessing) {
                    Text(status, color = Color.LightGray, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary, trackColor = Color.DarkGray
                    )
                } else {
                    if (isYoutubeSource) {
                        Text(stringResource(R.string.stream_select_subtitles_step), color = Color.LightGray, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))

                        if (subtitleChoices.isEmpty()) {
                            Text(stringResource(R.string.stream_no_captions_found), color = Color.Gray, fontSize = 13.sp)
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                                items(subtitleChoices) { option ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedSubtitleKey = if (selectedSubtitleKey == option.languageCode && isAutoSub == option.isAutoGenerated) null else option.languageCode
                                                isAutoSub = option.isAutoGenerated
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = (selectedSubtitleKey == option.languageCode && isAutoSub == option.isAutoGenerated),
                                            onClick = null
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(option.label, color = Color.White, fontSize = 14.sp)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Text(
                        stringResource(if (isYoutubeSource) R.string.stream_select_quality_step_youtube else R.string.stream_select_quality_step_default),
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val formats = setupData?.formats?.filter {
                        ((it.vcodec != "none" && it.acodec != "none") || (it.vcodec == "none" && it.acodec != "none")) &&
                            (!it.url.isNullOrBlank() || !it.manifestUrl.isNullOrBlank())
                    }?.distinctBy { it.formatId ?: "${it.height}-${it.ext}-${it.vcodec}-${it.acodec}" }
                        ?.sortedWith(
                            compareByDescending<YoutubeStreamFormat> { if (it.vcodec == "none") 0 else it.height }
                                .thenByDescending { if (it.isVlcFriendlyVideoFormat() || it.isVlcFriendlyAudioFormat()) 1 else 0 }
                                .thenByDescending { it.tbr }
                        )
                        ?: emptyList()

                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(formats) { format ->
                            val isAudioOnly = format.vcodec == "none"
                            val resolutionText = if (isAudioOnly) stringResource(R.string.common_audio_only) else {
                                if (format.height > 0) "${format.height}p" else format.formatNote ?: stringResource(R.string.common_standard)
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selectedFormat = format },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (isAudioOnly) Icons.Default.Audiotrack else Icons.Default.HighQuality, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = stringResource(R.string.stream_format_label, resolutionText, format.ext.orEmpty()),
                                        fontWeight = FontWeight.Medium, color = Color.White, fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (setupData == null || isProcessing) {
                Button(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    )
}
