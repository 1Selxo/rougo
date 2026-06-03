package com.selxo.rougo

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.core.content.edit
import com.selxo.rougo.dictionary.DeinflectorRegistry
import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.ImportResult
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.RougoHoshi
import de.manhhao.hoshi.TermResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.LinkedHashMap
import java.util.Locale

data class DictEntry(
    val term: String,
    val deinflected: String,
    val reading: String,
    val definition: String,
    val dictName: String,
    val definitionTags: String = "",
    val termTags: String = "",
    val pitchPositions: List<PitchInfo> = emptyList()
)

data class PitchInfo(val dictName: String, val position: Int)

class DictionaryEngine private constructor(private val context: Context) {

    companion object {
        private val CASE_FOLDING_LANGUAGES = setOf("de", "en", "es", "fr", "it", "ru")
        private val PREFIX_SCANNING_LANGUAGES = setOf("zh")
        private val WORD_SCANNING_LANGUAGES = setOf("de", "en")

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

    private data class CandidateLookup(
        val matched: String,
        val deinflected: String,
        val term: TermResult
    )

    private val prefs = context.getSharedPreferences("hoshi_engine", Context.MODE_PRIVATE)

    private val dictsDir: File
        get() = File(context.filesDir, "hoshi_dicts").also { it.mkdirs() }

    fun isNoiseCancellationEnabled(): Boolean = prefs.getBoolean("noise_cancel", false)
    fun setNoiseCancellationEnabled(enabled: Boolean) = prefs.edit { putBoolean("noise_cancel", enabled) }

    fun isDictionaryBlockCollapseEnabled(dictName: String): Boolean {
        return prefs.getBoolean(
            dictionaryBlockCollapseKey(dictName),
            prefs.getBoolean(dictionaryNestedCollapseKey(dictName), true)
        )
    }

    fun setDictionaryBlockCollapseEnabled(dictName: String, enabled: Boolean) {
        prefs.edit {
            putBoolean(dictionaryBlockCollapseKey(dictName), enabled)
            remove(dictionaryNestedCollapseKey(dictName))
        }
    }

    fun getTargetLanguage(): String {
        val saved = prefs.getString("target_language", DeinflectorRegistry.DEFAULT_LANGUAGE)
            ?: DeinflectorRegistry.DEFAULT_LANGUAGE
        val normalized = DeinflectorRegistry.normalize(saved)
        return if (DeinflectorRegistry.isSupported(normalized)) {
            normalized
        } else {
            DeinflectorRegistry.DEFAULT_LANGUAGE
        }
    }

    fun setTargetLanguage(languageCode: String) {
        val normalized = DeinflectorRegistry.normalize(languageCode)
        val target = if (DeinflectorRegistry.isSupported(normalized)) {
            normalized
        } else {
            DeinflectorRegistry.DEFAULT_LANGUAGE
        }
        prefs.edit { putString("target_language", target) }
    }

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
        prefs.edit {
            remove(dictionaryBlockCollapseKey(name))
            remove(dictionaryNestedCollapseKey(name))
        }
        loadDictionaries()
    }

    suspend fun searchPrefixes(queryStr: String): List<DictEntry> = withContext(Dispatchers.IO) {
        val cleanQuery = queryStr.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        try {
            val targetLanguage = getTargetLanguage()
            if (targetLanguage == DeinflectorRegistry.DEFAULT_LANGUAGE) {
                searchWithNativeLookup(cleanQuery)
            } else {
                searchWithLanguageDeinflector(cleanQuery, targetLanguage)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Dictionary lookup failed.", Toast.LENGTH_SHORT).show()
            }
            emptyList()
        }
    }

    private fun searchWithNativeLookup(cleanQuery: String): List<DictEntry> {
        val results: Array<LookupResult> = HoshiDicts.lookup(HoshiDicts.lookupObject, cleanQuery, 20, 25)
        return results.flatMap { lookupResult ->
            entriesForTerm(
                matched = lookupResult.matched,
                deinflected = lookupResult.deinflected,
                term = lookupResult.term
            )
        }
    }

