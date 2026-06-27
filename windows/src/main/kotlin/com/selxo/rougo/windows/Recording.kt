package com.selxo.rougo.windows

import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

class ShadowAudioRecorder(private val noiseCancellationEnabled: Boolean) {
    private val preferredFormats = listOf(
        AudioFormat(RECORD_SAMPLE_RATE.toFloat(), 16, RECORD_CHANNEL_COUNT, true, false),
        AudioFormat(44_100f, 16, RECORD_CHANNEL_COUNT, true, false)
    )
    private var line: TargetDataLine? = null
    private var worker: Thread? = null
    private var wavFile: File? = null

    fun start(file: File): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val tempWav = File(file.parentFile, "${file.nameWithoutExtension}.wav")
            for (format in preferredFormats) {
                val info = DataLine.Info(TargetDataLine::class.java, format)
                if (!AudioSystem.isLineSupported(info)) continue
                val nextLine = AudioSystem.getLine(info) as TargetDataLine
                try {
                    val bufferSize = ((format.sampleRate.toInt() / 5) * RECORD_BYTES_PER_FRAME).coerceAtLeast(4096)
                    nextLine.open(format, bufferSize)
                    nextLine.start()
                    line = nextLine
                    wavFile = tempWav
                    worker = thread(start = true, name = "rougo-recorder") {
                        AudioInputStream(nextLine).use { stream ->
                            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, tempWav)
                        }
                    }
                    return true
                } catch (t: Throwable) {
                    runCatching { nextLine.close() }
                }
            }
            false
        } catch (t: Throwable) {
            CrashReporter.recordHandled("ShadowAudioRecorder.start", t)
            false
        }
    }

    fun stop(targetFile: File): File? {
        val currentLine = line ?: return null
        return try {
            currentLine.stop()
            currentLine.close()
            worker?.join(2000)
            val tempWav = wavFile ?: return null
            val ffmpeg = MediaTools.ffmpegCommand()
            if (ffmpeg != null) {
                val args = recordingFfmpegArgs(
                    ffmpeg = ffmpeg,
                    inputFile = tempWav,
                    targetFile = targetFile,
                    noiseCancellationEnabled = noiseCancellationEnabled,
                    rnnoiseModelFile = MediaTools.rnnoiseModelFile()
                )
                val result = runProcess(
                    args,
                    timeoutSeconds = 60
                )
                if (result.exitCode == 0 && targetFile.isFile && targetFile.length() > 0L) {
                    tempWav.delete()
                    targetFile
                } else {
                    tempWav
                }
            } else {
                tempWav
            }
        } catch (t: Throwable) {
            CrashReporter.recordHandled("ShadowAudioRecorder.stop", t)
            null
        } finally {
            line = null
            worker = null
            wavFile = null
        }
    }

    fun cancel() {
        runCatching {
            line?.stop()
            line?.close()
            worker?.join(1000)
            wavFile?.delete()
        }
        line = null
        worker = null
        wavFile = null
    }

    private companion object {
        const val RECORD_SAMPLE_RATE = 48_000
        const val RECORD_CHANNEL_COUNT = 1
        const val RECORD_BIT_RATE = "160k"
        const val RECORD_BYTES_PER_FRAME = 2 * RECORD_CHANNEL_COUNT
    }
}

internal fun recordingFfmpegArgs(
    ffmpeg: String,
    inputFile: File,
    targetFile: File,
    noiseCancellationEnabled: Boolean,
    rnnoiseModelFile: File? = null
): List<String> = buildList {
    add(ffmpeg)
    add("-y")
    add("-i")
    add(inputFile.absolutePath)
    if (noiseCancellationEnabled) {
        add("-af")
        add(recordingNoiseSuppressionFilter(rnnoiseModelFile))
    }
    add("-ar")
    add("48000")
    add("-ac")
    add("1")
    add("-c:a")
    add("aac")
    add("-b:a")
    add("160k")
    add(targetFile.absolutePath)
}

internal fun recordingNoiseSuppressionFilter(rnnoiseModelFile: File?): String {
    return if (rnnoiseModelFile?.isFile == true) {
        "arnndn=m=${rnnoiseModelFile.ffmpegFilterPath()},highpass=f=120,lowpass=f=8500,dynaudnorm=f=150:g=15"
    } else {
        WINDOWS_NOISE_CANCELLATION_FALLBACK_FILTER
    }
}

private fun File.ffmpegFilterPath(): String =
    absolutePath
        .replace("\\", "/")
        .replace(":", """\\\:""")

internal const val WINDOWS_NOISE_CANCELLATION_FALLBACK_FILTER =
    "highpass=f=120,lowpass=f=8500,afftdn=nf=-35:nr=20,anlmdn,dynaudnorm=f=150:g=15"
