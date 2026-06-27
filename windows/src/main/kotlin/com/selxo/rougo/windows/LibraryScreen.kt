package com.selxo.rougo.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
private fun LibraryControlsCollapseHandle(expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "Collapse library controls" else "Expand library controls", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp).size(18.dp))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun LibraryPlaylistGroupCard(
    item: LibraryItem,
    childCount: Int,
    isExpanded: Boolean,
    canToggleExpansion: Boolean,
    onToggleExpanded: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val countLabel = if (item.playlistSourceUrl != null || item.itemKind == LibraryItemKind.Playlist) "$childCount videos" else "$childCount items"
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                AssistChip(onClick = {}, enabled = false, label = { Text(countLabel, fontSize = 11.sp) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, modifier = Modifier.size(14.dp)) })
            }
            if (canToggleExpansion) {
                IconButton(onClick = onToggleExpanded, modifier = Modifier.size(44.dp)) {
                    Icon(if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (isExpanded) "Collapse folder" else "Expand folder")
                }
            }
            IconButton(onClick = onRename, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Rename")
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
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
    onAddLink: (String) -> Unit,
    collapsedFolderIds: Set<String>,
    onCollapsedFolderIdsChanged: (Set<String>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val libraryManager = remember { LibraryManager() }
    val filterOptions = listOf("All", "Audio", "Video", "YouTube", "Local")
    val sortOptions = listOf("Recent", "Title", "Progress", "Recordings")
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingMediaFile by remember { mutableStateOf<File?>(null) }
    var pendingTitle by remember { mutableStateOf("") }
    var isVideoType by remember { mutableStateOf(false) }
    var isImportingMedia by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }
    var sortMode by remember { mutableStateOf("Recent") }
    var showSortMenu by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var linkText by remember { mutableStateOf("") }
    var isDownloadingLink by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderTitleText by remember { mutableStateOf("") }
    var renameFolderItem by remember { mutableStateOf<LibraryItem?>(null) }
    var renameFolderTitle by remember { mutableStateOf("") }
    var moveTargetItem by remember { mutableStateOf<LibraryItem?>(null) }
    var pendingDeleteItem by remember { mutableStateOf<LibraryItem?>(null) }
    var libraryControlsExpanded by remember { mutableStateOf(true) }
    val downloadStates = remember { mutableStateMapOf<String, LibraryDownloadState>() }

    val folderItems = remember(items) { items.filter { it.isFolderGroup() } }
    val folderIds = remember(folderItems) { folderItems.map { it.id }.toSet() }
    val displayRows = remember(items, searchQuery, selectedFilter, sortMode, collapsedFolderIds) {
        libraryDisplayRows(items, searchQuery, selectedFilter, sortMode, collapsedFolderIds)
    }
    val totalRecordings = remember(items) { items.sumOf { it.recordings.size } }
    val inProgressCount = remember(items) { items.count { it.duration > 0L && it.progress > 0L } }
    val mediaItemCount = remember(items) { libraryMediaItemCount(items) }

    LaunchedEffect(folderIds, collapsedFolderIds) {
        val pruned = collapsedFolderIds.intersect(folderIds)
        if (pruned != collapsedFolderIds) onCollapsedFolderIdsChanged(pruned)
    }

    fun savePendingMedia(subtitleFile: File?) {
        val mediaFile = pendingMediaFile ?: return
        val fallbackTitle = pendingTitle.ifBlank { mediaFile.name }
        val itemId = UUID.randomUUID().toString()
        isImportingMedia = true
        scope.launch {
            val subtitlePath = subtitleFile?.let { copyIntoAppSubtitles(it) }
            val metadata = withContext(Dispatchers.IO) { extractMediaMetadata(mediaFile.absolutePath, itemId, isVideoType) }
            val baseItem = LibraryItem(
                id = itemId,
                title = fallbackTitle,
                mediaUri = mediaFile.absolutePath,
                subtitleUri = subtitlePath,
                progress = 0L,
                duration = metadata.durationMs ?: 0L,
                isVideo = isVideoType
            )
            libraryManager.saveItem(mergeMetadataIntoItem(baseItem, metadata, fallbackTitle))
            isImportingMedia = false
            showAddDialog = false
            pendingMediaFile = null
            pendingTitle = ""
            onRefresh()
        }
    }

    fun createFolder() {
        val title = folderTitleText.trim()
        if (title.isBlank()) return
        libraryManager.saveItem(buildLibraryFolder(title) { UUID.randomUUID().toString() })
        folderTitleText = ""
        showCreateFolderDialog = false
        onRefresh()
    }

    fun requestDeleteItem(item: LibraryItem) {
        if (item.isVideo && item.recordings.size > 5) pendingDeleteItem = item else onDelete(item)
    }

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val file = openSingleFileDialog("Add Media", listOf(".mp3", ".m4a", ".wav", ".flac", ".ogg", ".opus", ".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4b"))
                if (file != null) {
                    pendingMediaFile = file
                    isVideoType = file.extension.lowercase() in setOf("mp4", "mkv", "webm", "mov", "avi", "m4v")
                    pendingTitle = file.nameWithoutExtension
                    showAddDialog = true
                }
            }, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Library", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    Text("$mediaItemCount items | $inProgressCount started | $totalRecordings recordings", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                },
                placeholder = { Text("Search library") },
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(10.dp))
            if (libraryControlsExpanded) {
                OutlinedButton(onClick = { showLinkDialog = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Link, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Stream or download video link")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenYoutubeBrowser, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Browse YouTube")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showCreateFolderDialog = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New folder")
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        items(filterOptions) { filter ->
                            FilterChip(selected = selectedFilter == filter, onClick = { selectedFilter = filter }, label = { Text(filter) }, leadingIcon = if (selectedFilter == filter) {
                                { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                            } else null)
                        }
                    }
                    Box {
                        TextButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(sortMode)
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            sortOptions.forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = {
                                    sortMode = option
                                    showSortMenu = false
                                })
                            }
                        }
                    }
                }
            }
            LibraryControlsCollapseHandle(libraryControlsExpanded) { libraryControlsExpanded = !libraryControlsExpanded }
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) {
                Card(Modifier.fillMaxWidth().padding(top = 48.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No media yet", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Add local audio/video or paste a video link into Rougo", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 13.sp)
                    }
                }
            } else if (displayRows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No matching items", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(displayRows, key = { row -> if (row is LibraryDisplayRow.PlaylistGroup) row.item.id else (row as LibraryDisplayRow.Media).item.id }) { row ->
                        if (row is LibraryDisplayRow.PlaylistGroup) {
                            val canToggleExpansion = row.childCount > 0 && searchQuery.trim().isEmpty()
                            LibraryPlaylistGroupCard(row.item, row.childCount, row.isExpanded, canToggleExpansion, onToggleExpanded = {
                                onCollapsedFolderIdsChanged(if (row.isExpanded) collapsedFolderIds + row.item.id else collapsedFolderIds - row.item.id)
                            }, onRename = {
                                renameFolderItem = row.item
                                renameFolderTitle = row.item.title
                            }, onDelete = { requestDeleteItem(row.item) })
                            return@items
                        }
                        val item = (row as LibraryDisplayRow.Media).item
                        val hasLocalCopy = item.hasDownloadedLocalCopy()
                        val youtubeSourceUrl = item.sourceUrl?.takeIf { isYoutubeUrl(it) }
                        val canManageYoutubeDownload = youtubeSourceUrl != null
                        val downloadState = if (canManageYoutubeDownload) downloadStates[item.id] ?: if (hasLocalCopy) LibraryDownloadState.Complete else LibraryDownloadState.Idle else LibraryDownloadState.Idle
                        LibraryCard(
                            item = item,
                            onClick = { onItemClick(item) },
                            onDelete = { requestDeleteItem(item) },
                            onMove = if (folderItems.isNotEmpty() || item.parentId != null) ({ moveTargetItem = item }) else null,
                            downloadState = downloadState,
                            modifier = if (row.isPlaylistChild) Modifier.padding(start = 18.dp) else Modifier,
                            onDeleteDownload = if (canManageYoutubeDownload && hasLocalCopy) ({
                                val updated = deleteDownloadedLocalCopy(item)
                                if (updated != null) {
                                    libraryManager.saveItem(updated)
                                    downloadStates.remove(item.id)
                                    onRefresh()
                                }
                            }) else null,
                            onDownload = if (canManageYoutubeDownload && !hasLocalCopy) ({
                                if (downloadState != LibraryDownloadState.Loading) {
                                    downloadStates[item.id] = LibraryDownloadState.Loading
                                    scope.launch {
                                        val downloaded = withContext(Dispatchers.IO) { downloadVideoLinkToLibraryItem(youtubeSourceUrl, item) }
                                        if (downloaded != null) {
                                            libraryManager.saveItem(downloaded)
                                            downloadStates[item.id] = LibraryDownloadState.Complete
                                            onRefresh()
                                        } else {
                                            downloadStates.remove(item.id)
                                        }
                                    }
                                }
                            }) else null
                        )
                    }
                }
            }
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("Welcome to Rougo") },
            text = {
                Text("Add local audio/video from the Library, paste a video link to stream or download it, install Yomitan dictionaries from Settings, then record short shadowing segments and compare waveforms.")
            },
            confirmButton = { Button(onClick = { showHelpDialog = false }) { Text("Done") } }
        )
    }

    pendingDeleteItem?.let { item ->
        AlertDialog(onDismissRequest = { pendingDeleteItem = null }, title = { Text("Delete video?") }, text = {
            Text("This video has ${item.recordings.size} recordings. Deleting it will also delete its downloaded video, recordings, subtitles, and cached artwork.")
        }, confirmButton = {
            Button(onClick = {
                pendingDeleteItem = null
                onDelete(item)
            }) { Text("Delete") }
        }, dismissButton = { OutlinedButton(onClick = { pendingDeleteItem = null }) { Text("Cancel") } })
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { if (!isImportingMedia) showAddDialog = false },
            title = { Text("Add Subtitles?") },
            text = { Text(if (isImportingMedia) "Reading embedded metadata and cover art..." else "Would you like to attach a subtitle file (.srt, .vtt, .ass) to '$pendingTitle'?") },
            confirmButton = {
                Button(onClick = {
                    val subtitle = openSingleFileDialog("Select Subtitles", listOf(".srt", ".vtt", ".ass"))
                    savePendingMedia(subtitle)
                }, enabled = !isImportingMedia) { Text("Select Subtitles") }
            },
            dismissButton = {
                OutlinedButton(onClick = { savePendingMedia(null) }, enabled = !isImportingMedia) { Text("Skip") }
            }
        )
    }

    if (showCreateFolderDialog) {
        AlertDialog(onDismissRequest = { showCreateFolderDialog = false }, title = { Text("Create Folder") }, text = {
            OutlinedTextField(value = folderTitleText, onValueChange = { folderTitleText = it }, singleLine = true, label = { Text("Folder name") }, modifier = Modifier.fillMaxWidth())
        }, confirmButton = {
            Button(onClick = { createFolder() }, enabled = folderTitleText.trim().isNotEmpty()) { Text("Create") }
        }, dismissButton = { OutlinedButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") } })
    }

    renameFolderItem?.let { folder ->
        AlertDialog(onDismissRequest = { renameFolderItem = null }, title = { Text("Rename Folder") }, text = {
            OutlinedTextField(value = renameFolderTitle, onValueChange = { renameFolderTitle = it }, singleLine = true, label = { Text("Folder name") }, modifier = Modifier.fillMaxWidth())
        }, confirmButton = {
            Button(onClick = {
                val title = renameFolderTitle.trim()
                if (title.isNotBlank()) {
                    libraryManager.saveItem(folder.copy(title = title))
                    renameFolderItem = null
                    renameFolderTitle = ""
                    onRefresh()
                }
            }, enabled = renameFolderTitle.trim().isNotEmpty()) { Text("Rename") }
        }, dismissButton = { OutlinedButton(onClick = { renameFolderItem = null }) { Text("Cancel") } })
    }

    moveTargetItem?.let { movingItem ->
        AlertDialog(onDismissRequest = { moveTargetItem = null }, title = { Text("Move to Folder") }, text = {
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                item {
                    TextButton(onClick = {
                        if (libraryManager.moveItemToFolder(movingItem.id, null)) onRefresh()
                        moveTargetItem = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.LibraryMusic, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("No folder")
                    }
                }
                items(folderItems, key = { it.id }) { folder ->
                    TextButton(onClick = {
                        if (libraryManager.moveItemToFolder(movingItem.id, folder.id)) onRefresh()
                        moveTargetItem = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(folder.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { moveTargetItem = null }) { Text("Cancel") } })
    }

    if (showLinkDialog) {
        val pendingUrl = extractFirstUrl(linkText)
        val isPlaylistLink = pendingUrl?.let { isYoutubePlaylistUrl(it) } == true
        AlertDialog(onDismissRequest = { if (!isDownloadingLink) showLinkDialog = false }, title = { Text("Add Video Link") }, text = {
            Column {
                OutlinedTextField(value = linkText, onValueChange = { linkText = it }, singleLine = true, enabled = !isDownloadingLink, label = { Text("Video URL") }, placeholder = { Text("https://...") }, modifier = Modifier.fillMaxWidth())
                if (isDownloadingLink) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }, confirmButton = {
            Button(enabled = !isDownloadingLink && pendingUrl != null, onClick = {
                val url = pendingUrl ?: return@Button
                if (isPlaylistLink) {
                    isDownloadingLink = true
                    scope.launch {
                        val plan = withContext(Dispatchers.IO) {
                            fetchYoutubePlaylistImportData(url)?.let { playlist ->
                                buildPlaylistImportPlan(playlist.title, url, playlist.entries) { UUID.randomUUID().toString() }
                            }
                        }
                        if (plan != null) {
                            libraryManager.saveItems(listOf(plan.group) + plan.children)
                            onRefresh()
                            showLinkDialog = false
                            linkText = ""
                        }
                        isDownloadingLink = false
                    }
                } else {
                    showLinkDialog = false
                    linkText = ""
                    onAddLink(url)
                }
            }) { Text(if (isPlaylistLink) "Import" else "Stream") }
        }, dismissButton = {
            if (!isPlaylistLink) {
                TextButton(enabled = !isDownloadingLink && pendingUrl != null, onClick = {
                    val url = pendingUrl ?: return@TextButton
                    isDownloadingLink = true
                    scope.launch {
                        val downloaded = withContext(Dispatchers.IO) { downloadVideoLinkToLibraryItem(url) }
                        if (downloaded != null) {
                            libraryManager.saveItem(downloaded)
                            onRefresh()
                            showLinkDialog = false
                            linkText = ""
                        }
                        isDownloadingLink = false
                    }
                }) { Text("Download") }
            } else {
                TextButton(enabled = !isDownloadingLink, onClick = { showLinkDialog = false }) { Text("Cancel") }
            }
        })
    }
}
