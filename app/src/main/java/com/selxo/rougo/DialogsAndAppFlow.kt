package com.selxo.rougo
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.FileProvider
import java.net.HttpURLConnection
import java.net.URL
import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Size
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.selxo.rougo.dictionary.DeinflectorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.system.exitProcess

// --- YT-DLP IMPORTS ---
import com.yausername.ffmpeg.FFmpeg
import com.yausername.ffmpeg.execute
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest

// --- IMPORT ALIASES ---
import android.media.MediaPlayer as AndroidMediaPlayer
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media as VLCMedia
import org.videolan.libvlc.MediaPlayer as VLCMediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var crashText by remember { mutableStateOf(CrashReporter.readLastCrash(context)) }
    val scrollState = rememberScrollState()

    if (crashText != null) {
        AlertDialog(
            onDismissRequest = {
                CrashReporter.clearLastCrash(context)
                crashText = null
            },
            title = { Text("朗語 crashed last time", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(scrollState)) {
                    Text(
                        "The crash report was saved so this can be debugged.",
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
                    copyTextToClipboard(context, "朗語 crash report", crashText.orEmpty())
                    Toast.makeText(context, "Crash report copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = {
                    CrashReporter.clearLastCrash(context)
                    crashText = null
                }) { Text("Clear") }
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
            title = { Text("Update Available (${updateInfo?.tagName})", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("A new version of 朗語 is available. Update now to access new features and bug fixes.")
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
                }) { Text("Update Now") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDialog = false
                }) { Text("Later") }
            }
        )
    }
}
@Composable
fun HelpDialog(showDialog: Boolean, onDismiss: () -> Unit) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Welcome to 朗語", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add local audio/video from the Library, or paste/share a video link to stream or download it.")
                    Text("Use headphones for shadowing so the microphone captures your voice instead of the source audio.")
                    Text("Install multiple pitch and dictionary sources from Settings > Dictionaries for better lookups.")
                    Text("This is built for listening and shadowing, not an Anki-mining workflow.")
                    Text("Record short segments, compare the waveforms, then repeat the segment until the rhythm feels natural.")
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) { Text("Done") }
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

    val pendingVideoUrl = sharedUrl ?: manualVideoUrl
    if (pendingVideoUrl != null) {
        YtStreamDialog(
            url = pendingVideoUrl,
            onDismiss = {
                if (sharedUrl != null) onSharedUrlProcessed() else manualVideoUrl = null
            },
            onComplete = { newItem ->
                libraryManager.saveItem(newItem)
                libraryItems = libraryManager.getItems()
                if (sharedUrl != null) onSharedUrlProcessed() else manualVideoUrl = null
                activeItem = newItem
                currentScreen = AppScreen.Player
            }
        )
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
            onAddLink = { url -> manualVideoUrl = url }
        )
    } else if (currentScreen == AppScreen.YoutubeBrowser) {
        YouTubeBrowserScreen(
            onBack = { currentScreen = AppScreen.Library },
            onOpenVideo = { url -> manualVideoUrl = url }
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
fun YtStreamDialog(url: String, onDismiss: () -> Unit, onComplete: (LibraryItem) -> Unit) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val sourceLabel = remember(url) { streamSourceLabel(url) }
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
    var status by remember { mutableStateOf("Fetching $sourceLabel stream...") }
    var setupData by remember { mutableStateOf<YoutubeSetupData?>(null) }
    var subtitleChoices by remember { mutableStateOf<List<YoutubeSubtitleChoice>>(emptyList()) }
    var selectedFormat by remember { mutableStateOf<YoutubeStreamFormat?>(null) }
    var selectedSubtitleKey by remember { mutableStateOf<String?>(null) }
    var isAutoSub by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        try {
            if (downloadBeforePlayback) {
                isProcessing = true
                status = "Downloading $sourceLabel video..."
                val downloadedItem = withContext(Dispatchers.IO) {
                    downloadVideoLinkToLibraryItem(context, url)
                }
                if (downloadedItem != null) {
                    onComplete(downloadedItem)
                } else {
                    status = "$sourceLabel download failed."
                    delay(3000)
                    onDismiss()
                }
                return@LaunchedEffect
            }

            if (preferredResolution != YOUTUBE_RESOLUTION_ASK) {
                isProcessing = true
                status = "Opening ${youtubeResolutionLabel(preferredResolution)}..."
                val fastItem = withContext(Dispatchers.IO) {
                    fetchFastYoutubeStream(context, url, preferredResolution)
                        ?.let { createFastYoutubeLibraryItem(it, url) }
                }
                if (fastItem != null) {
                    onComplete(fastItem)
                    return@LaunchedEffect
                }
                status = "Fetching $sourceLabel stream..."
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
                    status = "Opening ${youtubeResolutionLabel(preferredResolution)}..."
                    onComplete(createYoutubeLibraryItem(context, fetchedSetupData, format, null, url))
                } else {
                    status = "Preferred quality unavailable. Pick another format."
                    isProcessing = false
                }
            }
        } catch (e: Exception) {
            status = "Failed: ${e.localizedMessage}"
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
            status = if (selectedSubtitleLanguage != null) "Fetching subtitles..." else "Opening stream..."

            val subtitleUri = resolveSelectedSubtitle()

            if (selectedSubtitleLanguage != null && subtitleUri == null) {
                Toast.makeText(context, "Subtitle download failed; opening video without subtitles.", Toast.LENGTH_SHORT).show()
            }
            openSelectedFormat(format, subtitleUri)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isProcessing && setupData == null) onDismiss() },
        title = { Text("$sourceLabel Setup", fontWeight = FontWeight.Bold) },
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
                        Text("1. Select Subtitles (Optional):", color = Color.LightGray, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))

                        if (subtitleChoices.isEmpty()) {
                            Text("No captions found", color = Color.Gray, fontSize = 13.sp)
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

                    Text(if (isYoutubeSource) "2. Select Quality:" else "1. Select Quality:", color = Color.LightGray, fontSize = 14.sp)
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
                            val resolutionText = if (isAudioOnly) "Audio Only" else {
                                if (format.height > 0) "${format.height}p" else format.formatNote ?: "Standard"
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selectedFormat = format },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (isAudioOnly) Icons.Default.Audiotrack else Icons.Default.HighQuality, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "$resolutionText - ${format.ext}",
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
                Button(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
