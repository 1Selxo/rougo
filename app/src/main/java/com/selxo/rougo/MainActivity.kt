package com.selxo.rougo
import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.ImportResult
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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.lifecycle.lifecycleScope
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
                LibVLC(context.applicationContext, arrayListOf("-vvv")).also { libVLC = it }
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
            // Use Hoshi library's native check for pitch entries
            val isPitch = HoshiDicts.hasMetaModeEntries(folder.absolutePath, "pitch", 1)
            if (isPitch) {
                pitchPaths.add(folder.absolutePath)
            } else {
                termPaths.add(folder.absolutePath)
            }
        }

        android.util.Log.d("DictionaryEngine", "Rebuilding Query - Terms: ${termPaths.size}, Pitch: ${pitchPaths.size}")

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
            // Fixed: Explicitly calling from HoshiDicts
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
                
                // Get ALL pitches associated with this term across ALL pitch dictionaries
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

// ==========================================
// 2. MAIN ACTIVITY & APP FLOW
// ==========================================

class MainActivity : ComponentActivity() {
    private var sharedUrlState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
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
                // Small delay to ensure native libs are fully extracted/ready on some devices
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
                Surface(modifier = Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    MainAppFlow(
                        sharedUrl = sharedUrlState.value,
                        onSharedUrlProcessed = { sharedUrlState.value = null }
                    )
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
                libraryItem = item,
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
    var selectedSubtitleKey by remember { mutableStateOf<String?>(null) } // "ja", "en", etc.
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
            
            // Start playback IMMEDIATELY with the resolved URL
            onComplete(newItem)

            // Load subtitles in the background WITHOUT blocking the user
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
                        request.addOption("--convert-subs", "srt")
                    }

                    request.addOption("-o", "${destDir.absolutePath}/$fileId.%(ext)s")

                    YoutubeDL.getInstance().execute(request, fileId)
                    
                    val subFile = destDir.listFiles()?.find { it.name.startsWith(fileId) && (it.extension == "srt" || it.name.contains(".srt")) }
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
                    
