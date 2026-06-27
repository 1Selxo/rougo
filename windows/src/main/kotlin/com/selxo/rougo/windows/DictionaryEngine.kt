package com.selxo.rougo.windows

import com.selxo.rougo.dictionary.DeinflectorRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

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

private data class StoredTerm(
    val expression: String,
    val reading: String,
    val definitionTags: String,
    val rules: String,
    val glossary: String,
    val termTags: String,
    val dictName: String
)

private data class CandidateLookup(
    val matched: String,
    val deinflected: String,
    val terms: List<StoredTerm>
)

class DictionaryEngine private constructor() {
    companion object {
        private val CASE_FOLDING_LANGUAGES = setOf("de", "en", "es", "fr", "it", "ru")
        private val PREFIX_SCANNING_LANGUAGES = setOf("zh")
        private val WORD_SCANNING_LANGUAGES = setOf("de", "en")

        val instance: DictionaryEngine by lazy { DictionaryEngine() }
    }

    private val prefs = Prefs.dictionary
    private val dictsDir = WindowsPaths.dictionariesDir
    private val loadedTerms = LinkedHashMap<String, MutableList<StoredTerm>>()
    private val pitchByExpression = LinkedHashMap<String, MutableList<PitchInfo>>()

    fun isNoiseCancellationEnabled(): Boolean = prefs.getBoolean("noise_cancel", false)
    fun setNoiseCancellationEnabled(enabled: Boolean) = prefs.putBoolean("noise_cancel", enabled)

    fun getTargetLanguage(): String {
        val saved = prefs.getString("target_language", DeinflectorRegistry.DEFAULT_LANGUAGE)
            ?: DeinflectorRegistry.DEFAULT_LANGUAGE
        val normalized = DeinflectorRegistry.normalize(saved)
        return if (DeinflectorRegistry.isSupported(normalized)) normalized else DeinflectorRegistry.DEFAULT_LANGUAGE
    }

    fun setTargetLanguage(languageCode: String) {
        val normalized = DeinflectorRegistry.normalize(languageCode)
        prefs.putString(
            "target_language",
            if (DeinflectorRegistry.isSupported(normalized)) normalized else DeinflectorRegistry.DEFAULT_LANGUAGE
        )
    }

    fun isDictionaryBlockCollapseEnabled(dictName: String): Boolean {
        val blockKey = dictionaryBlockCollapseKey(dictName)
        return if (prefs.contains(blockKey)) {
            prefs.getBoolean(blockKey, true)
        } else {
            prefs.getBoolean(dictionaryNestedCollapseKey(dictName), true)
        }
    }

    fun setDictionaryBlockCollapseEnabled(dictName: String, enabled: Boolean) {
        prefs.putBoolean(dictionaryBlockCollapseKey(dictName), enabled)
        prefs.remove(dictionaryNestedCollapseKey(dictName))
    }

    fun isPitchDictionary(dictName: String): Boolean =
        File(dictsDir, dictName).resolve("pitch.jsonl").isFile

    fun getDictOrder(): List<String> {
        val json = prefs.getString("dict_order", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveDictOrder(order: List<String>) {
        prefs.putString("dict_order", JSONArray(order).toString())
        loadDictionaries()
    }

    fun loadDictionaries() {
        loadedTerms.clear()
        pitchByExpression.clear()
        sortedDictionaryFolders().forEach { folder ->
            loadDictionaryFolder(folder)
        }
    }

    suspend fun downloadJmdict(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (getInstalledDictionaries().any { it.contains("JMdict", ignoreCase = true) }) return@withContext
        try {
            onProgress("Downloading JMdict...")
            val connection = URL("https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/JMdict_english.zip")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            val tempFile = File(WindowsPaths.cacheDir, "jmdict_download.zip")
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            importZip(tempFile, onProgress)
            tempFile.delete()
        } catch (e: Exception) {
            CrashReporter.recordHandled("DictionaryEngine.downloadJmdict", e)
            onProgress("JMdict download failed: ${e.message}")
            delay(3000)
            onProgress("")
        }
    }

    suspend fun importZip(file: File, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            onProgress("Reading dictionary...")
            val result = importYomitanZip(file)
            if (result != null) {
                onProgress("Successfully imported $result!")
                loadDictionaries()
            } else {
                onProgress("Import failed.")
            }
            delay(1500)
            onProgress("")
        } catch (e: Exception) {
            CrashReporter.recordHandled("DictionaryEngine.importZip", e)
            onProgress("Import failed: ${e.message}")
            delay(2500)
            onProgress("")
        }
    }

    fun getInstalledDictionaries(): List<String> = sortedDictionaryFolders().map { it.name }

    fun deleteDict(name: String) {
        File(dictsDir, name).deleteRecursively()
        prefs.remove(dictionaryBlockCollapseKey(name))
        prefs.remove(dictionaryNestedCollapseKey(name))
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
            CrashReporter.recordHandled("DictionaryEngine.searchPrefixes", e)
            emptyList()
        }
    }

