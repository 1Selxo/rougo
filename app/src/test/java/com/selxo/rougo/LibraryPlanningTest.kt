package com.selxo.rougo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryPlanningTest {
    @Test
    fun playlistImportPlanCreatesFolderAndChildren() {
        var next = 0
        val plan = buildPlaylistImportPlan(
            playlistTitle = "Study playlist",
            playlistUrl = "https://www.youtube.com/playlist?list=abc",
            entries = listOf(
                PlaylistImportEntry("Lesson 1", "https://www.youtube.com/watch?v=one"),
                PlaylistImportEntry("Lesson 2", "https://www.youtube.com/watch?v=two")
            ),
            nextId = { "id-${next++}" }
        )

        assertEquals(LibraryItemKind.Playlist, plan.group.itemKind)
        assertEquals("id-0", plan.group.id)
        assertEquals(listOf("id-1", "id-2"), plan.children.map { it.id })
        assertTrue(plan.children.all { it.parentId == plan.group.id })
        assertEquals(listOf(0, 1), plan.children.map { it.playlistItemIndex })
    }

    @Test
    fun libraryRowsKeepPlaylistChildrenUnderFolder() {
        val plan = buildPlaylistImportPlan(
            playlistTitle = "Playlist",
            playlistUrl = "https://www.youtube.com/playlist?list=abc",
            entries = listOf(
                PlaylistImportEntry("B video", "https://www.youtube.com/watch?v=b"),
                PlaylistImportEntry("A video", "https://www.youtube.com/watch?v=a")
            ),
            nextId = generateSequence(1) { it + 1 }.map { "id-$it" }.iterator()::next
        )
        val single = LibraryItem(
            id = "single",
            title = "Single",
            mediaUri = "file:///single.mp4",
            subtitleUri = null,
            progress = 0L,
            duration = 0L,
            isVideo = true
        )

        val rows = libraryDisplayRows(listOf(single, plan.group) + plan.children, "", "All", "Recent")

        assertEquals(
            listOf("single", plan.group.id, plan.children[0].id, plan.children[1].id),
            rows.map {
                when (it) {
                    is LibraryDisplayRow.PlaylistGroup -> it.item.id
                    is LibraryDisplayRow.Media -> it.item.id
                }
            }
        )
    }

    @Test
    fun streamedSourceUriIsPersistedInsteadOfResolvedTemporaryUrl() {
        val persisted = persistableMediaUriForStorage(
            sourceUrl = "https://www.youtube.com/watch?v=source",
            currentMediaUri = "https://www.youtube.com/watch?v=source",
            actualMediaUri = "https://rr1---sn.example/videoplayback?expire=123",
            hasDownloadedLocalCopy = false
        )

        assertEquals("https://www.youtube.com/watch?v=source", persisted)
    }

    @Test
    fun downloadedLocalCopyKeepsLocalUriWhenRecordingSaves() {
        val persisted = persistableMediaUriForStorage(
            sourceUrl = "https://www.youtube.com/watch?v=source",
            currentMediaUri = "file:///local.mp4",
            actualMediaUri = "file:///local.mp4",
            hasDownloadedLocalCopy = true
        )

        assertEquals("file:///local.mp4", persisted)
    }
}
