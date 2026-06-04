package com.selxo.rougo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

internal data class YoutubeResolutionOption(val key: String, val label: String)
internal data class AccentOption(val key: String, val label: String, val darkColor: Color, val lightColor: Color)
enum class LibraryDownloadState { Idle, Loading, Complete }
internal const val PREF_YOUTUBE_RESOLUTION = "youtube_preferred_resolution"
internal const val PREF_YOUTUBE_AUTO_SUBTITLES = "youtube_auto_subtitles"
internal const val PREF_YOUTUBE_SUBTITLE_LANGUAGE = "youtube_subtitle_language"
internal const val PREF_SKIP_SECONDS = "player_skip_seconds"
internal const val PREF_SUBTITLE_OFFSET_MS = "subtitle_offset_ms"
internal const val PREF_SAVE_REPEAT_RECORDINGS = "save_repeat_recordings"
internal const val PREF_LIGHT_MODE = "app_light_mode"
internal const val PREF_THEME_MODE = "app_theme_mode"
internal const val PREF_ACCENT_COLOR = "app_accent_color"
internal const val DEFAULT_SKIP_SECONDS = 5
internal const val DEFAULT_SUBTITLE_OFFSET_MS = 0L
internal const val THEME_DARK = "dark"
internal const val THEME_BLACK = "black"
internal const val THEME_LIGHT = "light"
internal const val THEME_SYSTEM = "system"
internal const val YOUTUBE_RESOLUTION_ASK = "ask"
internal const val YOUTUBE_RESOLUTION_HIGHEST = "highest"
internal const val YOUTUBE_RESOLUTION_AUDIO = "audio"
internal const val DEFAULT_YOUTUBE_RESOLUTION = "720"
internal const val DEFAULT_YOUTUBE_SUBTITLE_LANGUAGE = "ja"
internal const val PLAYER_NOTIFICATION_CHANNEL_ID = "rougo_player_controls"
internal const val PLAYER_NOTIFICATION_ID = 2407
internal const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "rougo_downloads"
internal const val DOWNLOAD_NOTIFICATION_ID_BASE = 3400
internal const val ACTION_PLAYER_PLAY_PAUSE = "com.selxo.rougo.action.PLAY_PAUSE"
internal const val ACTION_PLAYER_REWIND = "com.selxo.rougo.action.REWIND"
internal const val ACTION_PLAYER_FORWARD = "com.selxo.rougo.action.FORWARD"
internal const val ACTION_PLAYER_STOP = "com.selxo.rougo.action.STOP"
internal val YOUTUBE_RESOLUTION_OPTIONS = listOf(
    YoutubeResolutionOption("720", "720p"),
    YoutubeResolutionOption(YOUTUBE_RESOLUTION_ASK, "Ask every time"),
    YoutubeResolutionOption("480", "480p"),
    YoutubeResolutionOption("1080", "1080p"),
    YoutubeResolutionOption(YOUTUBE_RESOLUTION_HIGHEST, "Highest available"),
    YoutubeResolutionOption(YOUTUBE_RESOLUTION_AUDIO, "Audio only")
)
internal val YOUTUBE_SUBTITLE_LANGUAGE_OPTIONS = listOf(
    YoutubeResolutionOption("ja", "Japanese"),
    YoutubeResolutionOption("en", "English"),
    YoutubeResolutionOption("zh-Hant", "Chinese (Traditional)"),
    YoutubeResolutionOption("zh-Hans", "Chinese (Simplified)"),
    YoutubeResolutionOption("ko", "Korean"),
    YoutubeResolutionOption("es", "Spanish"),
    YoutubeResolutionOption("any", "Best available")
)
internal val THEME_MODE_OPTIONS = listOf(
    YoutubeResolutionOption(THEME_DARK, "Dark"),
    YoutubeResolutionOption(THEME_BLACK, "Black (OLED)"),
    YoutubeResolutionOption(THEME_LIGHT, "Light"),
    YoutubeResolutionOption(THEME_SYSTEM, "System")
)
internal val ACCENT_OPTIONS = listOf(
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
internal val PLAYER_NOTIFICATION_ACTIONS = arrayOf(
    ACTION_PLAYER_PLAY_PAUSE,
    ACTION_PLAYER_REWIND,
    ACTION_PLAYER_FORWARD,
    ACTION_PLAYER_STOP
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
internal fun rougoColorScheme(themeMode: String, accentKey: String, systemDark: Boolean): androidx.compose.material3.ColorScheme {
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