                    val subOptions = mutableListOf<Triple<String, String, Boolean>>() // DisplayName, Key, isAuto
                    videoInfo?.subtitles?.forEach { (lang, _) -> subOptions.add(Triple(lang, lang, false)) }
                    videoInfo?.automaticCaptions?.forEach { (lang, _) -> subOptions.add(Triple("$lang (Auto)", lang, true)) }

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
        containerColor = MaterialTheme.colorScheme.background,
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
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
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
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(libraryItem: LibraryItem, onBack: (LibraryItem) -> Unit) {
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
    var showBacklog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    var actualMediaUri by remember { mutableStateOf(if (libraryItem.sourceUrl == null) libraryItem.mediaUri else null) }
    var isRefreshingStream by remember { mutableStateOf(libraryItem.sourceUrl != null) }

    LaunchedEffect(libraryItem) {
        if (libraryItem.sourceUrl != null) {
            isRefreshingStream = true
            withContext(Dispatchers.IO) {
                try {
                    val info = YoutubeDL.getInstance().getInfo(libraryItem.sourceUrl)
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

    // Rely on global VLC Singleton to prevent rapid destruction/instantiation crashes.
    val libVlc = remember { VLCManager.getLibVLC(context) }
    val vlcPlayer = remember { VLCMediaPlayer(libVlc) }

    var videoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }

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

    // Safely and accurately release strictly scoped resources only when PlayerScreen COMPLETELY unmounts.
    // NOTE: We no longer release libVlc, preserving stability.
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
            while (vlcPlayer.time < segment.endTime) { delay(50) }
            vlcPlayer.pause()
            activeOriginalSegment = null
        }
    }

    LaunchedEffect(libraryItem.subtitleUri) {
        if (libraryItem.subtitleUri != null) {
            isParsingSubtitles = true
            withContext(Dispatchers.IO) {
                parsedAudioCues = parseSimpleSubtitles(context, libraryItem.subtitleUri.toUri())
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

        // Declare the pfd reference outside so we can close it later when VLC has finished with it
        var pfd: ParcelFileDescriptor? = null

        try {
            val mediaUri = actualMediaUri!!.toUri()
            val media = if (mediaUri.scheme == "content") {
                pfd = context.contentResolver.openFileDescriptor(mediaUri, "r")
                if (pfd != null) {
                    // Do NOT close the pfd here. Just pass the FileDescriptor so VLC can stream it.
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
                        // Modifying Player states inside native thread callback triggers deadlocks. Wrap accurately:
                        CoroutineScope(Dispatchers.Main).launch {
                            try {
                                vlcPlayer.spuTrack = -1
                                if (!initialSeekDone && libraryItem.progress > 0) {
                                    vlcPlayer.time = libraryItem.progress
                                    initialSeekDone = true
                                }
                            } catch (e: Exception) {}
                        }
                    }
                    VLCMediaPlayer.Event.Paused, VLCMediaPlayer.Event.Stopped -> isPlaying = false
                    VLCMediaPlayer.Event.TimeChanged -> {
                        // Utilize safe native properties over internal native lock requests (`vlcPlayer.time`)
                        currentPos = event.timeChanged.coerceAtLeast(0)
                    }
                    VLCMediaPlayer.Event.LengthChanged -> {
                        if (event.lengthChanged > 0) duration = event.lengthChanged
                    }
                    VLCMediaPlayer.Event.EncounteredError -> {
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(context, "Playback failed. Stream might be geo-blocked or broken.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            if (libraryItem.isVideo && videoLayout != null) {
                vlcPlayer.attachViews(videoLayout!!, null, false, false)
            }

            vlcPlayer.setEventListener(listener)
            vlcPlayer.play()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Playback Initialization Error", Toast.LENGTH_SHORT).show()
        }

        onDispose {
            val updatedItem = libraryItem.copy(
                progress = vlcPlayer.time.coerceAtLeast(0),
                duration = if (vlcPlayer.length > 0) vlcPlayer.length else duration,
                recordings = recordings.toList(),
                mediaUri = actualMediaUri!!
            )
            LibraryManager(context).saveItem(updatedItem)

            try { vlcPlayer.stop() } catch (e: Exception) {}
            try { vlcPlayer.detachViews() } catch (e: Exception) {}

            // Close the file descriptor safely here when playback finishes or view is disposed
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
                IconButton(onClick = { isSubtitlesVisible = !isSubtitlesVisible }) {
                    Icon(Icons.Default.Subtitles, contentDescription = "Toggle Subs", tint = if (isSubtitlesVisible) MaterialTheme.colorScheme.primary else Color.Gray)
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
                        Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(if (isRefreshingStream) Color.DarkGray else MaterialTheme.colorScheme.primary).clickable(enabled = !isRefreshingStream) { if (isPlaying) vlcPlayer.pause() else vlcPlayer.play() }, contentAlignment = Alignment.Center) {
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
                        colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) Color(0xFFE53935) else Color(0xFF383842)),
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
                            onPlayOriginal = { activeOriginalSegment = latest },
                            onPlayVoice = {
                                try {
                                    voiceAudioPlayer.reset(); voiceAudioPlayer.setDataSource(latest.filePath)
                                    voiceAudioPlayer.prepare(); voiceAudioPlayer.start()
                                } catch (e: Exception) { Toast.makeText(context, "Error playing audio", Toast.LENGTH_SHORT).show() }
                            },
                            onDelete = {
                                try { File(latest.filePath).delete() } catch (e: Exception) {}
                                recordings.remove(latest)
                                syncWithStorage()
                            }
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
                            onPlayOriginal = { activeOriginalSegment = rec },
                            onPlayVoice = {
                                try {
                                    voiceAudioPlayer.reset(); voiceAudioPlayer.setDataSource(rec.filePath)
                                    voiceAudioPlayer.prepare(); voiceAudioPlayer.start()
                                } catch (e: Exception) { Toast.makeText(context, "Error playing audio", Toast.LENGTH_SHORT).show() }
                            },
                            onDelete = {
                                try { File(rec.filePath).delete() } catch (e: Exception) {}
                                recordings.remove(rec)
                                syncWithStorage()
                            }
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
                // Center the point in the mora slot (0.5 * stepX)
                points.add(androidx.compose.ui.geometry.Offset(i * stepX + stepX / 2, if (isHigh) highY else lowY))
            }

            // Line to particle
            val particleHigh = pitchPosition == 0 || (pitchPosition > 0 && morae.size < pitchPosition)
            val particlePoint = androidx.compose.ui.geometry.Offset(morae.size * stepX + stepX / 2, if (particleHigh) highY else lowY)

            // Draw lines
            for (i in 0 until points.size - 1) {
                drawLine(color = textColor, start = points[i], end = points[i + 1], strokeWidth = strokeWidth.toPx())
            }
            
            // Dotted line to particle indicator
            drawLine(
                color = textColor.copy(alpha = 0.5f),
                start = points.last(),
                end = particlePoint,
                strokeWidth = strokeWidth.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
            )

            // Draw dots
            points.forEachIndexed { i, pt ->
                if (i == 0) {
                    drawCircle(color = textColor, radius = dotRadius.toPx(), center = pt, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
                } else {
                    drawCircle(color = textColor, radius = dotRadius.toPx(), center = pt)
                }
            }
            
            // Draw particle indicator (triangle)
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
            // Empty space for particle triangle
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
            // Header: Deinflected (Main), Reading
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
            
            // Pitch Section
            if (first.pitchPositions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                // Group by dictionary for pitch sources
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
            
            // Group results by dictionary
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
fun AudioWaveformComparison(originalAmplitudes: List<Float>, recordedAmplitudes: List<Float>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().background(Color(0xFF1E1E24), RoundedCornerShape(8.dp)).padding(8.dp)) {
        // Original Audio Waveform (Blue)
        WaveformTrack(amplitudes = originalAmplitudes, color = Color(0xFF5E5CE6), label = "Original")
        Spacer(Modifier.height(8.dp))
        // Recorded Audio Waveform (Gray)
        WaveformTrack(amplitudes = recordedAmplitudes, color = Color(0xFF8E8E93), label = "Recorded")
    }
}

@Composable
fun WaveformTrack(amplitudes: List<Float>, color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
        Box(modifier = Modifier.size(32.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
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
fun RecordingItemCard(rec: ShadowRecording, onPlayOriginal: () -> Unit, onPlayVoice: () -> Unit, onDelete: () -> Unit) {
    var originalAmplitudes by remember { mutableStateOf<List<Float>>(emptyList()) }
    var recordedAmplitudes by remember { mutableStateOf<List<Float>>(emptyList()) }

    LaunchedEffect(rec) {
        withContext(Dispatchers.IO) {
            // Simulate FFmpeg amplitude extraction
            originalAmplitudes = List(40) { (0.1 + Math.random() * 0.7).toFloat() }
            recordedAmplitudes = List(40) { (0.1 + Math.random() * 0.7).toFloat() }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Segment: ${formatTime(rec.startTime)} - ${formatTime(rec.endTime)}", color = Color.White, fontSize = 14.sp)
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(18.dp))
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            AudioWaveformComparison(
                originalAmplitudes = originalAmplitudes,
                recordedAmplitudes = recordedAmplitudes
            )
            
            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPlayOriginal, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Play Original", fontSize = 12.sp) }
                Button(onClick = onPlayVoice, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("Play My Voice", fontSize = 12.sp) }
            }
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
            val srtRegex = Regex("(\\d{2}:\\d{2}:\\d{2}[.,]\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2}[.,]\\d{3})")

            var currentStart = -1L
            var currentEnd = -1L
            val currentText = StringBuilder()

            var startIndex = 1
            var endIndex = 2
            var textIndex = 9

            reader.forEachLine { line ->
                val trimmed = line.trim()

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

                val srtMatch = srtRegex.find(trimmed)
                if (srtMatch != null) {
                    if (currentStart != -1L) {
                        cues.add(SubtitleCue(currentStart, currentEnd, currentText.toString().trim()))
                        currentText.clear()
                    }
                    currentStart = parseTimeMs(srtMatch.groupValues[1])
                    currentEnd = parseTimeMs(srtMatch.groupValues[2])
                } else if (trimmed.isNotBlank() && !trimmed.matches(Regex("^\\d+$")) && !trimmed.startsWith("WEBVTT")) {
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
