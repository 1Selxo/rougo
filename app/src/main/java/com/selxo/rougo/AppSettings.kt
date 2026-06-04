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

internal data class YoutubeResolutionOption(val key: String, val label: String)
internal data class AccentOption(val key: String, val label: String, val darkColor: Color, val lightColor: Color)
enum class LibraryDownloadState { Idle, Loading, Complete }
internal const val PREF_YOUTUBE_RESOLUTION = "youtube_preferred_resolution"
internal const val PREF_YOUTUBE_AUTO_SUBTITLES = "youtube_auto_subtitles"
internal const val PREF_YOUTUBE_SUBTITLE_LANGUAGE = "youtube_subtitle_language"
internal const val PREF_SKIP_SECONDS = "player_skip_seconds"
internal const val PREF_SUBTITLE_OFFSET_MS = "subtitle_offset_ms"
internal const val PREF_LIGHT_MODE = "app_light_mode"
internal const val PREF_THEME_MODE = "app_theme_mode"
internal const val PREF_ACCENT_COLOR = "app_accent_color"
internal const val DEFAULT_SKIP_SECONDS = 5
internal const val DEFAULT_SUBTITLE_OFFSET_MS = 0L
internal const val THEME_DARK = "dark"
internal const val THEME_BLACK = "black"
internal const val THEME_LIGHT = "light"
internal const val THEME_SYSTEM = "system"
internal const val YOUTUBE_RESOLUTION_ASK = "ask"
internal const val YOUTUBE_RESOLUTION_HIGHEST = "highest"
internal const val YOUTUBE_RESOLUTION_AUDIO = "audio"
internal const val DEFAULT_YOUTUBE_RESOLUTION = "720"
internal const val DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE = "ja"
internal const val PLAYER_NOTIFICATION_CHANNEL_ID = "rougo_player_controls"
internal const val PLAYER_NOTIFICATION_ID = 2407
internal const val ACTION_PLAYER_PLAY_PAUSE = "com.selxo.rougo.action.PLAY_PAUSE"
internal const val ACTION_PLAYER_REWIND = "com.selxo.rougo.action.REWIND"
internal const val ACTION_PLAYER_FORWARD = "com.selxo.rougo.action.FORWARD"
internal const val ACTION_PLAYER_STOP = "com.selxo.rougo.action.STOP"
internal val YOUTUBE_RESOLUTION_OPTIONS = listOf(
    YoutubeResolutionOption("720", "720p"),
    YoutubeResolutionOption(YOUTUBE_RESOLUTION_ASK, "Ask every time"),
    YoutubeResolutionOption("480", "480p"),
    YoutubeResolutionOption("1080", "1080p"),
    YoutubeResolutionOption(YOUTUBE_RESOLUTION_HIGHEST, "Highest available"),
    YoutubeResolutionOption(YOUTUBE_RESOLUTION_AUDIO, "Audio only")
)
internal val YOUTUBE_SUBTITLE_LANGUAGE_OPTIONS = listOf(
    YoutubeResolutionOption("ja", "Japanese"),
    YoutubeResolutionOption("en", "English"),
    YoutubeResolutionOption("zh-Hant", "Chinese (Traditional)"),
    YoutubeResolutionOption("zh-Hans", "Chinese (Simplified)"),
    YoutubeResolutionOption("ko", "Korean"),
    YoutubeResolutionOption("es", "Spanish"),
    YoutubeResolutionOption("any", "Best available")
)
internal val THEME_MODE_OPTIONS = listOf(
    YoutubeResolutionOption(THEME_DARK, "Dark"),
    YoutubeResolutionOption(THEME_BLACK, "Black (OLED)"),
    YoutubeResolutionOption(THEME_LIGHT, "Light"),
    YoutubeResolutionOption(THEME_SYSTEM, "System")
)
internal val ACCENT_OPTIONS = listOf(
    AccentOption("purple", "Purple", Color(0xFFAEB2FF), Color(0xFF585DDB)),
    AccentOption("red", "Red", Color(0xFFFF8A80), Color(0xFFC62828)),
    AccentOption("pink", "Pink", Color(0xFFFF8FD8), Color(0xFFAD1457)),
    AccentOption("orange", "Orange", Color(0xFFFFB86B), Color(0xFFBF5F00)),
    AccentOption("yellow", "Yellow", Color(0xFFFFD75E), Color(0xFF8A6D00)),
    AccentOption("green", "Green", Color(0xFF7EE787), Color(0xFF1B7F38)),
    AccentOption("teal", "Teal", Color(0xFF64D8CB), Color(0xFF00796B)),
    AccentOption("blue", "Blue", Color(0xFF8AB4FF), Color(0xFF1565C0)),
    AccentOption("indigo", "Indigo", Color(0xFF9FA8FF), Color(0xFF3949AB)),
    AccentOption("gray", "Gray", Color(0xFFCBD5E1), Color(0xFF475569))
)
internal val PLAYER_NOTIFICATION_ACTIONS = arrayOf(
    ACTION_PLAYER_PLAY_PAUSE,
    ACTION_PLAYER_REWIND,
    ACTION_PLAYER_FORWARD,
    ACTION_PLAYER_STOP
)
private val RougoDarkColorScheme = darkColorScheme(
    background = Color(0xFF141419),
    surface = Color(0xFF222228),
    surfaceVariant = Color(0xFF2D2D36),
    primary = Color(0xFFAEB2FF),
    secondary = Color(0xFF73D6C9),
    tertiary = Color(0xFFFFB08A),
    onBackground = Color(0xFFF7F7FB),
    onSurface = Color(0xFFF7F7FB),
    onSurfaceVariant = Color(0xFFC7C7D1),
    onPrimary = Color(0xFF141419)
)
private val RougoLightColorScheme = lightColorScheme(
    background = Color(0xFFF7F7FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EAF4),
    primary = Color(0xFF585DDB),
    secondary = Color(0xFF067A71),
    tertiary = Color(0xFFB85F2E),
    onBackground = Color(0xFF171720),
    onSurface = Color(0xFF171720),
    onSurfaceVariant = Color(0xFF5D6070),
    onPrimary = Color.White
)
private val RougoBlackColorScheme = darkColorScheme(
    background = Color.Black,
    surface = Color(0xFF111116),
    surfaceVariant = Color(0xFF1C1C22),
    primary = Color(0xFFAEB2FF),
    secondary = Color(0xFF73D6C9),
    tertiary = Color(0xFFFFB08A),
    onBackground = Color(0xFFF7F7FB),
    onSurface = Color(0xFFF7F7FB),
    onSurfaceVariant = Color(0xFFC7C7D1),
    onPrimary = Color.Black
)
internal fun rougoColorScheme(themeMode: String, accentKey: String, systemDark: Boolean): androidx.compose.material3.ColorScheme {
    val usesDarkSurfaces = when (themeMode) {
        THEME_LIGHT -> false
        THEME_SYSTEM -> systemDark
        else -> true
    }
    val base = when (themeMode) {
        THEME_BLACK -> RougoBlackColorScheme
        THEME_LIGHT -> RougoLightColorScheme
        THEME_SYSTEM -> if (systemDark) RougoDarkColorScheme else RougoLightColorScheme
        else -> RougoDarkColorScheme
    }
    val accent = ACCENT_OPTIONS.firstOrNull { it.key == accentKey } ?: ACCENT_OPTIONS.first()
    val primary = if (usesDarkSurfaces) accent.darkColor else accent.lightColor
    return base.copy(
        primary = primary,
        onPrimary = if (usesDarkSurfaces) Color(0xFF111116) else Color.White
    )
}
