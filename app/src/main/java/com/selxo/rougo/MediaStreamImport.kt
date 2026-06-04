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

internal data class YoutubeSubtitleChoice(
    val label: String,
    val languageCode: String,
    val isAutoGenerated: Boolean
)
internal data class YoutubeStreamFormat(
    val formatId: String?,
    val formatNote: String?,
    val ext: String?,
    val vcodec: String?,
    val acodec: String?,
    val height: Int,
    val tbr: Int,
    val url: String?,
    val manifestUrl: String?,
    val protocol: String?
)
internal data class YoutubeSetupData(
    val title: String,
    val fallbackUrl: String?,
    val formats: List<YoutubeStreamFormat>,
    val subtitleChoices: List<YoutubeSubtitleChoice>,
    val thumbnailUrl: String? = null,
    val httpUserAgent: String? = null,
    val httpReferer: String? = null
)
internal data class FastYoutubeStream(
    val title: String,
    val streamUrl: String,
    val formatId: String?,
    val isVideo: Boolean,
    val httpUserAgent: String? = null,
    val httpReferer: String? = null
)
private val mediaToolsInitLock = Any()
@Volatile
private var mediaToolsInitialized = false
@Volatile
private var mediaToolsInitFailureLogged = false
internal fun ensureMediaToolsReady(context: Context): Boolean {
    if (mediaToolsInitialized) return true
    val appContext = context.applicationContext
    return synchronized(mediaToolsInitLock) {
        if (mediaToolsInitialized) {
            true
        } else {
            try {
                YoutubeDL.getInstance().init(appContext)
                FFmpeg.getInstance().init(appContext)
                mediaToolsInitialized = true
                true
            } catch (t: Throwable) {
                t.printStackTrace()
                if (!mediaToolsInitFailureLogged) {
                    CrashReporter.recordHandled(appContext, "Media tools init", t)
                    mediaToolsInitFailureLogged = true
                }
                false
            }
        }
    }
}
internal fun isYoutubeUrl(url: String): Boolean {
    val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
    return host.contains("youtube.com") || host.contains("youtu.be")
}
internal fun playableYoutubeUrl(url: String): String? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    val host = uri.host.orEmpty().lowercase(Locale.US)
    val segments = uri.pathSegments

    val hasVideoId = when {
        host == "youtu.be" || host.endsWith(".youtu.be") -> segments.firstOrNull()?.isNotBlank() == true
        host.contains("youtube.com") -> {
            !uri.getQueryParameter("v").isNullOrBlank() ||
                (segments.size >= 2 && segments[0] in setOf("shorts", "live", "embed") && segments[1].isNotBlank())
        }
        else -> false
    }

    return if (hasVideoId) url else null
}
internal fun youtubeThumbnailUrl(sourceUrl: String?): String? {
    val videoId = youtubeVideoId(sourceUrl) ?: return null
    return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}
