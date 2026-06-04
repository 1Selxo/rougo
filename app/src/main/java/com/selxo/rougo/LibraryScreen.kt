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
private fun LibraryControlsCollapseHandle(
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse library controls" else "Expand library controls",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp).size(18.dp)
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    items: List<LibraryItem>,
    onRefresh: () -> Unit,
    onItemClick: (LibraryItem) -> Unit,
    onDelete: (LibraryItem) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenYoutubeBrowser: () -> Unit,
    onAddLink: (String) -> Unit
) {
    val context = LocalContext.current
    val importScope = rememberCoroutineScope()
    val libraryManager = remember { LibraryManager(context) }

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingMediaUri by remember { mutableStateOf<Uri?>(null) }
    var pendingTitle by remember { mutableStateOf("") }
    var isVideoType by remember { mutableStateOf(false) }
    var isImportingMedia by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }
    var sortMode by remember { mutableStateOf("Recent") }
    var showSortMenu by remember { mutableStateOf(false) }
    var attemptedMetadataRefresh by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var linkText by remember { mutableStateOf("") }
    var isDownloadingLink by remember { mutableStateOf(false) }
    var pendingDeleteItem by remember { mutableStateOf<LibraryItem?>(null) }
    var libraryControlsExpanded by remember { mutableStateOf(true) }
    val downloadStates = remember { mutableStateMapOf<String, LibraryDownloadState>() }

    val filteredItems = remember(items, searchQuery, selectedFilter, sortMode) {
        val query = searchQuery.trim().lowercase(Locale.US)
        val base = items.filter { item ->
            val matchesQuery = query.isEmpty() || item.title.lowercase(Locale.US).contains(query)
            val matchesFilter = when (selectedFilter) {
                "Audio" -> !item.isVideo
                "Video" -> item.isVideo
                "YouTube" -> item.sourceUrl != null
                "Local" -> item.sourceUrl == null
                else -> true
            }
            matchesQuery && matchesFilter
        }

        when (sortMode) {
            "Title" -> base.sortedBy { it.title.lowercase(Locale.US) }
            "Progress" -> base.sortedByDescending { if (it.duration > 0L) it.progress.toFloat() / it.duration.toFloat() else 0f }
            "Recordings" -> base.sortedByDescending { it.recordings.size }
            else -> base
        }
    }

    val totalRecordings = remember(items) { items.sumOf { it.recordings.size } }
    val inProgressCount = remember(items) { items.count { it.duration > 0L && it.progress > 0L } }

    LaunchedEffect(items) {
        if (attemptedMetadataRefresh) return@LaunchedEffect
        val candidates = items.filter { it.needsLocalMetadataRefresh() }
        if (candidates.isEmpty()) {
            attemptedMetadataRefresh = true
            return@LaunchedEffect
        }

        attemptedMetadataRefresh = true
        var changed = false
        withContext(Dispatchers.IO) {
            candidates.forEach { item ->
                val metadata = extractMediaMetadata(context, Uri.parse(item.mediaUri), item.id, item.isVideo)
                val updatedItem = mergeMetadataIntoItem(item, metadata)
                if (updatedItem != item) {
                    libraryManager.saveItem(updatedItem)
                    changed = true
                }
            }
        }
        if (changed) onRefresh()
    }

    fun savePendingMedia(subtitleUri: Uri?) {
        val mediaUri = pendingMediaUri ?: return
        val fallbackTitle = pendingTitle.ifBlank { getFileName(context, mediaUri) }
        val itemId = UUID.randomUUID().toString()

        isImportingMedia = true
        importScope.launch {
            val metadata = withContext(Dispatchers.IO) {
                extractMediaMetadata(context, mediaUri, itemId, isVideoType)
            }
            val baseItem = LibraryItem(
                id = itemId,
                title = fallbackTitle,
                mediaUri = mediaUri.toString(),
                subtitleUri = subtitleUri?.toString(),
                progress = 0L,
                duration = metadata.durationMs ?: 0L,
                isVideo = isVideoType
            )
            libraryManager.saveItem(mergeMetadataIntoItem(baseItem, metadata, fallbackTitle))

            isImportingMedia = false
            showAddDialog = false
            pendingMediaUri = null
            pendingTitle = ""
            onRefresh()
        }
    }

    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            pendingMediaUri = uri
            val mime = context.contentResolver.getType(uri) ?: ""
            isVideoType = mime.startsWith("video/")
            pendingTitle = getFileName(context, uri)
            showAddDialog = true
        }
    }

    val subtitleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            savePendingMedia(uri)
        }
    }

    fun requestDeleteItem(item: LibraryItem) {
        if (item.isVideo && item.recordings.size > 5) {
            pendingDeleteItem = item
        } else {
            onDelete(item)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(onClick = { mediaLauncher.launch(arrayOf("audio/*", "video/*")) }, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Library", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "${items.size} items | $inProgressCount started | $totalRecordings recordings",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(Icons.Default.HelpOutline, contentDescription = "Help", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                placeholder = { Text("Search library") },
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (libraryControlsExpanded) {
                OutlinedButton(
                    onClick = { showLinkDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Stream or download video link")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onOpenYoutubeBrowser,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Browse YouTube")
                }

                Spacer(modifier = Modifier.height(6.dp))
            }

            LibraryControlsCollapseHandle(
                expanded = libraryControlsExpanded,
                onClick = { libraryControlsExpanded = !libraryControlsExpanded }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(listOf("All", "Audio", "Video", "YouTube", "Local")) { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter) },
                            leadingIcon = if (selectedFilter == filter) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }

                Box {
                    TextButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(sortMode)
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        listOf("Recent", "Title", "Progress", "Recordings").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    sortMode = option
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (items.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No media yet", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Add local audio/video or share a YouTube link into 朗語.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp
                        )
                    }
                }
            } else if (filteredItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No matching items", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(filteredItems, key = { it.id }) { item ->
                        val hasLocalCopy = item.hasDownloadedLocalCopy()
                        val youtubeSourceUrl = item.sourceUrl?.takeIf { isYoutubeUrl(it) }
                        val canManageYoutubeDownload = youtubeSourceUrl != null

                        val downloadState = if (canManageYoutubeDownload) {
                            downloadStates[item.id]
                                ?: if (hasLocalCopy) LibraryDownloadState.Complete else LibraryDownloadState.Idle
                        } else {
                            LibraryDownloadState.Idle
                        }

                        LibraryCard(
                            item = item,
                            onClick = { onItemClick(item) },
                            onDelete = { requestDeleteItem(item) },
                            downloadState = downloadState,

                            onDeleteDownload = if (canManageYoutubeDownload && hasLocalCopy) {
                                {
                                    val updatedItem = deleteDownloadedLocalCopy(context, item)
                                    if (updatedItem != null) {
                                        libraryManager.saveItem(updatedItem)
                                        downloadStates.remove(item.id)
                                        onRefresh()
                                        Toast.makeText(context, "Deleted downloaded file.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Download file could not be removed.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                null
                            },

                            onDownload = if (canManageYoutubeDownload && !hasLocalCopy) {
                                {
                                    val sourceUrl = youtubeSourceUrl ?: return@LibraryCard

                                    if (downloadState != LibraryDownloadState.Loading) {
                                        downloadStates[item.id] = LibraryDownloadState.Loading
                                        importScope.launch {
                                            val downloadedItem = withContext(Dispatchers.IO) {
                                                downloadVideoLinkToLibraryItem(context, sourceUrl, item)
                                            }
                                            if (downloadedItem != null) {
                                                libraryManager.saveItem(downloadedItem)
                                                downloadStates[item.id] = LibraryDownloadState.Complete
                                                onRefresh()
                                                Toast.makeText(context, "Downloaded video.", Toast.LENGTH_SHORT).show()
                                            } else {
                                                downloadStates.remove(item.id)
                                                Toast.makeText(context, "Download failed.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }

    HelpDialog(showDialog = showHelpDialog, onDismiss = { showHelpDialog = false })

    pendingDeleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDeleteItem = null },
            title = { Text("Delete video?") },
            text = {
                Text(
                    "This video has ${item.recordings.size} recordings. Deleting it will also delete its downloaded video, recordings, subtitles, and cached artwork."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteItem = null
                        onDelete(item)
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDeleteItem = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { if (!isImportingMedia) showAddDialog = false },
            title = { Text("Add Subtitles?") },
            text = {
                Text(
                    if (isImportingMedia) {
                        "Reading embedded metadata and cover art..."
                    } else {
                        "Would you like to attach a subtitle file (.srt, .vtt, .ass) to '$pendingTitle'?"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = { subtitleLauncher.launch(arrayOf("*/*")) },
                    enabled = !isImportingMedia
                ) { Text("Select Subtitles") }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { savePendingMedia(null) },
                    enabled = !isImportingMedia
                ) { Text("Skip") }
            }
        )
    }

    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDownloadingLink) showLinkDialog = false },
            title = { Text("Add Video Link") },
            text = {
                Column {
                    OutlinedTextField(
                        value = linkText,
                        onValueChange = { linkText = it },
                        singleLine = true,
                        enabled = !isDownloadingLink,
                        label = { Text("Video URL") },
                        placeholder = { Text("https://...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isDownloadingLink) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isDownloadingLink && extractFirstUrl(linkText) != null,
                    onClick = {
                        val url = extractFirstUrl(linkText) ?: return@Button
                        showLinkDialog = false
                        linkText = ""
                        onAddLink(url)
                    }
                ) { Text("Stream") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDownloadingLink && extractFirstUrl(linkText) != null,
                    onClick = {
                        val url = extractFirstUrl(linkText) ?: return@TextButton
                        isDownloadingLink = true
                        importScope.launch {
                            val downloadedItem = withContext(Dispatchers.IO) {
                                downloadVideoLinkToLibraryItem(context, url)
                            }
                            if (downloadedItem != null) {
                                libraryManager.saveItem(downloadedItem)
                                onRefresh()
                                Toast.makeText(context, "Downloaded video.", Toast.LENGTH_SHORT).show()
                                showLinkDialog = false
                                linkText = ""
                            } else {
                                Toast.makeText(context, "Download failed.", Toast.LENGTH_LONG).show()
                            }
                            isDownloadingLink = false
                        }
                    }
                ) { Text("Download") }
            }
        )
    }
}
