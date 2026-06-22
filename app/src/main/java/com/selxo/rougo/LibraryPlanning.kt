package com.selxo.rougo

import java.util.Locale

internal data class PlaylistImportEntry(
    val title: String,
    val sourceUrl: String,
    val formatId: String? = null,
    val thumbnailUrl: String? = null,
    val isVideo: Boolean = true
)

internal data class PlaylistImportPlan(
    val group: LibraryItem,
    val children: List<LibraryItem>
)

internal sealed class LibraryDisplayRow {
    data class PlaylistGroup(
        val item: LibraryItem,
        val childCount: Int,
        val isExpanded: Boolean
    ) : LibraryDisplayRow()
    data class Media(val item: LibraryItem, val isPlaylistChild: Boolean) : LibraryDisplayRow()
}

internal fun buildLibraryFolder(
    folderTitle: String,
    nextId: () -> String
): LibraryItem {
    return LibraryItem(
        id = nextId(),
        title = folderTitle.ifBlank { "Folder" },
        mediaUri = "",
        subtitleUri = null,
        progress = 0L,
        duration = 0L,
        isVideo = false,
        itemKind = LibraryItemKind.Folder
    )
}

internal fun buildPlaylistImportPlan(
    playlistTitle: String,
    playlistUrl: String,
    entries: List<PlaylistImportEntry>,
    nextId: () -> String
): PlaylistImportPlan {
    val groupId = nextId()
    val group = LibraryItem(
        id = groupId,
        title = playlistTitle.ifBlank { "Playlist" },
        mediaUri = "",
        subtitleUri = null,
        progress = 0L,
        duration = 0L,
        isVideo = true,
        sourceUrl = playlistUrl,
        playlistSourceUrl = playlistUrl,
        itemKind = LibraryItemKind.Folder
    )
    val children = entries.mapIndexed { index, entry ->
        LibraryItem(
            id = nextId(),
            title = entry.title.ifBlank { "Video ${index + 1}" },
            mediaUri = entry.sourceUrl,
            subtitleUri = null,
            progress = 0L,
            duration = 0L,
            isVideo = entry.isVideo,
            sourceUrl = entry.sourceUrl,
            formatId = entry.formatId,
            parentId = groupId,
            playlistSourceUrl = playlistUrl,
            playlistItemIndex = index
        )
    }
    return PlaylistImportPlan(group, children)
}

internal fun libraryDisplayRows(
    items: List<LibraryItem>,
    searchQuery: String,
    selectedFilter: String,
    sortMode: String,
    collapsedGroupIds: Set<String> = emptySet()
): List<LibraryDisplayRow> {
    val query = searchQuery.trim().lowercase(Locale.US)
    val media = items.filter { !it.isFolderGroup() }
    val mediaByParent = media.groupBy { it.parentId }
    val visibleTopLevelMediaIds = filterLibraryMediaItems(mediaByParent[null].orEmpty(), query, selectedFilter)
        .map { it.id }
        .toSet()
    val rows = mutableListOf<LibraryDisplayRow>()

    sortLibraryTopLevelItems(items.filter { it.parentId == null }, sortMode).forEach { topLevelItem ->
        if (topLevelItem.isFolderGroup()) {
            val children = sortLibraryMediaItems(
                filterLibraryMediaItems(mediaByParent[topLevelItem.id].orEmpty(), query, selectedFilter),
                sortMode,
                keepPlaylistOrder = sortMode == "Recent"
            )
            val groupMatches = matchesLibraryQuery(topLevelItem, query) && matchesLibraryFilter(topLevelItem, selectedFilter)
            if (groupMatches || children.isNotEmpty()) {
                val childCount = mediaByParent[topLevelItem.id].orEmpty().size
                val isExpanded = topLevelItem.id !in collapsedGroupIds || query.isNotEmpty()
                rows += LibraryDisplayRow.PlaylistGroup(topLevelItem, childCount, isExpanded)
                if (isExpanded) {
                    children.forEach { child ->
                        rows += LibraryDisplayRow.Media(child, isPlaylistChild = true)
                    }
                }
            }
        } else if (topLevelItem.id in visibleTopLevelMediaIds) {
            rows += LibraryDisplayRow.Media(topLevelItem, isPlaylistChild = false)
        }
    }

    return rows
}

internal fun libraryMediaItemCount(items: List<LibraryItem>): Int =
    items.count { !it.isFolderGroup() }

internal fun persistableMediaUriForStorage(
    sourceUrl: String?,
    currentMediaUri: String,
    actualMediaUri: String?,
    hasDownloadedLocalCopy: Boolean
): String {
    if (sourceUrl == null || hasDownloadedLocalCopy) return actualMediaUri ?: currentMediaUri
    return sourceUrl
}

private fun filterLibraryMediaItems(
    items: List<LibraryItem>,
    query: String,
    selectedFilter: String
): List<LibraryItem> {
    return items.filter { item ->
        matchesLibraryQuery(item, query) && matchesLibraryFilter(item, selectedFilter)
    }
}

private fun matchesLibraryQuery(item: LibraryItem, query: String): Boolean {
    return query.isEmpty() || item.title.lowercase(Locale.US).contains(query)
}

private fun matchesLibraryFilter(item: LibraryItem, selectedFilter: String): Boolean {
    return when (selectedFilter) {
        "Audio" -> !item.isFolderGroup() && !item.isVideo
        "Video" -> !item.isFolderGroup() && item.isVideo
        "YouTube" -> item.sourceUrl != null || item.playlistSourceUrl != null
        "Local" -> !item.isFolderGroup() && item.sourceUrl == null
        else -> true
    }
}

private fun sortLibraryMediaItems(
    items: List<LibraryItem>,
    sortMode: String,
    keepPlaylistOrder: Boolean = false
): List<LibraryItem> {
    if (keepPlaylistOrder) return items.sortedBy { it.playlistItemIndex }
    return when (sortMode) {
        "Title" -> items.sortedBy { it.title.lowercase(Locale.US) }
        "Progress" -> items.sortedByDescending {
            if (it.duration > 0L) it.progress.toFloat() / it.duration.toFloat() else 0f
        }
        "Recordings" -> items.sortedByDescending { it.recordings.size }
        else -> items
    }
}

private fun sortLibraryTopLevelItems(items: List<LibraryItem>, sortMode: String): List<LibraryItem> {
    return when (sortMode) {
        "Title" -> items.sortedBy { it.title.lowercase(Locale.US) }
        "Progress" -> items.sortedByDescending {
            if (it.isFolderGroup()) 0f else if (it.duration > 0L) it.progress.toFloat() / it.duration.toFloat() else 0f
        }
        "Recordings" -> items.sortedByDescending { it.recordings.size }
        else -> items
    }
}
