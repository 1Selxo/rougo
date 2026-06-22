package com.selxo.rougo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Size
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import com.yausername.ffmpeg.FFmpeg
import com.yausername.ffmpeg.execute
import java.io.File

internal fun LibraryItem.persistableMediaUri(actualMediaUri: String?): String {
    return persistableMediaUriForStorage(
        sourceUrl = sourceUrl,
        currentMediaUri = mediaUri,
        actualMediaUri = actualMediaUri,
        hasDownloadedLocalCopy = hasDownloadedLocalCopy()
    )
}
internal fun extractMediaMetadata(context: Context, uri: Uri, itemId: String, isVideo: Boolean): MediaMetadataSnapshot {
    var retriever: MediaMetadataRetriever? = null
    var pfd: ParcelFileDescriptor? = null
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var albumArtist: String? = null
    var genre: String? = null
    var year: String? = null
    var durationMs: Long? = null
    var coverArtPath: String? = null

    try {
        retriever = MediaMetadataRetriever()
        if (uri.scheme == "file") {
            retriever.setDataSource(File(uri.path ?: "").absolutePath)
        } else {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) retriever.setDataSource(pfd.fileDescriptor)
        }

        title = retriever.cleanMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        artist = firstCleanMetadataValue(
            retriever.cleanMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            retriever.cleanMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
            retriever.cleanMetadata(MediaMetadataRetriever.METADATA_KEY_WRITER),
            retriever.cleanMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
        )
        album = retriever.cleanMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        albumArtist = retriever.cleanMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
        genre = retriever.cleanMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
        year = firstCleanMetadataValue(
            retriever.cleanMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
            retriever.cleanMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        )?.take(4)
        durationMs = retriever.cleanMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

        coverArtPath = if (isVideo) {
            retriever.getFrameAtTime(10_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let { cacheCoverBitmap(context, itemId, it) }
        } else {
            retriever.embeddedPicture?.let { cacheCoverBytes(context, itemId, it) }
        }
    } catch (e: Throwable) {
        e.printStackTrace()
    } finally {
        try { retriever?.release() } catch (e: Throwable) {}
        try { pfd?.close() } catch (e: Throwable) {}
    }

    if (coverArtPath == null && !isVideo) {
        coverArtPath = extractAttachedPictureWithFfmpeg(context, itemId, uri)
    }

    return MediaMetadataSnapshot(
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        genre = genre,
        year = year,
        durationMs = durationMs,
        coverArtPath = coverArtPath
    )
}
internal fun MediaMetadataRetriever.cleanMetadata(keyCode: Int): String? {
    return try {
        cleanMetadataValue(extractMetadata(keyCode))
    } catch (e: Exception) {
        null
    }
}
internal fun decodeSampledBitmapFile(path: String, maxSize: Int = 1024): Bitmap? {
    val file = File(path)
    if (!file.exists() || file.length() <= 0L) return null

    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

    var scale = 1
    while (boundsOptions.outWidth / scale > maxSize || boundsOptions.outHeight / scale > maxSize) scale *= 2

    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = scale })
}
internal fun cachedCoverPathForItem(context: Context, itemId: String?): String? {
    val id = itemId?.takeIf { it.isNotBlank() } ?: return null
    val coverDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "MetadataCovers")
    val safeId = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    return File(coverDir, "$safeId.jpg")
        .takeIf { it.exists() && it.length() > 0L }
        ?.absolutePath
}
internal fun cacheCoverBytes(context: Context, itemId: String, bytes: ByteArray): String? {
    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

    var scale = 1
    while (boundsOptions.outWidth / scale > 1024 || boundsOptions.outHeight / scale > 1024) scale *= 2

    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = scale }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null
    return cacheCoverBitmap(context, itemId, bitmap)
}
private fun cacheCoverBitmap(context: Context, itemId: String, bitmap: Bitmap): String? {
    val coverDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "MetadataCovers")
        .apply { mkdirs() }
    val safeId = itemId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val coverFile = File(coverDir, "$safeId.jpg")

    return try {
        coverFile.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
        }
        coverFile.takeIf { it.length() > 0L }?.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        try { coverFile.delete() } catch (deleteError: Exception) {}
        null
    }
}
private fun extractAttachedPictureWithFfmpeg(context: Context, itemId: String, uri: Uri): String? {
    if (!ensureMediaToolsReady(context)) return null
    val input = resolveFfmpegInput(context, uri, preferFileDescriptor = false) ?: return null
    val coverDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "MetadataCovers")
        .apply { mkdirs() }
    val safeId = itemId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val tempCover = File(coverDir, "$safeId.ffmpeg.jpg")

    return try {
        try { tempCover.delete() } catch (e: Exception) {}
        val rc = FFmpeg.getInstance().execute(
            arrayOf(
                "-y",
                "-hide_banner",
                "-loglevel",
                "error",
                "-nostdin",
                "-i",
                input.value,
                "-map",
                "0:v:0",
                "-frames:v",
                "1",
                tempCover.absolutePath
            )
        )

        if (rc == 0 && tempCover.length() > 0L) {
            BitmapFactory.decodeFile(tempCover.absolutePath)
                ?.let { cacheCoverBitmap(context, itemId, it) }
        } else {
            null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } finally {
        try { tempCover.delete() } catch (e: Exception) {}
        try { input.tempFile?.delete() } catch (e: Exception) {}
        try { input.pfd?.close() } catch (e: Exception) {}
    }
}
internal fun mergeMetadataIntoItem(item: LibraryItem, metadata: MediaMetadataSnapshot, fallbackTitle: String = item.title): LibraryItem {
    return item.copy(
        title = metadata.title
            ?.takeIf { !looksLikeGeneratedFileId(it) }
            ?: cleanMetadataValue(fallbackTitle)
            ?: item.title.takeIf { !looksLikeGeneratedFileId(it) }
            ?: item.title,
        duration = if (item.duration > 0L) item.duration else metadata.durationMs ?: item.duration,
        artist = metadata.artist ?: item.artist,
        album = metadata.album ?: item.album,
        albumArtist = metadata.albumArtist ?: item.albumArtist,
        genre = metadata.genre ?: item.genre,
        year = metadata.year ?: item.year,
        coverArtPath = metadata.coverArtPath ?: item.coverArtPath
    )
}
