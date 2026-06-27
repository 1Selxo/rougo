package com.selxo.rougo.windows

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

data class ShadowRecording(
    val id: String = UUID.randomUUID().toString(),
    val filePath: String,
    val startTime: Long,
    val endTime: Long,
    val timestamp: Long = System.currentTimeMillis()
)

enum class LibraryItemKind { Media, Folder, Playlist }

data class LibraryItem(
    val id: String,
    val title: String,
    val mediaUri: String,
    val subtitleUri: String?,
    var progress: Long,
    var duration: Long,
    val isVideo: Boolean,
    var recordings: List<ShadowRecording> = emptyList(),
    val sourceUrl: String? = null,
    val formatId: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val coverArtPath: String? = null,
    val httpUserAgent: String? = null,
    val httpReferer: String? = null,
    val itemKind: LibraryItemKind = LibraryItemKind.Media,
    val parentId: String? = null,
    val playlistSourceUrl: String? = null,
    val playlistItemIndex: Int = 0
)

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

data class MediaMetadataSnapshot(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val durationMs: Long? = null,
    val coverArtPath: String? = null
)

data class PlaylistImportEntry(
    val title: String,
    val sourceUrl: String,
    val formatId: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
    val index: Int = 0,
    val isVideo: Boolean = true
)

data class PlaylistImportPlan(
    val group: LibraryItem,
    val children: List<LibraryItem>
)

sealed interface LibraryDisplayRow {
    data class PlaylistGroup(
        val item: LibraryItem,
        val childCount: Int,
        val isExpanded: Boolean
    ) : LibraryDisplayRow

    data class Media(
        val item: LibraryItem,
        val isPlaylistChild: Boolean
    ) : LibraryDisplayRow
}

data class YoutubeResolutionOption(val key: String, val label: String)
data class AccentOption(val key: String, val label: String, val darkColor: Color, val lightColor: Color)

enum class LibraryDownloadState { Idle, Loading, Complete }
enum class AppScreen { Library, Player, Settings, DictionarySettings, YoutubeBrowser }
enum class DesktopPlayerCommand { TogglePlayPause, SeekBackward, SeekForward, Stop, SeekStart, SeekEnd }

data class DesktopPlayerCommandEvent(
    val id: Int,
    val command: DesktopPlayerCommand
)

data class PlaybackStorageDecision(
    val item: LibraryItem,
    val blockedRecordingMediaUri: Boolean
)

const val PREF_YOUTUBE_RESOLUTION = "youtube_preferred_resolution"
const val PREF_YOUTUBE_AUTO_SUBTITLES = "youtube_auto_subtitles"
const val PREF_YOUTUBE_SUBTITLE_LANGUAGE = "youtube_subtitle_language"
const val PREF_SKIP_SECONDS = "player_skip_seconds"
const val PREF_SUBTITLE_OFFSET_MS = "subtitle_offset_ms"
const val PREF_SAVE_REPEAT_RECORDINGS = "save_repeat_recordings"
const val PREF_LIGHT_MODE = "app_light_mode"
const val PREF_THEME_MODE = "app_theme_mode"
const val PREF_ACCENT_COLOR = "app_accent_color"
const val DEFAULT_SKIP_SECONDS = 5
const val DEFAULT_SUBTITLE_OFFSET_MS = 0L
const val THEME_DARK = "dark"
const val THEME_BLACK = "black"
const val THEME_LIGHT = "light"
const val THEME_SYSTEM = "system"
const val YOUTUBE_RESOLUTION_ASK = "ask"
const val YOUTUBE_RESOLUTION_HIGHEST = "highest"
const val YOUTUBE_RESOLUTION_AUDIO = "audio"
const val DEFAULT_YOUTUBE_RESOLUTION = "720"
const val DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE = "ja"
const val APP_VERSION = "V2.7.7"

val YOUTUBE_RESOLUTION_OPTIONS = listOf(
    YoutubeResolutionOption("720", "720p"),
    YoutubeResolutionOption(YOUTUBE_RESOLUTION_ASK, "Ask every time"),
    YoutubeResolutionOption("480", "480p"),
    YoutubeResolutionOption("1080", "1080p"),
    YoutubeResolutionOption(YOUTUBE_RESOLUTION_HIGHEST, "Highest available"),
    YoutubeResolutionOption(YOUTUBE_RESOLUTION_AUDIO, "Audio only")
)

