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

internal fun extractAudioDataCached(
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
internal const val WAVEFORM_CACHE_VERSION = 6
private const val WAVEFORM_SAMPLE_RATE = 44100
private const val WAVEFORM_CHANNEL_COUNT = 1
internal const val WAVEFORM_BUCKET_COUNT = 96
internal const val MIN_SHADOW_SEGMENT_MS = 250L
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
internal data class FfmpegInput(
    val value: String,
    val tempFile: File? = null,
    val pfd: ParcelFileDescriptor? = null
)
private data class PitchEstimate(val hz: Float, val confidence: Float)
internal data class PitchScale(val minLogHz: Double, val maxLogHz: Double)
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
internal fun resolveFfmpegInput(context: Context, uri: Uri, preferFileDescriptor: Boolean): FfmpegInput? {
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
internal fun pitchDisplayScale(pitches: List<Float?>): PitchScale? {
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
internal fun pitchToNormalizedY(pitchHz: Float, scale: PitchScale): Float {
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
