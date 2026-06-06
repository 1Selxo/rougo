package com.selxo.rougo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPersistenceTest {
    @Test
    fun streamSourcePersistsSourceUrlAfterRecording() {
        val sourceUrl = "https://www.youtube.com/watch?v=source"
        val recording = ShadowRecording(filePath = "/recordings/voice.m4a", startTime = 1_000L, endTime = 3_000L)
        val item = mediaItem(mediaUri = sourceUrl, sourceUrl = sourceUrl)

        val decision = decidePlaybackStorageItem(
            item = item,
            progress = 2_000L,
            duration = 10_000L,
            recordings = listOf(recording),
            actualMediaUri = "https://rr1---sn.example/videoplayback?expire=123",
            hasDownloadedLocalCopy = false
        )

        assertEquals(sourceUrl, decision.item.mediaUri)
        assertEquals(listOf(recording), decision.item.recordings)
        assertFalse(decision.blockedRecordingMediaUri)
    }

    @Test
    fun recordingPathCannotReplaceLocalSourceClip() {
        val recording = ShadowRecording(filePath = "/recordings/voice.m4a", startTime = 1_000L, endTime = 3_000L)
        val item = mediaItem(mediaUri = "file:///movies/source.mp4", sourceUrl = null)

        val decision = decidePlaybackStorageItem(
            item = item,
            progress = 2_000L,
            duration = 10_000L,
            recordings = listOf(recording),
            actualMediaUri = "file:///recordings/voice.m4a",
            hasDownloadedLocalCopy = false
        )

        assertEquals("file:///movies/source.mp4", decision.item.mediaUri)
        assertTrue(decision.blockedRecordingMediaUri)
    }

    @Test
    fun downloadedLocalCopyRemainsTheMediaUriAfterRecording() {
        val recording = ShadowRecording(filePath = "/recordings/voice.m4a", startTime = 1_000L, endTime = 3_000L)
        val item = mediaItem(
            mediaUri = "file:///downloads/source.mp4",
            sourceUrl = "https://www.youtube.com/watch?v=source"
        )

        val decision = decidePlaybackStorageItem(
            item = item,
            progress = 2_000L,
            duration = 10_000L,
            recordings = listOf(recording),
            actualMediaUri = "file:///downloads/source.mp4",
            hasDownloadedLocalCopy = true
        )

        assertEquals("file:///downloads/source.mp4", decision.item.mediaUri)
        assertFalse(decision.blockedRecordingMediaUri)
    }

    @Test
    fun recordingPathCannotReplaceDownloadedLocalCopy() {
        val recording = ShadowRecording(filePath = "/recordings/voice.m4a", startTime = 1_000L, endTime = 3_000L)
        val item = mediaItem(
            mediaUri = "file:///downloads/source.mp4",
            sourceUrl = "https://www.youtube.com/watch?v=source"
        )

        val decision = decidePlaybackStorageItem(
            item = item,
            progress = 2_000L,
            duration = 10_000L,
            recordings = listOf(recording),
            actualMediaUri = "/recordings/voice.m4a",
            hasDownloadedLocalCopy = true
        )

        assertEquals("file:///downloads/source.mp4", decision.item.mediaUri)
        assertTrue(decision.blockedRecordingMediaUri)
    }

    private fun mediaItem(mediaUri: String, sourceUrl: String?): LibraryItem {
        return LibraryItem(
            id = "item-1",
            title = "Source",
            mediaUri = mediaUri,
            subtitleUri = null,
            progress = 0L,
            duration = 0L,
            isVideo = true,
            sourceUrl = sourceUrl
        )
    }
}
