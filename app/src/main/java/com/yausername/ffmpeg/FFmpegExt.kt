package com.yausername.ffmpeg

import com.yausername.youtubedl_android.YoutubeDL
import java.io.File

/**
 * Extension function to provide standalone FFmpeg execution support for youtubedl-android:ffmpeg 0.18.1.
 * This satisfies the signature requested by the user and implements it by running the unzipped binary.
 */
fun FFmpeg.execute(commands: Array<String>): Int {
    return try {
        val ytdl = YoutubeDL.getInstance()
        val ffmpegPath = reflectedFile(ytdl, "ffmpegPath")?.absolutePath ?: return -1

        val processBuilder = ProcessBuilder(mutableListOf(ffmpegPath).apply { addAll(commands) })
            .redirectErrorStream(true)

        reflectedString(ytdl, "ENV_LD_LIBRARY_PATH")?.takeIf { it.isNotBlank() }?.let {
            processBuilder.environment()["LD_LIBRARY_PATH"] = it
        }
        reflectedString(ytdl, "TMPDIR")?.takeIf { it.isNotBlank() }?.let {
            processBuilder.environment()["TMPDIR"] = it
        }
        reflectedFile(ytdl, "binDir")?.absolutePath?.let { binDir ->
            val currentPath = processBuilder.environment()["PATH"].orEmpty()
            processBuilder.environment()["PATH"] = if (currentPath.isBlank()) binDir else "$currentPath:$binDir"
        }

        val process = processBuilder.start()
        val outputDrain = Thread {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (input.read(buffer) >= 0) {
                        // Drain ffmpeg output so the process cannot block on a full pipe.
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        outputDrain.start()

        val exitCode = process.waitFor()
        outputDrain.join(1000)
        exitCode
    } catch (t: Throwable) {
        t.printStackTrace()
        -1
    }
}

private fun reflectedFile(instance: Any, fieldName: String): File? {
    return try {
        val field = instance.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.get(instance) as? File
    } catch (e: Exception) {
        null
    }
}

private fun reflectedString(instance: Any, fieldName: String): String? {
    return try {
        val field = instance.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.get(instance) as? String
    } catch (e: Exception) {
        null
    }
}

/**
 * Extension property to provide the RETURN_CODE_SUCCESS constant.
 */
val FFmpeg.RETURN_CODE_SUCCESS: Int get() = 0
