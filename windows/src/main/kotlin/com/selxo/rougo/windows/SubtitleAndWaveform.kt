package com.selxo.rougo.windows

import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val PITCH_MIN_HZ = 50f
private const val PITCH_MAX_HZ = 650f
private const val YIN_THRESHOLD = 0.22f
private const val PITCH_FRAME_RMS_RATIO = 0.09f
private const val PITCH_MIN_FRAME_RMS = 0.0009f
private const val PITCH_MAX_FRAME_RMS = 0.017f
private const val PITCH_FALLBACK_MAX_CMND = 0.36f
private const val PITCH_MIN_CONFIDENCE = 0.55f
private const val WAVEFORM_SAMPLE_RATE = 44_100

object SubtitleParseCache {
    private val cache = LinkedHashMap<String, List<SubtitleCue>>()
    fun get(key: String): List<SubtitleCue>? = synchronized(cache) { cache[key] }
    fun put(key: String, value: List<SubtitleCue>) = synchronized(cache) {
        cache[key] = value
        while (cache.size > 24) {
            val firstKey = cache.keys.firstOrNull() ?: break
            cache.remove(firstKey)
        }
    }
}

object WaveformCache {
    private val cache = LinkedHashMap<String, Pair<List<Float>, List<Float?>>>()
    fun get(key: String): Pair<List<Float>, List<Float?>>? = synchronized(cache) { cache[key] }
    fun put(key: String, value: Pair<List<Float>, List<Float?>>) = synchronized(cache) {
        cache[key] = value
        while (cache.size > 32) {
            val firstKey = cache.keys.firstOrNull() ?: break
            cache.remove(firstKey)
        }
    }
}

