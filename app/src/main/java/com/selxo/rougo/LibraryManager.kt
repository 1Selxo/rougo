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

class LibraryManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("rougo_library", Context.MODE_PRIVATE)

    fun getItems(): List<LibraryItem> {
        val jsonString = prefs.getString("items", "[]") ?: "[]"
        val jsonArray = try {
            JSONArray(jsonString)
        } catch (e: Exception) {
            CrashReporter.recordHandled(appContext, "LibraryManager.getItems root JSON", e)
            JSONArray()
        }
        val list = mutableListOf<LibraryItem>()
        for (i in 0 until jsonArray.length()) {
            try {
                val obj = jsonArray.getJSONObject(i)
                val mediaUri = obj.optString("mediaUri").takeIf { it.isNotBlank() } ?: continue

                val recordingsList = mutableListOf<ShadowRecording>()
                if (obj.has("recordings")) {
                    val recArray = obj.optJSONArray("recordings") ?: JSONArray()
                    for (j in 0 until recArray.length()) {
                        try {
                            val recObj = recArray.getJSONObject(j)
                            val filePath = recObj.optString("filePath").takeIf { it.isNotBlank() } ?: continue
                            recordingsList.add(
                                ShadowRecording(
                                    id = recObj.optString("id", UUID.randomUUID().toString()),
                                    filePath = filePath,
                                    startTime = recObj.optLong("startTime", 0L),
                                    endTime = recObj.optLong("endTime", 0L),
                                    timestamp = recObj.optLong("timestamp", System.currentTimeMillis())
                                )
                            )
                        } catch (e: Exception) {
                            CrashReporter.recordHandled(appContext, "LibraryManager.getItems recording $i/$j", e)
                        }
                    }
                }

                list.add(
                    LibraryItem(
                        id = obj.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                        title = obj.optCleanString("title") ?: "Unknown Media",
                        mediaUri = mediaUri,
                        subtitleUri = obj.optString("subtitleUri").takeIf { it.isNotBlank() },
                        progress = obj.optLong("progress", 0L),
                        duration = obj.optLong("duration", 0L),
                        isVideo = obj.optBoolean("isVideo", false),
                        recordings = recordingsList,
                        sourceUrl = obj.optString("sourceUrl").takeIf { it.isNotBlank() },
                        formatId = obj.optString("formatId").takeIf { it.isNotBlank() },
                        artist = obj.optCleanString("artist"),
                        album = obj.optCleanString("album"),
                        albumArtist = obj.optCleanString("albumArtist"),
                        genre = obj.optCleanString("genre"),
                        year = obj.optCleanString("year"),
                        coverArtPath = obj.optCleanString("coverArtPath"),
                        httpUserAgent = obj.optCleanString("httpUserAgent"),
                        httpReferer = obj.optCleanString("httpReferer")
                    )
                )
            } catch (e: Exception) {
                CrashReporter.recordHandled(appContext, "LibraryManager.getItems item $i", e)
            }
        }
        return list
    }

    fun saveItem(item: LibraryItem) {
        val current = getItems().toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) current[index] = item else current.add(0, item)
        prefs.edit { putString("items", itemsToJson(current).toString()) }
    }

    fun deleteItem(id: String) {
        val items = getItems()
        items.firstOrNull { it.id == id }?.let { item ->
            deleteLibraryItemAssociatedFiles(appContext, item)
        }
        prefs.edit { putString("items", itemsToJson(items.filter { it.id != id }).toString()) }
    }

    private fun itemsToJson(items: List<LibraryItem>): JSONArray {
        val array = JSONArray()
        items.forEach { array.put(itemToJson(it)) }
        return array
    }

    private fun itemToJson(item: LibraryItem): JSONObject {
        val obj = JSONObject()
        obj.put("id", item.id); obj.put("title", item.title); obj.put("mediaUri", item.mediaUri)
        obj.put("subtitleUri", item.subtitleUri ?: ""); obj.put("progress", item.progress)
        obj.put("duration", item.duration); obj.put("isVideo", item.isVideo)
        obj.put("sourceUrl", item.sourceUrl ?: ""); obj.put("formatId", item.formatId ?: "")
        obj.put("artist", item.artist ?: ""); obj.put("album", item.album ?: "")
        obj.put("albumArtist", item.albumArtist ?: ""); obj.put("genre", item.genre ?: "")
        obj.put("year", item.year ?: ""); obj.put("coverArtPath", item.coverArtPath ?: "")
        obj.put("httpUserAgent", item.httpUserAgent ?: ""); obj.put("httpReferer", item.httpReferer ?: "")

        val recArray = JSONArray()
        item.recordings.forEach { rec ->
            val recObj = JSONObject()
            recObj.put("id", rec.id); recObj.put("filePath", rec.filePath)
            recObj.put("startTime", rec.startTime); recObj.put("endTime", rec.endTime)
            recObj.put("timestamp", rec.timestamp)
            recArray.put(recObj)
        }
        obj.put("recordings", recArray)
        return obj
    }
}
