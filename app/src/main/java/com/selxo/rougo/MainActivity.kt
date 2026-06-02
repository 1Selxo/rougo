package com.selxo.rougo
import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.ImportResult
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
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Html
import android.util.Size
import android.util.Log
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalOverscrollFactory
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

// ==========================================
// 1. DATA MODELS & DICTIONARY ENGINE
// ==========================================

class RougoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

object CrashReporter {
    private const val TAG = "RougoCrash"
    private const val CRASH_FILE_NAME = "last_crash.txt"
    private const val HANDLED_FILE_NAME = "handled_errors.txt"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(appContext, thread, throwable)
                Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
            } catch (loggingError: Throwable) {
                loggingError.printStackTrace()
            }

            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun readLastCrash(context: Context): String? {
        return try {
            crashFile(context).takeIf { it.exists() && it.length() > 0L }?.readText()
        } catch (e: Exception) {
            null
        }
    }

    fun clearLastCrash(context: Context) {
        try { crashFile(context).delete() } catch (e: Exception) {}
    }

    fun recordHandled(context: Context, area: String, throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
            handledFile(context).appendText(
                buildString {
                    appendLine("[$timestamp] $area")
                    appendLine(stackTraceToString(throwable))
                    appendLine()
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not write handled error", e)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toLong()
        }

        crashFile(context).writeText(
            buildString {
                appendLine("朗語 crash report")
                appendLine("Time: $timestamp")
                appendLine("App: ${packageInfo?.versionName ?: "unknown"} ($versionCode)")
                appendLine("Package: ${context.packageName}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Thread: ${thread.name} / ${thread.id}")
                appendLine()
                appendLine(stackTraceToString(throwable))
            }
        )
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun crashFile(context: Context): File = File(context.filesDir, CRASH_FILE_NAME)

    private fun handledFile(context: Context): File = File(context.filesDir, HANDLED_FILE_NAME)
}

data class DictEntry(
    val term: String,
    val deinflected: String,
    val reading: String,
    val definition: String,
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
    val sourceUrl: String? = null, val formatId: String? = null,
    val artist: String? = null, val album: String? = null, val albumArtist: String? = null,
    val genre: String? = null, val year: String? = null, val coverArtPath: String? = null,
    val httpUserAgent: String? = null, val httpReferer: String? = null
)

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

private data class MediaMetadataSnapshot(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val durationMs: Long? = null,
    val coverArtPath: String? = null
)

private fun JSONObject.optCleanString(key: String): String? = cleanMetadataValue(optString(key, ""))

private fun cleanMetadataValue(value: String?): String? {
    val cleaned = value
        ?.replace('\u0000', ' ')
        ?.replace('\u00A0', ' ')
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()

    return cleaned.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
}

private fun firstCleanMetadataValue(vararg values: String?): String? {
    return values.firstNotNullOfOrNull { cleanMetadataValue(it) }
}

private fun LibraryItem.metadataSummary(): String? {
    return listOfNotNull(
        firstCleanMetadataValue(artist, albumArtist),
        album,
        year
    ).distinct().joinToString(" / ").takeIf { it.isNotBlank() }
}

private fun LibraryItem.needsLocalMetadataRefresh(): Boolean {
    if (sourceUrl != null && !hasDownloadedLocalCopy()) return false
    val hasCover = coverArtPath?.let { File(it).exists() && File(it).length() > 0L } == true
    return !hasCover || metadataSummary() == null || duration <= 0L
}

private fun isLocalMediaUriValue(value: String): Boolean {
    val scheme = runCatching { Uri.parse(value).scheme?.lowercase(Locale.US) }.getOrNull()
    return scheme.isNullOrBlank() || scheme == "file" || scheme == "content"
}

private fun LibraryItem.hasDownloadedLocalCopy(): Boolean {
    val media = mediaUri.trim()
    val source = sourceUrl?.trim().orEmpty()
    return source.isNotBlank() && media.isNotBlank() && media != source && isLocalMediaUriValue(media)
}

private fun LibraryItem.displaySourceLabel(): String {
    val source = sourceUrl
    return when {
        source != null && hasDownloadedLocalCopy() -> "${streamSourceLabel(source)} (local)"
        source != null -> streamSourceLabel(source)
        isVideo -> "Video"
        else -> "Audio"
    }
}

object ImageCache {
    val cache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()
}

object WaveformCache {
    private const val MAX_ITEMS = 80
    private val cache = object : LinkedHashMap<String, Pair<List<Float>, List<Float?>>>(MAX_ITEMS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<List<Float>, List<Float?>>>?): Boolean {
            return size > MAX_ITEMS
        }
    }

    @Synchronized
    fun get(key: String): Pair<List<Float>, List<Float?>>? = cache[key]

    @Synchronized
    fun put(key: String, value: Pair<List<Float>, List<Float?>>) {
        cache[key] = value
    }
}

object VLCManager {
    @Volatile
    private var libVLC: LibVLC? = null

    fun getLibVLC(context: Context): LibVLC {
        return libVLC ?: synchronized(this) {
            libVLC ?: try {
                val options = arrayListOf(
                    "--verbose=0",
                    "--network-caching=800",
                    "--clock-jitter=0",
                    "--clock-synchro=0",
                    "--file-caching=300"
                )
                LibVLC(context.applicationContext, options).also { libVLC = it }
            } catch (e: Exception) {
                LibVLC(context.applicationContext).also { libVLC = it }
            }
        }
    }
}

private class ShadowAudioRecorder(private val noiseCancellationEnabled: Boolean) {
    private var audioRecord: android.media.AudioRecord? = null
    private var encoder: android.media.MediaCodec? = null
    private var muxer: android.media.MediaMuxer? = null
    private var recordingThread: Thread? = null
    private var outputFile: File? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var wroteSamples = false

    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    private var echoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
    private var gainControl: android.media.audiofx.AutomaticGainControl? = null

    @Volatile
    private var shouldRecord = false

    @Volatile
    private var threadFailed = false

    fun start(file: File): Boolean {
        release()
        val sources = buildList {
            if (noiseCancellationEnabled) {
                add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                add(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                add(MediaRecorder.AudioSource.MIC)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) add(MediaRecorder.AudioSource.UNPROCESSED)
                add(MediaRecorder.AudioSource.MIC)
                add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            }
        }.distinct()

        for (source in sources) {
            try { file.delete() } catch (e: Exception) {}
            if (startWithSource(file, source)) return true
            release()
        }

        try { file.delete() } catch (e: Exception) {}
        return false
    }