val YOUTUBE_SUBTITLE_LANGUAGE_OPTIONS = listOf(
    YoutubeResolutionOption("ja", "Japanese"),
    YoutubeResolutionOption("en", "English"),
    YoutubeResolutionOption("zh-Hant", "Chinese (Traditional)"),
    YoutubeResolutionOption("zh-Hans", "Chinese (Simplified)"),
    YoutubeResolutionOption("ko", "Korean"),
    YoutubeResolutionOption("es", "Spanish"),
    YoutubeResolutionOption("any", "Best available")
)

val THEME_MODE_OPTIONS = listOf(
    YoutubeResolutionOption(THEME_DARK, "Dark"),
    YoutubeResolutionOption(THEME_BLACK, "Black (OLED)"),
    YoutubeResolutionOption(THEME_LIGHT, "Light"),
    YoutubeResolutionOption(THEME_SYSTEM, "System")
)

val ACCENT_OPTIONS = listOf(
    AccentOption("purple", "Purple", Color(0xFFAEB2FF), Color(0xFF585DDB)),
    AccentOption("red", "Red", Color(0xFFFF8A80), Color(0xFFC62828)),
    AccentOption("pink", "Pink", Color(0xFFFF8FD8), Color(0xFFAD1457)),
    AccentOption("orange", "Orange", Color(0xFFFFB86B), Color(0xFFBF5F00)),
    AccentOption("yellow", "Yellow", Color(0xFFFFD75E), Color(0xFF8A6D00)),
    AccentOption("green", "Green", Color(0xFF7EE787), Color(0xFF1B7F38)),
    AccentOption("teal", "Teal", Color(0xFF64D8CB), Color(0xFF00796B)),
    AccentOption("blue", "Blue", Color(0xFF8AB4FF), Color(0xFF1565C0)),
    AccentOption("indigo", "Indigo", Color(0xFF9FA8FF), Color(0xFF3949AB)),
    AccentOption("gray", "Gray", Color(0xFFCBD5E1), Color(0xFF475569))
)

private val RougoDarkColorScheme = darkColorScheme(
    background = Color(0xFF141419),
    surface = Color(0xFF222228),
    surfaceVariant = Color(0xFF2D2D36),
    primary = Color(0xFFAEB2FF),
    secondary = Color(0xFF73D6C9),
    tertiary = Color(0xFFFFB08A),
    onBackground = Color(0xFFF7F7FB),
    onSurface = Color(0xFFF7F7FB),
    onSurfaceVariant = Color(0xFFC7C7D1),
    onPrimary = Color(0xFF141419)
)

private val RougoLightColorScheme = lightColorScheme(
    background = Color(0xFFF7F7FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EAF4),
    primary = Color(0xFF585DDB),
    secondary = Color(0xFF067A71),
    tertiary = Color(0xFFB85F2E),
    onBackground = Color(0xFF171720),
    onSurface = Color(0xFF171720),
    onSurfaceVariant = Color(0xFF5D6070),
    onPrimary = Color.White
)

private val RougoBlackColorScheme = darkColorScheme(
    background = Color.Black,
    surface = Color(0xFF111116),
    surfaceVariant = Color(0xFF1C1C22),
    primary = Color(0xFFAEB2FF),
    secondary = Color(0xFF73D6C9),
    tertiary = Color(0xFFFFB08A),
    onBackground = Color(0xFFF7F7FB),
    onSurface = Color(0xFFF7F7FB),
    onSurfaceVariant = Color(0xFFC7C7D1),
    onPrimary = Color.Black
)

fun rougoColorScheme(themeMode: String, accentKey: String, systemDark: Boolean): ColorScheme {
    val usesDarkSurfaces = when (themeMode) {
        THEME_LIGHT -> false
        THEME_SYSTEM -> systemDark
        else -> true
    }
    val base = when (themeMode) {
        THEME_BLACK -> RougoBlackColorScheme
        THEME_LIGHT -> RougoLightColorScheme
        THEME_SYSTEM -> if (systemDark) RougoDarkColorScheme else RougoLightColorScheme
        else -> RougoDarkColorScheme
    }
    val accent = ACCENT_OPTIONS.firstOrNull { it.key == accentKey } ?: ACCENT_OPTIONS.first()
    val primary = if (usesDarkSurfaces) accent.darkColor else accent.lightColor
    return base.copy(
        primary = primary,
        onPrimary = if (usesDarkSurfaces) Color(0xFF111116) else Color.White
    )
}

