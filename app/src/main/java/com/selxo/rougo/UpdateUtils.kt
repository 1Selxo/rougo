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

data class UpdateInfo(val tagName: String, val downloadUrl: String, val body: String, val publishedAtMillis: Long)
suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
    try {
        val url = URL("https://api.github.com/repos/kaihouguide/rougo/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        if (conn.responseCode == 200) {
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val tagName = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            var fallbackDownloadUrl = ""
            var preferredDownloadUrl = ""
            val supportedAbis = Build.SUPPORTED_ABIS.map { it.lowercase(Locale.US) }
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    val assetUrl = asset.getString("browser_download_url")
                    if (fallbackDownloadUrl.isEmpty()) fallbackDownloadUrl = assetUrl
                    val lowerName = name.lowercase(Locale.US)
                    if (preferredDownloadUrl.isEmpty() && supportedAbis.any { abi -> lowerName.contains(abi) }) {
                        preferredDownloadUrl = assetUrl
                    }
                }
            }
            val body = json.optString("body", "")
            val downloadUrl = preferredDownloadUrl.ifEmpty { fallbackDownloadUrl }
            if (downloadUrl.isNotEmpty()) {
                return@withContext UpdateInfo(tagName, downloadUrl, body, parseGithubTimestamp(json.optString("published_at")))
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    null
}
private fun parseGithubTimestamp(value: String): Long {
    if (value.isBlank()) return 0L
    return try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse(value)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }
}
fun downloadAndInstallUpdate(context: Context, downloadUrl: String) {
    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: run {
        Toast.makeText(context, "Update storage is unavailable.", Toast.LENGTH_LONG).show()
        return
    }
    cleanupOldUpdateApks(downloadsDir)
    val fileName = updateDownloadFileName(downloadUrl)
    val destinationFile = File(downloadsDir, fileName)
    try { destinationFile.delete() } catch (e: Exception) {}
    val request = DownloadManager.Request(Uri.parse(downloadUrl))
        .setTitle(fileName)
        .setDescription("Downloading Rougo update...")
        .setMimeType("application/vnd.android.package-archive")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)

    val downloadId = downloadManager.enqueue(request)

    val onComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == downloadId) {
                val downloadedFile = resolveDownloadedUpdateFile(downloadManager, downloadId, destinationFile)
                if (isDownloadSuccessful(downloadManager, downloadId) && downloadedFile.exists() && downloadedFile.length() > 0L) {
                    installApk(context, downloadedFile)
                } else {
                    Toast.makeText(context, "Update download failed.", Toast.LENGTH_LONG).show()
                }
                try { context.unregisterReceiver(this) } catch (e: Exception) {}
            }
        }
    }

    val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(onComplete, filter, Context.RECEIVER_EXPORTED)
    } else {
        context.registerReceiver(onComplete, filter)
    }
}
private fun updateDownloadFileName(downloadUrl: String): String {
    var rawName = Uri.parse(downloadUrl).lastPathSegment
        ?.substringAfterLast('/')
        ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
        ?.takeIf { it.isNotBlank() }
        ?: "update"
    if (rawName.endsWith(".apk", ignoreCase = true)) {
        rawName = rawName.dropLast(4)
    }
    val safeBaseName = rawName.trim('.', '_', '-').ifBlank { "update" }
    return "rougo-${System.currentTimeMillis()}-$safeBaseName.apk"
}
private fun cleanupOldUpdateApks(downloadsDir: File?) {
    downloadsDir?.listFiles()
        ?.filter { file ->
            file.isFile &&
                (
                    file.name.equals("update", ignoreCase = true) ||
                        file.name.equals("update.apk", ignoreCase = true) ||
                        file.name.startsWith("rougo-")
                )
        }
        ?.forEach { file ->
            try { file.delete() } catch (e: Exception) {}
        }
}
private fun resolveDownloadedUpdateFile(downloadManager: DownloadManager, downloadId: Long, expectedApkFile: File): File {
    if (expectedApkFile.exists() && expectedApkFile.length() > 0L) return expectedApkFile
    val downloadedFile = queryDownloadedFile(downloadManager, downloadId)
    return if (downloadedFile != null && downloadedFile.exists() && downloadedFile.length() > 0L) {
        ensureUpdateApkExtension(downloadedFile, expectedApkFile)
    } else {
        expectedApkFile
    }
}
private fun queryDownloadedFile(downloadManager: DownloadManager, downloadId: Long): File? {
    return try {
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            if (localUriIndex >= 0) {
                val localUri = cursor.getString(localUriIndex)
                if (!localUri.isNullOrBlank()) {
                    val uri = Uri.parse(localUri)
                    if (uri.scheme.equals("file", ignoreCase = true)) {
                        uri.path?.let { return File(it) }
                    }
                }
            }

            val localFileIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME)
            if (localFileIndex >= 0) {
                cursor.getString(localFileIndex)?.takeIf { it.isNotBlank() }?.let { return File(it) }
            }
            null
        }
    } catch (e: Exception) {
        null
    }
}
private fun ensureUpdateApkExtension(downloadedFile: File, expectedApkFile: File): File {
    if (downloadedFile.name.endsWith(".apk", ignoreCase = true)) return downloadedFile
    return try {
        expectedApkFile.parentFile?.mkdirs()
        try { expectedApkFile.delete() } catch (e: Exception) {}
        if (downloadedFile.renameTo(expectedApkFile)) {
            expectedApkFile
        } else {
            downloadedFile.inputStream().use { input ->
                expectedApkFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            expectedApkFile
        }
    } catch (e: Exception) {
        downloadedFile
    }
}
private fun isDownloadSuccessful(downloadManager: DownloadManager, downloadId: Long): Boolean {
    return try {
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (!cursor.moveToFirst()) return false
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            statusIndex >= 0 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL
        } ?: false
    } catch (e: Exception) {
        false
    }
}
fun installApk(context: Context, file: File = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")) {
    if (file.exists()) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

// ==========================================
// 2. MAIN ACTIVITY & APP FLOW
// ==========================================
fun isNewerVersion(remoteTag: String, currentVersion: String?): Boolean {
    if (currentVersion == null) return true

    val remote = remoteTag.trim().trimStart('v', 'V')
    val local = currentVersion.trim().trimStart('v', 'V')

    if (remote == local) return false

    val remoteParts = Regex("\\d+").findAll(remote).map { it.value.toInt() }.toList()
    val localParts = Regex("\\d+").findAll(local).map { it.value.toInt() }.toList()

    val maxLength = maxOf(remoteParts.size, localParts.size)
    for (i in 0 until maxLength) {
        val r = remoteParts.getOrElse(i) { 0 }
        val l = localParts.getOrElse(i) { 0 }
        if (r > l) return true
        if (r < l) return false
    }
    return false
}
fun isUpdateAvailable(info: UpdateInfo, currentVersion: String?, lastUpdateTime: Long): Boolean {
    if (isNewerVersion(info.tagName, currentVersion)) return true
    val remote = info.tagName.trim().trimStart('v', 'V')
    val local = currentVersion?.trim()?.trimStart('v', 'V')
    return remote == local && info.publishedAtMillis > 0L && lastUpdateTime > 0L && info.publishedAtMillis > lastUpdateTime + 60000L
}