    private fun searchWithNativeLookup(cleanQuery: String): List<DictEntry> {
        val candidates = (cleanQuery.length.coerceAtMost(25) downTo 1)
            .map { cleanQuery.take(it) }
            .firstNotNullOfOrNull { prefix ->
                val terms = lookupExactCandidate(prefix)
                if (terms.isNotEmpty()) CandidateLookup(prefix, prefix, terms) else null
            }
            ?.let(::listOf)
            ?: emptyList()
        return candidates.flatMap { candidate ->
            candidate.terms.flatMap { term ->
                entriesForTerm(candidate.matched, candidate.deinflected, term)
            }
        }.take(80)
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
                        if (candidate.isNotBlank()) candidates += candidate
                    }
                }
            for (candidate in candidates) {
                val terms = lookupExactCandidate(candidate)
                if (terms.isNotEmpty()) {
                    val key = terms.joinToString("|") { "${it.expression}\u0000${it.reading}\u0000${it.dictName}" }
                    matchedTerms.putIfAbsent(key, CandidateLookup(substring, candidate, terms))
                }
                if (matchedTerms.size >= 20) break
            }
            if (matchedTerms.size > previousMatchCount || matchedTerms.size >= 20) break
        }

        return matchedTerms.values.take(20).flatMap { result ->
            result.terms.flatMap { term -> entriesForTerm(result.matched, result.deinflected, term) }
        }
    }

    private fun lookupExactCandidate(candidate: String): List<StoredTerm> {
        val exact = loadedTerms[candidate].orEmpty()
        if (exact.isNotEmpty()) return exact
        val folded = candidate.lowercase(Locale.ROOT)
        return loadedTerms[folded].orEmpty()
    }

    private fun lookupSubstrings(cleanQuery: String, languageCode: String): List<String> {
        val normalized = cleanLookupText(cleanQuery, languageCode)
        if (normalized.isBlank()) return emptyList()
        if (languageCode in WORD_SCANNING_LANGUAGES) return wordSubstrings(normalized)
        if (languageCode !in PREFIX_SCANNING_LANGUAGES) return listOf(normalized)
        val scanLength = normalized.length.coerceAtMost(25)
        return (scanLength downTo 1).map { normalized.take(it).trim() }.filter { it.isNotBlank() }
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
        return tokenEnds.asReversed()
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

    private fun entriesForTerm(matched: String, deinflected: String, term: StoredTerm): List<DictEntry> {
        val pitches = pitchByExpression[term.expression].orEmpty() + pitchByExpression["${term.expression}\u0000${term.reading}"].orEmpty()
        return splitGlossaries(term.glossary).map { glossary ->
            DictEntry(
                term = matched,
                deinflected = deinflected,
                reading = term.reading,
                definition = glossary,
                dictName = term.dictName,
                definitionTags = term.definitionTags,
                termTags = term.termTags,
                pitchPositions = pitches.distinct()
            )
        }
    }

    private fun importYomitanZip(zipFile: File): String? {
        ZipFile(zipFile).use { zip ->
            val indexEntry = zip.getEntry("index.json") ?: return null
            val indexJson = JSONObject(zip.getInputStream(indexEntry).bufferedReader().use { it.readText() })
            val title = cleanMetadataValue(indexJson.optString("title")) ?: zipFile.nameWithoutExtension
            val targetName = sanitizeDictionaryName(title)
            val targetDir = File(dictsDir, targetName)
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()
            File(targetDir, "index.json").writeText(indexJson.toString(2), Charsets.UTF_8)

            val termsFile = File(targetDir, "terms.jsonl")
            termsFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                zip.entries().asSequence()
                    .filter { it.name.matches(Regex("""term_bank_\d+\.json""")) }
                    .sortedBy { it.name }
                    .forEach { entry ->
                        val arr = JSONArray(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                        for (i in 0 until arr.length()) {
                            val term = parseYomitanTerm(arr.getJSONArray(i), targetName)
                            writer.append(termToJson(term).toString()).append('\n')
                        }
                    }
            }

            val pitchFile = File(targetDir, "pitch.jsonl")
            pitchFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                zip.entries().asSequence()
                    .filter { it.name.matches(Regex("""term_meta_bank_\d+\.json""")) }
                    .sortedBy { it.name }
                    .forEach { entry ->
                        val arr = JSONArray(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                        for (i in 0 until arr.length()) {
                            parsePitchMeta(arr.getJSONArray(i), targetName).forEach { pitch ->
                                writer.append(pitch.toString()).append('\n')
                            }
                        }
                    }
            }
            if (pitchFile.length() == 0L) pitchFile.delete()
            return targetName
        }
    }

    private fun parseYomitanTerm(arr: JSONArray, dictName: String): StoredTerm {
        val expression = arr.optString(0)
        val reading = arr.optString(1)
        val definitionTags = arr.optString(2)
        val rules = arr.optString(3)
        val glossaryNode = arr.opt(5)
        val termTags = arr.optString(7)
        return StoredTerm(
            expression = expression,
            reading = reading,
            definitionTags = definitionTags,
            rules = rules,
            glossary = glossaryToStorageString(glossaryNode),
            termTags = termTags,
            dictName = dictName
        )
    }

    private fun parsePitchMeta(arr: JSONArray, dictName: String): List<JSONObject> {
        val expression = arr.optString(0)
        val mode = arr.optString(1)
        if (mode != "pitch" && mode != "ipa") return emptyList()
        val data = arr.opt(2)
        val results = mutableListOf<JSONObject>()
        fun appendPitch(reading: String, position: Int) {
            results += JSONObject()
                .put("expression", expression)
                .put("reading", reading)
                .put("dictName", dictName)
                .put("position", position)
        }
        when (data) {
            is JSONObject -> {
                val reading = data.optString("reading")
                val pitches = data.optJSONArray("pitches") ?: JSONArray()
                for (i in 0 until pitches.length()) {
                    appendPitch(reading, pitches.optJSONObject(i)?.optInt("position", -1) ?: pitches.optInt(i, -1))
                }
            }
            is JSONArray -> {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val reading = item.optString("reading")
                    val pitches = item.optJSONArray("pitches") ?: JSONArray()
                    for (j in 0 until pitches.length()) {
                        appendPitch(reading, pitches.optJSONObject(j)?.optInt("position", -1) ?: pitches.optInt(j, -1))
                    }
                }
            }
        }
        return results.filter { it.optInt("position", -1) >= 0 }
    }

    private fun loadDictionaryFolder(folder: File) {
        val termsFile = File(folder, "terms.jsonl")
        if (termsFile.isFile) {
            termsFile.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank()) return@forEachLine
                val term = jsonToTerm(JSONObject(line))
                loadedTerms.getOrPut(term.expression) { mutableListOf() } += term
                val folded = term.expression.lowercase(Locale.ROOT)
                if (folded != term.expression) loadedTerms.getOrPut(folded) { mutableListOf() } += term
            }
        }

        val pitchFile = File(folder, "pitch.jsonl")
        if (pitchFile.isFile) {
            pitchFile.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank()) return@forEachLine
                val obj = JSONObject(line)
                val expression = obj.optString("expression")
                val reading = obj.optString("reading")
                val pitch = PitchInfo(obj.optString("dictName", folder.name), obj.optInt("position", -1))
                if (pitch.position >= 0) {
                    pitchByExpression.getOrPut(expression) { mutableListOf() } += pitch
                    if (reading.isNotBlank()) pitchByExpression.getOrPut("$expression\u0000$reading") { mutableListOf() } += pitch
                }
            }
        }
    }

    private fun termToJson(term: StoredTerm): JSONObject = JSONObject()
        .put("expression", term.expression)
        .put("reading", term.reading)
        .put("definitionTags", term.definitionTags)
        .put("rules", term.rules)
        .put("glossary", term.glossary)
        .put("termTags", term.termTags)
        .put("dictName", term.dictName)

    private fun jsonToTerm(obj: JSONObject): StoredTerm = StoredTerm(
        expression = obj.optString("expression"),
        reading = obj.optString("reading"),
        definitionTags = obj.optString("definitionTags"),
        rules = obj.optString("rules"),
        glossary = obj.optString("glossary"),
        termTags = obj.optString("termTags"),
        dictName = obj.optString("dictName")
    )

    private fun glossaryToStorageString(node: Any?): String {
        return when (node) {
            is JSONArray -> {
                val values = buildList {
                    for (i in 0 until node.length()) add(glossaryToStorageString(node.opt(i)))
                }.filter { it.isNotBlank() }
                if (values.size == 1) values.first() else JSONArray(values).toString()
            }
            is JSONObject -> node.toString()
            null -> ""
            else -> node.toString()
        }
    }

    private fun splitGlossaries(glossary: String): List<String> {
        if (glossary.isBlank()) return emptyList()
        return if (glossary.trim().startsWith("[")) {
            runCatching {
                val arr = JSONArray(glossary)
                List(arr.length()) { arr.opt(it).toString() }.filter { it.isNotBlank() }
            }.getOrDefault(listOf(glossary))
        } else {
            listOf(glossary)
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

    private fun sanitizeDictionaryName(raw: String): String {
        val base = raw.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "Dictionary_${UUID.randomUUID()}" }
        var candidate = base
        var suffix = 2
        while (File(dictsDir, candidate).exists()) {
            candidate = "$base $suffix"
            suffix++
        }
        return candidate
    }

    private fun dictionaryBlockCollapseKey(dictName: String): String = "dict_block_collapse_$dictName"
    private fun dictionaryNestedCollapseKey(dictName: String): String = "dict_nested_collapse_$dictName"
}
