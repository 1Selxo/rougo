package com.selxo.rougo
import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.ImportResult
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.FileProvider
import java.net.HttpURLConnection
import java.net.URL
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Size
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

// --- YT-DLP IMPORTS ---
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo

// --- IMPORT ALIASES ---
import android.media.MediaPlayer as AndroidMediaPlayer
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media as VLCMedia
import org.videolan.libvlc.MediaPlayer as VLCMediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

// ==========================================
// 1. DATA MODELS & DICTIONARY ENGINE
// ==========================================

data class DictEntry(
    val term: String,
    val deinflected: String,
    val reading: String,
    val definition: String,   // HTML or structured glossary
    val dictName: String,
    val pitchPositions: List<PitchInfo> = emptyList()
)

data class PitchInfo(val dictName: String, val position: Int)

data class ShadowRecording(
    val id: String = UUID.randomUUID().toString(),
    val filePath: String, val startTime: Long, val endTime: Long, val timestamp: Long = System.currentTimeMillis()
)

data class LibraryItem(
    val id: String, val title: String, val mediaUri: String,
    val subtitleUri: String?, var progress: Long, var duration: Long, val isVideo: Boolean,
    var recordings: List<ShadowRecording> = emptyList(),
    val sourceUrl: String? = null, val formatId: String? = null
)

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

object ImageCache {
    val cache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()
}

object VLCManager {
    @Volatile
    private var libVLC: LibVLC? = null

    fun getLibVLC(context: Context): LibVLC {
        return libVLC ?: synchronized(this) {
            libVLC ?: try {
                val options = arrayListOf(
                    "-vvv",
                    "--network-caching=150",
                    "--clock-jitter=0",
                    "--clock-synchro=0",
                    "--file-caching=150"
                )
                LibVLC(context.applicationContext, options).also { libVLC = it }
            } catch (e: Exception) {
                LibVLC(context.applicationContext).also { libVLC = it }
            }
        }
    }
}

class DictionaryEngine private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: DictionaryEngine? = null

        fun getInstance(context: Context): DictionaryEngine {
            return instance ?: synchronized(this) {
                instance ?: DictionaryEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences("hoshi_engine", Context.MODE_PRIVATE)

    private val dictsDir: File
        get() = File(context.filesDir, "hoshi_dicts").also { it.mkdirs() }

    fun isNoiseCancellationEnabled(): Boolean = prefs.getBoolean("noise_cancel", false)
    fun setNoiseCancellationEnabled(enabled: Boolean) = prefs.edit { putBoolean("noise_cancel", enabled) }

    fun getDictOrder(): List<String> {
        val json = prefs.getString("dict_order", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun saveDictOrder(order: List<String>) {
        prefs.edit { putString("dict_order", JSONArray(order).toString()) }
        loadDictionaries()
    }

    fun loadDictionaries() {
        val allFolders = dictsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val order = getDictOrder()

        val sortedFolders = allFolders.sortedBy { folder ->
            val idx = order.indexOf(folder.name)
            if (idx == -1) Int.MAX_VALUE else idx
        }

        val termPaths = mutableListOf<String>()
        val pitchPaths = mutableListOf<String>()

        sortedFolders.forEach { folder ->
            val isPitch = HoshiDicts.hasMetaModeEntries(folder.absolutePath, "pitch", 1)
            if (isPitch) {
                pitchPaths.add(folder.absolutePath)
            } else {
                termPaths.add(folder.absolutePath)
            }
        }

        HoshiDicts.rebuildQuery(
            session = HoshiDicts.lookupObject,
            termPaths = termPaths.toTypedArray(),
            freqPaths = emptyArray<String>(),
            pitchPaths = pitchPaths.toTypedArray()
        )
    }

    suspend fun downloadJmdict(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (getInstalledDictionaries().any { it.contains("JMdict", ignoreCase = true) }) return@withContext

        try {
            onProgress("Downloading JMdict...")
            val url = java.net.URL("https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_english.zip")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"

            val tempFile = File(context.cacheDir, "jmdict_download.zip")
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }

            onProgress("Importing JMdict...")
            val result = HoshiDicts.importDictionary(tempFile.absolutePath, dictsDir.absolutePath, false)
            tempFile.delete()

            if (result.success) {
                onProgress("JMdict imported successfully!")
                loadDictionaries()
            } else {
                onProgress("JMdict import failed.")
            }
            delay(2000)
            onProgress("")
        } catch (e: Exception) {
            e.printStackTrace()
            onProgress("JMdict download failed: ${e.message}")
            delay(3000)
            onProgress("")
        }
    }

    suspend fun importZip(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            onProgress("Copying dictionary file...")
            val tempFile = File(context.cacheDir, "dict_import_${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }

            onProgress("Importing dictionary (this may take a minute)...")
            val result: ImportResult = HoshiDicts.importDictionary(
                zipPath = tempFile.absolutePath,
                outputDir = dictsDir.absolutePath,
                lowRam = false
            )
            tempFile.delete()

            if (result.success) {
                onProgress("Successfully imported ${result.title}!")
                loadDictionaries()
            } else {
                onProgress("Import failed.")
            }
            delay(2000)
            onProgress("")
        } catch (e: Exception) {
            e.printStackTrace()
            onProgress("Import failed: ${e.message}")
            delay(3000)
            onProgress("")
        }
    }

    @Suppress("unused")
    fun getInstalledDictionaries(): List<String> {
        return dictsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    }

    @Suppress("unused")
    fun deleteDict(name: String) {
        File(dictsDir, name).deleteRecursively()
        loadDictionaries()
    }

    suspend fun searchPrefixes(queryStr: String): List<DictEntry> = withContext(Dispatchers.IO) {
        if (queryStr.isBlank()) return@withContext emptyList()

        try {
            val results: Array<LookupResult> = HoshiDicts.lookup(HoshiDicts.lookupObject, queryStr, 20, 16)
            results.flatMap { lookupResult ->
                val term = lookupResult.term
                val allPitches = term.pitches.flatMap { entry ->
                    entry.pitchPositions.map { pos -> PitchInfo(entry.dictName, pos) }
                }

                term.glossaries.map { glossary ->
                    DictEntry(
                        term = lookupResult.matched,
                        deinflected = lookupResult.deinflected,
                        reading = term.reading,
                        definition = glossary.glossary,
                        dictName = glossary.dictName,
                        pitchPositions = allPitches
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}

class LibraryManager(context: Context) {
    private val prefs = context.getSharedPreferences("rougo_library", Context.MODE_PRIVATE)

    fun getItems(): List<LibraryItem> {
        val jsonString = prefs.getString("items", "[]") ?: "[]"
        val jsonArray = JSONArray(jsonString)
        val list = mutableListOf<LibraryItem>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)

            val recordingsList = mutableListOf<ShadowRecording>()
            if (obj.has("recordings")) {
                val recArray = obj.getJSONArray("recordings")
                for (j in 0 until recArray.length()) {
                    val recObj = recArray.getJSONObject(j)
                    recordingsList.add(
                        ShadowRecording(
                            id = recObj.optString("id", UUID.randomUUID().toString()),
                            filePath = recObj.getString("filePath"),
                            startTime = recObj.getLong("startTime"),
                            endTime = recObj.getLong("endTime"),
                            timestamp = recObj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
            }

            list.add(LibraryItem(
                id = obj.getString("id"), title = obj.getString("title"), mediaUri = obj.getString("mediaUri"),
                subtitleUri = if (obj.has("subtitleUri") && obj.getString("subtitleUri").isNotEmpty()) obj.getString("subtitleUri") else null,
                progress = obj.getLong("progress"), duration = obj.getLong("duration"), isVideo = obj.getBoolean("isVideo"),
                recordings = recordingsList,
                sourceUrl = if (obj.has("sourceUrl") && obj.getString("sourceUrl").isNotEmpty()) obj.getString("sourceUrl") else null,
                formatId = if (obj.has("formatId") && obj.getString("formatId").isNotEmpty()) obj.getString("formatId") else null
            ))
        }
        return list
    }

    fun saveItem(item: LibraryItem) {
        val current = getItems().toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) current[index] = item else current.add(0, item)
        val array = JSONArray()
        current.forEach {
            val obj = JSONObject()
            obj.put("id", it.id); obj.put("title", it.title); obj.put("mediaUri", it.mediaUri)
            obj.put("subtitleUri", it.subtitleUri ?: ""); obj.put("progress", it.progress)
            obj.put("duration", it.duration); obj.put("isVideo", it.isVideo)
            obj.put("sourceUrl", it.sourceUrl ?: ""); obj.put("formatId", it.formatId ?: "")

            val recArray = JSONArray()
            it.recordings.forEach { rec ->
                val recObj = JSONObject()
                recObj.put("id", rec.id); recObj.put("filePath", rec.filePath)
                recObj.put("startTime", rec.startTime); recObj.put("endTime", rec.endTime)
                recObj.put("timestamp", rec.timestamp)
                recArray.put(recObj)
            }
            obj.put("recordings", recArray)
            array.put(obj)
        }
        prefs.edit { putString("items", array.toString()) }
    }

    fun deleteItem(id: String) {
        val current = getItems().filter { it.id != id }
        val array = JSONArray()
        current.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id); put("title", item.title); put("mediaUri", item.mediaUri)
                put("subtitleUri", item.subtitleUri ?: ""); put("progress", item.progress)
                put("duration", item.duration); put("isVideo", item.isVideo)
                put("sourceUrl", item.sourceUrl ?: ""); put("formatId", item.formatId ?: "")
                put("recordings", JSONArray())
            }
            array.put(obj)
        }
        prefs.edit { putString("items", array.toString()) }
    }
}

data class UpdateInfo(val tagName: String, val downloadUrl: String, val body: String)

suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
    try {
        val url = URL("https://api.github.com/repos/kaihouguide/rougo/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        
        if (conn.responseCode == 200) {
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val tagName = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            var downloadUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    downloadUrl = asset.getString("browser_download_url")
                    break
                }
            }
            val body = json.optString("body", "")
            if (downloadUrl.isNotEmpty()) {
                return@withContext UpdateInfo(tagName, downloadUrl, body)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    null
}

fun downloadAndInstallUpdate(context: Context, downloadUrl: String) {
    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val request = DownloadManager.Request(Uri.parse(downloadUrl))
        .setTitle("Rougo Update")
        .setDescription("Downloading latest version...")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "update.apk")
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)

    val downloadId = downloadManager.enqueue(request)

    val onComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == downloadId) {
                installApk(context)
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

fun installApk(context: Context) {
    val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
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

class MainActivity : ComponentActivity() {
    private var sharedUrlState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
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
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                try { YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.NIGHTLY) } catch (e: Exception) { e.printStackTrace() }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Initialization failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }

        handleIntent(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Color(0xFF141419), surface = Color(0xFF222228),
                primary = Color(0xFFAEB2FF), onPrimary = Color(0xFF141419)
            )) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.systemBarsPadding()) {
                        MainAppFlow(
                            sharedUrl = sharedUrlState.value,
                            onSharedUrlProcessed = { sharedUrlState.value = null }
                        )
                        UpdateNotificationDialog()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val url = extractUrl(text)
            if (url != null) {
                sharedUrlState.value = url
            }
        }
    }

    private fun extractUrl(text: String): String? {
        val regex = Regex("(?i)\\b((?:https?://|www\\d{0,3}[.]|[a-z0-9.\\-]+[.][a-z]{2,4}/)(?:[^\\s()<>]+|\\((?:[^\\s()<>]+|\\([^\\s()<>]+\\))*\\))+(?:\\((?:[^\\s()<>]+|\\([^\\s()<>]+\\))*\\)|[^\\s`!()\\[\\]{};:'\".,<>?«»“”‘’]))")
        return regex.find(text)?.value
    }
}