fun JSONObject.optCleanString(key: String): String? = cleanMetadataValue(optString(key, ""))

fun cleanMetadataValue(value: String?): String? {
    val cleaned = value
        ?.replace('\u0000', ' ')
        ?.replace('\u00A0', ' ')
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()

    return cleaned.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
}

fun firstCleanMetadataValue(vararg values: String?): String? {
    return values.firstNotNullOfOrNull { cleanMetadataValue(it) }
}

fun LibraryItem.metadataSummary(): String? {
    return listOfNotNull(
        firstCleanMetadataValue(artist, albumArtist),
        album,
        year
    ).distinct().joinToString(" / ").takeIf { it.isNotBlank() }
}

fun LibraryItem.isPlaylistGroup(): Boolean = itemKind == LibraryItemKind.Playlist

fun LibraryItem.isFolderGroup(): Boolean =
    itemKind == LibraryItemKind.Folder || itemKind == LibraryItemKind.Playlist

fun LibraryItem.needsLocalMetadataRefresh(): Boolean {
    if (itemKind != LibraryItemKind.Media) return false
    if (sourceUrl != null && !hasDownloadedLocalCopy()) return false
    val hasCover = coverArtPath?.let { File(it).exists() && File(it).length() > 0L } == true
    return !hasCover || metadataSummary() == null || duration <= 0L
}

fun isLocalMediaUriValue(value: String): Boolean {
    val scheme = runCatching { URI(value).scheme?.lowercase(Locale.US) }.getOrNull()
    return scheme.isNullOrBlank() || scheme == "file"
}

fun LibraryItem.hasDownloadedLocalCopy(): Boolean {
    if (itemKind != LibraryItemKind.Media) return false
    val media = mediaUri.trim()
    val source = sourceUrl?.trim().orEmpty()
    return source.isNotBlank() && media.isNotBlank() && media != source && isLocalMediaUriValue(media)
}

private val youtubeVideoOnlyFormatIds = setOf(
    "133", "134", "135", "136", "137", "138", "160", "167", "168", "169",
    "170", "212", "218", "219", "242", "243", "244", "245", "246", "247",
    "248", "264", "266", "271", "272", "278", "298", "299", "302", "303",
    "308", "313", "315", "330", "331", "332", "333", "334", "335", "336",
    "337", "394", "395", "396", "397", "398", "399", "400", "401", "402",
    "571", "694", "695", "696", "697", "698", "699", "700", "701", "702"
)

fun isKnownYoutubeVideoOnlyFormatId(formatId: String?): Boolean {
    val value = formatId?.trim().orEmpty()
    if (value.isBlank() || "+" in value || "," in value || "/" in value) return false
    return value in youtubeVideoOnlyFormatIds
}

fun LibraryItem.hasLegacyYoutubeVideoOnlyFormat(): Boolean =
    sourceUrl?.let { isYoutubeUrl(it) } == true && isKnownYoutubeVideoOnlyFormatId(formatId)

fun LibraryItem.withoutLegacyYoutubeVideoOnlyFormat(): LibraryItem =
    if (hasLegacyYoutubeVideoOnlyFormat()) copy(mediaUri = sourceUrl ?: mediaUri, formatId = null) else this

fun decidePlaybackStorageItem(
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
    val regularPersisted = persistableMediaUriForStorage(
        sourceUrl = item.sourceUrl,
        currentMediaUri = item.mediaUri,
        actualMediaUri = actualMediaUri,
        hasDownloadedLocalCopy = hasDownloadedLocalCopy
    )
    return PlaybackStorageDecision(
        item = item.copy(
            progress = progress,
            duration = duration,
            recordings = recordings.toList(),
            mediaUri = mediaUriCandidate
        ),
        blockedRecordingMediaUri = mediaUriCandidate != regularPersisted
    )
}

fun persistableMediaUriForPlaybackStorage(
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
    return if (recordingFilePaths.any { mediaUriReferencesFile(candidate, it) }) currentMediaUri else candidate
}

fun persistableMediaUriForStorage(
    sourceUrl: String?,
    currentMediaUri: String,
    actualMediaUri: String?,
    hasDownloadedLocalCopy: Boolean
): String {
    if (sourceUrl == null || hasDownloadedLocalCopy) return actualMediaUri ?: currentMediaUri
    return sourceUrl
}

