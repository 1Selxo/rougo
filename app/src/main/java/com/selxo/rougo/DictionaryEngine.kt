package com.selxo.rougo

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.core.content.edit
import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.ImportResult
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.RougoHoshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

data class DictEntry(
    val term: String,
    val deinflected: String,
    val reading: String,
    val definition: String,
    val dictName: String,
    val pitchPositions: List<PitchInfo> = emptyList()
)

data class PitchInfo(val dictName: String, val position: Int)

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

    private data class EntryTypes(
        val termCount: Long,
        val freqCount: Long,
        val pitchCount: Long
    ) {
        val hasAny: Boolean
            get() = termCount > 0 || freqCount > 0 || pitchCount > 0
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
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveDictOrder(order: List<String>) {
        prefs.edit { putString("dict_order", JSONArray(order).toString()) }
        loadDictionaries()
    }

    fun loadDictionaries() {
        val termPaths = mutableListOf<String>()
        val freqPaths = mutableListOf<String>()
        val pitchPaths = mutableListOf<String>()

        sortedDictionaryFolders().forEach { folder ->
            val types = entryTypes(folder)
            if (types.termCount > 0) termPaths += folder.absolutePath
            if (types.freqCount > 0) freqPaths += folder.absolutePath
            if (types.pitchCount > 0) pitchPaths += folder.absolutePath
        }

        HoshiDicts.rebuildQuery(
            session = HoshiDicts.lookupObject,
            termPaths = termPaths.toTypedArray(),
            freqPaths = freqPaths.toTypedArray(),
            pitchPaths = pitchPaths.toTypedArray()
        )
    }

    suspend fun downloadJmdict(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (hasUsableDictionaryNamed("JMdict")) return@withContext

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
        return sortedDictionaryFolders().map { it.name }
    }

    fun deleteDict(name: String) {
        File(dictsDir, name).deleteRecursively()
        loadDictionaries()
    }

    suspend fun searchPrefixes(queryStr: String): List<DictEntry> = withContext(Dispatchers.IO) {
        val cleanQuery = queryStr.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        try {
            val results: Array<LookupResult> = HoshiDicts.lookup(HoshiDicts.lookupObject, cleanQuery, 20, 25)
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
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Dictionary lookup failed.", Toast.LENGTH_SHORT).show()
            }
            emptyList()
        }
    }

    private fun sortedDictionaryFolders(): List<File> {
        val allFolders = dictsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val order = getDictOrder()
        return allFolders.sortedBy { folder ->
            val idx = order.indexOf(folder.name)
            if (idx == -1) Int.MAX_VALUE else idx
        }
    }

    private fun hasUsableDictionaryNamed(namePart: String): Boolean {
        return sortedDictionaryFolders().any { folder ->
            folder.name.contains(namePart, ignoreCase = true) && entryTypes(folder).hasAny
        }
    }

    private fun entryTypes(folder: File): EntryTypes {
        if (!File(folder, ".hoshidicts_1").isFile) return EntryTypes(0, 0, 0)
        if (!File(folder, "hash.table").isFile || !File(folder, "blobs.bin").isFile) {
            return EntryTypes(0, 0, 0)
        }

        val counts = runCatching { RougoHoshi.probeEntryTypes(folder.absolutePath) }.getOrNull()
            ?: return EntryTypes(0, 0, 0)
        return EntryTypes(
            termCount = counts.getOrElse(0) { 0L },
            freqCount = counts.getOrElse(1) { 0L },
            pitchCount = counts.getOrElse(2) { 0L }
        )
    }
}