fun isNewerVersion(remoteTag: String, currentVersion: String?): Boolean {
    if (currentVersion == null) return true
    
    val remote = remoteTag.removePrefix("v").trim()
    val local = currentVersion.removePrefix("v").trim()
    
    if (remote == local) return false
    
    val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
    val localParts = local.split(".").mapNotNull { it.toIntOrNull() }
    
    val maxLength = maxOf(remoteParts.size, localParts.size)
    for (i in 0 until maxLength) {
        val r = remoteParts.getOrElse(i) { 0 }
        val l = localParts.getOrElse(i) { 0 }
        if (r > l) return true
        if (r < l) return false
    }
    return false
}

@Composable
fun UpdateNotificationDialog() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val lastChecked = prefs.getLong("last_update_check", 0L)
        if (System.currentTimeMillis() - lastChecked > 3600000L) { // Check every hour
            val info = checkForUpdates()
            if (info != null) {
                // Get current version name
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val currentVersion = pInfo.versionName
                if (isNewerVersion(info.tagName, currentVersion)) {
                    updateInfo = info
                    showDialog = true
                }
            }
            prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
        }
    }

    if (showDialog && updateInfo != null) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Update Available (${updateInfo?.tagName})", fontWeight = FontWeight.Bold) },
            text = { 
                Column {
                    Text("A new version of Rougo Reader is available. Update now to access new features and bug fixes.")
                    if (updateInfo?.body?.isNotEmpty() == true) {
                        Spacer(Modifier.height(8.dp))
                        Text(updateInfo!!.body, fontSize = 12.sp, color = Color.Gray)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showDialog = false
                    downloadAndInstallUpdate(context, updateInfo!!.downloadUrl)
                }) { Text("Update Now") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDialog = false
                }) { Text("Later") }
            }
        )
    }
}

enum class AppScreen { Library, Player, Settings, DictionarySettings }

@Composable
fun MainAppFlow(sharedUrl: String?, onSharedUrlProcessed: () -> Unit) {
    val context = LocalContext.current
    val libraryManager = remember { LibraryManager(context) }
    var currentScreen by remember { mutableStateOf(AppScreen.Library) }
    var activeItem by remember { mutableStateOf<LibraryItem?>(null) }
    var libraryItems by remember { mutableStateOf(libraryManager.getItems()) }

    if (sharedUrl != null) {
        YtStreamDialog(
            url = sharedUrl,
            onDismiss = onSharedUrlProcessed,
            onComplete = { newItem ->
                libraryManager.saveItem(newItem)
                libraryItems = libraryManager.getItems()
                onSharedUrlProcessed()
                activeItem = newItem
                currentScreen = AppScreen.Player
            }
        )
    }

    if (currentScreen == AppScreen.Library) {
        LibraryScreen(
            items = libraryItems,
            onRefresh = { libraryItems = libraryManager.getItems() },
            onItemClick = { item -> activeItem = item; currentScreen = AppScreen.Player },
            onDelete = { item ->
                libraryManager.deleteItem(item.id)
                libraryItems = libraryManager.getItems()
            },
            onOpenSettings = { currentScreen = AppScreen.Settings }
        )
    } else if (currentScreen == AppScreen.Settings) {
        SettingsScreen(
            onBack = { currentScreen = AppScreen.Library },
            onNavigateToDictionaries = { currentScreen = AppScreen.DictionarySettings }
        )
    } else if (currentScreen == AppScreen.DictionarySettings) {
        DictionarySettingsScreen(
            onBack = { currentScreen = AppScreen.Settings }
        )
    } else {
        activeItem?.let { item ->
            PlayerScreen(
                initialLibraryItem = item,
                onBack = { updatedItem ->
                    libraryManager.saveItem(updatedItem)
                    libraryItems = libraryManager.getItems()
                    currentScreen = AppScreen.Library
                    activeItem = null
                }
            )
        }
    }
}

