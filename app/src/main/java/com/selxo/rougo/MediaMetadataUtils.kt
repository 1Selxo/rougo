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

internal fun LibraryItem.persistableMediaUri(actualMediaUri: String?): String {
    if (sourceUrl == null || hasDownloadedLocalCopy()) return actualMediaUri ?: mediaUri
    return sourceUrl
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

// ==========================================
// 3. UI SCREENS
// ==========================================
