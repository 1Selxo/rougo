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

class MainActivity : ComponentActivity() {
    private var sharedUrlState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(applicationContext)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        lifecycleScope.launch(Dispatchers.IO) {
            val engine = DictionaryEngine.getInstance(applicationContext)
            engine.loadDictionaries()
            val installed = engine.getInstalledDictionaries()
            if (installed.isEmpty() || installed.none { it.contains("JMdict", ignoreCase = true) }) {
                engine.downloadJmdict { }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                delay(500)
                if (!ensureMediaToolsReady(applicationContext)) return@launch
                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val lastUpdate = prefs.getLong("last_ytdlp_update", 0L)
                if (System.currentTimeMillis() - lastUpdate > 24 * 60 * 60 * 1000L) {
                    try {
                        YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
                        prefs.edit().putLong("last_ytdlp_update", System.currentTimeMillis()).apply()
                    } catch (t: Throwable) {
                        t.printStackTrace()
                        CrashReporter.recordHandled(applicationContext, "YoutubeDL update", t)
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                CrashReporter.recordHandled(applicationContext, "Media tools startup", t)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Media tools initialization failed.", Toast.LENGTH_LONG).show()
                }
            }
        }

        handleIntent(intent)

        setContent {
            val prefs = remember { getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
            var themeMode by remember {
                mutableStateOf(
                    prefs.getString(PREF_THEME_MODE, null)
                        ?: if (prefs.getBoolean(PREF_LIGHT_MODE, false)) THEME_LIGHT else THEME_DARK
                )
            }
            var accentColor by remember { mutableStateOf(prefs.getString(PREF_ACCENT_COLOR, "purple") ?: "purple") }
            val systemDark = isSystemInDarkTheme()
            val usesDarkSystemBars = themeMode == THEME_DARK || themeMode == THEME_BLACK || (themeMode == THEME_SYSTEM && systemDark)
            val systemBarStyle = if (usesDarkSystemBars) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            }

            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = systemBarStyle,
                    navigationBarStyle = systemBarStyle
                )
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !usesDarkSystemBars
                    isAppearanceLightNavigationBars = !usesDarkSystemBars
                }
            }

            MaterialTheme(colorScheme = rougoColorScheme(themeMode, accentColor, systemDark)) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.systemBarsPadding()) {
                        MainAppFlow(
                            sharedUrl = sharedUrlState.value,
                            onSharedUrlProcessed = { sharedUrlState.value = null },
                            themeMode = themeMode,
                            onThemeModeChanged = { mode ->
                                themeMode = mode
                                prefs.edit {
                                    putString(PREF_THEME_MODE, mode)
                                    putBoolean(PREF_LIGHT_MODE, mode == THEME_LIGHT)
                                }
                            },
                            accentColor = accentColor,
                            onAccentColorChanged = { accent ->
                                accentColor = accent
                                prefs.edit { putString(PREF_ACCENT_COLOR, accent) }
                            },
                            systemDark = systemDark
                        )
                        CrashReportDialog()
                        UpdateNotificationDialog()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = extractSharedVideoUrl(intent)

        if (url != null) {
            sharedUrlState.value = url
        }

        if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_VIEW) {
            clearConsumedShareIntent()
        }
    }

    private fun clearConsumedShareIntent() {
        setIntent(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
        })
    }

    private fun extractSharedVideoUrl(intent: Intent?): String? {
        if (intent == null) return null

        val candidates = mutableListOf<String>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                candidates += listOfNotNull(
                    intent.getStringExtra(Intent.EXTRA_TEXT),
                    intent.getStringExtra(Intent.EXTRA_SUBJECT),
                    intent.getStringExtra(Intent.EXTRA_TITLE)
                )

                intent.clipData?.let { clipData ->
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i)
                            ?.coerceToText(this)
                            ?.toString()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { candidates += it }
                    }
                }
            }

            Intent.ACTION_VIEW -> {
                candidates += listOfNotNull(
                    intent.dataString,
                    intent.data?.toString()
                )
            }

            else -> return null
        }

        val urls = candidates
            .flatMap { extractAllUrls(it) }
            .map { normalizeVideoUrlCandidate(it) }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .distinct()

        return urls.firstOrNull { isSupportedVideoLink(it) }
            ?: urls.firstOrNull()
    }
}