fun mediaUriReferencesFile(mediaUri: String, filePath: String): Boolean {
    val normalizedMedia = normalizedFileReference(mediaUri)
    val normalizedFile = normalizedFileReference(filePath)
    return normalizedMedia.isNotBlank() && normalizedMedia == normalizedFile
}

private fun normalizedFileReference(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    val raw = if (trimmed.startsWith("file:", ignoreCase = true)) {
        runCatching { URI(trimmed).path }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: trimmed.removePrefix("file://")
    } else {
        trimmed
    }
    val slashed = raw.replace('\\', '/')
    return if (slashed.length >= 3 && slashed[0] == '/' && slashed[2] == ':') {
        slashed.drop(1)
    } else {
        slashed
    }
}

fun LibraryItem.displaySourceLabel(): String {
    if (isFolderGroup()) {
        return if (playlistSourceUrl != null || itemKind == LibraryItemKind.Playlist) "Playlist" else "Folder"
    }
    val source = sourceUrl
    return when {
        source != null && hasDownloadedLocalCopy() -> "${streamSourceLabel(source)} (local)"
        source != null -> streamSourceLabel(source)
        isVideo -> "Video"
        else -> "Audio"
    }
}

fun buildLibraryFolder(
    folderTitle: String,
    nextId: () -> String
): LibraryItem = LibraryItem(
    id = nextId(),
    title = folderTitle.trim(),
    mediaUri = "",
    subtitleUri = null,
    progress = 0L,
    duration = 0L,
    isVideo = false,
    itemKind = LibraryItemKind.Folder
)

fun buildPlaylistImportPlan(
    playlistTitle: String,
    playlistUrl: String,
    entries: List<PlaylistImportEntry>,
    nextId: () -> String
): PlaylistImportPlan {
    val groupId = nextId()
    val group = LibraryItem(
        id = groupId,
        title = playlistTitle.trim().ifBlank { "Playlist" },
        mediaUri = "",
        subtitleUri = null,
        progress = 0L,
        duration = 0L,
        isVideo = true,
        sourceUrl = playlistUrl,
        itemKind = LibraryItemKind.Folder,
        playlistSourceUrl = playlistUrl
    )
    val children = entries.mapIndexed { index, entry ->
        LibraryItem(
            id = nextId(),
            title = entry.title.ifBlank { "Video ${index + 1}" },
            mediaUri = entry.sourceUrl,
            subtitleUri = null,
            progress = 0L,
            duration = entry.durationMs,
            isVideo = entry.isVideo,
            sourceUrl = entry.sourceUrl,
            formatId = entry.formatId,
            coverArtPath = entry.thumbnailUrl,
            parentId = groupId,
            playlistSourceUrl = playlistUrl,
            playlistItemIndex = entry.index.takeIf { it > 0 } ?: index
        )
    }
    return PlaylistImportPlan(group, children)
}

fun libraryDisplayRows(
    items: List<LibraryItem>,
    searchQuery: String,
    selectedFilter: String,
    sortMode: String,
    collapsedFolderIds: Set<String>
): List<LibraryDisplayRow> {
    val query = searchQuery.trim()
    val mediaItems = items.filter { it.itemKind == LibraryItemKind.Media }
    val groupItems = items.filter { it.isFolderGroup() }
    val groupedMedia = mediaItems.groupBy { it.parentId }

    val rows = mutableListOf<LibraryDisplayRow>()
    val topLevelItems = buildList {
        addAll(groupItems)
        addAll(groupedMedia[null].orEmpty())
    }

    sortLibraryTopLevelItems(topLevelItems, sortMode).forEach { item ->
        if (item.isFolderGroup()) {
            val children = filterLibraryMediaItems(groupedMedia[item.id].orEmpty(), query, selectedFilter)
            val groupMatches = matchesLibraryQuery(item, query) && matchesLibraryFilter(item, selectedFilter)
            if (groupMatches || children.isNotEmpty()) {
                val expanded = query.isNotBlank() || item.id !in collapsedFolderIds
                rows += LibraryDisplayRow.PlaylistGroup(item, groupedMedia[item.id].orEmpty().size, expanded)
                sortLibraryMediaItems(children, sortMode).forEach { child ->
                    if (expanded) rows += LibraryDisplayRow.Media(child, true)
                }
            }
        } else if (item.itemKind == LibraryItemKind.Media &&
            matchesLibraryQuery(item, query) &&
            matchesLibraryFilter(item, selectedFilter)
        ) {
            rows += LibraryDisplayRow.Media(item, false)
        }
    }
    return rows
}

