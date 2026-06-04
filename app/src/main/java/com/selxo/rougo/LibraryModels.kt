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

data class ShadowRecording(
    val id: String = UUID.randomUUID().toString(),
    val filePath: String, val startTime: Long, val endTime: Long, val timestamp: Long = System.currentTimeMillis()
)
data class LibraryItem(
    val id: String, val title: String, val mediaUri: String,
    val subtitleUri: String?, var progress: Long, var duration: Long, val isVideo: Boolean,
    var recordings: List<ShadowRecording> = emptyList(),
    val sourceUrl: String? = null, val formatId: String? = null,
    val artist: String? = null, val album: String? = null, val albumArtist: String? = null,
    val genre: String? = null, val year: String? = null, val coverArtPath: String? = null,
    val httpUserAgent: String? = null, val httpReferer: String? = null
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
    if (sourceUrl != null && !hasDownloadedLocalCopy()) return false
    val hasCover = coverArtPath?.let { File(it).exists() && File(it).length() > 0L } == true
    return !hasCover || metadataSummary() == null || duration <= 0L
}
internal fun isLocalMediaUriValue(value: String): Boolean {
    val scheme = runCatching { Uri.parse(value).scheme?.lowercase(Locale.US) }.getOrNull()
    return scheme.isNullOrBlank() || scheme == "file" || scheme == "content"
}
internal fun LibraryItem.hasDownloadedLocalCopy(): Boolean {
    val media = mediaUri.trim()
    val source = sourceUrl?.trim().orEmpty()
    return source.isNotBlank() && media.isNotBlank() && media != source && isLocalMediaUriValue(media)
}
internal fun deleteDownloadedLocalCopy(context: Context, item: LibraryItem): LibraryItem? {
    val source = item.sourceUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (!item.hasDownloadedLocalCopy()) return null

    if (!deleteAppOwnedMediaUri(context, item.mediaUri.toUri())) return null
    return item.copy(mediaUri = source)
}
internal fun deleteLibraryItemAssociatedFiles(context: Context, item: LibraryItem) {
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
internal fun LibraryItem.displaySourceLabel(): String {
    val source = sourceUrl
    return when {
        source != null && hasDownloadedLocalCopy() -> "${streamSourceLabel(source)} (local)"
        source != null -> streamSourceLabel(source)
        isVideo -> "Video"
        else -> "Audio"
    }
}
