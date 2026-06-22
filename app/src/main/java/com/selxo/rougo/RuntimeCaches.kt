package com.selxo.rougo

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.ImageBitmap

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

object SubtitleParseCache {
    private const val MAX_ITEMS = 24
    private val cache = object : LinkedHashMap<String, List<SubtitleCue>>(MAX_ITEMS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SubtitleCue>>?): Boolean {
            return size > MAX_ITEMS
        }
    }

    @Synchronized
    fun get(key: String): List<SubtitleCue>? = cache[key]

    @Synchronized
    fun put(key: String, value: List<SubtitleCue>) {
        cache[key] = value
    }
}