fun libraryMediaItemCount(items: List<LibraryItem>): Int =
    items.count { it.itemKind == LibraryItemKind.Media }

private fun filterLibraryMediaItems(
    items: List<LibraryItem>,
    query: String,
    selectedFilter: String
): List<LibraryItem> = items.filter { item ->
    matchesLibraryQuery(item, query) && matchesLibraryFilter(item, selectedFilter)
}

private fun matchesLibraryQuery(item: LibraryItem, query: String): Boolean {
    return query.isBlank() || item.title.contains(query, ignoreCase = true) || item.metadataSummary()?.contains(query, ignoreCase = true) == true
}

private fun matchesLibraryFilter(item: LibraryItem, selectedFilter: String): Boolean {
    return when (selectedFilter) {
        "Audio" -> !item.isVideo
        "Video" -> item.isVideo
        "YouTube" -> item.sourceUrl?.let { isYoutubeUrl(it) } == true || item.playlistSourceUrl?.let { isYoutubeUrl(it) } == true
        "Local" -> item.sourceUrl == null || item.hasDownloadedLocalCopy()
        else -> true
    }
}

private fun sortLibraryMediaItems(
    items: List<LibraryItem>,
    sortMode: String
): List<LibraryItem> = when (sortMode) {
    "Title" -> items.sortedBy { it.title.lowercase(Locale.getDefault()) }
    "Progress" -> items.sortedByDescending { if (it.duration > 0L) it.progress.toDouble() / it.duration else 0.0 }
    "Recordings" -> items.sortedByDescending { it.recordings.size }
    else -> items
}

private fun sortLibraryTopLevelItems(items: List<LibraryItem>, sortMode: String): List<LibraryItem> {
    return when (sortMode) {
        "Title" -> items.sortedBy { it.title.lowercase(Locale.getDefault()) }
        "Progress" -> items.sortedByDescending { if (it.duration > 0L) it.progress.toDouble() / it.duration else 0.0 }
        "Recordings" -> items.sortedByDescending { it.recordings.size }
        else -> items
    }
}

fun extractFirstUrl(text: String): String? = extractAllUrls(text).firstOrNull()

fun extractAllUrls(text: String): List<String> {
    val pattern = Regex("""(?i)\bhttps?://[^\s<>"']+""")
    return pattern.findAll(text)
        .map { normalizeVideoUrlCandidate(it.value) }
        .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        .distinct()
        .toList()
}

fun normalizeVideoUrlCandidate(raw: String): String {
    var value = raw.trim()
        .trimEnd('.', ',', ';', ':', ')', ']', '}', '>', '"', '\'')
        .trimStart('<', '"', '\'')

    value = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault(value)
    return value
}

fun isSupportedVideoLink(url: String): Boolean {
    val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
    return host.contains("youtube.com") ||
        host.contains("youtu.be") ||
        host.contains("bilibili.com") ||
        host.contains("b23.tv") ||
        host.contains("nicovideo.jp") ||
        host.contains("nico.ms")
}

fun isYoutubeUrl(url: String): Boolean {
    val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
    return host.contains("youtube.com") || host.contains("youtu.be")
}

fun isYoutubePlaylistUrl(url: String): Boolean {
    if (!isYoutubeUrl(url)) return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val path = uri.path.orEmpty().lowercase(Locale.US)
    val query = uri.rawQuery.orEmpty()
    return query.split('&').any { it.substringBefore('=') == "list" && it.substringAfter('=', "").isNotBlank() } ||
        path.startsWith("/playlist")
}

fun isBilibiliUrl(url: String): Boolean {
    val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
    return host.contains("bilibili.com") || host == "b23.tv"
}

fun isNiconicoUrl(url: String): Boolean {
    val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
    return host.contains("nicovideo.jp") || host == "nico.ms"
}

fun streamSourceLabel(url: String?): String {
    return when {
        url == null -> "Stream"
        isYoutubeUrl(url) -> "YouTube"
        isBilibiliUrl(url) -> "Bilibili"
        isNiconicoUrl(url) -> "Niconico"
        else -> "Stream"
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

fun formatPercent(progress: Long, duration: Long): Int {
    return if (duration > 0L) ((progress.toDouble() / duration.toDouble()) * 100.0).roundToInt().coerceIn(0, 100) else 0
}
