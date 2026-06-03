package de.manhhao.hoshi

object RougoHoshi {
    init {
        System.loadLibrary("hoshidicts_jni")
    }

    external fun probeEntryTypes(dictPath: String): LongArray

    fun hasMetaModeEntries(storagePath: String, mode: String, minCount: Int): Boolean {
        if (minCount <= 0) return true
        val counts = runCatching { probeEntryTypes(storagePath) }.getOrNull() ?: return false
        val count = when (mode.lowercase()) {
            "freq" -> counts.getOrElse(1) { 0L }
            "pitch" -> counts.getOrElse(2) { 0L }
            else -> 0L
        }
        return count >= minCount
    }

    fun isFrequencyDictionary(storagePath: String, minFreqEntryCount: Int = 5): Boolean {
        return hasMetaModeEntries(storagePath, "freq", minFreqEntryCount)
    }
}
