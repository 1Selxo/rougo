package com.selxo.rougo

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import java.io.File
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

data class ShadowRecording(
    val id: String = UUID.randomUUID().toString(),
    val filePath: String, val startTime: Long, val endTime: Long, val timestamp: Long = System.currentTimeMillis()
)
enum class LibraryItemKind { Media, Playlist }
data class LibraryItem(
    val id: String, val title: String, val mediaUri: String,
    val subtitleUri: String?, var progress: Long, var duration: Long, val isVideo: Boolean,
    var recordings: List<ShadowRecording> = emptyList(),
    val sourceUrl: String? = null, val formatId: String? = null,
    val artist: String? = null, val album: String? = null, val albumArtist: String? = null,
    val genre: String? = null, val year: String? = null, val coverArtPath: String? = null,
    val httpUserAgent: String? = null, val httpReferer: String? = null,
    val itemKind: LibraryItemKind = LibraryItemKind.Media,
    val parentId: String? = null,
    val playlistSourceUrl: String? = null,
    val playlistItemIndex: Int = 0
)
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)
internal data class MediaMetadataSnapshot(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val durationMs: Long? = null,
    val coverArtPath: String? = null
)
internal fun JSONObject.optCleanString(key: String): String? = cleanMetadataValue(optString(key, ""))
internal fun cleanMetadataValue(value: String?): String? {
    val cleaned = value
        ?.replace('\u0000', ' ')
        ?.replace('\u00A0', ' ')
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()

    return cleaned.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
}
internal fun firstCleanMetadataValue(vararg values: String?): String? {
    return values.firstNotNullOfOrNull { cleanMetadataValue(it) }
}
internal fun LibraryItem.metadataSummary(): String? {
    return listOfNotNull(
        firstCleanMetadataValue(artist, albumArtist),
        album,
        year
    ).distinct().joinToString(" / ").takeIf { it.isNotBlank() }
}
internal fun LibraryItem.needsLocalMetadataRefresh(): Boolean {
    if (itemKind != LibraryItemKind.Media) return false
    if (sourceUrl != null && !hasDownloadedLocalCopy()) return false
    val hasCover = coverArtPath?.let { File(it).exists() && File(it).length() > 0L } == true
    return !hasCover || metadataSummary() == null || duration <= 0L
}
internal fun isLocalMediaUriValue(value: String): Boolean {
    val scheme = runCatching { Uri.parse(value).scheme?.lowercase(Locale.US) }.getOrNull()
    return scheme.isNullOrBlank() || scheme == "file" || scheme == "content"
}
internal fun LibraryItem.hasDownloadedLocalCopy(): Boolean {
    if (itemKind != LibraryItemKind.Media) return false
    val media = mediaUri.trim()
    val source = sourceUrl?.trim().orEmpty()
    return source.isNotBlank() && media.isNotBlank() && media != source && isLocalMediaUriValue(media)
}
internal fun LibraryItem.isPlaylistGroup(): Boolean = itemKind == LibraryItemKind.Playlist
internal fun deleteDownloadedLocalCopy(context: Context, item: LibraryItem): LibraryItem? {
    val source = item.sourceUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (!item.hasDownloadedLocalCopy()) return null

    if (!deleteAppOwnedMediaUri(context, item.mediaUri.toUri())) return null
    return item.copy(mediaUri = source)
}
internal fun deleteLibraryItemAssociatedFiles(context: Context, item: LibraryItem) {
    if (item.isPlaylistGroup()) return
    item.coverArtPath?.let { deleteAppOwnedFilePath(context, it) }
    cachedCoverPathForItem(context, item.id)?.let { deleteAppOwnedFilePath(context, it) }
    item.subtitleUri?.let { deleteAppOwnedMediaUri(context, it.toUri()) }
    item.recordings.forEach { recording ->
        deleteAppOwnedFilePath(context, recording.filePath)
    }

    if (item.hasDownloadedLocalCopy()) {
        deleteAppOwnedMediaUri(context, item.mediaUri.toUri())
    }
    deleteAppOwnedDownloadFilesForItem(context, item.id)
}
private fun deleteAppOwnedDownloadFilesForItem(context: Context, itemId: String) {
    val destDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "RougoDownloads")
    val fileId = itemId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    destDir.listFiles()
        ?.filter { it.isFile && it.name.startsWith(fileId) }
        ?.forEach { deleteAppOwnedFile(context, it) }
}
private fun deleteAppOwnedMediaUri(context: Context, uri: Uri): Boolean {
    return try {
        when (uri.scheme?.lowercase(Locale.US)) {
            null, "file" -> {
                val path = uri.path ?: return true
                deleteAppOwnedFile(context, File(path))
            }
            "content" -> if (uri.authority?.startsWith(context.packageName) == true) {
                context.contentResolver.delete(uri, null, null) > 0
            } else {
                false
            }
            else -> false
        }
    } catch (e: Exception) {
        false
    }
}
private fun deleteAppOwnedFilePath(context: Context, path: String): Boolean {
    return deleteAppOwnedFile(context, File(path))
}
private fun deleteAppOwnedFile(context: Context, file: File): Boolean {
    return try {
        val canonicalFile = file.canonicalFile
        if (!isAppOwnedFile(context, canonicalFile)) return false
        !canonicalFile.exists() || canonicalFile.delete()
    } catch (e: Exception) {
        false
    }
}
private fun isAppOwnedFile(context: Context, file: File): Boolean {
    val roots = buildList {
        add(context.filesDir)
        add(context.cacheDir)
        context.externalCacheDir?.let { add(it) }
        context.getExternalFilesDir(null)?.let { add(it) }
    }

    val filePath = runCatching { file.canonicalPath }.getOrNull() ?: return false
    return roots.any { root ->
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
        filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
    }
}
internal fun LibraryItem.displaySourceLabel(context: Context): String {
    if (isPlaylistGroup()) return context.getString(R.string.media_source_playlist)
    val source = sourceUrl
    return when {
        source != null && hasDownloadedLocalCopy() ->
            context.getString(R.string.media_source_local_format, streamSourceLabel(context, source))
        source != null -> streamSourceLabel(context, source)
        isVideo -> context.getString(R.string.media_source_video)
        else -> context.getString(R.string.media_source_audio)
    }
}
