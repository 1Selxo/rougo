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

fun exportRecording(context: Context, file: File) {
    try {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Segment"))
    } catch (e: Exception) {
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "RougoShare_${System.currentTimeMillis()}.m4a")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_MUSIC + "/Rougo")
                }
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
                Toast.makeText(context, "Exported to Music/Rougo!", Toast.LENGTH_SHORT).show()

                val viewIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(viewIntent, "Share Segment"))
            }
        } catch (ex: Exception) {
            Toast.makeText(context, "Export failed: ${ex.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
@Composable
fun loadAlbumArt(
    context: Context,
    uriString: String,
    isVideo: Boolean,
    cachedCoverPath: String? = null,
    itemId: String? = null,
    sourceUrl: String? = null
): ImageBitmap? {
    val savedCoverPath = cachedCoverPath?.takeIf { File(it).exists() && File(it).length() > 0L }
    val remoteThumbnailUrl = if (isVideo) youtubeThumbnailUrl(sourceUrl) else null
    val cachedRemoteCoverPath = if (savedCoverPath == null) cachedCoverPathForItem(context, itemId) else null
    val cacheKey = savedCoverPath ?: cachedRemoteCoverPath ?: remoteThumbnailUrl ?: uriString
    var bitmap by remember(cacheKey) { mutableStateOf<ImageBitmap?>(ImageCache.cache[cacheKey]) }

    LaunchedEffect(uriString, cachedCoverPath, itemId, sourceUrl) {
        if (bitmap != null) return@LaunchedEffect

        val loadedImage = withContext(Dispatchers.IO) {
            savedCoverPath
                ?.let { decodeSampledBitmapFile(it)?.asImageBitmap() }
                ?: cachedRemoteCoverPath?.let { decodeSampledBitmapFile(it)?.asImageBitmap() }
                ?: remoteThumbnailUrl?.let { thumbnailUrl ->
                    val coverId = itemId?.takeIf { it.isNotBlank() } ?: "thumb_${thumbnailUrl.hashCode()}"
                    downloadRemoteCover(context, coverId, thumbnailUrl)
                        ?.let { decodeSampledBitmapFile(it)?.asImageBitmap() }
                }
                ?: if (sourceUrl != null && !isLocalMediaUriValue(uriString)) {
                    null
                } else {
                    loadAlbumArtFromMedia(context, uriString, isVideo)
                }
        }

        if (loadedImage != null) {
            ImageCache.cache[cacheKey] = loadedImage
            bitmap = loadedImage
        }
    }
    return bitmap
}
private fun loadAlbumArtFromMedia(context: Context, uriString: String, isVideo: Boolean): ImageBitmap? {
    val uri = Uri.parse(uriString)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            return context.contentResolver.loadThumbnail(uri, Size(800, 800), null).asImageBitmap()
        } catch (e: Throwable) { }
    }

    var retriever: MediaMetadataRetriever? = null
    var pfd: ParcelFileDescriptor? = null
    return try {
        retriever = MediaMetadataRetriever()
        if (uri.scheme == "file") {
            retriever.setDataSource(File(uri.path ?: "").absolutePath)
        } else {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) retriever.setDataSource(pfd.fileDescriptor)
        }

        if (isVideo) {
            val duration = retriever.cleanMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val randomTimeUs = if (duration > 60000L) {
                (duration * 1000 * (0.1 + Math.random() * 0.8)).toLong()
            } else {
                10_000_000L
            }
            retriever.getFrameAtTime(randomTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.asImageBitmap()
        } else {
            retriever.embeddedPicture
                ?.let { bytes ->
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
                    var scale = 1
                    while (boundsOptions.outWidth / scale > 1024 || boundsOptions.outHeight / scale > 1024) scale *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = scale })
                        ?.asImageBitmap()
                }
        }
    } catch (e: Throwable) {
        e.printStackTrace()
        null
    } finally {
        try { retriever?.release() } catch (e: Throwable) {}
        try { pfd?.close() } catch (e: Throwable) {}
    }
}
