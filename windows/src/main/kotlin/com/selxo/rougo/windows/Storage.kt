package com.selxo.rougo.windows

import org.json.JSONArray
import org.json.JSONObject
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object WindowsPaths {
    val appDir: File by lazy {
        val base = System.getenv("APPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("user.home"), "AppData/Roaming")
        File(base, "Rougo").also { it.mkdirs() }
    }
    val cacheDir: File by lazy { File(appDir, "cache").also { it.mkdirs() } }
    val coversDir: File by lazy { File(appDir, "covers").also { it.mkdirs() } }
    val downloadsDir: File by lazy { File(appDir, "downloads").also { it.mkdirs() } }
    val recordingsDir: File by lazy { File(appDir, "recordings").also { it.mkdirs() } }
    val subtitlesDir: File by lazy { File(appDir, "subtitles").also { it.mkdirs() } }
    val dictionariesDir: File by lazy { File(appDir, "hoshi_dicts").also { it.mkdirs() } }
    val exportsDir: File by lazy {
        val music = File(System.getProperty("user.home"), "Music")
        File(music, "Rougo").also { it.mkdirs() }
    }
}

class JsonPrefs(private val file: File) {
    private val lock = Any()
    private var data: JSONObject = read()

    fun getString(key: String, defaultValue: String? = null): String? = synchronized(lock) {
        data.optString(key, defaultValue ?: "").takeIf { it.isNotEmpty() } ?: defaultValue
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean = synchronized(lock) {
        if (data.has(key)) data.optBoolean(key, defaultValue) else defaultValue
    }

    fun getInt(key: String, defaultValue: Int): Int = synchronized(lock) {
        if (data.has(key)) data.optInt(key, defaultValue) else defaultValue
    }

    fun getLong(key: String, defaultValue: Long): Long = synchronized(lock) {
        if (data.has(key)) data.optLong(key, defaultValue) else defaultValue
    }

    fun contains(key: String): Boolean = synchronized(lock) {
        data.has(key)
    }

    fun putString(key: String, value: String?) = edit {
        if (value == null) remove(key) else put(key, value)
    }

    fun putBoolean(key: String, value: Boolean) = edit { put(key, value) }

    fun putInt(key: String, value: Int) = edit { put(key, value) }

    fun putLong(key: String, value: Long) = edit { put(key, value) }

    fun remove(key: String) = edit { remove(key) }

    private fun edit(block: JSONObject.() -> Unit) = synchronized(lock) {
        data.block()
        write()
    }

    private fun read(): JSONObject {
        return runCatching {
            if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject()
        }.getOrElse {
            CrashReporter.recordHandled("JsonPrefs.read ${file.name}", it)
            JSONObject()
        }
    }

    private fun write() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(data.toString(2), Charsets.UTF_8)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

object Prefs {
    val app = JsonPrefs(File(WindowsPaths.appDir, "app_prefs.json"))
    val dictionary = JsonPrefs(File(WindowsPaths.appDir, "hoshi_engine.json"))
}

fun readThemeModePreference(prefs: JsonPrefs): String =
    prefs.getString(PREF_THEME_MODE, null)
        ?: if (prefs.getBoolean(PREF_LIGHT_MODE, false)) THEME_LIGHT else THEME_DARK

fun writeThemeModePreference(prefs: JsonPrefs, mode: String) {
    prefs.putString(PREF_THEME_MODE, mode)
    prefs.putBoolean(PREF_LIGHT_MODE, mode == THEME_LIGHT)
}

object CrashReporter {
    private val lastCrashFile = File(WindowsPaths.appDir, "last_crash.txt")
    private val handledFile = File(WindowsPaths.appDir, "handled_errors.log")

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrash(thread, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun readLastCrash(): String? = lastCrashFile.takeIf { it.isFile }?.readText(Charsets.UTF_8)

    fun clearLastCrash() {
        lastCrashFile.delete()
    }

    fun recordHandled(area: String, throwable: Throwable) {
        runCatching {
            handledFile.parentFile?.mkdirs()
            handledFile.appendText(
                buildString {
                    appendLine("=== ${timestamp()} $area ===")
                    appendLine(stackTrace(throwable))
                },
                Charsets.UTF_8
            )
        }
    }

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        runCatching {
            lastCrashFile.writeText(
                buildString {
                    appendLine("Rougo Windows crash")
                    appendLine("Time: ${timestamp()}")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Version: $APP_VERSION")
                    appendLine()
                    appendLine(stackTrace(throwable))
                },
                Charsets.UTF_8
            )
        }
    }

    private fun stackTrace(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
}

class LibraryManager(private val libraryFile: File = File(WindowsPaths.appDir, "rougo_library.json")) {
    private val lock = Any()

    fun getItems(): List<LibraryItem> = synchronized(lock) {
        val jsonString = migrateAndroidSharedPreferenceIfNeeded()
        val jsonArray = try {
            JSONArray(jsonString)
        } catch (e: Exception) {
            CrashReporter.recordHandled("LibraryManager.getItems root JSON", e)
            JSONArray()
        }
        buildList {
            for (i in 0 until jsonArray.length()) {
                try {
                    add(parseItem(jsonArray.getJSONObject(i)))
                } catch (e: Exception) {
                    CrashReporter.recordHandled("LibraryManager.getItems item $i", e)
                }
            }
        }
    }

    fun saveItem(item: LibraryItem) = synchronized(lock) {
        val current = getItems().toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) current[index] = item else current.add(0, item)
        saveItemsArray(current)
    }

    fun saveItems(newItems: List<LibraryItem>) = synchronized(lock) {
        if (newItems.isEmpty()) return@synchronized
        val current = getItems()
        val incomingById = newItems.associateBy { it.id }
        val existingIds = current.map { it.id }.toSet()
        val additions = newItems.filter { it.id !in existingIds }
        val updated = current.map { incomingById[it.id] ?: it }
        saveItemsArray(additions + updated)
    }

    fun moveItemToFolder(itemId: String, targetFolderId: String?): Boolean = synchronized(lock) {
        val items = getItems()
        if (targetFolderId != null && items.none { it.id == targetFolderId && it.isFolderGroup() }) return false
        val nextIndex = items.filter { it.parentId == targetFolderId }
            .maxOfOrNull { it.playlistItemIndex }
            ?.plus(1)
            ?: 0
        var changed = false
        val updatedItems = items.map { item ->
            if (item.id == itemId && !item.isFolderGroup()) {
                changed = changed || item.parentId != targetFolderId || item.playlistItemIndex != nextIndex
                item.copy(parentId = targetFolderId, playlistItemIndex = nextIndex)
            } else {
                item
            }
        }
        if (!changed) return@synchronized false
        saveItemsArray(updatedItems)
        true
    }

    fun deleteItem(id: String) = synchronized(lock) {
        val items = getItems()
        items.filter { it.id == id || it.parentId == id }.forEach { deleteLibraryItemAssociatedFiles(it) }
        saveItemsArray(items.filter { it.id != id && it.parentId != id })
    }

    private fun saveItemsArray(items: List<LibraryItem>) {
        libraryFile.parentFile?.mkdirs()
        val array = JSONArray()
        items.forEach { array.put(itemToJson(it)) }
        val tmp = File(libraryFile.parentFile, "${libraryFile.name}.tmp")
        tmp.writeText(array.toString(2), Charsets.UTF_8)
        Files.move(tmp.toPath(), libraryFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun migrateAndroidSharedPreferenceIfNeeded(): String {
        if (libraryFile.isFile) return libraryFile.readText(Charsets.UTF_8)
        val sharedPrefsXml = File(WindowsPaths.appDir, "rougo_library.xml")
        if (sharedPrefsXml.isFile) {
            val match = Regex("""<string\s+name="items">(.+?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .find(sharedPrefsXml.readText(Charsets.UTF_8))
            if (match != null) {
                val xmlValue = match.groupValues[1]
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                libraryFile.writeText(xmlValue, Charsets.UTF_8)
                return xmlValue
            }
        }
        return "[]"
    }

    private fun parseItem(obj: JSONObject): LibraryItem {
        val itemKind = runCatching {
            LibraryItemKind.valueOf(obj.optString("itemKind", LibraryItemKind.Media.name))
        }.getOrDefault(LibraryItemKind.Media)
        val mediaUri = obj.optString("mediaUri").takeIf { it.isNotBlank() }
            ?: if (itemKind != LibraryItemKind.Media) "" else ""

        val recordingsList = mutableListOf<ShadowRecording>()
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
                CrashReporter.recordHandled("LibraryManager.getItems recording", e)
            }
        }

        return LibraryItem(
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
            httpReferer = obj.optCleanString("httpReferer"),
            itemKind = itemKind,
            parentId = obj.optString("parentId").takeIf { it.isNotBlank() },
            playlistSourceUrl = obj.optString("playlistSourceUrl").takeIf { it.isNotBlank() },
            playlistItemIndex = obj.optInt("playlistItemIndex", 0)
        ).withoutLegacyYoutubeVideoOnlyFormat()
    }

    private fun itemToJson(item: LibraryItem): JSONObject {
        val obj = JSONObject()
        obj.put("id", item.id)
        obj.put("title", item.title)
        obj.put("mediaUri", item.mediaUri)
        obj.put("subtitleUri", item.subtitleUri ?: "")
        obj.put("progress", item.progress)
        obj.put("duration", item.duration)
        obj.put("isVideo", item.isVideo)
        obj.put("sourceUrl", item.sourceUrl ?: "")
        obj.put("formatId", item.formatId ?: "")
        obj.put("artist", item.artist ?: "")
        obj.put("album", item.album ?: "")
        obj.put("albumArtist", item.albumArtist ?: "")
        obj.put("genre", item.genre ?: "")
        obj.put("year", item.year ?: "")
        obj.put("coverArtPath", item.coverArtPath ?: "")
        obj.put("httpUserAgent", item.httpUserAgent ?: "")
        obj.put("httpReferer", item.httpReferer ?: "")
        obj.put("itemKind", item.itemKind.name)
        obj.put("parentId", item.parentId ?: "")
        obj.put("playlistSourceUrl", item.playlistSourceUrl ?: "")
        obj.put("playlistItemIndex", item.playlistItemIndex)
        val recArray = JSONArray()
        item.recordings.forEach { rec ->
            recArray.put(
                JSONObject()
                    .put("id", rec.id)
                    .put("filePath", rec.filePath)
                    .put("startTime", rec.startTime)
                    .put("endTime", rec.endTime)
                    .put("timestamp", rec.timestamp)
            )
        }
        obj.put("recordings", recArray)
        return obj
    }
}

fun deleteDownloadedLocalCopy(item: LibraryItem): LibraryItem? {
    val source = item.sourceUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (!item.hasDownloadedLocalCopy()) return null
    if (!deleteAppOwnedFilePath(item.mediaUri)) return null
    return item.copy(mediaUri = source)
}

fun deleteLibraryItemAssociatedFiles(item: LibraryItem) {
    if (item.isFolderGroup()) return
    item.coverArtPath?.let { deleteAppOwnedFilePath(it) }
    item.subtitleUri?.let { deleteAppOwnedFilePath(it) }
    item.recordings.forEach { recording -> deleteAppOwnedFilePath(recording.filePath) }
    if (item.hasDownloadedLocalCopy()) deleteAppOwnedFilePath(item.mediaUri)
    deleteAppOwnedDownloadFilesForItem(item.id)
}

private fun deleteAppOwnedDownloadFilesForItem(itemId: String) {
    val fileId = itemId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    WindowsPaths.downloadsDir.listFiles()
        ?.filter { it.isFile && it.name.startsWith(fileId) }
        ?.forEach { deleteAppOwnedFile(it) }
}

private fun deleteAppOwnedFilePath(path: String): Boolean {
    val file = path.removePrefix("file://").let(::File)
    return deleteAppOwnedFile(file)
}

private fun deleteAppOwnedFile(file: File): Boolean {
    return try {
        val canonicalFile = file.canonicalFile
        if (!isAppOwnedFile(canonicalFile)) return false
        !canonicalFile.exists() || canonicalFile.delete()
    } catch (e: Exception) {
        false
    }
}

private fun isAppOwnedFile(file: File): Boolean {
    val roots = listOf(
        WindowsPaths.appDir,
        WindowsPaths.cacheDir,
        WindowsPaths.coversDir,
        WindowsPaths.downloadsDir,
        WindowsPaths.recordingsDir,
        WindowsPaths.subtitlesDir
    )
    val filePath = runCatching { file.canonicalPath }.getOrNull() ?: return false
    return roots.any { root ->
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return@any false
        filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
    }
}

fun openSingleFileDialog(title: String, extensions: List<String> = emptyList()): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    if (extensions.isNotEmpty()) {
        dialog.filenameFilter = java.io.FilenameFilter { _, name ->
            extensions.any { ext -> name.endsWith(ext, ignoreCase = true) }
        }
    }
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(directory, file)
}

fun openZipFileDialog(title: String = "Import Dictionary ZIP"): File? =
    openSingleFileDialog(title, listOf(".zip"))

fun exportRecording(file: File): File? {
    return runCatching {
        val target = File(WindowsPaths.exportsDir, "RougoShare_${System.currentTimeMillis()}_${file.name}")
        Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(WindowsPaths.exportsDir)
        }
        DesktopNotifier.info("Exported to Music/Rougo", target.name)
        target
    }.getOrElse {
        CrashReporter.recordHandled("exportRecording", it)
        DesktopNotifier.warning("Export failed", it.message.orEmpty())
        null
    }
}

fun copyIntoAppSubtitles(file: File): String? {
    return runCatching {
        val safeName = "${System.currentTimeMillis()}_${file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        val target = File(WindowsPaths.subtitlesDir, safeName)
        Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        target.absolutePath
    }.getOrElse {
        CrashReporter.recordHandled("copyIntoAppSubtitles", it)
        null
    }
}