    private fun searchWithLanguageDeinflector(cleanQuery: String, languageCode: String): List<DictEntry> {
        val deinflector = DeinflectorRegistry.get(languageCode) ?: return searchWithNativeLookup(cleanQuery)
        val matchedTerms = LinkedHashMap<String, CandidateLookup>()
        val substrings = lookupSubstrings(cleanQuery, languageCode)

        for (substring in substrings) {
            if (substring.isBlank()) continue

            val previousMatchCount = matchedTerms.size
            val candidates = linkedSetOf<String>()
            deinflector.preProcess(substring)
                .flatMap { lookupTextVariants(it, languageCode) }
                .distinct()
                .forEach { variant ->
                    deinflector.deinflect(variant, languageCode).forEach { result ->
                        val candidate = result.text.trim()
                        if (candidate.isNotBlank()) {
                            candidates += candidate
                        }
                    }
                }

            for (candidate in candidates) {
                lookupExactCandidate(candidate).forEach { term ->
                    val key = "${term.expression}\u0000${term.reading}"
                    matchedTerms.putIfAbsent(key, CandidateLookup(substring, candidate, term))
                }
                if (matchedTerms.size >= 20) break
            }
            if (matchedTerms.size > previousMatchCount || matchedTerms.size >= 20) break
        }

        return matchedTerms.values
            .take(20)
            .flatMap { result ->
                entriesForTerm(
                    matched = result.matched,
                    deinflected = result.deinflected,
                    term = result.term
                )
            }
    }

    private fun lookupExactCandidate(candidate: String): List<TermResult> {
        val scanLength = candidate.length.coerceIn(1, 25)
        return HoshiDicts.lookup(HoshiDicts.lookupObject, candidate, 20, scanLength)
            .asSequence()
            .filter { it.matched == candidate && it.deinflected == candidate }
            .map { it.term }
            .toList()
    }

    private fun lookupSubstrings(cleanQuery: String, languageCode: String): List<String> {
        val normalized = cleanLookupText(cleanQuery, languageCode)
        if (normalized.isBlank()) return emptyList()
        if (languageCode in WORD_SCANNING_LANGUAGES) return wordSubstrings(normalized)
        if (languageCode !in PREFIX_SCANNING_LANGUAGES) return listOf(normalized)

        val scanLength = normalized.length.coerceAtMost(25)
        return (scanLength downTo 1)
            .map { normalized.take(it).trim() }
            .filter { it.isNotBlank() }
    }

    private fun wordSubstrings(text: String): List<String> {
        val tokenEnds = mutableListOf<Int>()
        var inToken = false

        for (i in text.indices) {
            if (isLookupTokenChar(text[i])) {
                inToken = true
            } else if (inToken) {
                tokenEnds += i
                inToken = false
            }
        }
        if (inToken) tokenEnds += text.length

        return tokenEnds
            .asReversed()
            .map { end -> text.substring(0, end).trim().trim { !isLookupTokenChar(it) } }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun cleanLookupText(text: String, languageCode: String): String {
        val trimmed = text.trim()
        if (languageCode in PREFIX_SCANNING_LANGUAGES) return trimmed
        return trimmed.trim { !isLookupTokenChar(it) }
    }

    private fun lookupTextVariants(text: String, languageCode: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyList()
        if (languageCode !in CASE_FOLDING_LANGUAGES) return listOf(trimmed)

        val lowercase = trimmed.lowercase(Locale.ROOT)
        return if (lowercase == trimmed) listOf(trimmed) else listOf(trimmed, lowercase)
    }

    private fun isLookupTokenChar(char: Char): Boolean {
        return char.isLetterOrDigit() || char == '\'' || char == '\u2019' || char == '-' || char == '\u2010' || char == '\u2011'
    }

    private fun entriesForTerm(matched: String, deinflected: String, term: TermResult): List<DictEntry> {
        val allPitches = term.pitches.flatMap { entry ->
            entry.pitchPositions.map { pos -> PitchInfo(entry.dictName, pos) }
        }

        return term.glossaries.map { glossary ->
            DictEntry(
                term = matched,
                deinflected = deinflected,
                reading = term.reading,
                definition = glossary.glossary,
                dictName = glossary.dictName,
                definitionTags = glossary.definitionTags,
                termTags = glossary.termTags,
                pitchPositions = allPitches
            )
        }
    }

    private fun dictionaryBlockCollapseKey(dictName: String): String = "dict_block_collapse_$dictName"
    private fun dictionaryNestedCollapseKey(dictName: String): String = "dict_nested_collapse_$dictName"

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
