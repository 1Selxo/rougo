package com.selxo.rougo

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleCacheTest {
    @Test
    fun subtitleCacheKeyChangesWhenFileMetadataChanges() {
        val first = subtitleParseCacheKey("file:///subs/test.vtt", length = 100L, lastModified = 1_000L)
        val same = subtitleParseCacheKey("file:///subs/test.vtt", length = 100L, lastModified = 1_000L)
        val changedLength = subtitleParseCacheKey("file:///subs/test.vtt", length = 101L, lastModified = 1_000L)
        val changedModified = subtitleParseCacheKey("file:///subs/test.vtt", length = 100L, lastModified = 1_001L)

        assertEquals(first, same)
        assertNotEquals(first, changedLength)
        assertNotEquals(first, changedModified)
    }
}