private fun youtubeVideoId(sourceUrl: String?): String? {
    val uri = runCatching { Uri.parse(sourceUrl ?: "") }.getOrNull() ?: return null
    val host = uri.host.orEmpty().lowercase(Locale.US)
    val segments = uri.pathSegments
    val candidate = when {
        host == "youtu.be" || host.endsWith(".youtu.be") -> segments.firstOrNull()
        host.contains("youtube.com") -> uri.getQueryParameter("v")
            ?: if (segments.size >= 2 && segments[0] in setOf("shorts", "live", "embed")) segments[1] else null
        else -> null
    }?.trim()

    return candidate?.takeIf { Regex("[A-Za-z0-9_-]{6,}").matches(it) }
}
internal fun isBilibiliUrl(url: String): Boolean {
    val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
    return host.contains("bilibili.com") || host == "b23.tv" || host.endsWith(".b23.tv")
}
internal fun isNiconicoUrl(url: String): Boolean {
    val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
    return host.contains("nicovideo.jp") || host.contains("nico.ms")
}
internal fun streamSourceLabel(url: String?): String {
    if (url.isNullOrBlank()) return "Video"
    return when {
        isYoutubeUrl(url) -> "YouTube"
        isBilibiliUrl(url) -> "Bilibili"
        isNiconicoUrl(url) -> "Niconico"
        else -> runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }
            .getOrDefault("")
            .ifBlank { "Stream" }
    }
}
private fun addFastYoutubeOptions(context: Context, request: YoutubeDLRequest, sourceUrl: String, skipDownload: Boolean = true): YoutubeDLRequest {
    val cacheDir = File(context.cacheDir, "yt-dlp-cache").apply { mkdirs() }
    val configured = request
        .addOption("--no-playlist")
        .addOption("--no-warnings")
        .addOption("--no-progress")
        .addOption("--force-ipv4")
        .addOption("--cache-dir", cacheDir.absolutePath)

    if (skipDownload) configured.addOption("--skip-download")

    if (isYoutubeUrl(sourceUrl)) {
        // iOS/Android clients bypass the Web JS challenge.
        // Skipping webpage, configs, and js stops yt-dlp from downloading heavy HTML/JS,
        // saving several more seconds by directly hitting the lightweight mobile API!
        configured.addOption("--extractor-args", "youtube:player_client=ios,android;player_skip=webpage,configs,js")
    }

    return configured
}
private fun fastYoutubeFormatSelector(preferredResolution: String): String {
    return when (preferredResolution) {
        YOUTUBE_RESOLUTION_AUDIO -> "bestaudio[ext=m4a]/bestaudio/best"
        YOUTUBE_RESOLUTION_HIGHEST ->
            "best[vcodec!=none][acodec!=none][ext=mp4]/best[vcodec!=none][acodec!=none]/best"
        else -> {
            val targetHeight = preferredResolution.toIntOrNull()?.coerceAtLeast(144)
            if (targetHeight == null) {
                "best[vcodec!=none][acodec!=none][ext=mp4]/best[vcodec!=none][acodec!=none]/best"
            } else {
                "best[height<=$targetHeight][vcodec!=none][acodec!=none][ext=mp4]/" +
                    "best[height<=$targetHeight][vcodec!=none][acodec!=none]/" +
                    "best[vcodec!=none][acodec!=none]/best"
            }
        }
    }
}
private fun parseYtdlpPrintOutput(output: String): Map<String, String> {
    val expectedKeys = setOf("title", "format_id", "url", "vcodec", "http_headers")
    return output.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val separatorIndex = line.indexOf(':')
            if (separatorIndex <= 0) return@mapNotNull null
            val key = line.substring(0, separatorIndex)
            if (key !in expectedKeys) return@mapNotNull null
            key to line.substring(separatorIndex + 1).trim()
        }
        .toMap()
}
private fun cleanYtdlpPrintedValue(value: String?): String? {
    return value
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != "NA" && it != "null" }
}
private fun sourceDefaultTitle(url: String): String = "${streamSourceLabel(url)} Video"
private fun cleanYtdlpTitle(value: String?, sourceUrl: String): String? {
    val cleaned = cleanYtdlpPrintedValue(value)
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf { !looksLikeGeneratedFileId(it) }
    return cleaned ?: sourceDefaultTitle(sourceUrl)
}
private fun cleanOptionalYtdlpTitle(value: String?, sourceUrl: String): String? {
    return cleanYtdlpTitle(value, sourceUrl)
        ?.takeIf { it != sourceDefaultTitle(sourceUrl) }
}
internal fun looksLikeGeneratedFileId(value: String): Boolean {
    return Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:[._ -].*)?$")
        .matches(value.trim())
}
private fun titleFromDownloadedFile(file: File, fileId: String, sourceUrl: String): String? {
    val raw = file.nameWithoutExtension
        .removePrefix(fileId)
        .trimStart('.', '-', '_', ' ')
        .replace(Regex("\\s+"), " ")
    return cleanOptionalYtdlpTitle(raw, sourceUrl)
}
internal fun fetchFastYoutubeStream(context: Context, url: String, preferredResolution: String): FastYoutubeStream? {
    if (!isYoutubeUrl(url)) return null
    check(ensureMediaToolsReady(context)) { "Media tools are unavailable." }
    val request = addFastYoutubeOptions(context, YoutubeDLRequest(url), url)
    request.addOption("-f", fastYoutubeFormatSelector(preferredResolution))
    request.addOption("--print", "%(title)s|||%(format_id)s|||%(url)s|||%(vcodec)s|||%(http_headers)j")

    return try {
        val response = YoutubeDL.getInstance().execute(request, null, false)
        val outLine = response.out.lineSequence().lastOrNull { it.contains("|||") } ?: return null
        val parts = outLine.split("|||")
        if (parts.size >= 5) {
            val title = parts[0].takeIf { it.isNotBlank() && it != "NA" && it != "null" } ?: "YouTube Stream"
            val formatId = parts[1].takeIf { it.isNotBlank() && it != "NA" && it != "null" }
            val streamUrl = parts[2].takeIf { it.isNotBlank() && it != "NA" && it != "null" } ?: return null
            val vcodec = parts[3].takeIf { it.isNotBlank() && it != "NA" && it != "null" }
            val headersJson = parts[4].takeIf { it.isNotBlank() && it != "NA" && it != "null" }
            val headers = headersJson?.let {
                runCatching { parseStreamHttpHeaders(JSONObject(it)) }.getOrNull()
            }
            FastYoutubeStream(
                title = title,
                streamUrl = streamUrl,
                formatId = formatId,
                isVideo = vcodec != "none",
                httpUserAgent = headers?.first,
                httpReferer = headers?.second
            )
        } else {
            null
        }
    } catch (t: Throwable) {
        CrashReporter.recordHandled(context, "Fast YouTube stream", t)
        null
    }
}
internal fun createFastYoutubeLibraryItem(stream: FastYoutubeStream, sourceUrl: String): LibraryItem {
    return LibraryItem(
        id = UUID.randomUUID().toString(),
        title = stream.title,
        mediaUri = stream.streamUrl,
        subtitleUri = null,
        progress = 0L,
        duration = 0L,
        isVideo = stream.isVideo,
        sourceUrl = sourceUrl,
        formatId = stream.formatId,
        httpUserAgent = stream.httpUserAgent,
        httpReferer = stream.httpReferer
    )
}
private fun addWebViewCookiesOption(context: Context, request: YoutubeDLRequest, sourceUrl: String) {
    if (!isYoutubeUrl(sourceUrl)) return
    val cookieFile = exportWebViewCookiesForYtDlp(context) ?: return
    request.addOption("--cookies", cookieFile.absolutePath)
}
private fun exportWebViewCookiesForYtDlp(context: Context): File? {
    return try {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()

        val cookieSources = listOf(
            "https://www.youtube.com/" to ".youtube.com",
            "https://m.youtube.com/" to ".youtube.com",
            "https://youtube.com/" to ".youtube.com",
            "https://accounts.youtube.com/" to ".youtube.com",
            "https://www.google.com/" to ".google.com",
            "https://accounts.google.com/" to ".google.com"
        )

        val lines = mutableListOf("# Netscape HTTP Cookie File")
        val seen = mutableSetOf<String>()

        cookieSources.forEach { (url, domain) ->
            val rawCookies = cookieManager.getCookie(url).orEmpty()
            rawCookies.split(";")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { cookie ->
                    val separatorIndex = cookie.indexOf('=')
                    if (separatorIndex <= 0) return@forEach

                    val name = cookie.substring(0, separatorIndex).trim()
                    val value = cookie.substring(separatorIndex + 1).trim()
                    if (name.isBlank()) return@forEach

                    val key = "$domain\t$name"
                    if (seen.add(key)) {
                        lines += listOf(domain, "TRUE", "/", "TRUE", "0", name, value).joinToString("\t")
                    }
                }
        }

        if (lines.size == 1) return null

        File(context.cacheDir, "yt-dlp-webview-cookies.txt").also { file ->
            file.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        CrashReporter.recordHandled(context, "Export WebView cookies", t)
        null
    }
}
internal class YoutubeBrowserBridge(
    private val onVideoUrl: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun openVideo(url: String?) {
        val videoUrl = url?.takeIf { it.isNotBlank() } ?: return
        mainHandler.post { onVideoUrl(videoUrl) }
    }
}
internal fun youtubeBrowserInterceptScript(): String {
    return """
        (function() {
            if (window.__rougoYoutubeBridgeInstalled) return;
            window.__rougoYoutubeBridgeInstalled = true;

            function isVideoUrl(rawUrl) {
                try {
                    var url = new URL(rawUrl, window.location.href);
                    var host = url.hostname.toLowerCase();
                    var path = url.pathname || "";
                    return ((host === "youtu.be" || host.endsWith(".youtu.be")) && path.length > 1) ||
                        (host.indexOf("youtube.com") !== -1 &&
                            (url.searchParams.get("v") || /^\/(shorts|live|embed)\//.test(path)));
                } catch (e) {
                    return false;
                }
            }

            function openInRougo(rawUrl) {
                if (!isVideoUrl(rawUrl) || !window.RougoYoutube) return false;
                window.RougoYoutube.openVideo(new URL(rawUrl, window.location.href).href);
                return true;
            }

            document.addEventListener("click", function(event) {
                var node = event.target;
                while (node && node.tagName !== "A") node = node.parentElement;
                if (node && node.href && openInRougo(node.href)) {
                    event.preventDefault();
                    event.stopPropagation();
                    event.stopImmediatePropagation();
                }
            }, true);

            var pushState = history.pushState;
            history.pushState = function() {
                var result = pushState.apply(this, arguments);
                setTimeout(function() { openInRougo(window.location.href); }, 0);
                return result;
            };

            var replaceState = history.replaceState;
            history.replaceState = function() {
                var result = replaceState.apply(this, arguments);
                setTimeout(function() { openInRougo(window.location.href); }, 0);
                return result;
            };

            window.addEventListener("popstate", function() {
                setTimeout(function() { openInRougo(window.location.href); }, 0);
            });

            openInRougo(window.location.href);
        })();
    """.trimIndent()
}
internal fun stopYoutubeWebViewPlayback(webView: WebView?) {
    webView?.stopLoading()
    webView?.evaluateJavascript(
        """
            (function() {
                document.querySelectorAll("video, audio").forEach(function(media) {
                    try {
                        media.pause();
                        media.removeAttribute("src");
                        media.load();
                    } catch (e) {}
                });
            })();
        """.trimIndent(),
        null
    )
}
internal fun fetchYoutubeSetupData(context: Context, url: String): YoutubeSetupData {
    val json = fetchYoutubeInfoJson(context, url)
    val headers = parseStreamHttpHeaders(json)
    val isYoutubeSource = isYoutubeUrl(url)
    val title = cleanYtdlpTitle(
        firstCleanMetadataValue(
            json.optString("fulltitle"),
            json.optString("title"),
            json.optString("alt_title")
        ),
        url
    ) ?: sourceDefaultTitle(url)

    return YoutubeSetupData(
        title = title,
        fallbackUrl = json.optString("url").takeIf { it.isNotBlank() },
        formats = parseYoutubeFormats(json),
        subtitleChoices = if (isYoutubeSource) parseYoutubeSubtitleChoices(json) else emptyList(),
        thumbnailUrl = bestThumbnailUrl(json),
        httpUserAgent = headers.first,
        httpReferer = headers.second
    )
}
private fun parseStreamHttpHeaders(json: JSONObject): Pair<String?, String?> {
    val headers = json.optJSONObject("http_headers")
    val userAgent = headers?.optCleanString("User-Agent")
        ?: headers?.optCleanString("user-agent")
        ?: json.optCleanString("user_agent")
    val referer = headers?.optCleanString("Referer")
        ?: headers?.optCleanString("referer")
        ?: headers?.optCleanString("Referrer")
    return userAgent to referer
}
internal suspend fun createYoutubeLibraryItem(
    context: Context,
    setupData: YoutubeSetupData?,
    format: YoutubeStreamFormat,
    subtitleUri: String?,
    sourceUrl: String
): LibraryItem = withContext(Dispatchers.IO) {
    val itemId = UUID.randomUUID().toString()
    val coverArtPath = setupData?.thumbnailUrl?.let { downloadRemoteCover(context, itemId, it) }
    LibraryItem(
        id = itemId,
        title = setupData?.title ?: "YouTube Stream",
        mediaUri = sourceUrl,
        subtitleUri = subtitleUri,
        progress = 0L,
        duration = 0L,
        isVideo = format.vcodec != "none",
        sourceUrl = sourceUrl,
        formatId = format.formatId,
        coverArtPath = coverArtPath,
        httpUserAgent = setupData?.httpUserAgent,
        httpReferer = setupData?.httpReferer
    )
}
private fun bestThumbnailUrl(json: JSONObject): String? {
    val thumbnails = json.optJSONArray("thumbnails")
    if (thumbnails != null && thumbnails.length() > 0) {
        var bestUrl: String? = null
        var bestArea = -1
        for (i in 0 until thumbnails.length()) {
            val item = thumbnails.optJSONObject(i) ?: continue
            val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
            val area = item.optInt("width", 0) * item.optInt("height", 0)
            if (area >= bestArea) {
                bestUrl = url
                bestArea = area
            }
        }
        if (!bestUrl.isNullOrBlank()) return bestUrl
    }
    return json.optString("thumbnail").takeIf { it.isNotBlank() }
}
internal fun downloadRemoteCover(context: Context, itemId: String, url: String): String? {
    return try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (connection.responseCode !in 200..299) return null
        val bytes = connection.inputStream.use { it.readBytes() }
        if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) return null
        cacheCoverBytes(context, itemId, bytes)
    } catch (e: Exception) {
        null
    }
}
internal fun youtubeResolutionLabel(key: String): String {
    return YOUTUBE_RESOLUTION_OPTIONS.firstOrNull { it.key == key }?.label ?: "Ask every time"
}
internal fun hasPlayerNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
private fun createPlayerNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(PLAYER_NOTIFICATION_CHANNEL_ID) != null) return

    val channel = NotificationChannel(
        PLAYER_NOTIFICATION_CHANNEL_ID,
        "Player controls",
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = "Playback controls for 朗語"
        setShowBadge(false)
    }
    manager.createNotificationChannel(channel)
}
private fun playerNotificationPendingIntent(context: Context, action: String): PendingIntent {
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    val intent = Intent(action).setPackage(context.packageName)
    return PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
}
internal fun showPlayerNotification(
    context: Context,
    title: String,
    subtitle: String?,
    coverArtPath: String?,
    currentPos: Long,
    duration: Long,
    isPlaying: Boolean,
    skipSeconds: Int
) {
    if (!hasPlayerNotificationPermission(context)) return
    createPlayerNotificationChannel(context)

    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    val contentIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        flags
    )
    val timeText = "${formatTime(currentPos)} / ${formatTime(duration)}"
    val largeIcon = coverArtPath?.let { decodeSampledBitmapFile(it, maxSize = 512) }

    val notification = NotificationCompat.Builder(context, PLAYER_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(title)
        .setContentText(subtitle ?: timeText)
        .setSubText(timeText)
        .setContentIntent(contentIntent)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setOngoing(isPlaying)
        .also { builder ->
            if (largeIcon != null) builder.setLargeIcon(largeIcon)
        }
        .addAction(
            android.R.drawable.ic_media_rew,
            "-${skipSeconds}s",
            playerNotificationPendingIntent(context, ACTION_PLAYER_REWIND)
        )
        .addAction(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "Pause" else "Play",
            playerNotificationPendingIntent(context, ACTION_PLAYER_PLAY_PAUSE)
        )
        .addAction(
            android.R.drawable.ic_media_ff,
            "+${skipSeconds}s",
            playerNotificationPendingIntent(context, ACTION_PLAYER_FORWARD)
        )
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            playerNotificationPendingIntent(context, ACTION_PLAYER_STOP)
        )
        .build()

    try {
        NotificationManagerCompat.from(context).notify(PLAYER_NOTIFICATION_ID, notification)
    } catch (e: SecurityException) {
        e.printStackTrace()
    }
}
internal fun cancelPlayerNotification(context: Context) {
    try {
        NotificationManagerCompat.from(context).cancel(PLAYER_NOTIFICATION_ID)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
internal fun selectPreferredYoutubeSubtitle(choices: List<YoutubeSubtitleChoice>, preferredLanguage: String): YoutubeSubtitleChoice? {
    if (choices.isEmpty()) return null
    if (preferredLanguage == "any") return choices.firstOrNull()

    val normalized = preferredLanguage.lowercase(Locale.US)
    return choices.firstOrNull { choice ->
        val code = choice.languageCode.lowercase(Locale.US)
        code == normalized || code.startsWith("$normalized-")
    } ?: choices.firstOrNull()
}
private fun youtubeSubtitleChoiceKey(choice: YoutubeSubtitleChoice): String {
    return "${choice.languageCode}:${choice.isAutoGenerated}"
}
private fun preferredYoutubeSubtitleCandidates(
    choices: List<YoutubeSubtitleChoice>,
    preferredLanguage: String
): List<YoutubeSubtitleChoice> {
    val preferredChoice = selectPreferredYoutubeSubtitle(choices, preferredLanguage) ?: return emptyList()
    val normalizedPreference = preferredLanguage.lowercase(Locale.US)
    val preferredLanguageMatches = if (normalizedPreference == "any") {
        emptyList()
    } else {
        choices.filter { choice ->
            val code = choice.languageCode.lowercase(Locale.US)
            code == normalizedPreference || code.startsWith("$normalizedPreference-")
        }
    }
    return (listOf(preferredChoice) +
        choices.filter { it.languageCode == preferredChoice.languageCode } +
        preferredLanguageMatches +
        choices.take(2))
        .distinctBy { youtubeSubtitleChoiceKey(it) }
}
internal fun downloadPreferredYoutubeSubtitle(
    context: Context,
    url: String,
    choices: List<YoutubeSubtitleChoice>,
    preferredLanguage: String
): String? {
    preferredYoutubeSubtitleCandidates(choices, preferredLanguage).forEach { choice ->
        val subtitleUri = downloadYoutubeSubtitle(context, url, choice.languageCode, choice.isAutoGenerated)
        if (subtitleUri != null) return subtitleUri
    }
    return null
}
internal fun selectPreferredYoutubeFormat(
    formats: List<YoutubeStreamFormat>,
    preferredResolution: String
): YoutubeStreamFormat? {
    val playable = formats.filter { !it.url.isNullOrBlank() || !it.manifestUrl.isNullOrBlank() }
    val audioFormats = playable
        .filter { it.vcodec == "none" && it.acodec != "none" }
        .sortedWith(compareByDescending<YoutubeStreamFormat> { if (it.isVlcFriendlyAudioFormat()) 1 else 0 }.thenByDescending { it.tbr }.thenByDescending { it.formatId ?: "" })
    val videoFormats = playable
        .filter { it.vcodec != "none" && it.acodec != "none" }
        .sortedWith(compareByDescending<YoutubeStreamFormat> { it.height }.thenByDescending { if (it.isVlcFriendlyVideoFormat()) 1 else 0 }.thenByDescending { it.tbr })

    return when (preferredResolution) {
        YOUTUBE_RESOLUTION_AUDIO -> audioFormats.firstOrNull() ?: videoFormats.firstOrNull()
        YOUTUBE_RESOLUTION_HIGHEST -> videoFormats.firstOrNull() ?: audioFormats.firstOrNull()
        else -> {
            val targetHeight = preferredResolution.toIntOrNull()
            if (targetHeight == null) {
                videoFormats.firstOrNull() ?: audioFormats.firstOrNull()
            } else {
                videoFormats.firstOrNull { it.height in 1..targetHeight }
                    ?: videoFormats.lastOrNull()
                    ?: audioFormats.firstOrNull()
            }
        }
    }
}
internal fun resolveYoutubeStreamUrl(context: Context, url: String, formatId: String?): String? {
    if (!ensureMediaToolsReady(context)) return null
    val request = addFastYoutubeOptions(context, YoutubeDLRequest(url), url)
    if (formatId != null) {
        request.addOption("-f", formatId)
    } else {
        request.addOption("-f", "best[vcodec!=none][acodec!=none][ext=mp4]/best[vcodec!=none][acodec!=none]/best")
    }
    request.addOption("--print", "url")
    return try {
        val response = YoutubeDL.getInstance().execute(request, null, false)
        response.out.lineSequence().firstOrNull { it.trim().startsWith("http") }?.trim()
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
private fun fetchYoutubeInfoJson(context: Context, url: String): JSONObject {
    check(ensureMediaToolsReady(context)) { "Media tools are unavailable." }
    val request = addFastYoutubeOptions(context, YoutubeDLRequest(url), url)
    request.addOption("--dump-json")

    val response = YoutubeDL.getInstance().execute(request, null, false)
    return JSONObject(extractDumpedJson(response.out))
}
internal fun downloadYoutubeSubtitle(context: Context, url: String, languageCode: String, isAutoGenerated: Boolean): String? {
    if (!isYoutubeUrl(url)) return null
    if (!ensureMediaToolsReady(context)) return null
    val destDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "RougoSubs")
    destDir.mkdirs()

    val fileId = UUID.randomUUID().toString()
    val request = addFastYoutubeOptions(context, YoutubeDLRequest(url), url)
    request.addOption(if (isAutoGenerated) "--write-auto-subs" else "--write-subs")
    request.addOption("--sub-langs", languageCode)
    request.addOption("--sub-format", "vtt/srt/best")
    request.addOption("--convert-subs", "srt")
    request.addOption("-o", "${destDir.absolutePath}/$fileId.%(ext)s")

    return try {
        YoutubeDL.getInstance().execute(request, fileId, false)
        val subtitleUri = findDownloadedSubtitle(destDir, fileId)?.let { Uri.fromFile(it).toString() }
        if (subtitleUri == null && !isAutoGenerated) {
            downloadYoutubeSubtitle(context, url, languageCode, true)
        } else {
            subtitleUri
        }
    } catch (e: Exception) {
        e.printStackTrace()
        if (!isAutoGenerated) {
            downloadYoutubeSubtitle(context, url, languageCode, true)
        } else {
            null
        }
    }
}
internal fun downloadVideoLinkToLibraryItem(context: Context, url: String, existingItem: LibraryItem? = null): LibraryItem? {
    if (!ensureMediaToolsReady(context)) return null

    val setupData = runCatching { fetchYoutubeSetupData(context, url) }.getOrNull()
    val itemId = existingItem?.id ?: UUID.randomUUID().toString()
    val destDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "RougoDownloads").apply { mkdirs() }
    val fileId = itemId.replace(Regex("[^A-Za-z0-9_-]"), "_")

    val request = addFastYoutubeOptions(context, YoutubeDLRequest(url), url, skipDownload = false)
    request.addOption("-f", "bestvideo[height<=720]+bestaudio/best[height<=720]/best")
    request.addOption("--merge-output-format", "mp4")
    request.addOption("-o", "${destDir.absolutePath}/$fileId.%(title)s.%(ext)s")

    return try {
        YoutubeDL.getInstance().execute(request, fileId, false)
        val downloadedFile = destDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(fileId) && it.length() > 0L && !it.name.endsWith(".part") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null

        val mediaUri = Uri.fromFile(downloadedFile)
        val metadata = extractMediaMetadata(context, mediaUri, itemId, isVideo = true)
        val fallbackTitle = cleanOptionalYtdlpTitle(setupData?.title, url)
            ?: existingItem?.title?.takeIf { !looksLikeGeneratedFileId(it) }
            ?: titleFromDownloadedFile(downloadedFile, fileId, url)
            ?: sourceDefaultTitle(url)
        val baseItem = LibraryItem(
            id = itemId,
            title = fallbackTitle,
            mediaUri = mediaUri.toString(),
            subtitleUri = existingItem?.subtitleUri,
            progress = existingItem?.progress ?: 0L,
            duration = metadata.durationMs ?: 0L,
            isVideo = true,
            sourceUrl = existingItem?.sourceUrl ?: url,
            formatId = existingItem?.formatId,
            recordings = existingItem?.recordings ?: emptyList(),
            coverArtPath = metadata.coverArtPath
                ?: setupData?.thumbnailUrl?.let { downloadRemoteCover(context, itemId, it) }
                ?: existingItem?.coverArtPath,
            httpUserAgent = existingItem?.httpUserAgent ?: setupData?.httpUserAgent,
            httpReferer = existingItem?.httpReferer ?: setupData?.httpReferer
        )
        mergeMetadataIntoItem(baseItem, metadata, fallbackTitle)
    } catch (t: Throwable) {
        t.printStackTrace()
        CrashReporter.recordHandled(context, "Download video link", t)
        null
    }
}
private fun findDownloadedSubtitle(destDir: File, fileId: String): File? {
    return destDir.listFiles()
        ?.filter {
            val ext = it.extension.lowercase(Locale.US)
            it.name.startsWith(fileId) && it.length() > 0L && (ext == "srt" || ext == "vtt" || ext == "ass")
        }
        ?.maxWithOrNull(compareBy<File> {
            when (it.extension.lowercase(Locale.US)) {
                "srt" -> 3
                "vtt" -> 2
                else -> 1
            }
        }.thenBy { it.lastModified() })
}
private fun extractDumpedJson(output: String): String {
    val trimmed = output.trim()
    if (trimmed.startsWith("{")) return trimmed

    return output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("{") && it.endsWith("}") }
        ?: trimmed
}
private fun parseYoutubeSubtitleChoices(json: JSONObject): List<YoutubeSubtitleChoice> {
    val choices = linkedMapOf<String, YoutubeSubtitleChoice>()

    fun addChoices(groupName: String, isAutoGenerated: Boolean) {
        val group = json.optJSONObject(groupName) ?: return
        val keys = group.keys()
        while (keys.hasNext()) {
            val languageCode = keys.next()
            if (languageCode == "live_chat") continue
            val tracks = group.optJSONArray(languageCode)
            if (tracks == null || tracks.length() == 0) continue

            val firstTrack = tracks.optJSONObject(0)
            val trackName = firstTrack?.optString("name").orEmpty().takeIf { it.isNotBlank() }
            val localeName = Locale.forLanguageTag(languageCode).displayName.takeIf { it.isNotBlank() && it != languageCode }
            val languageName = trackName ?: localeName ?: languageCode
            val label = if (isAutoGenerated) {
                "$languageName ($languageCode, Auto)"
            } else {
                "$languageName ($languageCode)"
            }
            choices["$languageCode:$isAutoGenerated"] = YoutubeSubtitleChoice(label, languageCode, isAutoGenerated)
        }
    }

    addChoices("subtitles", false)
    addChoices("automatic_captions", true)

    fun languagePriority(choice: YoutubeSubtitleChoice): Int {
        val code = choice.languageCode.lowercase(Locale.US)
        return when {
            code == "ja" || code.startsWith("ja-") -> 0
            code == "en" || code.startsWith("en-") -> 1
            else -> 2
        }
    }

    return choices.values.sortedWith(
        compareBy<YoutubeSubtitleChoice> { languagePriority(it) }
            .thenBy { it.isAutoGenerated }
            .thenBy { it.label.lowercase(Locale.US) }
    )
}
private fun parseYoutubeFormats(json: JSONObject): List<YoutubeStreamFormat> {
    val formatsJson = json.optJSONArray("formats") ?: return emptyList()
    val formats = mutableListOf<YoutubeStreamFormat>()

    for (i in 0 until formatsJson.length()) {
        val format = formatsJson.optJSONObject(i) ?: continue
        formats.add(
            YoutubeStreamFormat(
                formatId = format.optString("format_id").takeIf { it.isNotBlank() },
                formatNote = format.optString("format_note").takeIf { it.isNotBlank() },
                ext = format.optString("ext").takeIf { it.isNotBlank() },
                vcodec = format.optString("vcodec").takeIf { it.isNotBlank() },
                acodec = format.optString("acodec").takeIf { it.isNotBlank() },
                height = format.optInt("height", 0),
                tbr = format.optDouble("tbr", 0.0).toInt(),
                url = format.optString("url").takeIf { it.isNotBlank() },
                manifestUrl = format.optString("manifest_url").takeIf { it.isNotBlank() },
                protocol = format.optString("protocol").takeIf { it.isNotBlank() }
            )
        )
    }

    return formats
}
internal fun initialPlayableMediaUri(item: LibraryItem): String? {
    val mediaUri = item.mediaUri.trim()
    if (mediaUri.isBlank()) return null
    if (item.sourceUrl == null) return mediaUri
    if (item.hasDownloadedLocalCopy()) return mediaUri
    if (mediaUri != item.sourceUrl.trim()) return mediaUri
    return null
}
private fun YoutubeStreamFormat.bestPlaybackUrl(): String? {
    val manifest = manifestUrl?.takeIf { it.isNotBlank() }
    val directUrl = url?.takeIf { it.isNotBlank() }
    val protocolValue = protocol?.lowercase(Locale.US).orEmpty()
    return when {
        protocolValue.contains("m3u8") -> directUrl ?: manifest
        protocolValue.contains("dash") -> manifest ?: directUrl
        isVlcFriendlyVideoFormat() || isVlcFriendlyAudioFormat() -> directUrl ?: manifest
        else -> manifest ?: directUrl
    }
}
internal fun YoutubeStreamFormat.isVlcFriendlyVideoFormat(): Boolean {
    if (vcodec == "none" || acodec == "none") return false
    val extValue = ext?.lowercase(Locale.US).orEmpty()
    val videoCodec = vcodec?.lowercase(Locale.US).orEmpty()
    val audioCodec = acodec?.lowercase(Locale.US).orEmpty()
    return extValue == "mp4" &&
        (videoCodec.startsWith("avc") || videoCodec.startsWith("h264")) &&
        (audioCodec.startsWith("mp4a") || audioCodec.startsWith("aac"))
}
internal fun YoutubeStreamFormat.isVlcFriendlyAudioFormat(): Boolean {
    if (vcodec != "none" || acodec == "none") return false
    val extValue = ext?.lowercase(Locale.US).orEmpty()
    val audioCodec = acodec?.lowercase(Locale.US).orEmpty()
    return extValue == "m4a" || extValue == "mp4" || audioCodec.startsWith("mp4a") || audioCodec.startsWith("aac")
}