fun parseSimpleSubtitles(path: String): List<SubtitleCue> {
    val file = localMediaFile(path)
    val cacheKey = subtitleParseCacheKey(path.trim(), file.takeIf { it.exists() }?.length(), file.takeIf { it.exists() }?.lastModified())
    SubtitleParseCache.get(cacheKey)?.let { return it }
    val cues = mutableListOf<SubtitleCue>()
    try {
        file.bufferedReader(Charsets.UTF_8).use { reader ->
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
                        val text = parts[textIndex].trim().replace(Regex("\\{.*?}"), "").replace("\\N", "\n")
                        if (text.isNotBlank()) cues.add(SubtitleCue(startMs, endMs, text))
                    }
                    return@forEachLine
                }

                val timeMatch = timeRegex.find(trimmed) ?: vttTimeRegex.find(trimmed)
                if (timeMatch != null) {
                    if (currentStart != -1L && currentText.isNotBlank()) {
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
    } catch (e: Exception) {
        CrashReporter.recordHandled("parseSimpleSubtitles", e)
    }
    return cues.also { SubtitleParseCache.put(cacheKey, it) }
}

fun subtitleParseCacheKey(uriString: String, length: Long?, lastModified: Long?): String {
    return listOf(
        uriString.trim(),
        length?.takeIf { it >= 0L }?.toString() ?: "?",
        lastModified?.takeIf { it >= 0L }?.toString() ?: "?"
    ).joinToString(separator = "\u001f")
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

fun subtitleTextForPlayback(
    cues: List<SubtitleCue>,
    currentPosMs: Long,
    subtitleDelayMs: Long,
    isSubtitlesVisible: Boolean,
    isParsingSubtitles: Boolean,
    hasSubtitleFile: Boolean
): String {
    if (!isSubtitlesVisible) return ""
    return when {
        isParsingSubtitles -> "Loading subtitles..."
        cues.isNotEmpty() -> findSubtitleCue(cues, currentPosMs - subtitleDelayMs)?.text.orEmpty()
        hasSubtitleFile -> "No valid subtitles"
        else -> ""
    }
}

fun parseTimeMs(timeStr: String): Long {
    try {
        val parts = timeStr.replace(",", ".").split(":", ".")
        if (parts.size >= 3) {
            val h = parts[0].toLong() * 3_600_000
            val m = parts[1].toLong() * 60_000
            val s = parts[2].toLong() * 1000
            var ms = if (parts.size >= 4) parts[3].toLong() else 0L
            if (parts.size >= 4 && parts[3].length == 2) ms *= 10
            else if (parts.size >= 4 && parts[3].length == 1) ms *= 100
            return h + m + s + ms
        }
    } catch (_: Exception) {
    }
    return 0L
}

fun extractAudioDataCached(
    key: String,
    mediaPath: String,
    startTimeMs: Long,
    endTimeMs: Long,
    buckets: Int
): Pair<List<Float>, List<Float?>> {
    WaveformCache.get(key)?.let { return it }
    return extractAudioData(mediaPath, startTimeMs, endTimeMs, buckets).also { WaveformCache.put(key, it) }
}

fun extractAudioData(
    mediaPath: String,
    startTimeMs: Long,
    endTimeMs: Long,
    buckets: Int
): Pair<List<Float>, List<Float?>> {
    val ffmpeg = MediaTools.ffmpegCommand() ?: return emptyWaveform(buckets)
    val file = localMediaFile(mediaPath)
    if (!file.isFile && !mediaPath.startsWith("http", ignoreCase = true)) return emptyWaveform(buckets)

    val raw = File(WindowsPaths.cacheDir, "waveform_${System.nanoTime()}.s16le")
    val startSeconds = (startTimeMs.coerceAtLeast(0L) / 1000.0).toString()
    val args = mutableListOf(ffmpeg, "-y")
    if (startTimeMs > 0L) args += listOf("-ss", startSeconds)
    args += listOf("-i", mediaPath)
    if (endTimeMs > startTimeMs) {
        args += listOf("-t", ((endTimeMs - startTimeMs) / 1000.0).toString())
    }
    args += listOf(
        "-map", "0:a:0",
        "-vn",
        "-sn",
        "-dn",
        "-acodec", "pcm_s16le",
        "-ar", WAVEFORM_SAMPLE_RATE.toString(),
        "-ac", "1",
        "-sample_fmt", "s16",
        "-f", "s16le",
        raw.absolutePath
    )
    val result = runProcess(args, timeoutSeconds = 60)
    if (result.exitCode != 0 || !raw.isFile || raw.length() < 2L) {
        raw.delete()
        return emptyWaveform(buckets)
    }

    return try {
        val bytes = raw.readBytes()
        val shorts = ShortArray(bytes.size / 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in shorts.indices) shorts[i] = buffer.short

        val monoSamples = FloatArray(shorts.size) { index -> shorts[index] / 32768f }
        if (monoSamples.isEmpty()) return emptyWaveform(buckets)

        var globalSumSq = 0.0
        var globalPeak = 0f
        for (sample in monoSamples) {
            val absSample = abs(sample)
            globalSumSq += (sample * sample).toDouble()
            if (absSample > globalPeak) globalPeak = absSample
        }
        val globalRms = sqrt(globalSumSq / monoSamples.size).toFloat()
        if (globalPeak < 0.0005f || globalRms < 0.0002f) return emptyWaveform(buckets)

        val amplitudes = bucketAmplitudes(monoSamples, buckets, globalRms)
        val pitches = estimatePitchContourYin(monoSamples, WAVEFORM_SAMPLE_RATE, buckets, globalRms)
        amplitudes to pitches
    } catch (e: Exception) {
        CrashReporter.recordHandled("extractAudioData", e)
        emptyWaveform(buckets)
    } finally {
        raw.delete()
    }
}

fun localMediaFile(mediaPath: String): File {
    val trimmed = mediaPath.trim()
    if (trimmed.startsWith("file:", ignoreCase = true)) {
        val decoded = runCatching { URI(trimmed).path }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: trimmed.removePrefix("file://")
        val windowsPath = if (decoded.length >= 3 && decoded[0] == '/' && decoded[2] == ':') decoded.drop(1) else decoded
        return File(windowsPath)
    }
    return File(trimmed)
}

private fun emptyWaveform(buckets: Int): Pair<List<Float>, List<Float?>> =
    List(buckets) { 0f } to List<Float?>(buckets) { null }

private fun bucketAmplitudes(samples: FloatArray, buckets: Int, globalRms: Float): List<Float> {
    if (samples.isEmpty() || buckets <= 0) return emptyList()
    val samplesPerBucket = samples.size / buckets
    if (samplesPerBucket == 0) return emptyList()

    val rawBucketRms = FloatArray(buckets)
    for (index in 0 until buckets) {
        val start = index * samplesPerBucket
        val end = if (index == buckets - 1) samples.size else start + samplesPerBucket
        val length = end - start
        var sumSq = 0.0
        for (i in start until end) {
            val sample = samples[i].toDouble()
            sumSq += sample * sample
        }
        rawBucketRms[index] = if (length > 0) sqrt(sumSq / length).toFloat() else 0f
    }

    val sortedRms = rawBucketRms.toList().sorted()
    val noiseFloor = percentile(sortedRms, 0.10f)
    val referenceRms = percentile(sortedRms, 0.95f).coerceAtLeast(globalRms)
    val ampRange = (referenceRms - noiseFloor).coerceAtLeast(0.0001f)
    return List(buckets) { index ->
        val normalized = ((rawBucketRms[index] - noiseFloor) / ampRange).coerceIn(0f, 1f)
        Math.pow(normalized.toDouble(), 0.55).toFloat().coerceIn(0.06f, 1f)
    }
}

fun estimatePitch(samples: ShortArray, sampleRate: Int, channels: Int = 1): Float? {
    if (samples.isEmpty() || sampleRate <= 0) return null
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
    val rms = sqrt(sumSq / mono.size).toFloat()
    return estimatePitchYinFrame(mono, 0, mono.size, sampleRate, (rms * PITCH_FRAME_RMS_RATIO).coerceAtLeast(PITCH_MIN_FRAME_RMS))?.hz
}

private data class PitchEstimate(val hz: Float, val confidence: Float)

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
    val minFrameRms = (globalRms * PITCH_FRAME_RMS_RATIO).coerceIn(PITCH_MIN_FRAME_RMS, PITCH_MAX_FRAME_RMS)
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
    val rms = sqrt(sumSq / frameSize).toFloat()
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
        if (minValue > PITCH_FALLBACK_MAX_CMND) return null
        bestTau = minIndex
    }

    val refinedTau = parabolicTau(cmnd, bestTau)
    if (refinedTau <= 0f) return null

    val hz = sampleRate / refinedTau
    if (hz !in PITCH_MIN_HZ..PITCH_MAX_HZ) return null

    val confidence = (1f - cmnd[bestTau]).coerceIn(0f, 1f)
    if (confidence < PITCH_MIN_CONFIDENCE) return null

    return PitchEstimate(hz, confidence)
}