    fun stop(): Boolean {
        shouldRecord = false
        try { audioRecord?.stop() } catch (e: Exception) {}

        val thread = recordingThread
        if (thread != null && thread.isAlive) {
            try {
                thread.join(5000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                threadFailed = true
            }
            if (thread.isAlive) threadFailed = true
        }

        val success = !threadFailed && wroteSamples && (outputFile?.length() ?: 0L) > 0L
        release()
        return success
    }

    fun release() {
        shouldRecord = false
        releaseEffect(gainControl)
        releaseEffect(echoCanceler)
        releaseEffect(noiseSuppressor)
        gainControl = null
        echoCanceler = null
        noiseSuppressor = null

        try { audioRecord?.stop() } catch (e: Exception) {}
        try { audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null

        try { encoder?.stop() } catch (e: Exception) {}
        try { encoder?.release() } catch (e: Exception) {}
        encoder = null

        try {
            if (muxerStarted && wroteSamples) muxer?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try { muxer?.release() } catch (e: Exception) {}
        muxer = null

        recordingThread = null
        outputFile = null
        trackIndex = -1
        muxerStarted = false
        wroteSamples = false
    }

    private fun startWithSource(file: File, source: Int): Boolean {
        val minBuffer = android.media.AudioRecord.getMinBufferSize(
            RECORD_SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false

        val pcmBufferSize = maxOf(minBuffer * 2, RECORD_SAMPLE_RATE / 5 * RECORD_BYTES_PER_FRAME)
        return try {
            val recorder = createAudioRecord(source, pcmBufferSize)
            if (recorder.state != android.media.AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return false
            }

            audioRecord = recorder
            if (noiseCancellationEnabled) attachVoiceEffects(recorder.audioSessionId)

            val format = android.media.MediaFormat.createAudioFormat(
                android.media.MediaFormat.MIMETYPE_AUDIO_AAC,
                RECORD_SAMPLE_RATE,
                RECORD_CHANNEL_COUNT
            ).apply {
                setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(android.media.MediaFormat.KEY_BIT_RATE, RECORD_BIT_RATE)
                setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, pcmBufferSize)
            }

            val nextEncoder = android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder = nextEncoder
            nextEncoder.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            val nextMuxer = android.media.MediaMuxer(file.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = nextMuxer

            outputFile = file
            trackIndex = -1
            muxerStarted = false
            wroteSamples = false
            threadFailed = false
            shouldRecord = true

            nextEncoder.start()
            recorder.startRecording()
            recordingThread = Thread({ encodeLoop() }, "RougoShadowAudioRecorder").also { it.start() }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @Suppress("MissingPermission")
    private fun createAudioRecord(source: Int, bufferSize: Int): android.media.AudioRecord {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.media.AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(RECORD_SAMPLE_RATE)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.media.AudioRecord(
                source,
                RECORD_SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }
    }

    private fun attachVoiceEffects(audioSessionId: Int) {
        noiseSuppressor = try {
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                android.media.audiofx.NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }

        echoCanceler = try {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                android.media.audiofx.AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }

        gainControl = try {
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                android.media.audiofx.AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun releaseEffect(effect: android.media.audiofx.AudioEffect?) {
        try { effect?.release() } catch (e: Exception) {}
    }

    private fun encodeLoop() {
        val recorder = audioRecord ?: return
        val nextEncoder = encoder ?: return
        val nextMuxer = muxer ?: return
        val info = android.media.MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var submittedFrames = 0L

        try {
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = nextEncoder.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = nextEncoder.getInputBuffer(inputIndex)
                        if (inputBuffer == null) {
                            nextEncoder.queueInputBuffer(inputIndex, 0, 0, 0L, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else if (shouldRecord) {
                            inputBuffer.clear()
                            val bytesRead = recorder.read(inputBuffer, inputBuffer.capacity(), android.media.AudioRecord.READ_BLOCKING)
                            if (bytesRead > 0) {
                                val presentationTimeUs = submittedFrames * 1_000_000L / RECORD_SAMPLE_RATE
                                nextEncoder.queueInputBuffer(inputIndex, 0, bytesRead, presentationTimeUs, 0)
                                submittedFrames += bytesRead / RECORD_BYTES_PER_FRAME
                            } else if (!shouldRecord) {
                                nextEncoder.queueInputBuffer(inputIndex, 0, 0, submittedFrames * 1_000_000L / RECORD_SAMPLE_RATE, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                threadFailed = true
                                nextEncoder.queueInputBuffer(inputIndex, 0, 0, submittedFrames * 1_000_000L / RECORD_SAMPLE_RATE, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            }
                        } else {
                            nextEncoder.queueInputBuffer(inputIndex, 0, 0, submittedFrames * 1_000_000L / RECORD_SAMPLE_RATE, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        }
                    }
                }

                var outputIndex = nextEncoder.dequeueOutputBuffer(info, 10000)
                while (outputIndex != android.media.MediaCodec.INFO_TRY_AGAIN_LATER) {
                    when {
                        outputIndex == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = nextMuxer.addTrack(nextEncoder.outputFormat)
                            nextMuxer.start()
                            muxerStarted = true
                        }
                        outputIndex >= 0 -> {
                            val outputBuffer = nextEncoder.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                if ((info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    info.size = 0
                                }
                                if (info.size > 0 && muxerStarted) {
                                    outputBuffer.position(info.offset)
                                    outputBuffer.limit(info.offset + info.size)
                                    nextMuxer.writeSampleData(trackIndex, outputBuffer, info)
                                    wroteSamples = true
                                }
                            }

                            outputEnded = (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            nextEncoder.releaseOutputBuffer(outputIndex, false)
                            if (outputEnded) break
                        }
                    }
                    outputIndex = nextEncoder.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Exception) {
            threadFailed = true
            e.printStackTrace()
        }
    }

    private companion object {
        const val RECORD_SAMPLE_RATE = 48000
        const val RECORD_CHANNEL_COUNT = 1
        const val RECORD_BIT_RATE = 160000
        const val RECORD_BYTES_PER_FRAME = 2 * RECORD_CHANNEL_COUNT
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

    fun getInstalledDictionaries(): List<String> {
        return dictsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    }

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
        items.firstOrNull { it.id == id }?.coverArtPath?.let { path ->
            try { File(path).delete() } catch (e: Exception) {}
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

data class UpdateInfo(val tagName: String, val downloadUrl: String, val body: String, val publishedAtMillis: Long)

private data class YoutubeSubtitleChoice(
    val label: String,
    val languageCode: String,
    val isAutoGenerated: Boolean
)

private data class YoutubeStreamFormat(
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

private data class YoutubeSetupData(
    val title: String,
    val fallbackUrl: String?,
    val formats: List<YoutubeStreamFormat>,
    val subtitleChoices: List<YoutubeSubtitleChoice>,
    val thumbnailUrl: String? = null,
    val httpUserAgent: String? = null,
    val httpReferer: String? = null
)

private data class YoutubeResolutionOption(val key: String, val label: String)
private data class AccentOption(val key: String, val label: String, val darkColor: Color, val lightColor: Color)
enum class LibraryDownloadState { Idle, Loading, Complete }

private enum class PairedPlaybackExitMode {
    PauseBoth,
    ContinueOriginal,
    ContinueVoice
}

private const val PREF_YOUTUBE_RESOLUTION = "youtube_preferred_resolution"
private const val PREF_YOUTUBE_AUTO_SUBTITLES = "youtube_auto_subtitles"
private const val PREF_YOUTUBE_SUBTITLE_LANGUAGE = "youtube_subtitle_language"
private const val PREF_SKIP_SECONDS = "player_skip_seconds"
private const val PREF_SUBTITLE_OFFSET_MS = "subtitle_offset_ms"
private const val PREF_LIGHT_MODE = "app_light_mode"
private const val PREF_THEME_MODE = "app_theme_mode"
private const val PREF_ACCENT_COLOR = "app_accent_color"
private const val DEFAULT_SKIP_SECONDS = 5
private const val DEFAULT_SUBTITLE_OFFSET_MS = 0L
private const val THEME_DARK = "dark"
private const val THEME_BLACK = "black"
private const val THEME_LIGHT = "light"
private const val THEME_SYSTEM = "system"
private const val YOUTUBE_RESOLUTION_ASK = "ask"
private const val YOUTUBE_RESOLUTION_HIGHEST = "highest"
private const val YOUTUBE_RESOLUTION_AUDIO = "audio"
private const val DEFAULT_YOUTUBE_RESOLUTION = "720"
private const val DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE = "ja"
private const val PLAYER_NOTIFICATION_CHANNEL_ID = "rougo_player_controls"
private const val PLAYER_NOTIFICATION_ID = 2407
private const val ACTION_PLAYER_PLAY_PAUSE = "com.selxo.rougo.action.PLAY_PAUSE"
private const val ACTION_PLAYER_REWIND = "com.selxo.rougo.action.REWIND"
private const val ACTION_PLAYER_FORWARD = "com.selxo.rougo.action.FORWARD"
private const val ACTION_PLAYER_STOP = "com.selxo.rougo.action.STOP"

private val YOUTUBE_RESOLUTION_OPTIONS = listOf(
    YoutubeResolutionOption("720", "720p"),
    YoutubeResolutionOption(YOUTUBE_RESOLUTION_ASK, "Ask every time"),
    YoutubeResolutionOption("480", "480p"),
    YoutubeResolutionOption("1080", "1080p"),
    YoutubeResolutionOption(YOUTUBE_RESOLUTION_HIGHEST, "Highest available"),
    YoutubeResolutionOption(YOUTUBE_RESOLUTION_AUDIO, "Audio only")
)

private val YOUTUBE_SUBTITLE_LANGUAGE_OPTIONS = listOf(
    YoutubeResolutionOption("ja", "Japanese"),
    YoutubeResolutionOption("en", "English"),
    YoutubeResolutionOption("zh-Hant", "Chinese (Traditional)"),
    YoutubeResolutionOption("zh-Hans", "Chinese (Simplified)"),
    YoutubeResolutionOption("ko", "Korean"),
    YoutubeResolutionOption("es", "Spanish"),
    YoutubeResolutionOption("any", "Best available")
)

private val THEME_MODE_OPTIONS = listOf(
    YoutubeResolutionOption(THEME_DARK, "Dark"),
    YoutubeResolutionOption(THEME_BLACK, "Black (OLED)"),
    YoutubeResolutionOption(THEME_LIGHT, "Light"),
    YoutubeResolutionOption(THEME_SYSTEM, "System")
)

private val ACCENT_OPTIONS = listOf(
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

private val PLAYER_NOTIFICATION_ACTIONS = arrayOf(
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

private fun rougoColorScheme(themeMode: String, accentKey: String, systemDark: Boolean): androidx.compose.material3.ColorScheme {
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

private fun extractFirstUrl(text: String): String? {
    val regex = Regex("(?i)\\b((?:https?://|www\\d{0,3}[.]|[a-z0-9.\\-]+[.][a-z]{2,6}/)(?:[^\\s()<>]+|\\((?:[^\\s()<>]+|\\([^\\s()<>]+\\))*\\))+(?:\\((?:[^\\s()<>]+|\\([^\\s()<>]+\\))*\\)|[^\\s`!()\\[\\]{};:'\".,<>?«»“”‘’]))")
    return regex.find(text)?.value?.trim()
}

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

@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var crashText by remember { mutableStateOf(CrashReporter.readLastCrash(context)) }
    val scrollState = rememberScrollState()

    if (crashText != null) {
        AlertDialog(
            onDismissRequest = {
                CrashReporter.clearLastCrash(context)
                crashText = null
            },
            title = { Text("朗語 crashed last time", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(scrollState)) {
                    Text(
                        "The crash report was saved so this can be debugged.",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        crashText.orEmpty().take(4000),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    copyTextToClipboard(context, "朗語 crash report", crashText.orEmpty())
                    Toast.makeText(context, "Crash report copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = {
                    CrashReporter.clearLastCrash(context)
                    crashText = null
                }) { Text("Clear") }
            }
        )
    }
}

private fun copyTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
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
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val currentVersion = pInfo.versionName
                if (isUpdateAvailable(info, currentVersion, pInfo.lastUpdateTime)) {
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
                    Text("A new version of 朗語 is available. Update now to access new features and bug fixes.")
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

@Composable
fun HelpDialog(showDialog: Boolean, onDismiss: () -> Unit) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Welcome to 朗語", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add local audio/video from the Library, or paste/share a video link to stream or download it.")
                    Text("Use headphones for shadowing so the microphone captures your voice instead of the source audio.")
                    Text("Install multiple pitch and dictionary sources from Settings > Dictionaries for better lookups.")
                    Text("This is built for listening and shadowing, not an Anki-mining workflow.")
                    Text("Record short segments, compare the waveforms, then repeat the segment until the rhythm feels natural.")
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) { Text("Done") }
            }
        )
    }
}

enum class AppScreen { Library, Player, Settings, DictionarySettings }

@Composable
fun MainAppFlow(
    sharedUrl: String?,
    onSharedUrlProcessed: () -> Unit,
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    accentColor: String,
    onAccentColorChanged: (String) -> Unit,
    systemDark: Boolean
) {
    val context = LocalContext.current
    val libraryManager = remember { LibraryManager(context) }
    var currentScreen by remember { mutableStateOf(AppScreen.Library) }
    var activeItem by remember { mutableStateOf<LibraryItem?>(null) }
    var libraryItems by remember { mutableStateOf(libraryManager.getItems()) }
    var manualVideoUrl by remember { mutableStateOf<String?>(null) }

    val pendingVideoUrl = sharedUrl ?: manualVideoUrl
    if (pendingVideoUrl != null) {
        YtStreamDialog(
            url = pendingVideoUrl,
            onDismiss = {
                if (sharedUrl != null) onSharedUrlProcessed() else manualVideoUrl = null
            },
            onComplete = { newItem ->
                libraryManager.saveItem(newItem)
                libraryItems = libraryManager.getItems()
                if (sharedUrl != null) onSharedUrlProcessed() else manualVideoUrl = null
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
            onOpenSettings = { currentScreen = AppScreen.Settings },
            onAddLink = { url -> manualVideoUrl = url }
        )
    } else if (currentScreen == AppScreen.Settings) {
        BackHandler { currentScreen = AppScreen.Library }
        SettingsScreen(
            onBack = { currentScreen = AppScreen.Library },
            onNavigateToDictionaries = { currentScreen = AppScreen.DictionarySettings },
            themeMode = themeMode,
            onThemeModeChanged = onThemeModeChanged,
            accentColor = accentColor,
            onAccentColorChanged = onAccentColorChanged,
            systemDark = systemDark
        )
    } else if (currentScreen == AppScreen.DictionarySettings) {
        BackHandler { currentScreen = AppScreen.Settings }
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
    val uiScope = rememberCoroutineScope()
    val sourceLabel = remember(url) { streamSourceLabel(url) }
    val downloadBeforePlayback = remember(url) { isBilibiliUrl(url) || isNiconicoUrl(url) }
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val preferredResolution = remember {
        prefs.getString(PREF_YOUTUBE_RESOLUTION, DEFAULT_YOUTUBE_RESOLUTION) ?: DEFAULT_YOUTUBE_RESOLUTION
    }
    val shouldAutoDownloadSubtitles = remember {
        prefs.getBoolean(PREF_YOUTUBE_AUTO_SUBTITLES, true)
    }
    val preferredSubtitleLanguage = remember {
        prefs.getString(PREF_YOUTUBE_SUBTITLE_LANGUAGE, DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE) ?: DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE
    }
    var status by remember { mutableStateOf("Fetching $sourceLabel stream...") }
    var setupData by remember { mutableStateOf<YoutubeSetupData?>(null) }
    var subtitleChoices by remember { mutableStateOf<List<YoutubeSubtitleChoice>>(emptyList()) }
    var selectedFormat by remember { mutableStateOf<YoutubeStreamFormat?>(null) }
    var selectedSubtitleKey by remember { mutableStateOf<String?>(null) }
    var isAutoSub by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        try {
            val fetchedSetupData = withContext(Dispatchers.IO) {
                fetchYoutubeSetupData(context, url)
            }
            setupData = fetchedSetupData
            subtitleChoices = fetchedSetupData.subtitleChoices
            if (shouldAutoDownloadSubtitles && selectedSubtitleKey == null) {
                selectPreferredYoutubeSubtitle(fetchedSetupData.subtitleChoices, preferredSubtitleLanguage)?.let { choice ->
                    selectedSubtitleKey = choice.languageCode
                    isAutoSub = choice.isAutoGenerated
                }
            }

            if (downloadBeforePlayback) {
                isProcessing = true
                status = "Downloading $sourceLabel video..."
                val downloadedItem = withContext(Dispatchers.IO) {
                    downloadVideoLinkToLibraryItem(context, url)
                }
                if (downloadedItem != null) {
                    onComplete(downloadedItem)
                } else {
                    status = "$sourceLabel download failed."
                    delay(3000)
                    onDismiss()
                }
                return@LaunchedEffect
            }

            if (preferredResolution != YOUTUBE_RESOLUTION_ASK) {
                val format = selectPreferredYoutubeFormat(fetchedSetupData.formats, preferredResolution)
                if (format != null) {
                    isProcessing = true
                    status = "Opening ${youtubeResolutionLabel(preferredResolution)}..."
                    val subtitleChoice = if (shouldAutoDownloadSubtitles) {
                        selectPreferredYoutubeSubtitle(fetchedSetupData.subtitleChoices, preferredSubtitleLanguage)
                    } else {
                        null
                    }
                    val subtitleUri = if (subtitleChoice != null) {
                        status = "Fetching subtitles..."
                        withContext(Dispatchers.IO) {
                            downloadPreferredYoutubeSubtitle(context, url, fetchedSetupData.subtitleChoices, preferredSubtitleLanguage)
                        }
                    } else {
                        null
                    }

                    onComplete(createYoutubeLibraryItem(context, fetchedSetupData, format, subtitleUri, url))
                } else {
                    status = "Preferred quality unavailable. Pick another format."
                }
            }
        } catch (e: Exception) {
            status = "Failed: ${e.localizedMessage}"
            delay(3000)
            onDismiss()
        }
    }

    fun openSelectedFormat(format: YoutubeStreamFormat, subtitleUri: String?) {
        uiScope.launch {
            onComplete(createYoutubeLibraryItem(context, setupData, format, subtitleUri, url))
        }
    }

    suspend fun resolveSelectedSubtitle(): String? {
        val selectedSubtitleLanguage = selectedSubtitleKey
        return if (selectedSubtitleLanguage != null) {
            withContext(Dispatchers.IO) {
                downloadYoutubeSubtitle(context, url, selectedSubtitleLanguage, isAutoSub)
            }
        } else {
            null
        }
    }

    LaunchedEffect(selectedFormat) {
        val format = selectedFormat
        if (format != null) {
            isProcessing = true
            val selectedSubtitleLanguage = selectedSubtitleKey
            status = if (selectedSubtitleLanguage != null) "Fetching subtitles..." else "Opening stream..."

            val subtitleUri = resolveSelectedSubtitle()

            if (selectedSubtitleLanguage != null && subtitleUri == null) {
                Toast.makeText(context, "Subtitle download failed; opening video without subtitles.", Toast.LENGTH_SHORT).show()
            }
            openSelectedFormat(format, subtitleUri)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isProcessing && setupData == null) onDismiss() },
        title = { Text("$sourceLabel Setup", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (setupData == null || isProcessing) {
                    Text(status, color = Color.LightGray, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary, trackColor = Color.DarkGray
                    )
                } else {
                    Text("1. Select Subtitles (Optional):", color = Color.LightGray, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))

                    if (subtitleChoices.isEmpty()) {
                        Text("No captions found", color = Color.Gray, fontSize = 13.sp)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                            items(subtitleChoices) { option ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedSubtitleKey = if (selectedSubtitleKey == option.languageCode && isAutoSub == option.isAutoGenerated) null else option.languageCode
                                            isAutoSub = option.isAutoGenerated
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = (selectedSubtitleKey == option.languageCode && isAutoSub == option.isAutoGenerated),
                                        onClick = null
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(option.label, color = Color.White, fontSize = 14.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("2. Select Quality:", color = Color.LightGray, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    val formats = setupData?.formats?.filter {
                        ((it.vcodec != "none" && it.acodec != "none") || (it.vcodec == "none" && it.acodec != "none")) &&
                            (!it.url.isNullOrBlank() || !it.manifestUrl.isNullOrBlank())
                    }?.distinctBy { it.formatId ?: "${it.height}-${it.ext}-${it.vcodec}-${it.acodec}" }
                        ?.sortedWith(
                            compareByDescending<YoutubeStreamFormat> { if (it.vcodec == "none") 0 else it.height }
                                .thenByDescending { if (it.isVlcFriendlyVideoFormat() || it.isVlcFriendlyAudioFormat()) 1 else 0 }
                                .thenByDescending { it.tbr }
                        )
                        ?: emptyList()

                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(formats) { format ->
                            val isAudioOnly = format.vcodec == "none"
                            val resolutionText = if (isAudioOnly) "Audio Only" else {
                                if (format.height > 0) "${format.height}p" else format.formatNote ?: "Standard"
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
            if (setupData == null || isProcessing) {
                Button(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

private val mediaToolsInitLock = Any()

@Volatile
private var mediaToolsInitialized = false

@Volatile
private var mediaToolsInitFailureLogged = false

private fun ensureMediaToolsReady(context: Context): Boolean {
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

private fun isYoutubeUrl(url: String): Boolean {
    val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
    return host.contains("youtube.com") || host.contains("youtu.be")
}

private fun isBilibiliUrl(url: String): Boolean {
    val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
    return host.contains("bilibili.com") || host == "b23.tv" || host.endsWith(".b23.tv")
}

private fun isNiconicoUrl(url: String): Boolean {
    val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
    return host.contains("nicovideo.jp") || host.contains("nico.ms")
}

private fun streamSourceLabel(url: String?): String {
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
        .addOption("--socket-timeout", "20")
        .addOption("--retries", "2")
        .addOption("--fragment-retries", "2")
        .addOption("--cache-dir", cacheDir.absolutePath)

    if (skipDownload) configured.addOption("--skip-download")
    if (isYoutubeUrl(sourceUrl)) configured.addOption("--extractor-args", "youtube:player_client=android,web")

    return configured
}

private fun fetchYoutubeSetupData(context: Context, url: String): YoutubeSetupData {
    val json = fetchYoutubeInfoJson(context, url)
    val headers = parseStreamHttpHeaders(json)

    return YoutubeSetupData(
        title = json.optString("title", "YouTube Stream"),
        fallbackUrl = json.optString("url").takeIf { it.isNotBlank() },
        formats = parseYoutubeFormats(json),
        subtitleChoices = parseYoutubeSubtitleChoices(json),
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

private suspend fun createYoutubeLibraryItem(
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

private fun downloadRemoteCover(context: Context, itemId: String, url: String): String? {
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

private fun youtubeResolutionLabel(key: String): String {
    return YOUTUBE_RESOLUTION_OPTIONS.firstOrNull { it.key == key }?.label ?: "Ask every time"
}

private fun hasPlayerNotificationPermission(context: Context): Boolean {
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

private fun showPlayerNotification(
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

private fun cancelPlayerNotification(context: Context) {
    try {
        NotificationManagerCompat.from(context).cancel(PLAYER_NOTIFICATION_ID)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun selectPreferredYoutubeSubtitle(choices: List<YoutubeSubtitleChoice>, preferredLanguage: String): YoutubeSubtitleChoice? {
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

private fun downloadPreferredYoutubeSubtitle(
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

private fun selectPreferredYoutubeFormat(
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

private fun resolveYoutubeStreamUrl(context: Context, url: String, formatId: String?): String? {
    val json = fetchYoutubeInfoJson(context, url)
    val targetFormat = parseYoutubeFormats(json).firstOrNull { it.formatId == formatId }
    return targetFormat?.bestPlaybackUrl() ?: json.optString("url").takeIf { it.isNotBlank() }
}

private fun fetchYoutubeInfoJson(context: Context, url: String): JSONObject {
    check(ensureMediaToolsReady(context)) { "Media tools are unavailable." }
    val request = addFastYoutubeOptions(context, YoutubeDLRequest(url), url)
    request.addOption("--dump-json")

    val response = YoutubeDL.getInstance().execute(request, null, false)
    return JSONObject(extractDumpedJson(response.out))
}

private fun downloadYoutubeSubtitle(context: Context, url: String, languageCode: String, isAutoGenerated: Boolean): String? {
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

private fun downloadVideoLinkToLibraryItem(context: Context, url: String, existingItem: LibraryItem? = null): LibraryItem? {
    if (!ensureMediaToolsReady(context)) return null

    val setupData = runCatching { fetchYoutubeSetupData(context, url) }.getOrNull()
    val itemId = existingItem?.id ?: UUID.randomUUID().toString()
    val destDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "RougoDownloads").apply { mkdirs() }
    val fileId = itemId.replace(Regex("[^A-Za-z0-9_-]"), "_")

    val request = addFastYoutubeOptions(context, YoutubeDLRequest(url), url, skipDownload = false)
    request.addOption("-f", "bestvideo[height<=720]+bestaudio/best[height<=720]/best")
    request.addOption("--merge-output-format", "mp4")
    request.addOption("-o", "${destDir.absolutePath}/$fileId.%(ext)s")

    return try {
        YoutubeDL.getInstance().execute(request, fileId, false)
        val downloadedFile = destDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(fileId) && it.length() > 0L && !it.name.endsWith(".part") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null

        val mediaUri = Uri.fromFile(downloadedFile)
        val metadata = extractMediaMetadata(context, mediaUri, itemId, isVideo = true)
        val fallbackTitle = setupData?.title ?: existingItem?.title ?: downloadedFile.nameWithoutExtension
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

private fun initialPlayableMediaUri(item: LibraryItem): String? {
    val mediaUri = item.mediaUri.trim()
    if (mediaUri.isBlank()) return null
    if (item.sourceUrl == null) return mediaUri
    return mediaUri.takeIf { item.hasDownloadedLocalCopy() }
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

private fun YoutubeStreamFormat.isVlcFriendlyVideoFormat(): Boolean {
    if (vcodec == "none" || acodec == "none") return false
    val extValue = ext?.lowercase(Locale.US).orEmpty()
    val videoCodec = vcodec?.lowercase(Locale.US).orEmpty()
    val audioCodec = acodec?.lowercase(Locale.US).orEmpty()
    return extValue == "mp4" &&
        (videoCodec.startsWith("avc") || videoCodec.startsWith("h264")) &&
        (audioCodec.startsWith("mp4a") || audioCodec.startsWith("aac"))
}

private fun YoutubeStreamFormat.isVlcFriendlyAudioFormat(): Boolean {
    if (vcodec != "none" || acodec == "none") return false
    val extValue = ext?.lowercase(Locale.US).orEmpty()
    val audioCodec = acodec?.lowercase(Locale.US).orEmpty()
    return extValue == "m4a" || extValue == "mp4" || audioCodec.startsWith("mp4a") || audioCodec.startsWith("aac")
}

private fun LibraryItem.persistableMediaUri(actualMediaUri: String?): String {
    if (sourceUrl == null || hasDownloadedLocalCopy()) return actualMediaUri ?: mediaUri
    return sourceUrl
}

private fun extractMediaMetadata(context: Context, uri: Uri, itemId: String, isVideo: Boolean): MediaMetadataSnapshot {
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

private fun MediaMetadataRetriever.cleanMetadata(keyCode: Int): String? {
    return try {
        cleanMetadataValue(extractMetadata(keyCode))
    } catch (e: Exception) {
        null
    }
}

private fun decodeSampledBitmapFile(path: String, maxSize: Int = 1024): Bitmap? {
    val file = File(path)
    if (!file.exists() || file.length() <= 0L) return null

    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

    var scale = 1
    while (boundsOptions.outWidth / scale > maxSize || boundsOptions.outHeight / scale > maxSize) scale *= 2

    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = scale })
}

private fun cacheCoverBytes(context: Context, itemId: String, bytes: ByteArray): String? {
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

private fun mergeMetadataIntoItem(item: LibraryItem, metadata: MediaMetadataSnapshot, fallbackTitle: String = item.title): LibraryItem {
    return item.copy(
        title = metadata.title ?: cleanMetadataValue(fallbackTitle) ?: item.title,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    items: List<LibraryItem>,
    onRefresh: () -> Unit,
    onItemClick: (LibraryItem) -> Unit,
    onDelete: (LibraryItem) -> Unit,
    onOpenSettings: () -> Unit,
    onAddLink: (String) -> Unit
) {
    val context = LocalContext.current
    val importScope = rememberCoroutineScope()
    val libraryManager = remember { LibraryManager(context) }

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingMediaUri by remember { mutableStateOf<Uri?>(null) }
    var pendingTitle by remember { mutableStateOf("") }
    var isVideoType by remember { mutableStateOf(false) }
    var isImportingMedia by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }
    var sortMode by remember { mutableStateOf("Recent") }
    var showSortMenu by remember { mutableStateOf(false) }
    var attemptedMetadataRefresh by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var linkText by remember { mutableStateOf("") }
    var isDownloadingLink by remember { mutableStateOf(false) }
    val downloadStates = remember { mutableStateMapOf<String, LibraryDownloadState>() }

    val filteredItems = remember(items, searchQuery, selectedFilter, sortMode) {
        val query = searchQuery.trim().lowercase(Locale.US)
        val base = items.filter { item ->
            val matchesQuery = query.isEmpty() || item.title.lowercase(Locale.US).contains(query)
            val matchesFilter = when (selectedFilter) {
                "Audio" -> !item.isVideo
                "Video" -> item.isVideo
                "YouTube" -> item.sourceUrl != null
                "Local" -> item.sourceUrl == null
                else -> true
            }
            matchesQuery && matchesFilter
        }

        when (sortMode) {
            "Title" -> base.sortedBy { it.title.lowercase(Locale.US) }
            "Progress" -> base.sortedByDescending { if (it.duration > 0L) it.progress.toFloat() / it.duration.toFloat() else 0f }
            "Recordings" -> base.sortedByDescending { it.recordings.size }
            else -> base
        }
    }

    val totalRecordings = remember(items) { items.sumOf { it.recordings.size } }
    val inProgressCount = remember(items) { items.count { it.duration > 0L && it.progress > 0L } }

    LaunchedEffect(items) {
        if (attemptedMetadataRefresh) return@LaunchedEffect
        val candidates = items.filter { it.needsLocalMetadataRefresh() }
        if (candidates.isEmpty()) {
            attemptedMetadataRefresh = true
            return@LaunchedEffect
        }

        attemptedMetadataRefresh = true
        var changed = false
        withContext(Dispatchers.IO) {
            candidates.forEach { item ->
                val metadata = extractMediaMetadata(context, Uri.parse(item.mediaUri), item.id, item.isVideo)
                val updatedItem = mergeMetadataIntoItem(item, metadata)
                if (updatedItem != item) {
                    libraryManager.saveItem(updatedItem)
                    changed = true
                }
            }
        }
        if (changed) onRefresh()
    }

    fun savePendingMedia(subtitleUri: Uri?) {
        val mediaUri = pendingMediaUri ?: return
        val fallbackTitle = pendingTitle.ifBlank { getFileName(context, mediaUri) }
        val itemId = UUID.randomUUID().toString()

        isImportingMedia = true
        importScope.launch {
            val metadata = withContext(Dispatchers.IO) {
                extractMediaMetadata(context, mediaUri, itemId, isVideoType)
            }
            val baseItem = LibraryItem(
                id = itemId,
                title = fallbackTitle,
                mediaUri = mediaUri.toString(),
                subtitleUri = subtitleUri?.toString(),
                progress = 0L,
                duration = metadata.durationMs ?: 0L,
                isVideo = isVideoType
            )
            libraryManager.saveItem(mergeMetadataIntoItem(baseItem, metadata, fallbackTitle))

            isImportingMedia = false
            showAddDialog = false
            pendingMediaUri = null
            pendingTitle = ""
            onRefresh()
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
            savePendingMedia(uri)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(onClick = { mediaLauncher.launch(arrayOf("audio/*", "video/*")) }, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Library", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "${items.size} items | $inProgressCount started | $totalRecordings recordings",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(Icons.Default.HelpOutline, contentDescription = "Help", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                placeholder = { Text("Search library") },
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = { showLinkDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Stream or download video link")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(listOf("All", "Audio", "Video", "YouTube", "Local")) { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter) },
                            leadingIcon = if (selectedFilter == filter) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }

                Box {
                    TextButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(sortMode)
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        listOf("Recent", "Title", "Progress", "Recordings").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    sortMode = option
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (items.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No media yet", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Add local audio/video or share a YouTube link into 朗語.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp
                        )
                    }
                }
            } else if (filteredItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No matching items", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(filteredItems, key = { it.id }) { item ->
                        val hasLocalCopy = item.hasDownloadedLocalCopy()
                        val downloadState = downloadStates[item.id]
                            ?: if (hasLocalCopy) LibraryDownloadState.Complete else LibraryDownloadState.Idle
                        LibraryCard(
                            item = item,
                            onClick = { onItemClick(item) },
                            onDelete = { onDelete(item) },
                            downloadState = downloadState,
                            onDownload = item.sourceUrl?.takeUnless { hasLocalCopy }?.let { sourceUrl ->
                                {
                                    if (downloadState != LibraryDownloadState.Loading) {
                                        downloadStates[item.id] = LibraryDownloadState.Loading
                                        importScope.launch {
                                            val downloadedItem = withContext(Dispatchers.IO) {
                                                downloadVideoLinkToLibraryItem(context, sourceUrl, item)
                                            }
                                            if (downloadedItem != null) {
                                                libraryManager.saveItem(downloadedItem)
                                                downloadStates[item.id] = LibraryDownloadState.Complete
                                                onRefresh()
                                                Toast.makeText(context, "Downloaded video.", Toast.LENGTH_SHORT).show()
                                            } else {
                                                downloadStates.remove(item.id)
                                                Toast.makeText(context, "Download failed.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    HelpDialog(showDialog = showHelpDialog, onDismiss = { showHelpDialog = false })

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { if (!isImportingMedia) showAddDialog = false },
            title = { Text("Add Subtitles?") },
            text = {
                Text(
                    if (isImportingMedia) {
                        "Reading embedded metadata and cover art..."
                    } else {
                        "Would you like to attach a subtitle file (.srt, .vtt, .ass) to '$pendingTitle'?"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = { subtitleLauncher.launch(arrayOf("*/*")) },
                    enabled = !isImportingMedia
                ) { Text("Select Subtitles") }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { savePendingMedia(null) },
                    enabled = !isImportingMedia
                ) { Text("Skip") }
            }
        )
    }

    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDownloadingLink) showLinkDialog = false },
            title = { Text("Add Video Link") },
            text = {
                Column {
                    OutlinedTextField(
                        value = linkText,
                        onValueChange = { linkText = it },
                        singleLine = true,
                        enabled = !isDownloadingLink,
                        label = { Text("Video URL") },
                        placeholder = { Text("https://...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isDownloadingLink) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isDownloadingLink && extractFirstUrl(linkText) != null,
                    onClick = {
                        val url = extractFirstUrl(linkText) ?: return@Button
                        showLinkDialog = false
                        linkText = ""
                        onAddLink(url)
                    }
                ) { Text("Stream") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDownloadingLink && extractFirstUrl(linkText) != null,
                    onClick = {
                        val url = extractFirstUrl(linkText) ?: return@TextButton
                        isDownloadingLink = true
                        importScope.launch {
                            val downloadedItem = withContext(Dispatchers.IO) {
                                downloadVideoLinkToLibraryItem(context, url)
                            }
                            if (downloadedItem != null) {
                                libraryManager.saveItem(downloadedItem)
                                onRefresh()
                                Toast.makeText(context, "Downloaded video.", Toast.LENGTH_SHORT).show()
                                showLinkDialog = false
                                linkText = ""
                            } else {
                                Toast.makeText(context, "Download failed.", Toast.LENGTH_LONG).show()
                            }
                            isDownloadingLink = false
                        }
                    }
                ) { Text("Download") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToDictionaries: () -> Unit,
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    accentColor: String,
    onAccentColorChanged: (String) -> Unit,
    systemDark: Boolean
) {
    val context = LocalContext.current
    val engine = remember { DictionaryEngine.getInstance(context) }
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val settingsScope = rememberCoroutineScope()
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "V2.3"
    }
    val usesDarkSurfaces = when (themeMode) {
        THEME_LIGHT -> false
        THEME_SYSTEM -> systemDark
        else -> true
    }
    var manualUpdateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var noiseCancelEnabled by remember { mutableStateOf(engine.isNoiseCancellationEnabled()) }
    var preferredYoutubeResolution by remember {
        mutableStateOf(prefs.getString(PREF_YOUTUBE_RESOLUTION, DEFAULT_YOUTUBE_RESOLUTION) ?: DEFAULT_YOUTUBE_RESOLUTION)
    }
    var autoYoutubeSubtitles by remember {
        mutableStateOf(prefs.getBoolean(PREF_YOUTUBE_AUTO_SUBTITLES, true))
    }
    var preferredYoutubeSubtitleLanguage by remember {
        mutableStateOf(prefs.getString(PREF_YOUTUBE_SUBTITLE_LANGUAGE, DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE) ?: DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE)
    }
    var skipSeconds by remember {
        mutableIntStateOf(prefs.getInt(PREF_SKIP_SECONDS, DEFAULT_SKIP_SECONDS).coerceIn(1, 30))
    }
    var subtitleOffsetSteps by remember {
        mutableIntStateOf((prefs.getLong(PREF_SUBTITLE_OFFSET_MS, DEFAULT_SUBTITLE_OFFSET_MS) / 250L).toInt().coerceIn(-20, 20))
    }
    var showYoutubeQualityMenu by remember { mutableStateOf(false) }
    var showYoutubeSubtitleLanguageMenu by remember { mutableStateOf(false) }
    var showThemeMenu by remember { mutableStateOf(false) }

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
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Theme", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text("Choose dark, black, light, or system", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Box {
                            TextButton(onClick = { showThemeMenu = true }) {
                                Text(THEME_MODE_OPTIONS.firstOrNull { it.key == themeMode }?.label ?: "Dark")
                            }
                            DropdownMenu(
                                expanded = showThemeMenu,
                                onDismissRequest = { showThemeMenu = false }
                            ) {
                                THEME_MODE_OPTIONS.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            onThemeModeChanged(option.key)
                                            showThemeMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Text("Accent", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(ACCENT_OPTIONS) { option ->
                            val selected = accentColor == option.key
                            val swatch = if (usesDarkSurfaces) option.darkColor else option.lightColor
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(swatch)
                                    .clickable { onAccentColorChanged(option.key) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Icon(Icons.Default.Check, contentDescription = option.label, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth().clickable { onNavigateToDictionaries() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Dictionaries", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Manage and import Yomitan dictionaries", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Text("Noise Cancellation", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Reduce background noise during shadowing", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Player Controls", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text("Customize seek buttons and subtitle timing", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Skip Buttons", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.width(120.dp))
                        Slider(
                            value = skipSeconds.toFloat(),
                            onValueChange = {
                                val next = it.roundToInt().coerceIn(1, 30)
                                skipSeconds = next
                                prefs.edit { putInt(PREF_SKIP_SECONDS, next) }
                            },
                            valueRange = 1f..30f,
                            steps = 28,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${skipSeconds}s", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val subtitleOffsetMs = subtitleOffsetSteps * 250L
                        Text("Subtitle Offset", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.width(120.dp))
                        Slider(
                            value = subtitleOffsetSteps.toFloat(),
                            onValueChange = {
                                val next = it.roundToInt().coerceIn(-20, 20)
                                subtitleOffsetSteps = next
                                prefs.edit { putLong(PREF_SUBTITLE_OFFSET_MS, next * 250L) }
                            },
                            valueRange = -20f..20f,
                            steps = 39,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            String.format(Locale.US, "%.2fs", subtitleOffsetMs / 1000f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(56.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.HighQuality, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("YouTube Quality", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text("Skip quality picker when sharing videos", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Box {
                            TextButton(onClick = { showYoutubeQualityMenu = true }) {
                                Text(youtubeResolutionLabel(preferredYoutubeResolution))
                            }
                            DropdownMenu(
                                expanded = showYoutubeQualityMenu,
                                onDismissRequest = { showYoutubeQualityMenu = false }
                            ) {
                                YOUTUBE_RESOLUTION_OPTIONS.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            preferredYoutubeResolution = option.key
                                            prefs.edit { putString(PREF_YOUTUBE_RESOLUTION, option.key) }
                                            showYoutubeQualityMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(40.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto YouTube Subtitles", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                            Text("Use your preferred captions when quality picker is skipped", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Switch(
                            checked = autoYoutubeSubtitles,
                            onCheckedChange = {
                                autoYoutubeSubtitles = it
                                prefs.edit { putBoolean(PREF_YOUTUBE_AUTO_SUBTITLES, it) }
                            }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(40.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Subtitle Language", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                            Text("Default is Japanese when available", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Box {
                            TextButton(onClick = { showYoutubeSubtitleLanguageMenu = true }, enabled = autoYoutubeSubtitles) {
                                Text(YOUTUBE_SUBTITLE_LANGUAGE_OPTIONS.firstOrNull { it.key == preferredYoutubeSubtitleLanguage }?.label ?: preferredYoutubeSubtitleLanguage)
                            }
                            DropdownMenu(
                                expanded = showYoutubeSubtitleLanguageMenu,
                                onDismissRequest = { showYoutubeSubtitleLanguageMenu = false }
                            ) {
                                YOUTUBE_SUBTITLE_LANGUAGE_OPTIONS.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            preferredYoutubeSubtitleLanguage = option.key
                                            prefs.edit { putString(PREF_YOUTUBE_SUBTITLE_LANGUAGE, option.key) }
                                            showYoutubeSubtitleLanguageMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Version", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("$appVersion (朗語)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    TextButton(
                        enabled = !isCheckingUpdate,
                        onClick = {
                            isCheckingUpdate = true
                            settingsScope.launch {
                                val info = withContext(Dispatchers.IO) { checkForUpdates() }
                                val pInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
                                if (info != null && pInfo != null && isUpdateAvailable(info, pInfo.versionName, pInfo.lastUpdateTime)) {
                                    manualUpdateInfo = info
                                } else {
                                    Toast.makeText(context, "朗語 is up to date.", Toast.LENGTH_SHORT).show()
                                }
                                prefs.edit { putLong("last_update_check", System.currentTimeMillis()) }
                                isCheckingUpdate = false
                            }
                        }
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Check")
                        }
                    }
                }
            }
        }
    }

    manualUpdateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { manualUpdateInfo = null },
            title = { Text("Update Available (${info.tagName})", fontWeight = FontWeight.Bold) },
            text = { Text(info.body.ifBlank { "A new version of 朗語 is available." }, fontSize = 12.sp) },
            confirmButton = {
                Button(onClick = {
                    manualUpdateInfo = null
                    downloadAndInstallUpdate(context, info.downloadUrl)
                }) { Text("Update Now") }
            },
            dismissButton = {
                TextButton(onClick = { manualUpdateInfo = null }) { Text("Later") }
            }
        )
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
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
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
                    Text("No dictionaries installed.\nTap + to import a Yomitan ZIP.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sortedDicts) { dictName ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Text(dictName, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))

                                Row {
                                    IconButton(onClick = { moveDict(dictName, true) }, enabled = sortedDicts.indexOf(dictName) > 0) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up", tint = if (sortedDicts.indexOf(dictName) > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant)
                                    }
                                    IconButton(onClick = { moveDict(dictName, false) }, enabled = sortedDicts.indexOf(dictName) < sortedDicts.size - 1) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down", tint = if (sortedDicts.indexOf(dictName) < sortedDicts.size - 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant)
                                    }
                                    IconButton(onClick = {
                                        engine.deleteDict(dictName)
                                        installedDicts = engine.getInstalledDictionaries()
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val dictionaryEngine = remember { DictionaryEngine.getInstance(context) }
    var showDictQuery by remember { mutableStateOf<String?>(null) }
    val uiScope = rememberCoroutineScope()
    val skipSeconds = remember { prefs.getInt(PREF_SKIP_SECONDS, DEFAULT_SKIP_SECONDS).coerceIn(1, 30) }
    val skipDurationMs = skipSeconds * 1000L

    var isPlaying by remember { mutableStateOf(false) }
    var hasReachedEnd by remember { mutableStateOf(false) }
    var isPlayerNotificationVisible by remember { mutableStateOf(true) }
    var currentPos by remember { mutableLongStateOf(libraryItem.progress) }
    var duration by remember { mutableLongStateOf(libraryItem.duration) }
    var isSubtitlesVisible by remember { mutableStateOf(libraryItem.subtitleUri != null || libraryItem.isVideo) }
    var subtitleDelayMs by remember {
        mutableLongStateOf(prefs.getLong(PREF_SUBTITLE_OFFSET_MS, DEFAULT_SUBTITLE_OFFSET_MS).coerceIn(-5000L, 5000L))
    }

    var parsedAudioCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var isParsingSubtitles by remember { mutableStateOf(libraryItem.subtitleUri != null) }
    var currentSubtitleText by remember { mutableStateOf("") }
    var youtubeSubtitleChoices by remember(libraryItem.id) { mutableStateOf<List<YoutubeSubtitleChoice>>(emptyList()) }
    var isLoadingYoutubeSubtitleChoices by remember(libraryItem.id) { mutableStateOf(false) }
    var youtubeSubtitleChoicesLoaded by remember(libraryItem.id) { mutableStateOf(false) }
    var selectedYoutubeSubtitleKey by remember(libraryItem.id) { mutableStateOf<String?>(null) }
    var autoSubtitleRetryAttempted by remember(libraryItem.id) { mutableStateOf(false) }

    val albumArt = if (libraryItem.sourceUrl == null || libraryItem.hasDownloadedLocalCopy()) {
        loadAlbumArt(context, libraryItem.mediaUri, libraryItem.isVideo, libraryItem.coverArtPath)
    } else {
        null
    }

    var isRecording by remember { mutableStateOf(false) }
    var shadowAudioRecorder by remember { mutableStateOf<ShadowAudioRecorder?>(null) }
    var recordStartTime by remember { mutableLongStateOf(0L) }

    val recordings = remember { mutableStateListOf<ShadowRecording>().apply { addAll(libraryItem.recordings) } }
    var activeOriginalSegment by remember { mutableStateOf<ShadowRecording?>(null) }
    var activeBothSegment by remember { mutableStateOf<ShadowRecording?>(null) }
    var continuingOriginalSegment by remember { mutableStateOf<ShadowRecording?>(null) }
    var pairedPlaybackExitMode by remember { mutableStateOf(PairedPlaybackExitMode.PauseBoth) }
    var repeatPracticeSegment by remember { mutableStateOf<ShadowRecording?>(null) }
    var repeatAttemptCount by remember { mutableIntStateOf(0) }

    var showBacklog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    val initialPlayableUri = remember(libraryItem.id) { initialPlayableMediaUri(libraryItem) }
    var actualMediaUri by remember(libraryItem.id) { mutableStateOf(initialPlayableUri) }
    var isRefreshingStream by remember(libraryItem.id) { mutableStateOf(libraryItem.sourceUrl != null && initialPlayableUri == null) }
    var streamRefreshAttempts by remember(libraryItem.id) { mutableIntStateOf(0) }
    var resumePlaybackAfterPause by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val libVlc = remember { VLCManager.getLibVLC(context) }
    val vlcPlayer = remember { VLCMediaPlayer(libVlc) }
    var videoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun seekMainPlayer(positionMs: Long, resumeAfterSeek: Boolean = isPlaying) {
        val upperBound = if (duration > 0L) (duration - 500L).coerceAtLeast(0L) else Long.MAX_VALUE
        val nextPosition = positionMs.coerceIn(0L, upperBound)
        try {
            if (hasReachedEnd) {
                vlcPlayer.stop()
                vlcPlayer.play()
                hasReachedEnd = false
            }
            vlcPlayer.time = nextPosition
            if (resumeAfterSeek) {
                vlcPlayer.play()
            } else if (!isRecording) {
                vlcPlayer.pause()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        currentPos = nextPosition
    }

    fun playMainPlayer() {
        try {
            isPlayerNotificationVisible = true
            if (hasReachedEnd || (duration > 0L && currentPos >= duration - 500L)) {
                vlcPlayer.stop()
                vlcPlayer.play()
                hasReachedEnd = false
                vlcPlayer.time = 0L
                currentPos = 0L
            } else {
                vlcPlayer.play()
            }
            isPlaying = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPlayerNotificationPermission(context)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(vlcPlayer, skipDurationMs, duration, isRecording) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_PLAYER_PLAY_PAUSE -> {
                        if (isRecording) return
                        if (vlcPlayer.isPlaying) {
                            vlcPlayer.pause()
                            isPlaying = false
                        } else {
                            playMainPlayer()
                        }
                    }
                    ACTION_PLAYER_REWIND -> {
                        if (isRecording) return
                        seekMainPlayer(vlcPlayer.time - skipDurationMs, resumeAfterSeek = vlcPlayer.isPlaying)
                    }
                    ACTION_PLAYER_FORWARD -> {
                        if (isRecording) return
                        seekMainPlayer(vlcPlayer.time + skipDurationMs, resumeAfterSeek = vlcPlayer.isPlaying)
                    }
                    ACTION_PLAYER_STOP -> {
                        try {
                            vlcPlayer.pause()
                            vlcPlayer.time = 0L
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        currentPos = 0L
                        isPlaying = false
                        isPlayerNotificationVisible = false
                        cancelPlayerNotification(context)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            PLAYER_NOTIFICATION_ACTIONS.forEach { addAction(it) }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        onDispose {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
            cancelPlayerNotification(context)
        }
    }

    LaunchedEffect(isPlayerNotificationVisible, isPlaying, currentPos / 1000L, duration, libraryItem.title, libraryItem.artist, libraryItem.album, libraryItem.coverArtPath, skipSeconds) {
        if (isPlayerNotificationVisible && actualMediaUri != null) {
            showPlayerNotification(context, libraryItem.title, libraryItem.metadataSummary(), libraryItem.coverArtPath, currentPos, duration, isPlaying, skipSeconds)
        } else {
            cancelPlayerNotification(context)
        }
    }

    DisposableEffect(lifecycleOwner, videoLayout, actualMediaUri) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (libraryItem.isVideo) {
                        resumePlaybackAfterPause = vlcPlayer.isPlaying
                        currentPos = vlcPlayer.time.coerceAtLeast(0)
                        try { vlcPlayer.pause() } catch (e: Exception) {}
                        try { vlcPlayer.detachViews() } catch (e: Exception) {}
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (libraryItem.isVideo && videoLayout != null && actualMediaUri != null) {
                        try {
                            vlcPlayer.detachViews()
                            vlcPlayer.attachViews(videoLayout!!, null, false, false)
                            if (currentPos > 0) vlcPlayer.time = currentPos
                            if (resumePlaybackAfterPause) vlcPlayer.play()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(libraryItem) {
        val currentSourceUrl = libraryItem.sourceUrl
        if (currentSourceUrl != null && actualMediaUri == null) {
            isRefreshingStream = true
            val resolvedUri = withContext(Dispatchers.IO) {
                try {
                    resolveYoutubeStreamUrl(context, currentSourceUrl, libraryItem.formatId)
                } catch (e: Exception) {
                    e.printStackTrace()
                    initialPlayableMediaUri(libraryItem)
                }
            }
            if (!resolvedUri.isNullOrBlank()) {
                actualMediaUri = resolvedUri
                libraryItem = libraryItem.copy(progress = currentPos)
                LibraryManager(context).saveItem(libraryItem)
            }
            isRefreshingStream = false
        }
    }

    fun syncWithStorage() {
        val updatedItem = libraryItem.copy(
            progress = currentPos,
            duration = duration,
            recordings = recordings.toList(),
            mediaUri = libraryItem.persistableMediaUri(actualMediaUri)
        )
        LibraryManager(context).saveItem(updatedItem)
    }

    var voiceCurrentPos by remember { mutableLongStateOf(-1L) }
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
    var activeVoiceSegmentId by remember { mutableStateOf<String?>(null) }

    fun playVoiceSegment(segment: ShadowRecording, startAtMs: Long = 0L) {
        try {
            voiceAudioPlayer.apply {
                reset()
                setDataSource(segment.filePath)
                setOnPreparedListener {
                    activeVoiceSegmentId = segment.id
                    val seekToMs = startAtMs.coerceAtLeast(0L).coerceAtMost((segment.endTime - segment.startTime).coerceAtLeast(0L))
                    if (seekToMs > 0L) seekTo(seekToMs.toInt())
                    start()
                }
                setOnCompletionListener {
                    activeVoiceSegmentId = null
                    voiceCurrentPos = -1L
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            activeVoiceSegmentId = null
            Toast.makeText(context, "Error playing audio", Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun prepareVoiceSegment(segment: ShadowRecording, startAtMs: Long = 0L): Boolean {
        val prepared = CompletableDeferred<Boolean>()
        return try {
            voiceAudioPlayer.apply {
                reset()
                setDataSource(segment.filePath)
                setOnPreparedListener {
                    activeVoiceSegmentId = segment.id
                    val seekToMs = startAtMs.coerceAtLeast(0L).coerceAtMost((segment.endTime - segment.startTime).coerceAtLeast(0L))
                    if (seekToMs > 0L) seekTo(seekToMs.toInt())
                    voiceCurrentPos = seekToMs
                    if (!prepared.isCompleted) prepared.complete(true)
                }
                setOnCompletionListener {
                    activeVoiceSegmentId = null
                    voiceCurrentPos = -1L
                }
                setOnErrorListener { _, _, _ ->
                    activeVoiceSegmentId = null
                    voiceCurrentPos = -1L
                    if (!prepared.isCompleted) prepared.complete(false)
                    true
                }
                prepareAsync()
            }
            withTimeoutOrNull(2500) { prepared.await() } == true
        } catch (e: Exception) {
            activeVoiceSegmentId = null
            voiceCurrentPos = -1L
            false
        }
    }

    fun toggleVoiceSegment(segment: ShadowRecording) {
        if (activeVoiceSegmentId == segment.id) {
            if (voiceAudioPlayer.isPlaying) {
                voiceAudioPlayer.pause()
            } else {
                voiceAudioPlayer.start()
            }
        } else {
            playVoiceSegment(segment)
        }
    }

    fun seekVoiceSegment(segment: ShadowRecording, positionMs: Long) {
        if (activeVoiceSegmentId == segment.id) {
            try {
                voiceAudioPlayer.seekTo(positionMs.coerceAtLeast(0L).toInt())
                voiceCurrentPos = positionMs
            } catch (e: Exception) {
                playVoiceSegment(segment, positionMs)
            }
        } else {
            playVoiceSegment(segment, positionMs)
        }
    }

    fun toggleBothSegment(segment: ShadowRecording) {
        if (activeBothSegment?.id == segment.id) {
            pairedPlaybackExitMode = PairedPlaybackExitMode.PauseBoth
            activeBothSegment = null
        } else {
            activeOriginalSegment = null
            continuingOriginalSegment = null
            pairedPlaybackExitMode = PairedPlaybackExitMode.PauseBoth
            activeBothSegment = segment
        }
    }

    fun pauseOriginalFromBoth(segment: ShadowRecording) {
        if (activeBothSegment?.id != segment.id) return
        pairedPlaybackExitMode = PairedPlaybackExitMode.ContinueVoice
        continuingOriginalSegment = null
        activeBothSegment = null
    }

    fun pauseVoiceFromBoth(segment: ShadowRecording) {
        if (activeBothSegment?.id != segment.id) return
        pairedPlaybackExitMode = PairedPlaybackExitMode.ContinueOriginal
        continuingOriginalSegment = segment
        activeBothSegment = null
    }

    DisposableEffect(Unit) {
        onDispose {
            try { vlcPlayer.stop() } catch (e: Exception) {}
            try { vlcPlayer.detachViews() } catch (e: Exception) {}
            try { vlcPlayer.release() } catch (e: Exception) {}
            try { voiceAudioPlayer.release() } catch (e: Exception) {}
            try { shadowAudioRecorder?.release() } catch (e: Exception) {}
        }
    }

    BackHandler {
        val updatedItem = libraryItem.copy(progress = currentPos, duration = duration, recordings = recordings.toList(), mediaUri = libraryItem.persistableMediaUri(actualMediaUri))
        onBack(updatedItem)
    }

    LaunchedEffect(activeOriginalSegment) {
        activeOriginalSegment?.let { segment ->
            try {
                seekMainPlayer(segment.startTime, resumeAfterSeek = true)

                var seekWaitCount = 0
                while (Math.abs(vlcPlayer.time - segment.startTime) > 1500 && seekWaitCount < 40) {
                    delay(50)
                    seekWaitCount++
                }

                while (vlcPlayer.time < segment.endTime) { delay(50) }
            } finally {
                vlcPlayer.pause()
                if (activeOriginalSegment?.id == segment.id) activeOriginalSegment = null
            }
        }
    }

    LaunchedEffect(activeBothSegment) {
        activeBothSegment?.let { segment ->
            try {
                try { voiceAudioPlayer.pause() } catch (e: Exception) {}
                seekMainPlayer(segment.startTime, resumeAfterSeek = false)

                var seekWaitCount = 0
                while (Math.abs(vlcPlayer.time - segment.startTime) > 600 && seekWaitCount < 24) {
                    delay(25)
                    seekWaitCount++
                }

                if (!prepareVoiceSegment(segment)) {
                    Toast.makeText(context, "Error playing recorded audio", Toast.LENGTH_SHORT).show()
                    return@let
                }

                val segmentDuration = (segment.endTime - segment.startTime).coerceAtLeast(1L)
                vlcPlayer.time = segment.startTime
                currentPos = segment.startTime
                voiceCurrentPos = 0L

                val playRequestedAtMs = SystemClock.elapsedRealtime()
                vlcPlayer.play()
                isPlaying = true

                while (
                    !vlcPlayer.isPlaying &&
                    SystemClock.elapsedRealtime() - playRequestedAtMs < 180L &&
                    activeBothSegment?.id == segment.id
                ) {
                    delay(10)
                }

                val originalOffsetAtStart = (vlcPlayer.time - segment.startTime).coerceIn(0L, segmentDuration)
                if (originalOffsetAtStart > 20L) {
                    try { voiceAudioPlayer.seekTo(originalOffsetAtStart.toInt()) } catch (e: Exception) {}
                    voiceCurrentPos = originalOffsetAtStart
                }

                if (activeBothSegment?.id == segment.id) {
                    voiceAudioPlayer.start()
                }

                while (activeBothSegment?.id == segment.id && vlcPlayer.time < segment.endTime) {
                    val originalOffset = (vlcPlayer.time - segment.startTime).coerceIn(0L, segmentDuration)
                    if (voiceAudioPlayer.isPlaying) {
                        val voiceOffset = voiceAudioPlayer.currentPosition.toLong().coerceAtLeast(0L)
                        val drift = originalOffset - voiceOffset
                        if (kotlin.math.abs(drift) > 120L && originalOffset < segmentDuration - 50L) {
                            try { voiceAudioPlayer.seekTo(originalOffset.toInt()) } catch (e: Exception) {}
                            voiceCurrentPos = originalOffset
                        }
                    }
                    delay(25)
                }
            } finally {
                when (pairedPlaybackExitMode) {
                    PairedPlaybackExitMode.PauseBoth -> {
                        try { vlcPlayer.pause() } catch (e: Exception) {}
                        if (activeVoiceSegmentId == segment.id && voiceAudioPlayer.isPlaying) {
                            try { voiceAudioPlayer.pause() } catch (e: Exception) {}
                        }
                        if (activeVoiceSegmentId == segment.id) {
                            activeVoiceSegmentId = null
                            voiceCurrentPos = -1L
                        }
                    }
                    PairedPlaybackExitMode.ContinueOriginal -> {
                        if (activeVoiceSegmentId == segment.id && voiceAudioPlayer.isPlaying) {
                            try { voiceAudioPlayer.pause() } catch (e: Exception) {}
                        }
                        if (activeVoiceSegmentId == segment.id) {
                            activeVoiceSegmentId = null
                            voiceCurrentPos = -1L
                        }
                        continuingOriginalSegment = segment
                    }
                    PairedPlaybackExitMode.ContinueVoice -> {
                        try { vlcPlayer.pause() } catch (e: Exception) {}
                    }
                }
                if (activeBothSegment?.id == segment.id) activeBothSegment = null
                pairedPlaybackExitMode = PairedPlaybackExitMode.PauseBoth
            }
        }
    }

    LaunchedEffect(continuingOriginalSegment) {
        continuingOriginalSegment?.let { segment ->
            try {
                if (!vlcPlayer.isPlaying) vlcPlayer.play()
                while (continuingOriginalSegment?.id == segment.id && vlcPlayer.time < segment.endTime) {
                    delay(50)
                }
            } finally {
                if (continuingOriginalSegment?.id == segment.id) {
                    try { vlcPlayer.pause() } catch (e: Exception) {}
                    continuingOriginalSegment = null
                }
            }
        }
    }

    LaunchedEffect(libraryItem.id, libraryItem.subtitleUri, libraryItem.sourceUrl) {
        val sourceUrl = libraryItem.sourceUrl
        if (
            !autoSubtitleRetryAttempted &&
            libraryItem.subtitleUri == null &&
            sourceUrl != null &&
            isYoutubeUrl(sourceUrl) &&
            prefs.getBoolean(PREF_YOUTUBE_AUTO_SUBTITLES, true)
        ) {
            autoSubtitleRetryAttempted = true
            val preferredLanguage = prefs.getString(PREF_YOUTUBE_SUBTITLE_LANGUAGE, DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE)
                ?: DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE
            val setupData = withContext(Dispatchers.IO) {
                runCatching { fetchYoutubeSetupData(context, sourceUrl) }.getOrNull()
            }
            val subtitleUri = if (setupData != null) {
                youtubeSubtitleChoices = setupData.subtitleChoices
                youtubeSubtitleChoicesLoaded = true
                withContext(Dispatchers.IO) {
                    downloadPreferredYoutubeSubtitle(context, sourceUrl, setupData.subtitleChoices, preferredLanguage)
                }
            } else {
                null
            }
            if (subtitleUri != null) {
                val updatedItem = libraryItem.copy(subtitleUri = subtitleUri)
                libraryItem = updatedItem
                isSubtitlesVisible = true
                selectedYoutubeSubtitleKey = null
                LibraryManager(context).saveItem(updatedItem)
            }
        }
    }

    LaunchedEffect(libraryItem.subtitleUri) {
        if (libraryItem.subtitleUri != null) {
            isParsingSubtitles = true
            withContext(Dispatchers.IO) {
                parsedAudioCues = parseSimpleSubtitles(context, libraryItem.subtitleUri!!.toUri())
            }
            isParsingSubtitles = false
        } else {
            parsedAudioCues = emptyList()
            isParsingSubtitles = false
        }
    }

    LaunchedEffect(currentPos, parsedAudioCues, isSubtitlesVisible, subtitleDelayMs, isParsingSubtitles) {
        if (isSubtitlesVisible) {
            if (isParsingSubtitles) {
                currentSubtitleText = "Loading subtitles..."
            } else if (parsedAudioCues.isNotEmpty()) {
                val effectivePos = currentPos - subtitleDelayMs
                val nextSubtitleText = findSubtitleCue(parsedAudioCues, effectivePos)?.text ?: ""
                if (currentSubtitleText != nextSubtitleText) currentSubtitleText = nextSubtitleText
            } else if (libraryItem.subtitleUri != null) {
                if (currentSubtitleText != "No valid text found in subtitle file.") {
                    currentSubtitleText = "No valid text found in subtitle file."
                }
            }
        } else {
            if (currentSubtitleText.isNotEmpty()) currentSubtitleText = ""
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

            media.addOption(":network-caching=800")
            media.addOption(":file-caching=300")
            media.addOption(":http-reconnect")
            libraryItem.httpUserAgent?.takeIf { it.isNotBlank() }?.let {
                media.addOption(":http-user-agent=$it")
            }
            libraryItem.httpReferer?.takeIf { it.isNotBlank() }?.let {
                media.addOption(":http-referrer=$it")
            }
            vlcPlayer.media = media
            media.release()

            var initialSeekDone = false

            val listener = VLCMediaPlayer.EventListener { event ->
                when (event.type) {
                    VLCMediaPlayer.Event.Playing -> {
                        isPlaying = true
                        hasReachedEnd = false
                        CoroutineScope(Dispatchers.Main).launch {
                            try {
                                if (!initialSeekDone && libraryItem.progress > 0) {
                                    vlcPlayer.time = libraryItem.progress
                                    initialSeekDone = true
                                }
                            } catch (e: Exception) {}
                        }
                    }
                    VLCMediaPlayer.Event.Paused, VLCMediaPlayer.Event.Stopped -> isPlaying = false
                    VLCMediaPlayer.Event.EndReached -> {
                        isPlaying = false
                        hasReachedEnd = true
                        if (duration > 0L) currentPos = duration
                    }
                    VLCMediaPlayer.Event.TimeChanged -> {
                        currentPos = event.timeChanged.coerceAtLeast(0)
                    }
                    VLCMediaPlayer.Event.LengthChanged -> {
                        if (event.lengthChanged > 0) duration = event.lengthChanged
                    }
                    VLCMediaPlayer.Event.EncounteredError -> {
                        isPlaying = false
                        val sourceUrl = libraryItem.sourceUrl
                        if (sourceUrl != null && !isRefreshingStream && streamRefreshAttempts < 1) {
                            uiScope.launch {
                                streamRefreshAttempts += 1
                                isRefreshingStream = true
                                val refreshedUri = withContext(Dispatchers.IO) {
                                    try {
                                        resolveYoutubeStreamUrl(context, sourceUrl, libraryItem.formatId)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        null
                                    }
                                }
                                if (!refreshedUri.isNullOrBlank()) {
                                    libraryItem = libraryItem.copy(progress = currentPos)
                                    LibraryManager(context).saveItem(libraryItem)
                                    actualMediaUri = refreshedUri
                                } else {
                                    Toast.makeText(context, "Playback failed. Stream might be geo-blocked or broken.", Toast.LENGTH_SHORT).show()
                                }
                                isRefreshingStream = false
                            }
                        } else {
                            uiScope.launch {
                                Toast.makeText(context, "Playback failed. Stream might be geo-blocked or broken.", Toast.LENGTH_SHORT).show()
                            }
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
                mediaUri = libraryItem.persistableMediaUri(actualMediaUri)
            )
            LibraryManager(context).saveItem(updatedItem)

            try { vlcPlayer.stop() } catch (e: Exception) {}
            try { vlcPlayer.detachViews() } catch (e: Exception) {}
            try { pfd?.close() } catch (e: Exception) {}
        }
    }

    var tempFilePath by remember { mutableStateOf("") }

    fun startRecordingToFile(file: File): Boolean {
        val recorder = ShadowAudioRecorder(dictionaryEngine.isNoiseCancellationEnabled())
        return if (recorder.start(file)) {
            shadowAudioRecorder = recorder
            isRecording = true
            true
        } else {
            recorder.release()
            false
        }
    }

    fun stopRecordingSafe(
        currentFilePath: String,
        startTimeOverride: Long? = null,
        endTimeOverride: Long? = null,
        pausePlayer: Boolean = true,
        showShortToast: Boolean = true
    ): Boolean {
        val recorder = shadowAudioRecorder ?: return false
        val endTime = endTimeOverride ?: vlcPlayer.time
        var success = true
        try {
            success = recorder.stop()
        } catch (e: Exception) {
            success = false
            if (showShortToast) Toast.makeText(context, "Recording was too short!", Toast.LENGTH_SHORT).show()
        } finally {
            shadowAudioRecorder = null
            isRecording = false
            if (pausePlayer) vlcPlayer.pause()
        }

        val startTime = startTimeOverride ?: recordStartTime
        if (success && endTime > startTime + MIN_SHADOW_SEGMENT_MS) {
            recordings.add(0, ShadowRecording(filePath = currentFilePath, startTime = startTime, endTime = endTime))
            syncWithStorage()
        } else {
            if (success && showShortToast) Toast.makeText(context, "Recording segment was too short.", Toast.LENGTH_SHORT).show()
            try { File(currentFilePath).delete() } catch (e: Exception) {}
            success = false
        }
        return success
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) Toast.makeText(context, "Mic required!", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(repeatPracticeSegment) {
        val segment = repeatPracticeSegment ?: return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            repeatPracticeSegment = null
            return@LaunchedEffect
        }
        if (segment.endTime <= segment.startTime + MIN_SHADOW_SEGMENT_MS) {
            Toast.makeText(context, "Segment is too short to repeat.", Toast.LENGTH_SHORT).show()
            repeatPracticeSegment = null
            return@LaunchedEffect
        }

        repeatAttemptCount = 0
        activeOriginalSegment = null
        continuingOriginalSegment = null
        pairedPlaybackExitMode = PairedPlaybackExitMode.PauseBoth
        activeBothSegment = null

        try {
            while (repeatPracticeSegment?.id == segment.id) {
                val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Shadowing_${System.currentTimeMillis()}.m4a")
                tempFilePath = file.absolutePath
                recordStartTime = segment.startTime

                vlcPlayer.time = segment.startTime
                if (!startRecordingToFile(file)) {
                    Toast.makeText(context, "Could not start recording.", Toast.LENGTH_SHORT).show()
                    repeatPracticeSegment = null
                    break
                }

                vlcPlayer.play()

                var seekWaitCount = 0
                while (
                    kotlin.math.abs(vlcPlayer.time - segment.startTime) > 1500 &&
                    seekWaitCount < 40 &&
                    repeatPracticeSegment?.id == segment.id
                ) {
                    delay(50)
                    seekWaitCount++
                }

                while (vlcPlayer.time < segment.endTime && repeatPracticeSegment?.id == segment.id) {
                    delay(50)
                }

                if (repeatPracticeSegment?.id != segment.id) break

                stopRecordingSafe(
                    currentFilePath = file.absolutePath,
                    startTimeOverride = segment.startTime,
                    endTimeOverride = segment.endTime,
                    pausePlayer = true,
                    showShortToast = false
                )
                repeatAttemptCount += 1
                delay(350)
            }
        } finally {
            if (shadowAudioRecorder != null && tempFilePath.isNotBlank()) {
                stopRecordingSafe(
                    currentFilePath = tempFilePath,
                    startTimeOverride = segment.startTime,
                    endTimeOverride = vlcPlayer.time.coerceAtLeast(segment.startTime),
                    pausePlayer = true,
                    showShortToast = false
                )
            }
            try { vlcPlayer.pause() } catch (e: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        var lastFrameMs = 0L
        var lastPolledMainPos = currentPos
        while (true) {
            val activeTimeline = isPlaying ||
                voiceAudioPlayer.isPlaying ||
                activeOriginalSegment != null ||
                activeBothSegment != null ||
                continuingOriginalSegment != null ||
                repeatPracticeSegment != null
            if (activeTimeline) {
                val frameMs = withFrameMillis { it }
                if (isPlaying || activeOriginalSegment != null || activeBothSegment != null || continuingOriginalSegment != null || repeatPracticeSegment != null) {
                    val polledPos = vlcPlayer.time.coerceAtLeast(0L)
                    val frameDelta = if (lastFrameMs > 0L) (frameMs - lastFrameMs).coerceIn(0L, 100L) else 0L
                    currentPos = if (polledPos == lastPolledMainPos && frameDelta > 0L && duration > 0L) {
                        (currentPos + frameDelta).coerceAtMost(duration)
                    } else {
                        polledPos
                    }
                    lastPolledMainPos = polledPos
                }

                val pairedSegment = activeBothSegment
                if (pairedSegment != null) {
                    val pairedDuration = (pairedSegment.endTime - pairedSegment.startTime).coerceAtLeast(1L)
                    voiceCurrentPos = (currentPos - pairedSegment.startTime).coerceIn(0L, pairedDuration)
                } else if (activeVoiceSegmentId != null) {
                    voiceCurrentPos = voiceAudioPlayer.currentPosition.toLong()
                } else {
                    voiceCurrentPos = -1L
                }
                lastFrameMs = frameMs
            } else {
                lastFrameMs = 0L
                lastPolledMainPos = currentPos
                delay(250)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                val updatedItem = libraryItem.copy(progress = currentPos, duration = duration, recordings = recordings.toList(), mediaUri = libraryItem.persistableMediaUri(actualMediaUri))
                onBack(updatedItem)
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(libraryItem.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                libraryItem.metadataSummary()?.let { summary ->
                    Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (libraryItem.subtitleUri != null || libraryItem.isVideo) {
                var showSubMenu by remember { mutableStateOf(false) }
                var spuTracks by remember { mutableStateOf<Array<org.videolan.libvlc.MediaPlayer.TrackDescription>>(emptyArray()) }

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
                        tint = if (isSubtitlesVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClick = { showSubMenu = true },
                                onLongClick = { showSubMenu = true }
                            )
                            .padding(8.dp)
                    )

                    DropdownMenu(
                        expanded = showSubMenu,
                        onDismissRequest = { showSubMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isSubtitlesVisible) "Hide Captions" else "Show Captions") },
                            onClick = {
                                isSubtitlesVisible = !isSubtitlesVisible
                                showSubMenu = false
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Add Custom Subtitles") },
                            onClick = {
                                showSubMenu = false
                                subtitleLauncher.launch(arrayOf("*/*"))
                            }
                        )

                        LaunchedEffect(showSubMenu) {
                            if (showSubMenu) spuTracks = vlcPlayer.spuTracks ?: emptyArray()
                        }

                        LaunchedEffect(showSubMenu, libraryItem.sourceUrl) {
                            val sourceUrl = libraryItem.sourceUrl
                            if (showSubMenu && sourceUrl != null && !youtubeSubtitleChoicesLoaded && !isLoadingYoutubeSubtitleChoices) {
                                isLoadingYoutubeSubtitleChoices = true
                                youtubeSubtitleChoices = withContext(Dispatchers.IO) {
                                    runCatching { fetchYoutubeSetupData(context, sourceUrl).subtitleChoices }.getOrDefault(emptyList())
                                }
                                youtubeSubtitleChoicesLoaded = true
                                isLoadingYoutubeSubtitleChoices = false
                            }
                        }

                        if (libraryItem.sourceUrl != null) {
                            if (isLoadingYoutubeSubtitleChoices) {
                                DropdownMenuItem(
                                    text = { Text("Loading YouTube Captions...") },
                                    enabled = false,
                                    onClick = {}
                                )
                            } else if (youtubeSubtitleChoices.isEmpty() && youtubeSubtitleChoicesLoaded) {
                                DropdownMenuItem(
                                    text = { Text("No YouTube Captions Found") },
                                    enabled = false,
                                    onClick = {}
                                )
                            } else {
                                youtubeSubtitleChoices.forEach { choice ->
                                    val choiceKey = "${choice.languageCode}:${choice.isAutoGenerated}"
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (selectedYoutubeSubtitleKey == choiceKey) {
                                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(Modifier.width(8.dp))
                                                }
                                                Text(choice.label)
                                            }
                                        },
                                        onClick = {
                                            val sourceUrl = libraryItem.sourceUrl ?: return@DropdownMenuItem
                                            showSubMenu = false
                                            selectedYoutubeSubtitleKey = choiceKey
                                            uiScope.launch {
                                                isParsingSubtitles = true
                                                val subtitleUri = withContext(Dispatchers.IO) {
                                                    downloadYoutubeSubtitle(context, sourceUrl, choice.languageCode, choice.isAutoGenerated)
                                                }
                                                if (subtitleUri != null) {
                                                    libraryItem = libraryItem.copy(subtitleUri = subtitleUri)
                                                    isSubtitlesVisible = true
                                                    LibraryManager(context).saveItem(libraryItem)
                                                } else {
                                                    Toast.makeText(context, "Subtitle download failed.", Toast.LENGTH_SHORT).show()
                                                    isParsingSubtitles = false
                                                }
                                            }
                                        }
                                    )
                                }
                            }

                            HorizontalDivider()
                        }

                        val visibleTracks = spuTracks.filter { it.id != -1 }
                        if (visibleTracks.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No Embedded Captions") },
                                enabled = false,
                                onClick = {}
                            )
                        }

                        visibleTracks.forEach { track ->
                            if (track.id != -1) {
                                DropdownMenuItem(
                                    text = { Text(track.name) },
                                    onClick = {
                                        vlcPlayer.spuTrack = track.id
                                        isSubtitlesVisible = true
                                        showSubMenu = false
                                    }
                                )
                            }
                        }

                        DropdownMenuItem(
                            text = { Text("Disable Embedded Subs") },
                            onClick = {
                                vlcPlayer.spuTrack = -1
                                isSubtitlesVisible = false
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
                        Text("Resolving live stream URL...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                } else if (libraryItem.isVideo) {
                    AndroidView(
                        factory = { ctx ->
                            VLCVideoLayout(ctx)
                        },
                        update = { view ->
                            if (videoLayout !== view) videoLayout = view
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
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), modifier = Modifier.size(80.dp))
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
                        Text(formatTime(currentPos), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        Slider(
                            value = currentPos.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                            onValueChange = {
                                seekMainPlayer(it.toLong(), resumeAfterSeek = isPlaying)
                            },
                            enabled = !isRecording,
                            valueRange = 0f..duration.toFloat().coerceAtLeast(1f), modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text(formatTime(duration), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }

                    if (!isRecording) {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                seekMainPlayer(currentPos - skipDurationMs, resumeAfterSeek = isPlaying)
                            }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.FastRewind, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp)) }
                            Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(if (isRefreshingStream) Color.DarkGray else MaterialTheme.colorScheme.primary).clickable(enabled = !isRefreshingStream) {
                                if (isPlaying) {
                                    vlcPlayer.pause()
                                } else {
                                    playMainPlayer()
                                }
                            }, contentAlignment = Alignment.Center) {
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
                            }
                            IconButton(onClick = {
                                seekMainPlayer(currentPos + skipDurationMs, resumeAfterSeek = isPlaying)
                            }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.FastForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp)) }
                        }
                    }

                    if (isSubtitlesVisible) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Subtitle Delay", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    subtitleDelayMs = (subtitleDelayMs - 250L).coerceIn(-5000L, 5000L)
                                    prefs.edit { putLong(PREF_SUBTITLE_OFFSET_MS, subtitleDelayMs) }
                                }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("-0.25s", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                                }
                                Text(String.format(Locale.US, "%.2fs", subtitleDelayMs / 1000f), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                TextButton(onClick = {
                                    subtitleDelayMs = (subtitleDelayMs + 250L).coerceIn(-5000L, 5000L)
                                    prefs.edit { putLong(PREF_SUBTITLE_OFFSET_MS, subtitleDelayMs) }
                                }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("+0.25s", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    repeatPracticeSegment?.let { segment ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Repeat, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Repeating ${formatTime(segment.startTime)} - ${formatTime(segment.endTime)}  Attempts: $repeatAttemptCount",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TextButton(onClick = { repeatPracticeSegment = null }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("Stop", color = Color(0xFFFF8A80), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (repeatPracticeSegment != null) {
                                repeatPracticeSegment = null
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                if (isRecording) {
                                    stopRecordingSafe(tempFilePath)
                                } else {
                                    val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Shadowing_${System.currentTimeMillis()}.m4a")
                                    tempFilePath = file.absolutePath
                                    recordStartTime = if (hasReachedEnd || (duration > 0L && currentPos >= duration - 500L)) 0L else vlcPlayer.time

                                    if (startRecordingToFile(file)) {
                                        playMainPlayer()
                                    } else {
                                        Toast.makeText(context, "Could not start recording.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording || repeatPracticeSegment != null) Color(0xFFE53935) else MaterialTheme.colorScheme.primary,
                            contentColor = if (isRecording || repeatPracticeSegment != null) Color.White else MaterialTheme.colorScheme.onPrimary
                        ),
                        enabled = !isRefreshingStream
                    ) {
                        Icon(if (isRecording || repeatPracticeSegment != null) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                repeatPracticeSegment != null -> "STOP REPEAT MODE"
                                isRecording -> "STOP SHADOWING"
                                else -> "START SHADOWING"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                if (recordings.isNotEmpty() && !isRecording) {
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Latest Recording", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
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
                            onPlayOriginal = {
                                if (activeBothSegment?.id == latest.id) {
                                    pauseOriginalFromBoth(latest)
                                } else {
                                    pairedPlaybackExitMode = PairedPlaybackExitMode.PauseBoth
                                    activeBothSegment = null
                                    continuingOriginalSegment = null
                                    activeOriginalSegment = if (activeOriginalSegment?.id == latest.id) null else latest
                                }
                            },
                            onPlayVoice = {
                                if (activeBothSegment?.id == latest.id) {
                                    pauseVoiceFromBoth(latest)
                                } else {
                                    pairedPlaybackExitMode = PairedPlaybackExitMode.PauseBoth
                                    activeBothSegment = null
                                    continuingOriginalSegment = null
                                    toggleVoiceSegment(latest)
                                }
                            },
                            onPlayBoth = { toggleBothSegment(latest) },
                            onSeekOriginal = { targetMs -> seekMainPlayer(targetMs, resumeAfterSeek = false) },
                            onSeekVoice = { targetMs -> seekVoiceSegment(latest, targetMs) },
                            onRepeatPractice = {
                                repeatPracticeSegment = if (repeatPracticeSegment?.id == latest.id) null else latest
                            },
                            onDelete = {
                                try { File(latest.filePath).delete() } catch (e: Exception) {}
                                if (repeatPracticeSegment?.id == latest.id) repeatPracticeSegment = null
                                if (activeVoiceSegmentId == latest.id) {
                                    try { voiceAudioPlayer.stop() } catch (e: Exception) {}
                                    activeVoiceSegmentId = null
                                }
                                recordings.remove(latest)
                                syncWithStorage()
                            },
                            onShare = { exportRecording(context, File(latest.filePath)) },
                            isRepeatPracticeActive = repeatPracticeSegment?.id == latest.id,
                            currentOriginalTime = currentPos,
                            currentRecordedTime = if (activeVoiceSegmentId == latest.id || activeBothSegment?.id == latest.id) voiceCurrentPos else -1L,
                            isOriginalPlaying = activeOriginalSegment?.id == latest.id || activeBothSegment?.id == latest.id || continuingOriginalSegment?.id == latest.id,
                            isRecordedPlaying = (activeVoiceSegmentId == latest.id && voiceAudioPlayer.isPlaying) || activeBothSegment?.id == latest.id,
                            isBothPlaying = activeBothSegment?.id == latest.id
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
                            onPlayOriginal = {
                                if (activeBothSegment?.id == rec.id) {
                                    pauseOriginalFromBoth(rec)
                                } else {
                                    pairedPlaybackExitMode = PairedPlaybackExitMode.PauseBoth
                                    activeBothSegment = null
                                    continuingOriginalSegment = null
                                    activeOriginalSegment = if (activeOriginalSegment?.id == rec.id) null else rec
                                }
                            },
                            onPlayVoice = {
                                if (activeBothSegment?.id == rec.id) {
                                    pauseVoiceFromBoth(rec)
                                } else {
                                    pairedPlaybackExitMode = PairedPlaybackExitMode.PauseBoth
                                    activeBothSegment = null
                                    continuingOriginalSegment = null
                                    toggleVoiceSegment(rec)
                                }
                            },
                            onPlayBoth = { toggleBothSegment(rec) },
                            onSeekOriginal = { targetMs -> seekMainPlayer(targetMs, resumeAfterSeek = false) },
                            onSeekVoice = { targetMs -> seekVoiceSegment(rec, targetMs) },
                            onRepeatPractice = {
                                repeatPracticeSegment = if (repeatPracticeSegment?.id == rec.id) null else rec
                            },
                            onDelete = {
                                try { File(rec.filePath).delete() } catch (e: Exception) {}
                                if (repeatPracticeSegment?.id == rec.id) repeatPracticeSegment = null
                                if (activeVoiceSegmentId == rec.id) {
                                    try { voiceAudioPlayer.stop() } catch (e: Exception) {}
                                    activeVoiceSegmentId = null
                                }
                                recordings.remove(rec)
                                syncWithStorage()
                            },
                            onShare = { exportRecording(context, File(rec.filePath)) },
                            isRepeatPracticeActive = repeatPracticeSegment?.id == rec.id,
                            currentOriginalTime = currentPos,
                            currentRecordedTime = if (activeVoiceSegmentId == rec.id || activeBothSegment?.id == rec.id) voiceCurrentPos else -1L,
                            isOriginalPlaying = activeOriginalSegment?.id == rec.id || activeBothSegment?.id == rec.id || continuingOriginalSegment?.id == rec.id,
                            isRecordedPlaying = (activeVoiceSegmentId == rec.id && voiceAudioPlayer.isPlaying) || activeBothSegment?.id == rec.id,
                            isBothPlaying = activeBothSegment?.id == rec.id
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
    val textColor = MaterialTheme.colorScheme.onSurface
    val annotationColor = MaterialTheme.colorScheme.onSurfaceVariant
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
                Text(mora, color = textColor, fontSize = 20.sp)
                androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                    if (isHigh) {
                        drawLine(textColor, androidx.compose.ui.geometry.Offset(0f, 2.dp.toPx()), androidx.compose.ui.geometry.Offset(size.width, 2.dp.toPx()), 1.5.dp.toPx())
                    }
                    if (hasDrop) {
                        drawLine(textColor, androidx.compose.ui.geometry.Offset(size.width, 2.dp.toPx()), androidx.compose.ui.geometry.Offset(size.width, size.height * 0.6f), 1.5.dp.toPx())
                    }
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Text("[$pitchPosition]", color = annotationColor, fontSize = 14.sp)
    }
}

@Composable
fun PitchDiagram(reading: String, pitchPosition: Int, modifier: Modifier = Modifier) {
    val textColor = MaterialTheme.colorScheme.onSurface
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

    val dotRadius = 4.dp
    val strokeWidth = 2.dp
    val moraWidth = 32.dp

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
                Text(
                    text = mora,
                    fontSize = 12.sp,
                    color = textColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(moraWidth),
                    maxLines = 1,
                    softWrap = false
                )
            }
            Spacer(Modifier.width(moraWidth))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoshiDictionaryBottomSheet(query: String, engine: DictionaryEngine, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val colorScheme = MaterialTheme.colorScheme
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.88f
    val resultsListState = rememberLazyListState()
    var routeExpandedTopDragToSheet by remember { mutableStateOf(false) }
    fun listIsAtTop(): Boolean {
        return resultsListState.firstVisibleItemIndex == 0 &&
            resultsListState.firstVisibleItemScrollOffset == 0
    }
    LaunchedEffect(
        sheetState.currentValue,
        resultsListState.firstVisibleItemIndex,
        resultsListState.firstVisibleItemScrollOffset
    ) {
        if (sheetState.currentValue != SheetValue.Expanded || !listIsAtTop()) {
            routeExpandedTopDragToSheet = false
        }
    }
    val expandedTopDragRouter = remember(sheetState, resultsListState) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero

                if (available.y < 0f) {
                    routeExpandedTopDragToSheet = false
                    return Offset.Zero
                }

                val shouldRouteToSheet =
                    available.y > 0f &&
                        listIsAtTop() &&
                        sheetState.currentValue == SheetValue.Expanded &&
                        sheetState.targetValue == SheetValue.Expanded

                if (!shouldRouteToSheet) {
                    routeExpandedTopDragToSheet = false
                    return Offset.Zero
                }

                val wasRouting = routeExpandedTopDragToSheet
                routeExpandedTopDragToSheet = true
                return if (wasRouting) Offset.Zero else Offset(x = 0f, y = available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                routeExpandedTopDragToSheet = false
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                routeExpandedTopDragToSheet = false
                return Velocity.Zero
            }
        }
    }
    val defaultFlingBehavior = ScrollableDefaults.flingBehavior()
    val containTopBoundListFling = remember(resultsListState, defaultFlingBehavior) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val startedAtTop = listIsAtTop()
                val remainingVelocity = with(defaultFlingBehavior) {
                    performFling(initialVelocity)
                }
                return if (!startedAtTop && listIsAtTop()) {
                    0f
                } else {
                    remainingVelocity
                }
            }
        }
    }
    var results by remember { mutableStateOf<List<DictEntry>>(emptyList()) }
    var searchQuery by remember { mutableStateOf(query) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery) {
        isSearching = true
        results = engine.searchPrefixes(searchQuery)
        if (resultsListState.firstVisibleItemIndex != 0 || resultsListState.firstVisibleItemScrollOffset != 0) {
            resultsListState.scrollToItem(0)
        }
        isSearching = false
    }

    BackHandler(enabled = true) {
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = true,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxWidth().height(maxSheetHeight).padding(16.dp).padding(bottom = 32.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search Dictionary") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colorScheme.onSurface,
                    unfocusedTextColor = colorScheme.onSurface,
                    focusedContainerColor = colorScheme.surface,
                    unfocusedContainerColor = colorScheme.surface,
                    focusedLabelColor = colorScheme.primary,
                    unfocusedLabelColor = colorScheme.onSurfaceVariant,
                    focusedBorderColor = colorScheme.primary,
                    unfocusedBorderColor = colorScheme.onSurfaceVariant,
                    cursorColor = colorScheme.primary
                ),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear", tint = colorScheme.onSurfaceVariant) }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isSearching) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else if (results.isEmpty()) {
                Text("No results found.", color = colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                val grouped = results.groupBy { "${it.deinflected}|${it.reading}" }
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                    LazyColumn(
                        state = resultsListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .nestedScroll(expandedTopDragRouter)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Final)
                                    } while (event.changes.any { it.pressed })
                                    routeExpandedTopDragToSheet = false
                                }
                            },
                        flingBehavior = containTopBoundListFling,
                        userScrollEnabled = !routeExpandedTopDragToSheet,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
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
}

@Composable
fun DictGroupCard(entries: List<DictEntry>) {
    val first = entries.first()
    val colorScheme = MaterialTheme.colorScheme
    val chipContainer = colorScheme.primary.copy(alpha = if (isSystemInDarkTheme()) 0.20f else 0.12f)
    Card(
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(first.deinflected, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)

                if (first.reading.isNotEmpty() && first.reading != first.deinflected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "【${first.reading}】",
                        fontSize = 18.sp,
                        color = colorScheme.primary,
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
                            color = chipContainer,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Text(dictName, color = colorScheme.primary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
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
                val processedDefinitions = dictEntries.map { entry ->
                    if (entry.definition.trim().startsWith("[") || entry.definition.trim().startsWith("{")) {
                        convertStructuredToHtml(entry.definition)
                    } else {
                        entry.definition
                    }
                }
                DictionaryEntrySection(
                    dictName = dictName,
                    processedDefinitions = processedDefinitions,
                    chipContainer = chipContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DictionaryEntrySection(
    dictName: String,
    processedDefinitions: List<String>,
    chipContainer: Color
) {
    var expanded by remember(dictName, processedDefinitions) { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme
    val sourceText = remember(processedDefinitions) { dictionarySourcePlainText(processedDefinitions) }
    val preview = remember(sourceText) { firstDictionaryDefinitionLine(sourceText) }
    val expandable = remember(sourceText, preview) { isExpandableDictionarySource(sourceText, preview) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = expandable) { expanded = !expanded }
                .padding(vertical = 4.dp)
        ) {
            Surface(
                color = chipContainer,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = dictName,
                    color = colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (expanded) {
                Spacer(modifier = Modifier.weight(1f))
            } else {
                Text(
                    preview,
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (expandable) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse dictionary source" else "Expand dictionary source",
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (expanded && sourceText.isNotBlank()) {
            Text(
                sourceText,
                color = colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 8.dp)
            )
        }
    }
}

private fun dictionarySourcePlainText(processedDefinitions: List<String>): String {
    return processedDefinitions
        .asSequence()
        .map { dictionaryDefinitionPlainText(it).trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
}

private fun firstDictionaryDefinitionLine(sourceText: String): String {
    return sourceText
        .lineSequence()
        .map { cleanDictionaryDefinitionLine(it) }
        .firstOrNull { it.isNotBlank() }
        ?.takeIf { it.isNotBlank() }
        ?: "Definition"
}

private fun isExpandableDictionarySource(sourceText: String, preview: String): Boolean {
    val nonBlankLines = sourceText
        .lineSequence()
        .map { cleanDictionaryDefinitionLine(it) }
        .filter { it.isNotBlank() }
        .toList()
    val compactText = nonBlankLines.joinToString(" ")
    return nonBlankLines.size > 1 ||
        compactText.length > preview.length ||
        compactText.length > 72
}

private fun dictionaryDefinitionPlainText(processedDefinition: String): String {
    return if (processedDefinition.contains("<")) {
        Html.fromHtml(processedDefinition, Html.FROM_HTML_MODE_LEGACY).toString()
    } else {
        processedDefinition
    }.replace('\u00A0', ' ')
}

private fun cleanDictionaryDefinitionLine(line: String): String {
    return line
        .trim()
        .trimStart('•', '・', '-')
        .trim()
}

@Composable
fun AudioWaveformComparison(
    originalAmplitudes: List<Float>,
    originalPitches: List<Float?>? = null,
    recordedAmplitudes: List<Float>,
    recordedPitches: List<Float?>? = null,
    onPlayOriginal: () -> Unit,
    onPlayVoice: () -> Unit,
    onSeekOriginal: (Float) -> Unit = {},
    onSeekVoice: (Float) -> Unit = {},
    isOriginalPlaying: Boolean = false,
    isRecordedPlaying: Boolean = false,
    originalProgress: Float = 0f,
    recordedProgress: Float = 0f,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val recordedColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        WaveformTrack(
            amplitudes = originalAmplitudes,
            pitches = originalPitches,
            color = accent,
            pitchColor = MaterialTheme.colorScheme.secondary,
            cursorColor = accent,
            label = "Original",
            onClick = onPlayOriginal,
            onSeek = onSeekOriginal,
            isPlaying = isOriginalPlaying,
            progress = originalProgress,
            isLoading = isLoading
        )
        Spacer(Modifier.height(8.dp))
        WaveformTrack(
            amplitudes = recordedAmplitudes,
            pitches = recordedPitches,
            color = recordedColor,
            pitchColor = MaterialTheme.colorScheme.secondary,
            cursorColor = accent,
            label = "Recorded",
            onClick = onPlayVoice,
            onSeek = onSeekVoice,
            isPlaying = isRecordedPlaying,
            progress = recordedProgress,
            isLoading = isLoading
        )
    }
}

@Composable
fun WaveformTrack(
    amplitudes: List<Float>,
    pitches: List<Float?>? = null,
    color: Color,
    pitchColor: Color,
    cursorColor: Color,
    label: String,
    onClick: () -> Unit,
    onSeek: (Float) -> Unit = {},
    isPlaying: Boolean = false,
    progress: Float = 0f,
    isLoading: Boolean = false
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(color, CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(amplitudes) {
                    detectTapGestures { offset ->
                        if (amplitudes.isNotEmpty() && size.width > 0) {
                            onSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (amplitudes.isEmpty()) {
                Text(
                    if (isLoading) "Analyzing $label..." else "$label audio unavailable",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                return@Box
            }

            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {

            val step = size.width / amplitudes.size
            val midY = size.height / 2

            // Draw Symmetrical Amplitudes Bars
            val barWidth = (step * 0.7f).coerceAtLeast(2f)
            for (i in amplitudes.indices) {
                val amp = amplitudes[i]
                val x = i * step + step / 2f
                val barHeight = (amp * size.height).coerceAtLeast(4f)

                drawLine(
                    color = color.copy(alpha = 0.8f),
                    start = androidx.compose.ui.geometry.Offset(x, midY - barHeight / 2f),
                    end = androidx.compose.ui.geometry.Offset(x, midY + barHeight / 2f),
                    strokeWidth = barWidth,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }

            // Draw Smooth Pitch Contour
            if (pitches != null && pitches.isNotEmpty()) {
                val pitchPath = androidx.compose.ui.graphics.Path()
                var isFirst = true
                val pitchScale = pitchDisplayScale(pitches)

                pitches.forEachIndexed { index, pitchHz ->
                    if (pitchHz != null && pitchScale != null) {
                        val normalizedY = pitchToNormalizedY(pitchHz, pitchScale)
                        val y = normalizedY * size.height
                        val x = index * step + step / 2f

                        if (isFirst) {
                            pitchPath.moveTo(x, y)
                            isFirst = false
                        } else {
                            pitchPath.lineTo(x, y)
                        }
                    } else {
                        isFirst = true
                    }
                }
                drawPath(
                    path = pitchPath,
                    color = pitchColor,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2.5.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }

            if (progress > 0f) {
                val cursorX = size.width * progress.coerceIn(0f, 1f)
                drawLine(
                    color = cursorColor,
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
fun LibraryCard(
    item: LibraryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onDownload: (() -> Unit)? = null,
    downloadState: LibraryDownloadState = LibraryDownloadState.Idle
) {
    val context = LocalContext.current
    val progressPct = if (item.duration > 0) (item.progress.toFloat() / item.duration.toFloat()) else 0f
    val albumArt = loadAlbumArt(context, item.mediaUri, item.isVideo, item.coverArtPath)
    val metadataLine = item.metadataSummary()
    val itemType = item.displaySourceLabel()

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.width(68.dp).height(92.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (albumArt != null) {
                    Image(bitmap = albumArt, contentDescription = "Cover", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(
                        if (item.isVideo) Icons.Default.Movie else Icons.Default.Audiotrack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (metadataLine != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(metadataLine, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(itemType, fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(
                                if (item.hasDownloadedLocalCopy()) {
                                    Icons.Default.CheckCircle
                                } else if (item.sourceUrl != null) {
                                    Icons.Default.Cloud
                                } else if (item.isVideo) {
                                    Icons.Default.Movie
                                } else {
                                    Icons.Default.Audiotrack
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                    if (item.recordings.isNotEmpty()) {
                        Text("${item.recordings.size} recordings", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${(progressPct * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (item.subtitleUri != null) "Subtitles" else "No subtitles",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progressPct },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Column(
                modifier = Modifier.width(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onDownload != null || downloadState != LibraryDownloadState.Idle) {
                    IconButton(
                        onClick = { onDownload?.invoke() },
                        enabled = onDownload != null && downloadState == LibraryDownloadState.Idle,
                        modifier = Modifier.size(44.dp)
                    ) {
                        when (downloadState) {
                            LibraryDownloadState.Loading -> {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                            LibraryDownloadState.Complete -> {
                                Icon(Icons.Default.CheckCircle, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary)
                            }
                            LibraryDownloadState.Idle -> {
                                Icon(Icons.Default.Download, contentDescription = "Download", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
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
    onSeekOriginal: (Long) -> Unit = {},
    onSeekVoice: (Long) -> Unit = {},
    onRepeatPractice: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    isRepeatPracticeActive: Boolean = false,
    currentOriginalTime: Long = -1L,
    currentRecordedTime: Long = -1L,
    isOriginalPlaying: Boolean = false,
    isRecordedPlaying: Boolean = false,
    isBothPlaying: Boolean = false
) {
    var originalAmplitudes by remember { mutableStateOf<List<Float>>(emptyList()) }
    var originalPitches by remember { mutableStateOf<List<Float?>>(emptyList()) }
    var recordedAmplitudes by remember { mutableStateOf<List<Float>>(emptyList()) }
    var recordedPitches by remember { mutableStateOf<List<Float?>>(emptyList()) }
    var isWaveformLoading by remember { mutableStateOf(true) }

    LaunchedEffect(rec, originalMediaUri) {
        isWaveformLoading = true
        val (originalData, recordedData) = withContext(Dispatchers.IO) {
            val originalKey = "v$WAVEFORM_CACHE_VERSION:original:${originalMediaUri}:${rec.startTime}:${rec.endTime}"
            val recordedFile = File(rec.filePath)
            val recordedKey = "v$WAVEFORM_CACHE_VERSION:recorded:${rec.filePath}:${recordedFile.lastModified()}:${recordedFile.length()}"
            val originalData = if (rec.endTime > rec.startTime + MIN_SHADOW_SEGMENT_MS) {
                extractAudioDataCached(context, originalKey, Uri.parse(originalMediaUri), rec.startTime, rec.endTime, WAVEFORM_BUCKET_COUNT)
            } else {
                Pair(emptyList<Float>(), emptyList<Float?>())
            }
            val recordedData = extractAudioDataCached(context, recordedKey, Uri.fromFile(recordedFile), 0, 0, WAVEFORM_BUCKET_COUNT)
            Pair(originalData, recordedData)
        }
        originalAmplitudes = originalData.first
        originalPitches = originalData.second
        recordedAmplitudes = recordedData.first
        recordedPitches = recordedData.second
        isWaveformLoading = false
    }

    val segmentDuration = (rec.endTime - rec.startTime).coerceAtLeast(1L)

    val originalProgress = if (currentOriginalTime in rec.startTime..rec.endTime) {
        (currentOriginalTime - rec.startTime).toFloat() / segmentDuration.toFloat()
    } else 0f

    val recordedProgress = if (currentRecordedTime >= 0) {
        currentRecordedTime.toFloat() / segmentDuration.toFloat()
    } else 0f

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Segment: ${formatTime(rec.startTime)} - ${formatTime(rec.endTime)}", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    if (currentRecordedTime >= 0L) {
                        Text("Recording: ${formatTime(currentRecordedTime)} / ${formatTime(segmentDuration)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
                Row {
                    IconButton(onClick = onShare, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            AudioWaveformComparison(
                originalAmplitudes = originalAmplitudes,
                originalPitches = originalPitches,
                recordedAmplitudes = recordedAmplitudes,
                recordedPitches = recordedPitches,
                onPlayOriginal = onPlayOriginal,
                onPlayVoice = onPlayVoice,
                onSeekOriginal = { fraction ->
                    val target = rec.startTime + (segmentDuration * fraction).toLong()
                    onSeekOriginal(target)
                },
                onSeekVoice = { fraction ->
                    val target = (segmentDuration * fraction).toLong()
                    onSeekVoice(target)
                },
                isOriginalPlaying = isOriginalPlaying,
                isRecordedPlaying = isRecordedPlaying,
                originalProgress = originalProgress,
                recordedProgress = recordedProgress,
                isLoading = isWaveformLoading
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onPlayBoth,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
            ) {
                Icon(if (isBothPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isBothPlaying) "Pause Both" else "Play Both", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onRepeatPractice,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isRepeatPracticeActive) Color(0xFFFF8A80) else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(if (isRepeatPracticeActive) Icons.Default.Stop else Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isRepeatPracticeActive) "Stop Repeat" else "Repeat Segment", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun extractAudioDataCached(
    context: Context,
    cacheKey: String,
    uri: Uri,
    startTimeMs: Long,
    endTimeMs: Long,
    buckets: Int
): Pair<List<Float>, List<Float?>> {
    WaveformCache.get(cacheKey)?.let { return it }
    return extractAudioData(context, uri, startTimeMs, endTimeMs, buckets).also {
        WaveformCache.put(cacheKey, it)
    }
}

private const val WAVEFORM_CACHE_VERSION = 6
private const val WAVEFORM_SAMPLE_RATE = 44100
private const val WAVEFORM_CHANNEL_COUNT = 1
private const val WAVEFORM_BUCKET_COUNT = 96
private const val MIN_SHADOW_SEGMENT_MS = 250L
private const val MAX_WAVEFORM_DECODE_DURATION_MS = 120_000L
private const val MAX_WAVEFORM_PCM_BYTES = 32L * 1024L * 1024L
private const val PITCH_MIN_HZ = 50f
private const val PITCH_MAX_HZ = 650f
private const val YIN_THRESHOLD = 0.20f

private data class DecodedPcm(
    val bytes: ByteArray,
    val sampleRate: Int,
    val channelCount: Int
)

private data class FfmpegInput(
    val value: String,
    val tempFile: File? = null,
    val pfd: ParcelFileDescriptor? = null
)

private data class PitchEstimate(val hz: Float, val confidence: Float)

private data class PitchScale(val minLogHz: Double, val maxLogHz: Double)

fun extractAudioData(context: Context, uri: Uri, startTimeMs: Long, endTimeMs: Long, buckets: Int): Pair<List<Float>, List<Float?>> {
    val safeBuckets = buckets.coerceAtLeast(1)
    val waveformWindow = resolveWaveformWindow(context, uri, startTimeMs, endTimeMs)
        ?: return Pair(emptyList(), emptyList())
    val safeStartTimeMs = waveformWindow.first
    val safeEndTimeMs = waveformWindow.second

    val decoded = decodeAudioWithFfmpeg(context, uri, safeStartTimeMs, safeEndTimeMs)
        ?: decodeAudioWithMediaCodec(context, uri, safeStartTimeMs, safeEndTimeMs)

    if (decoded == null || decoded.bytes.isEmpty()) {
        return Pair(emptyList(), emptyList())
    }

    try {
        val monoSamples = decodePcm16ToMonoFloat(decoded)
        if (monoSamples.isEmpty()) return Pair(emptyList(), emptyList())

        val samplesPerBucket = monoSamples.size / safeBuckets
        if (samplesPerBucket == 0) return Pair(emptyList(), emptyList())

        var globalSumSq = 0.0
        var globalPeak = 0f
        for (sample in monoSamples) {
            val absSample = kotlin.math.abs(sample)
            globalSumSq += (sample * sample).toDouble()
            if (absSample > globalPeak) globalPeak = absSample
        }
        val globalRms = kotlin.math.sqrt(globalSumSq / monoSamples.size).toFloat()
        if (globalPeak < 0.0005f || globalRms < 0.0002f) return Pair(emptyList(), emptyList())

        val bucketAmps = FloatArray(safeBuckets)
        val rawBucketRms = FloatArray(safeBuckets)

        for (i in 0 until safeBuckets) {
            val startIndex = i * samplesPerBucket
            val endIndex = if (i == safeBuckets - 1) monoSamples.size else startIndex + samplesPerBucket

            val length = endIndex - startIndex
            var sumSq = 0.0
            for (j in startIndex until endIndex) {
                val s = monoSamples[j].toDouble()
                sumSq += s * s
            }

            val rms = if (length > 0) Math.sqrt(sumSq / length).toFloat() else 0f
            rawBucketRms[i] = rms
        }

        val sortedRms = rawBucketRms.sorted()
        val noiseFloor = percentile(sortedRms, 0.10f)
        val referenceRms = percentile(sortedRms, 0.95f).coerceAtLeast(globalRms)
        val ampRange = (referenceRms - noiseFloor).coerceAtLeast(0.0001f)
        for (i in 0 until safeBuckets) {
            val normalized = ((rawBucketRms[i] - noiseFloor) / ampRange).coerceIn(0f, 1f)
            bucketAmps[i] = Math.pow(normalized.toDouble(), 0.55).toFloat().coerceIn(0.06f, 1f)
        }

        val pitchBuckets = estimatePitchContourYin(
            samples = monoSamples,
            sampleRate = decoded.sampleRate,
            bucketCount = safeBuckets,
            globalRms = globalRms
        )

        return Pair(bucketAmps.toList(), pitchBuckets)
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return Pair(emptyList(), emptyList())
}

private fun resolveWaveformWindow(context: Context, uri: Uri, startTimeMs: Long, endTimeMs: Long): Pair<Long, Long>? {
    val safeStart = startTimeMs.coerceAtLeast(0L)
    val requestedEnd = if (endTimeMs > startTimeMs) {
        endTimeMs
    } else {
        readMediaDurationMs(context, uri) ?: return null
    }
    val cappedEnd = requestedEnd.coerceAtMost(safeStart + MAX_WAVEFORM_DECODE_DURATION_MS)
    return if (cappedEnd > safeStart + MIN_SHADOW_SEGMENT_MS) {
        safeStart to cappedEnd
    } else {
        null
    }
}

private fun readMediaDurationMs(context: Context, uri: Uri): Long? {
    return try {
        val retriever = MediaMetadataRetriever()
        try {
            when {
                uri.scheme == "content" -> context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                }
                uri.scheme == "file" -> retriever.setDataSource(uri.path)
                uri.scheme?.startsWith("http", ignoreCase = true) == true -> retriever.setDataSource(uri.toString(), mapOf("User-Agent" to "Mozilla/5.0"))
                else -> retriever.setDataSource(context, uri)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0L }
        } finally {
            retriever.release()
        }
    } catch (e: Exception) {
        null
    }
}

private fun decodeAudioWithFfmpeg(context: Context, uri: Uri, startTimeMs: Long, endTimeMs: Long): DecodedPcm? {
    if (!ensureMediaToolsReady(context)) return null

    resolveFfmpegInput(context, uri, preferFileDescriptor = true)?.let { firstInput ->
        runFfmpegDecode(firstInput, context, startTimeMs, endTimeMs)?.let { return it }
    }

    if (endTimeMs > startTimeMs) {
        resolveFfmpegInput(context, uri, preferFileDescriptor = true)?.let { accurateSeekInput ->
            runFfmpegDecode(accurateSeekInput, context, startTimeMs, endTimeMs, seekAfterInput = true)?.let { return it }
        }
    }

    if (uri.scheme?.lowercase(Locale.US) == "content") {
        val fallbackInput = resolveFfmpegInput(context, uri, preferFileDescriptor = false) ?: return null
        runFfmpegDecode(fallbackInput, context, startTimeMs, endTimeMs)?.let { return it }
        if (endTimeMs > startTimeMs) {
            val accurateSeekFallbackInput = resolveFfmpegInput(context, uri, preferFileDescriptor = false) ?: return null
            return runFfmpegDecode(accurateSeekFallbackInput, context, startTimeMs, endTimeMs, seekAfterInput = true)
        }
    }

    return null
}

private fun runFfmpegDecode(
    input: FfmpegInput,
    context: Context,
    startTimeMs: Long,
    endTimeMs: Long,
    seekAfterInput: Boolean = false
): DecodedPcm? {
    val output = File.createTempFile("waveform_pcm_", ".raw", context.cacheDir)
    return try {
        val safeStartMs = startTimeMs.coerceAtLeast(0L)
        val durationMs = if (endTimeMs > startTimeMs) endTimeMs - startTimeMs else 0L
        if (durationMs <= MIN_SHADOW_SEGMENT_MS) return null
        val cmd = mutableListOf("-y", "-hide_banner", "-loglevel", "error", "-nostdin")

        if (safeStartMs > 0L && !seekAfterInput) {
            cmd.add("-ss")
            cmd.add(formatSeconds(safeStartMs))
        }

        if (input.value.startsWith("http://", ignoreCase = true) || input.value.startsWith("https://", ignoreCase = true)) {
            cmd.add("-user_agent")
            cmd.add("Mozilla/5.0")
        }

        cmd.add("-i")
        cmd.add(input.value)

        if (safeStartMs > 0L && seekAfterInput) {
            cmd.add("-ss")
            cmd.add(formatSeconds(safeStartMs))
        }

        if (durationMs > 0L) {
            cmd.add("-t")
            cmd.add(formatSeconds(durationMs))
        }

        cmd.addAll(
            listOf(
                "-map", "0:a:0",
                "-vn",
                "-sn",
                "-dn",
                "-acodec", "pcm_s16le",
                "-ar", WAVEFORM_SAMPLE_RATE.toString(),
                "-ac", WAVEFORM_CHANNEL_COUNT.toString(),
                "-sample_fmt", "s16",
                "-f", "s16le",
                output.absolutePath
            )
        )

        val rc = FFmpeg.getInstance().execute(cmd.toTypedArray())
        if (rc == 0 && output.length() > 0L && output.length() <= MAX_WAVEFORM_PCM_BYTES) {
            DecodedPcm(output.readBytes(), WAVEFORM_SAMPLE_RATE, WAVEFORM_CHANNEL_COUNT)
        } else {
            null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } finally {
        try { output.delete() } catch (e: Exception) {}
        try { input.tempFile?.delete() } catch (e: Exception) {}
        try { input.pfd?.close() } catch (e: Exception) {}
    }
}

private fun resolveFfmpegInput(context: Context, uri: Uri, preferFileDescriptor: Boolean): FfmpegInput? {
    val scheme = uri.scheme?.lowercase(Locale.US)
    return when (scheme) {
        null, "file" -> {
            val path = uri.path ?: return null
            FfmpegInput(path)
        }
        "content" -> {
            if (preferFileDescriptor) {
                val pfd = try {
                    context.contentResolver.openFileDescriptor(uri, "r")
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                } ?: return null

                FfmpegInput("/proc/self/fd/${pfd.fd}", pfd = pfd)
            } else {
                materializeContentUriForFfmpeg(context, uri)?.let { FfmpegInput(it.absolutePath) }
            }
        }
        "http", "https" -> FfmpegInput(uri.toString())
        else -> FfmpegInput(uri.toString())
    }
}

private fun materializeContentUriForFfmpeg(context: Context, uri: Uri): File? {
    val fileName = getFileName(context, uri)
    val extension = fileName.substringAfterLast('.', "media").lowercase(Locale.US).takeIf { it.length in 2..6 } ?: "media"
    val expectedLength = getContentLength(context, uri)
    val cacheDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.cacheDir, "WaveformMediaCache").apply { mkdirs() }
    val safeKey = Integer.toHexString(uri.toString().hashCode())
    val cachedFile = File(cacheDir, "media_${safeKey}.$extension")

    if (cachedFile.exists() && cachedFile.length() > 0L) {
        if (expectedLength == null || cachedFile.length() == expectedLength) return cachedFile
        try { cachedFile.delete() } catch (e: Exception) {}
    }

    val tempFile = File(cacheDir, "${cachedFile.name}.tmp")
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return null

        if (tempFile.length() <= 0L) {
            try { tempFile.delete() } catch (e: Exception) {}
            return null
        }

        if (cachedFile.exists()) try { cachedFile.delete() } catch (e: Exception) {}
        if (tempFile.renameTo(cachedFile)) {
            cachedFile
        } else {
            tempFile.copyTo(cachedFile, overwrite = true)
            try { tempFile.delete() } catch (e: Exception) {}
            cachedFile.takeIf { it.length() > 0L }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        try { tempFile.delete() } catch (deleteError: Exception) {}
        null
    }
}

private fun getContentLength(context: Context, uri: Uri): Long? {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}

private fun decodeAudioWithMediaCodec(context: Context, uri: Uri, startTimeMs: Long, endTimeMs: Long): DecodedPcm? {
    var extractor: android.media.MediaExtractor? = null
    var codec: android.media.MediaCodec? = null
    var pfd: ParcelFileDescriptor? = null
    var finalSampleRate = WAVEFORM_SAMPLE_RATE
    var finalChannelCount = WAVEFORM_CHANNEL_COUNT

    return try {
        extractor = android.media.MediaExtractor()
        when {
            uri.scheme == "content" -> {
                pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                extractor.setDataSource(pfd.fileDescriptor)
            }
            uri.scheme == "file" -> extractor.setDataSource(uri.path!!)
            uri.scheme?.startsWith("http", ignoreCase = true) == true -> {
                extractor.setDataSource(uri.toString(), mapOf("User-Agent" to "Mozilla/5.0"))
            }
            else -> extractor.setDataSource(context, uri, null)
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

        if (audioTrackIndex < 0) return null

        extractor.selectTrack(audioTrackIndex)

        var firstSampleUs = extractor.sampleTime
        if (firstSampleUs < 0L) firstSampleUs = 0L

        val format = extractor.getTrackFormat(audioTrackIndex)
        val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: return null
        finalSampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
        finalChannelCount = if (format.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            WAVEFORM_CHANNEL_COUNT
        }

        codec = android.media.MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val adjustedStartUs = startTimeMs.coerceAtLeast(0L) * 1000L + firstSampleUs
        val adjustedEndUs = if (endTimeMs > startTimeMs) endTimeMs * 1000L + firstSampleUs else 0L

        if (adjustedStartUs > 0L) {
            extractor.seekTo(adjustedStartUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

        val info = android.media.MediaCodec.BufferInfo()
        var inputEOS = false
        var outputEOS = false
        val pcmData = ByteArrayOutputStream()

        while (!outputEOS) {
            if (!inputEOS) {
                val inIndex = codec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    val sampleTimeUs = extractor.sampleTime

                    if (sampleSize < 0 || (adjustedEndUs > 0L && sampleTimeUs > adjustedEndUs)) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0L, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTimeUs, 0)
                        extractor.advance()
                    }
                }
            }

            var outIndex = codec.dequeueOutputBuffer(info, 10000)
            while (outIndex != android.media.MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (outIndex >= 0) {
                    if ((info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEOS = true
                    }

                    val insideStart = info.presentationTimeUs >= adjustedStartUs - 100000L
                    val insideEnd = adjustedEndUs == 0L || info.presentationTimeUs <= adjustedEndUs + 100000L
                    if (info.size > 0 && insideStart && insideEnd) {
                        if (pcmData.size().toLong() + info.size > MAX_WAVEFORM_PCM_BYTES) {
                            outputEOS = true
                            codec.releaseOutputBuffer(outIndex, false)
                            break
                        }
                        val outBuffer = codec.getOutputBuffer(outIndex)!!
                        outBuffer.position(info.offset)
                        outBuffer.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        outBuffer.get(chunk)
                        pcmData.write(chunk)
                    }

                    codec.releaseOutputBuffer(outIndex, false)
                    if (outputEOS) break
                } else if (outIndex == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                        finalSampleRate = newFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) {
                        finalChannelCount = newFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
                outIndex = codec.dequeueOutputBuffer(info, 10000)
            }
        }

        val bytes = pcmData.toByteArray()
        if (bytes.isNotEmpty()) DecodedPcm(bytes, finalSampleRate, finalChannelCount) else null
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } finally {
        try { codec?.stop() } catch (e: Exception) {}
        try { codec?.release() } catch (e: Exception) {}
        try { extractor?.release() } catch (e: Exception) {}
        try { pfd?.close() } catch (e: Exception) {}
    }
}

private fun formatSeconds(timeMs: Long): String {
    return String.format(Locale.US, "%.3f", timeMs / 1000.0)
}

private fun decodePcm16ToMonoFloat(decoded: DecodedPcm): FloatArray {
    val byteCount = decoded.bytes.size - (decoded.bytes.size % 2)
    if (byteCount <= 0) return FloatArray(0)

    val shortBuffer = ByteBuffer.wrap(decoded.bytes, 0, byteCount).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val allSamples = ShortArray(shortBuffer.remaining())
    shortBuffer.get(allSamples)

    val channelCount = decoded.channelCount.coerceAtLeast(1)
    val frameCount = allSamples.size / channelCount
    if (frameCount <= 0) return FloatArray(0)

    val mono = FloatArray(frameCount)
    for (frame in 0 until frameCount) {
        var sum = 0f
        val base = frame * channelCount
        for (channel in 0 until channelCount) {
            sum += allSamples[base + channel] / 32768f
        }
        mono[frame] = sum / channelCount
    }
    return mono
}

private fun estimatePitchContourYin(
    samples: FloatArray,
    sampleRate: Int,
    bucketCount: Int,
    globalRms: Float
): List<Float?> {
    if (samples.isEmpty() || sampleRate <= 0 || bucketCount <= 0) return emptyList()

    val minTau = (sampleRate / PITCH_MAX_HZ).roundToInt().coerceAtLeast(2)
    val maxTau = (sampleRate / PITCH_MIN_HZ).roundToInt().coerceAtLeast(minTau + 1)
    if (samples.size <= maxTau * 2) return List(bucketCount) { null }

    val targetFrameSize = (sampleRate * 0.085f).roundToInt()
    val frameSize = targetFrameSize.coerceIn(maxTau * 2, minOf(samples.size, 4096))
    val minFrameRms = (globalRms * 0.10f).coerceIn(0.001f, 0.018f)
    val rawPitches = MutableList<Float?>(bucketCount) { null }

    for (bucket in 0 until bucketCount) {
        val center = ((bucket + 0.5f) * samples.size / bucketCount).roundToInt()
        val frameStart = (center - frameSize / 2).coerceIn(0, samples.size - frameSize)
        rawPitches[bucket] = estimatePitchYinFrame(samples, frameStart, frameSize, sampleRate, minFrameRms)?.hz
    }

    return smoothPitchContour(rawPitches)
}

private fun estimatePitchYinFrame(
    samples: FloatArray,
    start: Int,
    frameSize: Int,
    sampleRate: Int,
    minFrameRms: Float
): PitchEstimate? {
    if (start < 0 || start + frameSize > samples.size) return null

    val minTau = (sampleRate / PITCH_MAX_HZ).roundToInt().coerceAtLeast(2)
    val maxTau = (sampleRate / PITCH_MIN_HZ).roundToInt().coerceAtMost(frameSize - 2)
    if (maxTau <= minTau) return null

    var mean = 0f
    for (i in 0 until frameSize) mean += samples[start + i]
    mean /= frameSize

    var sumSq = 0.0
    for (i in 0 until frameSize) {
        val centered = samples[start + i] - mean
        sumSq += (centered * centered).toDouble()
    }
    val rms = kotlin.math.sqrt(sumSq / frameSize).toFloat()
    if (rms < minFrameRms) return null

    val difference = FloatArray(maxTau + 1)
    for (tau in 1..maxTau) {
        var diff = 0f
        val limit = frameSize - tau
        var i = 0
        while (i < limit) {
            val delta = (samples[start + i] - mean) - (samples[start + i + tau] - mean)
            diff += delta * delta
            i++
        }
        difference[tau] = diff
    }

    val cmnd = FloatArray(maxTau + 1) { 1f }
    var runningSum = 0f
    for (tau in 1..maxTau) {
        runningSum += difference[tau]
        cmnd[tau] = if (runningSum > 0f) difference[tau] * tau / runningSum else 1f
    }

    var bestTau = -1
    var tau = minTau
    while (tau <= maxTau) {
        if (cmnd[tau] < YIN_THRESHOLD) {
            while (tau + 1 <= maxTau && cmnd[tau + 1] < cmnd[tau]) tau++
            bestTau = tau
            break
        }
        tau++
    }

    if (bestTau < 0) {
        var minValue = Float.MAX_VALUE
        var minIndex = -1
        for (candidate in minTau..maxTau) {
            if (cmnd[candidate] < minValue) {
                minValue = cmnd[candidate]
                minIndex = candidate
            }
        }
        if (minValue > 0.34f) return null
        bestTau = minIndex
    }

    val refinedTau = parabolicTau(cmnd, bestTau)
    if (refinedTau <= 0f) return null

    val hz = sampleRate / refinedTau
    if (hz !in PITCH_MIN_HZ..PITCH_MAX_HZ) return null

    val confidence = (1f - cmnd[bestTau]).coerceIn(0f, 1f)
    if (confidence < 0.58f) return null

    return PitchEstimate(hz, confidence)
}

private fun parabolicTau(values: FloatArray, tau: Int): Float {
    if (tau <= 0 || tau >= values.lastIndex) return tau.toFloat()
    val left = values[tau - 1]
    val center = values[tau]
    val right = values[tau + 1]
    val denominator = left - 2f * center + right
    if (kotlin.math.abs(denominator) < 0.000001f) return tau.toFloat()
    return tau + 0.5f * (left - right) / denominator
}

private fun smoothPitchContour(raw: List<Float?>): List<Float?> {
    if (raw.isEmpty()) return raw
    val cleaned = raw.toMutableList()

    for (i in cleaned.indices) {
        val current = cleaned[i] ?: continue
        val previous = cleaned.getOrNull(i - 1)
        val next = cleaned.getOrNull(i + 1)
        if (previous != null && next != null) {
            val prevNextDistance = semitoneDistance(previous, next)
            val prevCurrentDistance = semitoneDistance(previous, current)
            val nextCurrentDistance = semitoneDistance(next, current)
            if (prevNextDistance < 3f && prevCurrentDistance > 9f && nextCurrentDistance > 9f) {
                cleaned[i] = kotlin.math.sqrt(previous * next)
            }
        }
    }

    for (i in cleaned.indices) {
        if (cleaned[i] != null) continue
        val previousIndex = (i - 1 downTo maxOf(0, i - 5)).firstOrNull { cleaned[it] != null }
        val nextIndex = (i + 1..minOf(cleaned.lastIndex, i + 5)).firstOrNull { cleaned[it] != null }
        if (previousIndex != null && nextIndex != null) {
            val previous = cleaned[previousIndex]!!
            val next = cleaned[nextIndex]!!
            if (semitoneDistance(previous, next) < 7f) {
                val t = (i - previousIndex).toFloat() / (nextIndex - previousIndex).toFloat()
                val logHz = kotlin.math.ln(previous.toDouble()) * (1.0 - t) + kotlin.math.ln(next.toDouble()) * t
                cleaned[i] = Math.exp(logHz).toFloat()
            }
        }
    }

    return cleaned
}

private fun semitoneDistance(a: Float, b: Float): Float {
    return kotlin.math.abs(12.0 * kotlin.math.ln((a / b).toDouble()) / kotlin.math.ln(2.0)).toFloat()
}

fun estimatePitch(samples: ShortArray, sampleRate: Int, channels: Int = 1): Float? {
    if (samples.isEmpty()) return null
    val channelCount = channels.coerceAtLeast(1)
    val frameCount = samples.size / channelCount
    if (frameCount <= 0) return null

    val mono = FloatArray(frameCount)
    for (frame in 0 until frameCount) {
        var sum = 0f
        val base = frame * channelCount
        for (channel in 0 until channelCount) {
            sum += samples[base + channel] / 32768f
        }
        mono[frame] = sum / channelCount
    }

    var sumSq = 0.0
    for (sample in mono) sumSq += (sample * sample).toDouble()
    val rms = kotlin.math.sqrt(sumSq / mono.size).toFloat()
    return estimatePitchYinFrame(mono, 0, mono.size, sampleRate, (rms * 0.10f).coerceAtLeast(0.001f))?.hz
}

private fun pitchDisplayScale(pitches: List<Float?>): PitchScale? {
    val logs = pitches
        .mapNotNull { pitch -> pitch?.takeIf { it in PITCH_MIN_HZ..PITCH_MAX_HZ } }
        .map { kotlin.math.ln(it.toDouble()) }
        .sorted()

    if (logs.size < 2) return null

    val low = percentile(logs, 0.05f)
    val high = percentile(logs, 0.95f)
    val minSpan = kotlin.math.ln(2.0) * (5.0 / 12.0)
    val center = (low + high) / 2.0
    val span = (high - low).coerceAtLeast(minSpan)
    return PitchScale(center - span / 2.0, center + span / 2.0)
}

private fun pitchToNormalizedY(pitchHz: Float, scale: PitchScale): Float {
    val logHz = kotlin.math.ln(pitchHz.coerceIn(PITCH_MIN_HZ, PITCH_MAX_HZ).toDouble())
    return (1.0 - ((logHz - scale.minLogHz) / (scale.maxLogHz - scale.minLogHz))).toFloat().coerceIn(0f, 1f)
}

private fun percentile(sortedValues: List<Float>, percentile: Float): Float {
    if (sortedValues.isEmpty()) return 0f
    val index = ((sortedValues.lastIndex) * percentile.coerceIn(0f, 1f)).roundToInt()
    return sortedValues[index.coerceIn(sortedValues.indices)]
}

private fun percentile(sortedValues: List<Double>, percentile: Float): Double {
    if (sortedValues.isEmpty()) return 0.0
    val index = ((sortedValues.lastIndex) * percentile.coerceIn(0f, 1f)).roundToInt()
    return sortedValues[index.coerceIn(sortedValues.indices)]
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
fun loadAlbumArt(context: Context, uriString: String, isVideo: Boolean, cachedCoverPath: String? = null): ImageBitmap? {
    val cacheKey = cachedCoverPath?.takeIf { File(it).exists() && File(it).length() > 0L } ?: uriString
    var bitmap by remember(cacheKey) { mutableStateOf<ImageBitmap?>(ImageCache.cache[cacheKey]) }

    LaunchedEffect(uriString, cachedCoverPath) {
        if (bitmap != null) return@LaunchedEffect

        val loadedImage = withContext(Dispatchers.IO) {
            cachedCoverPath
                ?.let { decodeSampledBitmapFile(it)?.asImageBitmap() }
                ?: loadAlbumArtFromMedia(context, uriString, isVideo)
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

fun findSubtitleCue(cues: List<SubtitleCue>, timeMs: Long): SubtitleCue? {
    var low = 0
    var high = cues.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cue = cues[mid]
        when {
            timeMs < cue.startMs -> high = mid - 1
            timeMs > cue.endMs -> low = mid + 1
            else -> return cue
        }
    }
    return null
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
