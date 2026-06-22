package com.selxo.rougo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlCacheTest {
    @Test
    fun cachedStreamUrlsNeedSafetyMarginBeforeExpiry() {
        val now = 1_000L

        assertTrue(
            isValidCachedStreamUrl(
                CachedStreamUrl("https://stream.example/video", now + STREAM_URL_CACHE_SAFETY_MARGIN_MS + 1L),
                now
            )
        )
        assertFalse(
            isValidCachedStreamUrl(
                CachedStreamUrl("https://stream.example/video", now + STREAM_URL_CACHE_SAFETY_MARGIN_MS),
                now
            )
        )
    }

    @Test
    fun blankStreamUrlsAreNeverValidCacheEntries() {
        assertFalse(
            isValidCachedStreamUrl(
                CachedStreamUrl("  ", Long.MAX_VALUE),
                nowMs = 1_000L
            )
        )
    }

    @Test
    fun cacheExpiryIsShortLivedByProvider() {
        val now = 10_000L

        assertEquals(45L * 60L * 1000L, streamUrlCacheExpiryMs(StreamProvider.YouTube, now) - now)
        assertEquals(20L * 60L * 1000L, streamUrlCacheExpiryMs(StreamProvider.Bilibili, now) - now)
        assertEquals(20L * 60L * 1000L, streamUrlCacheExpiryMs(StreamProvider.Niconico, now) - now)
    }
}