private fun parabolicTau(values: FloatArray, tau: Int): Float {
    if (tau <= 0 || tau >= values.lastIndex) return tau.toFloat()
    val left = values[tau - 1]
    val center = values[tau]
    val right = values[tau + 1]
    val denominator = left - 2f * center + right
    if (abs(denominator) < 0.000001f) return tau.toFloat()
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
                cleaned[i] = sqrt(previous * next)
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
                val logHz = ln(previous.toDouble()) * (1.0 - t) + ln(next.toDouble()) * t
                cleaned[i] = Math.exp(logHz).toFloat()
            }
        }
    }

    return cleaned
}

private fun semitoneDistance(a: Float, b: Float): Float {
    return abs(12.0 * ln((a / b).toDouble()) / ln(2.0)).toFloat()
}

data class PitchScale(val minLogHz: Double, val maxLogHz: Double)

fun pitchDisplayScale(pitches: List<Float?>): PitchScale? {
    val logs = pitches
        .mapNotNull { pitch -> pitch?.takeIf { it in PITCH_MIN_HZ..PITCH_MAX_HZ } }
        .map { ln(it.toDouble()) }
        .sorted()

    if (logs.size < 2) return null

    val low = percentileDouble(logs, 0.05)
    val high = percentileDouble(logs, 0.95)
    val minSpan = ln(2.0) * (5.0 / 12.0)
    val center = (low + high) / 2.0
    val span = (high - low).coerceAtLeast(minSpan)
    return PitchScale(center - span / 2.0, center + span / 2.0)
}

fun pitchToNormalizedY(pitchHz: Float, scale: PitchScale): Float {
    val logHz = ln(pitchHz.coerceIn(PITCH_MIN_HZ, PITCH_MAX_HZ).toDouble())
    return (1.0 - ((logHz - scale.minLogHz) / (scale.maxLogHz - scale.minLogHz))).toFloat().coerceIn(0f, 1f)
}

private fun percentile(sortedValues: List<Float>, percentile: Float): Float {
    if (sortedValues.isEmpty()) return 0f
    val index = ((sortedValues.lastIndex) * percentile.coerceIn(0f, 1f)).roundToInt()
    return sortedValues[index.coerceIn(sortedValues.indices)]
}

private fun percentileDouble(sortedValues: List<Double>, percentile: Double): Double {
    if (sortedValues.isEmpty()) return 0.0
    val index = (sortedValues.lastIndex * percentile.coerceIn(0.0, 1.0)).roundToInt()
    return sortedValues[index.coerceIn(sortedValues.indices)]
}
