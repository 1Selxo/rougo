package com.selxo.rougo

import java.net.URI

internal data class PlaybackStorageDecision(
    val item: LibraryItem,
    val blockedRecordingMediaUri: Boolean
)

internal fun decidePlaybackStorageItem(
    item: LibraryItem,
    progress: Long,
    duration: Long,
    recordings: List<ShadowRecording>,
    actualMediaUri: String?,
    hasDownloadedLocalCopy: Boolean = item.hasDownloadedLocalCopy()
): PlaybackStorageDecision {
    val mediaUriCandidate = persistableMediaUriForPlaybackStorage(
        sourceUrl = item.sourceUrl,
        currentMediaUri = item.mediaUri,
        actualMediaUri = actualMediaUri,
        hasDownloadedLocalCopy = hasDownloadedLocalCopy,
        recordingFilePaths = recordings.map { it.filePath }
    )
    val blockedRecordingMediaUri = mediaUriCandidate != item.persistableMediaUri(actualMediaUri)
    return PlaybackStorageDecision(
        item = item.copy(
            progress = progress,
            duration = duration,
            recordings = recordings.toList(),
            mediaUri = mediaUriCandidate
        ),
        blockedRecordingMediaUri = blockedRecordingMediaUri
    )
}

internal fun persistableMediaUriForPlaybackStorage(
    sourceUrl: String?,
    currentMediaUri: String,
    actualMediaUri: String?,
    hasDownloadedLocalCopy: Boolean,
    recordingFilePaths: List<String>
): String {
    val candidate = persistableMediaUriForStorage(
        sourceUrl = sourceUrl,
        currentMediaUri = currentMediaUri,
        actualMediaUri = actualMediaUri,
        hasDownloadedLocalCopy = hasDownloadedLocalCopy
    )
    return if (recordingFilePaths.any { mediaUriReferencesFile(candidate, it) }) {
        currentMediaUri
    } else {
        candidate
    }
}

internal fun mediaUriReferencesFile(mediaUri: String, filePath: String): Boolean {
    val normalizedMedia = normalizedFileReference(mediaUri)
    val normalizedFile = normalizedFileReference(filePath)
    return normalizedMedia.isNotBlank() && normalizedMedia == normalizedFile
}

private fun normalizedFileReference(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    return if (trimmed.startsWith("file:", ignoreCase = true)) {
        runCatching { URI(trimmed).path }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: trimmed.removePrefix("file://")
    } else {
        trimmed
    }
}