@Composable
fun YtStreamDialog(url: String, onDismiss: () -> Unit, onComplete: (LibraryItem) -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Fetching video info...") }
    var videoInfo by remember { mutableStateOf<VideoInfo?>(null) }
    var selectedFormat by remember { mutableStateOf<VideoFormat?>(null) }
    var selectedSubtitleKey by remember { mutableStateOf<String?>(null) }
    var isAutoSub by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            try {
                val info = YoutubeDL.getInstance().getInfo(url)
                withContext(Dispatchers.Main) { videoInfo = info }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status = "Failed: ${e.localizedMessage}"
                    delay(3000)
                    onDismiss()
                }
            }
        }
    }

    LaunchedEffect(selectedFormat) {
        if (selectedFormat != null) {
            isProcessing = true
            status = "Resolving Stream..."

            val newItem = LibraryItem(
                id = UUID.randomUUID().toString(),
                title = videoInfo?.title ?: "YouTube Stream",
                mediaUri = selectedFormat?.url ?: videoInfo?.url ?: url,
                subtitleUri = null,
                progress = 0L,
                duration = 0L,
                isVideo = selectedFormat?.vcodec != "none",
                sourceUrl = url,
                formatId = selectedFormat?.formatId
            )

            onComplete(newItem)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val destDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "RougoSubs")
                    destDir.mkdirs()
                    val fileId = UUID.randomUUID().toString()

                    val request = YoutubeDLRequest(url)
                    request.addOption("--no-update")
                    request.addOption("--skip-download")

                    if (selectedSubtitleKey != null) {
                        if (isAutoSub) {
                            request.addOption("--write-auto-subs")
                        } else {
                            request.addOption("--write-subs")
                        }
                        request.addOption("--sub-langs", selectedSubtitleKey!!)
                    }

                    request.addOption("-o", "${destDir.absolutePath}/$fileId.%(ext)s")

                    YoutubeDL.getInstance().execute(request, fileId)

                    val subFile = destDir.listFiles()?.find { it.name.startsWith(fileId) && (it.extension.lowercase() == "srt" || it.extension.lowercase() == "vtt" || it.name.endsWith(".srt") || it.name.endsWith(".vtt")) }
                    if (subFile != null) {
                        val updatedItem = newItem.copy(subtitleUri = Uri.fromFile(subFile).toString())
                        LibraryManager(context).saveItem(updatedItem)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isProcessing && videoInfo == null) onDismiss() },
        title = { Text("Stream Setup", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (videoInfo == null || isProcessing) {
                    Text(status, color = Color.LightGray, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary, trackColor = Color.DarkGray
                    )
                } else {
                    Text("1. Select Subtitles (Optional):", color = Color.LightGray, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))

                    val subOptions = mutableListOf<Triple<String, String, Boolean>>()

                    try {
                        val subtitlesField = videoInfo?.javaClass?.getDeclaredField("subtitles")
                        subtitlesField?.isAccessible = true
                        val subtitles = subtitlesField?.get(videoInfo) as? Map<String, *>
                        subtitles?.forEach { (lang, _) -> subOptions.add(Triple(lang, lang, false)) }

                        val autoSubsField = videoInfo?.javaClass?.getDeclaredField("automaticCaptions")
                        autoSubsField?.isAccessible = true
                        val autoSubs = autoSubsField?.get(videoInfo) as? Map<String, *>
                        autoSubs?.forEach { (lang, _) -> subOptions.add(Triple("$lang (Auto)", lang, true)) }
                    } catch (e: Exception) { e.printStackTrace() }

                    LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                        items(subOptions) { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedSubtitleKey = if (selectedSubtitleKey == option.second && isAutoSub == option.third) null else option.second
                                        isAutoSub = option.third
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (selectedSubtitleKey == option.second && isAutoSub == option.third),
                                    onClick = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(option.first, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("2. Select Quality:", color = Color.LightGray, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    val formats = videoInfo?.formats?.filter {
                        (it.vcodec != "none" && it.acodec != "none") || (it.vcodec == "none" && it.acodec != "none")
                    }?.reversed() ?: emptyList()

                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(formats) { format ->
                            val isAudioOnly = format.vcodec == "none"
                            val resolutionText = if (isAudioOnly) "Audio Only" else {
                                format.height?.let { "${it}p" } ?: format.formatNote ?: "Standard"
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selectedFormat = format },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (isAudioOnly) Icons.Default.Audiotrack else Icons.Default.HighQuality, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "$resolutionText - ${format.ext}",
                                        fontWeight = FontWeight.Medium, color = Color.White, fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (videoInfo == null || isProcessing) {
                Button(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

// ==========================================
// 3. UI SCREENS
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(items: List<LibraryItem>, onRefresh: () -> Unit, onItemClick: (LibraryItem) -> Unit, onDelete: (LibraryItem) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { DictionaryEngine.getInstance(context) }
    var importStatus by remember { mutableStateOf("") }

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingMediaUri by remember { mutableStateOf<Uri?>(null) }
    var pendingTitle by remember { mutableStateOf("") }
    var isVideoType by remember { mutableStateOf(false) }

    val dictLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            CoroutineScope(Dispatchers.Main).launch {
                engine.importZip(context, uri) { status -> importStatus = status }
            }
        }
    }

    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            pendingMediaUri = uri
            val mime = context.contentResolver.getType(uri) ?: ""
            isVideoType = mime.startsWith("video/")
            pendingTitle = getFileName(context, uri)
            showAddDialog = true
        }
    }

    val subtitleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val newItem = LibraryItem(UUID.randomUUID().toString(), pendingTitle, pendingMediaUri.toString(), uri.toString(), 0L, 0L, isVideoType)
            LibraryManager(context).saveItem(newItem)
            showAddDialog = false
            onRefresh()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(onClick = { mediaLauncher.launch(arrayOf("audio/*", "video/*")) }, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.Black)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("My Library", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Row {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (importStatus.isNotEmpty()) {
                Text(importStatus, color = Color.Green, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Library is empty.\nTap + to add local media,\nor Share from YouTube directly.", color = Color.Gray, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(items) { item -> LibraryCard(item, onClick = { onItemClick(item) }, onDelete = { onDelete(item) }) }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Subtitles?") },
            text = { Text("Would you like to attach a subtitle file (.srt, .vtt, .ass) to '$pendingTitle'?") },
            confirmButton = { Button(onClick = { subtitleLauncher.launch(arrayOf("*/*")) }) { Text("Select Subtitles") } },
            dismissButton = {
                OutlinedButton(onClick = {
                    val newItem = LibraryItem(UUID.randomUUID().toString(), pendingTitle, pendingMediaUri.toString(), null, 0L, 0L, isVideoType)
                    LibraryManager(context).saveItem(newItem)
                    showAddDialog = false
                    onRefresh()
                }) { Text("Skip") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onNavigateToDictionaries: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { DictionaryEngine.getInstance(context) }
    var noiseCancelEnabled by remember { mutableStateOf(engine.isNoiseCancellationEnabled()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onNavigateToDictionaries() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Dictionaries", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Manage and import Yomitan dictionaries", color = Color.Gray, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Noise Cancellation", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Reduce background noise during shadowing", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(
                        checked = noiseCancelEnabled,
                        onCheckedChange = {
                            noiseCancelEnabled = it
                            engine.setNoiseCancellationEnabled(it)
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color.Gray)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Version", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("1.0.0 (Rougo Reader)", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionarySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { DictionaryEngine.getInstance(context) }
    var installedDicts by remember { mutableStateOf(engine.getInstalledDictionaries()) }
    var dictOrder by remember { mutableStateOf(engine.getDictOrder()) }
    var importStatus by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }

    val sortedDicts = remember(installedDicts, dictOrder) {
        installedDicts.sortedBy { name ->
            val idx = dictOrder.indexOf(name)
            if (idx == -1) Int.MAX_VALUE else idx
        }
    }

    fun moveDict(name: String, up: Boolean) {
        val currentOrder = sortedDicts.toMutableList()
        val index = currentOrder.indexOf(name)
        if (up && index > 0) {
            val tmp = currentOrder[index - 1]
            currentOrder[index - 1] = name
            currentOrder[index] = tmp
        } else if (!up && index < currentOrder.size - 1) {
            val tmp = currentOrder[index + 1]
            currentOrder[index + 1] = name
            currentOrder[index] = tmp
        }
        dictOrder = currentOrder
        engine.saveDictOrder(currentOrder)
    }

    val dictLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            isImporting = true
            CoroutineScope(Dispatchers.Main).launch {
                engine.importZip(context, uri) { status ->
                    importStatus = status
                    if (status.isEmpty()) {
                        isImporting = false
                        installedDicts = engine.getInstalledDictionaries()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Dictionaries") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { dictLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) }, enabled = !isImporting) {
                        Icon(Icons.Default.Add, contentDescription = "Import", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            if (isImporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                Text(importStatus, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(16.dp))
            }

            if (installedDicts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No dictionaries installed.\nTap + to import a Yomitan ZIP.", color = Color.Gray, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sortedDicts) { dictName ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Book, contentDescription = null, tint = Color.Gray)
                                Spacer(Modifier.width(12.dp))
                                Text(dictName, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.weight(1f))

                                Row {
                                    IconButton(onClick = { moveDict(dictName, true) }, enabled = sortedDicts.indexOf(dictName) > 0) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up", tint = if (sortedDicts.indexOf(dictName) > 0) Color.White else Color.DarkGray)
                                    }
                                    IconButton(onClick = { moveDict(dictName, false) }, enabled = sortedDicts.indexOf(dictName) < sortedDicts.size - 1) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down", tint = if (sortedDicts.indexOf(dictName) < sortedDicts.size - 1) Color.White else Color.DarkGray)
                                    }
                                    IconButton(onClick = {
                                        engine.deleteDict(dictName)
                                        installedDicts = engine.getInstalledDictionaries()
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(initialLibraryItem: LibraryItem, onBack: (LibraryItem) -> Unit) {
    var libraryItem by remember { mutableStateOf(initialLibraryItem) }
    val context = LocalContext.current
    val dictionaryEngine = remember { DictionaryEngine.getInstance(context) }
    var showDictQuery by remember { mutableStateOf<String?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPos by remember { mutableLongStateOf(libraryItem.progress) }
    var duration by remember { mutableLongStateOf(libraryItem.duration) }
    var isSubtitlesVisible by remember { mutableStateOf(libraryItem.subtitleUri != null || libraryItem.isVideo) }
    var subtitleDelayMs by remember { mutableLongStateOf(0L) }

    var parsedAudioCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var isParsingSubtitles by remember { mutableStateOf(libraryItem.subtitleUri != null) }
    var currentSubtitleText by remember { mutableStateOf("") }

    val albumArt = if (libraryItem.sourceUrl == null) loadAlbumArt(context, libraryItem.mediaUri, libraryItem.isVideo) else null

    var isRecording by remember { mutableStateOf(false) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordStartTime by remember { mutableLongStateOf(0L) }

    val recordings = remember { mutableStateListOf<ShadowRecording>().apply { addAll(libraryItem.recordings) } }
    var activeOriginalSegment by remember { mutableStateOf<ShadowRecording?>(null) }
    var activeBothSegment by remember { mutableStateOf<ShadowRecording?>(null) }

    var showBacklog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    var actualMediaUri by remember { mutableStateOf(if (libraryItem.sourceUrl == null) libraryItem.mediaUri else null) }
    var isRefreshingStream by remember { mutableStateOf(libraryItem.sourceUrl != null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val libVlc = remember { VLCManager.getLibVLC(context) }
    val vlcPlayer = remember { VLCMediaPlayer(libVlc) }
    var videoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }

    DisposableEffect(lifecycleOwner, videoLayout) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (libraryItem.isVideo && videoLayout != null) {
                    vlcPlayer.detachViews()
                    vlcPlayer.attachViews(videoLayout!!, null, false, false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(libraryItem) {
        val currentSourceUrl = libraryItem.sourceUrl
        if (currentSourceUrl != null && actualMediaUri == null) {
            isRefreshingStream = true
            withContext(Dispatchers.IO) {
                try {
                    val info = YoutubeDL.getInstance().getInfo(currentSourceUrl)
                    val targetFormat = info.formats?.find { it.formatId == libraryItem.formatId }
                    actualMediaUri = targetFormat?.url ?: info.url
                } catch (e: Exception) {
                    actualMediaUri = libraryItem.mediaUri
                }
            }
            isRefreshingStream = false
        }
    }

    fun syncWithStorage() {
        val updatedItem = libraryItem.copy(progress = currentPos, duration = duration, recordings = recordings.toList())
        LibraryManager(context).saveItem(updatedItem)
    }

    val voiceAudioPlayer = remember {
        AndroidMediaPlayer().apply {
            setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .build()
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { vlcPlayer.stop() } catch (e: Exception) {}
            try { vlcPlayer.detachViews() } catch (e: Exception) {}
            try { vlcPlayer.release() } catch (e: Exception) {}
            try { voiceAudioPlayer.release() } catch (e: Exception) {}
            try { mediaRecorder?.release() } catch (e: Exception) {}
        }
    }

    BackHandler {
        val updatedItem = libraryItem.copy(progress = currentPos, duration = duration, recordings = recordings.toList(), mediaUri = actualMediaUri ?: libraryItem.mediaUri)
        onBack(updatedItem)
    }

    LaunchedEffect(activeOriginalSegment) {
        activeOriginalSegment?.let { segment ->
            vlcPlayer.time = segment.startTime
            vlcPlayer.play()

            var seekWaitCount = 0
            while (Math.abs(vlcPlayer.time - segment.startTime) > 1500 && seekWaitCount < 40) {
                delay(50)
                seekWaitCount++
            }

            while (vlcPlayer.time < segment.endTime) { delay(50) }
            vlcPlayer.pause()
            activeOriginalSegment = null
        }
    }

    LaunchedEffect(activeBothSegment) {
        activeBothSegment?.let { segment ->
            vlcPlayer.time = segment.startTime
            vlcPlayer.play()

            var seekWaitCount = 0
            while (Math.abs(vlcPlayer.time - segment.startTime) > 1500 && seekWaitCount < 40) {
                delay(50)
                seekWaitCount++
            }

            try {
                voiceAudioPlayer.apply {
                    reset()
                    setDataSource(segment.filePath)
                    setOnPreparedListener { start() }
                    prepareAsync()
                }
            } catch (e: Exception) {}

            while (vlcPlayer.time < segment.endTime) { delay(50) }
            vlcPlayer.pause()
            activeBothSegment = null
        }
    }

    LaunchedEffect(libraryItem.subtitleUri) {
        if (libraryItem.subtitleUri != null) {
            isParsingSubtitles = true
            withContext(Dispatchers.IO) {
                parsedAudioCues = parseSimpleSubtitles(context, libraryItem.subtitleUri!!.toUri())
            }
            isParsingSubtitles = false
        }
    }

    LaunchedEffect(currentPos, parsedAudioCues, isSubtitlesVisible, subtitleDelayMs, isParsingSubtitles) {
        if (isSubtitlesVisible) {
            if (isParsingSubtitles) {
                currentSubtitleText = "Loading subtitles..."
            } else if (parsedAudioCues.isNotEmpty()) {
                val effectivePos = currentPos - subtitleDelayMs
                val activeCue = parsedAudioCues.find { effectivePos in it.startMs..it.endMs }
                currentSubtitleText = activeCue?.text ?: ""
            } else if (libraryItem.subtitleUri != null) {
                currentSubtitleText = "No valid text found in subtitle file."
            }
        } else {
            currentSubtitleText = ""
        }
    }

    DisposableEffect(actualMediaUri, videoLayout) {
        if (actualMediaUri == null) return@DisposableEffect onDispose { }
        if (libraryItem.isVideo && videoLayout == null) return@DisposableEffect onDispose { }

        var pfd: ParcelFileDescriptor? = null

        try {
            val mediaUri = actualMediaUri!!.toUri()
            val media = if (mediaUri.scheme == "content") {
                pfd = context.contentResolver.openFileDescriptor(mediaUri, "r")
                if (pfd != null) {
                    VLCMedia(libVlc, pfd.fileDescriptor)
                } else {
                    VLCMedia(libVlc, mediaUri)
                }
            } else {
                VLCMedia(libVlc, mediaUri)
            }

            vlcPlayer.media = media
            media.release()

            var initialSeekDone = false

            val listener = VLCMediaPlayer.EventListener { event ->
                when (event.type) {
                    VLCMediaPlayer.Event.Playing -> {
                        isPlaying = true
                        CoroutineScope(Dispatchers.Main).launch {
                            try {
                                if (!initialSeekDone && libraryItem.progress > 0) {
                                    vlcPlayer.time = libraryItem.progress
                                    initialSeekDone = true
                                }
                            } catch (e: Exception) {}
                        }
                    }
                    VLCMediaPlayer.Event.Paused, VLCMediaPlayer.Event.Stopped, VLCMediaPlayer.Event.EndReached -> isPlaying = false
                    VLCMediaPlayer.Event.TimeChanged -> {
                        currentPos = event.timeChanged.coerceAtLeast(0)
                    }
                    VLCMediaPlayer.Event.LengthChanged -> {
                        if (event.lengthChanged > 0) duration = event.lengthChanged
                    }
                    VLCMediaPlayer.Event.EncounteredError -> {
                        isPlaying = false
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(context, "Playback failed. Stream might be geo-blocked or broken.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            if (libraryItem.isVideo && videoLayout != null) {
                vlcPlayer.detachViews()
                vlcPlayer.attachViews(videoLayout!!, null, false, false)
            }

            vlcPlayer.setEventListener(listener)
            vlcPlayer.play()

        } catch (e: Exception) {
            e.printStackTrace()
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, "Playback Initialization Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }

        onDispose {
            val updatedItem = libraryItem.copy(
                progress = vlcPlayer.time.coerceAtLeast(0),
                duration = if (vlcPlayer.length > 0) vlcPlayer.length else duration,
                recordings = recordings.toList(),
                mediaUri = actualMediaUri ?: libraryItem.mediaUri
            )
            LibraryManager(context).saveItem(updatedItem)

            try { vlcPlayer.stop() } catch (e: Exception) {}
            try { vlcPlayer.detachViews() } catch (e: Exception) {}
            try { pfd?.close() } catch (e: Exception) {}
        }
    }

    var tempFilePath by remember { mutableStateOf("") }

    fun stopRecordingSafe(currentFilePath: String) {
        val endTime = vlcPlayer.time
        var success = true
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            success = false
            Toast.makeText(context, "Recording was too short!", Toast.LENGTH_SHORT).show()
        } finally {
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            vlcPlayer.pause()
        }

        if (success) {
            recordings.add(0, ShadowRecording(filePath = currentFilePath, startTime = recordStartTime, endTime = endTime))
            syncWithStorage()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) Toast.makeText(context, "Mic required!", Toast.LENGTH_SHORT).show()
    }

    var voiceCurrentPos by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(isPlaying, activeOriginalSegment, activeBothSegment) {
        while (isPlaying || activeOriginalSegment != null || activeBothSegment != null) {
            if (activeBothSegment != null || voiceAudioPlayer.isPlaying) {
                voiceCurrentPos = voiceAudioPlayer.currentPosition.toLong()
            } else {
                voiceCurrentPos = -1L
            }
            delay(33) // ~30fps for smooth cursor movement
        }
        voiceCurrentPos = -1L
    }

    Column(modifier = Modifier.fillMaxSize()) {

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                val updatedItem = libraryItem.copy(progress = currentPos, duration = duration, recordings = recordings.toList(), mediaUri = actualMediaUri ?: libraryItem.mediaUri)
                onBack(updatedItem)
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(libraryItem.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))

            if (libraryItem.subtitleUri != null || libraryItem.isVideo) {
                var showSubMenu by remember { mutableStateOf(false) }

                val subtitleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        libraryItem = libraryItem.copy(subtitleUri = uri.toString())
                    }
                }

                Box {
                    Icon(
                        imageVector = Icons.Default.Subtitles,
                        contentDescription = "Toggle Subs",
                        tint = if (isSubtitlesVisible) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClick = { isSubtitlesVisible = !isSubtitlesVisible },
                                onLongClick = { showSubMenu = true }
                            )
                            .padding(8.dp)
                    )

                    DropdownMenu(
                        expanded = showSubMenu,
                        onDismissRequest = { showSubMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Add Custom Subtitles") },
                            onClick = {
                                showSubMenu = false
                                subtitleLauncher.launch(arrayOf("*/*"))
                            }
                        )

                        var spuTracks by remember { mutableStateOf<Array<org.videolan.libvlc.MediaPlayer.TrackDescription>?>(null) }
                        LaunchedEffect(showSubMenu) {
                            if (showSubMenu) spuTracks = vlcPlayer.spuTracks
                        }

                        spuTracks?.forEach { track ->
                            if (track.id != -1) {
                                DropdownMenuItem(
                                    text = { Text(track.name) },
                                    onClick = {
                                        vlcPlayer.spuTrack = track.id
                                        showSubMenu = false
                                    }
                                )
                            }
                        }

                        DropdownMenuItem(
                            text = { Text("Disable Embedded Subs") },
                            onClick = {
                                vlcPlayer.spuTrack = -1
                                showSubMenu = false
                            }
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(0.45f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                if (isRefreshingStream) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Resolving live stream URL...", color = Color.LightGray, fontSize = 14.sp)
                    }
                } else if (libraryItem.isVideo) {
                    AndroidView(
                        factory = { ctx ->
                            VLCVideoLayout(ctx).apply {
                                videoLayout = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    if (albumArt != null) {
                        Image(
                            bitmap = albumArt, contentDescription = "Album Art", contentScale = if (isSubtitlesVisible) ContentScale.Crop else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().then(if (isSubtitlesVisible) Modifier.blur(radius = 24.dp) else Modifier)
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E24)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(80.dp))
                        }
                    }
                }

                if (isSubtitlesVisible && currentSubtitleText.isNotBlank()) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
                    JapaneseClickableSubtitle(
                        text = currentSubtitleText,
                        onWordClicked = { clickedChunk ->
                            showDictQuery = clickedChunk
                            if (isPlaying) vlcPlayer.pause()
                        }
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(0.55f).fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).padding(horizontal = 16.dp)) {

            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(currentPos), color = Color.Gray, fontSize = 11.sp)
                        Slider(
                            value = currentPos.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                            onValueChange = {
                                vlcPlayer.time = it.toLong()
                                currentPos = it.toLong()
                            },
                            valueRange = 0f..duration.toFloat().coerceAtLeast(1f), modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text(formatTime(duration), color = Color.Gray, fontSize = 11.sp)
                    }

                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vlcPlayer.time = (currentPos - 5000).coerceAtLeast(0) }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.FastRewind, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                        Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(if (isRefreshingStream) Color.DarkGray else MaterialTheme.colorScheme.primary).clickable(enabled = !isRefreshingStream) { 
                            if (isPlaying) {
                                vlcPlayer.pause()
                            } else {
                                if (currentPos >= duration - 500L && duration > 0) {
                                    vlcPlayer.time = 0
                                }
                                vlcPlayer.play()
                            }
                        }, contentAlignment = Alignment.Center) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = { vlcPlayer.time = (currentPos + 5000).coerceAtMost(duration) }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.FastForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                    }

                    if (isSubtitlesVisible) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Subtitle Delay", color = Color.Gray, fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { subtitleDelayMs -= 250L }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("-0.25s", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                                }
                                Text(String.format(Locale.US, "%.2fs", subtitleDelayMs / 1000f), color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                TextButton(onClick = { subtitleDelayMs += 250L }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("+0.25s", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                if (isRecording) {
                                    stopRecordingSafe(tempFilePath)
                                } else {
                                    val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Shadowing_${System.currentTimeMillis()}.m4a")
                                    tempFilePath = file.absolutePath
                                    recordStartTime = vlcPlayer.time

                                    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
                                    try {
                                        recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                                        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                        recorder.setAudioEncodingBitRate(128000)
                                        recorder.setAudioSamplingRate(44100)
                                        recorder.setOutputFile(file.absolutePath)
                                        recorder.prepare()
                                        recorder.start()
                                        mediaRecorder = recorder
                                        isRecording = true
                                        vlcPlayer.play()
                                    } catch (e: Exception) { e.printStackTrace() }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) Color(0xFFE53935) else MaterialTheme.colorScheme.primary,
                            contentColor = if (isRecording) Color.White else Color.Black
                        ),
                        enabled = !isRefreshingStream
                    ) {
                        Icon(if (isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (isRecording) "STOP SHADOWING" else "START SHADOWING", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                if (recordings.isNotEmpty() && !isRecording) {
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Latest Recording", color = Color.Gray, fontSize = 13.sp)
                            if (recordings.size > 1) {
                                TextButton(onClick = { showBacklog = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                                    Text("View Backlog (${recordings.size - 1})", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                                }
                            }
                        }

                        val latest = recordings.first()
                        RecordingItemCard(
                            rec = latest,
                            context = context,
                            originalMediaUri = actualMediaUri ?: libraryItem.mediaUri,
                            onPlayOriginal = { activeOriginalSegment = latest },
                            onPlayVoice = {
                                try {
                                    voiceAudioPlayer.apply {
                                        reset()
                                        setDataSource(latest.filePath)
                                        setOnPreparedListener { start() }
                                        prepareAsync()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error playing audio", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onPlayBoth = { activeBothSegment = latest },
                            onDelete = {
                                try { File(latest.filePath).delete() } catch (e: Exception) {}
                                recordings.remove(latest)
                                syncWithStorage()
                            },
                            onShare = { exportRecording(context, File(latest.filePath)) },
                            currentOriginalTime = currentPos,
                            currentRecordedTime = voiceCurrentPos
                        )
                    }
                }
            }
        }
    }

    if (showDictQuery != null) {
        HoshiDictionaryBottomSheet(
            query = showDictQuery!!,
            engine = dictionaryEngine,
            onDismiss = { showDictQuery = null }
        )
    }

    if (showBacklog) {
        ModalBottomSheet(
            onDismissRequest = { showBacklog = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 32.dp)) {
                Text("Session Backlog", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recordings.drop(1)) { rec ->
                        RecordingItemCard(
                            rec = rec,
                            context = context,
                            originalMediaUri = actualMediaUri ?: libraryItem.mediaUri,
                            onPlayOriginal = { activeOriginalSegment = rec },
                            onPlayVoice = {
                                try {
                                    voiceAudioPlayer.apply {
                                        reset()
                                        setDataSource(rec.filePath)
                                        setOnPreparedListener { start() }
                                        prepareAsync()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error playing audio", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onPlayBoth = { activeBothSegment = rec },
                            onDelete = {
                                try { File(rec.filePath).delete() } catch (e: Exception) {}
                                recordings.remove(rec)
                                syncWithStorage()
                            },
                            onShare = { exportRecording(context, File(rec.filePath)) },
                            currentOriginalTime = currentPos,
                            currentRecordedTime = voiceCurrentPos
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// 4. COMPONENTS & HELPERS
// ==========================================

@Composable
fun JapaneseClickableSubtitle(text: String, onWordClicked: (String) -> Unit) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = text,
        color = Color.White,
        fontSize = 32.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 44.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(16.dp)
            .pointerInput(text) {
                detectTapGestures { pos ->
                    layoutResult?.let { layout ->
                        val offset = layout.getOffsetForPosition(pos)
                        if (offset < text.length) {
                            val endIndex = minOf(text.length, offset + 8)
                            onWordClicked(text.substring(offset, endIndex))
                        }
                    }
                }
            },
        onTextLayout = { layoutResult = it }
    )
}

@Composable
fun PitchOverline(reading: String, pitchPosition: Int) {
    val morae = remember(reading) {
        val list = mutableListOf<String>()
        var i = 0
        while (i < reading.length) {
            val c = reading[i]
            if (i + 1 < reading.length && (reading[i + 1] == 'ゃ' || reading[i + 1] == 'ゅ' || reading[i + 1] == 'ょ' || reading[i + 1] == 'ャ' || reading[i + 1] == 'ュ' || reading[i + 1] == 'ョ' || reading[i + 1] == 'ぁ' || reading[i + 1] == 'ぃ' || reading[i + 1] == 'ぅ' || reading[i + 1] == 'ぇ' || reading[i + 1] == 'ぉ')) {
                list.add(reading.substring(i, i + 2))
                i += 2
            } else {
                list.add(c.toString())
                i++
            }
        }
        list
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        morae.forEachIndexed { i, mora ->
            val isHigh = when (pitchPosition) {
                0 -> i > 0
                1 -> i == 0
                else -> i > 0 && i < pitchPosition
            }
            val hasDrop = pitchPosition > 0 && i == pitchPosition - 1

            Box(contentAlignment = Alignment.TopStart) {
                Text(mora, color = Color.White, fontSize = 20.sp)
                androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                    if (isHigh) {
                        drawLine(Color.White, androidx.compose.ui.geometry.Offset(0f, 2.dp.toPx()), androidx.compose.ui.geometry.Offset(size.width, 2.dp.toPx()), 1.5.dp.toPx())
                    }
                    if (hasDrop) {
                        drawLine(Color.White, androidx.compose.ui.geometry.Offset(size.width, 2.dp.toPx()), androidx.compose.ui.geometry.Offset(size.width, size.height * 0.6f), 1.5.dp.toPx())
                    }
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Text("[$pitchPosition]", color = Color.Gray, fontSize = 14.sp)
    }
}

@Composable
fun PitchDiagram(reading: String, pitchPosition: Int, modifier: Modifier = Modifier) {
    val morae = remember(reading) {
        val list = mutableListOf<String>()
        var i = 0
        while (i < reading.length) {
            val c = reading[i]
            if (i + 1 < reading.length && (reading[i + 1] == 'ゃ' || reading[i + 1] == 'ゅ' || reading[i + 1] == 'ょ' || reading[i + 1] == 'ャ' || reading[i + 1] == 'ュ' || reading[i + 1] == 'ョ' || reading[i + 1] == 'ぁ' || reading[i + 1] == 'ぃ' || reading[i + 1] == 'ぅ' || reading[i + 1] == 'ぇ' || reading[i + 1] == 'ぉ')) {
                list.add(reading.substring(i, i + 2))
                i += 2
            } else {
                list.add(c.toString())
                i++
            }
        }
        list
    }

    val textColor = Color.White
    val dotRadius = 4.dp
    val strokeWidth = 2.dp
    val moraWidth = 24.dp

    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .width((moraWidth * (morae.size + 1)))
                .height(40.dp)
        ) {
            val stepX = moraWidth.toPx()
            val highY = 10.dp.toPx()
            val lowY = 30.dp.toPx()

            val points = mutableListOf<androidx.compose.ui.geometry.Offset>()
            for (i in morae.indices) {
                val isHigh = when (pitchPosition) {
                    0 -> i > 0
                    1 -> i == 0
                    else -> if (i == 0) false else i < pitchPosition
                }
                points.add(androidx.compose.ui.geometry.Offset(i * stepX + stepX / 2, if (isHigh) highY else lowY))
            }

            val particleHigh = pitchPosition == 0 || (pitchPosition > 0 && morae.size < pitchPosition)
            val particlePoint = androidx.compose.ui.geometry.Offset(morae.size * stepX + stepX / 2, if (particleHigh) highY else lowY)

            for (i in 0 until points.size - 1) {
                drawLine(color = textColor, start = points[i], end = points[i + 1], strokeWidth = strokeWidth.toPx())
            }

            drawLine(
                color = textColor.copy(alpha = 0.5f),
                start = points.last(),
                end = particlePoint,
                strokeWidth = strokeWidth.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
            )

            points.forEachIndexed { i, pt ->
                if (i == 0) {
                    drawCircle(color = textColor, radius = dotRadius.toPx(), center = pt, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
                } else {
                    drawCircle(color = textColor, radius = dotRadius.toPx(), center = pt)
                }
            }

            val trianglePath = androidx.compose.ui.graphics.Path().apply {
                moveTo(particlePoint.x, particlePoint.y - 4.dp.toPx())
                lineTo(particlePoint.x - 4.dp.toPx(), particlePoint.y + 4.dp.toPx())
                lineTo(particlePoint.x + 4.dp.toPx(), particlePoint.y + 4.dp.toPx())
                close()
            }
            drawPath(path = trianglePath, color = textColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
        }

        Row(modifier = Modifier.width(moraWidth * (morae.size + 1))) {
            morae.forEach { mora ->
                Text(mora, fontSize = 12.sp, color = textColor, textAlign = TextAlign.Center, modifier = Modifier.width(moraWidth))
            }
            Spacer(Modifier.width(moraWidth))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoshiDictionaryBottomSheet(query: String, engine: DictionaryEngine, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var results by remember { mutableStateOf<List<DictEntry>>(emptyList()) }
    var searchQuery by remember { mutableStateOf(query) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery) {
        isSearching = true
        results = engine.searchPrefixes(searchQuery)
        isSearching = false
    }

    BackHandler(enabled = true) {
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color(0xFF1E1E24)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 32.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search Dictionary") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF2B2B36), unfocusedContainerColor = Color(0xFF2B2B36)
                ),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isSearching) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else if (results.isEmpty()) {
                Text("No results found.", color = Color.Gray, modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                val grouped = results.groupBy { "${it.deinflected}|${it.reading}" }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    grouped.forEach { (_, groupEntries) ->
                        item {
                            DictGroupCard(groupEntries)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DictGroupCard(entries: List<DictEntry>) {
    val first = entries.first()
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A35)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(first.deinflected, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)

                if (first.reading.isNotEmpty() && first.reading != first.deinflected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "【${first.reading}】",
                        fontSize = 18.sp,
                        color = Color(0xFFAEB2FF),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            if (first.pitchPositions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                val pitchesByDict = first.pitchPositions.groupBy { it.dictName }
                pitchesByDict.forEach { (dictName, pitches) ->
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        Surface(
                            color = Color(0xFF5E5CE6).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Text(dictName, color = Color(0xFFAEB2FF), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }

                        pitches.forEach { pitch ->
                            PitchOverline(reading = first.reading, pitchPosition = pitch.position)
                            Spacer(Modifier.height(8.dp))
                            PitchDiagram(reading = first.reading, pitchPosition = pitch.position)
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val byDict = entries.groupBy { it.dictName }
            byDict.forEach { (dictName, dictEntries) ->
                Surface(
                    color = Color(0xFF5E5CE6).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = dictName,
                        color = Color(0xFFAEB2FF),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                dictEntries.forEach { entry ->
                    val processedDefinition = if (entry.definition.trim().startsWith("[") || entry.definition.trim().startsWith("{")) {
                        convertStructuredToHtml(entry.definition)
                    } else {
                        entry.definition
                    }

                    if (processedDefinition.contains("<")) {
                        AndroidView(
                            factory = { ctx ->
                                android.webkit.WebView(ctx).apply {
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    settings.apply {
                                        allowFileAccess = false
                                        javaScriptEnabled = false
                                    }
                                    val html = """
                                        <html><head><style>
                                        body { color: #D0D0D0; font-size: 15px; font-family: sans-serif;
                                               background: transparent; margin: 0; padding: 0; }
                                        a { color: #AEB2FF; text-decoration: none; } 
                                        ul, ol { padding-left: 20px; margin-top: 4px; }
                                        li { margin-bottom: 4px; }
                                        ruby rt { font-size: 0.6em; color: #AEB2FF; }
                                        </style></head><body>$processedDefinition</body></html>
                                    """.trimIndent()
                                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp, max = 500.dp)
                        )
                    } else {
                        Text(processedDefinition, color = Color.LightGray, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun AudioWaveformComparison(
    originalAmplitudes: List<Float>,
    recordedAmplitudes: List<Float>,
    onPlayOriginal: () -> Unit,
    onPlayVoice: () -> Unit,
    originalProgress: Float = 0f,
    recordedProgress: Float = 0f,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().background(Color(0xFF1E1E24), RoundedCornerShape(8.dp)).padding(8.dp)) {
        WaveformTrack(amplitudes = originalAmplitudes, color = Color(0xFF5E5CE6), label = "Original", onClick = onPlayOriginal, progress = originalProgress)
        Spacer(Modifier.height(8.dp))
        WaveformTrack(amplitudes = recordedAmplitudes, color = Color(0xFF8E8E93), label = "Recorded", onClick = onPlayVoice, progress = recordedProgress)
    }
}

@Composable
fun WaveformTrack(amplitudes: List<Float>, color: Color, label: String, onClick: () -> Unit, progress: Float = 0f) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(color, CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (amplitudes.isNotEmpty()) {
                val step = size.width / amplitudes.size
                val midY = size.height / 2
                val points = amplitudes.mapIndexed { index, amp ->
                    androidx.compose.ui.geometry.Offset(index * step, midY - (amp * midY))
                }

                val path = androidx.compose.ui.graphics.Path()
                if (points.isNotEmpty()) {
                    path.moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) {
                        path.lineTo(points[i].x, points[i].y)
                    }
                }
                drawPath(path = path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))

                if (progress > 0f) {
                    val cursorX = size.width * progress.coerceIn(0f, 1f)
                    drawLine(
                        color = Color.White.copy(alpha = 0.8f),
                        start = androidx.compose.ui.geometry.Offset(cursorX, 0f),
                        end = androidx.compose.ui.geometry.Offset(cursorX, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }
        }
    }
}

@Composable
fun LibraryCard(item: LibraryItem, onClick: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val progressPct = if (item.duration > 0) (item.progress.toFloat() / item.duration.toFloat()) else 0f
    val albumArt = if (item.sourceUrl == null) loadAlbumArt(context, item.mediaUri, item.isVideo) else null

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(64.dp).height(90.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                if (albumArt != null) {
                    Image(bitmap = albumArt, contentDescription = "Cover", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(if (item.isVideo) Icons.Default.Movie else Icons.Default.Audiotrack, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${(progressPct * 100).toInt()}%", color = Color.White, fontSize = 14.sp)
                    Text(if (item.sourceUrl != null) "Cloud Stream" else if (item.subtitleUri != null) "Subtitles attached" else "No subtitles", color = Color.Gray, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(progress = { progressPct }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = MaterialTheme.colorScheme.primary, trackColor = Color.DarkGray)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray) }
        }
    }
}

@Composable
fun RecordingItemCard(
    rec: ShadowRecording,
    context: Context,
    originalMediaUri: String,
    onPlayOriginal: () -> Unit,
    onPlayVoice: () -> Unit,
    onPlayBoth: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    currentOriginalTime: Long = -1L,
    currentRecordedTime: Long = -1L
) {
    var originalAmplitudes by remember { mutableStateOf<List<Float>>(emptyList()) }
    var recordedAmplitudes by remember { mutableStateOf<List<Float>>(emptyList()) }

    LaunchedEffect(rec) {
        withContext(Dispatchers.IO) {
            if (originalMediaUri.startsWith("http")) {
                originalAmplitudes = List(40) { (0.1 + Math.random() * 0.7).toFloat() }
            } else {
                originalAmplitudes = extractAudioAmplitudes(context, Uri.parse(originalMediaUri), rec.startTime, rec.endTime, 40)
            }
            recordedAmplitudes = extractAudioAmplitudes(context, Uri.fromFile(File(rec.filePath)), 0, rec.endTime - rec.startTime, 40)
        }
    }

    val originalProgress = if (currentOriginalTime in rec.startTime..rec.endTime) {
        (currentOriginalTime - rec.startTime).toFloat() / (rec.endTime - rec.startTime).toFloat()
    } else 0f

    val recordedProgress = if (currentRecordedTime >= 0) {
        currentRecordedTime.toFloat() / (rec.endTime - rec.startTime).toFloat()
    } else 0f

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Segment: ${formatTime(rec.startTime)} - ${formatTime(rec.endTime)}", color = Color.White, fontSize = 14.sp)
                Row {
                    IconButton(onClick = onShare, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.Gray, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            AudioWaveformComparison(
                originalAmplitudes = originalAmplitudes,
                recordedAmplitudes = recordedAmplitudes,
                onPlayOriginal = onPlayOriginal,
                onPlayVoice = onPlayVoice,
                originalProgress = originalProgress,
                recordedProgress = recordedProgress
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onPlayBoth,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Play Both", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

fun extractAudioAmplitudes(context: Context, uri: Uri, startTimeMs: Long, endTimeMs: Long, buckets: Int): List<Float> {
    val result = MutableList(buckets) { 0.05f }
    try {
        val extractor = android.media.MediaExtractor()
        if (uri.scheme == "content") {
            context.contentResolver.openFileDescriptor(uri, "r")?.fileDescriptor?.let {
                extractor.setDataSource(it)
            }
        } else if (uri.scheme == "file") {
            extractor.setDataSource(uri.path!!)
        } else {
            extractor.setDataSource(context, uri, null)
        }

        var audioTrackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                break
            }
        }
        if (audioTrackIndex < 0) {
            extractor.release()
            return result
        }

        extractor.selectTrack(audioTrackIndex)
        val format = extractor.getTrackFormat(audioTrackIndex)
        val mime = format.getString(android.media.MediaFormat.KEY_MIME)!!
        val codec = android.media.MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        extractor.seekTo(startTimeMs * 1000, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val durationMs = if (endTimeMs > startTimeMs) endTimeMs - startTimeMs else {
            val fileDurationUs = format.getLong(android.media.MediaFormat.KEY_DURATION)
            (fileDurationUs / 1000).takeIf { it > 0 } ?: 10000L
        }

        val bucketDurationUs = (durationMs * 1000) / buckets
        val bucketAmps = FloatArray(buckets)

        val info = android.media.MediaCodec.BufferInfo()
        var isEOS = false
        var maxGlobalAmp = 1f

        while (!isEOS) {
            val inIndex = codec.dequeueInputBuffer(5000)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex)!!
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0 || (endTimeMs > 0 && extractor.sampleTime > endTimeMs * 1000)) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    isEOS = true
                } else {
                    codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            var outIndex = codec.dequeueOutputBuffer(info, 5000)
            while (outIndex >= 0) {
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    isEOS = true
                }
                if (info.size > 0) {
                    val chunkTimeUs = info.presentationTimeUs - (startTimeMs * 1000)
                    if (chunkTimeUs >= 0) {
                        val bucketIdx = (chunkTimeUs / bucketDurationUs).toInt().coerceIn(0, buckets - 1)

                        val outBuffer = codec.getOutputBuffer(outIndex)!!
                        outBuffer.position(info.offset)
                        outBuffer.limit(info.offset + info.size)

                        val shortBuffer = outBuffer.asShortBuffer()
                        var localMax = 0
                        while (shortBuffer.hasRemaining()) {
                            val sample = Math.abs(shortBuffer.get().toInt())
                            if (sample > localMax) localMax = sample
                        }

                        if (localMax > bucketAmps[bucketIdx]) {
                            bucketAmps[bucketIdx] = localMax.toFloat()
                            if (localMax > maxGlobalAmp) maxGlobalAmp = localMax.toFloat()
                        }
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(info, 5000)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        for (i in 0 until buckets) {
            result[i] = (bucketAmps[i] / maxGlobalAmp).coerceIn(0.05f, 1f)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return result
}

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
fun loadAlbumArt(context: Context, uriString: String, isVideo: Boolean): ImageBitmap? {
    var bitmap by remember(uriString) { mutableStateOf<ImageBitmap?>(ImageCache.cache[uriString]) }

    LaunchedEffect(uriString) {
        if (bitmap != null) return@LaunchedEffect

        withContext(Dispatchers.IO) {
            val uri = Uri.parse(uriString)
            var bmImage: ImageBitmap? = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val bmp = context.contentResolver.loadThumbnail(uri, Size(800, 800), null)
                    bmImage = bmp.asImageBitmap()
                } catch (e: Throwable) { }
            }

            if (bmImage == null) {
                var retriever: MediaMetadataRetriever? = null
                var pfd: ParcelFileDescriptor? = null
                try {
                    retriever = MediaMetadataRetriever()
                    if (uri.scheme == "file") {
                        retriever.setDataSource(File(uri.path!!).absolutePath)
                    } else {
                        pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        if (pfd != null) retriever.setDataSource(pfd.fileDescriptor)
                    }

                    if (isVideo) {
                        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val duration = durationStr?.toLongOrNull() ?: 0L
                        val randomTimeUs = if (duration > 60000L) {
                            (duration * 1000 * (0.1 + Math.random() * 0.8)).toLong()
                        } else {
                            10000000L
                        }
                        val frame = retriever.getFrameAtTime(randomTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: retriever.getFrameAtTime(10000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

                        if (frame != null) bmImage = frame.asImageBitmap()

                    } else {
                        val artBytes = retriever.embeddedPicture
                        if (artBytes != null) {
                            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, boundsOptions)

                            var scale = 1
                            while (boundsOptions.outWidth / scale > 1024 || boundsOptions.outHeight / scale > 1024) scale *= 2

                            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = scale }
                            val bmp = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, decodeOptions)

                            if (bmp != null) bmImage = bmp.asImageBitmap()
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                } finally {
                    try { retriever?.release() } catch (e: Throwable) {}
                    try { pfd?.close() } catch (e: Throwable) {}
                }
            }

            if (bmImage != null) {
                ImageCache.cache[uriString] = bmImage
                bitmap = bmImage
            }
        }
    }
    return bitmap
}

fun parseSimpleSubtitles(context: Context, uri: Uri): List<SubtitleCue> {
    val cues = mutableListOf<SubtitleCue>()
    try {
        val inputStream = if (uri.scheme == "file") File(uri.path!!).inputStream() else context.contentResolver.openInputStream(uri)
        inputStream?.bufferedReader()?.use { reader ->
            val timeRegex = Regex("(\\d{2}:\\d{2}:\\d{2}[.,]\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2}[.,]\\d{3})")
            val vttTimeRegex = Regex("(\\d{2}:\\d{2}[.,]\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}[.,]\\d{3})")

            var currentStart = -1L
            var currentEnd = -1L
            val currentText = StringBuilder()

            var startIndex = 1
            var endIndex = 2
            var textIndex = 9

            reader.forEachLine { line ->
                val trimmed = line.trim()

                if (trimmed == "WEBVTT" || trimmed.startsWith("Language:") || trimmed.startsWith("Kind:") || trimmed.startsWith("Style:")) return@forEachLine

                if (trimmed.startsWith("Format:")) {
                    val formatParts = trimmed.substringAfter("Format:").split(",").map { it.trim() }
                    startIndex = formatParts.indexOf("Start").takeIf { it >= 0 } ?: startIndex
                    endIndex = formatParts.indexOf("End").takeIf { it >= 0 } ?: endIndex
                    textIndex = formatParts.indexOf("Text").takeIf { it >= 0 } ?: textIndex
                    return@forEachLine
                }

                if (trimmed.startsWith("Dialogue:")) {
                    val parts = trimmed.substringAfter("Dialogue:").split(",", limit = textIndex + 1)
                    if (parts.size > textIndex) {
                        val startMs = parseTimeMs(parts[startIndex].trim())
                        val endMs = parseTimeMs(parts[endIndex].trim())
                        val text = parts[textIndex].trim().replace(Regex("\\{.*?\\}"), "").replace("\\N", "\n")
                        if (text.isNotBlank()) cues.add(SubtitleCue(startMs, endMs, text))
                    }
                    return@forEachLine
                }

                val timeMatch = timeRegex.find(trimmed) ?: vttTimeRegex.find(trimmed)
                if (timeMatch != null) {
                    if (currentStart != -1L) {
                        cues.add(SubtitleCue(currentStart, currentEnd, currentText.toString().trim()))
                        currentText.clear()
                    }
                    var startStr = timeMatch.groupValues[1]
                    var endStr = timeMatch.groupValues[2]
                    if (startStr.length == 9) startStr = "00:$startStr"
                    if (endStr.length == 9) endStr = "00:$endStr"
                    currentStart = parseTimeMs(startStr)
                    currentEnd = parseTimeMs(endStr)
                } else if (trimmed.isNotBlank() && !trimmed.matches(Regex("^\\d+$"))) {
                    currentText.append(trimmed.replace(Regex("<[^>]*>"), "")).append("\n")
                }
            }
            if (currentStart != -1L && currentText.isNotBlank()) {
                cues.add(SubtitleCue(currentStart, currentEnd, currentText.toString().trim()))
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return cues
}

fun parseTimeMs(timeStr: String): Long {
    try {
        val parts = timeStr.replace(",", ".").split(":", ".")
        if (parts.size >= 3) {
            val h = parts[0].toLong() * 3600000
            val m = parts[1].toLong() * 60000
            val s = parts[2].toLong() * 1000
            var ms = if (parts.size >= 4) parts[3].toLong() else 0L
            if (parts.size >= 4 && parts[3].length == 2) ms *= 10
            else if (parts.size >= 4 && parts[3].length == 1) ms *= 100
            return h + m + s + ms
        }
    } catch (e: Exception) {}
    return 0L
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

fun getFileName(context: Context, uri: Uri): String {
    var name = ""
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) name = cursor.getString(idx)
            }
        }
    } catch (e: Exception) {}
    return name.ifEmpty { "Unknown Media" }
}

fun convertStructuredToHtml(json: String): String {
    return try {
        val root = if (json.trim().startsWith("[")) JSONArray(json) else JSONObject(json)
        val sb = StringBuilder()
        parseStructuredNode(root, sb)
        sb.toString()
    } catch (e: Exception) {
        json
    }
}

private fun parseStructuredNode(node: Any, sb: StringBuilder) {
    when (node) {
        is String -> sb.append(node.replace("\n", "<br>"))
        is JSONArray -> {
            for (i in 0 until node.length()) {
                parseStructuredNode(node.get(i), sb)
            }
        }
        is JSONObject -> {
            val type = node.optString("type")
            if (type == "structured-content") {
                parseStructuredNode(node.opt("content") ?: "", sb)
                return
            }

            val tag = node.optString("tag", "")
            val content = node.opt("content")

            if (tag.isNotEmpty()) {
                sb.append("<$tag")
                val href = node.optString("href")
                if (href.isNotEmpty()) sb.append(" href=\"$href\"")
                sb.append(">")
                if (content != null) parseStructuredNode(content, sb)
                sb.append("</$tag>")
            } else if (content != null) {
                parseStructuredNode(content, sb)
            }
        }
    }
}
