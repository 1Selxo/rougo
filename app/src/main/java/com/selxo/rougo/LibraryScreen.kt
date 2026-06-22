package com.selxo.rougo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            contentDescription = stringResource(if (expanded) R.string.library_collapse_controls else R.string.library_expand_controls),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp).size(18.dp)
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun LibraryPlaylistGroupCard(
    item: LibraryItem,
    childCount: Int,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.library_playlist_items_count, childCount), fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val deletedDownloadToast = stringResource(R.string.library_deleted_download_toast)
    val deleteDownloadFailedToast = stringResource(R.string.library_delete_download_failed_toast)
    val downloadedVideoToast = stringResource(R.string.library_downloaded_video_toast)
    val downloadFailedToast = stringResource(R.string.library_download_failed_toast)
    val filterOptions = remember {
        listOf(
            "All" to R.string.library_filter_all,
            "Audio" to R.string.library_filter_audio,
            "Video" to R.string.library_filter_video,
            "YouTube" to R.string.library_filter_youtube,
            "Local" to R.string.library_filter_local
        )
    }
    val sortOptions = remember {
        listOf(
            "Recent" to R.string.library_sort_recent,
            "Title" to R.string.library_sort_title,
            "Progress" to R.string.library_sort_progress,
            "Recordings" to R.string.library_sort_recordings
        )
    }

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

    fun requestDownloadNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPlayerNotificationPermission(context)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val displayRows = remember(items, searchQuery, selectedFilter, sortMode) {
        libraryDisplayRows(items, searchQuery, selectedFilter, sortMode)
    }

    val totalRecordings = remember(items) { items.sumOf { it.recordings.size } }
    val inProgressCount = remember(items) { items.count { it.duration > 0L && it.progress > 0L } }
    val mediaItemCount = remember(items) { libraryMediaItemCount(items) }

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
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.common_add), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(stringResource(R.string.library_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        stringResource(R.string.library_summary, mediaItemCount, inProgressCount, totalRecordings),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(Icons.Default.HelpOutline, contentDescription = stringResource(R.string.common_help), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.common_settings), tint = MaterialTheme.colorScheme.primary)
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
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.library_clear_search))
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
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
                    Text(stringResource(R.string.library_stream_or_download_link))
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onOpenYoutubeBrowser,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.library_browse_youtube))
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
                    items(filterOptions) { (filter, labelRes) ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(stringResource(labelRes)) },
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
                        Text(stringResource(sortOptions.firstOrNull { it.first == sortMode }?.second ?: R.string.library_sort_recent))
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        sortOptions.forEach { (option, labelRes) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(labelRes)) },
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
                        Text(stringResource(R.string.library_no_media_title), color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.library_no_media_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp
                        )
                    }
                }
            } else if (displayRows.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.library_no_matching_items), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(
                        displayRows,
                        key = { row ->
                            when (row) {
                                is LibraryDisplayRow.PlaylistGroup -> row.item.id
                                is LibraryDisplayRow.Media -> row.item.id
                            }
                        }
                    ) { row ->
                        if (row is LibraryDisplayRow.PlaylistGroup) {
                            LibraryPlaylistGroupCard(
                                item = row.item,
                                childCount = row.childCount,
                                onDelete = { requestDeleteItem(row.item) }
                            )
                            return@items
                        }
                        val item = (row as LibraryDisplayRow.Media).item
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
                            modifier = if (row.isPlaylistChild) Modifier.padding(start = 18.dp) else Modifier,

                            onDeleteDownload = if (canManageYoutubeDownload && hasLocalCopy) {
                                {
                                    val updatedItem = deleteDownloadedLocalCopy(context, item)
                                    if (updatedItem != null) {
                                        libraryManager.saveItem(updatedItem)
                                        downloadStates.remove(item.id)
                                        onRefresh()
                                        Toast.makeText(context, deletedDownloadToast, Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, deleteDownloadFailedToast, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                null
                            },

                            onDownload = if (canManageYoutubeDownload && !hasLocalCopy) {
                                {
                                    val sourceUrl = youtubeSourceUrl

                                    if (downloadState != LibraryDownloadState.Loading) {
                                        requestDownloadNotificationPermissionIfNeeded()
                                        downloadStates[item.id] = LibraryDownloadState.Loading
                                        importScope.launch {
                                            val downloadedItem = withContext(Dispatchers.IO) {
                                                downloadVideoLinkToLibraryItem(context, sourceUrl, item)
                                            }
                                            if (downloadedItem != null) {
                                                libraryManager.saveItem(downloadedItem)
                                                downloadStates[item.id] = LibraryDownloadState.Complete
                                                onRefresh()
                                                Toast.makeText(context, downloadedVideoToast, Toast.LENGTH_SHORT).show()
                                            } else {
                                                downloadStates.remove(item.id)
                                                Toast.makeText(context, downloadFailedToast, Toast.LENGTH_LONG).show()
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
            title = { Text(stringResource(R.string.library_delete_video_title)) },
            text = {
                Text(
                    stringResource(R.string.library_delete_video_body, item.recordings.size)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteItem = null
                        onDelete(item)
                    }
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDeleteItem = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { if (!isImportingMedia) showAddDialog = false },
            title = { Text(stringResource(R.string.library_add_subtitles_title)) },
            text = {
                Text(
                    if (isImportingMedia) {
                        stringResource(R.string.library_reading_metadata)
                    } else {
                        stringResource(R.string.library_add_subtitles_body, pendingTitle)
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = { subtitleLauncher.launch(arrayOf("*/*")) },
                    enabled = !isImportingMedia
                ) { Text(stringResource(R.string.library_select_subtitles)) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { savePendingMedia(null) },
                    enabled = !isImportingMedia
                ) { Text(stringResource(R.string.common_skip)) }
            }
        )
    }

    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDownloadingLink) showLinkDialog = false },
            title = { Text(stringResource(R.string.library_add_video_link_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = linkText,
                        onValueChange = { linkText = it },
                        singleLine = true,
                        enabled = !isDownloadingLink,
                        label = { Text(stringResource(R.string.library_video_url_label)) },
                        placeholder = { Text(stringResource(R.string.library_url_placeholder)) },
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
                ) { Text(stringResource(R.string.common_stream)) }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDownloadingLink && extractFirstUrl(linkText) != null,
                    onClick = {
                        val url = extractFirstUrl(linkText) ?: return@TextButton
                        requestDownloadNotificationPermissionIfNeeded()
                        isDownloadingLink = true
                        importScope.launch {
                            val downloadedItem = withContext(Dispatchers.IO) {
                                downloadVideoLinkToLibraryItem(context, url)
                            }
                            if (downloadedItem != null) {
                                libraryManager.saveItem(downloadedItem)
                                onRefresh()
                                Toast.makeText(context, downloadedVideoToast, Toast.LENGTH_SHORT).show()
                                showLinkDialog = false
                                linkText = ""
                            } else {
                                Toast.makeText(context, downloadFailedToast, Toast.LENGTH_LONG).show()
                            }
                            isDownloadingLink = false
                        }
                    }
                ) { Text(stringResource(R.string.common_download)) }
            }
        )
    }
}
