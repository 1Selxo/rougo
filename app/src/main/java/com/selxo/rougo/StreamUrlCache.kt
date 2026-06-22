package com.selxo.rougo

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

internal const val STREAM_URL_CACHE_SAFETY_MARGIN_MS = 2L * 60L * 1000L
private const val STREAM_URL_CACHE_PREFS = "rougo_stream_url_cache"
private const val STREAM_URL_CACHE_YOUTUBE_TTL_MS = 45L * 60L * 1000L
private const val STREAM_URL_CACHE_OTHER_TTL_MS = 20L * 60L * 1000L

internal enum class StreamProvider {
    YouTube,
    Bilibili,
    Niconico,
    Unknown
}

internal data class CachedStreamUrl(
    val streamUrl: String,
    val expiresAtMs: Long
)

internal fun detectStreamProvider(sourceUrl: String): StreamProvider {
    return when {
        isYoutubeUrl(sourceUrl) -> StreamProvider.YouTube
        isBilibiliUrl(sourceUrl) -> StreamProvider.Bilibili
        isNiconicoUrl(sourceUrl) -> StreamProvider.Niconico
        else -> StreamProvider.Unknown
    }
}

internal fun isValidCachedStreamUrl(entry: CachedStreamUrl, nowMs: Long): Boolean {
    return entry.streamUrl.isNotBlank() &&
        entry.expiresAtMs - nowMs > STREAM_URL_CACHE_SAFETY_MARGIN_MS
}

internal fun streamUrlCacheExpiryMs(provider: StreamProvider, nowMs: Long): Long {
    val ttl = when (provider) {
        StreamProvider.YouTube -> STREAM_URL_CACHE_YOUTUBE_TTL_MS
        StreamProvider.Bilibili, StreamProvider.Niconico -> STREAM_URL_CACHE_OTHER_TTL_MS
        StreamProvider.Unknown -> STREAM_URL_CACHE_OTHER_TTL_MS
    }
    return nowMs + ttl
}

internal object StreamUrlCache {
    fun get(context: Context, sourceUrl: String, formatId: String?, nowMs: Long = System.currentTimeMillis()): String? {
        val key = cacheKey(sourceUrl, formatId)
        val entry = readEntry(context, key)
        if (entry == null) {
            remove(context, key)
            return null
        }
        if (!isValidCachedStreamUrl(entry, nowMs)) {
            remove(context, key)
            return null
        }
        return entry.streamUrl
    }

    fun put(
        context: Context,
        sourceUrl: String,
        formatId: String?,
        streamUrl: String,
        provider: StreamProvider = detectStreamProvider(sourceUrl),
        nowMs: Long = System.currentTimeMillis()
    ) {
        val cleanedUrl = streamUrl.trim().takeIf { it.isNotBlank() } ?: return
        val entry = CachedStreamUrl(
            streamUrl = cleanedUrl,
            expiresAtMs = streamUrlCacheExpiryMs(provider, nowMs)
        )
        prefs(context).edit {
            putString(cacheKey(sourceUrl, formatId), entry.toJson().toString())
        }
    }

    fun invalidate(context: Context, sourceUrl: String, formatId: String?) {
        remove(context, cacheKey(sourceUrl, formatId))
    }

    private fun readEntry(context: Context, key: String): CachedStreamUrl? {
        val raw = prefs(context).getString(key, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            CachedStreamUrl(
                streamUrl = json.optString("streamUrl"),
                expiresAtMs = json.optLong("expiresAtMs", 0L)
            )
        }.getOrNull()
    }

    private fun remove(context: Context, key: String) {
        prefs(context).edit { remove(key) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(STREAM_URL_CACHE_PREFS, Context.MODE_PRIVATE)

    private fun cacheKey(sourceUrl: String, formatId: String?): String {
        return "${sourceUrl.trim()}\u001f${formatId.orEmpty().trim()}"
    }

    private fun CachedStreamUrl.toJson(): JSONObject {
        return JSONObject()
            .put("streamUrl", streamUrl)
            .put("expiresAtMs", expiresAtMs)
    }
}
